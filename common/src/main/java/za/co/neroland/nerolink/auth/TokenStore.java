package za.co.neroland.nerolink.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.nerolink.NeroLinkCommon;

/**
 * Persistent device-token store — the authentication backbone of the bridge. One row per
 * paired device, keyed by an opaque {@code deviceId}, holding:
 * <ul>
 *   <li>the SHA-256 <b>hash</b> of the bearer token (never the token itself),</li>
 *   <li>the owning player's UUID (every API response is scoped to this),</li>
 *   <li>a human {@code deviceName}, {@code createdAt} and {@code lastSeenAt}.</li>
 * </ul>
 *
 * <p><b>POPIA/GDPR.</b> Tokens are stored hashed only, so a leaked save file cannot be
 * replayed. {@link #forget(UUID)} drops every device for a player and is wired into Core's
 * {@code PlayerDataErasure} hook. Nothing here is logged at info with player identity.
 *
 * <p>Persistence mirrors Core's {@code LinkAlerts}/{@code ProgressionState} pattern exactly
 * ({@link SavedDataType} + Codec on the overworld data storage), keeping all storage in the
 * loader-neutral common module.
 *
 * <p><b>Threading.</b> Authentication runs on Netty I/O threads and the relay thread while the
 * game thread saves the world, so the device table is a {@link ConcurrentHashMap} (weakly
 * consistent iteration — no {@code ConcurrentModificationException} during autosave) and every
 * mutation is {@code synchronized}. The bridge resolves the instance once on the server thread
 * at start ({@link #get(MinecraftServer)} touches the level's data storage, which is not
 * thread-safe) and hands that reference to the I/O layer.
 *
 * <p><b>Retention.</b> Devices expire after {@code tokenExpiryDays} of inactivity and have an
 * absolute lifetime cap; {@link #sweepExpired} runs at bridge start and periodically, so
 * abandoned devices are deleted even if they never call again.
 */
public final class TokenStore extends SavedData {

    /** 32 random bytes, base64url — the opaque token handed to the client once. */
    public static final int TOKEN_BYTES = 32;

    /** Absolute maximum token lifetime regardless of activity (re-pair once a year). */
    public static final long MAX_LIFETIME_MILLIS = 365L * 24 * 60 * 60 * 1000;

    /** lastSeenAt is only persisted when it moves by at least this much (avoids a dirty save per request). */
    private static final long LAST_SEEN_GRANULARITY_MILLIS = 60_000L;

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroLinkCommon.MOD_ID, "tokens");

    public static final SavedDataType<TokenStore> TYPE =
            new SavedDataType<>(ID, TokenStore::new, codec(), null);

    /** deviceId -> device row. */
    private final Map<String, Device> byDevice = new ConcurrentHashMap<>();

    public TokenStore() {
    }

    public static TokenStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** A stored device pairing. {@code tokenHash} is the SHA-256 of the bearer token. */
    public record Device(String deviceId, String tokenHash, UUID player,
                         String deviceName, long createdAt, long lastSeenAt) {
    }

    /** The freshly minted token plus its device row (token returned to the client once). */
    public record Issued(String token, Device device) {
    }

    /**
     * Create a new device pairing for a player. Generates a random opaque token, stores only
     * its hash, and returns the plaintext token exactly once (for the client to persist).
     */
    public synchronized Issued issue(MinecraftServer server, UUID player, String deviceName) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String deviceId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Device device = new Device(deviceId, sha256(token), player,
                sanitizeName(deviceName), now, now);
        byDevice.put(deviceId, device);
        setDirty();
        return new Issued(token, device);
    }

    /**
     * Resolve a bearer token to its (non-expired) device, bumping {@code lastSeenAt}.
     * Returns empty if unknown, revoked or expired past the inactivity window.
     */
    public synchronized Optional<Device> authenticate(MinecraftServer server, String token, long expiryMillis) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256(token);
        for (Device d : byDevice.values()) {
            if (constantTimeEquals(d.tokenHash(), hash)) {
                long now = System.currentTimeMillis();
                if (isExpired(d, now, expiryMillis)) {
                    // Reject only; the retention sweep deletes it (and closes its socket and
                    // unbinds its relay push), so nothing about the device is left behind.
                    return Optional.empty();
                }
                if (now - d.lastSeenAt() < LAST_SEEN_GRANULARITY_MILLIS) {
                    return Optional.of(d);
                }
                Device bumped = new Device(d.deviceId(), d.tokenHash(), d.player(),
                        d.deviceName(), d.createdAt(), now);
                byDevice.put(d.deviceId(), bumped);
                setDirty();
                return Optional.of(bumped);
            }
        }
        return Optional.empty();
    }

    private static boolean isExpired(Device d, long now, long expiryMillis) {
        if (expiryMillis > 0 && now - d.lastSeenAt() > expiryMillis) {
            return true;
        }
        return now - d.createdAt() > MAX_LIFETIME_MILLIS;
    }

    /**
     * Retention sweep: delete every device past its inactivity window or absolute lifetime.
     *
     * @return the removed devices (so callers can close their sockets / unbind push)
     */
    public synchronized List<Device> sweepExpired(long expiryMillis) {
        long now = System.currentTimeMillis();
        List<Device> removed = new ArrayList<>();
        for (Device d : byDevice.values()) {
            if (isExpired(d, now, expiryMillis)) {
                removed.add(d);
            }
        }
        removed.forEach(d -> byDevice.remove(d.deviceId()));
        if (!removed.isEmpty()) {
            setDirty();
        }
        return removed;
    }

    /** Total paired devices across all players (op status; no personal data). */
    public int deviceCount() {
        return byDevice.size();
    }

    /** Look up a device row by id. */
    public Optional<Device> device(String deviceId) {
        return Optional.ofNullable(byDevice.get(deviceId));
    }

    /** Devices belonging to a player, most-recently-seen first (metadata only). */
    public List<Device> devicesOf(UUID player) {
        List<Device> out = new ArrayList<>();
        for (Device d : byDevice.values()) {
            if (d.player().equals(player)) {
                out.add(d);
            }
        }
        out.sort((a, b) -> Long.compare(b.lastSeenAt(), a.lastSeenAt()));
        return out;
    }

    /** Revoke by device id. @return true if it existed. */
    public synchronized boolean revoke(String deviceId) {
        if (byDevice.remove(deviceId) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    /** Revoke by the presented bearer token (used by DELETE /session). @return true if matched. */
    public synchronized boolean revokeByToken(String token) {
        if (token == null) {
            return false;
        }
        String hash = sha256(token);
        String match = null;
        for (Device d : byDevice.values()) {
            if (constantTimeEquals(d.tokenHash(), hash)) {
                match = d.deviceId();
                break;
            }
        }
        return match != null && revoke(match);
    }

    /** POPIA/GDPR erasure: remove every device for a player. */
    public synchronized void forget(UUID player) {
        boolean changed = byDevice.values().removeIf(d -> d.player().equals(player));
        if (changed) {
            setDirty();
        }
    }

    // --- hashing helpers -------------------------------------------------------------

    /** SHA-256 hex of a token. Never store or log the plaintext token. */
    public static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "device";
        }
        String trimmed = name.strip();
        return trimmed.length() > 48 ? trimmed.substring(0, 48) : trimmed;
    }

    // --- persistence (SavedDataType + Codec, same pattern as Core LinkAlerts) --------

    private record Row(String deviceId, String tokenHash, String player,
                       String deviceName, long createdAt, long lastSeenAt) {
        static final Codec<Row> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.STRING.fieldOf("device_id").forGetter(Row::deviceId),
                Codec.STRING.fieldOf("token_hash").forGetter(Row::tokenHash),
                Codec.STRING.fieldOf("player").forGetter(Row::player),
                Codec.STRING.optionalFieldOf("device_name", "device").forGetter(Row::deviceName),
                Codec.LONG.fieldOf("created_at").forGetter(Row::createdAt),
                Codec.LONG.optionalFieldOf("last_seen_at", 0L).forGetter(Row::lastSeenAt)
        ).apply(inst, Row::new));

        static Row of(Device d) {
            return new Row(d.deviceId(), d.tokenHash(), d.player().toString(),
                    d.deviceName(), d.createdAt(), d.lastSeenAt());
        }
    }

    private static Codec<TokenStore> codec() {
        return RecordCodecBuilder.create(inst -> inst.group(
                Row.CODEC.listOf().optionalFieldOf("devices", List.of()).forGetter(TokenStore::rows)
        ).apply(inst, TokenStore::fromRows));
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        byDevice.values().forEach(d -> out.add(Row.of(d)));
        out.sort((a, b) -> Long.compare(a.createdAt(), b.createdAt()));
        return out;
    }

    private static TokenStore fromRows(List<Row> rows) {
        TokenStore store = new TokenStore();
        for (Row row : rows) {
            UUID uuid;
            try {
                uuid = UUID.fromString(row.player());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            store.byDevice.put(row.deviceId(), new Device(row.deviceId(), row.tokenHash(), uuid,
                    row.deviceName(), row.createdAt(), row.lastSeenAt()));
        }
        return store;
    }
}

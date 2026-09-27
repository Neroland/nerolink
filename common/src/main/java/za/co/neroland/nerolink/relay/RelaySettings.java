package za.co.neroland.nerolink.relay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.google.gson.JsonObject;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.nerolink.NeroLinkCommon;

/**
 * Per-world relay registration produced by {@code /nerolink setup}. Holds the credentials a
 * successful {@code POST /register} returned from a NeroLink relay so the tunnel can be brought
 * up on every subsequent server start without re-registering:
 * <ul>
 *   <li>{@code relayOrigin} — the relay base origin this registration belongs to,</li>
 *   <li>{@code serverId} — the short id players enter in the companion app,</li>
 *   <li>{@code serverKey} — the tunnel bearer secret (never logged, never shown in chat),</li>
 *   <li>{@code tunnelUrl} — the {@code wss://.../tunnel/<serverId>} the bridge dials,</li>
 *   <li>{@code baseUrl} — the {@code https://.../s/<serverId>} app URL,</li>
 *   <li>{@code registeredAt} — epoch millis of registration.</li>
 * </ul>
 *
 * <p><b>Player data.</b> The credential row is server-scoped. The only player data is the
 * <i>outbound erasure queue</i>: when a player is erased (or a device revoked) while the relay
 * tunnel is down, a tombstone {@code erase:<uuid>} / {@code unbind:<uuid>:<deviceId>} is kept here
 * until the tunnel reconnects and delivers it, then removed. That is the minimum needed for the
 * erasure to reach the relay at all (POPIA s24 / GDPR Art. 17(2)); entries are capped and never
 * logged. The {@code serverKey} is the only secret and is never emitted to logs or chat.
 *
 * <p>Persistence mirrors {@link za.co.neroland.nerolink.auth.TokenStore} exactly
 * ({@link SavedDataType} + Codec on the overworld data storage), keeping storage loader-neutral.
 */
public final class RelaySettings extends SavedData {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroLinkCommon.MOD_ID, "relay");

    public static final SavedDataType<RelaySettings> TYPE =
            new SavedDataType<>(ID, RelaySettings::new, codec(), null);

    private String relayOrigin;
    private String serverId;
    private String serverKey; // secret — never logged, never shown in chat
    private String tunnelUrl;
    private String baseUrl;
    private long registeredAt;
    /** Undelivered relay instructions: {@code erase:<uuid>} or {@code unbind:<uuid>:<deviceId>}. */
    private final List<String> pendingOps = new ArrayList<>();

    /** Bound on the queue so a long-offline relay cannot grow the save without limit. */
    static final int MAX_PENDING_OPS = 4096;

    public RelaySettings() {
        this("", "", "", "", "", 0L, List.of());
    }

    private RelaySettings(String relayOrigin, String serverId, String serverKey,
                          String tunnelUrl, String baseUrl, long registeredAt, List<String> pendingOps) {
        this.pendingOps.addAll(pendingOps == null ? List.of() : pendingOps);
        this.relayOrigin = relayOrigin == null ? "" : relayOrigin;
        this.serverId = serverId == null ? "" : serverId;
        this.serverKey = serverKey == null ? "" : serverKey;
        this.tunnelUrl = tunnelUrl == null ? "" : tunnelUrl;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.registeredAt = registeredAt;
    }

    public static RelaySettings get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // --- state -----------------------------------------------------------------------

    /** True when a usable registration (id + key + tunnel) is stored. */
    public boolean isRegistered() {
        return !serverId.isBlank() && !serverKey.isBlank() && !tunnelUrl.isBlank();
    }

    /** True when a usable registration is stored for the given relay origin (origin-insensitive). */
    public boolean isRegisteredFor(String origin) {
        return isRegistered() && normalizeOrigin(relayOrigin).equals(normalizeOrigin(origin));
    }

    /** Persist a fresh registration (call on the server thread). */
    public void set(String relayOrigin, String serverId, String serverKey,
                    String tunnelUrl, String baseUrl, long registeredAt) {
        this.relayOrigin = relayOrigin == null ? "" : relayOrigin.trim();
        this.serverId = serverId == null ? "" : serverId.trim();
        this.serverKey = serverKey == null ? "" : serverKey.trim();
        this.tunnelUrl = tunnelUrl == null ? "" : tunnelUrl.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.registeredAt = registeredAt;
        setDirty();
    }

    /** Discard the stored registration (used by {@code /nerolink setup force}). */
    public void clear() {
        this.relayOrigin = "";
        this.serverId = "";
        this.serverKey = "";
        this.tunnelUrl = "";
        this.baseUrl = "";
        this.registeredAt = 0L;
        setDirty();
    }

    // --- outbound erasure queue ------------------------------------------------------

    /** Queue a player-erasure tombstone for the relay (their push registrations). */
    public synchronized void queueErase(UUID player) {
        // An erase supersedes any queued unbinds for the same player.
        String prefix = "unbind:" + player + ":";
        pendingOps.removeIf(op -> op.startsWith(prefix));
        addOp("erase:" + player);
    }

    /** Queue a single-device push unbind for the relay. */
    public synchronized void queueUnbind(UUID player, String deviceId) {
        if (deviceId.indexOf(':') >= 0) {
            return;
        }
        addOp("unbind:" + player + ":" + deviceId);
    }

    private void addOp(String op) {
        if (pendingOps.contains(op)) {
            return;
        }
        if (pendingOps.size() >= MAX_PENDING_OPS) {
            pendingOps.remove(0);
        }
        pendingOps.add(op);
        setDirty();
    }

    /** Snapshot of the queue, oldest first. */
    public synchronized List<String> pendingRelayOps() {
        return List.copyOf(pendingOps);
    }

    public synchronized void removeRelayOp(String op) {
        if (pendingOps.remove(op)) {
            setDirty();
        }
    }

    /** Build the tunnel frame for a queued op, or null if it is malformed. */
    public static JsonObject opFrame(String op) {
        String[] parts = op.split(":", 3);
        try {
            if (parts.length == 2 && parts[0].equals("erase")) {
                JsonObject f = new JsonObject();
                f.addProperty("t", "erase");
                f.addProperty("playerUuid", UUID.fromString(parts[1]).toString());
                return f;
            }
            if (parts.length == 3 && parts[0].equals("unbind")) {
                JsonObject f = new JsonObject();
                f.addProperty("t", "push_unbind");
                f.addProperty("playerUuid", UUID.fromString(parts[1]).toString());
                f.addProperty("deviceId", parts[2]);
                return f;
            }
        } catch (IllegalArgumentException ignored) {
            // fall through: malformed entry is dropped by the caller
        }
        return null;
    }

    // --- getters ---------------------------------------------------------------------

    public String relayOrigin() {
        return relayOrigin;
    }

    public String serverId() {
        return serverId;
    }

    /** The tunnel bearer secret. Callers MUST NOT log or display this. */
    public String serverKey() {
        return serverKey;
    }

    public String tunnelUrl() {
        return tunnelUrl;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public long registeredAt() {
        return registeredAt;
    }

    /** Lower-case, trailing-slash-stripped origin for equality checks. */
    static String normalizeOrigin(String origin) {
        if (origin == null) {
            return "";
        }
        String s = origin.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // --- persistence (SavedDataType + Codec, same pattern as TokenStore) -------------

    private static Codec<RelaySettings> codec() {
        return RecordCodecBuilder.create(inst -> inst.group(
                Codec.STRING.optionalFieldOf("relay_origin", "").forGetter(RelaySettings::relayOrigin),
                Codec.STRING.optionalFieldOf("server_id", "").forGetter(RelaySettings::serverId),
                Codec.STRING.optionalFieldOf("server_key", "").forGetter(RelaySettings::serverKey),
                Codec.STRING.optionalFieldOf("tunnel_url", "").forGetter(RelaySettings::tunnelUrl),
                Codec.STRING.optionalFieldOf("base_url", "").forGetter(RelaySettings::baseUrl),
                Codec.LONG.optionalFieldOf("registered_at", 0L).forGetter(RelaySettings::registeredAt),
                Codec.STRING.listOf().optionalFieldOf("pending_relay_ops", List.of())
                        .forGetter(RelaySettings::pendingRelayOps)
        ).apply(inst, RelaySettings::new));
    }
}

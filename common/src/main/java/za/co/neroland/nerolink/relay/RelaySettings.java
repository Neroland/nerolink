package za.co.neroland.nerolink.relay;

import java.util.Locale;

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
 * <p><b>Not player data.</b> This is a single server-scoped credential row — there is no player
 * UUID here, so it is outside Core's per-player erasure hook. The {@code serverKey} is the only
 * secret; it is treated like {@code TokenStore}'s hashes and is never emitted to logs or chat.
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

    public RelaySettings() {
        this("", "", "", "", "", 0L);
    }

    private RelaySettings(String relayOrigin, String serverId, String serverKey,
                          String tunnelUrl, String baseUrl, long registeredAt) {
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
                Codec.LONG.optionalFieldOf("registered_at", 0L).forGetter(RelaySettings::registeredAt)
        ).apply(inst, RelaySettings::new));
    }
}

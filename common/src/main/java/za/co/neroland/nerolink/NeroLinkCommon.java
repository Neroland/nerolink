package za.co.neroland.nerolink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.nerolandcore.data.SavedDataRecovery;
import za.co.neroland.nerolink.auth.TokenStore;
import za.co.neroland.nerolink.config.NeroLinkConfig;
import za.co.neroland.nerolink.platform.InstalledMods;
import za.co.neroland.nerolink.prefs.PrefsStore;

/**
 * Loader-agnostic entry point for NeroLink — the companion-app bridge. Each loader entry point
 * (Fabric / Forge / NeoForge) calls {@link #init()} once during mod construction to register
 * config and the shared per-player erasure hook, then wires the server-start / server-stop and
 * command events to {@link NeroLinkBridge} and {@link za.co.neroland.nerolink.command.NeroLinkCommand}.
 *
 * <p>The Netty HTTP + WebSocket server itself is started on server-start (a live world exists)
 * and stopped on server-stop, so nothing binds a socket at mod-construction time.
 */
public final class NeroLinkCommon {

    public static final String MOD_ID = "nerolink";
    /**
     * Fallback bridge version, used only until the loader has populated {@link InstalledMods}
     * (and in unit tests). Keep in step with {@code mod_version} in gradle.properties; at runtime
     * {@link #bridgeVersion()} reports the loader's own metadata so the two can never drift.
     */
    public static final String BRIDGE_VERSION = "0.0.0-dev";
    public static final Logger LOGGER = LoggerFactory.getLogger("NeroLink");

    private NeroLinkCommon() {
    }

    /** The running NeroLink version from loader metadata (falls back to {@link #BRIDGE_VERSION}). */
    public static String bridgeVersion() {
        return installedVersion(MOD_ID).orElse(BRIDGE_VERSION);
    }

    /** The version of an installed Nero mod as the loader reports it, if present. */
    public static java.util.Optional<String> installedVersion(String modId) {
        for (InstalledMods.Entry e : InstalledMods.entries()) {
            if (e.id().equalsIgnoreCase(modId) && e.version() != null && !e.version().isBlank()) {
                return java.util.Optional.of(e.version());
            }
        }
        return java.util.Optional.empty();
    }

    /** Called once per loader during mod construction. Registers config + erasure. */
    public static void init() {
        LOGGER.info("[NeroLink] common init");
        NeroLinkConfig.register();
        registerErasure();
    }

    /**
     * Wire NeroLink's per-player data into Core's shared erasure hook (POPIA/GDPR). One erasure
     * request purges this player's device tokens, notification prefs and any pending pairing
     * code — alongside every other mod's data.
     */
    private static void registerErasure() {
        PlayerDataErasure.register(NeroLinkCommon::eraseBridgeData);
    }

    /**
     * Purge everything NeroLink holds about one player: device tokens (and their rate-limit
     * buckets), notification prefs, any pending pairing code and live sockets, plus a relay
     * tombstone for their push registrations. The tombstone is persisted and re-sent when the
     * relay tunnel next connects, so erasure also reaches the relay when it is offline right now.
     * Runs on the server thread.
     */
    public static void eraseBridgeData(net.minecraft.server.MinecraftServer server, java.util.UUID uuid) {
        TokenStore tokens = TokenStore.get(server);
        java.util.List<TokenStore.Device> devices = tokens.devicesOf(uuid);
        tokens.forget(uuid);
        // Refresh each store's last-known-good backup at once so the erasure reaches Core's
        // backup copy too (SavedDataRecovery contract), not just the primary file.
        SavedDataRecovery.backupNow(server.overworld(), TokenStore.TYPE, tokens, TokenStore.ID.toString());
        PrefsStore prefs = PrefsStore.get(server);
        prefs.forget(uuid);
        SavedDataRecovery.backupNow(server.overworld(), PrefsStore.TYPE, prefs, PrefsStore.ID.toString());
        if (NeroLinkBridge.resolveRelayCredentials(server) != null) {
            // Only servers that use a relay can have push registrations there. The tombstone is
            // backed up too, so a restore from backup still delivers the erasure to the relay.
            za.co.neroland.nerolink.relay.RelaySettings relay =
                    za.co.neroland.nerolink.relay.RelaySettings.get(server);
            relay.queueErase(uuid);
            SavedDataRecovery.backupNow(server.overworld(), za.co.neroland.nerolink.relay.RelaySettings.TYPE,
                    relay, za.co.neroland.nerolink.relay.RelaySettings.ID.toString());
        }
        NeroLinkBridge bridge = NeroLinkBridge.instance();
        if (bridge != null) {
            bridge.pairing().forget(uuid);
            bridge.wsHub().disconnectPlayer(uuid);
            devices.forEach(d -> bridge.rateLimiter().forget(d.deviceId()));
            bridge.relay().flushPendingErasures();
        }
    }
}

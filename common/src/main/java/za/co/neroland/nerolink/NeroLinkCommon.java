package za.co.neroland.nerolink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.nerolink.auth.TokenStore;
import za.co.neroland.nerolink.config.NeroLinkConfig;
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
    public static final String BRIDGE_VERSION = "0.0.1-alpha.1";
    public static final Logger LOGGER = LoggerFactory.getLogger("NeroLink");

    private NeroLinkCommon() {
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
        PlayerDataErasure.register((server, uuid) -> {
            TokenStore.get(server).forget(uuid);
            PrefsStore.get(server).forget(uuid);
            NeroLinkBridge bridge = NeroLinkBridge.instance();
            if (bridge != null) {
                bridge.pairing().forget(uuid);
                bridge.wsHub().disconnectPlayer(uuid);
                bridge.rateLimiter().clear();
                // POPIA/GDPR: tombstone the player's push tokens on the relay too (no-op if offline).
                bridge.relay().sendErase(uuid);
            }
        });
    }
}

package za.co.neroland.nerolink;

import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.link.LinkEvent;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;
import za.co.neroland.nerolink.api.ApiDispatcher;
import za.co.neroland.nerolink.api.RequestDedup;
import za.co.neroland.nerolink.auth.PairingService;
import za.co.neroland.nerolink.auth.TokenStore;
import za.co.neroland.nerolink.config.NeroLinkConfig;
import za.co.neroland.nerolink.coremodule.CoreModule;
import za.co.neroland.nerolink.http.HttpBridgeServer;
import za.co.neroland.nerolink.ratelimit.RateLimiter;
import za.co.neroland.nerolink.relay.RelayClient;
import za.co.neroland.nerolink.relay.RelaySettings;
import za.co.neroland.nerolink.ws.WebSocketHub;

/**
 * The live bridge for one running server. Created on server-start and torn down on
 * server-stop by the loader glue via {@link #start(MinecraftServer)} / {@link #stop()}.
 * Owns the Netty server, the auth/rate-limit services, the WebSocket hub and the API
 * dispatcher, and bridges Core's {@link za.co.neroland.nerolandcore.link.LinkEventBus} to
 * connected sockets.
 *
 * <p>Everything the Netty threads touch here is either immutable service state or explicitly
 * marshalled onto the server thread (see {@link ApiDispatcher}); no game state is read or
 * mutated off-thread.
 */
public final class NeroLinkBridge {

    private static volatile NeroLinkBridge instance;

    private final MinecraftServer server;
    private final PairingService pairing = new PairingService();
    private final RateLimiter rateLimiter = new RateLimiter();
    private final RequestDedup dedup = new RequestDedup();
    private final WebSocketHub wsHub = new WebSocketHub();
    private final ApiDispatcher dispatcher;
    private final HttpBridgeServer httpServer;
    private final RelayClient relay;
    private final Consumer<LinkEvent> eventSubscriber;

    private NeroLinkBridge(MinecraftServer server) {
        this.server = server;
        this.dispatcher = new ApiDispatcher(this);
        this.httpServer = new HttpBridgeServer(this);
        this.relay = new RelayClient(this);
        // Forward Core's link events to the WebSocket hub (player-scoped or broadcast).
        this.eventSubscriber = wsHub::onLinkEvent;
    }

    /** Start the bridge for a server (if enabled in config). Idempotent per server run. */
    public static synchronized void start(MinecraftServer server) {
        if (instance != null) {
            return;
        }
        if (!NeroLinkConfig.ENABLED.get()) {
            NeroLinkCommon.LOGGER.info("[NeroLink] bridge disabled by config; not binding a socket.");
            return;
        }
        NeroLinkBridge bridge = new NeroLinkBridge(server);
        // Register the built-in `core` module (gates/alerts/energy/storage + ack_alert).
        CoreModule.register();
        NeroLinkRegistry.eventBus().subscribe(bridge.eventSubscriber);
        bridge.wsHub.attach(server);
        // The local HTTP/WS listener and the outbound relay tunnel are independent — either, both,
        // or neither may run. A failure to bind the local socket must not stop the relay.
        try {
            bridge.httpServer.start(NeroLinkConfig.BIND_ADDRESS.get(), NeroLinkConfig.PORT.get());
            NeroLinkCommon.LOGGER.info("[NeroLink] bridge listening on {}:{}",
                    NeroLinkConfig.BIND_ADDRESS.get(), NeroLinkConfig.PORT.get());
        } catch (Exception e) {
            NeroLinkCommon.LOGGER.error("[NeroLink] failed to start local bridge server", e);
        }
        // Start the relay tunnel from resolved credentials (config override else RelaySettings).
        // Host-only logging lives inside RelayClient; a null result leaves the tunnel disabled.
        RelayCreds creds = resolveRelayCredentials(server);
        if (creds != null) {
            bridge.relay.start(creds.url(), creds.key());
        }
        instance = bridge;
    }

    /** Resolved relay tunnel credentials: a {@code wss://} URL and its bearer key. */
    public record RelayCreds(String url, String key) {
    }

    /**
     * Resolve the relay credentials to bring the tunnel up with. Precedence:
     * <ol>
     *   <li>config {@code relayUrl} + {@code relayKey} when BOTH are set — a manual override;</li>
     *   <li>otherwise the {@link RelaySettings} written by {@code /nerolink setup}.</li>
     * </ol>
     * Returns {@code null} when neither source is configured (tunnel stays disabled). Never logs.
     */
    public static RelayCreds resolveRelayCredentials(MinecraftServer server) {
        String cfgUrl = NeroLinkConfig.RELAY_URL.get();
        String cfgKey = NeroLinkConfig.RELAY_KEY.get();
        if (cfgUrl != null && !cfgUrl.isBlank() && cfgKey != null && !cfgKey.isBlank()) {
            return new RelayCreds(cfgUrl.trim(), cfgKey.trim());
        }
        RelaySettings settings = RelaySettings.get(server);
        if (settings.isRegistered()) {
            return new RelayCreds(settings.tunnelUrl(), settings.serverKey());
        }
        return null;
    }

    /**
     * (Re)connect the relay tunnel with fresh credentials at runtime, without a server restart.
     * Called by {@code /nerolink setup} on the server thread after persisting a registration.
     */
    public void restartRelay(String tunnelUrl, String serverKey) {
        relay.restart(tunnelUrl, serverKey);
    }

    /** Stop the bridge (server stopping). Releases the socket and event loops. */
    public static synchronized void stop() {
        NeroLinkBridge bridge = instance;
        instance = null;
        if (bridge == null) {
            return;
        }
        NeroLinkRegistry.eventBus().unsubscribe(bridge.eventSubscriber);
        bridge.relay.stop();
        bridge.wsHub.shutdown();
        bridge.rateLimiter.clear();
        try {
            bridge.httpServer.stop();
        } catch (Exception e) {
            NeroLinkCommon.LOGGER.warn("[NeroLink] error stopping bridge server", e);
        }
        NeroLinkCommon.LOGGER.info("[NeroLink] bridge stopped.");
    }

    /** The live bridge, or null if not running. */
    public static NeroLinkBridge instance() {
        return instance;
    }

    // --- accessors for the request handlers ------------------------------------------

    public MinecraftServer server() {
        return server;
    }

    public PairingService pairing() {
        return pairing;
    }

    public TokenStore tokens() {
        return TokenStore.get(server);
    }

    public RateLimiter rateLimiter() {
        return rateLimiter;
    }

    public RequestDedup dedup() {
        return dedup;
    }

    public WebSocketHub wsHub() {
        return wsHub;
    }

    public ApiDispatcher dispatcher() {
        return dispatcher;
    }

    public RelayClient relay() {
        return relay;
    }
}

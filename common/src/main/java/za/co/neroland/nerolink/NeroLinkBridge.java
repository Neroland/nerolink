package za.co.neroland.nerolink;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
import za.co.neroland.nerolink.tls.BridgeTls;
import za.co.neroland.nerolink.ws.WebSocketHub;

/**
 * The live bridge for one running server. Created on server-start and torn down on
 * server-stop by the loader glue via {@link #start(MinecraftServer)} / {@link #stop()}.
 * Owns the Netty server, the auth/rate-limit services, the WebSocket hub and the API
 * dispatcher, and bridges Core's {@link za.co.neroland.nerolandcore.link.LinkEventBus} to
 * connected sockets.
 *
 * <p>Threading: the Netty and relay threads touch only thread-safe service state — the
 * {@link TokenStore} (resolved once on the server thread at start and internally synchronized),
 * the pairing/rate-limit/dedup services and the hub. Everything that reads or writes other game
 * state is marshalled onto the server thread (see {@link ApiDispatcher}).
 */
public final class NeroLinkBridge {

    private static volatile NeroLinkBridge instance;

    /** How often expired devices are swept (retention; POPIA/GDPR storage limitation). */
    private static final long SWEEP_INTERVAL_HOURS = 6;

    private final MinecraftServer server;
    private final TokenStore tokens;
    /** Codes pre-compute their direct-mode proof against the current TLS identity (null = no TLS). */
    private final PairingService pairing = new PairingService(() -> {
        BridgeTls current = this.tls;
        return current == null ? null : current.fingerprint();
    });
    private final RateLimiter rateLimiter = new RateLimiter();
    private final RequestDedup dedup = new RequestDedup();
    private final WebSocketHub wsHub = new WebSocketHub();
    private final ApiDispatcher dispatcher;
    private final HttpBridgeServer httpServer;
    private final RelayClient relay;
    private final Consumer<LinkEvent> eventSubscriber;
    private volatile BridgeTls tls;
    private volatile DirectEndpoint direct = DirectEndpoint.OFF;
    private ScheduledExecutorService housekeeping;

    /** Where (and whether) the direct-mode listener is reachable — for status and the pairing whisper. */
    public record DirectEndpoint(boolean running, String bindAddress, int port, boolean tls, String reason) {
        static final DirectEndpoint OFF = new DirectEndpoint(false, "", 0, false, "disabled");

        public boolean loopbackOnly() {
            return running && isLoopback(bindAddress);
        }
    }

    private NeroLinkBridge(MinecraftServer server) {
        this.server = server;
        // Resolve SavedData on the server thread (start() runs on it); the I/O threads get this reference.
        this.tokens = TokenStore.get(server);
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
        // Publish the instance first: relay/listener requests may arrive as soon as either starts.
        instance = bridge;
        // Register the built-in `core` module (gates/alerts/energy/storage + ack_alert).
        CoreModule.register();
        NeroLinkRegistry.eventBus().subscribe(bridge.eventSubscriber);
        bridge.wsHub.attach(server);
        bridge.sweepExpiredDevices();
        bridge.startHousekeeping();
        // The local listener and the outbound relay tunnel are independent — either, both, or
        // neither may run. A failure to bind the local socket must not stop the relay.
        bridge.startDirect();
        // Start the relay tunnel from resolved credentials (config override else RelaySettings).
        // Host-only logging lives inside RelayClient; a null result leaves the tunnel disabled.
        RelayCreds creds = resolveRelayCredentials(server);
        if (creds != null) {
            bridge.relay.start(creds.url(), creds.key());
        }
    }

    /**
     * Bring up the direct-mode listener according to config. Single-player worlds bind loopback
     * unless {@code singleplayerLanAccess}; plain HTTP is only ever allowed on loopback.
     */
    private void startDirect() {
        if (!NeroLinkConfig.DIRECT_ENABLED.get()) {
            direct = new DirectEndpoint(false, "", 0, false, "disabled in config (relay only)");
            NeroLinkCommon.LOGGER.info("[NeroLink] direct-mode listener disabled by config; relay only.");
            return;
        }
        String bind = NeroLinkConfig.BIND_ADDRESS.get();
        if (bind == null || bind.isBlank()) {
            bind = "0.0.0.0";
        }
        if (!server.isDedicatedServer() && !NeroLinkConfig.SINGLEPLAYER_LAN_ACCESS.get()) {
            bind = "127.0.0.1";
        }
        int port = NeroLinkConfig.PORT.get();
        boolean useTls = NeroLinkConfig.TLS_ENABLED.get();
        if (!useTls && !isLoopback(bind)) {
            direct = new DirectEndpoint(false, bind, port, false,
                    "refused: tlsEnabled=false requires bindAddress=127.0.0.1");
            NeroLinkCommon.LOGGER.error("[NeroLink] refusing to serve plain HTTP on {} - set tlsEnabled=true, "
                    + "or bindAddress=127.0.0.1 behind your own TLS proxy. Direct mode is OFF; the relay is unaffected.",
                    bind);
            return;
        }
        try {
            if (useTls) {
                tls = BridgeTls.loadOrCreate(tlsFile());
            }
            httpServer.start(bind, port, tls);
            direct = new DirectEndpoint(true, bind, port, useTls, "");
            if (useTls) {
                NeroLinkCommon.LOGGER.info("[NeroLink] direct mode listening on {}:{} (TLS, certificate {})",
                        bind, port, tls.shortFingerprint());
            } else {
                NeroLinkCommon.LOGGER.warn("[NeroLink] direct mode listening on {}:{} WITHOUT TLS (loopback only; "
                        + "put a TLS-terminating proxy in front before exposing it).", bind, port);
            }
        } catch (Exception e) {
            direct = new DirectEndpoint(false, bind, port, useTls, "failed to start: " + e.getClass().getSimpleName());
            NeroLinkCommon.LOGGER.error("[NeroLink] failed to start the direct-mode listener on {}:{}", bind, port, e);
        }
    }

    private static Path tlsFile() {
        return za.co.neroland.nerolandcore.platform.Services.PLATFORM.getConfigDir()
                .resolve("nerolink").resolve("bridge-tls.p12");
    }

    static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "localhost".equalsIgnoreCase(address) || "::1".equals(address);
    }

    private void startHousekeeping() {
        housekeeping = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nerolink-housekeeping");
            t.setDaemon(true);
            return t;
        });
        housekeeping.scheduleAtFixedRate(() -> server.execute(this::sweepExpiredDevices),
                SWEEP_INTERVAL_HOURS, SWEEP_INTERVAL_HOURS, TimeUnit.HOURS);
        housekeeping.scheduleAtFixedRate(dedup::prune, 10, 10, TimeUnit.MINUTES);
        housekeeping.scheduleAtFixedRate(rateLimiter::prune, 10, 10, TimeUnit.MINUTES);
    }

    /** Retention sweep: drop devices past their inactivity window / lifetime, close their sockets. */
    private void sweepExpiredDevices() {
        long expiryMillis = NeroLinkConfig.TOKEN_EXPIRY_DAYS.get() * 24L * 60 * 60 * 1000;
        List<TokenStore.Device> removed = tokens.sweepExpired(expiryMillis);
        for (TokenStore.Device d : removed) {
            onDeviceRemoved(d);
        }
        if (!removed.isEmpty()) {
            NeroLinkCommon.LOGGER.info("[NeroLink] retention sweep removed {} expired device(s)", removed.size());
        }
    }

    /** A device was revoked, expired or unpaired: close its live socket and unbind its relay push. */
    public void onDeviceRemoved(TokenStore.Device device) {
        wsHub.disconnectDevice(device.deviceId());
        rateLimiter.forget(device.deviceId());
        relay.sendPushUnbind(device.player(), device.deviceId());
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
        if (bridge.housekeeping != null) {
            bridge.housekeeping.shutdownNow();
        }
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
        return tokens;
    }

    /** The TLS identity, or null when the direct listener runs without TLS / is off. */
    public BridgeTls tls() {
        return tls;
    }

    public DirectEndpoint direct() {
        return direct;
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

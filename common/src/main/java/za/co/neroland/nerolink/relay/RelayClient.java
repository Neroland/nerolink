package za.co.neroland.nerolink.relay;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import za.co.neroland.nerolandcore.link.NeroLinkRegistry;
import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.auth.TokenStore;
import za.co.neroland.nerolink.config.NeroLinkConfig;
import za.co.neroland.nerolink.http.ApiErrors;
import za.co.neroland.nerolink.http.ApiRequest;
import za.co.neroland.nerolink.http.ApiResponse;
import za.co.neroland.nerolink.http.Json;
import za.co.neroland.nerolink.prefs.PrefsStore;

/**
 * The bridge's outbound relay tunnel: ONE persistent WebSocket to a NeroLink relay
 * ({@code wss://<relay>/tunnel/<serverId>}, {@code Authorization: Bearer <serverKey>}) over
 * which all companion-client traffic for a NAT'd server is multiplexed as JSON text frames.
 *
 * <p>The wire protocol (see {@code nerolink-relay/src/protocol.ts}) is symmetric with the local
 * bridge: a {@code req} frame is run through the very same {@link za.co.neroland.nerolink.api.ApiDispatcher}
 * as a local REST call, and a {@code ws_open}/{@code ws_msg}/{@code ws_close} triplet is handled
 * exactly like a local WebSocket, via a relay-backed {@link RelayWsConnection} registered with the
 * {@link za.co.neroland.nerolink.ws.WebSocketHub}. Outbound {@code ws_msg}/{@code ws_close} frames
 * are emitted by the hub through that virtual connection; {@code notify}/{@code erase} frames are
 * pushed by {@link NotifyForwarder} and the erasure hook.
 *
 * <p><b>Threading.</b> Inbound frames are decoded on the relay client's Netty event loop; any game
 * work is marshalled onto the server thread by the dispatcher and the response is sent from whatever
 * thread completes it. {@link #send(String)} is therefore thread-safe — Netty's {@code writeAndFlush}
 * hands the write to the channel's event loop.
 *
 * <p><b>Privacy (POPIA/GDPR).</b> The relay key, bearer tokens and player identifiers are never
 * logged. Only the relay <em>host</em> appears in lifecycle logs.
 */
public final class RelayClient {

    /** Connection state surfaced to {@code /nerolink status}. */
    public enum State {
        DISABLED, CONNECTING, CONNECTED
    }

    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final long PING_INTERVAL_S = 30L;
    /** Handshake HTTP response is tiny; frames are decoded by the WS codec, not this aggregator. */
    private static final int MAX_HTTP_BYTES = 64 * 1024;
    /** Generous WS frame cap for large snapshot frames flowing to a relay-backed client. */
    private static final int MAX_FRAME_BYTES = 1024 * 1024;

    private final NeroLinkBridge bridge;
    private final NotifyForwarder notifyForwarder;

    /** cid -> relay-backed virtual connection. */
    private final Map<String, RelayWsConnection> connections = new ConcurrentHashMap<>();

    private volatile boolean running;
    private volatile State state = State.DISABLED;

    private URI uri;
    private String host = "";
    private int port;
    private boolean secure;
    private String key = "";           // never logged
    private SslContext sslContext;

    private NioEventLoopGroup group;
    private volatile Channel channel;
    private volatile long backoffMs = INITIAL_BACKOFF_MS;
    private volatile ScheduledFuture<?> pingTask;

    public RelayClient(NeroLinkBridge bridge) {
        this.bridge = bridge;
        this.notifyForwarder = new NotifyForwarder();
    }

    // --- lifecycle -------------------------------------------------------------------

    /**
     * Start the tunnel to {@code relayUrl} authenticated with {@code relayKey}. No-op if either is
     * blank or already running. Logs the relay host only (never the key or full URL).
     */
    public synchronized void start(String relayUrl, String relayKey) {
        if (running) {
            return;
        }
        if (relayUrl == null || relayUrl.isBlank() || relayKey == null || relayKey.isBlank()) {
            state = State.DISABLED;
            return;
        }
        try {
            this.uri = new URI(relayUrl.trim());
        } catch (URISyntaxException e) {
            NeroLinkCommon.LOGGER.warn("[NeroLink] relay disabled: relayUrl is not a valid URL.");
            state = State.DISABLED;
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        this.secure = scheme.equals("wss") || scheme.equals("https");
        if (!secure && !scheme.equals("ws") && !scheme.equals("http")) {
            NeroLinkCommon.LOGGER.warn("[NeroLink] relay disabled: relayUrl scheme must be wss:// or ws://.");
            state = State.DISABLED;
            return;
        }
        this.host = uri.getHost();
        if (host == null || host.isBlank()) {
            NeroLinkCommon.LOGGER.warn("[NeroLink] relay disabled: relayUrl has no host.");
            state = State.DISABLED;
            return;
        }
        this.port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
        this.key = relayKey;

        if (secure) {
            try {
                this.sslContext = SslContextBuilder.forClient().build();
            } catch (Exception e) {
                NeroLinkCommon.LOGGER.warn("[NeroLink] relay disabled: could not init TLS.", e);
                state = State.DISABLED;
                return;
            }
        }

        this.group = new NioEventLoopGroup(1, daemonThreads("nerolink-relay"));
        this.running = true;
        this.state = State.CONNECTING;
        this.backoffMs = INITIAL_BACKOFF_MS;
        NeroLinkRegistry.eventBus().subscribe(notifyForwarder);
        NeroLinkCommon.LOGGER.info("[NeroLink] relay tunnel enabled; connecting to host {}.", host);
        connect();
    }

    /**
     * (Re)connect the tunnel with fresh credentials at runtime — used by {@code /nerolink setup}
     * after a successful registration so no server restart is needed. Tears down any existing
     * connection first, then starts anew. Synchronized like {@link #start} / {@link #stop} and
     * safe to call from the server thread. Logs the relay host only (never the key).
     */
    public synchronized void restart(String relayUrl, String relayKey) {
        if (running) {
            stop();
        }
        start(relayUrl, relayKey);
    }

    /** Stop the tunnel, drop every relay-backed client and release the event loop. */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        state = State.DISABLED;
        NeroLinkRegistry.eventBus().unsubscribe(notifyForwarder);
        stopPing();
        Channel ch = channel;
        channel = null;
        if (ch != null) {
            ch.close();
        }
        dropAllConnections();
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
        NeroLinkCommon.LOGGER.info("[NeroLink] relay tunnel stopped.");
    }

    /** Current connection state (for status output). */
    public State state() {
        return state;
    }

    /** Whether the tunnel is up and frames can be sent. */
    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    // --- connection management -------------------------------------------------------

    private void connect() {
        if (!running) {
            return;
        }
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (secure && sslContext != null) {
                            ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), host, port));
                        }
                        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                                uri, WebSocketVersion.V13, null, true, authHeaders(), MAX_FRAME_BYTES);
                        ch.pipeline()
                                .addLast(new HttpClientCodec())
                                .addLast(new HttpObjectAggregator(MAX_HTTP_BYTES))
                                .addLast(new WebSocketClientProtocolHandler(handshaker))
                                .addLast(new RelayInboundHandler());
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                channel = future.channel();
                channel.closeFuture().addListener(closed -> onDisconnected());
            } else {
                NeroLinkCommon.LOGGER.debug("[NeroLink] relay TCP connect to host {} failed", host, future.cause());
                scheduleReconnect();
            }
        });
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new DefaultHttpHeaders();
        // The relay authenticates the tunnel by Bearer server key. Never logged.
        headers.add(HttpHeaderNames.AUTHORIZATION, "Bearer " + key);
        return headers;
    }

    /** Handshake finished — the tunnel is live. */
    private void onHandshakeComplete() {
        state = State.CONNECTED;
        backoffMs = INITIAL_BACKOFF_MS;
        startPing();
        NeroLinkCommon.LOGGER.info("[NeroLink] relay tunnel connected (host {}).", host);
    }

    private void onDisconnected() {
        stopPing();
        channel = null;
        dropAllConnections();
        if (running) {
            state = State.CONNECTING;
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running || group == null || group.isShuttingDown()) {
            return;
        }
        long delay = backoffMs;
        backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
        NeroLinkCommon.LOGGER.debug("[NeroLink] relay reconnecting to host {} in {} ms", host, delay);
        try {
            group.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // group shutting down; nothing to do
        }
    }

    private void startPing() {
        stopPing();
        if (group == null) {
            return;
        }
        pingTask = group.scheduleAtFixedRate(() -> {
            Channel ch = channel;
            if (ch != null && ch.isActive() && state == State.CONNECTED) {
                ch.writeAndFlush(new PingWebSocketFrame());
            }
        }, PING_INTERVAL_S, PING_INTERVAL_S, TimeUnit.SECONDS);
    }

    private void stopPing() {
        ScheduledFuture<?> task = pingTask;
        pingTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private void dropAllConnections() {
        for (RelayWsConnection conn : connections.values()) {
            bridge.wsHub().unregister(conn.connectionId());
        }
        connections.clear();
    }

    // --- outbound frames (thread-safe) -----------------------------------------------

    /** Send a raw JSON text frame to the relay. Safe to call from any thread. */
    public void send(String frame) {
        Channel ch = channel;
        if (ch != null && ch.isActive() && state == State.CONNECTED) {
            ch.writeAndFlush(new TextWebSocketFrame(frame));
        }
    }

    /** Forward a hub frame to a relay-backed client: {@code {t:"ws_msg", cid, data}}. */
    void sendWsMsg(String cid, String data) {
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "ws_msg");
        frame.addProperty("cid", cid);
        frame.addProperty("data", data);
        send(Json.toString(frame));
    }

    /** Tell the relay to close a relay-backed client and forget it locally. */
    void closeVirtual(String cid, int code, String reason) {
        connections.remove(cid);
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "ws_close");
        frame.addProperty("cid", cid);
        frame.addProperty("code", code);
        frame.addProperty("reason", reason == null ? "" : reason);
        send(Json.toString(frame));
    }

    private void sendRes(String id, int status, String body) {
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "res");
        frame.addProperty("id", id);
        frame.addProperty("status", status);
        frame.addProperty("body", body);
        send(Json.toString(frame));
    }

    /**
     * Emit a POPIA/GDPR tombstone so the relay drops this player's push tokens. Called from the
     * bridge's {@code PlayerDataErasure} hook. No-op when the tunnel is down. The uuid is never logged.
     */
    public void sendErase(UUID playerUuid) {
        if (!isConnected() || playerUuid == null) {
            return;
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "erase");
        frame.addProperty("playerUuid", playerUuid.toString());
        send(Json.toString(frame));
    }

    // --- inbound frame handling ------------------------------------------------------

    private void onFrame(String raw) {
        JsonObject frame;
        try {
            frame = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            return; // garbage frame; ignore defensively
        }
        if (frame == null || !frame.has("t") || frame.get("t").isJsonNull()) {
            return;
        }
        switch (frame.get("t").getAsString()) {
            case "req" -> onReq(frame);
            case "ws_open" -> onWsOpen(frame);
            case "ws_msg" -> onWsMsg(frame);
            case "ws_close" -> onWsClose(frame);
            default -> { /* forward-compat: ignore unknown frame types */ }
        }
    }

    private void onReq(JsonObject frame) {
        String id = optString(frame, "id");
        if (id == null) {
            return;
        }
        String method = optString(frame, "method");
        String path = optString(frame, "path");
        if (method == null || path == null) {
            sendRes(id, 400, Json.toString(Json.error(ApiErrors.VALIDATION, "malformed req frame")));
            return;
        }
        String auth = optString(frame, "auth");
        String bodyRaw = optString(frame, "body");

        QueryStringDecoder decoder = new QueryStringDecoder(path);
        String p = decoder.path();
        final String prefix = "/api/v1";
        if (!p.equals(prefix) && !p.startsWith(prefix + "/")) {
            sendRes(id, 404, Json.toString(Json.error(ApiErrors.NOT_FOUND, "unknown path")));
            return;
        }
        List<String> segments = splitSegments(p.substring(prefix.length()));
        Map<String, String> query = new java.util.LinkedHashMap<>();
        decoder.parameters().forEach((k, v) -> {
            if (!v.isEmpty()) {
                query.put(k, v.get(0));
            }
        });
        JsonObject body = parseBody(bodyRaw);
        String bearer = bearerFrom(auth);

        ApiRequest request = new ApiRequest(method, segments, query, body, bearer);
        bridge.dispatcher().handle(request).whenComplete((response, error) -> {
            ApiResponse resp = error != null
                    ? ApiResponse.error(500, ApiErrors.INTERNAL, "internal error")
                    : response;
            // Reply from whatever thread completed the future; send() marshals onto the event loop.
            sendRes(id, resp.status(), Json.toString(resp.body()));
        });
    }

    private void onWsOpen(JsonObject frame) {
        String cid = optString(frame, "cid");
        if (cid == null) {
            return;
        }
        String token = bearerFrom(optString(frame, "auth"));
        long expiryMillis = NeroLinkConfig.TOKEN_EXPIRY_DAYS.get() * 24L * 60 * 60 * 1000;
        Optional<TokenStore.Device> auth = bridge.tokens()
                .authenticate(bridge.server(), token, expiryMillis);
        if (auth.isEmpty()) {
            // Mirror the local WS upgrade's 401 with the relay's unauthorized close code.
            JsonObject close = new JsonObject();
            close.addProperty("t", "ws_close");
            close.addProperty("cid", cid);
            close.addProperty("code", 4401);
            close.addProperty("reason", "unauthorized");
            send(Json.toString(close));
            return;
        }
        TokenStore.Device device = auth.get();
        RelayWsConnection conn = new RelayWsConnection(this, cid, device.player(), device.deviceId());
        connections.put(cid, conn);
        // Registering enforces the one-WS-per-token rule for relay clients too (replaces any prior).
        bridge.wsHub().register(conn);
    }

    private void onWsMsg(JsonObject frame) {
        String cid = optString(frame, "cid");
        String data = optString(frame, "data");
        if (cid == null || data == null) {
            return;
        }
        RelayWsConnection conn = connections.get(cid);
        if (conn == null) {
            return;
        }
        try {
            JsonObject control = JsonParser.parseString(data).getAsJsonObject();
            bridge.wsHub().onControlFrame(conn.connectionId(), control);
        } catch (Exception e) {
            NeroLinkCommon.LOGGER.debug("[NeroLink] bad relay ws control frame", e);
        }
    }

    private void onWsClose(JsonObject frame) {
        String cid = optString(frame, "cid");
        if (cid == null) {
            return;
        }
        RelayWsConnection conn = connections.remove(cid);
        if (conn != null) {
            bridge.wsHub().unregister(conn.connectionId());
        }
    }

    // --- notify fan-out --------------------------------------------------------------

    /**
     * Pushes {@code notify} frames for player-scoped Core link events whose category (the module id)
     * the player has opted into, but only when that player is not currently watching via any live WS
     * client (they would already see the delta). Payload contents are never included (privacy).
     */
    private final class NotifyForwarder implements java.util.function.Consumer<za.co.neroland.nerolandcore.link.LinkEvent> {
        @Override
        public void accept(za.co.neroland.nerolandcore.link.LinkEvent event) {
            if (!isConnected() || event == null || event.isBroadcast()) {
                return;
            }
            UUID player = event.playerId();
            if (player == null) {
                return;
            }
            String category = event.moduleId();
            if (!PrefsStore.get(bridge.server()).isEnabled(player, category)) {
                return;
            }
            if (bridge.wsHub().isPlayerConnected(player)) {
                return; // watching live; a push would be redundant
            }
            // TODO: richer per-topic copy. Deliberately plain and payload-free for privacy.
            String title = "NeroLink — " + event.moduleId();
            String body = event.topic() + " update";
            JsonObject frame = new JsonObject();
            frame.addProperty("t", "notify");
            frame.addProperty("playerUuid", player.toString());
            frame.addProperty("category", category);
            frame.addProperty("title", title);
            frame.addProperty("body", body);
            send(Json.toString(frame));
        }
    }

    // --- Netty inbound handler -------------------------------------------------------

    private final class RelayInboundHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
            onFrame(frame.text());
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                onHandshakeComplete();
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            NeroLinkCommon.LOGGER.debug("[NeroLink] relay channel error", cause);
            ctx.close();
        }
    }

    // --- helpers ---------------------------------------------------------------------

    private static String bearerFrom(String authHeader) {
        if (authHeader == null) {
            return null;
        }
        String prefix = "Bearer ";
        return authHeader.startsWith(prefix) ? authHeader.substring(prefix.length()).trim() : null;
    }

    private static JsonObject parseBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> splitSegments(String path) {
        List<String> out = new java.util.ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}

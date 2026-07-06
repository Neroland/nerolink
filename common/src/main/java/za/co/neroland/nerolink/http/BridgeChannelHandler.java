package za.co.neroland.nerolink.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;

import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.auth.TokenStore;
import za.co.neroland.nerolink.config.NeroLinkConfig;
import za.co.neroland.nerolink.ws.WsConnection;

/**
 * The bridge's Netty inbound handler. Distinguishes a WebSocket upgrade at {@code /ws/v1} from a
 * plain REST call under {@code /api/v1/...}. REST requests are decoded here (off the game thread)
 * and delegated to the {@link za.co.neroland.nerolink.api.ApiDispatcher}, which marshals any
 * game work onto the server thread and completes the response future asynchronously — this
 * handler just writes the completed envelope back on the I/O thread.
 *
 * <p>WebSocket upgrades are Bearer-authenticated before the handshake; each authenticated socket
 * becomes a {@link WsConnection} registered with the {@link za.co.neroland.nerolink.ws.WebSocketHub}.
 */
public final class BridgeChannelHandler extends SimpleChannelInboundHandler<Object> {

    private static final String WS_PATH = "/ws/v1";
    private static final String API_PREFIX = "/api/v1/";

    private final NeroLinkBridge bridge;
    private WebSocketServerHandshaker handshaker;
    private HubConnection wsConnection;

    public BridgeChannelHandler(NeroLinkBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest request) {
            handleHttp(ctx, request);
        } else if (msg instanceof WebSocketFrame frame) {
            handleWebSocketFrame(ctx, frame);
        }
    }

    // --- HTTP ------------------------------------------------------------------------

    private void handleHttp(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        QueryStringDecoder decoder = new QueryStringDecoder(uri);
        String path = decoder.path();

        if (path.equals(WS_PATH)) {
            handleWebSocketUpgrade(ctx, request);
            return;
        }
        if (!path.startsWith(API_PREFIX)) {
            writeJson(ctx, HttpResponseStatus.NOT_FOUND,
                    Json.error(ApiErrors.NOT_FOUND, "unknown path"), request);
            return;
        }

        // Decode into a transport-neutral ApiRequest (no game state touched here).
        List<String> segments = splitSegments(path.substring(API_PREFIX.length()));
        Map<String, String> query = new java.util.LinkedHashMap<>();
        decoder.parameters().forEach((k, v) -> {
            if (!v.isEmpty()) {
                query.put(k, v.get(0));
            }
        });
        JsonObject body = parseBody(request);
        String bearer = bearerToken(request);
        boolean keepAlive = HttpUtil.isKeepAlive(request);

        ApiRequest apiRequest = new ApiRequest(request.method().name(), segments, query, body, bearer);

        bridge.dispatcher().handle(apiRequest).whenComplete((response, error) -> {
            ApiResponse resp = error != null
                    ? ApiResponse.error(500, ApiErrors.INTERNAL, "internal error")
                    : response;
            // Write back on the channel's event loop.
            ctx.executor().execute(() -> writeApiResponse(ctx, resp, keepAlive));
        });
    }

    private void writeApiResponse(ChannelHandlerContext ctx, ApiResponse resp, boolean keepAlive) {
        byte[] bytes = Json.toString(resp.body()).getBytes(StandardCharsets.UTF_8);
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(resp.status()),
                Unpooled.wrappedBuffer(bytes));
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        httpResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        if (keepAlive) {
            httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(httpResponse);
        } else {
            httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, JsonObject body,
                           FullHttpRequest request) {
        byte[] bytes = Json.toString(body).getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    // --- WebSocket -------------------------------------------------------------------

    private void handleWebSocketUpgrade(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method() != HttpMethod.GET) {
            writeJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    Json.error(ApiErrors.VALIDATION, "ws upgrade must be GET"), request);
            return;
        }
        // Authenticate the upgrade via Bearer token.
        String token = bearerToken(request);
        long expiryMillis = NeroLinkConfig.TOKEN_EXPIRY_DAYS.get() * 24L * 60 * 60 * 1000;
        Optional<TokenStore.Device> auth = bridge.tokens()
                .authenticate(bridge.server(), token, expiryMillis);
        if (auth.isEmpty()) {
            writeJson(ctx, HttpResponseStatus.UNAUTHORIZED,
                    Json.error(ApiErrors.UNAUTHORIZED, "invalid bearer token"), request);
            return;
        }
        TokenStore.Device device = auth.get();

        WebSocketServerHandshakerFactory factory =
                new WebSocketServerHandshakerFactory(wsLocation(request), null, true);
        handshaker = factory.newHandshaker(request);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return;
        }
        handshaker.handshake(ctx.channel(), request).addListener(future -> {
            if (future.isSuccess()) {
                wsConnection = new HubConnection(ctx, device.player(), device.deviceId());
                bridge.wsHub().register(wsConnection);
            }
        });
    }

    private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame close) {
            if (handshaker != null) {
                handshaker.close(ctx.channel(), (CloseWebSocketFrame) close.retain());
            }
            return;
        }
        if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        if (frame instanceof PongWebSocketFrame) {
            return; // heartbeat ack
        }
        if (frame instanceof TextWebSocketFrame text && wsConnection != null) {
            try {
                JsonObject control = JsonParser.parseString(text.text()).getAsJsonObject();
                bridge.wsHub().onControlFrame(wsConnection.connectionId(), control);
            } catch (Exception e) {
                NeroLinkCommon.LOGGER.debug("[NeroLink] bad ws control frame", e);
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (wsConnection != null) {
            bridge.wsHub().unregister(wsConnection.connectionId());
            wsConnection = null;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        NeroLinkCommon.LOGGER.debug("[NeroLink] channel error", cause);
        ctx.close();
    }

    // --- helpers ---------------------------------------------------------------------

    /** A {@link WsConnection} backed by a Netty channel, sending frames on the channel's loop. */
    private static final class HubConnection implements WsConnection {
        private final ChannelHandlerContext ctx;
        private final UUID player;
        private final String deviceId;

        HubConnection(ChannelHandlerContext ctx, UUID player, String deviceId) {
            this.ctx = ctx;
            this.player = player;
            this.deviceId = deviceId;
        }

        @Override
        public UUID player() {
            return player;
        }

        @Override
        public String connectionId() {
            return deviceId;
        }

        @Override
        public void send(String json) {
            if (ctx.channel().isActive()) {
                ctx.channel().writeAndFlush(new TextWebSocketFrame(json));
            }
        }

        @Override
        public void close() {
            if (ctx.channel().isActive()) {
                ctx.channel().writeAndFlush(new CloseWebSocketFrame())
                        .addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    private static String bearerToken(FullHttpRequest request) {
        String header = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (header == null) {
            return null;
        }
        String prefix = "Bearer ";
        return header.startsWith(prefix) ? header.substring(prefix.length()).trim() : null;
    }

    private static JsonObject parseBody(FullHttpRequest request) {
        if (request.content().readableBytes() == 0) {
            return null;
        }
        String raw = request.content().toString(StandardCharsets.UTF_8);
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> splitSegments(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    private static String wsLocation(FullHttpRequest request) {
        String host = request.headers().get(HttpHeaderNames.HOST);
        return "ws://" + (host == null ? "localhost" : host) + WS_PATH;
    }
}

package za.co.neroland.nerolink.http;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.tls.BridgeTls;

/**
 * The embedded Netty HTTPS + WebSocket server (direct mode), on its own dedicated event-loop
 * groups so bridge I/O never contends with Minecraft's own Netty stack. Started on server-start
 * and stopped on server-stop by {@link NeroLinkBridge}.
 *
 * <p>Minecraft bundles Netty (its network stack, including {@code netty-handler}'s TLS support),
 * so {@code io.netty} resolves from the game classpath — no extra dependency is shipped. The
 * pipeline is: connection cap → TLS (the bridge's self-signed, app-pinned certificate) → idle
 * timeout → HTTP codec + aggregator → {@link BridgeChannelHandler}. Requests are decoded on the
 * I/O thread; anything touching game state is marshalled to the server thread by the dispatcher.
 */
public final class HttpBridgeServer {

    /** Cap aggregated HTTP bodies; the bridge only ever receives small JSON control payloads. */
    private static final int MAX_CONTENT_LENGTH = 16 * 1024;
    /** Close connections with no traffic in either direction for this long (WS pings every 30s). */
    private static final int IDLE_SECONDS = 90;
    /** Concurrent TCP connections allowed from one address (slow-loris / exhaustion guard). */
    private static final int MAX_CONNECTIONS_PER_ADDRESS = 16;

    private final NeroLinkBridge bridge;
    private final Map<InetAddress, AtomicInteger> perAddress = new ConcurrentHashMap<>();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private boolean tlsActive;

    public HttpBridgeServer(NeroLinkBridge bridge) {
        this.bridge = bridge;
    }

    /** Whether the listener is serving TLS (drives {@code wss://} in upgrade responses). */
    public boolean tlsActive() {
        return tlsActive;
    }

    /**
     * Bind the socket. Throws if the port is unavailable.
     *
     * @param tls the bridge identity to serve, or null for plain HTTP (loopback-only; enforced by the caller)
     */
    public void start(String bindAddress, int port, BridgeTls tls) throws Exception {
        SslContext sslContext = tls == null ? null
                : SslContextBuilder.forServer(tls.privateKey(), tls.certificate())
                        .sslProvider(SslProvider.JDK)
                        .protocols("TLSv1.3", "TLSv1.2")
                        .build();
        this.tlsActive = sslContext != null;

        bossGroup = new NioEventLoopGroup(1, namedThreads("nerolink-boss"));
        workerGroup = new NioEventLoopGroup(2, namedThreads("nerolink-io"));

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        InetAddress remote = remoteAddress(ch);
                        if (remote != null && !acquire(remote)) {
                            ch.close();
                            return;
                        }
                        if (remote != null) {
                            ch.closeFuture().addListener(f -> release(remote));
                        }
                        if (sslContext != null) {
                            ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                        }
                        ch.pipeline()
                                .addLast(new IdleStateHandler(0, 0, IDLE_SECONDS, TimeUnit.SECONDS))
                                .addLast(new IdleCloser())
                                .addLast(new HttpServerCodec(4096, 8192, 8192))
                                .addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH))
                                .addLast(new BridgeChannelHandler(bridge, remote, sslContext != null));
                    }
                });

        serverChannel = bootstrap.bind(bindAddress, port).sync().channel();
    }

    private boolean acquire(InetAddress address) {
        AtomicInteger count = perAddress.computeIfAbsent(address, a -> new AtomicInteger());
        if (count.incrementAndGet() > MAX_CONNECTIONS_PER_ADDRESS) {
            count.decrementAndGet();
            return false;
        }
        return true;
    }

    private void release(InetAddress address) {
        perAddress.computeIfPresent(address, (a, c) -> c.decrementAndGet() <= 0 ? null : c);
    }

    private static InetAddress remoteAddress(SocketChannel ch) {
        InetSocketAddress addr = ch.remoteAddress();
        return addr == null ? null : addr.getAddress();
    }

    /** Close the socket and wait (briefly) for both event-loop groups so the port is free for the next world. */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(3, TimeUnit.SECONDS);
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(3, TimeUnit.SECONDS);
            workerGroup = null;
        }
        perAddress.clear();
        NeroLinkCommon.LOGGER.debug("[NeroLink] Netty groups shut down");
    }

    /** Closes a connection once {@link IdleStateHandler} reports it idle. */
    private static final class IdleCloser extends ChannelDuplexHandler {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                ctx.close();
                return;
            }
            super.userEventTriggered(ctx, evt);
        }
    }

    private static java.util.concurrent.ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}

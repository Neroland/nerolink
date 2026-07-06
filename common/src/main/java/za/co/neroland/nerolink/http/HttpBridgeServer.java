package za.co.neroland.nerolink.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.NeroLinkCommon;

/**
 * The embedded Netty HTTP + WebSocket server, on its own dedicated event-loop groups so bridge
 * I/O never contends with Minecraft's own Netty stack. Started on server-start and stopped on
 * server-stop by {@link NeroLinkBridge}.
 *
 * <p>Minecraft bundles Netty (its network stack), so {@code io.netty} resolves from the game
 * classpath — no extra dependency is shipped. The pipeline is the standard HTTP-with-optional-
 * WS-upgrade: codec + aggregator + our {@link BridgeChannelHandler}. Requests are decoded on the
 * I/O thread; anything touching game state is marshalled to the server thread inside the handler.
 */
public final class HttpBridgeServer {

    /** Cap aggregated HTTP bodies; the bridge only ever receives small JSON control payloads. */
    private static final int MAX_CONTENT_LENGTH = 256 * 1024;

    private final NeroLinkBridge bridge;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public HttpBridgeServer(NeroLinkBridge bridge) {
        this.bridge = bridge;
    }

    /** Bind the socket. Throws if the port is unavailable. */
    public void start(String bindAddress, int port) throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1, namedThreads("nerolink-boss"));
        workerGroup = new NioEventLoopGroup(namedThreads("nerolink-io"));

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH))
                                .addLast(new BridgeChannelHandler(bridge));
                    }
                });

        serverChannel = bootstrap.bind(bindAddress, port).sync().channel();
    }

    /** Close the socket and shut down both event-loop groups. */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        NeroLinkCommon.LOGGER.debug("[NeroLink] Netty groups shut down");
    }

    private static java.util.concurrent.ThreadFactory namedThreads(String prefix) {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}

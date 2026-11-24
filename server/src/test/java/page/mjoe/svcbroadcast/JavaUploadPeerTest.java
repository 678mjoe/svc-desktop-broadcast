package page.mjoe.svcbroadcast;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import page.mjoe.svcbroadcast.sender.JavaUpload;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Failure paths that the production loopback server cannot provide on purpose. */
@Timeout(20)
class JavaUploadPeerTest {
    private static final String DEVICE_PATH = "/v1/device/audio";

    @TempDir Path directory;

    @Test void neverReadyDoesNotOpenCaptureAndSendsStop() throws Exception {
        try (var peer = new LocalPeer(Behavior.NO_READY)) {
            assertEquals("ready_timeout", assertThrows(JavaUpload.Failure.class, peer.upload()::run).code());
            assertTrue(peer.started.await(2, TimeUnit.SECONDS));
            assertTrue(peer.stopped.await(2, TimeUnit.SECONDS));
            assertTrue(peer.closed.await(2, TimeUnit.SECONDS));
        }
    }

    @Test void refusedUpgradeWithUnfinishedBodyStillReleasesTheConnection() throws Exception {
        try (var peer = new LocalPeer(Behavior.UNFINISHED_HTTP)) {
            assertEquals("connection", assertThrows(JavaUpload.Failure.class, peer.upload()::run).code());
            assertEquals(1, peer.requests.get());
            assertTrue(peer.closed.await(2, TimeUnit.SECONDS));
        }
    }

    @Test void upgradeDoesNotFollowRedirects() throws Exception {
        try (var peer = new LocalPeer(Behavior.REDIRECT)) {
            assertEquals("redirect_refused", assertThrows(JavaUpload.Failure.class, peer.upload()::run).code());
            assertEquals(1, peer.requests.get());
            assertTrue(peer.closed.await(2, TimeUnit.SECONDS));
        }
    }

    @Test void oversizedFragmentedControlNeverOpensCapture() throws Exception {
        try (var peer = new LocalPeer(Behavior.OVERSIZED_READY)) {
            assertEquals("invalid_control", assertThrows(JavaUpload.Failure.class, peer.upload()::run).code());
            assertTrue(peer.closed.await(2, TimeUnit.SECONDS));
        }
    }

    private enum Behavior { NO_READY, OVERSIZED_READY, UNFINISHED_HTTP, REDIRECT }

    private final class LocalPeer implements AutoCloseable {
        private final SSLContext trust;
        private final Channel listener;
        private final NioEventLoopGroup loop;
        private final AtomicInteger requests = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);

        LocalPeer(Behavior behavior) throws Exception {
            var identity = TlsIdentity.load(directory, "127.0.0.1", Clock.systemUTC());
            trust = DeviceIngressTest.trust(identity.caPem());
            loop = new NioEventLoopGroup(1);
            try {
                listener = new ServerBootstrap().group(loop).channel(NioServerSocketChannel.class)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override protected void initChannel(SocketChannel channel) {
                                channel.closeFuture().addListener(future -> closed.countDown());
                                channel.pipeline().addLast(identity.context.newHandler(channel.alloc()),
                                        new HttpServerCodec(), new HttpObjectAggregator(4096));
                                if (behavior == Behavior.NO_READY || behavior == Behavior.OVERSIZED_READY) {
                                    channel.pipeline().addLast(new WebSocketServerProtocolHandler(DEVICE_PATH),
                                            new SimpleChannelInboundHandler<TextWebSocketFrame>() {
                                                @Override protected void channelRead0(ChannelHandlerContext ctx,
                                                                                      TextWebSocketFrame frame) {
                                                    if (frame.text().startsWith("{\"type\":\"start\"")) {
                                                        started.countDown();
                                                        if (behavior == Behavior.OVERSIZED_READY) {
                                                            ctx.write(new TextWebSocketFrame(false, 0, "x".repeat(1024)));
                                                            ctx.writeAndFlush(new ContinuationWebSocketFrame(true, 0, "y"));
                                                        }
                                                    }
                                                    if (frame.text().equals("{\"type\":\"stop\"}")) stopped.countDown();
                                                }
                                            });
                                } else {
                                    channel.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                        @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                                            requests.incrementAndGet();
                                            if (behavior == Behavior.UNFINISHED_HTTP) {
                                                var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                                                        HttpResponseStatus.UNAUTHORIZED);
                                                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 32);
                                                ctx.writeAndFlush(response);
                                            } else {
                                                var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                        HttpResponseStatus.FOUND);
                                                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
                                                response.headers().set(HttpHeaderNames.LOCATION, DEVICE_PATH + "?redirected");
                                                ctx.writeAndFlush(response);
                                            }
                                        }
                                    });
                                }
                            }
                        }).bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
            } catch (Exception failure) {
                loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
                throw failure;
            }
        }

        JavaUpload upload() {
            int port = ((InetSocketAddress) listener.localAddress()).getPort();
            return new JavaUpload(URI.create("wss://127.0.0.1:" + port + DEVICE_PATH), trust, "a".repeat(64),
                    () -> fail("Capture opened without ready"), state -> {});
        }

        @Override public void close() throws Exception {
            listener.close().sync();
            loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}

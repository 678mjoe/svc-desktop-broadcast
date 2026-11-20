package page.mjoe.svcbroadcast;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.HashMap;
import java.util.Base64;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Separate loopback maintenance and configurable TLS device ingress; no blocking Minecraft IO. */
public final class AudioIngress implements AutoCloseable {
    public interface Host {
        boolean start(UUID sessionId, PcmStream stream, Runnable onStopped);
        void stop(UUID sessionId);
        default boolean start(UUID id, PcmStream stream, Runnable stopped, BroadcastAuthorization.Principal principal) {
            return start(id, stream, stopped);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AudioIngress.class);
    private static final int MAX_CLIENTS = 4;
    private static final String OFFLINE_PAIRING_DISABLED = "The server owner can enable offline-mode game pairing "
            + "with allow_offline_pairing=true in config/svc-broadcast/tls.properties and restart.";

    static final class OfflinePairingDisabled extends IllegalStateException {
        OfflinePairingDisabled() { super(OFFLINE_PAIRING_DISABLED); }
    }
    private final Path configDirectory;
    private final int requestedPort;
    private final Host host;
    private final LongSupplier clock;
    private final ExecutorService control = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "svc-broadcast-ingress-control");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.concurrent.Semaphore controlSlots = new java.util.concurrent.Semaphore(48);
    private boolean enqueue(Runnable operation) {
        if (!controlSlots.tryAcquire()) return false;
        try { control.execute(()-> {try {operation.run();} finally {controlSlots.release();}});return true; }
        catch (RejectedExecutionException e) {controlSlots.release();return false;}
    }
    private final ChannelGroup connections = new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE);
    private final AtomicInteger connectionCount = new AtomicInteger();
    // Only the single Netty event loop mutates this set or changes the active connection.
    private final Set<Client> clients = new HashSet<>();
    private final CompletableFuture<Integer> started = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private volatile IngressToken token;
    private volatile Client active;
    private volatile boolean closing;
    private volatile int boundPort = -1;
    private volatile String state = "not started";
    private volatile String lastStream = "no stream";
    private volatile SetupReport startupIssue;
    private boolean startRequested;
    private boolean rotationQueued;
    // Listener and event-loop ownership is confined to the control executor.
    private NioEventLoopGroup network;
    private Channel listener;
    private volatile Channel tlsListener;
    private volatile TlsIdentity identity;
    private volatile BroadcastAuthorization authorization;
    private volatile Set<UUID> online = Set.of();
    private final boolean deviceAccess;
    private final TlsSettings requestedSettings;
    private final Clock wallClock;
    private volatile TlsSettings tlsSettings;
    private final Map<String, long[]> pairingRates = new HashMap<>();
    private long pairWindow;
    private int pairRequests;

    public AudioIngress(Path directory, int port, Host host, boolean deviceAccess) {
        this(directory,port,host,System::nanoTime,deviceAccess,null,Clock.systemUTC());
    }
    AudioIngress(Path directory, int port, Host host, LongSupplier clock, TlsSettings settings, Clock wallClock) {
        this(directory,port,host,clock,true,settings,wallClock);
    }
    private AudioIngress(Path directory,int port,Host host,LongSupplier clock,boolean deviceAccess,TlsSettings settings,Clock wallClock) {
        if (port<0 || port>65535) throw new IllegalArgumentException("Invalid ingress port");
        this.configDirectory=Objects.requireNonNull(directory);
        this.requestedPort=port;
        this.host=Objects.requireNonNull(host);
        this.clock=Objects.requireNonNull(clock);
        this.deviceAccess=deviceAccess;
        this.requestedSettings=settings;
        this.wallClock=wallClock;
    }
    void presence(Set<UUID> players) {
        online=Set.copyOf(players);
        BroadcastAuthorization current=authorization;
        if (current!=null) current.presence(online);
    }
    <T> CompletableFuture<T> authorize(BroadcastAuthorization.Action<T> action) {
        CompletableFuture<T> result=new CompletableFuture<>();
        boolean queued=enqueue(() -> {
            try {
                if (closing || authorization==null || identity==null) throw new IllegalStateException();
                result.complete(action.apply(authorization));
            } catch (Exception e) { result.completeExceptionally(new IllegalStateException("Authorization operation failed")); }
        });
        if (!queued) result.completeExceptionally(new IllegalStateException("Authorization unavailable"));
        return result;
    }
    CompletableFuture<String> pairing(UUID player, boolean issue, boolean onlineMode) {
        TlsSettings settings = tlsSettings;
        if (settings == null || !tlsReady(tlsListener)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Pairing unavailable"));
        }
        if (!settings.allowsGamePairing(onlineMode)) {
            return CompletableFuture.failedFuture(new OfflinePairingDisabled());
        }
        return authorize(auth -> {
            try {
                auth.checkGrant(player);
                String url = settings.endpoint(settings.publicPort() == 0 ? tlsPort() : settings.publicPort());
                String ca = identity.caPem();
                var data = new HashMap<String, Object>(Map.of("version", 1, "server_id", identity.serverId.toString(),
                        "url", url, "ca_pem", ca));
                if (issue) {
                    data.put("code", auth.pairingCode(player));
                    data.put("expires_at", wallClock.millis() + 300_000);
                }
                return (issue ? "svcb1:" : "svcid1:") + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new Gson().toJson(data).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { throw new IllegalStateException("Pairing unavailable"); }
        });
    }
    int tlsPort() { return tlsListener==null ? -1 : ((InetSocketAddress)tlsListener.localAddress()).getPort(); }
    String caPem() throws Exception { return identity.caPem(); }
    UUID serverId() { return identity.serverId; }
    private void renewIdentity() {
        if (closing) return;
        try { identity=TlsIdentity.load(configDirectory.resolve("tls"),tlsSettings.host(),wallClock); }
        catch (Exception error) { identity=null; LOGGER.warn("TLS identity unavailable; existing files require inspection"); }
    }


    public AudioIngress(Path configDirectory, int port, Host host) {
        this(configDirectory, port, host, System::nanoTime);
    }

    AudioIngress(Path configDirectory, int port, Host host, LongSupplier clock) {
        this(configDirectory,port,host,clock,false,null,Clock.systemUTC());
    }

    public synchronized void start() {
        if (startRequested || closing) {
            return;
        }
        startRequested = true;
        state = "starting";
        control.execute(this::open);
    }

    public synchronized void rotateToken() {
        if (!startRequested || closing || rotationQueued) {
            return;
        }
        rotationQueued = true;
        control.execute(() -> {
            try {
                if (closing || listener == null || !listener.isActive()) {
                    return;
                }
                IngressToken replacement = IngressToken.rotate(configDirectory);
                token = replacement;
                network.next().execute(() -> {
                    for (Client client : new ArrayList<>(clients)) {
                        if (!client.device && client.authenticatedToken != token) {
                            client.stopAndClose("token_rotated");
                        }
                    }
                });
                state = "listening";
                LOGGER.info("Audio ingress token rotated; previous authentication revoked");
            } catch (Exception exception) {
                LOGGER.warn("Audio ingress token rotation failed: exception={}", exception.getClass().getSimpleName());
                if (!closing) {
                    state = "listening; token rotation failed";
                }
            } finally {
                synchronized (this) {
                    rotationQueued = false;
                }
            }
        });
    }

    public String status() {
        Client current = active;
        String stream = current == null || current.stream == null ? lastStream : current.stream.summary();
        return "Ingress: " + state + "; 127.0.0.1:" + (boundPort < 0 ? requestedPort : boundPort)
                + "; TLS=" + (tlsReady(tlsListener) ? "ready:"+tlsPort() : "unavailable")
                + "; clients=" + connectionCount.get() + "; " + stream;
    }

    record SetupReport(String code, String message) {
        boolean ready() { return code.equals("READY"); }
        String display() { return "Desktop Music setup: " + code + " — " + message; }
    }

    private boolean tlsReady(Channel channel) {
        return !closing && channel != null && channel.isActive() && identity != null && authorization != null;
    }

    SetupReport setupReport(boolean onlineMode) {
        if (closing) return new SetupReport("STOPPING", "Wait for the server to start again.");
        if (!deviceAccess) return new SetupReport("DISABLED", "Only loopback maintenance uploads are enabled.");
        SetupReport issue = startupIssue;
        if (issue != null) return issue;
        Channel channel = tlsListener;
        TlsSettings settings = tlsSettings;
        if (settings != null && tlsReady(channel)) {
            if (!settings.allowsGamePairing(onlineMode)) {
                return new SetupReport("ONLINE_MODE_REQUIRED", OFFLINE_PAIRING_DISABLED);
            }
            var address = (InetSocketAddress) channel.localAddress();
            if (address.getAddress().isLoopbackAddress() || settings.localOnly()) {
                return new SetupReport("LOCAL_ONLY", "For remote senders, set public_address to the player-facing IP or DNS name in "
                        + "config/svc-broadcast/tls.properties and restart. Existing advanced configurations use bind_address and advertised_host instead.");
            }
            int external = settings.publicPort() == 0 ? address.getPort() : settings.publicPort();
            return new SetupReport("READY", "Listening TCP " + address.getPort() + "; sender URL " + settings.endpoint(external)
                    + ". Verify TCP " + external + " from a sender's network. Then use /svcbroadcast auth grant <player> <days>,"
                    + " and have the player run /svcbroadcast pair.");
        }
        if (channel != null && channel.isActive() && identity == null) {
            return new SetupReport("IDENTITY", "Check the server identity and restore its private backup if needed.");
        }
        return new SetupReport("STARTING", "Wait for SVC and the upload listener to finish starting, then run setup again.");
    }

    @Override
    public synchronized void close() {
        if (closing) {
            return;
        }
        closing = true;
        state = "closing";
        control.execute(() -> {
            started.completeExceptionally(new IllegalStateException("Ingress closed"));
            shutdownNetwork();
        });
        control.shutdown();
    }

    CompletableFuture<Integer> startCompletion() {
        return started;
    }

    CompletableFuture<Void> closeCompletion() {
        return closed;
    }

    private void open() {
        String failureStage = "token_io";
        try {
            if (closing) {
                return;
            }
            token = IngressToken.loadOrCreate(configDirectory);
            if (closing) {
                return;
            }
            if (deviceAccess) {
                failureStage="tls_config";
                tlsSettings=requestedSettings==null ? TlsSettings.load(configDirectory) : requestedSettings;
                failureStage="tls_identity";
                identity=TlsIdentity.load(configDirectory.resolve("tls"),tlsSettings.host(),wallClock);
                failureStage="authorization_store";
                authorization=new BroadcastAuthorization(configDirectory,wallClock,tlsSettings.maxDevices());
                authorization.presence(online);
            }
            failureStage = "bind";
            network = new NioEventLoopGroup(1, task -> {
                Thread thread = new Thread(task, "svc-broadcast-ingress-network");
                thread.setDaemon(true);
                return thread;
            });
            listener = new ServerBootstrap().group(network)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, MAX_CLIENTS)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.AUTO_READ, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            int count = connectionCount.incrementAndGet();
                            if (closing || count > MAX_CLIENTS) {
                                connectionCount.decrementAndGet();
                                channel.close();
                                return;
                            }
                            connections.add(channel);
                            channel.closeFuture().addListener(future -> connectionCount.decrementAndGet());
                            channel.pipeline().addLast(new HttpServerCodec(4096, 8192, 4096));
                            channel.pipeline().addLast(new HttpObjectAggregator(4096, true));
                            channel.pipeline().addLast(new Client(false));
                        }
                    }).bind(new InetSocketAddress("127.0.0.1", requestedPort)).syncUninterruptibly().channel();
            if (closing) {
                shutdownNetwork();
                return;
            }
            if (deviceAccess) {
                failureStage = "tls_bind";
                tlsListener=new ServerBootstrap().group(network).channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG,MAX_CLIENTS).childOption(ChannelOption.TCP_NODELAY,true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            TlsIdentity current=identity;
                            int count=connectionCount.incrementAndGet();
                            if (closing || count>MAX_CLIENTS || current==null) {
                                connectionCount.decrementAndGet(); channel.close(); return;
                            }
                            connections.add(channel);
                            channel.closeFuture().addListener(f->connectionCount.decrementAndGet());
                            var ssl=current.context.newHandler(channel.alloc());
                            ssl.setHandshakeTimeoutMillis(5000);
                            channel.pipeline().addLast(ssl,new HttpServerCodec(4096,8192,4096),
                                    new HttpObjectAggregator(4096,true),new Client(true));
                        }
                    }).bind(new InetSocketAddress(tlsSettings.bind(),tlsSettings.port())).syncUninterruptibly().channel();
                network.next().scheduleAtFixedRate(()-> {
                    if (!closing) enqueue(this::renewIdentity);
                },1,1,TimeUnit.HOURS);
            }
            boundPort = ((InetSocketAddress) listener.localAddress()).getPort();
            state = "listening";
            LOGGER.info("Audio ingress listening on 127.0.0.1:{}; CLI protocol v1", boundPort);
            started.complete(boundPort);
        } catch (Exception | LinkageError exception) {
            String code = exception instanceof IngressToken.InvalidTokenException ? "invalid_token_file" : failureStage;
            startupIssue = setupFailure(code, exception);
            state = closing ? "closing" : "startup failed (" + code + ")";
            LOGGER.warn("Audio ingress startup failed: code={}, exception={}. {}", code,
                    exception.getClass().getSimpleName(), startupIssue.message());
            started.completeExceptionally(new IllegalStateException("Ingress startup failed"));
            shutdownNetwork();
        }
    }

    private SetupReport setupFailure(String stage, Throwable error) {
        return switch (stage) {
            case "tls_config" -> new SetupReport("CONFIG", "Check config/svc-broadcast/tls.properties. "
                    + (error instanceof TlsSettings.ConfigException ? error.getMessage() : "Check file access and permissions.") + " Restart after fixing it.");
            case "tls_identity" -> new SetupReport("IDENTITY", "Check private identity files and permissions. Restore the existing identity from backup if needed.");
            case "authorization_store" -> new SetupReport("AUTH_STORE", "Check the private authorization store and its backup.");
            case "bind" -> new SetupReport("MAINTENANCE_BIND", "Check whether loopback TCP " + requestedPort + " is occupied.");
            case "tls_bind" -> new SetupReport("UPLOAD_BIND", "Check the configured listen address and whether TCP " + tlsSettings.port() + " is occupied.");
            default -> new SetupReport("MAINTENANCE_TOKEN", "Check the private maintenance token file and permissions.");
        };
    }

    private void shutdownNetwork() {
        if (listener != null) {
            listener.close();
        }
        if (tlsListener!=null) tlsListener.close();
        connections.close();
        if (network == null) {
            finishClose();
        } else {
            network.shutdownGracefully(0, 2, TimeUnit.SECONDS).addListener(future -> finishClose());
        }
    }

    private void finishClose() {
        if (closing) {
            state = "closed";
            closed.complete(null);
        }
    }

    private final class Client extends SimpleChannelInboundHandler<Object> {
        private final boolean device;
        private BroadcastAuthorization.Principal principal;
        Client(boolean device) { this.device=device; }
        private boolean validAuthentication() {
            return device ? authorization!=null && authorization.permitted(principal) : authenticatedToken==token;
        }
        private ChannelHandlerContext context;
        private ScheduledFuture<?> phaseTimeout;
        private ScheduledFuture<?> idleCheck;
        private IngressToken authenticatedToken;
        private boolean upgraded;
        private boolean pairingPending;
        private boolean terminating;
        private boolean endRequested;
        private boolean hostAccepted;
        private boolean stopRequested;
        private boolean released;
        private UUID sessionId;
        private PcmStream stream;

        @Override
        public void channelActive(ChannelHandlerContext context) {
            this.context = context;
            clients.add(this);
            schedulePhaseTimeout();
            context.fireChannelActive();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, Object message) {
            if (terminating || closing) {
                stopAndClose("stopped");
                return;
            }
            try {
                if (pairingPending) { reject(HttpResponseStatus.BAD_REQUEST); return; }
                if (message instanceof FullHttpRequest request && !upgraded) {
                    handshake(request);
                } else if (message instanceof WebSocketFrame frame && upgraded) {
                    receive(frame);
                } else {
                    fail("invalid_message", 1008);
                }
            } catch (AudioProtocol.Failure failure) {
                fail(failure.code(), 1008);
            } catch (Exception exception) {
                LOGGER.warn("Audio ingress internal failure: session={}, exception={}",
                        sessionId, exception.getClass().getSimpleName());
                fail("internal_error", 1008);
            }
        }

        private void handshake(FullHttpRequest request) {
            if (device && request.uri().equals("/v1/pair")) { pair(request); return; }
            if (!request.decoderResult().isSuccess() || request.content().isReadable()) {
                reject(HttpResponseStatus.BAD_REQUEST);
                return;
            }
            if (!request.method().equals(HttpMethod.GET)) {
                reject(HttpResponseStatus.METHOD_NOT_ALLOWED);
                return;
            }
            if (!request.uri().equals(device ? "/v1/device/audio" : "/v1/audio")) {
                reject(HttpResponseStatus.NOT_FOUND);
                return;
            }
            if (request.headers().contains(HttpHeaderNames.ORIGIN)) {
                reject(HttpResponseStatus.FORBIDDEN);
                return;
            }
            IngressToken current = token;
            if (request.headers().getAll(HttpHeaderNames.AUTHORIZATION).size() != 1
                    || (device ? (principal=authorization.authenticate(request.headers().get(HttpHeaderNames.AUTHORIZATION)))==null
                            : current == null || !current.authorizes(request.headers().get(HttpHeaderNames.AUTHORIZATION)))) {
                reject(HttpResponseStatus.UNAUTHORIZED);
                return;
            }
            if (!HttpHeaderValues.WEBSOCKET.contentEqualsIgnoreCase(request.headers().get(HttpHeaderNames.UPGRADE))) {
                reject(HttpResponseStatus.BAD_REQUEST);
                return;
            }
            WebSocketDecoderConfig decoder = WebSocketDecoderConfig.newBuilder()
                    .maxFramePayloadLength(AudioProtocol.MAX_PAYLOAD).expectMaskedFrames(true)
                    .allowMaskMismatch(false).allowExtensions(false).closeOnProtocolViolation(false)
                    .withUTF8Validator(true).build();
            WebSocketServerHandshaker handshaker = new WebSocketServerHandshakerFactory(
                    (device ? "wss://localhost:"+tlsPort()+"/v1/device/audio" : "ws://127.0.0.1:"+boundPort+"/v1/audio"), null, decoder).newHandshaker(request);
            if (handshaker == null) {
                reject(HttpResponseStatus.UPGRADE_REQUIRED);
                return;
            }
            authenticatedToken = current;
            upgraded = true;
            handshaker.handshake(context.channel(), request).addListener(future -> {
                if (!future.isSuccess()) {
                    context.close();
                    return;
                }
                if (terminating || !context.channel().isActive()) {
                    return;
                }
                if (closing || !validAuthentication()) {
                    stopAndClose("token_rotated");
                    return;
                }
                if (stream == null) {
                    schedulePhaseTimeout();
                }
            });
        }

        private void pair(FullHttpRequest request) {
            if (!request.decoderResult().isSuccess() || request.content().readableBytes()>2048) { reject(HttpResponseStatus.BAD_REQUEST); return; }
            if (!request.method().equals(HttpMethod.POST)) { reject(HttpResponseStatus.METHOD_NOT_ALLOWED); return; }
            if (request.headers().contains(HttpHeaderNames.ORIGIN)) { reject(HttpResponseStatus.FORBIDDEN); return; }
            long now=clock.getAsLong();
            if (now-pairWindow>=60_000_000_000L || pairWindow==0) { pairWindow=now; pairRequests=0; pairingRates.clear(); }
            String ip=((InetSocketAddress)context.channel().remoteAddress()).getAddress().getHostAddress();
            long[] attempts=pairingRates.get(ip);
            if (attempts==null) { if (pairingRates.size()>=256) { reject(HttpResponseStatus.TOO_MANY_REQUESTS); return; } attempts=new long[1]; pairingRates.put(ip,attempts); }
            if (++attempts[0]>10 || ++pairRequests>40) { reject(HttpResponseStatus.TOO_MANY_REQUESTS); return; }
            String code=null,label=null,server=null;
            try (JsonReader reader=new JsonReader(new StringReader(request.content().toString(StandardCharsets.UTF_8)))) {
                reader.setLenient(false); reader.beginObject();
                Set<String> fields=new HashSet<>();
                while (reader.hasNext()) {
                    String key=reader.nextName();
                    if (!fields.add(key) || reader.peek()!=JsonToken.STRING) throw new IllegalArgumentException();
                    String value=reader.nextString();
                    switch (key) { case "code" -> code=value; case "label" -> label=value; case "server_id" -> server=value; default -> throw new IllegalArgumentException(); }
                }
                reader.endObject();
                if (reader.peek()!=JsonToken.END_DOCUMENT || code==null || label==null || identity==null || !identity.serverId.toString().equals(server)) throw new IllegalArgumentException();
            } catch (Exception error) { reject(HttpResponseStatus.BAD_REQUEST); return; }
            final String suppliedCode=code, suppliedLabel=label;
            pairingPending=true;
            // Keep phase timeout active while durable IO runs. No game thread waits.
            boolean queued=enqueue(()-> {
                String body=null;
                if (!context.channel().isActive() || closing) return;
                try {
                    var issued=authorization.redeem(suppliedCode,suppliedLabel);
                    body=new Gson().toJson(Map.of("version",1,"server_id",identity.serverId.toString(),
                        "device_id",issued.principal().deviceId().toString(),"player_id",issued.principal().playerId().toString(),
                        "token",issued.token(),"expires_at",issued.expiresAt()));
                } catch (Exception denied) { }
                final String response=body;
                context.executor().execute(()-> {
                    if (terminating || !context.channel().isActive()) return;
                    terminating=true; cancelPhaseTimeout();
                    byte[] data=response==null ? new byte[0] : response.getBytes(StandardCharsets.UTF_8);
                    var reply=new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,response==null ? HttpResponseStatus.FORBIDDEN : HttpResponseStatus.OK,Unpooled.wrappedBuffer(data));
                    reply.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,data.length);
                    reply.headers().set(HttpHeaderNames.CONTENT_TYPE,"application/json");
                    reply.headers().set(HttpHeaderNames.CACHE_CONTROL,"no-store");
                    reply.headers().set(HttpHeaderNames.CONNECTION,"close");
                    closeAfter(context.writeAndFlush(reply));
                });
            });
            if (!queued) reject(HttpResponseStatus.SERVICE_UNAVAILABLE);
        }

        private void receive(WebSocketFrame frame) {
            if (!context.channel().isWritable()) {
                fail("backpressure", 1008);
                return;
            }
            if (!validAuthentication()) {
                stopAndClose("token_rotated");
                return;
            }
            if (!frame.isFinalFragment() || frame.rsv() != 0) {
                fail("fragmented_message", 1008);
                return;
            }
            if (frame instanceof CloseWebSocketFrame) {
                terminating = true;
                drop("disconnected");
                closeAfter(context.writeAndFlush(new CloseWebSocketFrame(1000, "")));
            } else if (frame instanceof PingWebSocketFrame ping) {
                context.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            } else if (frame instanceof PongWebSocketFrame) {
                // Control traffic does not extend either the start deadline or audio idle deadline.
            } else if (frame instanceof TextWebSocketFrame text) {
                if (text.content().readableBytes() > AudioProtocol.MAX_JSON) {
                    fail("message_too_large", 1009);
                    return;
                }
                command(AudioProtocol.parse(text.text()));
            } else if (frame instanceof BinaryWebSocketFrame binary) {
                audio(binary.content());
            } else {
                fail("fragmented_message", 1008);
            }
        }

        private void command(AudioProtocol.Command command) {
            if (stream == null) {
                if (command != AudioProtocol.Command.START) {
                    throw new AudioProtocol.Failure("start_required");
                }
                begin();
                return;
            }
            switch (command) {
                case START -> throw new AudioProtocol.Failure("already_started");
                case END -> {
                    if (endRequested) {
                        throw new AudioProtocol.Failure("stream_ended");
                    }
                    endRequested = true;
                    stream.finish();
                }
                case STOP -> stopAndClose("stopped");
            }
        }

        private void begin() {
            if (active != null) {
                throw new AudioProtocol.Failure("busy");
            }
            sessionId = UUID.randomUUID();
            stream = new PcmStream(sessionId, clock.getAsLong());
            active = this;
            try {
                hostAccepted = host.start(sessionId, stream, () -> {
                    try {
                        context.executor().execute(this::hostStopped);
                    } catch (RejectedExecutionException exception) {
                        // Shutdown has already closed the channel and discarded pending PCM.
                        stream.stop();
                    }
                }, principal);
            } catch (Exception exception) {
                LOGGER.warn("Audio ingress Host.start failed: session={}, exception={}",
                        sessionId, exception.getClass().getSimpleName());
                stream.stop();
                active = null;
                try {
                    host.stop(sessionId);
                } catch (Exception stopFailure) {
                    LOGGER.warn("Audio ingress Host.stop after failed start failed: session={}, exception={}",
                            sessionId, stopFailure.getClass().getSimpleName());
                }
                throw new AudioProtocol.Failure("unavailable");
            }
            if (!hostAccepted) {
                active = null;
                stream.stop();
                throw new AudioProtocol.Failure("busy");
            }
            cancelPhaseTimeout();
            idleCheck = context.executor().scheduleAtFixedRate(() -> {
                if (!validAuthentication()) {
                    stopAndClose("authorization_revoked");
                } else if (stream.expireIdle(clock.getAsLong())) {
                    stopAndClose("timeout");
                }
            }, 100, 100, TimeUnit.MILLISECONDS);
            context.writeAndFlush(new TextWebSocketFrame(AudioProtocol.ready(sessionId)))
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        }

        private void audio(ByteBuf content) {
            if (stream == null) {
                throw new AudioProtocol.Failure("start_required");
            }
            if (endRequested) {
                throw new AudioProtocol.Failure("stream_ended");
            }
            if (content.readableBytes() != AudioProtocol.MESSAGE_BYTES) {
                throw new AudioProtocol.Failure("invalid_size");
            }
            int offset = content.readerIndex();
            if (content.getLong(offset) != sessionId.getMostSignificantBits()
                    || content.getLong(offset + Long.BYTES) != sessionId.getLeastSignificantBits()) {
                throw new AudioProtocol.Failure("invalid_session");
            }
            long sequence = content.getLong(offset + 2 * Long.BYTES);
            content.skipBytes(AudioProtocol.HEADER_BYTES);
            stream.offer(sequence, content, clock.getAsLong());
        }

        private void hostStopped() {
            if (released) {
                return;
            }
            released = true;
            if (active == this) {
                active = null;
            }
            String reason = stream.terminalReason();
            if (!endRequested || !"completed".equals(reason)) {
                reason = "timeout".equals(reason) ? "timeout" : "stopped";
            }
            stream.stop();
            lastStream = stream.summary();
            if (!terminating && context.channel().isActive()) {
                stopAndClose(reason);
            }
        }

        private void drop(String reason) {
            cancelPhaseTimeout();
            if (idleCheck != null) {
                idleCheck.cancel(false);
            }
            if (stream != null) {
                stream.stop(reason);
                lastStream = stream.summary();
            }
            if (hostAccepted && !stopRequested && !released) {
                stopRequested = true;
                try {
                    host.stop(sessionId);
                } catch (Exception exception) {
                    LOGGER.warn("Audio ingress Host.stop failed: session={}, exception={}",
                            sessionId, exception.getClass().getSimpleName());
                }
            }
        }

        private void stopAndClose(String reason) {
            if (terminating) {
                return;
            }
            terminating = true;
            drop(reason);
            if (upgraded) {
                context.write(new TextWebSocketFrame("{\"type\":\"stopped\",\"reason\":\"" + reason + "\"}"));
                closeAfter(context.writeAndFlush(new CloseWebSocketFrame(1000, "")));
            } else {
                context.close();
            }
        }

        private void fail(String code, int closeCode) {
            if (terminating) {
                return;
            }
            terminating = true;
            drop("invalid_input");
            if (upgraded) {
                context.write(new TextWebSocketFrame("{\"type\":\"error\",\"code\":\"" + code + "\"}"));
                closeAfter(context.writeAndFlush(new CloseWebSocketFrame(closeCode, "")));
            } else {
                reject(HttpResponseStatus.BAD_REQUEST);
            }
        }

        private void reject(HttpResponseStatus status) {
            terminating = true;
            cancelPhaseTimeout();
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            closeAfter(context.writeAndFlush(response));
        }

        private void closeAfter(ChannelFuture write) {
            ScheduledFuture<?> deadline = context.executor().schedule(() -> context.close(), 250, TimeUnit.MILLISECONDS);
            context.channel().closeFuture().addListener(future -> deadline.cancel(false));
            write.addListener(ChannelFutureListener.CLOSE);
        }

        private void schedulePhaseTimeout() {
            cancelPhaseTimeout();
            long phaseStarted = clock.getAsLong();
            phaseTimeout = context.executor().scheduleAtFixedRate(() -> {
                if (clock.getAsLong() - phaseStarted >= 5_000_000_000L) {
                    if (upgraded) {
                        fail("start_timeout", 1008);
                    } else {
                        reject(HttpResponseStatus.REQUEST_TIMEOUT);
                    }
                }
            }, 100, 100, TimeUnit.MILLISECONDS);
        }

        private void cancelPhaseTimeout() {
            if (phaseTimeout != null) {
                phaseTimeout.cancel(false);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            terminating = true;
            drop("disconnected");
            clients.remove(this);
            context.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            boolean oversized = cause instanceof TooLongFrameException
                    || cause instanceof CorruptedWebSocketFrameException failure
                    && failure.closeStatus().code() == 1009;
            fail(oversized ? "message_too_large" : "invalid_message", oversized ? 1009 : 1008);
        }
    }
}

package page.mjoe.svcbroadcast.sender;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** One explicit, authenticated V1 upload. Socket and source work runs outside the game thread. */
public final class JavaUpload implements AutoCloseable {
    private static final String DEVICE_PATH = "/v1/device/audio";
    private static final String START = "{\"type\":\"start\",\"version\":1,\"sample_rate\":48000,"
            + "\"channels\":1,\"encoding\":\"pcm_s16le\",\"frame_samples\":960}";
    private static final String END = "{\"type\":\"end\"}";
    private static final String STOP = "{\"type\":\"stop\"}";
    static final int PCM_BYTES = 960 * Short.BYTES;
    static final long FRAME_NS = 20_000_000;
    private static final long MAX_AGE_NS = 200_000_000;
    private static final long PING_NS = 20_000_000_000L;
    private static final int MAX_CONTROL_BYTES = 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int SEND_MILLIS = 250;

    public enum State { CONNECTING, READY, CAPTURING }

    @FunctionalInterface
    public interface SourceFactory { Source open() throws IOException, InterruptedException; }

    public interface Source extends AutoCloseable {
        byte[] readFrame(long index) throws IOException, InterruptedException;
        default boolean ready() { return true; }
        /** A positive count for finite sources, or -1 for live capture. */
        default long frameCount() { return -1; }
        /** Called off the game thread. Must unblock a concurrent read. */
        @Override void close();
    }

    public record Result(long sentFrames, long droppedFrames, boolean stopped) {}

    public static final class Failure extends IOException {
        public Failure(String code) { super(code.matches("[a-z0-9_]{1,48}") ? code : "internal_error"); }
        public String code() { return getMessage(); }
    }

    private final URI endpoint;
    private final SSLContext trust;
    private final String token;
    private final SourceFactory sourceFactory;
    private final Consumer<State> onState;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Source> source = new AtomicReference<>();
    private final CompletableFuture<Void> sourceClosed = new CompletableFuture<>();
    private volatile Thread worker;
    private volatile boolean draining;

    public JavaUpload(URI endpoint, SSLContext trust, String token, SourceFactory sourceFactory, Consumer<State> onState) {
        this.endpoint = endpoint;
        this.trust = trust;
        this.token = token;
        this.sourceFactory = sourceFactory;
        this.onState = onState;
    }

    /** A direct client with scoped trust and no credential-bearing wire diagnostics. */
    public static HttpClient newClient(SSLContext trust) throws Failure {
        String logs = System.getProperty("jdk.httpclient.HttpClient.log", "");
        for (String option : logs.toLowerCase(Locale.ROOT).split(",")) {
            if (Set.of("headers", "content", "all").contains(option.trim())) throw new Failure("network_logging");
        }
        if (trust == null) throw new Failure("tls_verification");
        var parameters = new SSLParameters();
        parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        return HttpClient.newBuilder().sslContext(trust).sslParameters(parameters)
                .version(HttpClient.Version.HTTP_1_1).proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT_TIMEOUT).build();
    }

    public Result run() throws Failure {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Upload already started");
        worker = Thread.currentThread();
        var peer = new Peer();
        var counters = new Counters();
        HttpClient client = null;
        WebSocket socket = null;
        Source opened = null;
        boolean completed = false;
        boolean startSent = false;
        try {
            checkActive(peer);
            validateEndpoint(endpoint);
            if (token == null || !token.matches("[0-9a-f]{64}")) throw new Failure("authentication");
            onState.accept(State.CONNECTING);
            checkActive(peer);
            client = newClient(trust);
            socket = connect(client, peer);
            checkActive(peer);
            send(socket, socket.sendText(START, true));
            startSent = true;
            UUID session = await(peer.ready, 5000, "ready_timeout");
            checkActive(peer);
            onState.accept(State.READY);
            checkActive(peer);
            opened = sourceFactory.open();
            if (opened == null) throw new Failure("source_frame");
            source.set(opened);
            checkActive(peer);
            stream(opened, peer, socket, session, counters);
            checkActive(peer);
            draining = true;
            send(socket, socket.sendText(END, true));
            var outcome = await(peer.terminal, 3000, "drain_timeout");
            if (outcome.failure() != null) throw outcome.failure();
            if (!outcome.completed()) throw new Failure("server_stopped");
            completed = true;
            return counters.result(false);
        } catch (InterruptedException interrupted) {
            if (cancelled.get()) return counters.result(true);
            var outcome = peer.terminal.getNow(null);
            if (outcome != null) throw peerFailure(outcome);
            throw new Failure("connection");
        } catch (IOException | RuntimeException failure) {
            if (cancelled.get()) return counters.result(true);
            var outcome = peer.terminal.getNow(null);
            if (outcome != null && !outcome.completed()) throw peerFailure(outcome);
            if (failure instanceof Failure safe) throw safe;
            throw new Failure("source_frame");
        } finally {
            worker = null;
            Thread.interrupted();
            releaseSource();
            if (socket != null) finishSocket(socket, startSent && !completed && !peer.terminal.isDone());
            if (client != null) client.shutdownNow();
            if (opened != null) {
                try { sourceClosed.get(2500, TimeUnit.MILLISECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (ExecutionException | TimeoutException ignored) { /* Source owns its cleanup deadline. */ }
            }
        }
    }

    private WebSocket connect(HttpClient client, Peer peer) throws IOException, InterruptedException {
        var pending = client.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT)
                .header("Authorization", "Bearer " + token).buildAsync(endpoint, peer);
        try {
            return pending.get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException failure) {
            pending.whenComplete((late, ignored) -> { if (late != null) late.abort(); });
            pending.cancel(true);
            client.shutdownNow();
            if (failure instanceof InterruptedException interruption) throw interruption;
            throw new Failure("connection");
        } catch (ExecutionException failure) {
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof SSLException) throw new Failure("tls_verification");
                if (cause instanceof WebSocketHandshakeException handshake) {
                    int status = handshake.getResponse().statusCode();
                    if (status == 401 || status == 403) throw new Failure("authentication");
                    throw new Failure(status >= 300 && status < 400 ? "redirect_refused" : "handshake");
                }
            }
            throw new Failure("connection");
        }
    }

    static void validateEndpoint(URI endpoint) throws Failure {
        if (endpoint == null || !"wss".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !DEVICE_PATH.equals(endpoint.getRawPath()) || endpoint.getHost().contains("%")
                || endpoint.getRawAuthority().endsWith(":") || endpoint.getPort() == 0 || endpoint.getPort() > 65535) {
            throw new Failure("endpoint");
        }
    }

    private void stream(Source input, Peer peer, WebSocket socket, UUID session, Counters counters)
            throws IOException, InterruptedException {
        long count = input.frameCount();
        if (count != -1 && count < 1) throw new Failure("source_duration");
        byte[] first = input.readFrame(0);
        checkActive(peer);
        long epoch = System.nanoTime();
        writeFrame(input, socket, session, 0, first, counters);
        long next = 1;
        while (count < 0 || next < count) {
            checkActive(peer);
            if (count < 0) {
                long readStarted = System.nanoTime();
                byte[] pcm = input.readFrame(next++);
                long tick = nextTick(counters.lastWire, System.nanoTime() - epoch);
                waitForTick(epoch, tick);
                checkActive(peer);
                long now = System.nanoTime();
                if (now - readStarted >= MAX_AGE_NS) {
                    // Skipped wire ticks are counted when the next fresh frame is sent.
                    continue;
                }
                long wire = nextTick(counters.lastWire, now - epoch);
                counters.dropped += wire - counters.lastWire - 1;
                writeFrame(input, socket, session, wire, pcm, counters);
            } else {
                long tick = Math.min(count, Math.max(next, (System.nanoTime() - epoch) / FRAME_NS));
                counters.dropped += tick - next;
                next = tick;
                if (tick == count) break;
                waitForTick(epoch, tick);
                checkActive(peer);
                if ((System.nanoTime() - epoch) / FRAME_NS > tick) continue;
                byte[] pcm = input.readFrame(tick);
                checkActive(peer);
                if ((System.nanoTime() - epoch) / FRAME_NS > tick) continue;
                writeFrame(input, socket, session, tick, pcm, counters);
                next++;
            }
            peer.keepAlive(socket);
        }
    }

    static long nextTick(long previousWire, long elapsedNs) {
        return Math.max(previousWire + 1, elapsedNs / FRAME_NS);
    }

    private static void waitForTick(long epoch, long sequence) throws InterruptedException {
        long deadline = epoch + sequence * FRAME_NS;
        for (long delay; (delay = deadline - System.nanoTime()) > 0;) {
            LockSupport.parkNanos(delay);
            if (Thread.interrupted()) throw new InterruptedException();
        }
    }

    private void writeFrame(Source input, WebSocket socket, UUID session, long sequence, byte[] pcm, Counters counters)
            throws Failure, InterruptedException {
        send(socket, socket.sendBinary(packFrame(session, sequence, pcm), true));
        counters.lastWire = sequence;
        counters.sent++;
        if (!counters.capturing && input.ready()) {
            counters.capturing = true;
            onState.accept(State.CAPTURING);
        }
    }

    static ByteBuffer packFrame(UUID session, long sequence, byte[] pcm) throws Failure {
        if (sequence < 0 || pcm == null || pcm.length != PCM_BYTES) throw new Failure("source_frame");
        return ByteBuffer.allocate(3 * Long.BYTES + PCM_BYTES).putLong(session.getMostSignificantBits())
                .putLong(session.getLeastSignificantBits()).putLong(sequence).put(pcm).flip();
    }

    static void send(WebSocket socket, CompletableFuture<WebSocket> pending) throws Failure, InterruptedException {
        try { await(pending, SEND_MILLIS, "send_timeout"); }
        catch (Failure | InterruptedException error) {
            socket.abort(); // A pending write can still own audio bytes.
            throw error;
        }
    }

    private static <T> T await(CompletableFuture<T> future, int milliseconds, String timeout)
            throws Failure, InterruptedException {
        try { return future.get(milliseconds, TimeUnit.MILLISECONDS); }
        catch (TimeoutException failure) { throw new Failure(timeout); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Failure safe) throw safe;
            throw new Failure("connection");
        }
    }

    private static void finishSocket(WebSocket socket, boolean sendStop) {
        try {
            if (sendStop && !socket.isOutputClosed()) send(socket, socket.sendText(STOP, true));
            if (!socket.isOutputClosed()) send(socket, socket.sendClose(WebSocket.NORMAL_CLOSURE, ""));
        } catch (Failure | InterruptedException | RuntimeException ignored) {
            // The peer may already have disconnected.
        } finally {
            socket.abort();
        }
    }

    private void checkActive(Peer peer) throws Failure, InterruptedException {
        if (cancelled.get() || Thread.interrupted()) throw new InterruptedException();
        var outcome = peer.terminal.getNow(null);
        if (outcome != null) throw peerFailure(outcome);
    }

    private static Failure peerFailure(Outcome outcome) {
        return outcome.failure() == null ? new Failure("server_stopped") : outcome.failure();
    }

    private void releaseSource() {
        Source active = source.getAndSet(null);
        if (active == null) return;
        Thread.startVirtualThread(() -> {
            try { active.close(); }
            catch (RuntimeException ignored) { /* No remote or local process details in diagnostics. */ }
            finally { sourceClosed.complete(null); }
        });
    }

    private void wakeWorker() {
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }

    @Override public void close() {
        if (cancelled.compareAndSet(false, true)) {
            wakeWorker();
            releaseSource();
        }
    }

    private static final class Counters {
        long sent, dropped, lastWire;
        boolean capturing;
        Result result(boolean stopped) { return new Result(sent, dropped, stopped); }
    }

    private record Outcome(boolean completed, Failure failure) {}

    private final class Peer implements WebSocket.Listener {
        private final CompletableFuture<UUID> ready = new CompletableFuture<>();
        private final CompletableFuture<Outcome> terminal = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();
        private final AtomicReference<Long> awaitingPong = new AtomicReference<>();
        private long lastPing = System.nanoTime();

        @Override public void onOpen(WebSocket socket) {
            if (cancelled.get()) socket.abort();
            else socket.request(1);
        }

        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            try {
                if (data.length() > MAX_CONTROL_BYTES - text.length()) throw new Failure("invalid_control");
                text.append(data);
                if (last) {
                    Control control = parseControl(text.toString());
                    text.setLength(0);
                    if (control.session() != null) {
                        if (!ready.complete(control.session())) throw new Failure("unexpected_control");
                    } else {
                        terminate(new Outcome(control.completed() && draining, null));
                    }
                }
            } catch (Failure failure) {
                terminate(new Outcome(false, failure));
            }
            if (!terminal.isDone()) socket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            terminate(new Outcome(false, new Failure("invalid_control")));
            return null;
        }

        @Override public CompletionStage<?> onPong(WebSocket socket, ByteBuffer data) {
            Long expected = awaitingPong.get();
            if (expected != null && data.remaining() == Long.BYTES && data.getLong() == expected) {
                awaitingPong.compareAndSet(expected, null);
            }
            if (!terminal.isDone()) socket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            terminate(new Outcome(false, new Failure("connection")));
            return null;
        }

        @Override public void onError(WebSocket socket, Throwable error) {
            terminate(new Outcome(false, new Failure("connection")));
        }

        private void terminate(Outcome outcome) {
            if (terminal.complete(outcome)) {
                ready.completeExceptionally(peerFailure(outcome));
                if (!draining && source.get() != null) {
                    wakeWorker();
                    releaseSource();
                }
            }
        }

        private void keepAlive(WebSocket socket) throws Failure, InterruptedException {
            long now = System.nanoTime();
            if (now - lastPing < PING_NS) return;
            if (awaitingPong.get() != null) throw new Failure("connection");
            lastPing = now;
            awaitingPong.set(now);
            send(socket, socket.sendPing(ByteBuffer.allocate(Long.BYTES).putLong(now).flip()));
        }
    }

    record Control(UUID session, boolean completed) {}

    static Control parseControl(String text) throws Failure {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_CONTROL_BYTES) throw new Failure("invalid_control");
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            reader.beginObject();
            var fields = new HashMap<String, String>();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (fields.containsKey(name)) throw new Failure("invalid_control");
                var type = reader.peek();
                var expected = switch (name) {
                    case "version", "frame_samples", "max_buffer_frames" -> JsonToken.NUMBER;
                    case "type", "session_id", "reason", "code" -> JsonToken.STRING;
                    default -> throw new Failure("invalid_control");
                };
                if (type != expected) throw new Failure("invalid_control");
                fields.put(name, reader.nextString());
            }
            reader.endObject();
            String kind = fields.get("type");
            if (kind == null || reader.peek() != JsonToken.END_DOCUMENT) throw new Failure("invalid_control");
            return switch (kind) {
                case "ready" -> {
                    String id = fields.get("session_id");
                    if (fields.size() != 5 || id == null || !"1".equals(fields.get("version"))
                            || !"960".equals(fields.get("frame_samples")) || !"10".equals(fields.get("max_buffer_frames"))) {
                        throw new Failure("invalid_control");
                    }
                    UUID uuid = UUID.fromString(id);
                    if (!uuid.toString().equals(id)) throw new Failure("invalid_control");
                    yield new Control(uuid, false);
                }
                case "stopped", "error" -> {
                    String value = fields.get("stopped".equals(kind) ? "reason" : "code");
                    if (fields.size() != 2 || value == null || value.isEmpty() || value.length() > 64) {
                        throw new Failure("invalid_control");
                    }
                    if ("error".equals(kind)) throw new Failure("server_error");
                    yield new Control(null, "completed".equals(value));
                }
                default -> throw new Failure("invalid_control");
            };
        } catch (Failure failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new Failure("invalid_control");
        }
    }
}

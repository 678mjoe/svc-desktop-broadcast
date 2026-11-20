package page.mjoe.svcbroadcast;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback sockets with a local consuming Host; no Minecraft/native/player dependency. */
class AudioIngressTest {
    @TempDir
    Path temporary;

    @Test
    void authenticatesBeforeUpgradeAndRejectsBrowserPathsBodiesAndHeaderExcess() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            assertEquals(401, fixture.http("GET", "/v1/audio", null, null, null));
            assertEquals(401, fixture.http("GET", "/v1/audio", "Bearer " + "0".repeat(64), null, null));
            assertEquals(404, fixture.http("GET", "/v1/audio?token=hidden", fixture.authorization(), null, null));
            assertEquals(404, fixture.http("GET", "/unknown", fixture.authorization(), null, null));
            assertEquals(405, fixture.http("POST", "/v1/audio", fixture.authorization(), null, null));
            assertEquals(403, fixture.http("GET", "/v1/audio", fixture.authorization(), "Origin", "https://example.test"));
            assertEquals(400, fixture.http("GET", "/v1/audio", fixture.authorization(), "X-Large", "a".repeat(9000)));
            HttpRequest oversized = HttpRequest.newBuilder(fixture.uri("http", "/v1/audio"))
                    .header("Authorization", fixture.authorization())
                    .method("GET", HttpRequest.BodyPublishers.ofByteArray(new byte[4097])).build();
            assertEquals(413, fixture.http.send(oversized, HttpResponse.BodyHandlers.discarding()).statusCode());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> fixture.connect("Bearer " + "0".repeat(64)));
            assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
            assertEquals(401, ((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());
            assertEquals(0, fixture.host.startCount.get());
        }
    }

    @Test
    void readyEndAndFreshSessionsRespectHostReleaseAndDrainShortInput() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            Client first = fixture.connect(fixture.authorization());
            UUID firstId = first.start();
            Session session = fixture.host.nextStarted();
            assertEquals(firstId, session.id);
            first.binary(packet(firstId, 0, 1234), true);
            first.text("{\"type\":\"end\"}");
            first.expectStopped("completed");
            assertEquals(1000, first.closed.get(3, TimeUnit.SECONDS));
            assertTrue(session.stream.summary().contains("received=1, played=1"));
            assertEquals("completed", session.stream.terminalReason());

            Client second = fixture.connect(fixture.authorization());
            UUID secondId = second.start();
            assertNotEquals(firstId, secondId);
            Session fresh = fixture.host.nextStarted();
            assertTrue(fresh.stream.summary().contains("received=0"));
            second.binary(packet(firstId, 0, 99), true);
            second.expectError("invalid_session", 1008);
            assertEquals(secondId, fixture.host.nextStopped());
            assertEquals(0, fresh.stream.queuedFrames());
        }
    }

    @Test
    void stopDropsQueuedAudioAndBusyPersistsUntilEncoderReleaseCallback() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            fixture.host.releaseEnabled = false;
            Client first = fixture.connect(fixture.authorization());
            UUID id = first.start();
            Session session = fixture.host.nextStarted();
            first.binary(packet(id, 0, 17), true);
            first.text("{\"type\":\"stop\"}");
            first.expectStopped("stopped");
            assertEquals(id, fixture.host.nextStopped());
            assertEquals(0, session.stream.queuedFrames());
            assertNull(session.stream.poll(0));

            Client busy = fixture.connect(fixture.authorization());
            busy.text(AudioProtocolTest.START);
            busy.expectError("busy", 1008);
            assertEquals(1, fixture.host.startCount.get());
            fixture.host.releaseEnabled = true;
            session.released.get(3, TimeUnit.SECONDS);
            Client fresh = fixture.connect(fixture.authorization());
            assertNotEquals(id, fresh.start());
            fixture.host.nextStarted();
            fresh.text("{\"type\":\"stop\"}");
            fresh.expectStopped("stopped");
        }
    }

    @Test
    void hostReadinessAndSynchronousCompletionAreHandledWithoutOrphaningSessions() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            fixture.host.available = false;
            Client unavailable = fixture.connect(fixture.authorization());
            unavailable.text(AudioProtocolTest.START);
            unavailable.expectError("busy", 1008);
            assertEquals(0, fixture.host.startCount.get());
            fixture.host.available = true;
            fixture.host.completeImmediately = true;
            Client completed = fixture.connect(fixture.authorization());
            completed.start();
            completed.expectStopped("stopped");
            assertEquals(1000, completed.closed.get(3, TimeUnit.SECONDS));
            assertNull(fixture.host.active);
        }
    }

    @Test
    void rejectsFormatFragmentationSizesSessionsReplayAndExcessRateOnRealWebSockets() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            Client format = fixture.connect(fixture.authorization());
            format.text(AudioProtocolTest.START.replace("48000", "44100"));
            format.expectError("unsupported_format", 1008);
            Client preStart = fixture.connect(fixture.authorization());
            preStart.binary(ByteBuffer.allocate(1), true);
            preStart.expectError("start_required", 1008);
            Client oversizedJson = fixture.connect(fixture.authorization());
            oversizedJson.text(" ".repeat(1025));
            oversizedJson.expectError("message_too_large", 1009);

            for (String violation : List.of("invalid_size", "invalid_session", "invalid_sequence",
                    "future_sequence", "rate_exceeded", "fragmented_message", "message_too_large")) {
                Client client = fixture.connect(fixture.authorization());
                UUID id = client.start();
                Session session = fixture.host.nextStarted();
                switch (violation) {
                    case "invalid_size" -> client.binary(ByteBuffer.allocate(1943), true);
                    case "invalid_session" -> client.binary(packet(UUID.randomUUID(), 0, 0), true);
                    case "invalid_sequence" -> {
                        client.binary(packet(id, 0, 0), true);
                        client.binary(packet(id, 0, 0), true);
                    }
                    case "future_sequence" -> {
                        client.binary(packet(id, 0, 0), true);
                        client.binary(packet(id, Long.MAX_VALUE, 0), true);
                    }
                    case "rate_exceeded" -> {
                        long start = fixture.now.get();
                        for (int sequence = 0; sequence <= 2; sequence++) {
                            fixture.now.set(start + sequence * 1_000_000_000L);
                            client.binary(packet(id, sequence, 0), true);
                            client.ping();
                        }
                        fixture.now.set(start + 2_100_000_000L);
                        for (int sequence = 3; sequence <= 3 + PcmStream.ARRIVAL_BURST_FRAMES; sequence++) {
                            client.binary(packet(id, sequence, 0), true);
                        }
                    }
                    case "fragmented_message" -> client.binary(packet(id, 0, 0), false);
                    case "message_too_large" -> client.binary(ByteBuffer.allocate(2049), true);
                    default -> fail("Unknown test case");
                }
                client.expectError(violation, violation.equals("message_too_large") ? 1009 : 1008);
                assertEquals(id, fixture.host.nextStopped());
                session.released.get(3, TimeUnit.SECONDS);
                assertEquals(0, session.stream.queuedFrames());
            }
        }
    }

    @Test
    void injectedClockExpiresStartAndAudioWithoutControlTrafficKeepingEitherAlive() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            Client pending = fixture.connect(fixture.authorization());
            pending.ping();
            fixture.now.set(5_000_000_000L);
            pending.expectError("start_timeout", 1008);

            Client streaming = fixture.connect(fixture.authorization());
            UUID id = streaming.start();
            Session session = fixture.host.nextStarted();
            streaming.binary(packet(id, 0, 0), true);
            streaming.ping();
            fixture.now.addAndGet(PcmStream.IDLE_NANOS);
            streaming.expectStopped("timeout");
            assertEquals("timeout", session.stream.terminalReason());
            assertEquals(0, session.stream.queuedFrames());
        }
    }

    @Test
    void rotationRevokesActiveAndPendingClientsAndChangesTheAuthenticationToken() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            String oldAuthorization = fixture.authorization();
            Client active = fixture.connect(oldAuthorization);
            UUID id = active.start();
            Session session = fixture.host.nextStarted();
            active.binary(packet(id, 0, 24), true);
            Client pending = fixture.connect(oldAuthorization);
            fixture.ingress.rotateToken();
            active.expectStopped("token_rotated");
            pending.expectStopped("token_rotated");
            assertEquals(id, fixture.host.nextStopped());
            session.released.get(3, TimeUnit.SECONDS);
            assertEquals(0, session.stream.queuedFrames());
            String replacement = fixture.authorization();
            assertNotEquals(oldAuthorization, replacement);
            assertThrows(ExecutionException.class, () -> fixture.connect(oldAuthorization));
            Client fresh = fixture.connect(replacement);
            assertNotEquals(id, fresh.start());
            assertFalse(fixture.ingress.status().contains(oldAuthorization.substring(7)));
            assertFalse(fixture.ingress.status().contains(replacement.substring(7)));
        }
    }

    @Test
    void transportLossAndControllerCloseDropAudioAndReleaseTheListener() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            Client disconnected = fixture.connect(fixture.authorization());
            UUID id = disconnected.start();
            Session session = fixture.host.nextStarted();
            disconnected.binary(packet(id, 0, 27), true);
            disconnected.socket.abort();
            assertEquals(id, fixture.host.nextStopped());
            session.released.get(3, TimeUnit.SECONDS);
            assertEquals(0, session.stream.queuedFrames());
            Client next = fixture.connect(fixture.authorization());
            UUID nextId = next.start();
            Session nextSession = fixture.host.nextStarted();
            fixture.ingress.close();
            fixture.ingress.closeCompletion().get(3, TimeUnit.SECONDS);
            assertEquals(nextId, fixture.host.nextStopped());
            assertEquals(0, nextSession.stream.queuedFrames());
            try (ServerSocket rebound = new ServerSocket()) {
                rebound.setReuseAddress(true);
                rebound.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), fixture.port));
            }
        }
    }

    @Test
    void boundsPendingConnectionsAndTimesOutIncompleteHttpRequests() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            for (int index = 0; index < 4; index++) {
                fixture.connect(fixture.authorization());
            }
            assertThrows(ExecutionException.class, () -> fixture.connect(fixture.authorization()));
            assertTrue(fixture.ingress.status().contains("clients=4"));
        }
        try (Fixture fixture = new Fixture(temporary.resolve("http-timeout"));
             Socket socket = new Socket("127.0.0.1", fixture.port)) {
            socket.setSoTimeout(3000);
            // Completing an ordinary request first ensures channelActive and its deadline exist.
            socket.getOutputStream().write("GET /v1/audio HTTP/1.1\r\nHost: localhost\r\n".getBytes(StandardCharsets.US_ASCII));
            // A separate completed request is processed on the same sole Netty loop.
            assertEquals(401, fixture.http("GET", "/v1/audio", null, null, null));
            fixture.now.set(5_000_000_000L);
            byte[] response = socket.getInputStream().readNBytes(12);
            assertEquals("HTTP/1.1 408", new String(response, StandardCharsets.US_ASCII).strip());
        }
    }

    @Test
    void closeBeforeOrDuringStartupNeverLeaksAndInvalidExistingTokensFailClosed() throws Exception {
        for (int iteration = 0; iteration < 12; iteration++) {
            try (RecordingHost host = new RecordingHost(new AtomicLong())) {
                AudioIngress ingress = new AudioIngress(temporary.resolve("race-" + iteration), 0, host);
                if (iteration % 2 == 0) {
                    ingress.start();
                }
                ingress.close();
                ingress.start();
                ingress.rotateToken();
                ingress.closeCompletion().get(3, TimeUnit.SECONDS);
                assertTrue(ingress.startCompletion().isDone());
                assertTrue(ingress.status().contains("closed"));
                assertEquals(0, host.startCount.get());
            }
        }
        Path invalid = Files.createDirectory(temporary.resolve("invalid"));
        Files.writeString(invalid.resolve("token.txt"), "secret-invalid-token");
        try (RecordingHost host = new RecordingHost(new AtomicLong())) {
            AudioIngress ingress = new AudioIngress(invalid, 0, host);
            try {
                ingress.start();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> ingress.startCompletion().get(3, TimeUnit.SECONDS));
                assertEquals("Ingress startup failed", failure.getCause().getMessage());
                assertEquals("secret-invalid-token", Files.readString(invalid.resolve("token.txt")));
                assertTrue(ingress.status().contains("invalid_token_file"));
                assertFalse(ingress.status().contains("secret-invalid-token"));
            } finally {
                ingress.close();
                ingress.closeCompletion().get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void reportsBindFailureAndClosesItsNetworkResources() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             RecordingHost host = new RecordingHost(new AtomicLong())) {
            AudioIngress ingress = new AudioIngress(temporary, occupied.getLocalPort(), host);
            try {
                ingress.start();
                assertThrows(ExecutionException.class, () -> ingress.startCompletion().get(3, TimeUnit.SECONDS));
                assertTrue(ingress.status().contains("startup failed (bind)"));
            } finally {
                ingress.close();
                ingress.closeCompletion().get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void controllerCloseNeverWaitsForAnInProgressHostStart() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Session> accepted = new AtomicReference<>();
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        AudioIngress.Host host = new AudioIngress.Host() {
            @Override
            public boolean start(UUID id, PcmStream stream, Runnable callback) {
                accepted.set(new Session(id, stream, callback));
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test host was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return true;
            }

            @Override
            public void stop(UUID id) {
                Session session = accepted.get();
                assertEquals(session.id, id);
                session.stream.stop();
                session.callback.run();
                stopped.complete(null);
            }
        };
        AudioIngress ingress = new AudioIngress(temporary, 0, host);
        Client client = new Client();
        try (HttpClient http = HttpClient.newHttpClient()) {
            try {
                ingress.start();
                int port = ingress.startCompletion().get(3, TimeUnit.SECONDS);
                client.socket = http.newWebSocketBuilder()
                        .header("Authorization", "Bearer " + Files.readString(temporary.resolve("token.txt")).strip())
                        .buildAsync(URI.create("ws://127.0.0.1:" + port + "/v1/audio"), client)
                        .get(3, TimeUnit.SECONDS);
                client.text(AudioProtocolTest.START);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(1), ingress::close);
                release.countDown();
                stopped.get(3, TimeUnit.SECONDS);
                ingress.closeCompletion().get(3, TimeUnit.SECONDS);
                assertEquals(0, accepted.get().stream.queuedFrames());
            } finally {
                release.countDown();
                if (client.socket != null) {
                    client.socket.abort();
                }
                ingress.close();
                ingress.closeCompletion().get(3, TimeUnit.SECONDS);
            }
        }
    }

    private static ByteBuffer packet(UUID id, long sequence, int sample) {
        ByteBuffer packet = ByteBuffer.allocate(AudioProtocol.MESSAGE_BYTES);
        packet.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putLong(sequence);
        packet.order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < PcmStream.FRAME_SAMPLES; index++) {
            packet.putShort((short) sample);
        }
        // JDK WebSocket masking reads words in the buffer's current byte order.
        return packet.flip().order(ByteOrder.BIG_ENDIAN);
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicLong now = new AtomicLong();
        final RecordingHost host = new RecordingHost(now);
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1).build();
        final List<Client> clients = new ArrayList<>();
        final Path directory;
        final AudioIngress ingress;
        final int port;

        Fixture(Path directory) throws Exception {
            this.directory = directory;
            ingress = new AudioIngress(directory, 0, host, now::get);
            ingress.start();
            port = ingress.startCompletion().get(3, TimeUnit.SECONDS);
        }

        String authorization() throws IOException {
            return "Bearer " + Files.readString(directory.resolve("token.txt")).strip();
        }

        URI uri(String scheme, String path) {
            return URI.create(scheme + "://127.0.0.1:" + port + path);
        }

        Client connect(String authorization) throws Exception {
            Client client = new Client();
            client.socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2))
                    .header("Authorization", authorization)
                    .buildAsync(uri("ws", "/v1/audio"), client).get(3, TimeUnit.SECONDS);
            clients.add(client);
            return client;
        }

        int http(String method, String path, String authorization, String name, String value) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri("http", path))
                    .timeout(Duration.ofSeconds(3)).method(method, HttpRequest.BodyPublishers.noBody());
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            if (name != null) {
                request.header(name, value);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }

        @Override
        public void close() throws Exception {
            for (Client client : clients) {
                client.socket.abort();
            }
            ingress.close();
            ingress.closeCompletion().get(3, TimeUnit.SECONDS);
            host.close();
            http.close();
        }
    }

    private static final class Client implements WebSocket.Listener {
        WebSocket socket;
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<Boolean> pongs = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder message = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            message.append(data);
            if (last) {
                messages.add(message.toString());
                message.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            pongs.add(true);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }

        void text(String text) throws Exception {
            socket.sendText(text, true).get(3, TimeUnit.SECONDS);
        }

        void binary(ByteBuffer bytes, boolean last) throws Exception {
            socket.sendBinary(bytes, last).get(3, TimeUnit.SECONDS);
        }

        void ping() throws Exception {
            socket.sendPing(ByteBuffer.wrap(new byte[]{1})).get(3, TimeUnit.SECONDS);
            assertNotNull(pongs.poll(3, TimeUnit.SECONDS), "The server must process preceding audio before the clock advances");
        }

        UUID start() throws Exception {
            text(AudioProtocolTest.START);
            var ready = JsonParser.parseString(next()).getAsJsonObject();
            assertEquals("ready", ready.get("type").getAsString());
            assertEquals(1, ready.get("version").getAsInt());
            assertEquals(960, ready.get("frame_samples").getAsInt());
            assertEquals(10, ready.get("max_buffer_frames").getAsInt());
            return UUID.fromString(ready.get("session_id").getAsString());
        }

        void expectStopped(String reason) throws Exception {
            assertEquals("{\"type\":\"stopped\",\"reason\":\"" + reason + "\"}", next());
        }

        void expectError(String code, int status) throws Exception {
            assertEquals("{\"type\":\"error\",\"code\":\"" + code + "\"}", next());
            assertEquals(status, closed.get(3, TimeUnit.SECONDS));
        }

        String next() throws InterruptedException {
            String next = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(next, "Expected a server control message");
            return next;
        }
    }

    private static final class Session {
        final UUID id;
        final PcmStream stream;
        final Runnable callback;
        final CompletableFuture<Void> released = new CompletableFuture<>();

        Session(UUID id, PcmStream stream, Runnable callback) {
            this.id = id;
            this.stream = stream;
            this.callback = callback;
        }
    }

    private static final class RecordingHost implements AudioIngress.Host, AutoCloseable {
        final AtomicLong clock;
        final AtomicInteger startCount = new AtomicInteger();
        final BlockingQueue<Session> starts = new LinkedBlockingQueue<>();
        final BlockingQueue<UUID> stops = new LinkedBlockingQueue<>();
        final ScheduledExecutorService consumer = Executors.newSingleThreadScheduledExecutor();
        volatile Session active;
        volatile boolean available = true;
        volatile boolean releaseEnabled = true;
        volatile boolean completeImmediately;

        RecordingHost(AtomicLong clock) {
            this.clock = clock;
            consumer.scheduleAtFixedRate(this::tick, 0, 20, TimeUnit.MILLISECONDS);
        }

        @Override
        public synchronized boolean start(UUID id, PcmStream stream, Runnable callback) {
            if (!available || active != null) {
                return false;
            }
            Session session = new Session(id, stream, callback);
            startCount.incrementAndGet();
            starts.add(session);
            if (completeImmediately) {
                stream.stop();
                callback.run();
                session.released.complete(null);
            } else {
                active = session;
            }
            return true;
        }

        @Override
        public synchronized void stop(UUID id) {
            Session session = active;
            if (session != null && session.id.equals(id)) {
                session.stream.stop();
                stops.add(id);
            }
        }

        private void tick() {
            Session session = active;
            if (session == null || session.stream.poll(clock.get()) != null || !releaseEnabled) {
                return;
            }
            synchronized (this) {
                if (active != session) {
                    return;
                }
                active = null;
            }
            session.stream.stop();
            session.callback.run();
            session.released.complete(null);
        }

        Session nextStarted() throws InterruptedException {
            Session session = starts.poll(3, TimeUnit.SECONDS);
            assertNotNull(session);
            return session;
        }

        UUID nextStopped() throws InterruptedException {
            UUID id = stops.poll(3, TimeUnit.SECONDS);
            assertNotNull(id);
            return id;
        }

        @Override
        public void close() {
            consumer.shutdownNow();
            Session session = active;
            if (session != null) {
                session.stream.stop();
                active = null;
                session.callback.run();
                session.released.complete(null);
            }
        }
    }
}

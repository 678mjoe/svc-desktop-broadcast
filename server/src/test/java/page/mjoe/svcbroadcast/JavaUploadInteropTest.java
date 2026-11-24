package page.mjoe.svcbroadcast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import page.mjoe.svcbroadcast.sender.JavaProfileTestBridge;
import page.mjoe.svcbroadcast.sender.JavaUpload;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real TLS, authentication, protocol and PCM path on loopback, without Minecraft or audio devices. */
@Timeout(20)
class JavaUploadInteropTest {
    @TempDir Path directory;

    @Test void pairsAndReusesAProfileThenUploadsSyntheticPcmToTheSameServer() throws Exception {
        try (var fixture = new Fixture("127.0.0.1")) {
            List<JavaUpload.State> states = new ArrayList<>();
            var closed = new AtomicInteger();
            String identity = fixture.service.pairing(fixture.player, false, true).get(5, TimeUnit.SECONDS);
            String offer = fixture.service.pairing(fixture.player, true, true).get(5, TimeUnit.SECONDS);
            var upload = JavaProfileTestBridge.pairAndUpload(directory.resolve("profiles"), fixture.player,
                    identity, offer, () -> {
                assertEquals(fixture.player, fixture.host.active.principal().playerId());
                return new JavaUpload.Source() {
                    @Override public long frameCount() { return 50; }
                    @Override public byte[] readFrame(long index) { return tone(index); }
                    @Override public void close() { closed.incrementAndGet(); }
                };
            }, states::add);
            var result = upload.run();
            assertFalse(result.stopped());
            assertEquals(50, result.sentFrames() + result.droppedFrames());
            assertTrue(result.sentFrames() >= 45);
            assertTrue(fixture.host.active.stream().summary().contains("received=" + result.sentFrames() + ","));
            assertEquals("completed", fixture.host.active.stream().terminalReason());
            assertTrue(fixture.host.nonSilent.get() > 0);
            assertEquals(1, closed.get());
            assertEquals(List.of(JavaUpload.State.CONNECTING, JavaUpload.State.READY, JavaUpload.State.CAPTURING), states);
            var devices = fixture.service.authorize(auth -> auth.devices(fixture.player)).get(5, TimeUnit.SECONDS);
            assertEquals(1, devices.size());
            assertEquals(devices.getFirst().id(), fixture.host.active.principal().deviceId());
        }
    }

    @Test void badCredentialOrTrustNeverOpensCapture() throws Exception {
        try (var fixture = new Fixture("127.0.0.1")) {
            assertRefused(fixture, fixture.trust, "0".repeat(64), "authentication");
            assertRefused(fixture, SSLContext.getDefault(), fixture.issue().token(), "tls_verification");
        }
    }

    @Test void matchingCaWithWrongHostnameNeverOpensCapture() throws Exception {
        try (var fixture = new Fixture("localhost")) {
            assertRefused(fixture, fixture.trust, fixture.issue().token(), "tls_verification");
        }
    }

    @Test void localStopReleasesTheBlockedSourceAndServer() throws Exception {
        try (var fixture = new Fixture("127.0.0.1"); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var source = new BlockedSource();
            try (var upload = fixture.upload(source)) {
                var pending = executor.submit(upload::run);
                assertTrue(source.waiting.await(5, TimeUnit.SECONDS));
                upload.close();
                assertTrue(pending.get(5, TimeUnit.SECONDS).stopped());
                assertTrue(fixture.host.released.await(2, TimeUnit.SECONDS));
                assertEquals(1, source.closes.get());
            }
        }
    }

    @Test void serverDisconnectReleasesTheBlockedSource() throws Exception {
        try (var fixture = new Fixture("127.0.0.1"); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var source = new BlockedSource();
            try (var upload = fixture.upload(source)) {
                var pending = executor.submit(upload::run);
                assertTrue(source.waiting.await(5, TimeUnit.SECONDS));
                fixture.service.close();
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> pending.get(5, TimeUnit.SECONDS));
                var safe = assertInstanceOf(JavaUpload.Failure.class, failure.getCause());
                assertTrue(Set.of("connection", "server_stopped").contains(safe.code()));
                assertEquals(1, source.closes.get());
            }
        }
    }

    private static void assertRefused(Fixture fixture, SSLContext trust, String token, String code) {
        var upload = new JavaUpload(fixture.endpoint(), trust, token, () -> fail("Rejected upload opened capture"),
                state -> assertEquals(JavaUpload.State.CONNECTING, state));
        assertEquals(code, assertThrows(JavaUpload.Failure.class, upload::run).code());
        assertNull(fixture.host.active);
    }

    private static byte[] tone(long frame) {
        var pcm = ByteBuffer.allocate(1920).order(ByteOrder.LITTLE_ENDIAN);
        for (int sample = 0; sample < 960; sample++) {
            pcm.putShort((short) (2200 * Math.sin(2 * Math.PI * 440 * (frame * 960 + sample) / 48000)));
        }
        return pcm.array();
    }

    private static final class BlockedSource implements JavaUpload.Source {
        private final CountDownLatch waiting = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closes = new AtomicInteger();

        @Override public byte[] readFrame(long index) throws InterruptedException {
            if (index > 0) {
                waiting.countDown();
                closed.await();
            }
            return tone(index);
        }
        @Override public void close() { closes.incrementAndGet(); closed.countDown(); }
    }

    private final class Fixture implements AutoCloseable {
        private final PlaybackHost host = new PlaybackHost();
        private final AudioIngress service;
        private final SSLContext trust;
        private final UUID player = UUID.randomUUID();
        private final java.util.concurrent.ScheduledExecutorService audioClock = Executors.newSingleThreadScheduledExecutor();

        Fixture(String hostName) throws Exception {
            service = new AudioIngress(directory, 0, host, System::nanoTime,
                    new TlsSettings("127.0.0.1", 0, hostName, 0, 3, false), Clock.systemUTC());
            try {
                service.start();
                service.startCompletion().get(5, TimeUnit.SECONDS);
                trust = DeviceIngressTest.trust(service.caPem());
                service.presence(Set.of(player));
                service.authorize(auth -> {
                    auth.grant(player, "Interop", 0);
                    return null;
                }).get(5, TimeUnit.SECONDS);
                audioClock.scheduleAtFixedRate(host::tick, 0, 20, TimeUnit.MILLISECONDS);
            } catch (Exception failure) {
                close();
                throw failure;
            }
        }

        URI endpoint() { return URI.create("wss://127.0.0.1:" + service.tlsPort() + "/v1/device/audio"); }
        BroadcastAuthorization.Issued issue() throws Exception {
            return service.authorize(auth -> auth.redeem(auth.pairingCode(player), "Java test")).get(5, TimeUnit.SECONDS);
        }

        JavaUpload upload(JavaUpload.Source source) throws Exception {
            return new JavaUpload(endpoint(), trust, issue().token(), () -> source, state -> {});
        }

        @Override public void close() throws Exception {
            audioClock.shutdownNow();
            service.close();
            service.closeCompletion().get(5, TimeUnit.SECONDS);
        }
    }

    private static final class PlaybackHost implements AudioIngress.Host {
        private record Active(PcmStream stream, Runnable done, BroadcastAuthorization.Principal principal) {}
        private volatile Active active;
        private final AtomicInteger nonSilent = new AtomicInteger();
        private final CountDownLatch released = new CountDownLatch(1);

        @Override public boolean start(UUID id, PcmStream stream, Runnable done) { return false; }

        @Override public boolean start(UUID id, PcmStream stream, Runnable done, BroadcastAuthorization.Principal principal) {
            active = new Active(stream, done, principal);
            return true;
        }

        @Override public void stop(UUID id) { release(); }

        private synchronized void release() {
            if (active == null || released.getCount() == 0) return;
            active.done().run();
            released.countDown();
        }

        void tick() {
            Active current = active;
            if (current == null || released.getCount() == 0) return;
            short[] pcm = current.stream().poll(System.nanoTime());
            if (pcm == null) {
                release();
                return;
            }
            for (short sample : pcm) {
                if (sample != 0) { nonSilent.incrementAndGet(); break; }
            }
        }
    }
}

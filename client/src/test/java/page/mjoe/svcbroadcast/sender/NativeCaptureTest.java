package page.mjoe.svcbroadcast.sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class NativeCaptureTest {
    @TempDir Path temp;

    @Test
    void appListingUsesValidatedSelectorsAndWindowsAudioSessionsComeFirst() {
        var windows = NativeCapture.parseApps(true, """
                5	100	W	A window
                7	200	A	Z music§
                8	300	A	Game
                7	200	W	Z window
                """, 8);
        assertEquals(List.of("7:200", "5:100"), windows.stream().map(NativeCapture.AppSelector::id).toList());
        assertEquals("Z music · 活动音频", windows.getFirst().label());
        var mac = NativeCapture.parseApps(false, "20:678:4\tcom.example.music\tMy player\n", 9);
        assertEquals(List.of(new NativeCapture.AppSelector("20:678:4", "My player · com.example.music")), mac);
        assertThrows(IllegalArgumentException.class, () -> NativeCapture.parseApps(true, "5\t0\tA\tBad", 9));
        assertThrows(IllegalArgumentException.class, () -> NativeCapture.parseApps(false, "2:4:2\tBad", 9));
    }

    @Test
    void refusesDuplicateAndGameProcessesBeforeStartingAnExecutable() {
        Path missing = temp.resolve("not-a-helper");
        var mac = EmbeddedSender.Platform.MAC_ARM64;
        var windows = EmbeddedSender.Platform.WINDOWS_X64;
        assertEquals("coreaudio_selection", assertThrows(JavaUpload.Failure.class,
                () -> NativeCapture.open(mac, missing, List.of("12:999:0"), 12)).code());
        assertEquals("wasapi_selection", assertThrows(JavaUpload.Failure.class,
                () -> NativeCapture.open(windows, missing, List.of("12:123", "12:456"), 22)).code());
    }

    @Test
    void macDecodesLittleEndianFloatsAndPreservesMixedHeadroom() throws IOException {
        var stream = macHeader(48_000);
        macBlock(stream, 1_000_000_000, 0, 480, 1.5f, 0.5f);
        macBlock(stream, 1_010_000_000, 480, 480, 1.5f, 0.5f);
        var sink = new TestSink();
        CapturePipes.readMac(shortReads(stream), 2, clock(10_000_000), sink);
        assertEquals(1, sink.frames.size());
        for (int i = 0; i < 960; i++) assertEquals(13107, sample(sink.frames.getFirst(), i));
    }

    @Test
    void macDropsIncompleteAudioWhenNativeSampleCountSkips() throws IOException {
        var stream = macHeader(48_000);
        macBlock(stream, 1_000_000_000, 0, 480, 1, 1);
        macBlock(stream, 1_030_000_000, 960, 960, 0, 0);
        var sink = new TestSink();
        CapturePipes.readMac(shortReads(stream), 1, clock(10_000_000), sink);
        assertEquals(1, sink.frames.size());
        assertArrayEquals(new byte[1920], sink.frames.getFirst().pcm());
    }

    @Test
    void macDropsExpiredPipeBacklogWithoutMakingItFreshAgain() throws IOException {
        var stream = macHeader(48_000);
        macBlock(stream, 1_000_000_000, 0, 960, 0, 0);
        macBlock(stream, 1_020_000_000, 960, 960, 1, 1);
        var sink = new TestSink();
        CapturePipes.readMac(shortReads(stream), 1, clock(350_000_000), sink);
        assertEquals(1, sink.frames.size());
        assertArrayEquals(new byte[1920], sink.frames.getFirst().pcm());
        assertEquals(List.of(true, true, false), sink.advances);
    }

    @Test
    void malformedCaptureRecordsAreRejected() {
        var mac = macHeader(48_000);
        macBlock(mac, 1, 0, 1, Float.NaN, 0);
        assertThrows(IOException.class, () -> CapturePipes.readMac(shortReads(mac), 1, clock(20_000_000), new TestSink()));
        var windows = new ByteArrayOutputStream();
        windows.writeBytes(le(16).putLong(10).putInt(1).putInt(882).array());
        assertThrows(IOException.class, () -> CapturePipes.readWindows(shortReads(windows), 1, clock(20_000_000), new TestSink()));
        var truncated = new ByteArrayOutputStream();
        truncated.writeBytes(new byte[15]);
        assertThrows(IOException.class, () -> CapturePipes.readWindows(shortReads(truncated), 1, clock(20_000_000), new TestSink()));
    }

    @Test
    void windowsAlignsAndMixesSelectedTracksAndProducesSilenceFromHeartbeats() throws IOException {
        var stream = new ByteArrayOutputStream();
        long t = 1_000_000_000;
        winRecord(stream, t, 0, 0, 0, 0);
        winRecord(stream, t - 600_000, 1, 882, 5000, 7000);
        winRecord(stream, t - 600_000, 2, 882, -3000, -3000);
        for (int i = 1; i <= 4; i++) winRecord(stream, t + i * 200_000, 0, 0, 0, 0);
        var sink = new TestSink();
        CapturePipes.readWindows(shortReads(stream), 2, clock(20_000_000), sink);
        assertEquals(4, sink.frames.size());
        assertArrayEquals(new byte[1920], sink.frames.getFirst().pcm());
        assertEquals(1200, sample(sink.frames.get(1), 500));
        assertArrayEquals(new byte[1920], sink.frames.getLast().pcm());
    }

    @Test
    void windowsDiscardsAudioFromAnOldTimelineAfterAClockJump() throws IOException {
        var stream = new ByteArrayOutputStream();
        long t = 1_000_000_000;
        winRecord(stream, t, 0, 0, 0, 0);
        winRecord(stream, t, 1, 882, 32767, 32767);
        winRecord(stream, t + 10_000_000, 0, 0, 0, 0);
        winRecord(stream, t + 10_000_000, 0, 0, 0, 0);
        var sink = new TestSink();
        CapturePipes.readWindows(shortReads(stream), 1, clock(20_000_000), sink);
        assertEquals(4, sink.frames.size());
        for (var frame : sink.frames) assertArrayEquals(new byte[1920], frame.pcm());
        assertEquals(List.of(true, true, false), sink.advances);
        long seconds = 500L * 24 * 60 * 60;
        assertEquals(seconds * 44_100, CapturePipes.windowsSampleTime(seconds * 10_000_000));
    }

    @Test
    void resamplesAContinuous44100HzSignalIntoFiftyFrames() {
        var sink = new TestSink();
        var converter = new PcmFrameConverter(44_100, sink::frame);
        for (int i = 0; i < 44_100; i++) {
            converter.accept(0.5 * Math.sin(i * 2 * Math.PI * 1000 / 44_100), i * 1_000_000_000L / 44_100);
        }
        assertEquals(50, sink.frames.size());
        int previous = 0;
        int rising = 0;
        int peak = 0;
        for (var frame : sink.frames.subList(1, 50)) {
            for (int i = 0; i < 960; i++) {
                int value = sample(frame, i);
                if (previous < 0 && value >= 0) rising++;
                peak = Math.max(peak, Math.abs(value));
                previous = value;
            }
        }
        assertEquals(980, rising, 1);
        assertEquals(16384, peak, 20);
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void controlledHelperClosesItsPipeAndReleasesAWaitingReader() throws Exception {
        var stream = macHeader(48_000);
        macBlock(stream, 1_000_000_000, 0, 960, 1, 1);
        Files.write(temp.resolve("fixture"), stream.toByteArray());
        Path helper = Files.writeString(temp.resolve("test helper"), """
                #!/bin/sh
                test "$1" = stream-checked-controlled && test "$2" = 18:32:0 || exit 9
                cd "$(dirname "$0")" || exit 8
                cat fixture
                cat >/dev/null
                touch closed
                """);
        Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwx------"));
        try (var source = NativeCapture.open(EmbeddedSender.Platform.MAC_ARM64, helper, List.of("18:32:0"), 20)) {
            long index = 0;
            int last = 0;
            long limit = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (!source.ready() && System.nanoTime() - limit < 0) {
                last = leRead(source.readFrame(index++)).getShort();
                Thread.sleep(5);
            }
            assertTrue(source.ready());
            assertEquals(26214, last);
            long next = index;
            var waiter = new FutureTask<>(() -> assertThrows(JavaUpload.Failure.class, () -> source.readFrame(next)).code());
            Thread.ofVirtual().start(waiter);
            source.close();
            assertEquals("capture_closed", waiter.get(2, TimeUnit.SECONDS));
            assertTrue(Files.exists(temp.resolve("closed")));
        }
    }

    private static ByteArrayOutputStream macHeader(int rate) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(le(8).putInt(0x54435653).putInt(rate).array());
        return output;
    }

    private static void macBlock(ByteArrayOutputStream out, long ns, long start, int frames, float left, float right) {
        var record = le(24 + frames * 8).putLong(ns).putLong(start).putInt(frames).putInt(0);
        for (int i = 0; i < frames; i++) record.putFloat(left).putFloat(right);
        out.writeBytes(record.array());
    }

    private static void winRecord(ByteArrayOutputStream out, long stamp, int track, int frames, int left, int right) {
        var record = le(16 + frames * 4).putLong(stamp).putInt(track).putInt(frames);
        for (int i = 0; i < frames; i++) record.putShort((short) left).putShort((short) right);
        out.writeBytes(record.array());
    }

    private static ByteBuffer le(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer leRead(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static short sample(PcmFrameConverter.Frame frame, int index) {
        return leRead(frame.pcm()).getShort(index * 2);
    }

    private static FilterInputStream shortReads(ByteArrayOutputStream output) {
        return new FilterInputStream(new ByteArrayInputStream(output.toByteArray())) {
            @Override public int read(byte[] target, int offset, int count) throws IOException {
                return super.read(target, offset, Math.min(count, 5));
            }
        };
    }

    private static java.util.function.LongSupplier clock(long step) {
        var time = new AtomicLong(5_000_000_000L);
        return () -> time.getAndAdd(step);
    }

    private static final class TestSink implements CapturePipes.Sink {
        final List<PcmFrameConverter.Frame> frames = new ArrayList<>();
        final List<Boolean> advances = new ArrayList<>();
        @Override public void pulse(long now, boolean advanced) { advances.add(advanced); }
        @Override public void frame(PcmFrameConverter.Frame frame) { frames.add(frame); }
    }
}

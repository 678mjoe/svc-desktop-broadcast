package page.mjoe.svcbroadcast.sender;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.LongSupplier;

/** Reads timestamped audio produced by the existing native helpers. */
final class CapturePipes {
    static final long MAX_AGE_NANOS = 200_000_000;
    private static final long SECOND = 1_000_000_000;
    private static final int WINDOWS_RATE = 44_100;
    private static final int WINDOWS_BLOCK = WINDOWS_RATE / 50;
    private static final int WINDOWS_CAPACITY = WINDOWS_RATE / 5;
    private static final int WINDOWS_HOLDBACK = WINDOWS_BLOCK * 4;
    private static final int WINDOWS_READY_BLOCKS = 4;
    private static final int MAC_MAX_FRAMES = 4_800;

    interface Sink {
        void pulse(long observedAt, boolean advanced);
        void frame(PcmFrameConverter.Frame frame);
    }

    private CapturePipes() {}

    static void readMac(InputStream input, int tracks, LongSupplier clock, Sink sink) throws IOException {
        var format = buffer(8);
        if (!readRecord(input, format, 8)) return;
        int magic = format.getInt();
        int rate = format.getInt();
        if (magic != 0x54435653 || (rate != 44_100 && rate != 48_000)) { // SVCT
            throw new IOException("Invalid Core Audio format");
        }
        sink.pulse(clock.getAsLong(), true);
        var converter = new PcmFrameConverter(rate, sink::frame);
        var nativeClock = new NativeClock(SECOND);
        var header = buffer(24);
        var payload = buffer(MAC_MAX_FRAMES * 8);
        long inputEnd = 0;
        long lastStamp = 0;
        boolean gap = false;
        while (readRecord(input, header, 24)) {
            long stamp = header.getLong();
            long start = header.getLong();
            int frames = header.getInt();
            if (header.getInt() != 0 || stamp <= lastStamp || start < inputEnd
                    || frames < 1 || frames > MAC_MAX_FRAMES || start > Long.MAX_VALUE - frames) {
                throw new IOException("Invalid Core Audio block");
            }
            readPayload(input, payload, frames * 8);
            long now = clock.getAsLong();
            long capturedAt = nativeClock.observe(stamp, now) - sampleNanos(frames, rate);
            if (gap || start != inputEnd) converter.reset();
            inputEnd = start + frames;
            lastStamp = stamp;
            gap = now - capturedAt >= MAX_AGE_NANOS;
            sink.pulse(now, !gap);
            for (int i = 0; i < frames; i++) {
                float left = payload.getFloat();
                float right = payload.getFloat();
                if (!Float.isFinite(left) || !Float.isFinite(right)) {
                    throw new IOException("Non-finite Core Audio sample");
                }
                if (!gap) {
                    double mono = (left + (double) right) * (0.4 / tracks);
                    converter.accept(mono, capturedAt + sampleNanos(i, rate));
                }
            }
        }
    }

    static void readWindows(InputStream input, int tracks, LongSupplier clock, Sink sink) throws IOException {
        var mixer = new WindowsTimeline(tracks, sink);
        var header = buffer(16);
        var payload = buffer(WINDOWS_CAPACITY * 4);
        while (readRecord(input, header, 16)) {
            long stamp = header.getLong();
            int index = header.getInt();
            int frames = header.getInt();
            if (stamp <= 0) throw new IOException("Invalid WASAPI timestamp");
            long position = windowsSampleTime(stamp);
            if (index == 0 && frames == 0) {
                mixer.heartbeat(position, clock.getAsLong());
            } else {
                if (index < 1 || index > tracks || frames < 1 || frames > WINDOWS_CAPACITY) {
                    throw new IOException("Invalid WASAPI packet");
                }
                readPayload(input, payload, frames * 4);
                mixer.add(index - 1, position, frames, payload);
            }
        }
    }

    // Avoid multiplying the full QPC value, which can overflow on a long-running host.
    static long windowsSampleTime(long qpc100ns) {
        return (qpc100ns / 10_000_000) * WINDOWS_RATE
                + (qpc100ns % 10_000_000 * WINDOWS_RATE + 5_000_000) / 10_000_000;
    }

    private static long sampleNanos(long samples, long rate) {
        return samples / rate * SECOND + samples % rate * SECOND / rate;
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static boolean readRecord(InputStream input, ByteBuffer target, int bytes) throws IOException {
        int received = input.readNBytes(target.array(), 0, bytes);
        if (received == 0) return false;
        if (received != bytes) throw new EOFException("Truncated capture header");
        target.position(0);
        return true;
    }

    private static void readPayload(InputStream input, ByteBuffer target, int bytes) throws IOException {
        if (input.readNBytes(target.array(), 0, bytes) != bytes) throw new EOFException("Truncated capture payload");
        target.position(0);
    }

    /** Relates timestamps by observed deltas because the JVM clock may use a different origin. */
    private static final class NativeClock {
        private final long unitsPerSecond;
        private boolean anchored;
        private long firstNative;
        private long firstLocal;

        NativeClock(long unitsPerSecond) { this.unitsPerSecond = unitsPerSecond; }

        long observe(long stamp, long now) throws IOException {
            if (!anchored) {
                firstNative = stamp;
                firstLocal = now;
                anchored = true;
                return now;
            }
            long projected = localTime(stamp);
            long ahead = projected - now;
            if (ahead > 0) {
                // A faster read may improve the clock estimate. Late reads cannot make old frames new.
                firstLocal -= ahead;
                return now;
            }
            return projected;
        }

        long localTime(long stamp) throws IOException {
            long delta = stamp - firstNative;
            if (Math.abs(delta / unitsPerSecond) > Long.MAX_VALUE / SECOND - 1) {
                throw new IOException("Capture clock overflow");
            }
            return firstLocal + sampleNanos(delta, unitsPerSecond);
        }
    }

    /** Fixed-size per-track timeline. Missing packets within a progressing clock represent silence. */
    private static final class WindowsTimeline {
        private final int[][] mono;
        private final Sink sink;
        private final PcmFrameConverter converter;
        private final NativeClock time = new NativeClock(WINDOWS_RATE);
        private long cursor;
        private long horizon;
        private boolean initialized;

        WindowsTimeline(int tracks, Sink sink) {
            mono = new int[tracks][WINDOWS_CAPACITY];
            this.sink = sink;
            converter = new PcmFrameConverter(WINDOWS_RATE, sink::frame);
        }

        void heartbeat(long position, long observedAt) throws IOException {
            if (!initialized) {
                cursor = position - WINDOWS_HOLDBACK;
                horizon = cursor;
                initialized = true;
            }
            boolean advanced = position > horizon;
            sink.pulse(observedAt, advanced);
            if (!advanced) return;
            horizon = position;
            time.observe(horizon, observedAt);
            long blocks = (horizon - WINDOWS_HOLDBACK - cursor) / WINDOWS_BLOCK;
            long skipped = Math.max(0, blocks - WINDOWS_READY_BLOCKS);
            if (skipped > 0) {
                clear(skipped * WINDOWS_BLOCK);
                converter.reset();
                blocks -= skipped;
            }
            for (int i = 0; i < blocks; i++) mix();
        }

        void add(int track, long start, int frames, ByteBuffer input) {
            if (!initialized) return;
            long from = Math.max(start, cursor);
            long to = Math.min(start + frames, cursor + WINDOWS_CAPACITY);
            if (to <= from) return;
            input.position((int) (from - start) * 4);
            for (long pos = from; pos < to; pos++) {
                mono[track][slot(pos)] = input.getShort() + input.getShort();
            }
        }

        private void mix() throws IOException {
            long capturedAt = time.localTime(cursor);
            if (time.localTime(horizon) - capturedAt >= MAX_AGE_NANOS) {
                clear(WINDOWS_BLOCK);
                converter.reset();
                return;
            }
            double gain = 0.4 / (32768 * mono.length);
            for (int i = 0; i < WINDOWS_BLOCK; i++) {
                int slot = slot(cursor + i);
                int sum = 0;
                for (int[] track : mono) {
                    sum += track[slot];
                    track[slot] = 0;
                }
                converter.accept(sum * gain, capturedAt + sampleNanos(i, WINDOWS_RATE));
            }
            cursor += WINDOWS_BLOCK;
        }

        private void clear(long frames) {
            if (frames >= WINDOWS_CAPACITY) {
                for (int[] track : mono) Arrays.fill(track, 0);
            } else {
                for (long i = cursor; i < cursor + frames; i++) {
                    int slot = slot(i);
                    for (int[] track : mono) track[slot] = 0;
                }
            }
            cursor += frames;
        }

        private static int slot(long position) {
            return Math.floorMod(position, WINDOWS_CAPACITY);
        }
    }
}

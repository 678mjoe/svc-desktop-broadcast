package page.mjoe.svcbroadcast;

import io.netty.buffer.ByteBuf;

import java.util.Objects;
import java.util.UUID;

/** Bounded PCM handoff. The returned output/silence arrays belong to the single audio consumer. */
public final class PcmStream {
    static final int FRAME_SAMPLES = 960;
    static final int FRAME_BYTES = FRAME_SAMPLES * Short.BYTES;
    static final int CAPACITY = 10;
    static final int PREBUFFER = 3;
    static final long FRAME_NANOS = 20_000_000L;
    static final long MAX_AGE_NANOS = 200_000_000L;
    static final long IDLE_NANOS = 2_000_000_000L;
    static final long ARRIVAL_RATE = 55;
    // Arrival bursts and playback buffering are different limits. A reliable
    // transport may release up to one idle window of legitimate 50 fps audio
    // at once. Accept that bounded batch, then discard stale PCM below; never
    // grow the 10-frame playback queue or replay its backlog.
    static final long ARRIVAL_BURST_FRAMES = IDLE_NANOS * ARRIVAL_RATE / 1_000_000_000L;
    private static final long TOKEN = 1_000_000_000L;
    private final UUID sessionId;
    private final short[][] frames = new short[CAPACITY][FRAME_SAMPLES];
    private final long[] sourceTimes = new long[CAPACITY];
    private final short[] output = new short[FRAME_SAMPLES];
    private final short[] silence = new short[FRAME_SAMPLES];
    private int first;
    private int count;
    private boolean buffering = true;
    private boolean finished;
    private String terminalReason;
    private long sourceEpoch;
    private long lastSequence = -1;
    private long lastFrameNanos;
    private long bucketNanos;
    private long tokens = ARRIVAL_BURST_FRAMES * TOKEN;
    private long received;
    private long real;
    private long silent;
    private long dropped;

    public PcmStream(UUID sessionId) {
        this(sessionId, System.nanoTime());
    }

    PcmStream(UUID sessionId, long nowNanos) {
        this.sessionId = Objects.requireNonNull(sessionId);
        lastFrameNanos = nowNanos;
        bucketNanos = nowNanos;
    }

    /** Decodes before returning; neither retains the buffer nor changes its reader index. */
    public synchronized void offer(long sequence, ByteBuf pcm, long nowNanos) {
        if (finished || terminalReason != null || expireIdle(nowNanos)) {
            throw new AudioProtocol.Failure("stream_ended");
        }
        if (pcm.readableBytes() != FRAME_BYTES) {
            throw new AudioProtocol.Failure("invalid_size");
        }
        if (sequence < 0 || sequence <= lastSequence || (lastSequence == -1 && sequence != 0)) {
            throw new AudioProtocol.Failure("invalid_sequence");
        }
        long epoch = lastSequence == -1 ? nowNanos : sourceEpoch;
        long elapsed = Math.max(0, nowNanos - epoch);
        if (sequence > elapsed / FRAME_NANOS + CAPACITY) {
            throw new AudioProtocol.Failure("future_sequence");
        }
        long refill = Math.max(0, nowNanos - bucketNanos);
        // Saturating before multiplication also handles an arbitrarily long idle clock jump.
        long limit = ARRIVAL_BURST_FRAMES * TOKEN;
        tokens = refill >= limit / ARRIVAL_RATE + 1
                ? limit : Math.min(limit, tokens + refill * ARRIVAL_RATE);
        bucketNanos = nowNanos;
        if (tokens < TOKEN) {
            throw new AudioProtocol.Failure("rate_exceeded");
        }
        tokens -= TOKEN;
        long phaseError = nowNanos - epoch - sequence * FRAME_NANOS;
        // Slew at most 20 us per accepted frame (1000 ppm at 50 fps). A delayed
        // backlog beyond the age limit cannot reset its epoch and become fresh again.
        if (phaseError > -MAX_AGE_NANOS && phaseError < MAX_AGE_NANOS) {
            epoch += Math.max(-20_000L, Math.min(20_000L, phaseError));
        }
        sourceEpoch = epoch;
        lastSequence = sequence;
        lastFrameNanos = nowNanos;
        received = increment(received);
        long sourceTime = epoch + sequence * FRAME_NANOS;
        discardExpired(nowNanos);
        if (nowNanos - sourceTime >= MAX_AGE_NANOS) {
            dropped = increment(dropped);
            return;
        }
        if (count == CAPACITY) {
            discardFirst();
        }
        int slot = (first + count) % CAPACITY;
        int offset = pcm.readerIndex();
        for (int sample = 0; sample < FRAME_SAMPLES; sample++) {
            frames[slot][sample] = pcm.getShortLE(offset + sample * Short.BYTES);
        }
        sourceTimes[slot] = nowNanos - sourceTime < 0 ? nowNanos : sourceTime;
        count++;
    }

    /** One call per audio tick; null is terminal, a zero-filled array is a temporary gap. */
    public synchronized short[] poll(long nowNanos) {
        if (terminalReason != null || expireIdle(nowNanos)) {
            return null;
        }
        discardExpired(nowNanos);
        if (finished && count == 0) {
            terminalReason = "completed";
            return null;
        }
        // Coarse drift correction retains the newest three frames. This is not resampling.
        if (!finished && count > PREBUFFER * 2) {
            while (count > PREBUFFER) {
                discardFirst();
            }
        }
        if (!finished && (count == 0 || (buffering && count < PREBUFFER))) {
            buffering = true;
            silent = increment(silent);
            return silence;
        }
        buffering = false;
        System.arraycopy(frames[first], 0, output, 0, FRAME_SAMPLES);
        first = (first + 1) % CAPACITY;
        count--;
        real = increment(real);
        return output;
    }

    public synchronized void finish() {
        if (terminalReason == null) {
            finished = true;
        }
    }

    public synchronized void stop() {
        stop("stopped");
    }

    synchronized void stop(String reason) {
        if (terminalReason == null) {
            terminalReason = reason;
        }
        while (count != 0) {
            discardFirst();
        }
    }

    synchronized boolean expireIdle(long nowNanos) {
        if (terminalReason == null && !finished && nowNanos - lastFrameNanos >= IDLE_NANOS) {
            stop("timeout");
        }
        return "timeout".equals(terminalReason);
    }

    synchronized String terminalReason() {
        return terminalReason;
    }

    synchronized int queuedFrames() {
        return count;
    }

    public synchronized String summary() {
        String state = terminalReason != null ? terminalReason : finished ? "draining" : "streaming";
        return state + "; received=" + received + ", played=" + real + ", underrun=" + silent
                + ", dropped=" + dropped + ", bufferFrames=" + count;
    }

    UUID sessionId() {
        return sessionId;
    }

    private void discardExpired(long nowNanos) {
        while (count != 0 && nowNanos - sourceTimes[first] >= MAX_AGE_NANOS) {
            discardFirst();
        }
    }

    private void discardFirst() {
        first = (first + 1) % CAPACITY;
        count--;
        dropped = increment(dropped);
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }
}

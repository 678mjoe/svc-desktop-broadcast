package page.mjoe.svcbroadcast;

/** Finite 48 kHz mono test source. The returned 20 ms buffer is reused synchronously. */
final class TestTone {
    static final int SAMPLE_RATE = 48_000;
    static final int FRAME_SAMPLES = 960;
    static final int MAX_SECONDS = 30;
    static final int PEAK = 819; // About -32 dBFS; listeners still control their own volume.
    private static final int FADE_SAMPLES = SAMPLE_RATE / 100;
    private static final double RADIANS_PER_SAMPLE = 2.0 * Math.PI * 440.0 / SAMPLE_RATE;

    private final short[] frame = new short[FRAME_SAMPLES];
    private final int totalSamples;
    private int position;

    TestTone(int seconds) {
        if (seconds < 1 || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("Test duration must be between 1 and 30 seconds");
        }
        totalSamples = seconds * SAMPLE_RATE;
    }

    /** Advance to the current clock frame without replaying overdue audio. */
    void skipToFrame(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Frame index cannot be negative");
        }
        position = (int) Math.min(totalSamples, Math.max(position, (long) index * FRAME_SAMPLES));
    }

    short[] nextFrame() {
        if (position >= totalSamples) {
            return null;
        }
        for (int i = 0; i < frame.length; i++, position++) {
            double envelope = Math.min(1.0,
                    Math.min(position, totalSamples - 1 - position) / (double) FADE_SAMPLES);
            frame[i] = (short) Math.round(PEAK * envelope * Math.sin(position * RADIANS_PER_SAMPLE));
        }
        return frame;
    }
}

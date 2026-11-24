package page.mjoe.svcbroadcast.sender;

import java.util.Arrays;
import java.util.function.Consumer;

/** Converts mono samples to 20 ms of 48 kHz PCM16LE without buffering a whole stream. */
final class PcmFrameConverter {
    private static final int OUTPUT_RATE = 48_000;
    private static final int TAPS = 64;
    private static final int PHASES = 160;
    private static final int STEP = 147;
    private static final double[][] FILTER = coefficients();

    record Frame(byte[] pcm, long capturedAt) {}

    private final boolean resample;
    private final Consumer<Frame> sink;
    private final double[] history = new double[TAPS];
    private byte[] frame = new byte[1920];
    private long capturedAt;
    private int used;
    private long inputIndex = -1;
    private long nextInput;
    private int phase;

    PcmFrameConverter(int inputRate, Consumer<Frame> sink) {
        if (inputRate != 44_100 && inputRate != OUTPUT_RATE) throw new IllegalArgumentException("sample rate");
        resample = inputRate != OUTPUT_RATE;
        this.sink = sink;
    }

    void accept(double sample, long sampleTime) {
        if (!resample) {
            emit(sample, sampleTime);
            return;
        }
        inputIndex++;
        int slot = (int) (inputIndex % TAPS);
        history[slot] = sample;
        while (nextInput <= inputIndex) {
            double filtered = 0;
            double[] weights = FILTER[phase];
            for (int tap = 0; tap < TAPS; tap++) {
                filtered += weights[tap] * history[(slot - tap + TAPS) % TAPS];
            }
            // The filter is causal and delays the signal by roughly 32 input samples.
            emit(filtered, sampleTime - 725_624L);
            phase += STEP;
            nextInput += phase / PHASES;
            phase %= PHASES;
        }
    }

    void reset() {
        Arrays.fill(history, 0);
        used = 0;
        inputIndex = -1;
        nextInput = 0;
        phase = 0;
    }

    private void emit(double sample, long sampleTime) {
        if (used == 0) capturedAt = sampleTime;
        int encoded = (int) Math.rint(Math.max(-1, Math.min(32767.0 / 32768, sample)) * 32768);
        frame[used++] = (byte) encoded;
        frame[used++] = (byte) (encoded >> 8);
        if (used == frame.length) {
            sink.accept(new Frame(frame, capturedAt));
            frame = new byte[1920];
            used = 0;
        }
    }

    private static double[][] coefficients() {
        double cutoff = 40_000.0 / 44_100; // 20 kHz low-pass, normalized to Nyquist.
        var phases = new double[PHASES][TAPS];
        for (int phase = 0; phase < PHASES; phase++) {
            double sum = 0;
            for (int tap = 0; tap < TAPS; tap++) {
                double angle = 2 * Math.PI * tap / (TAPS - 1);
                double window = 0.42 - 0.5 * Math.cos(angle) + 0.08 * Math.cos(2 * angle);
                double distance = tap + (double) phase / PHASES - (TAPS - 1) / 2.0;
                double x = Math.PI * cutoff * distance;
                double weight = cutoff * (x == 0 ? 1 : Math.sin(x) / x) * window;
                phases[phase][tap] = weight;
                sum += weight;
            }
            for (int tap = 0; tap < TAPS; tap++) phases[phase][tap] /= sum;
        }
        return phases;
    }
}

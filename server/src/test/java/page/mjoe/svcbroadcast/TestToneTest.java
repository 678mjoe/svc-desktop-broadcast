package page.mjoe.svcbroadcast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestToneTest {
    @Test
    void producesExactDurationWithQuietPeakAndFadedEndpoints() {
        TestTone tone = new TestTone(2);
        int samples = 0;
        int peak = 0;
        short first = -1;
        short last = -1;
        short[] frame;
        while ((frame = tone.nextFrame()) != null) {
            assertEquals(960, frame.length, "SVC requires 20 ms frames at 48 kHz");
            if (samples == 0) {
                first = frame[0];
            }
            for (short value : frame) {
                peak = Math.max(peak, Math.abs((int) value));
            }
            samples += frame.length;
            last = frame[frame.length - 1];
        }
        assertEquals(96_000, samples);
        assertEquals(0, first, "Fade in avoids a start click");
        assertEquals(0, last, "Fade out avoids a stop click");
        assertTrue(peak > 750 && peak <= 819, "Audible test tone must stay below -32 dBFS");
        assertNull(tone.nextFrame(), "An exhausted test must never restart");
    }

    @Test
    void lateFramesAreSkippedWithoutReplayingOrExtendingTheTest() {
        TestTone tone = new TestTone(1);
        tone.nextFrame();
        tone.skipToFrame(49);
        short[] finalFrame = tone.nextFrame();
        assertEquals(0, finalFrame[959], "Clock jump still reaches the final fade-out");
        assertNull(tone.nextFrame(), "Missed frames must not be queued for later playback");
        tone.skipToFrame(0);
        assertNull(tone.nextFrame(), "An older timestamp cannot rewind the source");

        TestTone expired = new TestTone(30);
        expired.skipToFrame(Integer.MAX_VALUE);
        assertNull(expired.nextFrame(), "Very late timestamps cannot overflow back into audio");
    }

    @Test
    void rejectsUnboundedOrEmptyTestDurations() {
        assertThrows(IllegalArgumentException.class, () -> new TestTone(0));
        assertThrows(IllegalArgumentException.class, () -> new TestTone(-1));
        assertThrows(IllegalArgumentException.class, () -> new TestTone(31));
        assertThrows(IllegalArgumentException.class, () -> new TestTone(Integer.MAX_VALUE));
    }
}

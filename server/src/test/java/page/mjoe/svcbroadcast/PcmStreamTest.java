package page.mjoe.svcbroadcast;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PcmStreamTest {
    @Test
    void subTimeoutNetworkBatchDiscardsBacklogWithoutEndingTheStream() {
        ByteBuf pcm = frame(0);
        try {
            PcmStream stream = stream();
            stream.offer(0, pcm, 0);
            // A 50 fps producer continues while the transport holds 1.9 s of data.
            for (int sequence = 1; sequence <= 95; sequence++) {
                pcm.setShortLE(0, sequence);
                stream.offer(sequence, pcm, 1_900_000_000L);
                assertTrue(stream.queuedFrames() <= PcmStream.CAPACITY);
            }
            assertNull(stream.terminalReason());
            assertEquals(93, stream.poll(1_900_000_000L)[0]);
            assertEquals(94, stream.poll(1_920_000_000L)[0]);
            assertEquals(95, stream.poll(1_940_000_000L)[0]);
            assertArrayEquals(new short[960], stream.poll(1_960_000_000L));
        } finally {
            pcm.release();
        }
    }

    @Test
    void periodicTransportBatchesDoNotAccumulatePlaybackBacklog() {
        ByteBuf pcm = frame(1);
        try {
            PcmStream stream = stream();
            stream.offer(0, pcm, 0);
            for (int batch = 1; batch <= 150; batch++) {
                long now = batch * 400_000_000L;
                for (int sequence = (batch - 1) * 20 + 1; sequence <= batch * 20; sequence++) {
                    stream.offer(sequence, pcm, now);
                    assertTrue(stream.queuedFrames() <= PcmStream.CAPACITY);
                }
                assertNotNull(stream.poll(now));
            }
            assertNull(stream.terminalReason());
        } finally {
            pcm.release();
        }
    }

    @Test
    void prebuffersCopiesLittleEndianPcmAndReusesOutputWithoutRetainingInput() {
        PcmStream stream = stream();
        short[] silence = stream.poll(0);
        assertArrayEquals(new short[960], silence);
        ByteBuf pcm = frame(0x1234);
        try {
            stream.offer(0, pcm, 0);
            assertEquals(0, pcm.readerIndex());
            assertEquals(1, pcm.refCnt());
            pcm.setShortLE(0, -12);
            stream.offer(1, pcm, 20_000_000L);
            assertSame(silence, stream.poll(20_000_000L));
            pcm.setShortLE(0, -32768);
            stream.offer(2, pcm, 40_000_000L);
        } finally {
            pcm.release();
        }
        short[] output = stream.poll(40_000_000L);
        assertEquals(0x1234, output[0], "The caller may mutate/release PCM immediately after offer");
        assertEquals(0x1234, output[959]);
        assertSame(output, stream.poll(60_000_000L));
        assertEquals(-12, output[0]);
        assertSame(output, stream.poll(80_000_000L));
        assertEquals(-32768, output[0]);
        assertSame(silence, stream.poll(100_000_000L), "Underrun is silence, not end of stream");
        assertTrue(stream.summary().contains("received=3, played=3, underrun=3"));
    }

    @Test
    void boundsCapacityDropsOldestAndTrimsExcessTowardPrebuffer() {
        PcmStream stream = stream();
        ByteBuf pcm = frame(0);
        try {
            for (int sequence = 0; sequence < 10; sequence++) {
                pcm.setShortLE(0, sequence);
                stream.offer(sequence, pcm, 0);
            }
            assertEquals(10, stream.queuedFrames());
            pcm.setShortLE(0, 10);
            stream.offer(10, pcm, 20_000_000L);
            assertEquals(10, stream.queuedFrames());
            assertTrue(stream.summary().contains("dropped=1"));
            assertEquals(8, stream.poll(20_000_000L)[0]);
            assertEquals(2, stream.queuedFrames());
            assertTrue(stream.summary().contains("dropped=8"));
        } finally {
            pcm.release();
        }
    }

    @Test
    void finishDrainsShortAndFullBuffersButStopDiscardsImmediately() {
        ByteBuf pcm = frame(7);
        try {
            PcmStream shortStream = stream();
            shortStream.offer(0, pcm, 0);
            shortStream.finish();
            assertEquals(7, shortStream.poll(0)[0], "EOF bypasses the three-frame prebuffer");
            assertNull(shortStream.poll(20_000_000L));
            shortStream.stop();
            assertEquals("completed", shortStream.terminalReason(), "Host cleanup must preserve completion");
            assertThrows(AudioProtocol.Failure.class, () -> shortStream.offer(1, pcm, 20_000_000L));

            PcmStream fullStream = stream();
            for (int sequence = 0; sequence < 10; sequence++) {
                fullStream.offer(sequence, pcm, sequence * PcmStream.FRAME_NANOS);
            }
            fullStream.finish();
            for (int index = 0; index < 10; index++) {
                assertEquals(7, fullStream.poll(180_000_000L)[0]);
            }
            assertNull(fullStream.poll(180_000_000L));
            assertTrue(fullStream.summary().contains("dropped=0"));

            PcmStream stopped = stream();
            stopped.offer(0, pcm, 0);
            stopped.stop();
            assertNull(stopped.poll(0));
            assertEquals(0, stopped.queuedFrames());
            assertTrue(stopped.summary().contains("dropped=1"));
        } finally {
            pcm.release();
        }
    }

    @Test
    void staleSourceFramesAndOldQueuedAudioCannotBeReplayed() {
        PcmStream stream = stream();
        ByteBuf pcm = frame(3);
        try {
            stream.offer(0, pcm, 0);
            stream.offer(1, pcm, 250_000_000L);
            stream.offer(2, pcm, 270_000_000L);
            assertEquals(0, stream.queuedFrames(), "An abrupt backlog must not reset the source epoch");
            assertTrue(stream.summary().contains("dropped=3"));
            stream.offer(14, pcm, 280_000_000L);
            stream.finish();
            assertEquals(3, stream.poll(280_000_000L)[0], "A source gap resumes at fresh audio");
            assertNull(stream.poll(300_000_000L));

            PcmStream queued = stream();
            for (int sequence = 0; sequence < 3; sequence++) {
                queued.offer(sequence, pcm, 0);
            }
            assertArrayEquals(new short[960], queued.poll(200_000_000L),
                    "Even early source timestamps cannot extend queue residence beyond 200 ms");
            assertEquals(0, queued.queuedFrames());
        } finally {
            pcm.release();
        }
    }

    @Test
    void rejectsWrongSizesReplayFutureJumpsAndSustainedExcess() {
        ByteBuf pcm = frame(0);
        ByteBuf wrong = Unpooled.buffer(1).writeByte(0);
        try {
            PcmStream stream = stream();
            assertCode("invalid_size", () -> stream.offer(0, wrong, 0));
            assertCode("invalid_sequence", () -> stream.offer(1, pcm, 0));
            assertCode("invalid_sequence", () -> stream.offer(-1, pcm, 0));
            stream.offer(0, pcm, 0);
            assertCode("invalid_sequence", () -> stream.offer(0, pcm, 0));
            assertCode("future_sequence", () -> stream.offer(11, pcm, 0));
            assertCode("future_sequence", () -> stream.offer(Long.MAX_VALUE, pcm, 0));
            for (int sequence = 1; sequence < 10; sequence++) {
                stream.offer(sequence, pcm, 0);
            }
            stream.offer(10, pcm, 0);
            assertTrue(stream.summary().contains("received=11"));

            // Slow old input keeps the session alive, then an excessive burst
            // is still rejected even though its source indices are in the past.
            PcmStream excess = stream();
            excess.offer(0, pcm, 0);
            excess.offer(1, pcm, 1_000_000_000L);
            excess.offer(2, pcm, 2_000_000_000L);
            for (int sequence = 3; sequence < 3 + PcmStream.ARRIVAL_BURST_FRAMES; sequence++) {
                excess.offer(sequence, pcm, 2_100_000_000L);
            }
            assertCode("rate_exceeded", () -> excess.offer(113, pcm, 2_100_000_000L));
        } finally {
            pcm.release();
            wrong.release();
        }
    }

    @Test
    void initialAndActiveIdleTimeoutsAreTerminalAndSilenceInputRemainsValid() {
        PcmStream neverStarted = stream();
        assertNotNull(neverStarted.poll(PcmStream.IDLE_NANOS - 1));
        assertNull(neverStarted.poll(PcmStream.IDLE_NANOS));
        assertEquals("timeout", neverStarted.terminalReason());
        ByteBuf pcm = frame(0);
        try {
            assertCode("stream_ended", () -> neverStarted.offer(0, pcm, PcmStream.IDLE_NANOS));
            PcmStream inputSilence = stream();
            for (int sequence = 0; sequence < 101; sequence++) {
                long now = sequence * PcmStream.FRAME_NANOS;
                inputSilence.offer(sequence, pcm, now);
                assertNotNull(inputSilence.poll(now));
            }
            assertNull(inputSilence.terminalReason());
            assertTrue(inputSilence.summary().contains("received=101"));
            assertNull(inputSilence.poll(4_000_000_000L));
            assertEquals("timeout", inputSilence.terminalReason());

            PcmStream lateFirstFrame = stream();
            assertCode("stream_ended", () -> lateFirstFrame.offer(0, pcm, PcmStream.IDLE_NANOS));
        } finally {
            pcm.release();
        }
    }

    @Test
    void graduallyTracksBothSignsOfClockDriftForOverAnHourWithoutDropping() {
        ByteBuf pcm = frame(1);
        try {
            for (long period : new long[]{19_998_000L, 20_002_000L}) {
                PcmStream stream = stream();
                for (int sequence = 0; sequence <= 200_000; sequence++) {
                    long now = sequence * period;
                    stream.offer(sequence, pcm, now);
                    assertNotNull(stream.poll(now));
                }
                assertNull(stream.terminalReason());
                assertTrue(stream.summary().contains("received=200001"));
                assertTrue(stream.summary().contains("dropped=0"));
                assertTrue(stream.queuedFrames() <= PcmStream.PREBUFFER);
            }
        } finally {
            pcm.release();
        }
    }

    private static PcmStream stream() {
        return new PcmStream(UUID.randomUUID(), 0);
    }

    private static ByteBuf frame(int sample) {
        ByteBuf pcm = Unpooled.buffer(PcmStream.FRAME_BYTES);
        for (int index = 0; index < PcmStream.FRAME_SAMPLES; index++) {
            pcm.writeShortLE(sample);
        }
        return pcm;
    }

    private static void assertCode(String expected, Runnable operation) {
        assertEquals(expected, assertThrows(AudioProtocol.Failure.class, operation::run).code());
    }
}

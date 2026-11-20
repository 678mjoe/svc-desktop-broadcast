package page.mjoe.svcbroadcast;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AudioProtocolTest {
    static final String START = "{\"type\":\"start\",\"version\":1,\"sample_rate\":48000,"
            + "\"channels\":1,\"encoding\":\"pcm_s16le\",\"frame_samples\":960}";

    @Test
    void acceptsOnlyTheFixedStartFormatAndMinimalControlMessages() {
        assertEquals(AudioProtocol.Command.START, AudioProtocol.parse(START));
        assertEquals(AudioProtocol.Command.END, AudioProtocol.parse("{\"type\":\"end\"}"));
        assertEquals(AudioProtocol.Command.STOP, AudioProtocol.parse("{\"type\":\"stop\"}"));
        assertCode("unsupported_version", START.replace("\"version\":1", "\"version\":2"));
        assertCode("unsupported_format", START.replace("48000", "44100"));
        assertCode("unsupported_format", START.replace("\"channels\":1", "\"channels\":2"));
        assertCode("unsupported_format", START.replace("pcm_s16le", "pcm_f32le"));
        assertCode("unsupported_format", START.replace("960", "480"));
        assertCode("unsupported_format", START.replace("\"channels\":1,", ""));
    }

    @Test
    void rejectsDuplicateFieldsCoercionsExtensionsAndLenientJson() {
        for (String invalid : new String[]{
                "{\"type\":\"end\",\"type\":\"stop\"}",
                "{\"type\":\"stop\",\"secret\":\"do-not-echo\"}",
                "{\"type\":\"end\",\"version\":1}",
                "{type:'stop'}", "{\"type\":\"stop\",}", "/* comment */ {\"type\":\"stop\"}",
                "{\"type\":\"stop\"} {}", "[]", "null", "{\"type\":1}",
                START.replace("48000", "\"48000\""), START.replace("48000", "48000.0"),
                START.replace("\"channels\":1", "\"channels\":true")}) {
            AudioProtocol.Failure failure = assertThrows(AudioProtocol.Failure.class,
                    () -> AudioProtocol.parse(invalid));
            assertFalse(failure.getMessage().contains("do-not-echo"));
        }
    }

    @Test
    void readyAdvertisesTheExactSessionAndLimits() {
        UUID id = UUID.fromString("39fba251-4c89-466d-906b-79ac5a6ea9ea");
        assertEquals("{\"type\":\"ready\",\"version\":1,\"session_id\":\"" + id
                + "\",\"frame_samples\":960,\"max_buffer_frames\":10}", AudioProtocol.ready(id));
        assertEquals(1944, AudioProtocol.MESSAGE_BYTES);
    }

    private static void assertCode(String code, String input) {
        assertEquals(code, assertThrows(AudioProtocol.Failure.class, () -> AudioProtocol.parse(input)).code());
    }
}

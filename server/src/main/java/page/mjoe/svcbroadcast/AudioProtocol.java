package page.mjoe.svcbroadcast;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.UUID;

/** Fixed, deliberately small CLI protocol; errors never contain client-provided text. */
final class AudioProtocol {
    static final int HEADER_BYTES = 24;
    static final int MESSAGE_BYTES = HEADER_BYTES + PcmStream.FRAME_BYTES;
    static final int MAX_PAYLOAD = 2048;
    static final int MAX_JSON = 1024;
    private static final int ALL_START_FIELDS = 63;

    enum Command { START, END, STOP }

    static Command parse(String text) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            reader.beginObject();
            int fields = 0;
            String type = null;
            while (reader.hasNext()) {
                String name = reader.nextName();
                int field = switch (name) {
                    case "type" -> 1;
                    case "version" -> 2;
                    case "sample_rate" -> 4;
                    case "channels" -> 8;
                    case "encoding" -> 16;
                    case "frame_samples" -> 32;
                    default -> throw new Failure("invalid_message");
                };
                if ((fields & field) != 0) {
                    throw new Failure("invalid_message");
                }
                fields |= field;
                switch (name) {
                    case "type" -> type = string(reader);
                    case "version" -> number(reader, "1", "unsupported_version");
                    case "sample_rate" -> number(reader, "48000", "unsupported_format");
                    case "channels" -> number(reader, "1", "unsupported_format");
                    case "encoding" -> {
                        if (!"pcm_s16le".equals(string(reader))) {
                            throw new Failure("unsupported_format");
                        }
                    }
                    case "frame_samples" -> number(reader, "960", "unsupported_format");
                    default -> throw new Failure("invalid_message");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || type == null) {
                throw new Failure("invalid_message");
            }
            return switch (type) {
                case "start" -> {
                    if (fields != ALL_START_FIELDS) {
                        throw new Failure("unsupported_format");
                    }
                    yield Command.START;
                }
                case "end", "stop" -> {
                    if (fields != 1) {
                        throw new Failure("invalid_message");
                    }
                    yield "end".equals(type) ? Command.END : Command.STOP;
                }
                default -> throw new Failure("invalid_message");
            };
        } catch (IOException | IllegalStateException exception) {
            throw new Failure("invalid_message");
        }
    }

    static String ready(UUID sessionId) {
        return "{\"type\":\"ready\",\"version\":1,\"session_id\":\"" + sessionId
                + "\",\"frame_samples\":960,\"max_buffer_frames\":10}";
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            throw new Failure("invalid_message");
        }
        return reader.nextString();
    }

    private static void number(JsonReader reader, String expected, String code) throws IOException {
        if (reader.peek() != JsonToken.NUMBER || !expected.equals(reader.nextString())) {
            throw new Failure(code);
        }
    }

    static final class Failure extends IllegalArgumentException {
        private final String code;

        Failure(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private AudioProtocol() {}
}

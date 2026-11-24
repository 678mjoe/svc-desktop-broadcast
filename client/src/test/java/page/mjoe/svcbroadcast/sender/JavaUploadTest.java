package page.mjoe.svcbroadcast.sender;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class JavaUploadTest {
    private static final UUID SESSION = UUID.fromString("fe39ff35-2392-4036-93b8-0edea010be41");
    private static final String READY = "{\"type\":\"ready\",\"version\":1,\"frame_samples\":960,"
            + "\"max_buffer_frames\":10,\"session_id\":\"" + SESSION + "\"}";

    @Test void validatesWireFormatAndStrictControls() throws Exception {
        assertEquals(SESSION, JavaUpload.parseControl(READY).session());
        assertTrue(JavaUpload.parseControl("{\"type\":\"stopped\",\"reason\":\"completed\"}").completed());
        var invalid = new String[]{READY.replace("960", "\"960\""), READY.replace("1,", "1.0,"),
                READY.substring(0, READY.length() - 1) + ",\"type\":\"ready\"}",
                READY.replace(SESSION.toString(), "1-2-3-4-5"), READY + " {}", "[" + READY + "]",
                "{\"type\":\"stopped\",\"reason\":\"" + "中".repeat(342) + "\"}"};
        for (String text : invalid) {
            assertEquals("invalid_control", assertThrows(JavaUpload.Failure.class,
                    () -> JavaUpload.parseControl(text)).code());
        }
        String remoteSecret = "not-a-diagnostic";
        var error = assertThrows(JavaUpload.Failure.class,
                () -> JavaUpload.parseControl("{\"type\":\"error\",\"code\":\"" + remoteSecret + "\"}"));
        assertEquals("server_error", error.code());
        assertFalse(error.toString().contains(remoteSecret));

        byte[] pcm = new byte[1920];
        Arrays.fill(pcm, (byte) 0xaf);
        var frame = JavaUpload.packFrame(SESSION, 123, pcm);
        assertEquals(1944, frame.remaining());
        assertEquals(SESSION.getMostSignificantBits(), frame.getLong());
        assertEquals(SESSION.getLeastSignificantBits(), frame.getLong());
        assertEquals(123, frame.getLong());
        byte[] payload = new byte[1920];
        frame.get(payload);
        assertArrayEquals(pcm, payload);
        assertThrows(JavaUpload.Failure.class, () -> JavaUpload.packFrame(SESSION, -1, pcm));
        assertThrows(JavaUpload.Failure.class, () -> JavaUpload.packFrame(SESSION, 0, new byte[1919]));
    }

    @Test void checksDeviceEndpointBeforeUsingCredentials() throws Exception {
        JavaUpload.validateEndpoint(URI.create("wss://[::1]:24458/v1/device/audio"));
        for (String value : new String[]{"ws://127.0.0.1/v1/device/audio", "wss://127.0.0.1/v1/audio",
                "wss://test@127.0.0.1/v1/device/audio", "wss://127.0.0.1/v1/device/audio?",
                "wss://127.0.0.1/v1/device/audio#", "wss://127.0.0.1:/v1/device/audio",
                "wss://127.0.0.1:0/v1/device/audio", "wss://127.0.0.1:65536/v1/device/audio"}) {
            assertEquals("endpoint", assertThrows(JavaUpload.Failure.class,
                    () -> JavaUpload.validateEndpoint(URI.create(value))).code());
        }
    }

    @Test void cancellationBeforeRunDoesNotStartNetworkOrSource() throws Exception {
        var upload = new JavaUpload(null, null, null, () -> fail("Unexpected source"), state -> fail("Unexpected state"));
        upload.close();
        assertEquals(new JavaUpload.Result(0, 0, true), upload.run());
    }

    @Test void pacingSkipsMissedTicksWithoutReplayingOldFrames() {
        assertEquals(1, JavaUpload.nextTick(0, 5_000_000));
        assertEquals(10, JavaUpload.nextTick(0, 200_000_000));
        assertEquals(11, JavaUpload.nextTick(10, 201_000_000));
        assertEquals(100, JavaUpload.nextTick(11, 2_000_000_000));
    }

    @Test void aStalledSendAbortsWithoutStartingAnotherWrite() {
        var aborted = new AtomicBoolean();
        var socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[]{WebSocket.class}, (ignored, method, args) -> {
                    assertEquals("abort", method.getName());
                    assertTrue(aborted.compareAndSet(false, true));
                    return null;
                });
        assertEquals("send_timeout", assertThrows(JavaUpload.Failure.class,
                () -> JavaUpload.send(socket, new CompletableFuture<>())).code());
        assertTrue(aborted.get());
    }
}

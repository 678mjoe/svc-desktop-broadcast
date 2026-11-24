package page.mjoe.svcbroadcast.sender;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gives the server-side integration test access to client package-private pairing. */
public final class JavaProfileTestBridge {
    private JavaProfileTestBridge() {}

    public static JavaUpload pairAndUpload(Path directory, UUID player, String identityPacket, String offer,
                                           JavaUpload.SourceFactory source, Consumer<JavaUpload.State> state)
            throws IOException {
        String name = "java-device";
        var identity = JavaProfiles.parseIdentity(identityPacket);
        // This throwaway fixture tests interoperability, not Windows system ACL behavior.
        JavaProfiles.WindowsAcl fixtureAcl = (path, isDirectory) -> {};
        var store = new JavaProfiles(directory, fixtureAcl);
        assertTrue(store.inspect(name, player, identity).isEmpty());
        var issued = store.pair(name, player, identity, offer);

        var reopened = new JavaProfiles(directory, fixtureAcl);
        assertEquals(Optional.of(name), reopened.inspect(name, player, identity));
        var saved = reopened.load(name);
        assertEquals(player, saved.playerId());
        assertEquals(issued.deviceId(), saved.deviceId());
        return new JavaUpload(saved.endpoint(), saved.trust(), saved.token(), source, state);
    }
}

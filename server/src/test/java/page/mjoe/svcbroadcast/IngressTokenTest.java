package page.mjoe.svcbroadcast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class IngressTokenTest {
    @TempDir
    Path temporary;

    @Test
    void createsReusesAndAtomicallyRotatesRestrictedTokens() throws IOException {
        Path directory = temporary.resolve("config/svc-broadcast");
        IngressToken first = IngressToken.loadOrCreate(directory);
        Path file = directory.resolve("token.txt");
        String original = Files.readString(file).strip();
        assertTrue(original.matches("[0-9a-f]{64}"));
        assertTrue(first.authorizes("Bearer " + original));
        assertTrue(IngressToken.loadOrCreate(directory).authorizes("Bearer " + original));
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        }
        IngressToken rotated = IngressToken.rotate(directory);
        String replacement = Files.readString(file).strip();
        assertNotEquals(original, replacement);
        assertFalse(rotated.authorizes("Bearer " + original));
        assertTrue(rotated.authorizes("Bearer " + replacement));
        assertTrue(IngressToken.loadOrCreate(directory).authorizes("Bearer " + replacement));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count(), "Rotation must not leave temporary token copies");
        }
    }

    @Test
    void rejectsMalformedHeadersAndLeavesInvalidExistingFilesUntouched() throws IOException {
        Files.writeString(temporary.resolve("token.txt"), "a".repeat(64) + "\n");
        IngressToken token = IngressToken.loadOrCreate(temporary);
        for (String header : new String[]{"Bearer " + "b".repeat(64), "bearer " + "a".repeat(64),
                "Bearer " + "A".repeat(64), "Bearer " + "a".repeat(63),
                "Bearer " + "a".repeat(64) + " ", "Bearer " + "a".repeat(63) + "é"}) {
            assertFalse(token.authorizes(header));
        }
        assertFalse(token.authorizes(null));
        for (String invalid : new String[]{"", "a".repeat(63), "A".repeat(64),
                "a".repeat(64) + "\n\n", "a".repeat(10_000)}) {
            Files.writeString(temporary.resolve("token.txt"), invalid);
            IOException failure = assertThrows(IOException.class, () -> IngressToken.loadOrCreate(temporary));
            assertEquals("Invalid token file", failure.getMessage());
            assertEquals(invalid, Files.readString(temporary.resolve("token.txt")));
        }
    }

    @Test
    void refusesSymbolicLinkTokensWithoutReadingOrReplacingTheirTargets() throws IOException {
        Path directory = Files.createDirectory(temporary.resolve("config"));
        Path target = temporary.resolve("private.txt");
        Files.writeString(target, "a".repeat(64));
        Files.createSymbolicLink(directory.resolve("token.txt"), target);
        assertThrows(IOException.class, () -> IngressToken.loadOrCreate(directory));
        assertThrows(IOException.class, () -> IngressToken.rotate(directory));
        assertTrue(Files.isSymbolicLink(directory.resolve("token.txt")));
        assertEquals("a".repeat(64), Files.readString(target));
    }
}

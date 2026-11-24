package page.mjoe.svcbroadcast.sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static page.mjoe.svcbroadcast.sender.EmbeddedSender.Platform.MAC_ARM64;
import static page.mjoe.svcbroadcast.sender.EmbeddedSender.Platform.WINDOWS_X64;

class EmbeddedSenderTest {
    @TempDir Path temporary;

    @Test
    void installsReusesAndRepairsCacheWithoutChangingProfiles() throws Exception {
        Path cache = temporary.resolve("SVC Local Data/native");
        Path profile = cache.resolveSibling("profiles/old.json");
        Files.createDirectories(profile.getParent());
        Files.writeString(profile, "existing profile");
        var installs = new AtomicInteger();

        Path first = install(cache, "old executable", installs::incrementAndGet);
        assertEquals("old executable", Files.readString(first));
        assertEquals(first, install(cache, "old executable", installs::incrementAndGet));
        assertEquals(1, installs.get());

        Files.writeString(first, "corrupted executable");
        assertEquals(first, install(cache, "old executable", installs::incrementAndGet));
        assertEquals(2, installs.get());
        assertEquals("old executable", Files.readString(first));
        assertEquals("existing profile", Files.readString(profile));

        Path second = install(cache, "new executable", () -> {});
        assertNotEquals(first, second);
        assertEquals("old executable", Files.readString(first));
        assertEquals("new executable", Files.readString(second));
    }

    @Test
    void rejectsUnverifiedHelpersWithoutPublishingThem() throws Exception {
        Path cache = temporary.resolve("native");
        assertThrows(IOException.class, () -> EmbeddedSender.install(WINDOWS_X64, cache, manifest("expected"),
                ignored -> stream("different"), () -> {}));
        assertThrows(IOException.class, () -> EmbeddedSender.install(WINDOWS_X64, cache, manifest("expected"),
                ignored -> null, () -> {}));
        try (var files = Files.list(cache)) {
            assertEquals(0, files.filter(Files::isDirectory).count());
        }
    }

    @Test
    void extractsOnlyTheWindowsHelperFromTheRelease() throws Exception {
        assumeTrue(getClass().getResource(WINDOWS_X64.resource + "SHA256") != null);
        Path helper = EmbeddedSender.executable(WINDOWS_X64, temporary.resolve("cache with spaces"), () -> {});
        byte[] binary = Files.readAllBytes(helper);
        assertEquals('M', binary[0]);
        assertEquals('Z', binary[1]);
        try (var files = Files.list(helper.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void runsThePackagedMacHelperDirectlyWithNoRuntime() throws Exception {
        assumeTrue(EmbeddedSender.detect(System.getProperty("os.name"), System.getProperty("os.arch")) == MAC_ARM64);
        assumeTrue(getClass().getResource(MAC_ARM64.resource + "SHA256") != null);
        Path helper = EmbeddedSender.executable(MAC_ARM64, temporary.resolve("cache with spaces"), () -> {});
        assertTrue(Files.isExecutable(helper));
        Path app = helper.getParent().getParent().getParent();
        assertTrue(Files.isRegularFile(app.resolve("Contents/Info.plist")));
        assertTrue(Files.isRegularFile(app.resolve("Contents/_CodeSignature/CodeResources")));
        var launch = new ProcessBuilder(helper.toString(), "list-identities").directory(temporary.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        launch.environment().clear();
        var child = launch.start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void selectsOnlyTheMatchingOperatingSystemAndAbi() {
        assertSame(WINDOWS_X64, EmbeddedSender.detect("Windows 11", "amd64"));
        assertSame(WINDOWS_X64, EmbeddedSender.detect("Windows 11", "x86_64"));
        assertSame(MAC_ARM64, EmbeddedSender.detect("Mac OS X", "aarch64"));
        assertSame(MAC_ARM64, EmbeddedSender.detect("macOS", "arm64"));
        assertNull(EmbeddedSender.detect("Windows 11", "aarch64"));
        assertNull(EmbeddedSender.detect("Mac OS X", "x86_64"));
        assertNull(EmbeddedSender.detect("Linux", "aarch64"));
    }

    private static Path install(Path cache, String contents, Runnable onInstall) throws Exception {
        return EmbeddedSender.install(WINDOWS_X64, cache, manifest(contents), ignored -> stream(contents), onInstall);
    }

    private static ByteArrayInputStream manifest(String contents) throws Exception {
        return stream(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(contents.getBytes(StandardCharsets.UTF_8))) + "\n");
    }

    private static ByteArrayInputStream stream(String input) {
        return new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
    }
}

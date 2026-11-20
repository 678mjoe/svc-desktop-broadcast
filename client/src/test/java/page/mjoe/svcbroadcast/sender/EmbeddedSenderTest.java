package page.mjoe.svcbroadcast.sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static page.mjoe.svcbroadcast.sender.EmbeddedSender.Platform.MAC_ARM64;
import static page.mjoe.svcbroadcast.sender.EmbeddedSender.Platform.WINDOWS_X64;

class EmbeddedSenderTest {
    @TempDir Path temporary;
    private static final String PYTHON = "runtime/python.exe";
    private static final String SCRIPT = "app/windows_test.py";
    private static final String HELPER = "app/svc-wasapi-check.exe";

    @Test
    void installsReusesAndRepairsCacheWithoutChangingProfiles() throws Exception {
        Path cache = temporary.resolve("SVC Local Data/runtime");
        Path profile = cache.resolveSibling("profiles/old.json");
        Files.createDirectories(profile.getParent());
        Files.writeString(profile, "existing profile");
        byte[] zip = archive(PYTHON, SCRIPT, HELPER);
        String sha = sha256(zip);
        var installs = new AtomicInteger();

        Path first = EmbeddedSender.install(WINDOWS_X64, cache, sha, new ByteArrayInputStream(zip), installs::incrementAndGet);
        assertEquals(PYTHON, Files.readString(first.resolve(PYTHON)));
        assertEquals(first, EmbeddedSender.install(WINDOWS_X64, cache, sha, new ByteArrayInputStream(new byte[0]),
                installs::incrementAndGet));
        assertEquals(1, installs.get());

        Files.delete(first.resolve(HELPER));
        assertEquals(first, EmbeddedSender.install(WINDOWS_X64, cache, sha, new ByteArrayInputStream(zip), installs::incrementAndGet));
        assertEquals(2, installs.get());
        assertEquals(HELPER, Files.readString(first.resolve(HELPER)));
        assertEquals("existing profile", Files.readString(profile));

        byte[] newer = archive(PYTHON, SCRIPT, HELPER, "app/new-script.py");
        Path second = EmbeddedSender.install(WINDOWS_X64, cache, sha256(newer), new ByteArrayInputStream(newer), () -> {});
        assertNotEquals(first, second);
        assertTrue(Files.isRegularFile(first.resolve(PYTHON)));
        assertTrue(Files.isRegularFile(second.resolve("app/new-script.py")));
    }

    @Test
    void rejectsInvalidArchivesWithoutPublishingRuntime() throws Exception {
        Path cache = temporary.resolve("runtime");
        byte[] complete = archive(PYTHON, SCRIPT, HELPER);
        reject(cache, complete, "1".repeat(64));
        byte[] partial = archive(PYTHON, SCRIPT);
        reject(cache, partial, sha256(partial));
        byte[] invalidPath = archive(PYTHON, SCRIPT, HELPER, "app/../invalid.txt");
        reject(cache, invalidPath, sha256(invalidPath));
        try (var files = Files.list(cache)) {
            assertEquals(0, files.filter(Files::isDirectory).count());
        }
    }

    @Test
    void extractsTheActualBuildResourceWhenPresent() throws Exception {
        assumeTrue(getClass().getResource(WINDOWS_X64.resource + "sender.zip") != null);
        List<String> command = EmbeddedSender.command(WINDOWS_X64, temporary.resolve("cache with spaces"), () -> {});
        assertEquals("--cli", command.getLast());
        Path install = Path.of(command.getFirst()).getParent().getParent();
        assertTrue(Files.isRegularFile(install.resolve("runtime/python312.dll")));
        assertTrue(Files.isRegularFile(install.resolve("FILES-SHA256.json")));
    }

    @Test
    void launchesTheActualMacResourceWithoutLocalPython() throws Exception {
        assumeTrue(EmbeddedSender.detect(System.getProperty("os.name"), System.getProperty("os.arch")) == MAC_ARM64);
        assumeTrue(getClass().getResource(MAC_ARM64.resource + "sender.zip") != null);
        var command = new ArrayList<>(EmbeddedSender.command(MAC_ARM64, temporary.resolve("cache with spaces"), () -> {}));
        assertTrue(Files.isExecutable(Path.of(command.getFirst())));
        Path app = Path.of(command.getLast()).getParent();
        assertTrue(Files.isExecutable(app.getParent().resolve(MAC_ARM64.helper)));
        command.addAll(List.of("--profiles-dir", temporary.resolve("profiles").toString(), "mc-bridge", "profiles",
                "--game-pid", Long.toString(ProcessHandle.current().pid())));
        var launch = new ProcessBuilder(command).directory(temporary.toFile()).redirectErrorStream(true);
        launch.environment().clear();
        var child = launch.start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("\"event\": \"profiles\""), output);
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

    private static void reject(Path cache, byte[] zip, String sha) {
        assertThrows(IOException.class,
                () -> EmbeddedSender.install(WINDOWS_X64, cache, sha, new ByteArrayInputStream(zip), () -> {}));
    }

    private static byte[] archive(String... paths) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String path : paths) {
                zip.putNextEntry(new ZipEntry("sender-release/" + path));
                zip.write(path.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

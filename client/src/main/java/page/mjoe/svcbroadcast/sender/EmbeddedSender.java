package page.mjoe.svcbroadcast.sender;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Installs only this platform's capture helper, separately from profiles and legacy runtimes. */
final class EmbeddedSender {
    private static final int MAX_HELPER_BYTES = 8 * 1024 * 1024;

    private EmbeddedSender() {}

    enum Platform {
        WINDOWS_X64("windows-x64", "svc-wasapi-check.exe"),
        MAC_ARM64("macos-arm64", "SVC Audio Check.app/Contents/MacOS/svc-audio-check",
                "SVC Audio Check.app/Contents/Info.plist", "SVC Audio Check.app/Contents/_CodeSignature/CodeResources");

        final String resource;
        final String cachePrefix;
        final List<String> files;

        Platform(String name, String... files) {
            this.resource = "/svc-broadcast/native/" + name + "/";
            this.cachePrefix = name;
            this.files = List.of(files);
        }

        Path cache() {
            Path home = Path.of(System.getProperty("user.home"));
            if (this == MAC_ARM64) return home.resolve("Library/Caches/svc-broadcast/native");
            String localAppData = System.getenv("LOCALAPPDATA");
            Path local = localAppData == null || localAppData.isBlank()
                    ? home.resolve("AppData/Local") : Path.of(localAppData);
            return local.resolve("svc-broadcast/native");
        }
    }

    static Platform detect(String os, String arch) {
        String system = os.toLowerCase(Locale.ROOT);
        if (system.startsWith("windows") && (arch.equalsIgnoreCase("amd64") || arch.equalsIgnoreCase("x86_64"))) {
            return Platform.WINDOWS_X64;
        }
        if (system.startsWith("mac") && (arch.equalsIgnoreCase("aarch64") || arch.equalsIgnoreCase("arm64"))) {
            return Platform.MAC_ARM64;
        }
        return null;
    }

    static Path executable(Platform platform, Path cache, Runnable onInstall) throws IOException {
        try (var manifest = resource(platform, "SHA256")) {
            if (manifest == null) return null;
            return install(platform, cache, manifest, name -> resource(platform, name), onInstall);
        }
    }

    private static InputStream resource(Platform platform, String name) {
        return EmbeddedSender.class.getResourceAsStream(platform.resource + name);
    }

    @FunctionalInterface
    interface Resource {
        InputStream open(String name) throws IOException;
    }

    static synchronized Path install(Platform platform, Path cache, InputStream manifest, Resource resource,
                                     Runnable onInstall) throws IOException {
        if (!cache.isAbsolute()) throw new IOException("Invalid native cache path.");
        int manifestLength = platform.files.size() * 65;
        byte[] bytes = manifest.readNBytes(manifestLength + 1);
        String text = new String(bytes, StandardCharsets.US_ASCII);
        if (bytes.length != manifestLength || !text.matches("(?:[0-9a-f]{64}\\n)+")) {
            throw new IOException("Invalid native helper manifest.");
        }
        String[] hashes = text.split("\n");
        Files.createDirectories(cache);
        if (!Files.isDirectory(cache, NOFOLLOW_LINKS)) throw new IOException("Invalid native cache directory.");
        Path destination = cache.resolve(platform.cachePrefix + "-" + digest(bytes).substring(0, 24));
        Path executable = destination.resolve(platform.files.getFirst());
        try (var channel = FileChannel.open(cache.resolve(".install.lock"), CREATE, WRITE, NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            if (matches(platform, destination, hashes)) return executable;
            onInstall.run();
            Path staging = Files.createTempDirectory(cache, ".incoming-");
            try {
                for (int index = 0; index < hashes.length; index++) {
                    String name = platform.files.get(index);
                    try (InputStream input = resource.open(name)) {
                        if (input == null) throw new IOException("Missing native helper resource.");
                        byte[] contents = input.readNBytes(MAX_HELPER_BYTES + 1);
                        if (contents.length > MAX_HELPER_BYTES || !digest(contents).equals(hashes[index])) {
                            throw new IOException("Native helper checksum mismatch.");
                        }
                        Path file = staging.resolve(name);
                        Files.createDirectories(file.getParent());
                        Files.write(file, contents, java.nio.file.StandardOpenOption.CREATE_NEW);
                    }
                }
                if (platform == Platform.MAC_ARM64) {
                    Files.setPosixFilePermissions(staging.resolve(platform.files.getFirst()),
                            PosixFilePermissions.fromString("rwx------"));
                }
                if (Files.exists(destination, NOFOLLOW_LINKS)) {
                    // Leave a damaged cache aside without changing profiles or the old sender.
                    Path quarantine = Files.createTempDirectory(cache, ".damaged-").resolve("previous");
                    move(destination, quarantine);
                }
                move(staging, destination);
            } catch (FileAlreadyExistsException exception) {
                if (!matches(platform, destination, hashes)) throw exception;
            } finally {
                if (Files.exists(staging, NOFOLLOW_LINKS)) {
                    try (var paths = Files.walk(staging)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                    }
                }
            }
        }
        return executable;
    }

    private static boolean matches(Platform platform, Path directory, String[] hashes) throws IOException {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return false;
        for (int index = 0; index < hashes.length; index++) {
            Path file = directory.resolve(platform.files.get(index));
            for (Path parent = file.getParent(); !parent.equals(directory); parent = parent.getParent()) {
                if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return false;
            }
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > MAX_HELPER_BYTES) return false;
            try (var input = Files.newInputStream(file, NOFOLLOW_LINKS)) {
                byte[] actual = input.readNBytes(MAX_HELPER_BYTES + 1);
                if (actual.length > MAX_HELPER_BYTES || !digest(actual).equals(hashes[index])) return false;
            }
        }
        return platform != Platform.MAC_ARM64 || Files.isExecutable(directory.resolve(platform.files.getFirst()));
    }

    private static String digest(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }
}

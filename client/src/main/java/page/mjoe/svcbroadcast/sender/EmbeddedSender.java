package page.mjoe.svcbroadcast.sender;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/** Installs the matching portable sender in a cache separate from user profiles. */
final class EmbeddedSender {
    private static final String STAMP = ".bundle-sha256";
    private static final int MAX_ENTRIES = 10_000;
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final Pattern SAFE_SEGMENT = Pattern.compile(
            "(?!(?i:CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:[ .]|$))[A-Za-z0-9_.+ -]+");

    private EmbeddedSender() {}

    enum Platform {
        WINDOWS_X64("windows-x64", "win", "runtime/python.exe", "app/windows_test.py", "app/svc-wasapi-check.exe"),
        MAC_ARM64("macos-arm64", "mac", "runtime/bin/python3.12", "app/launch.py",
                "app/SVC Audio Check.app/Contents/MacOS/svc-audio-check");

        final String resource;
        final String cachePrefix;
        final String python;
        final String script;
        final String helper;

        Platform(String platform, String cachePrefix, String python, String script, String helper) {
            this.resource = "/svc-broadcast/" + platform + "/";
            this.cachePrefix = cachePrefix;
            this.python = python;
            this.script = script;
            this.helper = helper;
        }

        Path cache() {
            Path home = Path.of(System.getProperty("user.home"));
            if (this == MAC_ARM64) return home.resolve("Library/Caches/svc-broadcast/runtime");
            String localAppData = System.getenv("LOCALAPPDATA");
            Path local = localAppData == null || localAppData.isBlank()
                    ? home.resolve("AppData/Local") : Path.of(localAppData);
            return local.resolve("svc-broadcast/runtime");
        }
    }

    static List<String> command(Platform platform, Path cache, Runnable onInstall) throws IOException {
        if (!cache.isAbsolute()) throw new IOException("Invalid runtime cache path.");
        try (var checksum = EmbeddedSender.class.getResourceAsStream(platform.resource + "sender.sha256");
             var zip = EmbeddedSender.class.getResourceAsStream(platform.resource + "sender.zip")) {
            if (checksum == null || zip == null) return List.of();
            String sha256 = new String(checksum.readNBytes(67), StandardCharsets.US_ASCII).strip();
            Path install = install(platform, cache, sha256, zip, onInstall);
            var command = new ArrayList<>(List.of(install.resolve(platform.python).toString(), "-I", "-B", "-X", "utf8",
                    install.resolve(platform.script).toString()));
            if (platform == Platform.WINDOWS_X64) command.add("--cli");
            return command;
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

    static synchronized Path install(Platform platform, Path cache, String sha256, InputStream archive,
                                     Runnable onInstall) throws IOException {
        if (!sha256.matches("[0-9a-f]{64}")) throw new IOException("Invalid sender bundle checksum.");
        Files.createDirectories(cache);
        Path destination = cache.resolve(platform.cachePrefix + "-" + sha256.substring(0, 24));
        try (var channel = FileChannel.open(cache.resolve(".install.lock"), CREATE, WRITE);
             var lock = channel.lock()) {
            if (ready(platform, destination, sha256)) return destination;
            onInstall.run();
            Path staging = Files.createTempDirectory(cache, ".incoming-");
            try {
                unpack(archive, staging, sha256);
                if (!hasLauncher(platform, staging)) throw new IOException("Incomplete sender bundle.");
                if (platform == Platform.MAC_ARM64) {
                    var mode = PosixFilePermissions.fromString("rwx------");
                    Files.setPosixFilePermissions(staging.resolve(platform.python), mode);
                    Files.setPosixFilePermissions(staging.resolve(platform.helper), mode);
                }
                Files.writeString(staging.resolve(STAMP), sha256, CREATE_NEW, WRITE);
                if (Files.exists(destination, NOFOLLOW_LINKS)) {
                    // Preserve the damaged tree without touching any user profiles.
                    Path quarantine = Files.createTempDirectory(cache, ".damaged-").resolve("previous");
                    move(destination, quarantine);
                }
                move(staging, destination);
            } finally {
                if (Files.exists(staging, NOFOLLOW_LINKS)) {
                    try (var paths = Files.walk(staging)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                    }
                }
            }
        }
        return destination;
    }

    private static boolean ready(Platform platform, Path directory, String sha256) throws IOException {
        Path stamp = directory.resolve(STAMP);
        return hasLauncher(platform, directory) && Files.isRegularFile(stamp, NOFOLLOW_LINKS)
                && (platform != Platform.MAC_ARM64 || (Files.isExecutable(directory.resolve(platform.python))
                        && Files.isExecutable(directory.resolve(platform.helper))))
                && Files.size(stamp) == sha256.length() && Files.readString(stamp).equals(sha256);
    }

    private static boolean hasLauncher(Platform platform, Path directory) {
        return Files.isDirectory(directory, NOFOLLOW_LINKS)
                && Files.isDirectory(directory.resolve("runtime"), NOFOLLOW_LINKS)
                && Files.isDirectory(directory.resolve("app"), NOFOLLOW_LINKS)
                && Files.isRegularFile(directory.resolve(platform.python), NOFOLLOW_LINKS)
                && Files.isRegularFile(directory.resolve(platform.script), NOFOLLOW_LINKS)
                && Files.isRegularFile(directory.resolve(platform.helper), NOFOLLOW_LINKS);
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private static void unpack(InputStream archive, Path staging, String sha256) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
        try (var checked = new DigestInputStream(archive, digest); var zip = new ZipInputStream(checked)) {
            String root = null;
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int index = 0; ; index++) {
                var entry = zip.getNextEntry();
                if (entry == null) break;
                if (index >= MAX_ENTRIES) throw new IOException("Too many sender bundle entries.");
                String name = entry.getName();
                if (entry.isDirectory()) name = name.substring(0, name.length() - 1);
                String[] parts = name.split("/", -1);
                Path target = staging;
                for (int part = 0; part < parts.length; part++) {
                    String segment = parts[part];
                    if (!SAFE_SEGMENT.matcher(segment).matches() || segment.endsWith(".") || segment.endsWith(" ")) {
                        throw new IOException("Invalid sender bundle path.");
                    }
                    if (part > 0) target = target.resolve(segment);
                }
                if (root == null) root = parts[0];
                if (!root.equals(parts[0]) || (!entry.isDirectory() && target.equals(staging))) {
                    throw new IOException("Unexpected sender bundle layout.");
                }
                if (entry.isDirectory()) {
                    if (zip.read() != -1) throw new IOException("Unexpected data in bundle directory.");
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var output = Files.newOutputStream(target, CREATE_NEW, WRITE)) {
                    for (int count; (count = zip.read(buffer)) != -1; ) {
                        total += count;
                        if (total > MAX_BYTES) throw new IOException("Sender bundle exceeds installation size limit.");
                        output.write(buffer, 0, count);
                    }
                }
            }
            // ZipInputStream stops at the central directory. Hash the rest of the original resource as well.
            checked.transferTo(OutputStream.nullOutputStream());
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(sha256)) {
            throw new IOException("Sender bundle checksum mismatch.");
        }
    }
}

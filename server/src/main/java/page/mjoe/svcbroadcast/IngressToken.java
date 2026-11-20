package page.mjoe.svcbroadcast;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;

/** File operations belong exclusively to the ingress control executor. */
final class IngressToken {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] value;

    private IngressToken(byte[] value) {
        this.value = value;
    }

    static IngressToken loadOrCreate(Path directory) throws IOException {
        prepareDirectory(directory);
        Path path = directory.resolve("token.txt");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            IngressToken token = generate();
            try (SeekableByteChannel channel = Files.newByteChannel(path,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS), attributes(directory, "rw-------"))) {
                write(channel, token);
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                return readExisting(path);
            }
            return token;
        }
        return readExisting(path);
    }

    static IngressToken rotate(Path directory) throws IOException {
        prepareDirectory(directory);
        Path target = directory.resolve("token.txt");
        if (Files.isSymbolicLink(target)) {
            throw new InvalidTokenException();
        }
        IngressToken token = generate();
        Path temporary = Files.createTempFile(directory, ".token-", ".tmp",
                attributes(directory, "rw-------"));
        try {
            try (SeekableByteChannel channel = Files.newByteChannel(temporary,
                    Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                write(channel, token);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return token;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    boolean authorizes(String authorization) {
        if (authorization == null || authorization.length() != 71
                || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] candidate = new byte[64];
        for (int index = 0; index < candidate.length; index++) {
            char character = authorization.charAt(index + 7);
            if (!hex(character)) {
                return false;
            }
            candidate[index] = (byte) character;
        }
        return MessageDigest.isEqual(value, candidate);
    }

    private static IngressToken readExisting(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidTokenException();
        }
        restrict(path, "rw-------");
        ByteBuffer content = ByteBuffer.allocate(67);
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            while (content.hasRemaining() && channel.read(content) != -1) {
                // The bounded read catches oversized files without loading their contents.
            }
        }
        int size = content.position();
        byte[] bytes = content.array();
        if (size != 64 && !(size == 65 && bytes[64] == '\n')
                && !(size == 66 && bytes[64] == '\r' && bytes[65] == '\n')) {
            throw new InvalidTokenException();
        }
        byte[] token = new byte[64];
        for (int index = 0; index < token.length; index++) {
            if (!hex(bytes[index])) {
                throw new InvalidTokenException();
            }
            token[index] = bytes[index];
        }
        return new IngressToken(token);
    }

    private static void prepareDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Invalid token directory");
        }
        // The parent may not exist yet; the filesystem determines POSIX support.
        Path ancestor = directory.toAbsolutePath();
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        Files.createDirectories(directory, attributes(ancestor, "rwx------"));
        restrict(directory, "rwx------");
    }

    private static FileAttribute<?>[] attributes(Path path, String permissions) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS) == null) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString(permissions))};
    }

    private static void restrict(Path path, String permissions) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path,
                PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
            view.setPermissions(PosixFilePermissions.fromString(permissions));
        }
    }

    private static IngressToken generate() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return new IngressToken(HexFormat.of().formatHex(random).getBytes(StandardCharsets.US_ASCII));
    }

    private static void write(SeekableByteChannel channel, IngressToken token) throws IOException {
        ByteBuffer content = ByteBuffer.allocate(65);
        content.put(token.value).put((byte) '\n').flip();
        while (content.hasRemaining()) {
            channel.write(content);
        }
    }

    static final class InvalidTokenException extends IOException {
        InvalidTokenException() {
            super("Invalid token file");
        }
    }

    private static boolean hex(int character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f';
    }
}

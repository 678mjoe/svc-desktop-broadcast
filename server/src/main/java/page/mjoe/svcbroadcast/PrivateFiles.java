package page.mjoe.svcbroadcast;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;

/** Private bounded files, atomic replacement, and no symlink traversal at owned paths. */
final class PrivateFiles {
    static void directory(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Invalid private directory");
        Files.createDirectories(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid private directory");
        permissions(path, "rwx------");
    }
    static byte[] read(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid private file");
        permissions(path, "rw-------");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = stream.readNBytes(limit + 1);
            if (data.length > limit) throw new IOException("Private file too large");
            return data;
        }
    }
    static void write(Path path, byte[] data) throws IOException {
        directory(path.getParent());
        if (Files.isSymbolicLink(path)) throw new IOException("Invalid private file");
        Path temp = Files.createTempFile(path.getParent(), ".write-", ".tmp");
        try {
            permissions(temp, "rw-------");
            try (var output = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = java.nio.ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static void permissions(Path path, String mode) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
        else {
            // Non-POSIX servers must supply a private ACL. Never silently create world-readable keys.
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw new IOException("Private file permissions unavailable");
            acl.setAcl(java.util.List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                    .setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class)).build()));
        }
    }
}

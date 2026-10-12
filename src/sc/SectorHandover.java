package sc;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Atomic handover container for campaign .msav files: Gzip + CRC32 + ATOMIC_MOVE.
 *
 * Protocol (never writes to the target directly):
 *   1. Stream the payload into a uniquely created sibling `.tmp` file.
 *   2. Frame the uncompressed payload with its length and CRC32 so either side can prove
 *      integrity without trusting the transport.
 *   3. force(true) the temp file, re-read it and validate length + CRC32, then
 *      Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING).
 *   4. On any failure the temp file is deleted and the existing target is left untouched.
 *
 * Wire format:
 *   [int MAGIC][int VERSION][long payloadLength][int payloadCrc32][gzip stream of payload]
 */
public final class SectorHandover {
    private static final int MAGIC = 0x5343475A; // "SCGZ"
    private static final int VERSION = 1;
    private static final int BUFFER = 64 * 1024;

    /** Compress `source` into `target` atomically, embedding and validating the payload CRC32. */
    public static void saveCompressed(Path source, Path target) throws IOException {
        require(source, "source");
        require(target, "target");
        CRC32 crc = new CRC32();
        long length = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source))) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                crc.update(buffer, 0, read);
                length += read;
            }
        }
        Path directory = directoryOf(target);
        Path temp = Files.createTempFile(directory, prefix(target), ".tmp");
        boolean committed = false;
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(length);
                out.writeInt((int) crc.getValue());
                try (GZIPOutputStream gzip = new GZIPOutputStream(out, BUFFER)) {
                    Files.copy(source, gzip);
                }
            }
            fsync(temp);
            validate(temp, null);   // prove the artifact before it can ever become the target
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fsyncDirectory(directory);
            committed = true;
        } finally {
            if (!committed) Files.deleteIfExists(temp);
        }
    }

    /** Decompress `source` onto `target` atomically; the live target is never partially written. */
    public static void loadCompressed(Path source, Path target) throws IOException {
        require(source, "source");
        require(target, "target");
        Path directory = directoryOf(target);
        Path temp = Files.createTempFile(directory, prefix(target), ".tmp");
        boolean committed = false;
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                validate(source, out);
            }
            fsync(temp);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fsyncDirectory(directory);
            committed = true;
        } finally {
            if (!committed) Files.deleteIfExists(temp);
        }
    }

    /**
     * Read the framed container from `packed`, decompress it and verify length + CRC32.
     * When `out` is non-null the decompressed payload is also written there (the receive path).
     */
    private static void validate(Path packed, OutputStream out) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(packed)))) {
            if (in.readInt() != MAGIC) throw new IOException("bad-magic");
            if (in.readInt() != VERSION) throw new IOException("bad-version");
            long expectedLength = in.readLong();
            if (expectedLength < 0) throw new IOException("bad-length");
            long expectedCrc = in.readInt() & 0xFFFFFFFFL;
            CRC32 crc = new CRC32();
            long written = 0;
            try (GZIPInputStream gzip = new GZIPInputStream(in, BUFFER)) {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = gzip.read(buffer)) >= 0) {
                    if (out != null) out.write(buffer, 0, read);
                    crc.update(buffer, 0, read);
                    written += read;
                }
            }
            if (written != expectedLength) throw new IOException("length-mismatch");
            if (crc.getValue() != expectedCrc) throw new IOException("crc-mismatch");
        }
    }

    private static void require(Path value, String reason) {
        if (value == null) throw new IllegalArgumentException(reason);
    }

    private static Path directoryOf(Path target) throws IOException {
        Path directory = target.toAbsolutePath().normalize().getParent();
        if (directory == null) throw new IOException("no-parent");
        return directory;
    }

    private static String prefix(Path target) {
        String name = target.getFileName().toString();
        return name.length() >= 3 ? name : "sc-";
    }

    private static void fsync(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void fsyncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
            // Some platforms forbid opening a directory; the atomic rename is still ordered by the OS.
        }
    }
}

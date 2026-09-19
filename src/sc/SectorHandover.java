package sc;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/** SectorHandover: Gzip + Atomic Swap (AtomicMove) for .msav files. */
public final class SectorHandover {
    public static void saveCompressed(Path source, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (GZIPOutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            Files.copy(source, out);
        }
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void loadCompressed(Path source, Path target) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

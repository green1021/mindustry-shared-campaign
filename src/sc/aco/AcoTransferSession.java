package sc.aco;

import java.io.*;
import java.nio.file.*;
import java.util.zip.CRC32;

public class AcoTransferSession {
    public final String transferId;
    public final String sectorKey;
    public final long expectedSize;
    public final long expectedCrc;
    public final Path tempFile;
    
    private final OutputStream out;
    private final CRC32 crc = new CRC32();
    private long receivedBytes = 0;

    public AcoTransferSession(String id, String sector, long size, long crc) throws IOException {
        this.transferId = id;
        this.sectorKey = sector;
        this.expectedSize = size;
        this.expectedCrc = crc;
        this.tempFile = Files.createTempFile("aco-" + id, ".tmp");
        this.out = new BufferedOutputStream(new FileOutputStream(tempFile.toFile()));
    }

    public synchronized void receiveChunk(byte[] data) throws IOException {
        out.write(data);
        crc.update(data);
        receivedBytes += data.length;
    }

    public synchronized boolean verifyAndCommit(Path target) throws IOException {
        out.close();
        if (receivedBytes == expectedSize && crc.getValue() == expectedCrc) {
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
        Files.deleteIfExists(tempFile);
        return false;
    }

    public void cleanup() throws IOException {
        out.close();
        Files.deleteIfExists(tempFile);
    }
}

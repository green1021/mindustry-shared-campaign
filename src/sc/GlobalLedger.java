package sc;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/**
 * P1 Global Bank transaction journal: append-only write-ahead log + atomic snapshot.
 *
 * Contract:
 *  - Every committed resource transaction is appended to `global.wal` as a CRC32-framed record
 *    and forced to stable storage BEFORE the caller mutates live state. A committed request can
 *    therefore never be applied twice: replay restores requestId -> reply plus the monotonic
 *    revision and the recorded balance, so a host restart cannot double-debit or reset revision.
 *  - `global.snapshot` is written to a unique sibling temp file, CRC-framed, fsynced, then moved
 *    with ATOMIC_MOVE. The WAL is truncated only after the snapshot is durable; a crash between
 *    the two is safe because the snapshot carries its own high-water sequence.
 *  - A torn WAL tail (partial record from a crash/power loss) is truncated to the last valid
 *    frame on recovery instead of failing the store.
 *  - An in-memory instance (directory == null) keeps identical semantics without durability so
 *    callers have a single code path.
 */
public final class GlobalLedger implements AutoCloseable {
    private static final int MAGIC = 0x53434C47;            // "SCLG"
    private static final int VERSION = 1;
    private static final int MAX_FIELD = 1 << 16;           // 64 KiB per field
    private static final int MAX_RECORD = 1 << 20;          // 1 MiB per framed record
    private static final long MAX_WAL = 64L << 20;          // refuse to replay absurd logs
    private static final int DEFAULT_SNAPSHOT_INTERVAL = 256;
    private static final int CAPACITY = 1 << 16;            // retained idempotency records

    /** One committed transaction: the durable answer for a request id. */
    public static final class Txn {
        public final long sequence, revision, balance;
        public final String requestId, requestFrame, replyFrame;

        Txn(long sequence, String requestId, String requestFrame, String replyFrame, long revision, long balance) {
            this.sequence = sequence;
            this.requestId = requestId;
            this.requestFrame = requestFrame;
            this.replyFrame = replyFrame;
            this.revision = revision;
            this.balance = balance;
        }
    }

    private final Path directory, snapshotPath, walPath;
    private final int snapshotInterval;
    private final LinkedHashMap<String, Txn> committed = new LinkedHashMap<>();
    private FileChannel wal;
    private long sequence, revision, balance, snapshotSequence;
    private int recordsSinceSnapshot;
    private long replayedRecords, truncatedBytes;
    private boolean closed;

    private GlobalLedger(Path directory, int snapshotInterval) {
        this.directory = directory;
        this.snapshotInterval = snapshotInterval;
        this.snapshotPath = directory == null ? null : directory.resolve("global.snapshot");
        this.walPath = directory == null ? null : directory.resolve("global.wal");
    }

    /** Non-durable instance used when no ledger directory is configured. */
    public static GlobalLedger inMemory() {
        return new GlobalLedger(null, Integer.MAX_VALUE);
    }

    public static GlobalLedger open(Path directory) throws IOException {
        return open(directory, DEFAULT_SNAPSHOT_INTERVAL);
    }

    public static GlobalLedger open(Path directory, int snapshotInterval) throws IOException {
        if (directory == null) return inMemory();
        if (snapshotInterval < 1) throw new IllegalArgumentException("snapshot-interval");
        GlobalLedger ledger = new GlobalLedger(directory, snapshotInterval);
        ledger.recover();
        return ledger;
    }

    public boolean durable() {
        return directory != null;
    }

    public synchronized Txn find(String requestId) {
        return committed.get(requestId);
    }

    public synchronized int size() {
        return committed.size();
    }

    public int capacity() {
        return CAPACITY;
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized long balance() {
        return balance;
    }

    public synchronized long sequence() {
        return sequence;
    }

    /** Number of WAL records replayed beyond the snapshot during the last recovery. */
    public synchronized long replayedRecords() {
        return replayedRecords;
    }

    /** Bytes discarded from a torn WAL tail during the last recovery. */
    public synchronized long truncatedBytes() {
        return truncatedBytes;
    }

    public synchronized List<Txn> transactions() {
        return new ArrayList<>(committed.values());
    }

    /**
     * Durably record a committed transaction. Idempotent: re-committing the same request id
     * returns the original record and never rewrites the log. A conflicting frame is rejected.
     */
    public synchronized Txn commit(String requestId, String requestFrame, String replyFrame,
                                   long nextRevision, long nextBalance) throws IOException {
        require(!closed, "closed");
        require(SectorStore.id(requestId), "identifier");
        Txn prior = committed.get(requestId);
        if (prior != null) {
            require(prior.requestFrame.equals(requestFrame), "conflict");
            return prior;
        }
        if (nextRevision <= revision) throw new IllegalArgumentException("revision");
        require(committed.size() < CAPACITY, "capacity");
        long nextSequence = sequence + 1;
        Txn txn = new Txn(nextSequence, requestId, requestFrame, replyFrame, nextRevision, nextBalance);
        if (wal != null) append(txn);       // write-ahead + fsync BEFORE the caller mutates live state
        committed.put(requestId, txn);
        sequence = nextSequence;
        revision = nextRevision;
        balance = nextBalance;
        recordsSinceSnapshot++;
        if (wal != null && recordsSinceSnapshot >= snapshotInterval) snapshot();
        return txn;
    }

    /** Compact the WAL into an atomically swapped snapshot and truncate the log. */
    public synchronized void snapshot() throws IOException {
        if (directory == null) return;
        require(!closed, "closed");
        Path temp = Files.createTempFile(directory, "global-snapshot-", ".tmp");
        boolean committedSnapshot = false;
        try {
            byte[] record = frame(encodeSnapshot());
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                writeFully(channel, record);
                channel.force(true);
            }
            Files.move(temp, snapshotPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fsyncDirectory(directory);
            if (wal != null) {
                wal.truncate(0);
                wal.position(0);
                wal.force(true);
            }
            snapshotSequence = sequence;
            recordsSinceSnapshot = 0;
            committedSnapshot = true;
        } finally {
            if (!committedSnapshot) Files.deleteIfExists(temp);
        }
    }

    public synchronized void close() throws IOException {
        if (closed) return;
        try {
            if (directory != null) snapshot();
        } finally {
            closed = true;
            if (wal != null) wal.close();
        }
    }

    // ---- persistence internals -------------------------------------------------------------

    private void recover() throws IOException {
        Files.createDirectories(directory);
        rejectSymlinks(directory);
        if (Files.isRegularFile(snapshotPath, LinkOption.NOFOLLOW_LINKS)) {
            decodeSnapshot(unframe(Files.readAllBytes(snapshotPath)));
        }
        if (Files.isRegularFile(walPath, LinkOption.NOFOLLOW_LINKS) && Files.size(walPath) > MAX_WAL) {
            throw new IOException("wal-too-large");
        }
        wal = FileChannel.open(walPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        replayWal();
        wal.position(wal.size());
    }

    private void append(Txn txn) throws IOException {
        byte[] record = frame(encodeTxn(txn));
        wal.position(wal.size());
        writeFully(wal, record);
        wal.force(true);
    }

    private void replayWal() throws IOException {
        byte[] data = Files.readAllBytes(walPath);
        int offset = 0, lastValid = 0;
        while (offset < data.length) {
            if (data.length - offset < 4) break;                 // torn length prefix
            int length = readInt(data, offset);
            if (length < 0 || length > MAX_RECORD) break;
            long total = 8L + length;
            if (offset + total > data.length) break;             // torn record body
            int expectedCrc = readInt(data, offset + 4);
            byte[] payload = Arrays.copyOfRange(data, offset + 8, offset + 8 + length);
            if (crc(payload) != expectedCrc) break;              // corrupt frame
            Txn txn;
            try {
                txn = decodeTxn(payload);
            } catch (IOException ex) {
                break;
            }
            apply(txn);
            offset += (int) total;
            lastValid = offset;
        }
        if (lastValid != data.length) {
            truncatedBytes = data.length - lastValid;
            wal.truncate(lastValid);
            wal.force(true);
        }
    }

    private void apply(Txn txn) {
        if (txn.sequence <= snapshotSequence) return;            // already durable in the snapshot
        committed.put(txn.requestId, txn);
        if (txn.sequence > sequence) sequence = txn.sequence;
        revision = txn.revision;
        balance = txn.balance;
        replayedRecords++;
    }

    private byte[] encodeSnapshot() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeLong(sequence);
        out.writeLong(revision);
        out.writeLong(balance);
        out.writeInt(committed.size());
        for (Txn txn : committed.values()) {
            byte[] record = encodeTxn(txn);
            out.writeInt(record.length);
            out.write(record);
        }
        out.flush();
        return bytes.toByteArray();
    }

    private void decodeSnapshot(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readInt() != MAGIC) throw new IOException("snapshot-magic");
        if (in.readInt() != VERSION) throw new IOException("snapshot-version");
        long snapSequence = in.readLong();
        long snapRevision = in.readLong();
        long snapBalance = in.readLong();
        int count = in.readInt();
        if (count < 0 || count > CAPACITY) throw new IOException("snapshot-count");
        for (int i = 0; i < count; i++) {
            int length = in.readInt();
            if (length < 0 || length > MAX_RECORD) throw new IOException("snapshot-record");
            byte[] record = new byte[length];
            in.readFully(record);
            Txn txn = decodeTxn(record);
            committed.put(txn.requestId, txn);
        }
        if (in.available() != 0) throw new IOException("snapshot-trailing");
        sequence = snapSequence;
        revision = snapRevision;
        balance = snapBalance;
        snapshotSequence = snapSequence;
    }

    private static byte[] encodeTxn(Txn txn) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(txn.sequence);
        writeString(out, txn.requestId);
        writeString(out, txn.requestFrame);
        writeString(out, txn.replyFrame);
        out.writeLong(txn.revision);
        out.writeLong(txn.balance);
        out.flush();
        return bytes.toByteArray();
    }

    private static Txn decodeTxn(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        long sequence = in.readLong();
        String requestId = readString(in);
        String requestFrame = readString(in);
        String replyFrame = readString(in);
        long revision = in.readLong();
        long balance = in.readLong();
        if (in.available() != 0) throw new IOException("record-trailing");
        return new Txn(sequence, requestId, requestFrame, replyFrame, revision, balance);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FIELD) throw new IOException("field-too-large");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FIELD) throw new IOException("field-length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] frame(byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 8);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(payload.length);
        out.writeInt(crc(payload));
        out.write(payload);
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] unframe(byte[] data) throws IOException {
        if (data.length < 8) throw new IOException("truncated");
        int length = readInt(data, 0);
        int expectedCrc = readInt(data, 4);
        if (length < 0 || length > MAX_RECORD || data.length != 8 + length) throw new IOException("length");
        byte[] payload = Arrays.copyOfRange(data, 8, data.length);
        if (crc(payload) != expectedCrc) throw new IOException("crc");
        return payload;
    }

    private static int crc(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload, 0, payload.length);
        return (int) crc.getValue();
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
            | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static void fsyncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
            // Some platforms forbid opening a directory; the atomic rename is still ordered by the OS.
        }
    }

    private static void rejectSymlinks(Path path) throws IOException {
        for (Path current = path.toAbsolutePath(); current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("symlink");
        }
    }

    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalArgumentException(reason);
    }
}

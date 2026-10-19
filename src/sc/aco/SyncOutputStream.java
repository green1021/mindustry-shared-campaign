package sc.aco;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Serializes every write to one connection's output stream.
 *
 * <p>A connection's frames are produced from several threads: the connection's own
 * reader thread (PONG and request responses), another connection's reader thread
 * (a {@code GLOBAL_STATE_SYNC} broadcast triggered by its transaction), and the
 * handoff manager ({@code SECTOR_HANDOFF_REQUEST}). {@link OutputStream#write}
 * offers no atomicity across threads, so two concurrent frames could interleave
 * byte-for-byte and the peer's framed reader would see a corrupted length field.
 * Locking per session keeps the single-writer assumption true without serializing
 * different connections against each other.
 */
final class SyncOutputStream extends OutputStream {
    private final OutputStream delegate;

    private SyncOutputStream(OutputStream delegate) {
        this.delegate = delegate;
    }

    static OutputStream wrap(OutputStream out) {
        return out instanceof SyncOutputStream ? out : new SyncOutputStream(out);
    }

    @Override
    public synchronized void write(int b) throws IOException {
        delegate.write(b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
    }

    @Override
    public synchronized void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        delegate.close();
    }
}

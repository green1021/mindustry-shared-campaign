package sc.aco;

import arc.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Client-side ACO control connection: one socket, a daemon reader thread, and
 * msgId-correlated request/response plus push routing.
 *
 * <p>This replaces the old "write request, then block on the socket read" pattern
 * ({@code AcoController.roundTrip}), which could not coexist with unsolicited
 * broadcasts, with a handoff notification arriving while the client idles on the
 * planet screen, or with chunked snapshot transfers. The control connection is
 * deliberately a <em>separate</em> channel from gameplay networking (P2), as the
 * product spec requires.
 *
 * <p>Threading contract: {@link #request} and {@link #transferSnapshot} block the
 * calling thread and must NOT be called from a push handler (which runs on the
 * reader thread). Push handlers hand off to their own executor.
 */
public class AcoControlConnection implements TransferLink {

    /** Sentinel frame pushed into pending queues on close, to wake waiters. */
    private static final AcoFrame CLOSED = new AcoFrame((byte) 0, (byte) -1, 0, new byte[0]);
    private static final long TRANSFER_TIMEOUT_MS = 30_000;

    private final Object writeLock = new Object();
    private final Map<Integer, BlockingQueue<AcoFrame>> pending = new ConcurrentHashMap<>();
    private final Map<Byte, Consumer<AcoFrame>> pushes = new ConcurrentHashMap<>();

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean open;
    private int nextMsgId = 1;
    private Thread reader;
    private volatile Runnable onDisconnect;

    public synchronized void connect(String host, int port, int connectTimeoutMs) throws IOException {
        close();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        this.socket = s;
        this.in = s.getInputStream();
        this.out = s.getOutputStream();
        this.open = true;
        this.reader = new Thread(this::readLoop, "aco-control-connection");
        this.reader.setDaemon(true);
        this.reader.start();
        Log.info("ACO_CONTROL_CONNECTED host=@ port=@", host, port);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /** Register a handler for unsolicited (msgId 0) frames of one type. */
    public void addPushHandler(AcoMessageType type, Consumer<AcoFrame> handler) {
        pushes.put(type.id, handler);
    }

    public void setOnDisconnect(Runnable handler) {
        this.onDisconnect = handler;
    }

    @Override
    public synchronized boolean send(AcoMessageType type, String payload) {
        return write(new AcoFrame((byte) 1, type.id, 0, payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String request(AcoMessageType type, String payload, long timeoutMs, byte... expectedResponses) {
        if (out == null || !open) return null;
        BlockingQueue<AcoFrame> queue = new LinkedBlockingQueue<>();
        int id = register(queue);
        try {
            if (!write(new AcoFrame((byte) 1, type.id, id, payload.getBytes(StandardCharsets.UTF_8)))) return null;
            long deadline = System.currentTimeMillis() + Math.max(200, timeoutMs);
            while (true) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    Log.warn("ACO_CONTROL_REQUEST_TIMEOUT type=@", type);
                    return null;
                }
                AcoFrame f = poll(queue, remain);
                if (f == null || f.type() < 0) return null;
                for (byte e : expectedResponses) {
                    if (f.type() == e) return new String(f.payload(), StandardCharsets.UTF_8);
                }
                Log.warn("ACO_CONTROL_UNEXPECTED_RESPONSE request=@ got=@", type, f.type());
                // keep waiting until the deadline: the expected frame may still arrive
            }
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public boolean transferSnapshot(String sectorKey, long revision, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > SectorSnapshotCodec.MAX_SNAPSHOT_BYTES) return false;
        if (out == null || !open) return false;
        String sha = SectorSnapshotCodec.sha256(bytes);
        BlockingQueue<AcoFrame> queue = new LinkedBlockingQueue<>();
        int id = register(queue);
        try {
            if (!write(frame(AcoMessageType.SECTOR_SNAPSHOT_BEGIN, id,
                    SectorSnapshotCodec.beginPayload(sectorKey, revision, bytes.length, sha)))) {
                return false;
            }
            int seq = 0;
            for (int off = 0; off < bytes.length; off += SectorSnapshotCodec.CHUNK_RAW_BYTES) {
                byte[] raw = Arrays.copyOfRange(bytes, off,
                        Math.min(bytes.length, off + SectorSnapshotCodec.CHUNK_RAW_BYTES));
                if (!write(frame(AcoMessageType.SECTOR_SNAPSHOT_CHUNK, id,
                        SectorSnapshotCodec.chunkPayload(sectorKey, seq++, raw)))) {
                    return false;
                }
            }
            if (!write(frame(AcoMessageType.SECTOR_SNAPSHOT_END, id,
                    SectorSnapshotCodec.endPayload(sectorKey, sha)))) {
                return false;
            }
            long deadline = System.currentTimeMillis() + TRANSFER_TIMEOUT_MS;
            while (true) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    Log.warn("ACO_SNAPSHOT_ACK_TIMEOUT sector=@ revision=@", sectorKey, revision);
                    return false;
                }
                AcoFrame f = poll(queue, remain);
                if (f == null || f.type() < 0) return false;
                if (f.type() != AcoMessageType.SECTOR_SNAPSHOT_ACK.id) {
                    Log.warn("ACO_SNAPSHOT_UNEXPECTED_RESPONSE got=@", f.type());
                    continue;
                }
                String ack = new String(f.payload(), StandardCharsets.UTF_8);
                String[] parts = ack.split("\\|", -1);
                if (parts.length >= 3 && "OK".equals(parts[1])) return true;
                Log.warn("ACO_SNAPSHOT_REJECTED ack=@", ack);
                return false;
            }
        } finally {
            pending.remove(id);
        }
    }

    public synchronized void close() {
        if (!open && socket == null) return;
        open = false;
        Socket s = socket;
        socket = null;
        out = null;
        in = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        for (BlockingQueue<AcoFrame> q : pending.values()) q.offer(CLOSED);
        pending.clear();
    }

    // --------------------------------------------------------------- internals

    private synchronized int register(BlockingQueue<AcoFrame> queue) {
        int id = nextMsgId++;
        pending.put(id, queue);
        return id;
    }

    private static AcoFrame frame(AcoMessageType type, int msgId, String payload) {
        return new AcoFrame((byte) 1, type.id, msgId, payload.getBytes(StandardCharsets.UTF_8));
    }

    private boolean write(AcoFrame f) {
        OutputStream o = out;
        if (o == null || !open) return false;
        try {
            synchronized (writeLock) {
                o.write(f.encode());
                o.flush();
            }
            return true;
        } catch (IOException e) {
            Log.warn("ACO_CONTROL_WRITE_FAILED type=@ reason=@", f.type(), e.getMessage());
            return false;
        }
    }

    private static AcoFrame poll(BlockingQueue<AcoFrame> queue, long timeoutMs) {
        try {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void readLoop() {
        try {
            while (open) {
                AcoFrame f = readFrame(in);
                if (f == null) break;
                if (f.msgId() != 0) {
                    BlockingQueue<AcoFrame> q = pending.get(f.msgId());
                    if (q != null) {
                        q.offer(f);
                        continue;
                    }
                }
                Consumer<AcoFrame> handler = pushes.get(f.type());
                if (handler != null) {
                    try {
                        handler.accept(f);
                    } catch (Throwable t) {
                        Log.err("ACO_PUSH_HANDLER_FAILED type=@", f.type(), t);
                    }
                } else {
                    Log.debug("ACO_CONTROL_UNROUTED type=@ msgId=@", f.type(), f.msgId());
                }
            }
        } catch (IOException e) {
            if (open) Log.warn("ACO_CONTROL_READ_END reason=@", e.getMessage());
        } finally {
            boolean wasOpen;
            synchronized (this) {
                wasOpen = open;
                open = false;
            }
            for (BlockingQueue<AcoFrame> q : pending.values()) q.offer(CLOSED);
            Runnable r = onDisconnect;
            if (r != null) {
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
            }
            if (wasOpen) Log.info("ACO_CONTROL_DISCONNECTED");
        }
    }

    private static AcoFrame readFrame(InputStream in) throws IOException {
        if (in == null) return null;
        byte[] header = new byte[AcoFrame.HEADER_SIZE];
        if (!readFully(in, header)) return null;
        ByteBuffer hb = ByteBuffer.wrap(header);
        int len = hb.getInt(6);
        if (len < 0 || len > AcoFrame.MAX_PAYLOAD) return null;
        byte[] body = new byte[len];
        if (!readFully(in, body)) return null;
        return new AcoFrame(hb.get(0), hb.get(1), hb.getInt(2), body);
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r == -1) return false;
            total += r;
        }
        return true;
    }
}

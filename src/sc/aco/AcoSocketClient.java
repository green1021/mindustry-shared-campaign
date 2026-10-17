package sc.aco;

import arc.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Production transport for {@link AcoClient}: the "integration wrapper" its
 * {@code sendFrame} hook was left waiting for.
 *
 * <p>A sector host uses this to emit gameplay-originated requests to the campaign
 * authority and to receive the authority's {@code GLOBAL_STATE_SYNC} broadcasts.
 * The base {@code AcoClient} only knows how to frame a request and fold a sync into
 * its local view; this subclass owns the socket and the reader loop, so the frames
 * a gameplay event produces actually leave the process.
 *
 * <p>Frames arriving on the socket are dispatched on a daemon reader thread. Only
 * {@code GLOBAL_STATE_SYNC} is consumed here — responses to requests this client
 * sent are not awaited, because the gameplay boundary is emit-and-forget from the
 * host's point of view: the authority's broadcast is the acknowledgement.
 */
public class AcoSocketClient extends AcoClient {

    private Socket socket;
    private OutputStream out;
    private volatile boolean running;

    /** Open the connection and start the reader loop. Safe to call repeatedly. */
    public synchronized void connect(String host, int port) throws IOException {
        disconnect();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 3000);
        this.socket = s;
        this.out = s.getOutputStream();
        final InputStream in = s.getInputStream();
        this.running = true;
        Thread reader = new Thread(() -> readLoop(in), "aco-socket-client");
        reader.setDaemon(true);
        reader.start();
        Log.info("ACO_SOCKET_CLIENT_CONNECTED host=@ port=@", host, port);
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private void readLoop(InputStream in) {
        try {
            while (running) {
                AcoFrame f = readFrame(in);
                if (f == null) break;
                if (f.type() == AcoMessageType.GLOBAL_STATE_SYNC.id) {
                    handleGlobalStateSync(f);
                }
            }
        } catch (IOException e) {
            if (running) Log.warn("ACO_SOCKET_CLIENT_READ_END reason=@", e.getMessage());
        }
    }

    /**
     * Write a frame to the authority. Throws when not connected; the callers in
     * {@code AcoClient} log and drop, so gameplay never stalls on a dead link.
     */
    @Override
    protected void sendFrame(AcoFrame f) throws IOException {
        OutputStream o;
        synchronized (this) {
            o = out;
        }
        if (o == null) throw new IOException("aco-client-not-connected");
        synchronized (o) {
            o.write(f.encode());
            o.flush();
        }
    }

    public synchronized void disconnect() {
        running = false;
        Socket s = socket;
        socket = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static AcoFrame readFrame(InputStream in) throws IOException {
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

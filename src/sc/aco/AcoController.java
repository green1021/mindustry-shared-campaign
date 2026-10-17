package sc.aco;

import arc.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Owns the ACO control-plane lifecycle for the running mod.
 *
 * <p>Construction is inert: {@link #init()} only rebuilds in-memory state and
 * recovers the persisted campaign snapshot. No socket is bound until
 * {@link #hostCampaign(int, int)} is called, so simply loading the mod never
 * claims a port on a shared host.
 */
public final class AcoController implements sc.CampaignActions {
    private final Path storageDir;

    private CampaignPersistence persistence;
    private GlobalCampaignState globalState;
    private SectorRegistry registry;
    private GlobalTransactionManager txManager;
    private CampaignAuthority authority;
    private AcoAssignment assignment;
    private AcoP2Manager p2Manager;
    private AcoMigrationManager migrationManager;
    private AcoServer server;

    private boolean initialized;
    private boolean hosting;
    private int syncPort;
    private int sectorPort;

    private Socket authoritySocket;
    private String authorityHost;
    private int authorityPort;
    private volatile boolean connected;

    private SectorRuntime runtimeOverride;

    public AcoController(String dataDirName) {
        this.storageDir = Paths.get(dataDirName);
    }

    /**
     * Substitute the game-runtime adapter used by the authority (headless tests
     * inject a stub; production leaves the default {@link SectorRuntimeAdapter}).
     * Must be called before {@link #init()}.
     */
    public void setSectorRuntime(SectorRuntime runtime) {
        this.runtimeOverride = runtime;
    }

    /** Small holder so the P2 manager and migration manager share one instance. */
    private static final class AcoAssignment {
        final AcoP2Manager p2 = new AcoP2Manager();
    }

    public synchronized void init() {
        if (initialized) return;
        initialized = true;

        this.persistence = new CampaignPersistence(storageDir);
        this.registry = new SectorRegistry();
        this.globalState = new GlobalCampaignState();
        this.globalState.campaignId = "default";

        try {
            this.globalState = persistence.recover();
        } catch (Exception e) {
            Log.err("ACO_STATE_RECOVERY_FAILED", e);
        }

        this.txManager = new GlobalTransactionManager(globalState, new GlobalBank(globalState, persistence));
        this.authority = new CampaignAuthority(registry, persistence);
        if (runtimeOverride != null) {
            this.authority.setRuntime(runtimeOverride);
        }
        this.assignment = new AcoAssignment();
        this.p2Manager = assignment.p2;
        this.migrationManager = new AcoMigrationManager(p2Manager, null);

        Log.info("ACO_CONTROLLER_INIT data=@ revision=@", storageDir, globalState.globalRevision);
    }

    /**
     * Start the campaign authority sync endpoint and a P2 sector host.
     * Returns true when the sync server was started.
     */
    public synchronized boolean hostCampaign(int syncPort, int sectorPort) {
        init();
        if (hosting) return true;

        this.syncPort = syncPort;
        this.sectorPort = sectorPort;
        this.authority.setP2Port(sectorPort);
        this.server = new AcoServer(syncPort, migrationManager, authority, registry, globalState, txManager);
        server.start();
        this.hosting = true;
        Log.info("ACO_HOST_START sync=@ sector=@", syncPort, sectorPort);
        return true;
    }

    /**
     * Connect to a remote campaign authority and perform a bounded PING/PONG
     * handshake. Returns true only when a well-formed PONG was received.
     */
    public synchronized boolean connectToAuthority(String host, int port) {
        init();
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(3000);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            AcoFrame ping = new AcoFrame((byte) 1, AcoMessageType.PING.id, 1, new byte[0]);
            out.write(ping.encode());
            out.flush();

            byte[] header = new byte[AcoFrame.HEADER_SIZE];
            int total = 0;
            while (total < AcoFrame.HEADER_SIZE) {
                int r = in.read(header, total, AcoFrame.HEADER_SIZE - total);
                if (r == -1) break;
                total += r;
            }
            if (total < AcoFrame.HEADER_SIZE) {
                closeQuietly(socket);
                Log.warn("ACO_CONNECT_NO_RESPONSE host=@ port=@", host, port);
                return false;
            }

            AcoFrame response = AcoFrame.decode(ByteBuffer.wrap(header));
            if (response == null || response.type() != AcoMessageType.PONG.id) {
                closeQuietly(socket);
                Log.warn("ACO_CONNECT_BAD_RESPONSE host=@ port=@", host, port);
                return false;
            }

            this.authoritySocket = socket;
            this.authorityHost = host;
            this.authorityPort = port;
            this.connected = true;
            Log.info("ACO_CONNECT_OK host=@ port=@", host, port);
            return true;
        } catch (IOException e) {
            closeQuietly(socket);
            Log.warn("ACO_CONNECT_FAILED host=@ port=@ reason=@", host, port, e.getMessage());
            return false;
        }
    }

    // --- Verified vertical-slice operations (real socket, bounded) ---

    /**
     * Claim a sector on the connected authority. Returns the raw
     * {@code sectorKey|sessionId|OK|ownershipVersion|revision} response, or null
     * when not connected or the exchange failed.
     */
    public synchronized String requestClaim(String sectorKey, String sessionId, String deviceId, long revision) {
        String payload = sectorKey + "|" + sessionId + "|" + deviceId + "|" + revision;
        return roundTrip(AcoMessageType.SECTOR_CLAIM_REQUEST, payload);
    }

    /** Join (resolve endpoint of) a sector on the connected authority. */
    public synchronized String requestJoin(String sectorKey) {
        return roundTrip(AcoMessageType.SECTOR_JOIN_REQUEST, sectorKey);
    }

    /** Release a held sector on the connected authority. */
    public synchronized String requestRelease(String sectorKey, String sessionId, long ownershipVersion) {
        String payload = sectorKey + "|" + sessionId + "|" + ownershipVersion;
        return roundTrip(AcoMessageType.SECTOR_RELEASE, payload);
    }

    /** Send one request frame and read one response frame over the live socket. */
    private String roundTrip(AcoMessageType requestType, String payload) {
        if (authoritySocket == null || authoritySocket.isClosed()) {
            Log.warn("ACO_ROUNDTRIP_NOT_CONNECTED type=@", requestType);
            return null;
        }
        try {
            OutputStream out = authoritySocket.getOutputStream();
            InputStream in = authoritySocket.getInputStream();

            AcoFrame req = new AcoFrame((byte) 1, requestType.id, 1,
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(req.encode());
            out.flush();

            byte[] header = new byte[AcoFrame.HEADER_SIZE];
            if (!readFully(in, header)) return null;
            ByteBuffer hb = ByteBuffer.wrap(header);
            int type = hb.get(1);
            int len = hb.getInt(6);
            if (len < 0 || len > AcoFrame.MAX_PAYLOAD) return null;
            byte[] body = new byte[len];
            if (!readFully(in, body)) return null;

            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("ACO_ROUNDTRIP_FAILED type=@ reason=@", requestType, e.getMessage());
            return null;
        }
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

    // --- sc.CampaignActions (headless-testable UI seam) ---

    @Override
    public void hostCampaignAction(int syncPort, int sectorPort) {
        hostCampaign(syncPort, sectorPort);
    }

    @Override
    public void connectAction(String host, int port) {
        connectToAuthority(host, port);
    }

    public synchronized void dispose() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (p2Manager != null) p2Manager.stopP2();
        closeQuietly(authoritySocket);
        authoritySocket = null;
        connected = false;
        hosting = false;
        Log.info("ACO_CONTROLLER_DISPOSE");
    }

    public synchronized boolean isHosting() {
        return hosting;
    }

    public synchronized boolean isConnected() {
        return connected;
    }

    public synchronized long getGlobalRevision() {
        return globalState == null ? 0 : globalState.globalRevision;
    }

    public synchronized String getStatus() {
        return String.format("aco{init=%b,hosting=%b,syncPort=%d,connected=%b,revision=%d}",
            initialized, hosting, syncPort, connected, getGlobalRevision());
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}

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

    public AcoController(String dataDirName) {
        this.storageDir = Paths.get(dataDirName);
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

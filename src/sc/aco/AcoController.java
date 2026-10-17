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

    /**
     * Cap on unsolicited broadcast frames a single round trip will skip before
     * giving up, so a peer that only ever broadcasts cannot hang the client.
     */
    private static final int MAX_SKIPPED_FRAMES = 8;

    /** Diagnostics: total unsolicited broadcast frames skipped across requests. */
    private long skippedSyncFrames;

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

        // A campaign with no progression yet must still offer its starting sector,
        // or the authority would reject the very first completion as LOCKED. Only a
        // campaign that has never recorded anything gets the seed, so this can never
        // re-grant progression after recovery.
        if (globalState.unlockedSectors.isEmpty() && globalState.completedSectors.isEmpty()) {
            globalState.unlockStartingSectors();
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
        return roundTrip(AcoMessageType.SECTOR_CLAIM_REQUEST, payload, AcoMessageType.SECTOR_CLAIM_RESULT.id);
    }

    /** Join (resolve endpoint of) a sector on the connected authority. */
    public synchronized String requestJoin(String sectorKey) {
        // Success answers with SECTOR_HOST_CHANGED, a refusal with SECTOR_MIGRATION_RESULT.
        return roundTrip(AcoMessageType.SECTOR_JOIN_REQUEST, sectorKey,
            AcoMessageType.SECTOR_HOST_CHANGED.id, AcoMessageType.SECTOR_MIGRATION_RESULT.id);
    }

    /** Release a held sector on the connected authority. */
    public synchronized String requestRelease(String sectorKey, String sessionId, long ownershipVersion) {
        String payload = sectorKey + "|" + sessionId + "|" + ownershipVersion;
        return roundTrip(AcoMessageType.SECTOR_RELEASE, payload, AcoMessageType.SECTOR_RELEASE.id);
    }

    /**
     * Submit a global resource transaction to the connected authority.
     * Payload order matches {@link GlobalResourceRequest}'s components.
     */
    public synchronized String requestResource(String operationId, String sessionId, String sectorId,
                                               long expectedGlobalRevision, String resourceType, long amount,
                                               GlobalResourceRequest.OperationType operationType) {
        String payload = operationId + "|" + sessionId + "|" + sectorId + "|" + expectedGlobalRevision
            + "|" + resourceType + "|" + amount + "|" + operationType.name();
        return roundTrip(AcoMessageType.GLOBAL_RESOURCE_REQUEST, payload,
            AcoMessageType.GLOBAL_RESOURCE_RESPONSE.id);
    }

    /** Report a sector completion to the connected authority. */
    public synchronized String requestSectorCompletion(String sectorKey, String operationId) {
        return roundTrip(AcoMessageType.SECTOR_COMPLETION_EVENT, sectorKey + "|" + operationId,
            AcoMessageType.SECTOR_COMPLETION_EVENT.id);
    }

    /**
     * Send one request frame and read its response over the live socket.
     *
     * <p>Several handlers ({@code handleResourceRequest}, {@code handleSectorCompletion})
     * also push an unsolicited {@code GLOBAL_STATE_SYNC} frame to <em>every</em>
     * session, including the requester. Reading "the next frame" would therefore
     * return the queued broadcast instead of the response — see
     * {@code reports/2026-10-17-broadcast-desync.md}. This method skips known
     * unsolicited broadcasts (counting them) and keeps reading until it sees one of
     * the response types it is waiting for, bounded by {@link #MAX_SKIPPED_FRAMES}
     * so a misbehaving peer cannot hang the client.
     *
     * <p>The response id is not always the request id: {@code SECTOR_CLAIM_REQUEST}
     * is answered with {@code SECTOR_CLAIM_RESULT}, and a join is answered with
     * either {@code SECTOR_HOST_CHANGED} (success) or
     * {@code SECTOR_MIGRATION_RESULT} (refusal), so the caller lists the acceptable
     * responses explicitly.
     *
     * @return the response payload, or null when not connected or the exchange failed
     */
    private String roundTrip(AcoMessageType requestType, String payload, byte... expectedResponses) {
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

            for (int skipped = 0; skipped <= MAX_SKIPPED_FRAMES; skipped++) {
                AcoFrame frame = readFrame(in);
                if (frame == null) return null;
                for (byte expected : expectedResponses) {
                    if (frame.type() == expected) {
                        return new String(frame.payload(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
                if (isUnsolicited(frame.type())) {
                    skippedSyncFrames++;
                    Log.debug("ACO_ROUNDTRIP_SKIP_BROADCAST awaiting=@ got=@", requestType, frame.type());
                    continue;
                }
                // An unexpected, non-broadcast frame: do not guess, report failure.
                Log.warn("ACO_ROUNDTRIP_UNEXPECTED awaiting=@ got=@", requestType, frame.type());
                return null;
            }
            Log.warn("ACO_ROUNDTRIP_TOO_MANY_SKIPS awaiting=@", requestType);
            return null;
        } catch (IOException e) {
            Log.warn("ACO_ROUNDTRIP_FAILED type=@ reason=@", requestType, e.getMessage());
            return null;
        }
    }

    /** Frames the authority pushes without a matching request. */
    private static boolean isUnsolicited(byte type) {
        return type == AcoMessageType.GLOBAL_STATE_SYNC.id;
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

    /** Total unsolicited broadcast frames skipped while awaiting responses. */
    public synchronized long getSkippedSyncFrames() {
        return skippedSyncFrames;
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

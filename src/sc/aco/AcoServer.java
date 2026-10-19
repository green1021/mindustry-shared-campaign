package sc.aco;

import arc.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ACO TCP server: the transport around the campaign control plane.
 *
 * <p>What changed from the old server, and why:
 *
 * <ul>
 *   <li>It never calls into a sector runtime or starts P2 for a client. Claims are
 *       answered purely from {@link CampaignAuthority} (control plane); the claiming
 *       client's own machine runs the world ({@link LocalSectorSession}).</li>
 *   <li>Old SAVE_TRANSFER* /MIGRATION frames (byte types 6-9, 11-13) are replaced by
 *       the validated snapshot protocol (30-33): BEGIN/CHUNK/END whose assembled
 *       bytes are SHA-256/length-checked by {@link SectorSnapshotCodec} and
 *       metadata-validated by {@link CampaignSectorStore}, then committed; the
 *       ACK answers the END frame's message id.</li>
 *   <li>Directed responses echo the request message id (msgId), so the client can
 *       correlate responses while also receiving unsolicited broadcasts -- the old
 *       "reply with msgId 0" pattern made handoff notifications impossible.</li>
 *   <li>Sector authorization for resource transactions now consults the lease
 *       registry (owner connection), falling back to campaign-open sectors for
 *       host-run sectors; the ad-hoc {@code hostedConnections} map is gone.</li>
 *   <li>Disconnects orphan the connection's active leases (durable, journaled) and
 *       abort any pending handoff owned by that connection.</li>
 * </ul>
 */
public class AcoServer {

    /** Hard cap on concurrently served connections; excess is refused, not queued. */
    public static final int MAX_CONNECTIONS = 64;
    public static final int MAX_CONTENT_NAME = 64;
    public static final int MAX_OPERATION_ID = 128;
    public static final int MAX_NEIGHBORS = 16;
    /** Keys are free-form sector ids; bound the persisted unlock set as well. */
    public static final int MAX_UNLOCKED_SECTORS = 256;
    /**
     * Research is persisted in the same snapshot as the bank, and the whole state
     * sync has to fit one frame. Both tables therefore need a hard ceiling: a
     * client could otherwise add one valid entry per request forever, growing the
     * snapshot without bound and eventually pushing GLOBAL_STATE_SYNC past
     * MAX_PAYLOAD (where the broadcast is dropped and clients stop syncing).
     * Vanilla has ~150 tech nodes and ~20 items, so these caps are far above any
     * legitimate campaign.
     */
    public static final int MAX_TECH_ENTRIES = 512;
    private static final java.util.regex.Pattern CONTENT_NAME =
        java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1," + MAX_CONTENT_NAME + "}");

    private final int port;
    /**
     * Blocking sockets need one thread per connection. A fixed pool of four meant
     * four idle clients starved every other player: the acceptor kept accepting but
     * no thread ever became free. An unbounded cached pool would let connections
     * consume the heap, so the pool is capped and excess connections are refused
     * with a close instead of waiting forever.
     */
    private final ExecutorService ioExecutor = new java.util.concurrent.ThreadPoolExecutor(
        0, MAX_CONNECTIONS, 60L, java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.SynchronousQueue<>(),
        r -> {
            Thread t = new Thread(r, "aco-conn");
            t.setDaemon(true);
            return t;
        });
    private volatile boolean running = true;
    private final Map<String, AcoSession> sessions = new ConcurrentHashMap<>();
    /** Per-connection snapshot assembly state (one transfer at a time per connection). */
    private final Map<String, Assembly> assemblies = new ConcurrentHashMap<>();
    private final CampaignAuthority authority;
    private final SectorRegistry registry;
    private final GlobalCampaignState globalState;
    private final GlobalTransactionManager txManager;
    private SectorHandoffManager handoffManager;
    private CampaignSectorStore sectorStore;

    public AcoServer(int port, CampaignAuthority authority, SectorRegistry registry,
            GlobalCampaignState globalState, GlobalTransactionManager txManager) {
        this.port = port;
        this.authority = authority;
        this.registry = registry;
        this.globalState = globalState;
        this.txManager = txManager;
    }

    public void setHandoffManager(SectorHandoffManager manager) {
        this.handoffManager = manager;
    }

    public void setSectorStore(CampaignSectorStore store) {
        this.sectorStore = store;
    }

    private ServerSocket serverSocket;

    /**
     * Bind synchronously, then serve in the background. Binding used to happen on
     * the acceptor thread, so {@link #start()} returned before the port was known to
     * be usable: a caller could report "hosting" for a port that was already taken
     * and only a later client connect would fail. A failed bind is now thrown from
     * this method (the acceptor thread is not started at all).
     */
    public void start() {
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            running = false;
            Log.err("ACO_SERVER_BIND_FAILED port=@", port, e);
            throw new IllegalStateException("aco-bind-failed:" + port, e);
        }
        Thread acceptor = new Thread(() -> {
            try {
                while (running && !serverSocket.isClosed()) {
                    Socket s;
                    try {
                        s = serverSocket.accept();
                    } catch (IOException acceptFailure) {
                        // A transient accept error (EMFILE, ECONNABORTED) must not end
                        // the acceptor: that used to stop all new connections until
                        // the process restarted.
                        if (running && !serverSocket.isClosed()) Log.err("ACO_ACCEPT_FAILED", acceptFailure);
                        continue;
                    }
                    try {
                        ioExecutor.submit(() -> handle(s));
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        Log.warn("ACO_CONNECTION_REFUSED active=@", MAX_CONNECTIONS);
                        try {
                            s.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (RuntimeException e) {
                // Accept and submit both handle their own IOException above; anything
                // reaching here is a programming error, and it must not vanish.
                if (running) Log.err("ACO_SERVER_ERROR", e);
            }
        }, "aco-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        ioExecutor.shutdownNow();
    }

    /** Register a session output (used by tests and by the connection handler). */
    public void registerSession(String sessionId, String campaignId, OutputStream out) {
        sessions.put(sessionId, new AcoSession(SyncOutputStream.wrap(out)));
    }

    // --------------------------------------------------------------- connection

    private void handle(Socket s) {
        String sessionId = java.util.UUID.randomUUID().toString();
        try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
            // Must hold one complete maximum-size frame: HEADER_SIZE + MAX_PAYLOAD.
            // 65536 alone could never fit a 65536-byte payload, so a peer that sent
            // one stalled the reader in compact()/BufferOverflowException forever.
            ByteBuffer buf = ByteBuffer.allocate(AcoFrame.HEADER_SIZE + AcoFrame.MAX_PAYLOAD);
            registerSession(sessionId, "default", out);
            while (running) {
                int read = in.read(buf.array(), buf.position(), buf.remaining());
                if (read == -1) break;
                buf.position(buf.position() + read);
                buf.flip();
                while (buf.remaining() >= AcoFrame.HEADER_SIZE) {
                    AcoFrame frame = AcoFrame.decode(buf);
                    if (frame == null) break;
                    process(frame, out, sessionId);
                }
                buf.compact();
            }
        } catch (Exception e) {
            if (running) Log.err("ACO_CONNECTION_ERROR", e);
        } finally {
            onDisconnect(sessionId);
        }
    }

    /**
     * A connection went away: park its active leases (ORPHANED, journaled) so no
     * other machine can claim them silently, and abort a pending handoff it owned.
     */
    private void onDisconnect(String sessionId) {
        sessions.remove(sessionId);
        assemblies.remove(sessionId);
        if (handoffManager != null) handoffManager.onOwnerDisconnected(sessionId);
        try {
            java.util.List<String> orphaned = registry.orphanSession(sessionId);
            for (String key : orphaned) {
                Log.warn("ACO_LEASE_ORPHANED sector=@ session=@", key, sessionId);
                broadcast(AcoMessageType.SECTOR_HOST_CHANGED, key + "|ORPHANED|" + sessionId);
            }
        } catch (RuntimeException e) {
            Log.err("ACO_ORPHAN_FAILED session=@ : @", sessionId, e.getMessage());
        }
        Log.info("ACO_SESSION_CLOSED session=@", sessionId);
    }

    // ---------------------------------------------------------------- dispatch

    /**
     * A client aborted a host-takeover before committing: its save failed or the
     * snapshot transfer did not complete. The sector stays owned by that client
     * (its world was never left); the manager only records the abort so the host
     * stops waiting. Ownership is untouched here by construction.
     */
    private void handleHandoffAbort(AcoFrame f, String connSessionId) {
        String[] p = str(f).split("\\|", -1);
        String key = p.length > 0 ? p[0] : "";
        String reason = p.length > 2 ? p[2] : (p.length > 1 ? p[1] : "ABORT");
        Log.warn("SECTOR_HANDOFF_ABORT sector=@ reason=@ session=@", key, reason, connSessionId);
        if (handoffManager != null) handoffManager.abort(key, reason);
    }

    private void process(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        if (f.type() == AcoMessageType.PING.id) {
            out.write(new AcoFrame(f.version(), AcoMessageType.PONG.id, f.msgId(), new byte[0]).encode());
        } else if (f.type() == AcoMessageType.SECTOR_CLAIM_REQUEST.id) {
            handleClaimRequest(f, out, connSessionId);
        } else if (f.type() == AcoMessageType.SECTOR_RELEASE.id) {
            handleReleaseRequest(f, out, connSessionId);
        } else if (f.type() == AcoMessageType.SECTOR_HANDOFF_ABORT.id) {
            handleHandoffAbort(f, connSessionId);
        } else if (f.type() == AcoMessageType.SECTOR_REATTACH.id) {
            handleReattachRequest(f, out, connSessionId);
        } else if (f.type() == AcoMessageType.SECTOR_JOIN_REQUEST.id) {
            handleJoinRequest(f, out, connSessionId);
        } else if (f.type() == AcoMessageType.SECTOR_SNAPSHOT_BEGIN.id) {
            handleSnapshotBegin(f, connSessionId);
        } else if (f.type() == AcoMessageType.SECTOR_SNAPSHOT_CHUNK.id) {
            handleSnapshotChunk(f, connSessionId);
        } else if (f.type() == AcoMessageType.SECTOR_SNAPSHOT_END.id) {
            handleSnapshotEnd(f, out, connSessionId);
        } else if (f.type() == AcoMessageType.GLOBAL_RESOURCE_REQUEST.id) {
            handleResourceRequest(f, out, connSessionId);
        } else if (f.type() == AcoMessageType.RESEARCH_REQUEST.id) {
            handleResearchRequest(f, out, connSessionId);
        } else if (f.type() == AcoMessageType.SECTOR_COMPLETION_EVENT.id) {
            handleSectorCompletion(f, out, connSessionId);
        }
    }

    // ------------------------------------------------------------------- phase B

    private void handleClaimRequest(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        String payload = str(f);
        String[] p = payload.split("\\|", -1);
        // sectorKey|revision -- identity comes from the connection, never the payload.
        if (p.length != 2) {
            send(out, AcoMessageType.SECTOR_CLAIM_RESULT, f.msgId(), "|FAIL|INVALID_REQUEST_FIELDS");
            return;
        }
        long revision;
        try {
            revision = Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            send(out, AcoMessageType.SECTOR_CLAIM_RESULT, f.msgId(), p[0] + "|FAIL|INVALID_REVISION");
            return;
        }
        authority.handleClaimRequest(p[0], connSessionId, revision, f.msgId(), out);
    }

    private void handleReleaseRequest(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        String[] p = str(f).split("\\|", -1);
        // sectorKey|ownershipVersion|grant
        if (p.length != 3) {
            send(out, AcoMessageType.SECTOR_RELEASE, f.msgId(), "|FAIL|INVALID_REQUEST_FIELDS");
            return;
        }
        long version;
        try {
            version = Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            send(out, AcoMessageType.SECTOR_RELEASE, f.msgId(), p[0] + "|FAIL|INVALID_VERSION");
            return;
        }
        SectorRegistry.Result r = authority.handleReleaseRequest(p[0], connSessionId, version, p[2], f.msgId(), out);
        if (r == SectorRegistry.Result.OK) {
            SectorOwnership own = registry.get(p[0]);
            long newVersion = own == null ? -1 : own.ownershipVersion;
            broadcast(AcoMessageType.SECTOR_HOST_CHANGED, p[0] + "|FREE|" + newVersion);
            if (handoffManager != null) handoffManager.onReleased(p[0]);
        }
    }

    private void handleReattachRequest(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        String[] p = str(f).split("\\|", -1);
        // sectorKey|grant
        if (p.length != 2) {
            send(out, AcoMessageType.SECTOR_REATTACH, f.msgId(), "|FAIL|INVALID_REQUEST_FIELDS");
            return;
        }
        authority.handleReattachRequest(p[0], connSessionId, p[1], f.msgId(), out);
    }

    private void handleJoinRequest(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        String[] p = str(f).split("\\|", -1);
        // The authority paths validate their own key; join is a pure read and used to
        // skip that check, so a 64 KiB key was echoed back in the response payload.
        if (!SectorSnapshotCodec.validSectorKey(p[0])) {
            send(out, AcoMessageType.SECTOR_JOIN_RESPONSE, f.msgId(), "|FAIL|INVALID_SECTOR_KEY");
            return;
        }
        authority.handleJoinRequest(p[0], connSessionId, f.msgId(), out);
    }

    // --------------------------------------------------------------- snapshots

    private static final class Assembly {
        String key;
        long revision;
        int total;
        String sha;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int expectedSeq;
        String failure;
    }

    private void handleSnapshotBegin(AcoFrame f, String connSessionId) {
        SectorSnapshotCodec.Begin begin = SectorSnapshotCodec.parseBegin(str(f));
        if (begin == null) {
            Log.warn("SNAPSHOT_BEGIN_MALFORMED conn=@", connSessionId);
            return; // no assembly: a later END gets NO_ASSEMBLY
        }
        Assembly a = new Assembly();
        a.key = begin.key();
        a.revision = begin.revision();
        a.total = begin.totalLength();
        a.sha = begin.sha();
        // Only the connection that actively holds the CLIENT lease may upload worlds
        // for the sector. This is not authentication; it is the same connection-bound
        // authorization the rest of the control plane uses.
        SectorOwnership own = registry.get(begin.key());
        if (own == null || !own.active() || own.kind != SectorOwnership.OwnerKind.CLIENT
                || !connSessionId.equals(own.ownerSessionId)) {
            a.failure = "NOT_SECTOR_OWNER";
        }
        assemblies.put(connSessionId, a);
    }

    private void handleSnapshotChunk(AcoFrame f, String connSessionId) {
        Assembly a = assemblies.get(connSessionId);
        if (a == null) return;
        if (a.failure != null) return;
        SectorSnapshotCodec.Chunk chunk = SectorSnapshotCodec.parseChunk(str(f));
        if (chunk == null) {
            a.failure = "MALFORMED_CHUNK";
            return;
        }
        if (!chunk.key().equals(a.key)) {
            a.failure = "KEY_MISMATCH";
            return;
        }
        if (chunk.seq() != a.expectedSeq) {
            a.failure = "SEQ_MISMATCH expected=" + a.expectedSeq + " got=" + chunk.seq();
            return;
        }
        if (a.buf.size() + chunk.raw().length > a.total) {
            a.failure = "OVERSIZE";
            return;
        }
        a.buf.write(chunk.raw(), 0, chunk.raw().length);
        a.expectedSeq++;
    }

    private void handleSnapshotEnd(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        SectorSnapshotCodec.End end = SectorSnapshotCodec.parseEnd(str(f));
        Assembly a = assemblies.remove(connSessionId);
        if (a == null) {
            send(out, AcoMessageType.SECTOR_SNAPSHOT_ACK, f.msgId(),
                    SectorSnapshotCodec.ackPayload(end == null ? "unknown" : end.key(), false, "NO_ASSEMBLY"));
            return;
        }
        try {
            if (a.failure != null) {
                refuseSnapshot(out, f, a.key, a.failure);
                return;
            }
            if (end == null || !end.key().equals(a.key)) {
                refuseSnapshot(out, f, a.key, "END_MISMATCH");
                return;
            }
            byte[] bytes = a.buf.toByteArray();
            if (bytes.length != a.total) {
                refuseSnapshot(out, f, a.key, "LENGTH_MISMATCH");
                return;
            }
            if (!SectorSnapshotCodec.constantTimeEquals(SectorSnapshotCodec.sha256(bytes), a.sha)) {
                refuseSnapshot(out, f, a.key, "CHECKSUM_MISMATCH");
                return;
            }
            if (sectorStore == null) {
                refuseSnapshot(out, f, a.key, "STORE_UNAVAILABLE");
                return;
            }
            CampaignSectorStore.SnapshotInfo info = sectorStore.commitSnapshot(a.key, a.revision, bytes, a.sha);
            SectorRegistry.Result rev = registry.setAcceptedRevision(a.key, a.revision);
            if (rev != SectorRegistry.Result.OK) {
                refuseSnapshot(out, f, a.key, "REVISION_RECORD_REFUSED:" + rev.name());
                return;
            }
            send(out, AcoMessageType.SECTOR_SNAPSHOT_ACK, f.msgId(),
                    SectorSnapshotCodec.ackPayload(a.key, true, Long.toString(info.revision())));
            broadcast(AcoMessageType.SECTOR_HOST_CHANGED, a.key + "|CHECKPOINT|" + a.revision);
            if (handoffManager != null) handoffManager.onSnapshotCommitted(a.key, a.revision, a.sha);
            Log.info("SNAPSHOT_COMMITTED sector=@ revision=@ bytes=@", a.key, a.revision, bytes.length);
        } catch (SectorSnapshotCodec.SnapshotRejected e) {
            refuseSnapshot(out, f, a.key, e.getMessage());
        }
    }

    private void refuseSnapshot(OutputStream out, AcoFrame f, String key, String reason) throws IOException {
        Log.warn("SNAPSHOT_REFUSED sector=@ reason=@", key, reason);
        send(out, AcoMessageType.SECTOR_SNAPSHOT_ACK, f.msgId(),
                SectorSnapshotCodec.ackPayload(key == null ? "unknown" : key, false, reason));
    }

    // ------------------------------------------------------------- global state

    /**
     * Handle a global resource request.
     *
     * <p>Canonical payload schema (6 fields):
     * {@code operationId|sectorId|expectedGlobalRevision|resourceType|amount|operationType}.
     * The session identity is NOT part of the payload: it is derived from this
     * connection {@code connSessionId}, never from client-supplied text.
     */
    private void handleResourceRequest(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        String payload = str(f);
        String[] parts = payload.split("\\|", -1);

        if (parts.length != 6) {
            respondError(out, f.msgId(), "", "INVALID_REQUEST_FIELDS",
                    "unsupported payload arity " + parts.length + "; expected 6 fields");
            return;
        }

        GlobalResourceRequest req;
        try {
            req = new GlobalResourceRequest(
                    parts[0], connSessionId, parts[1], Long.parseLong(parts[2]), parts[3], Long.parseLong(parts[4]),
                    GlobalResourceRequest.OperationType.valueOf(parts[5]));
        } catch (IllegalArgumentException e) {
            respondError(out, f.msgId(), parts[0], "INVALID_REQUEST_FIELDS", "malformed field");
            return;
        }

        if (!isSectorAuthorized(parts[1], connSessionId)) {
            respondError(out, f.msgId(), parts[0], "UNAUTHORIZED_SECTOR",
                    "session not authorized for sector " + parts[1]);
            return;
        }

        TransactionResult res = txManager.execute(req);
        String responsePayload = res.operationId() + "|" + res.success() + "|" + res.error() + "|" + res.newRevision() + "|" + res.newBalance();
        send(out, AcoMessageType.GLOBAL_RESOURCE_RESPONSE, f.msgId(), responsePayload);

        if (res.success()) {
            broadcastStateSync();
        }
    }

    /**
     * Authorization for resource transactions on {@code sectorKey}: the active
     * lease owner may transact; when no lease governs the sector, a campaign-open
     * (unlocked) sector is allowed -- that is the host's own play path.
     */
    private boolean isSectorAuthorized(String sectorKey, String connSessionId) {
        if (sectorKey == null || sectorKey.isBlank()) return false;
        SectorOwnership own = registry.get(sectorKey);
        if (own != null && own.active()) {
            return own.ownerSessionId != null && own.ownerSessionId.equals(connSessionId);
        }
        return globalState != null && globalState.unlockedSectors.contains(sectorKey);
    }

    private void respondError(OutputStream out, int msgId, String operationId, String error, String detail) throws IOException {
        String curRevision = globalState == null ? "0" : Long.toString(globalState.globalRevision);
        String responsePayload = (operationId == null ? "" : operationId) + "|false|" + error + "|" + curRevision + "|0";
        send(out, AcoMessageType.GLOBAL_RESOURCE_RESPONSE, msgId, responsePayload);
        Log.warn("ACO_RESOURCE_REJECTED: @ (@)", error, detail);
    }

    private void broadcastStateSync() {
        // The wire view omits the idempotency table: clients fold the campaign, they
        // never replay authority operation ids, and a full 1024-entry window would
        // push the frame past MAX_PAYLOAD and kill every connection.
        broadcast(AcoMessageType.GLOBAL_STATE_SYNC, CampaignPersistence.toSyncJson(globalState));
    }

    private String currentRevision() {
        return Long.toString(globalState.globalRevision);
    }

    /** Persist while the caller holds the state monitor; false means "rolled back". */
    private boolean persistLocked(String kind, String detail) {
        try {
            authority.getPersistence().save(globalState);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.err("ACO_PERSIST_FAILED kind=@ detail=@", kind, detail, e);
            return false;
        }
    }

    private void handleResearchRequest(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        String[] parts = str(f).split("\\|", -1);
        String tech = parts[0].trim();
        String opId = parts.length > 1 ? parts[1].trim() : "";

        // Validated before it can reach the campaign map or the snapshot: an
        // unbounded or malformed content name would otherwise be persisted forever.
        if (tech.isEmpty() || tech.length() > MAX_CONTENT_NAME || !CONTENT_NAME.matcher(tech).matches()) {
            Log.warn("ACO_RESEARCH_REJECTED invalid-tech session=@ length=@", connSessionId, tech.length());
            send(out, AcoMessageType.RESEARCH_RESPONSE, f.msgId(), "|FAIL|INVALID_TECH|" + currentRevision() + "|0");
            return;
        }
        if (opId.isEmpty()) {
            // A per-entry fallback, not "research:" + tech: that key made a replay of
            // ANY earlier request for the same tech answer SUCCESS without applying
            // this one, so a retry after a disconnect could be silently dropped.
            opId = "auto|" + connSessionId + "|" + f.msgId();
        }
        if (opId.length() > MAX_OPERATION_ID) {
            send(out, AcoMessageType.RESEARCH_RESPONSE, f.msgId(), "|FAIL|INVALID_OPERATION_ID|" + currentRevision() + "|0");
            return;
        }
        String key = "research|" + tech + "|" + opId;

        synchronized (globalState) {
            if (!globalState.techTree.containsKey(tech) && globalState.techTree.size() >= MAX_TECH_ENTRIES) {
                send(out, AcoMessageType.RESEARCH_RESPONSE, f.msgId(),
                    tech + "|FAIL|TECH_TABLE_FULL|" + currentRevision() + "|0");
                return;
            }
            if (globalState.processedOperations.containsKey(key)) {
                send(out, AcoMessageType.RESEARCH_RESPONSE, f.msgId(),
                    tech + "|SUCCESS|" + currentRevision() + "|");
                return;
            }
            long previousRevision = globalState.globalRevision;
            boolean wasResearched = Boolean.TRUE.equals(globalState.techTree.put(tech, true));
            globalState.recordProcessed(key, Boolean.TRUE);
            if (!wasResearched) {
                globalState.globalRevision = previousRevision + 1;
                if (!persistLocked("research", tech)) {
                    globalState.techTree.remove(tech);
                    globalState.globalRevision = previousRevision;
                    globalState.forgetProcessed(key);
                    send(out, AcoMessageType.RESEARCH_RESPONSE, f.msgId(),
                        tech + "|FAIL|PERSISTENCE_FAILURE|" + previousRevision + "|0");
                    return;
                }
            }
            send(out, AcoMessageType.RESEARCH_RESPONSE, f.msgId(),
                tech + "|SUCCESS|" + currentRevision() + "|");
        }
        broadcastStateSync();
    }

    private void handleSectorCompletion(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        String[] parts = str(f).split("\\|", -1);
        String sectorId = parts == null || parts.length == 0 ? "" : parts[0].trim();
        String opId = parts.length > 1 ? parts[1].trim() : "";
        String neighborCsv = parts.length > 2 ? parts[2] : "";

        if (!SectorSnapshotCodec.validSectorKey(sectorId)) {
            Log.warn("ACO_COMPLETION_REJECTED invalid-sector session=@", connSessionId);
            send(out, AcoMessageType.SECTOR_COMPLETION_EVENT, f.msgId(),
                "INVALID_SECTOR|FAIL|INVALID_SECTOR_KEY|" + currentRevision() + "|0");
            return;
        }
        if (opId.isEmpty()) opId = "auto|" + connSessionId + "|" + f.msgId();
        if (opId.length() > MAX_OPERATION_ID) {
            send(out, AcoMessageType.SECTOR_COMPLETION_EVENT, f.msgId(),
                sectorId + "|FAIL|INVALID_OPERATION_ID|" + currentRevision() + "|0");
            return;
        }
        String key = "completion|" + sectorId + "|" + opId;

        synchronized (globalState) {
            if (globalState.processedOperations.containsKey(key)) {
                send(out, AcoMessageType.SECTOR_COMPLETION_EVENT, f.msgId(),
                    sectorId + "|SUCCESS|" + currentRevision() + "|");
                return;
            }
            if (!globalState.unlockedSectors.contains(sectorId)) {
                send(out, AcoMessageType.SECTOR_COMPLETION_EVENT, f.msgId(),
                    sectorId + "|FAIL|LOCKED|" + currentRevision() + "|0");
                return;
            }
            if (parseNeighborKeys(neighborCsv).isEmpty()) {
                // Not fatal (a client may legitimately have no adjacency data), but
                // logged loudly: without neighbours the campaign cannot advance.
                Log.warn("ACO_COMPLETION_NO_NEIGHBORS sector=@ session=@", sectorId, connSessionId);
            }

            long previousRevision = globalState.globalRevision;
            boolean wasCompleted = globalState.completedSectors.contains(sectorId);
            boolean wasUnlocked = globalState.unlockedSectors.contains(sectorId);
            java.util.List<String> added = new java.util.ArrayList<>();
            globalState.markSectorCompleted(sectorId);
            for (String n : parseNeighborKeys(neighborCsv)) {
                if (globalState.unlockedSectors.size() >= MAX_UNLOCKED_SECTORS) break;
                if (globalState.unlockedSectors.add(n)) added.add(n);
            }
            globalState.globalRevision = previousRevision + 1;
            globalState.recordProcessed(key, Boolean.TRUE);
            if (!persistLocked("completion", sectorId)) {
                if (!wasCompleted) globalState.completedSectors.remove(sectorId);
                if (!wasUnlocked) globalState.unlockedSectors.remove(sectorId);
                globalState.unlockedSectors.removeAll(added);
                globalState.globalRevision = previousRevision;
                globalState.forgetProcessed(key);
                send(out, AcoMessageType.SECTOR_COMPLETION_EVENT, f.msgId(),
                    sectorId + "|FAIL|PERSISTENCE_FAILURE|" + previousRevision + "|0");
                return;
            }
            send(out, AcoMessageType.SECTOR_COMPLETION_EVENT, f.msgId(),
                sectorId + "|SUCCESS|" + currentRevision() + "|");
        }
        broadcastStateSync();
    }

    /**
     * Parse the optional neighbour list. Only sector-shaped keys are accepted and
     * the list is capped, because it is untrusted client input that ends up in the
     * persisted unlock set.
     */
    private static java.util.List<String> parseNeighborKeys(String csv) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (csv == null || csv.isEmpty()) return out;
        for (String raw : csv.split(",", -1)) {
            String k = raw.trim();
            if (k.isEmpty()) continue;
            if (out.size() >= MAX_NEIGHBORS) break;
            if (SectorSnapshotCodec.validSectorKey(k)) out.add(k);
            else Log.warn("ACO_COMPLETION_BAD_NEIGHBOR key=@", k);
        }
        return out;
    }

    // -------------------------------------------------------------- notifiers

    /** Directed send used by {@link SectorHandoffManager} (SECTOR_HANDOFF_REQUEST). */
    public boolean sendToSession(String sessionId, AcoMessageType type, String payload) {
        AcoSession sess = sessions.get(sessionId);
        if (sess == null) return false;
        byte[] frame;
        try {
            frame = new AcoFrame((byte) 1, type.id, 0, payload.getBytes(StandardCharsets.UTF_8)).encode();
        } catch (IllegalArgumentException tooLarge) {
            Log.err("ACO_SEND_TOO_LARGE session=@ type=@ bytes=@", sessionId, type, payload.length());
            return false;
        }
        try {
            sess.out.write(frame);
            return true;
        } catch (IOException e) {
            Log.warn("ACO_SEND_TO_SESSION_FAILED session=@ type=@ : @", sessionId, type, e.getMessage());
            dropSession(sessionId, sess, "SEND_FAILED");
            return false;
        }
    }

    public void broadcast(AcoMessageType type, String payload) {
        byte[] frame;
        try {
            frame = new AcoFrame((byte) 1, type.id, 0, payload.getBytes(StandardCharsets.UTF_8)).encode();
        } catch (IllegalArgumentException tooLarge) {
            // One oversized payload used to abort the whole broadcast loop mid-way,
            // leaving later sessions without the state update.
            Log.err("ACO_BROADCAST_TOO_LARGE type=@ bytes=@", type, payload.length());
            return;
        }
        for (Map.Entry<String, AcoSession> e : sessions.entrySet()) {
            try {
                e.getValue().out.write(frame);
            } catch (IOException | RuntimeException ex) {
                // A dead socket never recovers; keeping it registered makes every
                // later broadcast fail and leak the entry for the process lifetime.
                dropSession(e.getKey(), e.getValue(), "BROADCAST_FAILED");
            }
        }
    }

    private void dropSession(String sessionId, AcoSession sess, String reason) {
        if (sessions.remove(sessionId, sess)) {
            Log.warn("ACO_SESSION_DROPPED session=@ reason=@", sessionId, reason);
        }
        try {
            sess.out.close();
        } catch (IOException ignored) {
        }
    }

    private static void send(OutputStream out, AcoMessageType type, int msgId, String payload) throws IOException {
        out.write(new AcoFrame((byte) 1, type.id, msgId, payload.getBytes(StandardCharsets.UTF_8)).encode());
    }

    private static String str(AcoFrame f) {
        return new String(f.payload(), StandardCharsets.UTF_8);
    }
}

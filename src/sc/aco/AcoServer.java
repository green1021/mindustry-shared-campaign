package sc.aco;

import arc.util.Log;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AcoServer {
    private final int port;
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(4);
    private boolean running = true;
    private final Map<String, AcoTransferSession> activeTransfers = new ConcurrentHashMap<>();
    private final Map<String, AcoSession> sessions = new ConcurrentHashMap<>();
    /**
     * Server-side sector-host bindings: sector key to the connection session that
     * claimed (or was first authorized for) it. Used by resource-request
     * authorization; populated from server-derived connection identity only.
     */
    private final Map<String, String> hostedConnections = new ConcurrentHashMap<>();
    private final AcoMigrationManager migrationManager;
    private final SectorRegistry registry;
    private final CampaignAuthority authority;
    private final GlobalCampaignState globalState;
    private final GlobalTransactionManager txManager;

    public AcoServer(int port, AcoMigrationManager migrationManager, CampaignAuthority authority, SectorRegistry registry, GlobalCampaignState globalState, GlobalTransactionManager txManager) {
        this.port = port;
        this.migrationManager = migrationManager;
        this.authority = authority;
        this.registry = registry;
        this.globalState = globalState;
        this.txManager = txManager;
    }

    private ServerSocket serverSocket;

    public void start() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                while (running && !serverSocket.isClosed()) {
                    Socket s = serverSocket.accept();
                    ioExecutor.submit(() -> handle(s));
                }
            } catch (IOException e) {
                if (running) Log.err("ACO_SERVER_ERROR", e);
            }
        }).start();
    }

    public void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        ioExecutor.shutdownNow();
    }

    public void registerSession(String sessionId, String campaignId, OutputStream out) {
        sessions.put(sessionId, new AcoSession(out));
        migrationManager.registerSession(sessionId, campaignId);
    }

    private void handle(Socket s) {
        try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
            ByteBuffer buf = ByteBuffer.allocate(65536);
            String sessionId = java.util.UUID.randomUUID().toString(); // server-generated connection identity (binding, not authentication)
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
        } catch (Exception e) { Log.err("ACO_CONNECTION_ERROR", e); }
    }

    /**
     * Dispatch one frame.
     *
     * <p>{@code connSessionId} is the server-generated identity of THIS connection.
     * It is the only session identity the handlers below accept: peer-supplied
     * session strings in payloads are never trusted for identity or authorization
     * (authentication is not yet implemented — see {@code handleResourceRequest}).
     */
    private void process(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        if (f.type() == AcoMessageType.PING.id) {
            out.write(new AcoFrame(f.version(), AcoMessageType.PONG.id, f.msgId(), new byte[0]).encode());
        } else if (f.type() == AcoMessageType.SECTOR_MIGRATION_REQUEST.id) {
            handleMigrationRequest(f, out);
        } else if (f.type() == AcoMessageType.SECTOR_MIGRATION_CONFIRM.id) {
            handleConfirmation(f);
        } else if (f.type() == AcoMessageType.SECTOR_CLAIM_REQUEST.id) {
            handleClaimRequest(f, out, connSessionId);
        } else if (f.type() == AcoMessageType.SECTOR_JOIN_REQUEST.id) { // New
            handleJoinRequest(f, out, connSessionId);
        } else if (f.type() == AcoMessageType.SECTOR_RELEASE.id) {
            handleReleaseRequest(f, out, connSessionId);
        } else if (f.type() == AcoMessageType.GLOBAL_RESOURCE_REQUEST.id) {
            handleResourceRequest(f, out, connSessionId);
        } else if (f.type() == AcoMessageType.RESEARCH_REQUEST.id) {
            handleResearchRequest(f, out);
        } else if (f.type() == AcoMessageType.SECTOR_COMPLETION_EVENT.id) {
            handleSectorCompletion(f, out);
        }
    }

    private void broadcast(AcoMessageType type, byte[] payload) {
        for (AcoSession sess : sessions.values()) {
            try {
                sess.out.write(new AcoFrame((byte)1, type.id, 0, payload).encode());
            } catch (IOException e) { Log.err("BROADCAST_FAILED", e); }
        }
    }

    /**
     * Handle a global resource request.
     *
     * <p>Canonical payload schema (6 fields):
     * {@code operationId|sectorId|expectedGlobalRevision|resourceType|amount|operationType}.
     * The session identity is NOT part of the payload: it is derived from this
     * server-authenticated connection {@code connSessionId}, never from
     * client-supplied text. The removed 7-field (client-identity) and legacy
     * 4-field payloads are rejected explicitly.
     *
     * <p>Documented limitation: peers are bound to their connection and several
     * checks are enforced server-side, but real peer authentication (proving WHO
     * runs a connection) is not implemented — connection identity is the strongest
     * claim the current transport supports.
     */
    private void handleResourceRequest(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|", -1);

        if (parts.length != 6) {
            // Explicit rejection of removed formats and any other arity.
            respondError(out, "", "INVALID_REQUEST_FIELDS",
                "unsupported payload arity " + parts.length + "; expected 6 fields");
            return;
        }

        GlobalResourceRequest req;
        try {
            req = new GlobalResourceRequest(
                parts[0], connSessionId, parts[1], Long.parseLong(parts[2]), parts[3], Long.parseLong(parts[4]),
                GlobalResourceRequest.OperationType.valueOf(parts[5]));
        } catch (IllegalArgumentException e) {
            respondError(out, parts[0], "INVALID_REQUEST_FIELDS", "malformed field");
            return;
        }

        // Authorization: a connection may only transact on sectors it hosts, or on
        // campaign-open sectors (the first authorized connection becomes host).
        if (!isSectorAuthorized(parts[1], connSessionId)) {
            respondError(out, parts[0], "UNAUTHORIZED_SECTOR",
                "session not authorized for sector " + parts[1]);
            return;
        }

        TransactionResult res = txManager.execute(req);
        String responsePayload = res.operationId() + "|" + res.success() + "|" + res.error() + "|" + res.newRevision() + "|" + res.newBalance();
        out.write(new AcoFrame((byte)1, AcoMessageType.GLOBAL_RESOURCE_RESPONSE.id, 0, responsePayload.getBytes()).encode());

        if (res.success()) {
            broadcastStateSync();
        }
    }

    /**
     * Server-side authorization for resource transactions on {@code sectorKey}:
     * only the connection bound as the sector host may transact there; a campaign
     * sector that no connection hosts yet opens for the requesting connection.
     */
    private boolean isSectorAuthorized(String sectorKey, String connSessionId) {
        if (sectorKey == null || sectorKey.isBlank()) return false;
        String host = hostedConnections.get(sectorKey);
        if (host != null) {
            return host.equals(connSessionId);
        }
        if (globalState != null && globalState.unlockedSectors.contains(sectorKey)) {
            hostedConnections.putIfAbsent(sectorKey, connSessionId);
            return true;
        }
        return false;
    }

    /** Explicit rejection responder; the operation id field is always present. */
    private void respondError(OutputStream out, String operationId, String error, String detail) throws IOException {
        String curRevision = globalState == null ? "0" : Long.toString(globalState.globalRevision);
        String responsePayload = (operationId == null ? "" : operationId) + "|false|" + error + "|" + curRevision + "|0";
        out.write(new AcoFrame((byte)1, AcoMessageType.GLOBAL_RESOURCE_RESPONSE.id, 0, responsePayload.getBytes()).encode());
        Log.warn("ACO_RESOURCE_REJECTED: @ (@)", error, detail);
    }

    /**
     * Push the campaign snapshot to every session.
     *
     * <p>Serializes through the authority's own {@link CampaignPersistence}, rooted
     * at the configured storage directory. This used to build a throwaway
     * {@code CampaignPersistence(Paths.get("."))} per broadcast.
     */
    private void broadcastStateSync() {
        broadcast(AcoMessageType.GLOBAL_STATE_SYNC,
            CampaignPersistence.toJson(globalState).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Apply a research report from a sector host to the campaign tech tree.
     *
     * <p>Payload is {@code tech|opId}, with {@code opId} optional. Research is
     * idempotent by tech name: acquiring the same technology twice is the same
     * operation, so the id defaults to {@code "research:" + tech} when omitted. A
     * replayed report is acknowledged without advancing the revision.
     *
     * <p>This used to answer {@code SUCCESS} while mutating nothing — a success that
     * made no state change. It now records the unlock in
     * {@link GlobalCampaignState#techTree}, advances the global revision, persists the
     * snapshot, and broadcasts it, exactly like a completion.
     */
    private void handleResearchRequest(AcoFrame f, OutputStream out) throws IOException {
        String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|", -1);
        String tech = parts[0];
        String opId = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "research:" + tech;

        if (tech.isEmpty()) {
            out.write(new AcoFrame((byte)1, AcoMessageType.RESEARCH_RESPONSE.id, 0,
                "|FAIL|0|".getBytes()).encode());
            return;
        }

        if (globalState.processedOperations.containsKey(opId)) {
            // Already applied: acknowledge with the current revision, change nothing.
            out.write(new AcoFrame((byte)1, AcoMessageType.RESEARCH_RESPONSE.id, 0,
                (tech + "|SUCCESS|" + globalState.globalRevision + "|").getBytes()).encode());
            return;
        }

        globalState.techTree.put(tech, true);
        globalState.globalRevision++;
        globalState.processedOperations.put(opId, true);
        try {
            authority.getPersistence().save(globalState);
        } catch (IOException e) {
            Log.err("ACO_RESEARCH_PERSIST_FAILED tech=@", tech, e);
        }

        out.write(new AcoFrame((byte)1, AcoMessageType.RESEARCH_RESPONSE.id, 0,
            (tech + "|SUCCESS|" + globalState.globalRevision + "|").getBytes()).encode());
        broadcastStateSync();
    }

    private void handleSectorCompletion(AcoFrame f, OutputStream out) throws IOException {
        String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|", -1);
        String sectorId = parts[0];
        String opId = parts.length > 1 ? parts[1] : "default-op";

        if (globalState.processedOperations.containsKey(opId)) {
            // Already processed
            String response = sectorId + "|SUCCESS|" + globalState.globalRevision + "|";
            out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_COMPLETION_EVENT.id, 0, response.getBytes()).encode());
            return;
        }

        // Validate: sector unlocked?
        if (!globalState.unlockedSectors.contains(sectorId)) {
            out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_COMPLETION_EVENT.id, 0, "LOCKED|FAIL|0|".getBytes()).encode());
            return;
        }

        // Authoritative update
        globalState.markSectorCompleted(sectorId);
        globalState.globalRevision++;
        globalState.processedOperations.put(opId, true);

        // Persist to the campaign's configured storage directory (not the process CWD).
        try {
            authority.getPersistence().save(globalState);
        } catch (IOException e) {
            Log.err("ACO_COMPLETION_PERSIST_FAILED sector=@", sectorId, e);
        }

        String response = sectorId + "|SUCCESS|" + globalState.globalRevision + "|";
        out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_COMPLETION_EVENT.id, 0, response.getBytes()).encode());

        broadcastStateSync();
    }

    private void handleJoinRequest(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
        // The joiner is identified by its server-bound connection, never by
        // client-supplied payload text.
        authority.handleJoinRequest(payload, connSessionId, out);
    }

    private void handleClaimRequest(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
        // sectorKey|sessionId|deviceId|revision  (4 fields; split(-1) keeps trailing empties)
        String[] p = payload.split("\\|", -1);
        if (p.length < 4) return;
        boolean ok = authority.handleClaimRequest(p[0], p[1], p[2], Long.parseLong(p[3]), out);
        if (ok) {
            // Bind the sector to the claiming CONNECTION: resource transactions are
            // later authorized against this server-side binding only.
            hostedConnections.put(p[0], connSessionId);
        }
    }

    private void handleReleaseRequest(AcoFrame f, OutputStream out, String connSessionId) throws IOException {
        String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
        // sectorKey|sessionId|ownershipVersion
        String[] p = payload.split("\\|", -1);
        if (p.length < 3) return;
        boolean ok = authority.handleReleaseRequest(p[0], p[1], Long.parseLong(p[2]), out);
        if (ok) {
            hostedConnections.remove(p[0]);
        }
    }

    private void handleMigrationRequest(AcoFrame f, OutputStream out) throws IOException {
        try {
            MigrationRequestPayload payload = MigrationRequestPayload.decode(f.payload());

            AcoSession session = sessions.get(payload.sessionId);
            if (session == null) {
                sendError(out, payload.migrationId, "Unknown session");
                return;
            }

            migrationManager.handleMigrationRequest(
                payload.sessionId,
                payload.campaignId,
                payload.source,
                payload.target,
                payload.migrationId,
                out
            );
        } catch (Exception e) {
            Log.err("MIGRATION_REQUEST_PARSE_ERROR", e);
            sendError(out, "unknown", "Malformed request");
        }
    }

    private void handleConfirmation(AcoFrame f) {
        try {
            String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|", -1);
            if (parts.length < 4) return;

            String sessionId = parts[0];
            String migrationId = parts[1];
            String campaignId = parts[2];
            String target = parts[3];

            AcoSession session = sessions.get(sessionId);
            if (session == null || !session.campaignId.equals(campaignId)) {
                Log.err("CONFIRMATION_REJECTED: Invalid session/campaign");
                return;
            }

            migrationManager.handleConfirmation(sessionId, migrationId, campaignId, target);
        } catch (Exception e) {
            Log.err("CONFIRMATION_PARSE_ERROR", e);
        }
    }

    private void sendError(OutputStream out, String migrationId, String error) throws IOException {
        String p = "unknown|" + migrationId + "|FAIL|||" + error;
        out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_MIGRATION_RESULT.id, 0, p.getBytes()).encode());
    }
}
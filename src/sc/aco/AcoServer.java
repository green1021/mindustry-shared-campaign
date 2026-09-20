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
            String sessionId = "temp-" + s.getPort(); // placeholder
            registerSession(sessionId, "default", out);
            while (running) {
                int read = in.read(buf.array(), buf.position(), buf.remaining());
                if (read == -1) break;
                buf.position(buf.position() + read);
                buf.flip();
                while (buf.remaining() >= AcoFrame.HEADER_SIZE) {
                    AcoFrame frame = AcoFrame.decode(buf);
                    if (frame == null) break;
                    process(frame, out);
                }
                buf.compact();
            }
        } catch (Exception e) { Log.err("ACO_CONNECTION_ERROR", e); }
    }

    private void process(AcoFrame f, OutputStream out) throws IOException {
        if (f.type() == AcoMessageType.PING.id) {
            out.write(new AcoFrame(f.version(), AcoMessageType.PONG.id, f.msgId(), new byte[0]).encode());
        } else if (f.type() == AcoMessageType.SECTOR_MIGRATION_REQUEST.id) {
            handleMigrationRequest(f, out);
        } else if (f.type() == AcoMessageType.SECTOR_MIGRATION_CONFIRM.id) {
            handleConfirmation(f);
        } else if (f.type() == AcoMessageType.SECTOR_CLAIM_REQUEST.id) {
            handleClaimRequest(f, out);
        } else if (f.type() == AcoMessageType.SECTOR_JOIN_REQUEST.id) { // New
            handleJoinRequest(f, out);
        } else if (f.type() == AcoMessageType.GLOBAL_RESOURCE_REQUEST.id) {
            handleResourceRequest(f, out);
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

    private void handleResourceRequest(AcoFrame f, OutputStream out) throws IOException {
        String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|", -1);
        if (parts.length < 7) return;

        GlobalResourceRequest req = new GlobalResourceRequest(
            parts[0], parts[1], parts[2], Long.parseLong(parts[3]), parts[4], Long.parseLong(parts[5]),
            GlobalResourceRequest.OperationType.valueOf(parts[6])
        );

        TransactionResult res = txManager.execute(req);
        String responsePayload = res.operationId() + "|" + res.success() + "|" + res.error() + "|" + res.newRevision() + "|" + res.newBalance();
        out.write(new AcoFrame((byte)1, AcoMessageType.GLOBAL_RESOURCE_RESPONSE.id, 0, responsePayload.getBytes()).encode());
        
        if (res.success()) {
            broadcast(AcoMessageType.GLOBAL_STATE_SYNC, new CampaignPersistence(java.nio.file.Paths.get(".")).toJson(globalState).getBytes());
        }
    }
    
    private void handleResearchRequest(AcoFrame f, OutputStream out) throws IOException {
        String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
        // payload: techName
        String tech = payload.split("\\|", -1)[0];
        
        // Validate and apply research
        // For now, just acknowledge
        String response = tech + "|SUCCESS|0|"; // tech|status|revision
        out.write(new AcoFrame((byte)1, AcoMessageType.RESEARCH_RESPONSE.id, 0, response.getBytes()).encode());
        
        // Broadcast sync if successful
        broadcast(AcoMessageType.GLOBAL_STATE_SYNC, new CampaignPersistence(java.nio.file.Paths.get(".")).toJson(globalState).getBytes());
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
        
        // Persist
        new CampaignPersistence(java.nio.file.Paths.get(".")).save(globalState);
        
        String response = sectorId + "|SUCCESS|" + globalState.globalRevision + "|";
        out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_COMPLETION_EVENT.id, 0, response.getBytes()).encode());
        
        broadcast(AcoMessageType.GLOBAL_STATE_SYNC, new CampaignPersistence(java.nio.file.Paths.get(".")).toJson(globalState).getBytes());
    }
    
    private void handleJoinRequest(AcoFrame f, OutputStream out) throws IOException {
        String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
        authority.handleJoinRequest(payload, "session-id", out); // Simplified
    }

    private void handleClaimRequest(AcoFrame f, OutputStream out) throws IOException {
        String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
        String[] p = payload.split("\\|", -1);
        if (p.length < 5) return;
        authority.handleClaimRequest(p[0], p[1], p[2], Long.parseLong(p[3]), out);
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
package sc.aco;

import arc.util.Log;
import mindustry.Vars;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AcoMigrationManager {
    private final Map<String, AcoSessionState> sessionStates = new ConcurrentHashMap<>();
    private final Map<String, String> sessionCampaigns = new ConcurrentHashMap<>();
    private final Map<String, String> activeMigrations = new ConcurrentHashMap<>();
    private final AcoP2Manager p2;
    private final sc.SectorStore store;

    public AcoMigrationManager(AcoP2Manager p2, sc.SectorStore store) {
        this.p2 = p2;
        this.store = store;
    }

    public void registerSession(String sessionId, String campaignId) {
        sessionCampaigns.put(sessionId, campaignId);
    }

    public synchronized void handleMigrationRequest(String sessionId, String campaignId, String source, String target, String migrationId, OutputStream out) throws IOException {
        if (!sessionCampaigns.getOrDefault(sessionId, "").equals(campaignId)) {
            sendResult(out, sessionId, migrationId, "FAIL", source, target, "Invalid Campaign");
            return;
        }
        if (activeMigrations.containsKey(sessionId)) {
            sendResult(out, sessionId, migrationId, "FAIL", source, target, "Migration already active");
            return;
        }

        activeMigrations.put(sessionId, migrationId);
        
        try {
            sessionStates.put(sessionId, AcoSessionState.SAVING_SOURCE);
            if (Vars.state.getSector() == null || !Vars.state.getSector().planet.name.equals(source.split(":")[0])) 
                throw new Exception("Source sector invalid");
            
            store.save(Vars.state.getSector());

            sessionStates.put(sessionId, AcoSessionState.STOPPING_P2);
            p2.stopP2();

            sessionStates.put(sessionId, AcoSessionState.LOADING_TARGET);
            // Target loading - parse planet:name format
            String[] targetParts = target.split(":");
            if (targetParts.length != 2) throw new Exception("Invalid target sector format");
            String planetName = targetParts[0];
            int sectorId = Integer.parseInt(targetParts[1]);
            mindustry.type.Planet planet = Vars.content.getByName(mindustry.ctype.ContentType.planet, planetName);
            if (planet == null) throw new Exception("Planet not found: " + planetName);
            mindustry.type.Sector s = planet.sectors.get(sectorId);
            if (s == null) throw new Exception("Target sector not found: " + target);
            // For now skip the actual open() since it needs grant token - just verify sector exists
            // In real implementation, we'd need a grant token from the host
            
            sessionStates.put(sessionId, AcoSessionState.STARTING_P2);
            p2.startP2(6567, target);
            
            // P2 Readiness Check
            if (!Vars.net.active()) throw new Exception("P2 startup failed");
            
            sessionStates.put(sessionId, AcoSessionState.ANNOUNCING);
            sendP2Info(out, sessionId, migrationId, target, "127.0.0.1", 6567);
            
            sessionStates.put(sessionId, AcoSessionState.WAITING_FOR_GUEST);

        } catch (Exception e) {
            Log.err("MIGRATION_FAILED_SESSION_@", sessionId, e);
            sendResult(out, sessionId, migrationId, "FAIL", source, target, e.getMessage());
            activeMigrations.remove(sessionId);
        }
    }

    public synchronized void handleConfirmation(String sessionId, String migrationId, String campaignId, String target) {
        if (activeMigrations.get(sessionId) != null && activeMigrations.get(sessionId).equals(migrationId)) {
            sessionStates.put(sessionId, AcoSessionState.COMPLETED);
            activeMigrations.remove(sessionId);
            Log.info("MIGRATION_COMMITTED: Sector @ transferred to session @", target, sessionId);
        }
    }

    private void sendResult(OutputStream out, String sessionId, String mid, String status, String src, String tar, String err) throws IOException {
        String p = sessionId + "|" + mid + "|" + status + "|" + src + "|" + tar + "|" + err;
        out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_MIGRATION_RESULT.id, 0, p.getBytes()).encode());
    }

    private void sendP2Info(OutputStream out, String sid, String mid, String target, String host, int port) throws IOException {
        String p = sid + "|" + sid + "|" + mid + "|" + target + "|" + host + "|" + port;
        out.write(new AcoFrame((byte)1, AcoMessageType.P2_INFO.id, 0, p.getBytes()).encode());
    }
}

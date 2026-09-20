package sc.aco;

import arc.util.Log;
import mindustry.Vars;
import java.io.IOException;
import java.io.OutputStream;

public class CampaignAuthority {
    private final SectorRegistry registry;

    private final CampaignPersistence persistence;

    public CampaignAuthority(SectorRegistry registry, CampaignPersistence persistence) {
        this.registry = registry;
        this.persistence = persistence;
    }

    private final SectorRuntimeAdapter adapter = new SectorRuntimeAdapter();

    public void handleClaimRequest(String sectorKey, String sessionId, String deviceId, long revision, OutputStream out) throws IOException {
        Log.info("CLAIM_REQUEST: Session @ for sector @ rev @", sessionId, sectorKey, revision);
        
        try {
            if (adapter.initializeSector(sectorKey, String.valueOf(revision))) {
                if (adapter.startP2(6567)) {
                    boolean success = registry.claimSector(sectorKey, sessionId, revision);
                    if (success) {
                        SectorOwnership own = registry.get(sectorKey);
                        String payload = sectorKey + "|" + sessionId + "|OK|" + own.ownershipVersion + "|" + own.revisionVersion;
                        out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_CLAIM_RESULT.id, 0, payload.getBytes()).encode());
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.err("CLAIM_RUNTIME_INIT_FAILED", e);
            adapter.stopP2();
        }

        String payload = sectorKey + "|" + sessionId + "|FAIL|Sector runtime init failed";
        out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_CLAIM_RESULT.id, 0, payload.getBytes()).encode());
    }

    public void handleReleaseRequest(String sectorKey, String sessionId, long ownershipVersion, OutputStream out) throws IOException {
        Log.info("RELEASE_REQUEST: Session @ for sector @ ver @", sessionId, sectorKey, ownershipVersion);
        
        SectorOwnership own = registry.get(sectorKey);
        if (own == null || !own.ownerSessionId.equals(sessionId) || own.ownershipVersion != ownershipVersion) {
            String payload = sectorKey + "|" + sessionId + "|FAIL|Invalid ownership version or not owner";
            out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_RELEASE.id, 0, payload.getBytes()).encode());
            return;
        }
        
        registry.releaseSector(sectorKey, sessionId);
        String payload = sectorKey + "|" + sessionId + "|OK|" + registry.get(sectorKey).ownershipVersion;
        out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_RELEASE.id, 0, payload.getBytes()).encode());
    }

    public void handleTransferRequest(String sectorKey, String fromSession, String toSession, long newRevision, OutputStream out) throws IOException {
        Log.info("TRANSFER_REQUEST: @ -> @ for sector @", fromSession, toSession, sectorKey);
        
        boolean success = registry.transferOwnership(sectorKey, fromSession, toSession, newRevision);
        
        if (success) {
            SectorOwnership own = registry.get(sectorKey);
            String payload = sectorKey + "|" + toSession + "|OK|" + own.ownershipVersion + "|" + own.revisionVersion;
            out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_CLAIM_RESULT.id, 0, payload.getBytes()).encode());
            
            // Broadcast to all: SECTOR_HOST_CHANGED
            broadcastHostChanged(sectorKey, own);
        } else {
            String payload = sectorKey + "|" + toSession + "|FAIL|Transfer denied: not owner or invalid sector";
            out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_CLAIM_RESULT.id, 0, payload.getBytes()).encode());
        }
    }

    private void broadcastHostChanged(String sectorKey, SectorOwnership own) {
        // In real implementation, iterate all sessions and send
        Log.info("HOST_CHANGED: Sector @ now owned by @ @", sectorKey, own.ownerSessionId, own.endpoint);
    }

    public SectorOwnership getOwnership(String sectorKey) {
        return registry.get(sectorKey);
    }

    public SectorRegistry getRegistry() {
        return registry;
    }

    public void handleJoinRequest(String sectorKey, String sessionId, OutputStream out) throws IOException {
        SectorOwnership own = registry.get(sectorKey);
        if (own == null || own.ownerSessionId == null) {
            out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_MIGRATION_RESULT.id, 0, "FAIL|Not Hosted".getBytes()).encode());
            return;
        }
        String payload = sectorKey + "|" + own.endpoint + "|" + own.ownershipVersion;
        out.write(new AcoFrame((byte)1, AcoMessageType.SECTOR_HOST_CHANGED.id, 0, payload.getBytes()).encode());
    }
}
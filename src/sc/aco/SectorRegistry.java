package sc.aco;

import arc.util.Log;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SectorRegistry {
    private final Map<String, SectorOwnership> registry = new ConcurrentHashMap<>();
    private final Map<String, String> sessionDevices = new ConcurrentHashMap<>(); // sessionId -> deviceId

    public SectorRegistry() {}

    public SectorOwnership get(String sectorKey) {
        return registry.get(sectorKey);
    }

    public boolean claimSector(String sectorKey, String sessionId, long expectedRevision) {
        SectorOwnership existing = registry.get(sectorKey);
        if (existing == null || existing.ownerSessionId == null) {
            SectorOwnership newOwnership = new SectorOwnership(sectorKey);
            newOwnership.ownerSessionId = sessionId;
            newOwnership.ownershipVersion = 1;
            newOwnership.revisionVersion = expectedRevision;
            newOwnership.lastHeartbeat = System.currentTimeMillis();
            registry.put(sectorKey, newOwnership);
            return true;
        }
        return false;
    }

    public boolean transferOwnership(String sectorKey, String fromSession, String toSession, long newRevision) {
        SectorOwnership own = registry.get(sectorKey);
        if (own == null || !own.ownerSessionId.equals(fromSession)) return false;
        
        own.ownerSessionId = toSession;
        own.ownershipVersion++;
        own.revisionVersion = newRevision;
        own.lastHeartbeat = System.currentTimeMillis();
        return true;
    }

    public void registerHeartbeat(String sectorKey, String sessionId) {
        SectorOwnership own = registry.get(sectorKey);
        if (own != null && own.ownerSessionId.equals(sessionId)) {
            own.lastHeartbeat = System.currentTimeMillis();
        }
    }

    public boolean releaseSector(String sectorKey, String sessionId) {
        SectorOwnership own = registry.get(sectorKey);
        if (own != null && own.ownerSessionId.equals(sessionId)) {
            own.ownerSessionId = null;
            own.endpoint = null;
            own.ownershipVersion++;
            return true;
        }
        return false;
    }

    public void updateEndpoint(String sectorKey, String sessionId, String endpoint) {
        SectorOwnership own = registry.get(sectorKey);
        if (own != null && own.ownerSessionId.equals(sessionId)) {
            own.endpoint = endpoint;
        }
    }
}
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
        SectorOwnership newOwnership = new SectorOwnership(sectorKey);
        newOwnership.ownerSessionId = sessionId;
        newOwnership.ownershipVersion = 1;
        newOwnership.revisionVersion = expectedRevision;
        newOwnership.lastHeartbeat = System.currentTimeMillis();
        
        return registry.compute(sectorKey, (k, existing) -> {
            if (existing == null || existing.ownerSessionId == null) {
                return newOwnership;
            }
            return existing;
        }) == newOwnership;
    }

    public boolean transferOwnership(String sectorKey, String fromSession, String toSession, long newRevision) {
        return registry.computeIfPresent(sectorKey, (k, own) -> {
            if (own.ownerSessionId != null && own.ownerSessionId.equals(fromSession)) {
                own.ownerSessionId = toSession;
                own.ownershipVersion++;
                own.revisionVersion = newRevision;
                own.lastHeartbeat = System.currentTimeMillis();
            }
            return own;
        }) != null && registry.get(sectorKey).ownerSessionId.equals(toSession);
    }

    public void registerHeartbeat(String sectorKey, String sessionId) {
        registry.computeIfPresent(sectorKey, (k, own) -> {
            if (own.ownerSessionId != null && own.ownerSessionId.equals(sessionId)) {
                own.lastHeartbeat = System.currentTimeMillis();
            }
            return own;
        });
    }

    public boolean releaseSector(String sectorKey, String sessionId) {
        return registry.computeIfPresent(sectorKey, (k, own) -> {
            if (own.ownerSessionId != null && own.ownerSessionId.equals(sessionId)) {
                own.ownerSessionId = null;
                own.endpoint = null;
                own.ownershipVersion++;
            }
            return own;
        }) != null && registry.get(sectorKey).ownerSessionId == null;
    }

    public void updateEndpoint(String sectorKey, String sessionId, String endpoint) {
        registry.computeIfPresent(sectorKey, (k, own) -> {
            if (own.ownerSessionId != null && own.ownerSessionId.equals(sessionId)) {
                own.endpoint = endpoint;
            }
            return own;
        });
    }
}
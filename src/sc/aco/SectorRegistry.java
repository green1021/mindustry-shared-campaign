package sc.aco;

import arc.util.Log;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class SectorRegistry {
    private final Map<String, SectorOwnership> registry = new ConcurrentHashMap<>();

    public SectorRegistry() {}

    public SectorOwnership get(String sectorKey) {
        return registry.get(sectorKey);
    }

    public boolean claimSector(String sectorKey, String sessionId, long expectedRevision) {
        if (sectorKey == null || sessionId == null) return false;
        
        AtomicBoolean claimed = new AtomicBoolean(false);
        registry.compute(sectorKey, (k, existing) -> {
            if (existing == null || existing.ownerSessionId == null) {
                SectorOwnership newOwnership = new SectorOwnership(sectorKey);
                newOwnership.ownerSessionId = sessionId;
                newOwnership.ownershipVersion = 1;
                newOwnership.revisionVersion = expectedRevision;
                newOwnership.lastHeartbeat = System.currentTimeMillis();
                claimed.set(true);
                return newOwnership;
            }
            return existing;
        });
        return claimed.get();
    }

    public boolean transferOwnership(String sectorKey, String fromSession, String toSession, long newRevision) {
        if (sectorKey == null || fromSession == null || toSession == null) return false;
        
        AtomicBoolean transferred = new AtomicBoolean(false);
        registry.computeIfPresent(sectorKey, (k, own) -> {
            if (own.ownerSessionId != null && own.ownerSessionId.equals(fromSession)) {
                own.ownerSessionId = toSession;
                own.ownershipVersion++;
                own.revisionVersion = newRevision;
                own.lastHeartbeat = System.currentTimeMillis();
                transferred.set(true);
            }
            return own;
        });
        return transferred.get();
    }

    public boolean releaseSector(String sectorKey, String sessionId) {
        if (sectorKey == null || sessionId == null) return false;
        
        AtomicBoolean released = new AtomicBoolean(false);
        registry.computeIfPresent(sectorKey, (k, own) -> {
            if (own.ownerSessionId != null && own.ownerSessionId.equals(sessionId)) {
                own.ownerSessionId = null;
                own.endpoint = null;
                own.ownershipVersion++;
                released.set(true);
            }
            return own;
        });
        return released.get();
    }

    public void registerHeartbeat(String sectorKey, String sessionId) {
        if (sectorKey == null || sessionId == null) return;
        registry.computeIfPresent(sectorKey, (k, own) -> {
            if (own.ownerSessionId != null && own.ownerSessionId.equals(sessionId)) {
                own.lastHeartbeat = System.currentTimeMillis();
            }
            return own;
        });
    }

    public void updateEndpoint(String sectorKey, String sessionId, String endpoint) {
        if (sectorKey == null || sessionId == null) return;
        registry.computeIfPresent(sectorKey, (k, own) -> {
            if (own.ownerSessionId != null && own.ownerSessionId.equals(sessionId)) {
                own.endpoint = endpoint;
            }
            return own;
        });
    }
}

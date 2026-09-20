package sc.aco;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalCampaignState {
    public String campaignId;
    public long globalRevision = 0;
    public Set<String> unlockedSectors = ConcurrentHashMap.newKeySet();
    public Set<String> completedSectors = ConcurrentHashMap.newKeySet();
    public Map<String, Boolean> techTree = new ConcurrentHashMap<>();
    public Map<String, Long> globalBank = new ConcurrentHashMap<>();
    
    // Idempotency state
    public Map<String, Object> processedOperations = new ConcurrentHashMap<>();
    
    public void markSectorCompleted(String sectorId) {
        completedSectors.add(sectorId);
        // Deterministic rule: Complete Ground Zero unlocks Craters
        if (sectorId.equals("groundZero")) {
            unlockedSectors.add("craters");
        }
    }
}
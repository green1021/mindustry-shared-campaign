package sc.aco;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalCampaignState {
    /** The campaign's starting sector, available before any completion. */
    public static final String STARTING_SECTOR = "groundZero";

    public String campaignId;
    public long globalRevision = 0;
    public Set<String> unlockedSectors = ConcurrentHashMap.newKeySet();
    public Set<String> completedSectors = ConcurrentHashMap.newKeySet();
    public Map<String, Boolean> techTree = new ConcurrentHashMap<>();
    public Map<String, Long> globalBank = new ConcurrentHashMap<>();

    // Idempotency state
    public Map<String, Object> processedOperations = new ConcurrentHashMap<>();

    /**
     * Seed a fresh campaign's initial unlock set.
     *
     * <p>The authority rejects a completion for any sector not in
     * {@code unlockedSectors} (see {@code AcoServer.handleSectorCompletion}), so a
     * campaign with an empty unlock set can never complete anything — not even its
     * first sector. This grants the starting sector so the campaign is playable at
     * all. Callers invoke it only for a brand-new campaign, never to re-grant after
     * progression.
     */
    public void unlockStartingSectors() {
        unlockedSectors.add(STARTING_SECTOR);
    }

    public void markSectorCompleted(String sectorId) {
        completedSectors.add(sectorId);
        // Deterministic rule: Complete Ground Zero unlocks Craters
        if (sectorId.equals("groundZero")) {
            unlockedSectors.add("craters");
        }
    }
}
package sc.aco;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class GlobalCampaignState {
    /** The campaign's starting sector, available before any completion. */
    public static final String STARTING_SECTOR = "groundZero";

    /**
     * Upper bound on the idempotency window. Every recorded operation id is also
     * written into campaign.json, so an unbounded map made both memory use and the
     * per-save snapshot grow without limit. The window only has to cover realistic
     * retry horizons: a duplicate older than this is re-evaluated instead of being
     * replayed from the record. The WAL is compacted against the durable snapshot
     * revision (see {@link CampaignPersistence}), so evicting from this window can
     * never resurrect an already-applied WAL record.
     */
    public static final int MAX_PROCESSED_OPERATIONS = 1024;

    public String campaignId;
    /** Volatile: read by connection threads while progression writes it under a lock. */
    public volatile long globalRevision = 0;
    public Set<String> unlockedSectors = ConcurrentHashMap.newKeySet();
    public Set<String> completedSectors = ConcurrentHashMap.newKeySet();
    public Map<String, Boolean> techTree = new ConcurrentHashMap<>();
    public Map<String, Long> globalBank = new ConcurrentHashMap<>();

    // Idempotency state
    public Map<String, Object> processedOperations = new ConcurrentHashMap<>();
    /** Insertion order of {@link #processedOperations} keys, used only for eviction. */
    private final ConcurrentLinkedDeque<String> processedOrder = new ConcurrentLinkedDeque<>();

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

    /**
     * Record an idempotency marker, evicting the oldest entry once the window is
     * full. The map itself is concurrent so readers need no lock; eviction is
     * serialized on this state's monitor to keep the order deque consistent.
     */
    public synchronized void recordProcessed(String operationId, Object value) {
        if (operationId == null) return;
        // Re-recording an id must move it to the tail, never append a second copy.
        // Keeping the original position would let the next eviction drop a marker
        // that was just refreshed; appending a duplicate would let a later eviction
        // delete the live entry through the stale reference. remove+addLast keeps
        // exactly one reference per id, ordered by last use.
        processedOrder.remove(operationId);
        processedOrder.addLast(operationId);
        processedOperations.put(operationId, value);
        while (processedOperations.size() > MAX_PROCESSED_OPERATIONS) {
            String oldest = processedOrder.pollFirst();
            if (oldest == null) break;
            processedOperations.remove(oldest);
        }
    }

    /**
     * The idempotency window in last-use order, oldest first. Used by the snapshot
     * writer so a state that was built through {@link #recordProcessed} serializes
     * deterministically instead of in hash order.
     */
    public synchronized java.util.List<String> processedOrderSnapshot() {
        return java.util.List.copyOf(processedOrder);
    }

    /**
     * Undo a {@link #recordProcessed} after a failed persist, keeping the order
     * deque in step with the map. Dropping only the map entry would leave an id in
     * the deque that no longer names a marker, so a later eviction would silently
     * remove an unrelated live entry.
     */
    public synchronized void forgetProcessed(String operationId) {
        if (operationId == null) return;
        processedOperations.remove(operationId);
        processedOrder.remove(operationId);
    }

    /**
     * Mark a sector completed and keep it unlocked for re-entry. Unlocking the
     * campaign's next sectors is data-driven: {@code AcoServer} applies the
     * neighbour keys reported by the capturing client. The previous hardcoded
     * {@code "craters"} rule named a sector that does not exist in the game
     * ({@code SectorPresets} has no such field), so completing Ground Zero could
     * never unlock a real sector.
     */
    public void markSectorCompleted(String sectorId) {
        completedSectors.add(sectorId);
        unlockedSectors.add(sectorId);
    }
}

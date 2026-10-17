package sc.aco;

import arc.util.Log;
import java.io.IOException;

public class AcoClient {
    private GlobalCampaignState localCache = new GlobalCampaignState();

    /**
     * Apply an unsolicited {@code GLOBAL_STATE_SYNC} broadcast to the local view.
     *
     * <p>The payload is the campaign snapshot serialized by
     * {@link CampaignPersistence#toJson}, the same codec the authority persists
     * to disk. This method is the client half of that contract: it parses the
     * payload and folds it into the cache via
     * {@link #updateLocalCache(GlobalCampaignState)}. Previously it logged the
     * frame and dropped the payload, so the local view (and therefore any
     * sector host reading global state) never advanced on a broadcast.
     *
     * <p>A malformed payload is logged and ignored rather than thrown: a bad
     * broadcast must not tear down the connection.
     */
    public void handleGlobalStateSync(AcoFrame f) {
        String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
        GlobalCampaignState incoming;
        try {
            incoming = CampaignPersistence.fromJson(payload);
        } catch (RuntimeException e) {
            Log.err("ACO_CLIENT_SYNC_PARSE_FAILED", e);
            return;
        }
        boolean applied = updateLocalCache(incoming);
        Log.debug("CLIENT: sync applied=@ revision=@", applied, incoming.globalRevision);
    }

    /**
     * Fold a received state into the local cache.
     *
     * <p>Guarded by revision: a broadcast older than or equal to what we already
     * hold is ignored, so a reordered or replayed sync cannot roll the view back.
     *
     * @return true when the cache advanced, false when the state was stale
     */
    public synchronized boolean updateLocalCache(GlobalCampaignState newState) {
        if (newState != null && newState.globalRevision > localCache.globalRevision) {
            this.localCache = newState;
            return true;
        }
        return false;
    }

    /** The most recent campaign state folded in from a sync broadcast. */
    public synchronized GlobalCampaignState getLocalCache() {
        return localCache;
    }

    protected void sendFrame(AcoFrame f) throws IOException {
        // Implementation by integration wrapper
    }

    public void sendGlobalResourceRequest(String item, long amount, String opId, String opType, long expectedRevision, String sessionId, String sectorId) {
        try {
            // protocol: operationId|sessionId|sectorId|expectedRevision|resourceType|amount|operationType
            String payload = opId + "|" + sessionId + "|" + sectorId + "|" + expectedRevision + "|" + item + "|" + amount + "|" + opType;
            AcoFrame frame = new AcoFrame((byte)1, AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, 0,
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sendFrame(frame);
        } catch (Exception e) {
            Log.err(e);
        }
    }

    /** Legacy 4-field request kept for basic GameplayAdapter compatibility until full migration. */
    public void sendGlobalResourceRequest(String item, long amount, String opId, String opType) {
        sendGlobalResourceRequest(item, amount, opId, opType, 0, "unknown", "unknown");
    }

    public void sendResearchRequest(String tech) {
        try {
            AcoFrame frame = new AcoFrame((byte)1, AcoMessageType.RESEARCH_REQUEST.id, 0,
                (tech + "|").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sendFrame(frame);
        } catch (Exception e) {
            Log.err(e);
        }
    }

    public void sendSectorCompletion(String sector) {
        try {
            AcoFrame frame = new AcoFrame((byte)1, AcoMessageType.SECTOR_COMPLETION_EVENT.id, 0,
                sector.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sendFrame(frame);
        } catch (Exception e) {
            Log.err(e);
        }
    }

    /**
     * Report a completion carrying an explicit operation id.
     *
     * <p>The authority dedupes completions by {@code operationId}, treating a repeat
     * as the same completion. The bare {@link #sendSectorCompletion(String)} sends no
     * id, so the authority falls back to a single shared {@code "default-op"} — with
     * that, the <em>second</em> distinct sector completion would be swallowed as a
     * duplicate of the first. Gameplay-originated completions therefore use this
     * overload and pass an id unique per (sector, attempt).
     */
    public void sendSectorCompletion(String sector, String opId) {
        try {
            String payload = sector + "|" + opId;
            AcoFrame frame = new AcoFrame((byte)1, AcoMessageType.SECTOR_COMPLETION_EVENT.id, 0,
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sendFrame(frame);
        } catch (Exception e) {
            Log.err(e);
        }
    }
}

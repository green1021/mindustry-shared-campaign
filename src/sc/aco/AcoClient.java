package sc.aco;

import arc.util.Log;
import java.io.IOException;

public class AcoClient {
    private GlobalCampaignState localCache = new GlobalCampaignState();

    public void handleGlobalStateSync(AcoFrame f) {
        String payload = new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8);
        Log.info("CLIENT: Received sync");
    }

    public synchronized void updateLocalCache(GlobalCampaignState newState) {
        if (newState.globalRevision > localCache.globalRevision) {
            this.localCache = newState;
        }
    }

    protected void sendFrame(AcoFrame f) throws IOException {
        // Implementation by integration wrapper
    }

    public void sendGlobalResourceRequest(String item, long amount, String opId, String opType) {
        try {
            // protocol: item|amount|opId|opType
            String payload = item + "|" + amount + "|" + opId + "|" + opType;
            AcoFrame frame = new AcoFrame((byte)1, AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, 0,
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sendFrame(frame);
        } catch (Exception e) {
            Log.err(e);
        }
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
}

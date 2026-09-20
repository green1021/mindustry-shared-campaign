package sc.aco;

import java.nio.file.*;
import java.util.*;

public class PersistenceTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("test_data");
        Files.createDirectories(root);
        CampaignPersistence p = new CampaignPersistence(root);
        
        GlobalCampaignState state = new GlobalCampaignState();
        state.campaignId = "test";
        state.globalRevision = 1;
        state.globalBank.put("copper", 100L);
        state.processedOperations.put("op1", new TransactionResult("op1", true, "", 1, 100));
        
        // Save
        p.save(state);
        
        // Recover
        GlobalCampaignState loaded = p.recover();
        
        if (!loaded.campaignId.equals("test") || loaded.globalRevision != 1 || loaded.globalBank.get("copper") != 100L) {
            throw new RuntimeException("Round-trip failed");
        }
        
        System.out.println("Round-trip OK");
    }
}
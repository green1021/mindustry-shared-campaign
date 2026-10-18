package tests;

import sc.aco.CampaignSectorStore;
import sc.aco.GameSectorHostOps;
import java.nio.file.Files;
import java.nio.file.Path;

public class HostSnapshotTest {
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("host-snap-test");
        CampaignSectorStore store = new CampaignSectorStore(tmp, "test", CampaignSectorStore.STRUCTURAL_ONLY);
        GameSectorHostOps ops = new GameSectorHostOps(store, null, tmp.resolve("scratch"), () -> 0, () -> null);
        
        // Assert: no snapshot exists
        boolean success = ops.loadSnapshot("nonexistent");
        if (success) {
            throw new RuntimeException("Should fail to load missing snapshot");
        }
        System.out.println("[PASS] testLoadSnapshotFailsWhenMissing");
    }
}

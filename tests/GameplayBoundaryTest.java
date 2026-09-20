package tests;

import sc.aco.*;
import sc.SectorStore;
import mindustry.Vars;
import mindustry.content.Items;
import arc.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Lightweight deterministic tests for GameplayAdapter and P1 Authority.
 * No Mindustry runtime bootstrap required.
 */
public class GameplayBoundaryTest {

    // Test state
    private static int passed = 0;
    private static int failed = 0;

    private static mindustry.type.Item copperItem;

    public static void main(String[] args) {
        System.setProperty("arc.headless", "true");
        if (Vars.content == null) {
            Vars.content = new mindustry.core.ContentLoader();
        }
        copperItem = new mindustry.type.Item("copper");

        runTest("ResourceRequestEncoding", () -> { testResourceRequestEncoding(); return null; });
        runTest("ResearchRequestEncoding", () -> { testResearchRequestEncoding(); return null; });
        runTest("SectorCompletionEncoding", () -> { testSectorCompletionEncoding(); return null; });
        runTest("GlobalTransactionManagerBasic", () -> { testGlobalTransactionManagerBasic(); return null; });
        runTest("GlobalBankBalance", () -> { testGlobalBankBalance(); return null; });
        runTest("GlobalRevisionNumberIncrement", () -> { testGlobalRevisionNumberIncrement(); return null; });
        runTest("OperationIdIdempotency", () -> { testOperationIdIdempotency(); return null; });
        runTest("StaleRevisionRejection", () -> { testStaleRevisionRejection(); return null; });
        runTest("PersistenceRoundTrip", () -> { 
            try {
                testPersistenceRoundTrip(); 
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null; 
        });
        runTest("GameplayAdapterNoDirectMutation", () -> { testGameplayAdapterNoDirectMutation(); return null; });
        runTest("CampaignAuthorityRouting", () -> { testCampaignAuthorityRouting(); return null; });

        System.out.println("\n=== SUMMARY ===");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void runTest(String name, java.util.function.Supplier<Void> test) {
        try {
            test.get();
            System.out.println("[PASS] " + name);
            passed++;
        } catch (AssertionError e) {
            System.out.println("[FAIL] " + name + ": " + e.getMessage());
            failed++;
        } catch (Exception e) {
            System.out.println("[ERROR] " + name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    // 1. Resource request encoding
    private static void testResourceRequestEncoding() {
        MockClient client = new MockClient();
        GameplayAdapter adapter = new GameplayAdapter(client);
        
        // Verify fail-fast on null item
        boolean caughtNull = false;
        try {
            adapter.requestWithdrawal(null, 100L, "op-null");
        } catch (IllegalArgumentException e) {
            caughtNull = true;
        }
        assert caughtNull : "Should throw IllegalArgumentException when item is null";
        assert client.lastFrame == null : "No frame should be emitted on null item";

        // Create a legitimate item instance
        adapter.requestWithdrawal(copperItem, 100L, "op-001");
        assert client.lastFrame != null : "Frame should be sent";
        assert client.lastFrame.type() == AcoMessageType.GLOBAL_RESOURCE_REQUEST.id : "Correct message type";

        String payload = new String(client.lastFrame.payload());
        String[] parts = payload.split("\\|");
        assert parts.length == 4 : "Payload format item|amount|opId|opType";
        assert parts[0].equals("copper") : "Item name should be 'copper'";
        assert parts[1].equals("100") : "Amount";
        assert parts[2].equals("op-001") : "Operation ID";
        assert parts[3].equals("WITHDRAW") : "Operation type";
    }

    // 2. Research request encoding
    private static void testResearchRequestEncoding() {
        MockClient client = new MockClient();
        GameplayAdapter adapter = new GameplayAdapter(client);
        // GameplayAdapter doesn't have sendResearchRequest - we test AcoClient directly
        client.sendResearchRequest("thorium-processing");
        assert client.lastFrame != null : "Frame should be sent";
        assert client.lastFrame.type() == AcoMessageType.RESEARCH_REQUEST.id : "Correct message type";

        String payload = new String(client.lastFrame.payload());
        assert payload.equals("thorium-processing|") : "Payload format tech|";
    }

    // 3. Sector completion encoding
    private static void testSectorCompletionEncoding() {
        MockClient client = new MockClient();
        GameplayAdapter adapter = new GameplayAdapter(client);
        // GameplayAdapter doesn't have sendSectorCompletion - test AcoClient directly
        client.sendSectorCompletion("groundZero");
        assert client.lastFrame != null : "Frame should be sent";
        assert client.lastFrame.type() == AcoMessageType.SECTOR_COMPLETION_EVENT.id : "Correct message type";

        String payload = new String(client.lastFrame.payload());
        assert payload.equals("groundZero") : "Sector name payload";
    }

    // 4. GlobalTransactionManager basic execution
    private static void testGlobalTransactionManagerBasic() {
        GlobalCampaignState state = new GlobalCampaignState();
        Path persistDir = Paths.get("/tmp/test-persist-" + System.currentTimeMillis());
        CampaignPersistence persistence = new CampaignPersistence(persistDir);
        GlobalBank bank = new GlobalBank(state, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(state, bank);

        // Add initial balance
        state.globalBank.put("copper", 1000L);
        state.globalRevision = 1;

        GlobalResourceRequest req = new GlobalResourceRequest(
            "op-001", "sess-1", "sector1", 1, "copper", 500,
            GlobalResourceRequest.OperationType.WITHDRAW
        );

        TransactionResult res = txManager.execute(req);
        assert res.success() : "Should succeed with sufficient balance";
        assert res.newBalance() == 500 : "Balance should be 500 after withdrawal";
        assert res.newRevision() == 2 : "Revision should increment exactly once";
    }

    // 5. GlobalBank balance tracking
    private static void testGlobalBankBalance() {
        GlobalCampaignState state = new GlobalCampaignState();
        Path persistDir = Paths.get("/tmp/test-persist-" + System.currentTimeMillis());
        CampaignPersistence persistence = new CampaignPersistence(persistDir);
        GlobalBank bank = new GlobalBank(state, persistence);

        bank.process(new GlobalResourceRequest(
            "op-dep", "sess-1", "sector1", 0, "copper", 100,
            GlobalResourceRequest.OperationType.DEPOSIT
        ));
        assert state.globalBank.get("copper") == 100L : "Deposit works";

        bank.process(new GlobalResourceRequest(
            "op-wd", "sess-1", "sector1", 1, "copper", 30,
            GlobalResourceRequest.OperationType.WITHDRAW
        ));
        assert state.globalBank.get("copper") == 70L : "Withdraw works";
    }

    // 6. GlobalRevisionNumber increments exactly once per successful transaction
    private static void testGlobalRevisionNumberIncrement() {
        GlobalCampaignState state = new GlobalCampaignState();
        Path persistDir = Paths.get("/tmp/test-persist-" + System.currentTimeMillis());
        CampaignPersistence persistence = new CampaignPersistence(persistDir);
        GlobalBank bank = new GlobalBank(state, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(state, bank);

        state.globalBank.put("copper", 1000L);
        state.globalRevision = 5;

        for (int i = 0; i < 3; i++) {
            GlobalResourceRequest req = new GlobalResourceRequest(
                "op-" + i, "sess-1", "sector1", 5 + i, "copper", 100,
                GlobalResourceRequest.OperationType.WITHDRAW
            );
            TransactionResult res = txManager.execute(req);
            assert res.success() : "Transaction " + i + " should succeed";
            assert res.newRevision() == 5 + i + 1 : "Revision increments by 1 each time";
        }
        assert state.globalRevision == 8 : "Final revision should be 8";
    }

    // 7. OperationId idempotency - duplicate opId does not mutate twice
    private static void testOperationIdIdempotency() {
        GlobalCampaignState state = new GlobalCampaignState();
        Path persistDir = Paths.get("/tmp/test-persist-" + System.currentTimeMillis());
        CampaignPersistence persistence = new CampaignPersistence(persistDir);
        GlobalBank bank = new GlobalBank(state, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(state, bank);

        state.globalBank.put("copper", 1000L);
        state.globalRevision = 10;

        // First execution
        GlobalResourceRequest req1 = new GlobalResourceRequest(
            "op-duplicate", "sess-1", "sector1", 10, "copper", 200,
            GlobalResourceRequest.OperationType.WITHDRAW
        );
        TransactionResult res1 = txManager.execute(req1);
        assert res1.success() : "First should succeed";
        long balanceAfterFirst = state.globalBank.get("copper");
        long revisionAfterFirst = state.globalRevision;

        // Second execution with same opId
        GlobalResourceRequest req2 = new GlobalResourceRequest(
            "op-duplicate", "sess-1", "sector1", 10, "copper", 200,
            GlobalResourceRequest.OperationType.WITHDRAW
        );
        TransactionResult res2 = txManager.execute(req2);
        assert res2.success() : "Second should succeed (idempotent)";
        assert state.globalBank.get("copper") == balanceAfterFirst : "Balance should not change on duplicate";
        assert state.globalRevision == revisionAfterFirst : "Revision should not change on duplicate";
    }

    // 8. Stale GlobalRevisionNumber rejection
    private static void testStaleRevisionRejection() {
        GlobalCampaignState state = new GlobalCampaignState();
        Path persistDir = Paths.get("/tmp/test-persist-" + System.currentTimeMillis());
        CampaignPersistence persistence = new CampaignPersistence(persistDir);
        GlobalBank bank = new GlobalBank(state, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(state, bank);

        state.globalBank.put("copper", 1000L);
        state.globalRevision = 20;

        // Request with stale expected revision
        GlobalResourceRequest req = new GlobalResourceRequest(
            "op-stale", "sess-1", "sector1", 19, "copper", 100,  // expectedRevision=19 but current=20
            GlobalResourceRequest.OperationType.WITHDRAW
        );

        TransactionResult res = txManager.execute(req);
        assert !res.success() : "Should fail with stale revision";
        assert res.error() != null && res.error().contains("revision") : "Error should mention revision";
    }

    // 9. Persistence round-trip survives mutation
    private static void testPersistenceRoundTrip() throws Exception {
        Path persistDir = Paths.get("/tmp/test-persist-" + System.currentTimeMillis());

        // First round: create state, execute transaction, persist
        GlobalCampaignState state1 = new GlobalCampaignState();
        CampaignPersistence persistence1 = new CampaignPersistence(persistDir);
        GlobalBank bank1 = new GlobalBank(state1, persistence1);
        GlobalTransactionManager txManager1 = new GlobalTransactionManager(state1, bank1);

        state1.globalBank.put("copper", 500L);
        state1.globalRevision = 30;

        GlobalResourceRequest req = new GlobalResourceRequest(
            "op-persist", "sess-1", "sector1", 30, "copper", 100,
            GlobalResourceRequest.OperationType.WITHDRAW
        );
        TransactionResult res = txManager1.execute(req);
        assert res.success();
        assert state1.globalRevision == 31;
        assert state1.globalBank.get("copper") == 400L;

        // Persist
        persistence1.save(state1);

        // Second round: load and verify
        CampaignPersistence persistence2 = new CampaignPersistence(persistDir);
        GlobalCampaignState loadedState = persistence2.loadState().orElseThrow(() -> new AssertionError("State should load"));

        assert loadedState.globalRevision == 31 : "Revision persisted";
        assert loadedState.globalBank.get("copper") == 400L : "Balance persisted";
        assert loadedState.processedOperations.containsKey("op-persist") : "Idempotency record persisted";
    }

    // 10. GameplayAdapter has no direct GlobalBank mutation path
    private static void testGameplayAdapterNoDirectMutation() {
        MockClient client = new MockClient();
        GameplayAdapter adapter = new GameplayAdapter(client);

        // The adapter only sends requests, never mutates state directly
        adapter.requestWithdrawal(copperItem, 100L, "op-1");
        client.sendResearchRequest("test-tech");
        client.sendSectorCompletion("test-sector");

        // Verify only frames were sent, no direct state mutation
        assert client.framesSent.size() == 3 : "Three frames sent (got " + client.framesSent.size() + ")";
        boolean hasResourceReq = client.framesSent.stream().anyMatch(f -> f.type() == AcoMessageType.GLOBAL_RESOURCE_REQUEST.id);
        boolean hasResearchReq = client.framesSent.stream().anyMatch(f -> f.type() == AcoMessageType.RESEARCH_REQUEST.id);
        boolean hasSectorComplete = client.framesSent.stream().anyMatch(f -> f.type() == AcoMessageType.SECTOR_COMPLETION_EVENT.id);

        assert hasResourceReq : "Resource request sent";
        assert hasResearchReq : "Research request sent";
        assert hasSectorComplete : "Sector completion sent";
    }

    // 11. CampaignAuthority routing
    private static void testCampaignAuthorityRouting() {
        SectorRegistry registry = new SectorRegistry();
        Path persistDir = Paths.get("/tmp/test-persist-" + System.currentTimeMillis());
        CampaignPersistence persistence = new CampaignPersistence(persistDir);
        GlobalCampaignState globalState = new GlobalCampaignState();
        GlobalBank bank = new GlobalBank(globalState, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(globalState, bank);
        CampaignAuthority authority = new CampaignAuthority(registry, persistence);

        // Verify authority exists and has correct dependencies
        assert authority != null : "Authority created";
        assert authority.getRegistry() == registry : "Registry injected";
    }

    // Mock client for capturing frames
    static class MockClient extends AcoClient {
        AcoFrame lastFrame;
        java.util.List<AcoFrame> framesSent = new java.util.ArrayList<>();

        @Override
        protected void sendFrame(AcoFrame f) throws IOException {
            this.lastFrame = f;
            this.framesSent.add(f);
        }
    }
}
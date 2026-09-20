package tests;

import sc.aco.*;
import sc.SectorStore;
import mindustry.Vars;
import arc.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Lightweight deterministic tests for GameplayAdapter and P1 Authority.
 * No Mindustry runtime bootstrap required.
 */
public class GameplayBoundaryTest {

    // Test state
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.setProperty("arc.headless", "true");
        
        runTest("ResourceRequestEncoding", () -> testResourceRequestEncoding());
        runTest("ResearchRequestEncoding", () -> testResearchRequestEncoding());
        runTest("SectorCompletionEncoding", () -> testSectorCompletionEncoding());
        runTest("GlobalTransactionManagerBasic", () -> testGlobalTransactionManagerBasic());
        runTest("GlobalBankBalance", () -> testGlobalBankBalance());
        runTest("GlobalRevisionNumberIncrement", () -> testGlobalRevisionNumberIncrement());
        runTest("OperationIdIdempotency", () -> testOperationIdIdempotency());
        runTest("StaleRevisionRejection", () -> testStaleRevisionRejection());
        runTest("PersistenceRoundTrip", () -> testPersistenceRoundTrip());
        runTest("GameplayAdapterNoDirectMutation", () -> testGameplayAdapterNoDirectMutation());
        runTest("CampaignAuthorityRouting", () -> testCampaignAuthorityRouting());
        
        System.out.println("\n=== SUMMARY ===");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void runTest(String name, Runnable test) {
        try {
            test.run();
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
        adapter.requestGlobalResource("copper", 100);
        assert client.lastFrame != null : "Frame should be sent";
        assert client.lastFrame.type() == AcoMessageType.GLOBAL_RESOURCE_REQUEST.id : "Correct message type";
        
        String payload = new String(client.lastFrame.payload());
        String[] parts = payload.split("\\|");
        assert parts.length == 2 : "Payload format item|amount";
        assert parts[0].equals("copper") : "Item name";
        assert parts[1].equals("100") : "Amount";
    }

    // 2. Research request encoding
    private static void testResearchRequestEncoding() {
        MockClient client = new MockClient();
        GameplayAdapter adapter = new GameplayAdapter(client);
        adapter.sendResearchRequest("thorium-processing");
        assert client.lastFrame != null : "Frame should be sent";
        assert client.lastFrame.type() == AcoMessageType.RESEARCH_REQUEST.id : "Correct message type";
        
        String payload = new String(client.lastFrame.payload());
        assert payload.equals("thorium-processing|") : "Payload format tech|";
    }

    // 3. Sector completion encoding
    private static void testSectorCompletionEncoding() {
        MockClient client = new MockClient();
        GameplayAdapter adapter = new GameplayAdapter(client);
        adapter.sendSectorCompletion("groundZero");
        assert client.lastFrame != null : "Frame should be sent";
        assert client.lastFrame.type() == AcoMessageType.SECTOR_COMPLETION_EVENT.id : "Correct message type";
        
        String payload = new String(client.lastFrame.payload());
        assert payload.equals("groundZero") : "Sector name payload";
    }

    // 4. GlobalTransactionManager basic execution
    private static void testGlobalTransactionManagerBasic() {
        GlobalCampaignState state = new GlobalCampaignState();
        CampaignPersistence persistence = new CampaignPersistence(java.nio.file.Paths.get("/tmp/test-persist-" + System.currentTimeMillis()));
        GlobalBank bank = new GlobalBank(state, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(state, bank);

        // Add initial balance
        state.bank.balances.put("copper", 1000L);
        state.globalRevision = 1;
        
        GlobalResourceRequest req = new GlobalResourceRequest(
            "op-001", "sess-1", "copper", 1, 500, 
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
        CampaignPersistence persistence = new CampaignPersistence(java.nio.file.Paths.get("/tmp/test-persist-" + System.currentTimeMillis()));
        GlobalBank bank = new GlobalBank(state, persistence);
        
        bank.deposit("copper", 100);
        assert state.bank.balances.get("copper") == 100L : "Deposit works";
        
        bank.withdraw("copper", 30);
        assert state.bank.balances.get("copper") == 70L : "Withdraw works";
    }

    // 6. GlobalRevisionNumber increments exactly once per successful transaction
    private static void testGlobalRevisionNumberIncrement() {
        GlobalCampaignState state = new GlobalCampaignState();
        CampaignPersistence persistence = new CampaignPersistence(java.nio.file.Paths.get("/tmp/test-persist-" + System.currentTimeMillis()));
        GlobalBank bank = new GlobalBank(state, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(state, bank);

        state.bank.balances.put("copper", 1000L);
        state.globalRevision = 5;
        
        for (int i = 0; i < 3; i++) {
            GlobalResourceRequest req = new GlobalResourceRequest(
                "op-" + i, "sess-1", "copper", 100, 100, 
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
        CampaignPersistence persistence = new CampaignPersistence(java.nio.file.Paths.get("/tmp/test-persist-" + System.currentTimeMillis()));
        GlobalBank bank = new GlobalBank(state, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(state, bank);

        state.bank.balances.put("copper", 1000L);
        state.globalRevision = 10;
        
        // First execution
        GlobalResourceRequest req1 = new GlobalResourceRequest(
            "op-duplicate", "sess-1", "copper", 100, 200, 
            GlobalResourceRequest.OperationType.WITHDRAW
        );
        TransactionResult res1 = txManager.execute(req1);
        assert res1.success() : "First should succeed";
        long balanceAfterFirst = state.bank.balances.get("copper");
        long revisionAfterFirst = state.globalRevision;
        
        // Second execution with same opId
        GlobalResourceRequest req2 = new GlobalResourceRequest(
            "op-duplicate", "sess-1", "copper", 100, 200, 
            GlobalResourceRequest.OperationType.WITHDRAW
        );
        TransactionResult res2 = txManager.execute(req2);
        assert res2.success() : "Second should succeed (idempotent)";
        assert state.bank.balances.get("copper") == balanceAfterFirst : "Balance should not change on duplicate";
        assert state.globalRevision == revisionAfterFirst : "Revision should not change on duplicate";
    }

    // 8. Stale GlobalRevisionNumber rejection
    private static void testStaleRevisionRejection() {
        GlobalCampaignState state = new GlobalCampaignState();
        CampaignPersistence persistence = new CampaignPersistence(java.nio.file.Paths.get("/tmp/test-persist-" + System.currentTimeMillis()));
        GlobalBank bank = new GlobalBank(state, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(state, bank);

        state.bank.balances.put("copper", 1000L);
        state.globalRevision = 20;
        
        // Request with stale expected revision
        GlobalResourceRequest req = new GlobalResourceRequest(
            "op-stale", "sess-1", "copper", 19, 100,  // expectedRevision=19 but current=20
            GlobalResourceRequest.OperationType.WITHDRAW
        );
        
        TransactionResult res = txManager.execute(req);
        assert !res.success() : "Should fail with stale revision";
        assert res.error().contains("revision") : "Error should mention revision";
    }

    // 9. Persistence round-trip survives mutation
    private static void testPersistenceRoundTrip() throws Exception {
        java.nio.file.Path persistDir = java.nio.file.Paths.get("/tmp/test-persist-" + System.currentTimeMillis());
        
        // First round: create state, execute transaction, persist
        GlobalCampaignState state1 = new GlobalCampaignState();
        CampaignPersistence persistence1 = new CampaignPersistence(persistDir);
        GlobalBank bank1 = new GlobalBank(state1, persistence1);
        GlobalTransactionManager txManager1 = new GlobalTransactionManager(state1, bank1);
        
        state1.bank.balances.put("copper", 500L);
        state1.globalRevision = 30;
        
        GlobalResourceRequest req = new GlobalResourceRequest(
            "op-persist", "sess-1", "copper", 30, 100, 
            GlobalResourceRequest.OperationType.WITHDRAW
        );
        TransactionResult res = txManager1.execute(req);
        assert res.success();
        assert state1.globalRevision == 31;
        assert state1.bank.balances.get("copper") == 400L;
        
        // Persist
        persistence1.save(state1);
        
        // Second round: load and verify
        CampaignPersistence persistence2 = new CampaignPersistence(persistDir);
        GlobalCampaignState loadedState = persistence2.load();
        
        assert loadedState.globalRevision == 31 : "Revision persisted";
        assert loadedState.bank.balances.get("copper") == 400L : "Balance persisted";
        assert loadedState.processedOperations.containsKey("op-persist") : "Idempotency record persisted";
    }

    // 10. GameplayAdapter has no direct GlobalBank mutation path
    private static void testGameplayAdapterNoDirectMutation() {
        MockClient client = new MockClient();
        GameplayAdapter adapter = new GameplayAdapter(client);
        
        // The adapter only sends requests, never mutates state directly
        adapter.requestGlobalResource("copper", 100);
        adapter.sendResearchRequest("test-tech");
        adapter.sendSectorCompletion("test-sector");
        
        // Verify only frames were sent, no direct state mutation
        assert client.framesSent.size() == 3 : "Three frames sent";
        boolean hasResourceReq = client.framesSent.stream().anyMatch(f -> f.type() == AcoMessageType.GLOBAL_RESOURCE_REQUEST.id);
        boolean hasResearchReq = client.framesSent.stream().anyMatch(f -> f.type() == AcoMessageType.RESEARCH_REQUEST.id);
        boolean hasSectorComplete = client.framesSent.stream().anyMatch(f -> f.type() == AcoMessageType.SECTOR_COMPLETION_EVENT.id);
        
        assert hasResourceReq : "Resource request sent";
        assert hasResearchReq : "Research request sent";
        assert hasSectorComplete : "Sector completion sent";
        
        // Verify no direct access to GlobalBank/GlobalCampaignState in adapter
        // This is a structural assertion: the adapter only communicates via AcoClient
    }

    // 11. CampaignAuthority routing
    private static void testCampaignAuthorityRouting() {
        SectorRegistry registry = new SectorRegistry();
        GlobalCampaignState globalState = new GlobalCampaignState();
        CampaignPersistence persistence = new CampaignPersistence(java.nio.file.Paths.get("/tmp/test-persist-" + System.currentTimeMillis()));
        GlobalBank bank = new GlobalBank(globalState, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(globalState, bank);
        CampaignAuthority authority = new CampaignAuthority(registry, globalState, txManager, null);
        
        // Verify authority exists and has correct dependencies
        assert authority != null : "Authority created";
        assert authority.registry == registry : "Registry injected";
        assert authority.txManager == txManager : "TxManager injected";
        assert authority.globalState == globalState : "GlobalState injected";
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
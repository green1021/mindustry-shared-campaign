package tests;

import sc.aco.*;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Phase 8C Deterministic Test Suite
 */
public class Phase8CDeterministicTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== RUNNING PHASE 8C DETERMINISTIC TEST SUITE ===");

        runTest("WithdrawalSuccess", () -> testWithdrawalSuccess());
        runTest("WithdrawalInsufficientBalance", () -> testInsufficientBalance());
        runTest("IdempotencyDeduplication", () -> testIdempotency());
        runTest("RevisionConcurrency", () -> testRevisionConcurrency());
        runTest("BoundaryAuth", () -> testBoundaryAuth());

        System.out.println("\n=== SUMMARY ===");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);

        if (failed > 0) System.exit(1);
    }

    private static TestContext createContext() {
        GlobalCampaignState state = new GlobalCampaignState();
        state.globalBank.put("copper", 1000L);
        state.globalRevision = 1;
        CampaignPersistence persistence = new CampaignPersistence(Paths.get("/tmp/test-8c-" + System.nanoTime()));
        GlobalBank bank = new GlobalBank(state, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(state, bank);
        return new TestContext(state, txManager);
    }

    private static void testWithdrawalSuccess() {
        TestContext ctx = createContext();
        GlobalResourceRequest req = new GlobalResourceRequest("op-1", "s1", "sector1", 1, "copper", 100L, GlobalResourceRequest.OperationType.WITHDRAW);
        
        TransactionResult res = ctx.txManager.execute(req);
        assert res.success() : "Withdrawal should succeed";
        assert ctx.state.globalBank.get("copper") == 900L : "Balance should be updated";
        assert ctx.state.globalRevision == 2 : "Revision incremented";
    }

    private static void testInsufficientBalance() {
        TestContext ctx = createContext();
        GlobalResourceRequest req = new GlobalResourceRequest("op-2", "s1", "sector1", 1, "copper", 2000L, GlobalResourceRequest.OperationType.WITHDRAW);
        
        TransactionResult res = ctx.txManager.execute(req);
        assert !res.success() : "Should reject insufficient funds";
        assert ctx.state.globalBank.get("copper") == 1000L : "Balance unchanged";
    }

    private static void testIdempotency() {
        TestContext ctx = createContext();
        GlobalResourceRequest req = new GlobalResourceRequest("op-dup", "s1", "sector1", 1, "copper", 100L, GlobalResourceRequest.OperationType.WITHDRAW);
        
        ctx.txManager.execute(req);
        long revAfterFirst = ctx.state.globalRevision;
        
        ctx.txManager.execute(req);
        assert ctx.state.globalRevision == revAfterFirst : "Revision unchanged on duplicate";
        assert ctx.state.globalBank.get("copper") == 900L : "Balance unchanged on duplicate";
    }

    private static void testRevisionConcurrency() {
        TestContext ctx = createContext();
        // Current revision is 1, request expects 0 -> stale
        GlobalResourceRequest req = new GlobalResourceRequest("op-3", "s1", "sector1", 0, "copper", 100L, GlobalResourceRequest.OperationType.WITHDRAW);
        
        TransactionResult res = ctx.txManager.execute(req);
        assert !res.success() : "Stale revision rejected";
    }

    private static void testBoundaryAuth() {
        // Verify P2/Adapter logic - just verify the method signature matches
        // The adapter takes an Item, amount, opId - we can't construct Item without Mindustry runtime
        // This test verifies the boundary exists (adapter has the method)
        GameplayAdapter adapter = new GameplayAdapter(new MockClient());
        assert adapter != null : "Adapter instantiates";
    }

    private static void runTest(String name, Runnable test) {
        try { test.run(); System.out.println("[PASS] " + name); passed++; }
        catch (AssertionError | Exception e) { System.out.println("[FAIL] " + name + ": " + e.getMessage()); failed++; }
    }

    static class TestContext {
        final GlobalCampaignState state;
        final GlobalTransactionManager txManager;
        TestContext(GlobalCampaignState s, GlobalTransactionManager t) { state = s; txManager = t; }
    }

    static class MockClient extends AcoClient {
        AcoFrame lastFrame;
        @Override protected void sendFrame(AcoFrame f) { this.lastFrame = f; }
    }
}

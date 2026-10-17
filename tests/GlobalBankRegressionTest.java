package tests;

import sc.aco.*;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permanent regression test for P0-2 logic in GlobalBank.
 */
public class GlobalBankRegressionTest {
    public static void main(String[] args) {
        TestGuard.assertEnabled("GlobalBankRegressionTest");
        
        GlobalCampaignState state = new GlobalCampaignState();
        state.globalBank = new ConcurrentHashMap<>();
        state.processedOperations = new ConcurrentHashMap<>();
        state.globalRevision = 0;
        CampaignPersistence persistence = new CampaignPersistence(Paths.get("test-data-overflow"));
        GlobalBank bank = new GlobalBank(state, persistence);
        
        // 1. Positive overflow check (CREDIT)
        state.globalBank.put("copper", Long.MAX_VALUE);
        GlobalResourceRequest reqOverflow = new GlobalResourceRequest(
            "op-ovf", "sess-1", "groundZero", 0, "copper", 1L, GlobalResourceRequest.OperationType.CREDIT
        );
        TransactionResult resOverflow = bank.process(reqOverflow);
        assert !resOverflow.success() : "Should fail on overflow";
        assert "OVERFLOW".equals(resOverflow.error()) : "Error should be OVERFLOW";
        assert bank.getBalance("copper") == Long.MAX_VALUE : "State should be unchanged";
        System.out.println("[PASS] Overflow check & state preservation");

        // 2. Negative amount check
        GlobalResourceRequest reqNegative = new GlobalResourceRequest(
            "op-neg", "sess-1", "groundZero", 0, "copper", -100L, GlobalResourceRequest.OperationType.CREDIT
        );
        TransactionResult resNegative = bank.process(reqNegative);
        assert !resNegative.success() : "Should fail on negative amount";
        assert "INVALID_AMOUNT".equals(resNegative.error()) : "Error should be INVALID_AMOUNT";
        System.out.println("[PASS] Negative amount check");
        
        // 3. Zero amount check
        GlobalResourceRequest reqZero = new GlobalResourceRequest(
            "op-zero", "sess-1", "groundZero", 0, "copper", 0L, GlobalResourceRequest.OperationType.CREDIT
        );
        TransactionResult resZero = bank.process(reqZero);
        assert !resZero.success() : "Should fail on zero amount";
        assert "INVALID_AMOUNT".equals(resZero.error()) : "Error should be INVALID_AMOUNT";
        System.out.println("[PASS] Zero amount check");

        // 4. Missing required fields check
        GlobalResourceRequest reqMissing = new GlobalResourceRequest(
            "", "sess-1", "groundZero", 0, "copper", 10L, GlobalResourceRequest.OperationType.CREDIT
        );
        TransactionResult resMissing = bank.process(reqMissing);
        assert !resMissing.success() : "Should fail on missing field";
        assert "MISSING_REQUIRED_FIELDS".equals(resMissing.error()) : "Error should be MISSING_REQUIRED_FIELDS";
        System.out.println("[PASS] Missing fields check");

        // 5. Insufficient funds / underflow check
        state.globalBank.put("lead", 50L);
        GlobalResourceRequest reqUnderflow = new GlobalResourceRequest(
            "op-und", "sess-1", "groundZero", 0, "lead", 100L, GlobalResourceRequest.OperationType.DEBIT
        );
        TransactionResult resUnderflow = bank.process(reqUnderflow);
        assert !resUnderflow.success() : "Should fail on underflow";
        assert "INSUFFICIENT_BALANCE".equals(resUnderflow.error()) : "Error should be INSUFFICIENT_BALANCE";
        assert bank.getBalance("lead") == 50L : "State should be unchanged";
        System.out.println("[PASS] Underflow / Insufficient funds check");
    }
}

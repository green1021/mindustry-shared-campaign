package tests;

import sc.aco.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permanent regression test for P0-2 logic in GlobalBank.
 *
 * <p>Cases 1-5 pin request validation; cases 6-12 pin the idempotency contract
 * (full-payload fingerprint matching, legacy records refused) and the crash-safe
 * commit protocol (WAL-first: a snapshot failure after a durable WAL append is
 * still a committed operation, and recovery replays it exactly once).
 */
public class GlobalBankRegressionTest {
    public static void main(String[] args) throws Exception {
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
        assert "INVALID_REQUEST_FIELDS".equals(resMissing.error()) : "Error should be INVALID_REQUEST_FIELDS";
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

        // 6. Identical retry is served the original result and applies nothing twice
        GlobalCampaignState state6 = new GlobalCampaignState();
        Path dir6 = Files.createTempDirectory("aco-gbp6-");
        CampaignPersistence p6 = new CampaignPersistence(dir6);
        GlobalBank bank6 = new GlobalBank(state6, p6);
        state6.globalBank.put("copper", 500L);
        state6.globalRevision = 10;
        GlobalResourceRequest req6 = new GlobalResourceRequest(
            "op-idem", "sess-1", "sector1", 10, "copper", 100L, GlobalResourceRequest.OperationType.CREDIT
        );
        TransactionResult first6 = bank6.process(req6);
        assert first6.success() : "original transaction should commit";
        assert first6.newRevision() == 11 && first6.newBalance() == 600
            : "original result should carry post-commit revision/balance";
        TransactionResult retry6 = bank6.process(new GlobalResourceRequest(
            "op-idem", "sess-1", "sector1", 10, "copper", 100L, GlobalResourceRequest.OperationType.CREDIT
        ));
        assert retry6.success() && retry6.newRevision() == 11 && retry6.newBalance() == 600
            : "identical retry must be served the original result";
        assert state6.globalRevision == 11 : "retry must not advance the revision";
        assert bank6.getBalance("copper") == 600L : "retry must not apply the credit twice";
        System.out.println("[PASS] Identical retry served original result");

        // 7. Duplicate operation id with any protected field changed is rejected
        GlobalCampaignState state7 = new GlobalCampaignState();
        Path dir7 = Files.createTempDirectory("aco-gbp7-");
        GlobalBank bank7 = new GlobalBank(state7, new CampaignPersistence(dir7));
        state7.globalBank.put("copper", 500L);
        state7.globalRevision = 20;
        bank7.process(new GlobalResourceRequest(
            "op-matrix", "sess-1", "sector1", 20, "copper", 50L, GlobalResourceRequest.OperationType.CREDIT));
        assert state7.globalRevision == 21 && bank7.getBalance("copper") == 550L : "setup transaction should commit";
        GlobalResourceRequest[] tampered = {
            new GlobalResourceRequest("op-matrix", "sess-2", "sector1", 20, "copper", 50L, GlobalResourceRequest.OperationType.CREDIT),
            new GlobalResourceRequest("op-matrix", "sess-1", "sector2", 20, "copper", 50L, GlobalResourceRequest.OperationType.CREDIT),
            new GlobalResourceRequest("op-matrix", "sess-1", "sector1", 20, "iron", 50L, GlobalResourceRequest.OperationType.CREDIT),
            new GlobalResourceRequest("op-matrix", "sess-1", "sector1", 20, "copper", 51L, GlobalResourceRequest.OperationType.CREDIT),
            new GlobalResourceRequest("op-matrix", "sess-1", "sector1", 20, "copper", 50L, GlobalResourceRequest.OperationType.WITHDRAW),
            new GlobalResourceRequest("op-matrix", "sess-1", "sector1", 19, "copper", 50L, GlobalResourceRequest.OperationType.CREDIT),
        };
        for (GlobalResourceRequest req : tampered) {
            TransactionResult res = bank7.process(req);
            assert !res.success() && "IDEMPOTENCY_PAYLOAD_MISMATCH".equals(res.error())
                : "tampered duplicate must be IDEMPOTENCY_PAYLOAD_MISMATCH, got " + res.error();
        }
        assert state7.globalRevision == 21 : "mismatched duplicates must not advance the revision";
        assert bank7.getBalance("copper") == 550L : "mismatched duplicates must not change the balance";
        assert bank7.getBalance("iron") == 0L : "mismatched duplicates must not create state";
        System.out.println("[PASS] Idempotency payload mismatch matrix");

        // 8. Stored entries without a verifiable fingerprint are refused, never matched
        GlobalCampaignState state8 = new GlobalCampaignState();
        Path dir8 = Files.createTempDirectory("aco-gbp8-");
        GlobalBank bank8 = new GlobalBank(state8, new CampaignPersistence(dir8));
        state8.processedOperations.put("op-legacy-mark", CampaignPersistence.LegacyOperation.INSTANCE);
        state8.processedOperations.put("op-legacy-bool", Boolean.TRUE);
        state8.processedOperations.put("op-legacy-result", new TransactionResult("op-legacy-result", true, null, 1, 10));
        String[] legacyIds = {"op-legacy-mark", "op-legacy-bool", "op-legacy-result"};
        for (String id : legacyIds) {
            TransactionResult res = bank8.process(new GlobalResourceRequest(
                id, "sess-1", "sector1", 0, "copper", 10L, GlobalResourceRequest.OperationType.CREDIT));
            assert !res.success() && "INCOMPATIBLE_IDEMPOTENCY_TYPE".equals(res.error())
                : "legacy record must be refused with INCOMPATIBLE_IDEMPOTENCY_TYPE, got " + res.error();
        }
        assert bank8.getBalance("copper") == 0L : "legacy refusals must not mutate state";
        System.out.println("[PASS] Legacy stored entries refused explicitly");

        // 9. WAL append commits even when the snapshot save fails afterwards
        GlobalCampaignState state9 = new GlobalCampaignState();
        Path dir9 = Files.createTempDirectory("aco-gbp9-");
        FailingSavePersistence p9 = new FailingSavePersistence(dir9, 1);
        GlobalBank bank9 = new GlobalBank(state9, p9);
        GlobalResourceRequest req9 = new GlobalResourceRequest(
            "op-snapfail", "sess-1", "groundZero", 0, "copper", 40L, GlobalResourceRequest.OperationType.CREDIT
        );
        TransactionResult res9 = bank9.process(req9);
        assert res9.success() && res9.newBalance() == 40L
            : "operation committed in the WAL must still be answered successfully";
        assert bank9.snapshotFailureCount() == 1 : "the failed snapshot must be counted";
        assert state9.globalRevision == 1 && bank9.getBalance("copper") == 40L : "in-memory state is committed";
        java.util.List<TransactionLogEntry> wal9 = p9.loadWAL();
        assert wal9.size() == 1 && "op-snapfail".equals(wal9.get(0).operationId)
            : "the durable WAL must hold the committed record";
        System.out.println("[PASS] Snapshot failure after WAL commit still succeeds");

        // 10. Restart after a crash between WAL append and snapshot replays exactly once
        // (dir9 continues from case 9: committed in the WAL, snapshot never written)
        GlobalCampaignState recovered10 = new CampaignPersistence(dir9).recover();
        assert recovered10.globalRevision == 1 : "recovery replays the committed revision";
        assert recovered10.globalBank.getOrDefault("copper", 0L) == 40L : "recovery replays the committed balance";
        assert recovered10.processedOperations.get("op-snapfail") instanceof GlobalBank.IdempotentEntry
            : "recovery rebuilds the fingerprint-bearing entry";
        GlobalCampaignState recoveredAgain = new CampaignPersistence(dir9).recover();
        assert recoveredAgain.globalRevision == 1 && recoveredAgain.globalBank.getOrDefault("copper", 0L) == 40L
            : "a second recovery must not re-apply the WAL";
        System.out.println("[PASS] Crash recovery replays WAL exactly once");

        // 11. A retry after recovery is served the original result; tampering is refused
        GlobalBank bank11 = new GlobalBank(recovered10, new CampaignPersistence(dir9));
        TransactionResult retry11 = bank11.process(new GlobalResourceRequest(
            "op-snapfail", "sess-1", "groundZero", 0, "copper", 40L, GlobalResourceRequest.OperationType.CREDIT
        ));
        assert retry11.success() && retry11.newRevision() == 1 && retry11.newBalance() == 40L
            : "post-recovery retry must be served the committed result";
        assert recovered10.globalRevision == 1 && bank11.getBalance("copper") == 40L
            : "post-recovery retry must not double-apply";
        TransactionResult tampered11 = bank11.process(new GlobalResourceRequest(
            "op-snapfail", "sess-1", "groundZero", 0, "copper", 41L, GlobalResourceRequest.OperationType.CREDIT
        ));
        assert !tampered11.success() && "IDEMPOTENCY_PAYLOAD_MISMATCH".equals(tampered11.error())
            : "post-recovery tampered duplicate must be refused, got " + tampered11.error();
        System.out.println("[PASS] Retry after recovery is idempotent");

        // 12. Snapshot round-trip preserves the fingerprint (not just the result)
        GlobalCampaignState state12 = new GlobalCampaignState();
        Path dir12 = Files.createTempDirectory("aco-gbp12-");
        CampaignPersistence p12 = new CampaignPersistence(dir12);
        GlobalBank bank12 = new GlobalBank(state12, p12);
        bank12.process(new GlobalResourceRequest(
            "op-snap", "sess-9", "sector9", 0, "copper", 25L, GlobalResourceRequest.OperationType.CREDIT));
        p12.save(state12);
        GlobalCampaignState reloaded12 = new CampaignPersistence(dir12).loadState()
            .orElseThrow(() -> new AssertionError("snapshot should load"));
        assert reloaded12.processedOperations.get("op-snap") instanceof GlobalBank.IdempotentEntry
            : "snapshot must round-trip the fingerprint-bearing entry";
        GlobalBank bank12b = new GlobalBank(reloaded12, new CampaignPersistence(dir12));
        TransactionResult retry12 = bank12b.process(new GlobalResourceRequest(
            "op-snap", "sess-9", "sector9", 0, "copper", 25L, GlobalResourceRequest.OperationType.CREDIT));
        assert retry12.success() && retry12.newBalance() == 25L
            : "identical retry after snapshot reload is served the result";
        TransactionResult mismatch12 = bank12b.process(new GlobalResourceRequest(
            "op-snap", "sess-9", "sector9", 0, "copper", 26L, GlobalResourceRequest.OperationType.CREDIT));
        assert !mismatch12.success() && "IDEMPOTENCY_PAYLOAD_MISMATCH".equals(mismatch12.error())
            : "fingerprint must survive the snapshot: tampered duplicate refused, got " + mismatch12.error();
        System.out.println("[PASS] Snapshot round-trip preserves fingerprint");
    }

    /** Persistence stub whose snapshot save fails a fixed number of times; the WAL still lands. */
    private static final class FailingSavePersistence extends CampaignPersistence {
        private int remainingFailures;

        FailingSavePersistence(Path root, int remainingFailures) {
            super(root);
            this.remainingFailures = remainingFailures;
        }

        @Override
        public void save(GlobalCampaignState state) throws IOException {
            if (remainingFailures > 0) {
                remainingFailures--;
                throw new IOException("injected snapshot failure");
            }
            super.save(state);
        }
    }
}

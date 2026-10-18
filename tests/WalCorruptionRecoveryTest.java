package tests;

import sc.aco.CampaignPersistence;
import sc.aco.GlobalBank;
import sc.aco.GlobalCampaignState;
import sc.aco.GlobalResourceRequest;
import sc.aco.TransactionLogEntry;
import sc.aco.TransactionResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Regression tests for the fail-closed WAL corruption recovery policy.
 *
 * <p>Before this policy, {@code loadWAL()} logged and skipped malformed WAL
 * lines while {@code recover()} went on to save a reconciled snapshot, so a
 * corrupted committed record in the middle of the WAL could be permanently
 * lost. These tests pin the replacement policy:
 *
 * <ul>
 *   <li>valid WAL: replay normally, byte-for-byte untouched;</li>
 *   <li>unterminated malformed tail (a torn write: the writer terminates every
 *       record before its fsync returns): preserve the exact bytes to a sidecar,
 *       truncate the WAL to its last complete record, keep appending safely;</li>
 *   <li>unterminated parseable tail: redo the record and restore its terminator;</li>
 *   <li>terminated malformed record anywhere (including the last line) and any
 *       mid-WAL corruption: refuse recovery entirely - no snapshot is written,
 *       the dataset is untouched, and the instance latches into write refusal;</li>
 *   <li>corrupt non-empty snapshot: refuse instead of starting from a blank
 *       state that a later save would persist over recoverable data.</li>
 * </ul>
 */
public class WalCorruptionRecoveryTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("WalCorruptionRecoveryTest");
        System.setProperty("arc.headless", "true");

        run("ValidWalRecoveryIsExact", WalCorruptionRecoveryTest::validWalRecoveryIsExact);
        run("TornTailIsPreservedAndRepaired", WalCorruptionRecoveryTest::tornTailIsPreservedAndRepaired);
        run("MiddleCorruptionFailsClosed", WalCorruptionRecoveryTest::middleCorruptionFailsClosed);
        run("CorruptSnapshotFailsClosedAndLatchesWrites", WalCorruptionRecoveryTest::corruptSnapshotFailsClosedAndLatchesWrites);
        run("IdempotencySurvivesRecovery", WalCorruptionRecoveryTest::idempotencySurvivesRecovery);
        run("RestartAfterRecoveryKeepsSafety", WalCorruptionRecoveryTest::restartAfterRecoveryKeepsSafety);

        System.out.println("WAL_CORRUPTION_SUITE: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private interface Case {
        void run() throws Exception;
    }

    private static void run(String name, Case body) {
        try {
            body.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("[FAIL] " + name + ": " + t);
        }
    }

    // ---------- helpers ----------

    private static TransactionLogEntry entry(String op, long amount, long newBalance, long rev) {
        GlobalResourceRequest req = new GlobalResourceRequest(
            op, "sess-1", "sector9", rev - 1, "copper", amount, GlobalResourceRequest.OperationType.CREDIT);
        TransactionResult res = new TransactionResult(op, true, null, rev, newBalance);
        return new TransactionLogEntry(req, res, 1_000L + rev);
    }

    private static GlobalCampaignState freshState() {
        GlobalCampaignState s = new GlobalCampaignState();
        s.campaignId = "default";
        return s;
    }

    private static Path wal(Path dir) {
        return dir.resolve("transaction_log.jsonl");
    }

    private static Path snap(Path dir) {
        return dir.resolve("campaign.json");
    }

    private static List<Path> tornSidecars(Path dir) throws Exception {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "transaction_log.jsonl.torn-tail-*")) {
            for (Path p : ds) out.add(p);
        }
        Collections.sort(out);
        return out;
    }

    // ---------- 1. recovery from a valid WAL ----------

    private static void validWalRecoveryIsExact() throws Exception {
        Path dir = Files.createTempDirectory("aco-wal-valid-");
        CampaignPersistence p = new CampaignPersistence(dir);
        p.appendWAL(entry("op-1", 10, 10, 1));
        p.appendWAL(entry("op-2", 5, 15, 2));
        byte[] walBefore = Files.readAllBytes(wal(dir));

        GlobalCampaignState s = p.recover();
        assert s.globalRevision == 2 : "revision must replay to 2, got " + s.globalRevision;
        assert s.globalBank.getOrDefault("copper", 0L) == 15L
            : "balance must replay to 15, got " + s.globalBank.getOrDefault("copper", 0L);
        assert s.processedOperations.size() == 2 : "both operations must be replayed";
        assert s.processedOperations.get("op-1") instanceof GlobalBank.IdempotentEntry
            : "entries must be rebuilt with their fingerprints";
        assert Arrays.equals(walBefore, Files.readAllBytes(wal(dir)))
            : "a clean recovery must not modify the WAL";
        assert tornSidecars(dir).isEmpty() : "no torn-tail sidecar for a clean WAL";
        assert !p.isRecoveryRefused() : "a clean recovery must not latch the instance";

        String snapText = Files.readString(snap(dir));
        assert snapText.contains("op-1") && snapText.contains("op-2")
            : "the reconciled snapshot must contain both operations";
        GlobalCampaignState re = new CampaignPersistence(dir).loadState()
            .orElseThrow(() -> new AssertionError("snapshot must reparse after recovery"));
        assert re.globalRevision == 2 && re.processedOperations.size() == 2
            : "the snapshot must round-trip the recovered state";
    }

    // ---------- 2. malformed/torn FINAL record ----------

    private static void tornTailIsPreservedAndRepaired() throws Exception {
        Path dir = Files.createTempDirectory("aco-wal-torn-");
        CampaignPersistence p = new CampaignPersistence(dir);
        p.appendWAL(entry("op-1", 10, 10, 1));
        byte[] firstRecord = Files.readAllBytes(wal(dir));
        byte[] partial = "{\"operationId\":\"op-torn\",\"globalRev".getBytes(StandardCharsets.UTF_8);
        Files.write(wal(dir), partial, StandardOpenOption.APPEND);

        GlobalCampaignState s = p.recover();
        assert s.globalRevision == 1 && s.globalBank.getOrDefault("copper", 0L) == 10L
            : "only committed records before the torn tail may replay";
        assert !s.processedOperations.containsKey("op-torn")
            : "an uncommitted torn record must never replay";
        assert !p.isRecoveryRefused() : "a preserved torn tail is a safe recovery, not a refusal";
        assert Arrays.equals(firstRecord, Files.readAllBytes(wal(dir)))
            : "the WAL must be truncated back to its last complete record";
        List<Path> sidecars = tornSidecars(dir);
        assert sidecars.size() == 1 : "exactly one torn-tail sidecar expected, got " + sidecars.size();
        assert Arrays.equals(partial, Files.readAllBytes(sidecars.get(0)))
            : "the sidecar must preserve the torn bytes exactly";
        String snapText = Files.readString(snap(dir));
        assert snapText.contains("op-1") && !snapText.contains("op-torn")
            : "the reconciled snapshot must hold only committed state";

        // Subsequent appends must land cleanly on the repaired WAL.
        p.appendWAL(entry("op-2", 5, 15, 2));
        GlobalCampaignState s2 = new CampaignPersistence(dir).recover();
        assert s2.globalRevision == 2 && s2.globalBank.getOrDefault("copper", 0L) == 15L
            : "appends after the torn-tail repair must replay cleanly";
        assert tornSidecars(dir).size() == 1 : "a clean append must not create sidecars";

        // A parseable record missing only its terminator is redone, not discarded.
        byte[] completeWal = Files.readAllBytes(wal(dir));
        assert completeWal.length > 0 && completeWal[completeWal.length - 1] == '\n'
            : "fixture must end with a record terminator";
        Files.write(wal(dir), Arrays.copyOf(completeWal, completeWal.length - 1), StandardOpenOption.TRUNCATE_EXISTING);
        GlobalCampaignState s3 = new CampaignPersistence(dir).recover();
        assert s3.globalRevision == 2 && s3.globalBank.getOrDefault("copper", 0L) == 15L
            : "a parseable unterminated tail must be kept (redone), not discarded";
        assert Arrays.equals(completeWal, Files.readAllBytes(wal(dir)))
            : "the missing terminator must be restored after the redo";
        assert tornSidecars(dir).size() == 1
            : "a parseable tail is not a torn record; no new sidecar";
    }

    // ---------- 3. malformed record between valid records ----------

    private static void middleCorruptionFailsClosed() throws Exception {
        Path dir = Files.createTempDirectory("aco-wal-mid-");
        CampaignPersistence p = new CampaignPersistence(dir);
        p.save(freshState()); // a snapshot exists and must not be overwritten on refusal
        p.appendWAL(entry("op-1", 10, 10, 1));
        Files.write(wal(dir), "not-json-between-records\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        p.appendWAL(entry("op-3", 5, 15, 3));
        byte[] walBefore = Files.readAllBytes(wal(dir));
        byte[] snapBefore = Files.readAllBytes(snap(dir));

        try {
            p.recover();
            throw new AssertionError("a malformed mid-WAL record must refuse recovery");
        } catch (CampaignPersistence.RecoveryException e) {
            assert e.kind == CampaignPersistence.RecoveryRefusal.WAL_CORRUPTION
                : "expected WAL_CORRUPTION, got " + e.kind;
            assert e.lineNumber == 2 : "refusal must name the corrupt record (line 2), got " + e.lineNumber;
            assert e.getMessage().contains("line 2") : "message must be explicit, got: " + e.getMessage();
        }
        assert p.isRecoveryRefused() : "a refused recovery must latch the instance";
        assert Arrays.equals(walBefore, Files.readAllBytes(wal(dir))) : "refusal must leave the WAL untouched";
        assert Arrays.equals(snapBefore, Files.readAllBytes(snap(dir)))
            : "refusal must NOT save a snapshot omitting the unreadable record";
        assert tornSidecars(dir).isEmpty() : "mid-WAL corruption is not a torn tail; nothing to preserve";

        // A terminated malformed FINAL line is also not a torn write: it must refuse too.
        Path dir2 = Files.createTempDirectory("aco-wal-tailterm-");
        CampaignPersistence p2 = new CampaignPersistence(dir2);
        p2.appendWAL(entry("op-1", 10, 10, 1));
        Files.write(wal(dir2), "not-json-final\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        try {
            p2.recover();
            throw new AssertionError("a terminated malformed final record must refuse recovery");
        } catch (CampaignPersistence.RecoveryException e) {
            assert e.kind == CampaignPersistence.RecoveryRefusal.WAL_CORRUPTION && e.lineNumber == 2
                : "terminated corrupt final record must refuse at line 2, got " + e.kind + "@" + e.lineNumber;
        }

        // With no snapshot on disk, a refused recovery must not create one either.
        Path dir3 = Files.createTempDirectory("aco-wal-nosnap-");
        CampaignPersistence p3 = new CampaignPersistence(dir3);
        p3.appendWAL(entry("op-1", 10, 10, 1));
        Files.write(wal(dir3), "not-json\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        try {
            p3.recover();
            throw new AssertionError("must refuse recovery");
        } catch (CampaignPersistence.RecoveryException expected) {
        }
        assert !Files.exists(snap(dir3))
            : "a refused recovery must not write a snapshot that could bake in the loss";
    }

    // ---------- 4. snapshot/WAL inconsistency ----------

    private static void corruptSnapshotFailsClosedAndLatchesWrites() throws Exception {
        Path dir = Files.createTempDirectory("aco-snap-bad-");
        CampaignPersistence p = new CampaignPersistence(dir);
        Files.writeString(snap(dir), "{oops not a snapshot");
        p.appendWAL(entry("op-1", 10, 10, 1));
        byte[] snapBefore = Files.readAllBytes(snap(dir));
        byte[] walBefore = Files.readAllBytes(wal(dir));

        try {
            p.recover();
            throw new AssertionError("a corrupt snapshot must refuse recovery");
        } catch (CampaignPersistence.RecoveryException e) {
            assert e.kind == CampaignPersistence.RecoveryRefusal.SNAPSHOT_CORRUPTION
                : "expected SNAPSHOT_CORRUPTION, got " + e.kind;
            assert e.getMessage().contains("campaign.json") : "message must name the file";
        }
        assert p.isRecoveryRefused() : "a refused snapshot recovery must latch the instance";
        assert Arrays.equals(snapBefore, Files.readAllBytes(snap(dir)))
            : "the corrupt-but-maybe-recoverable snapshot must never be overwritten";

        // Latched writes refuse and touch nothing.
        try {
            p.save(freshState());
            throw new AssertionError("save() must be refused after a refused recovery");
        } catch (CampaignPersistence.RecoveryException e) {
            assert e.kind == CampaignPersistence.RecoveryRefusal.SNAPSHOT_CORRUPTION
                : "latch must carry the original refusal kind, got " + e.kind;
        }
        try {
            p.appendWAL(entry("op-2", 5, 15, 2));
            throw new AssertionError("appendWAL() must be refused after a refused recovery");
        } catch (CampaignPersistence.RecoveryException expected) {
        }
        assert Arrays.equals(snapBefore, Files.readAllBytes(snap(dir))) : "refused writes must not alter the snapshot";
        assert Arrays.equals(walBefore, Files.readAllBytes(wal(dir))) : "refused writes must not alter the WAL";

        // After an operator supplies a valid snapshot, a clean recovery unlatches and persists.
        CampaignPersistence p2 = new CampaignPersistence(dir);
        p2.save(freshState());
        GlobalCampaignState s = p2.recover();
        assert s.processedOperations.size() == 1 && s.globalRevision == 1
            : "after repair, recovery must replay the WAL";
        assert s.globalBank.getOrDefault("copper", 0L) == 10L : "replayed balance must be 10";
        assert !p2.isRecoveryRefused() : "a clean recovery must clear the latch";
    }

    // ---------- 5. idempotency after recovery ----------

    private static void idempotencySurvivesRecovery() throws Exception {
        Path dir = Files.createTempDirectory("aco-wal-idem-");
        CampaignPersistence p = new CampaignPersistence(dir);
        p.appendWAL(entry("op-1", 10, 10, 1));
        Files.write(wal(dir), "{\"trunc".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        GlobalCampaignState s = p.recover();
        assert tornSidecars(dir).size() == 1 : "the torn tail must have been preserved";

        GlobalBank bank = new GlobalBank(s, p);
        TransactionResult retry = bank.process(new GlobalResourceRequest(
            "op-1", "sess-1", "sector9", 0, "copper", 10L, GlobalResourceRequest.OperationType.CREDIT));
        assert retry.success() && retry.newRevision() == 1 && retry.newBalance() == 10L
            : "post-recovery identical retry must be served the committed result";
        assert s.globalRevision == 1 && bank.getBalance("copper") == 10L && s.processedOperations.size() == 1
            : "post-recovery retry must not double-apply";

        TransactionResult tampered = bank.process(new GlobalResourceRequest(
            "op-1", "sess-1", "sector9", 0, "copper", 11L, GlobalResourceRequest.OperationType.CREDIT));
        assert !tampered.success() && "IDEMPOTENCY_PAYLOAD_MISMATCH".equals(tampered.error())
            : "tampered retry must be refused with IDEMPOTENCY_PAYLOAD_MISMATCH, got " + tampered.error();
        assert s.globalRevision == 1 && bank.getBalance("copper") == 10L : "the refusal must not mutate state";

        TransactionResult next = bank.process(new GlobalResourceRequest(
            "op-2", "sess-1", "sector9", s.globalRevision, "copper", 5L, GlobalResourceRequest.OperationType.CREDIT));
        assert next.success() && next.newBalance() == 15L && s.globalRevision == 2
            : "new commits after recovery must proceed normally, got " + next.error();

        GlobalCampaignState s4 = new CampaignPersistence(dir).recover();
        assert s4.globalRevision == 2 && s4.globalBank.getOrDefault("copper", 0L) == 15L
            && s4.processedOperations.size() == 2
            : "restart must reproduce the post-recovery state exactly";
    }

    // ---------- 6. restart safety properties ----------

    private static void restartAfterRecoveryKeepsSafety() throws Exception {
        // (a) restart after a successful torn-tail recovery
        Path dir = Files.createTempDirectory("aco-wal-restart-");
        CampaignPersistence p = new CampaignPersistence(dir);
        p.appendWAL(entry("op-1", 10, 10, 1));
        Files.write(wal(dir), "{\"trunc".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        p.recover();
        byte[] walAfterRecovery = Files.readAllBytes(wal(dir));
        byte[] snapAfterRecovery = Files.readAllBytes(snap(dir));

        GlobalCampaignState again = new CampaignPersistence(dir).recover();
        assert again.globalRevision == 1 && again.globalBank.getOrDefault("copper", 0L) == 10L
            : "restart after recovery must reproduce the same state";
        assert again.processedOperations.size() == 1 : "restart must not duplicate processed operations";
        assert Arrays.equals(walAfterRecovery, Files.readAllBytes(wal(dir)))
            : "restart must not rewrite the WAL";
        assert tornSidecars(dir).size() == 1 : "restart must not create extra sidecars";

        GlobalBank bank = new GlobalBank(again, new CampaignPersistence(dir));
        TransactionResult retry = bank.process(new GlobalResourceRequest(
            "op-1", "sess-1", "sector9", 0, "copper", 10L, GlobalResourceRequest.OperationType.CREDIT));
        assert retry.success() && retry.newBalance() == 10L && bank.getBalance("copper") == 10L
            : "idempotency must still hold after restart, balance must not double";
        assert Arrays.equals(snapAfterRecovery, Files.readAllBytes(snap(dir)))
            : "a served idempotent retry must not rewrite the snapshot";

        // (b) restart after a REFUSED recovery: same refusal, same untouched bytes
        Path dirB = Files.createTempDirectory("aco-wal-restart2-");
        CampaignPersistence pb = new CampaignPersistence(dirB);
        pb.appendWAL(entry("op-1", 10, 10, 1));
        Files.write(wal(dirB), "garbage-between\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        pb.appendWAL(entry("op-3", 5, 15, 3));
        byte[] walB = Files.readAllBytes(wal(dirB));
        try {
            pb.recover();
            throw new AssertionError("the first recovery must refuse");
        } catch (CampaignPersistence.RecoveryException expected) {
        }
        CampaignPersistence pb2 = new CampaignPersistence(dirB);
        try {
            pb2.recover();
            throw new AssertionError("a restart must refuse again");
        } catch (CampaignPersistence.RecoveryException e) {
            assert e.kind == CampaignPersistence.RecoveryRefusal.WAL_CORRUPTION && e.lineNumber == 2
                : "restart refusal must be identical, got " + e.kind + "@" + e.lineNumber;
        }
        assert pb2.isRecoveryRefused() : "the restarted instance must also latch";
        assert Arrays.equals(walB, Files.readAllBytes(wal(dirB)))
            : "restart must leave the WAL untouched";
        assert !Files.exists(snap(dirB)) : "a refused recovery must not create a snapshot";
    }
}

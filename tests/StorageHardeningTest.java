package tests;

import sc.aco.AcoFrame;
import sc.aco.CampaignPersistence;
import sc.aco.CampaignSectorStore;
import sc.aco.GlobalCampaignState;
import sc.aco.GlobalResourceRequest;
import sc.aco.SectorLeaseJournal;
import sc.aco.SectorOwnership;
import sc.aco.TransactionLogEntry;
import sc.aco.TransactionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Storage/persistence hardening: bounded in-memory state, atomic file install,
 * revision-fenced replay, and concurrent access to one path.
 *
 * <p>Every case here is a real defect that was reproduced before it was fixed; the
 * assertion in each case is the observable consequence, not the presence of a
 * method. Cases that would only pass because of a <em>sleep</em> are written with
 * latches and barriers instead.
 */
public class StorageHardeningTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("StorageHardeningTest");

        run("ProcessedWindowIsBounded", StorageHardeningTest::processedWindowIsBounded);
        run("EvictionNeverDropsLiveMarker", StorageHardeningTest::evictionKeepsLiveMarker);
        run("ForgetProcessedKeepsWindowConsistent", StorageHardeningTest::forgetKeepsWindowConsistent);
        run("BoundedWindowSurvivesSaveAndReload", StorageHardeningTest::boundedWindowRoundTrip);
        run("LegacyHugeWindowIsTrimmedOnLoad", StorageHardeningTest::legacyHugeWindowTrimmedOnLoad);
        run("StateSyncStaysUnderFrameLimit", StorageHardeningTest::stateSyncFitsFrame);
        run("StateSyncStillCarriesCampaignView", StorageHardeningTest::stateSyncCarriesView);
        run("CompactionDropsCoveredKeepsNewer", StorageHardeningTest::compactionDropsCovered);
        run("CompactionPreservesUnreadableRecord", StorageHardeningTest::compactionPreservesUnreadable);
        run("CompactionIsSkippedForSmallLog", StorageHardeningTest::compactionThreshold);
        run("RecoveryIgnoresCoveredRecordAfterEviction", StorageHardeningTest::recoveryIgnoresCoveredRecord);
        run("RecoveryAppliesRecordAboveSnapshot", StorageHardeningTest::recoveryAppliesNewerRecord);
        run("ConcurrentSavesNeverExposePartialJson", StorageHardeningTest::concurrentSavesAreAtomic);
        run("SaveFailureLeavesNoTempFile", StorageHardeningTest::saveFailureCleansTemp);
        run("ConcurrentLeaseWritesAreAtomic", StorageHardeningTest::concurrentLeaseWrites);
        run("OversizedPayloadIsRejected", StorageHardeningTest::oversizedPayloadRejected);
        run("FreshCampaignDirectoryCreatesLeaseJournal", StorageHardeningTest::freshDirectoryCreatesLeaseJournal);

        System.out.println("\n=== SUMMARY ===");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void run(String name, Check check) {
        System.out.println("[RUN] " + name);
        try {
            check.run();
            System.out.println("[PASS] " + name);
            passed++;
        } catch (AssertionError e) {
            System.out.println("[FAIL] " + name + ": " + e.getMessage());
            failed++;
        } catch (Exception e) {
            System.out.println("[ERROR] " + name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            failed++;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static GlobalCampaignState freshState() {
        GlobalCampaignState s = new GlobalCampaignState();
        s.campaignId = "default";
        return s;
    }

    private static TransactionLogEntry entry(String op, long amount, long newBalance, long rev) {
        GlobalResourceRequest req = new GlobalResourceRequest(
            op, "sess-1", "groundZero", rev - 1, "copper", amount, GlobalResourceRequest.OperationType.CREDIT);
        TransactionResult res = new TransactionResult(op, true, null, rev, newBalance);
        return new TransactionLogEntry(req, res, 1_000L + rev);
    }

    private static Path wal(Path dir) {
        return dir.resolve("transaction_log.jsonl");
    }

    private static Path snap(Path dir) {
        return dir.resolve("campaign.json");
    }

    // ------------------------------------------------- 1. bounded idempotency

    private static void processedWindowIsBounded() {
        GlobalCampaignState s = freshState();
        for (int i = 0; i < 5000; i++) {
            s.recordProcessed("op-" + i, Boolean.TRUE);
        }
        assert s.processedOperations.size() == GlobalCampaignState.MAX_PROCESSED_OPERATIONS
            : "window must stay at " + GlobalCampaignState.MAX_PROCESSED_OPERATIONS
                + ", got " + s.processedOperations.size();
        assert s.processedOperations.containsKey("op-4999") : "newest marker must be present";
        assert !s.processedOperations.containsKey("op-0") : "oldest marker must be evicted";
        assert !s.processedOperations.containsKey("op-" + (5000 - 1 - GlobalCampaignState.MAX_PROCESSED_OPERATIONS))
            : "the marker just outside the window must be gone";
    }

    /**
     * Re-recording an id that is still inside the window must not queue a second
     * copy in the order deque: the duplicate would be evicted as "oldest" and delete
     * the live marker, silently dropping idempotency for a recent operation.
     */
    private static void evictionKeepsLiveMarker() {
        GlobalCampaignState s = freshState();
        for (int i = 0; i < GlobalCampaignState.MAX_PROCESSED_OPERATIONS; i++) {
            s.recordProcessed("op-" + i, Boolean.TRUE);
        }
        // Re-record the oldest live id, then push one new id to force one eviction.
        s.recordProcessed("op-0", Boolean.TRUE);
        s.recordProcessed("op-new", Boolean.TRUE);
        assert s.processedOperations.containsKey("op-0")
            : "re-recording a live id must not cause that id to be evicted";
        assert s.processedOperations.containsKey("op-new") : "the new id must be recorded";
        assert s.processedOperations.size() == GlobalCampaignState.MAX_PROCESSED_OPERATIONS
            : "window must not overflow: " + s.processedOperations.size();
    }

    private static void forgetKeepsWindowConsistent() {
        GlobalCampaignState s = freshState();
        s.recordProcessed("op-1", Boolean.TRUE);
        s.recordProcessed("op-2", Boolean.TRUE);
        s.forgetProcessed("op-1");
        assert !s.processedOperations.containsKey("op-1") : "forget must drop the marker";
        // Fill the window: the deque must not still hold the forgotten id, or the
        // eviction would consume a slot without removing anything from the map.
        for (int i = 0; i < GlobalCampaignState.MAX_PROCESSED_OPERATIONS + 5; i++) {
            s.recordProcessed("fill-" + i, Boolean.TRUE);
        }
        assert s.processedOperations.size() == GlobalCampaignState.MAX_PROCESSED_OPERATIONS
            : "window must be exactly bounded after a forget, got " + s.processedOperations.size();
        assert s.processedOperations.containsKey("fill-" + (GlobalCampaignState.MAX_PROCESSED_OPERATIONS + 4))
            : "newest fill marker must be present";
    }

    private static void boundedWindowRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("aco-bounded-");
        CampaignPersistence p = new CampaignPersistence(dir);
        GlobalCampaignState s = freshState();
        for (int i = 0; i < 3000; i++) {
            s.recordProcessed("op-" + i, Boolean.TRUE);
        }
        p.save(s);
        String json = Files.readString(snap(dir));
        GlobalCampaignState reloaded = CampaignPersistence.fromJson(json);
        assert reloaded.processedOperations.size() == GlobalCampaignState.MAX_PROCESSED_OPERATIONS
            : "a reload must not resurrect evicted markers, got " + reloaded.processedOperations.size();
        assert reloaded.processedOperations.containsKey("op-2999") : "newest marker must survive the round trip";
        assert reloaded.processedOperations.containsKey("op-0") == false : "oldest marker must stay evicted";

        // A second save of a full window must not grow the file: the table is a
        // window, not an append-only history.
        long firstLen = Files.size(snap(dir));
        p.save(reloaded);
        long secondLen = Files.size(snap(dir));
        assert Math.abs(secondLen - firstLen) < 64
            : "a full window must not grow the snapshot (first=" + firstLen + ", second=" + secondLen + ")";
    }

    /**
     * A campaign file written before the window existed can carry tens of thousands
     * of markers. Loading must trim to the window instead of keeping them all (and
     * writing them all back).
     */
    private static void legacyHugeWindowTrimmedOnLoad() throws Exception {
        Path dir = Files.createTempDirectory("aco-legacy-window-");
        StringBuilder ops = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            if (i > 0) ops.append(',');
            ops.append("\"op-").append(i).append("\":true");
        }
        Files.writeString(snap(dir),
            "{\"campaignId\":\"default\",\"globalRevision\":4000,\"unlockedSectors\":[\"groundZero\"],"
                + "\"completedSectors\":[],\"globalBank\":{},\"techTree\":{},"
                + "\"processedOperations\":{" + ops + "}}");
        GlobalCampaignState loaded = new CampaignPersistence(dir).loadState().orElseThrow();
        assert loaded.processedOperations.size() == GlobalCampaignState.MAX_PROCESSED_OPERATIONS
            : "a legacy oversized table must be trimmed on load, got " + loaded.processedOperations.size();
        assert loaded.processedOperations.containsKey("op-3999") : "the newest legacy markers must be kept";
        assert !loaded.processedOperations.containsKey("op-0") : "the oldest legacy markers must be dropped";
    }

    // ------------------------------------------------------- 2. wire payload

    /**
     * The state-sync frame carried the whole idempotency table. With a bounded window
     * of 1024 realistic ids the JSON exceeded AcoFrame.MAX_PAYLOAD, and encode() on
     * the peer side refused/killed the connection. The sync must be view-only.
     */
    private static void stateSyncFitsFrame() {
        GlobalCampaignState s = freshState();
        for (int i = 0; i < GlobalCampaignState.MAX_PROCESSED_OPERATIONS; i++) {
            s.recordProcessed("completion|crateredBattleground|op-" + i + "-0123456789abcdef0123456789abcdef",
                Boolean.TRUE);
        }
        String sync = CampaignPersistence.toSyncJson(s);
        assert !sync.contains("processedOperations")
            : "the sync payload must not ship the authority's idempotency table";
        assert sync.getBytes(StandardCharsets.UTF_8).length <= AcoFrame.MAX_PAYLOAD
            : "sync payload must fit one frame, got " + sync.getBytes(StandardCharsets.UTF_8).length;
        // The full snapshot keeps the table: a restart must still dedupe retries.
        assert CampaignPersistence.toJson(s).contains("processedOperations")
            : "the on-disk snapshot must keep the idempotency table";
    }

    private static void stateSyncCarriesView() {
        GlobalCampaignState s = freshState();
        s.globalRevision = 7;
        s.unlockedSectors.add("groundZero");
        s.unlockedSectors.add("crateredBattleground");
        s.completedSectors.add("groundZero");
        s.techTree.put("laser", true);
        s.globalBank.put("copper", 42L);
        GlobalCampaignState view = CampaignPersistence.fromJson(CampaignPersistence.toSyncJson(s));
        assert view.globalRevision == 7 : "revision must survive the sync round trip, got " + view.globalRevision;
        assert view.unlockedSectors.contains("crateredBattleground") : "unlocks must survive";
        assert view.completedSectors.contains("groundZero") : "completions must survive";
        assert Boolean.TRUE.equals(view.techTree.get("laser")) : "research must survive";
        assert Long.valueOf(42L).equals(view.globalBank.get("copper")) : "bank must survive";
    }

    // ------------------------------------------------------ 3. WAL compaction

    private static void compactionDropsCovered() throws Exception {
        Path dir = Files.createTempDirectory("aco-compact-");
        CampaignPersistence p = new CampaignPersistence(dir);
        p.appendWAL(entry("op-1", 10, 10, 1));
        p.appendWAL(entry("op-2", 5, 15, 2));
        p.appendWAL(entry("op-3", 5, 20, 3));
        p.compactWAL(2);
        List<TransactionLogEntry> kept = p.loadWAL();
        assert kept.size() == 1 : "compaction must keep exactly the uncovered record, got " + kept.size();
        assert kept.get(0).operationId.equals("op-3") : "the kept record must be the newest one";
        assert kept.get(0).globalRevision == 3 : "revision must be preserved, got " + kept.get(0).globalRevision;
    }

    private static void compactionPreservesUnreadable() throws Exception {
        Path dir = Files.createTempDirectory("aco-compact-garbage-");
        CampaignPersistence p = new CampaignPersistence(dir);
        p.appendWAL(entry("op-1", 10, 10, 1));
        Files.write(wal(dir), "not-json-must-survive\n".getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND);
        // Revision 3, i.e. ABOVE the snapshot revision used for compaction: only a
        // record the snapshot does not cover has to be replayed on recovery.
        p.appendWAL(entry("op-2", 5, 15, 3));
        p.compactWAL(2);
        String text = Files.readString(wal(dir));
        assert text.contains("not-json-must-survive")
            : "an unreadable record must be preserved for a human, never silently deleted";
        assert text.contains("\"op-2\"") : "a record above the snapshot must still be kept";
        assert !text.contains("\"op-1\"") : "a fully covered record must be dropped";
    }

    private static void compactionThreshold() throws Exception {
        Path dir = Files.createTempDirectory("aco-compact-threshold-");
        CampaignPersistence p = new CampaignPersistence(dir);
        GlobalCampaignState s = freshState();
        s.globalRevision = 2;
        p.appendWAL(entry("op-1", 10, 10, 1));
        p.appendWAL(entry("op-2", 5, 15, 2));
        byte[] before = Files.readAllBytes(wal(dir));
        p.save(s);
        assert Files.size(wal(dir)) < CampaignPersistence.WAL_COMPACT_MIN_BYTES
            : "fixture must stay under the compaction threshold";
        assert java.util.Arrays.equals(before, Files.readAllBytes(wal(dir)))
            : "a small log must stay byte-identical across a save (no rewrite per transaction)";
    }

    // -------------------------------------------------- 4. revision-fenced replay

    /**
     * The harmful leftover is an OLD absolute balance: record rev 5 says copper=25,
     * the snapshot has already moved on to rev 6 with copper=5. Replaying the rev-5
     * record because its id is no longer in the bounded window would overwrite the
     * newer balance with a stale one.
     */
    private static void recoveryIgnoresCoveredRecord() throws Exception {
        Path dir = Files.createTempDirectory("aco-stale-replay-");
        CampaignPersistence p = new CampaignPersistence(dir);
        GlobalCampaignState s = freshState();
        s.globalRevision = 6;
        s.globalBank.put("copper", 5L);
        p.save(s);
        p.appendWAL(entry("op-stale", 10, 25, 5));
        GlobalCampaignState recovered = p.recover();
        assert recovered.globalRevision == 6
            : "a record covered by the snapshot must not move the revision, got " + recovered.globalRevision;
        assert recovered.globalBank.getOrDefault("copper", 0L) == 5L
            : "a covered record must not overwrite the newer balance, got "
                + recovered.globalBank.get("copper");
    }

    private static void recoveryAppliesNewerRecord() throws Exception {
        Path dir = Files.createTempDirectory("aco-newer-replay-");
        CampaignPersistence p = new CampaignPersistence(dir);
        GlobalCampaignState s = freshState();
        s.globalRevision = 6;
        s.globalBank.put("copper", 5L);
        p.save(s);
        p.appendWAL(entry("op-new", 25, 30, 7));
        GlobalCampaignState recovered = p.recover();
        assert recovered.globalRevision == 7 : "an uncovered record must be replayed, got " + recovered.globalRevision;
        assert recovered.globalBank.getOrDefault("copper", 0L) == 30L
            : "the replayed absolute balance must win, got " + recovered.globalBank.get("copper");
    }

    // ------------------------------------------------- 5. atomic file install

    private static void concurrentSavesAreAtomic() throws Exception {
        Path dir = Files.createTempDirectory("aco-concurrent-save-");
        CampaignPersistence p = new CampaignPersistence(dir);
        GlobalCampaignState seed = freshState();
        // Same shape as every writer's document, so a reader that catches the seed
        // before the first install still satisfies the invariant under test.
        seed.campaignId = "A".repeat(20_000) + "seed";
        seed.globalRevision = 1;
        p.save(seed);

        // Deterministic proof of the install mechanism: a save must publish a NEW
        // file (temp + rename). Rewriting the live snapshot in place keeps the inode
        // and leaves a window in which a reader sees a truncated document - which the
        // loop below could only catch by luck, so it is asserted here instead.
        Object inodeBefore = Files.readAttributes(snap(dir),
            java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        GlobalCampaignState second = freshState();
        second.campaignId = "A".repeat(20_000) + "second";
        second.globalRevision = 2;
        p.save(second);
        Object inodeAfter = Files.readAttributes(snap(dir),
            java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        assert inodeBefore != null && inodeAfter != null
            : "this filesystem exposes no inode, so the install mechanism cannot be observed here";
        assert !inodeBefore.equals(inodeAfter)
            : "save must install a new file (temp + atomic rename), not rewrite the live snapshot in place";

        int writers = 4, rounds = 40;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();

        for (int w = 0; w < writers; w++) {
            final int id = w;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        GlobalCampaignState s = freshState();
                        // Large alternating field: a non-atomic install is visible
                        // immediately as a truncated or mixed document.
                        s.campaignId = (id % 2 == 0)
                            ? "A".repeat(20_000) + i
                            : "B".repeat(20_000) + i;
                        s.globalRevision = 1_000L * id + i;
                        p.save(s);
                    }
                } catch (Throwable t) {
                    writerFailure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }, "saver-" + w).start();
        }

        start.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (done.getCount() > 0 && System.nanoTime() < deadline) {
            String text = Files.readString(snap(dir));
            GlobalCampaignState read = CampaignPersistence.fromJson(text);
            reads.incrementAndGet();
            assert read.campaignId != null && (read.campaignId.startsWith("A") || read.campaignId.startsWith("B"))
                : "a reader observed a partial document";
            assert text.startsWith("{") && text.endsWith("}")
                : "a reader observed a truncated document (len=" + text.length() + ")";
        }
        assert done.await(20, TimeUnit.SECONDS) : "writers did not finish";
        assert writerFailure.get() == null : "concurrent saves failed: " + writerFailure.get();
        assert reads.get() > 0 : "the reader thread never ran; the test proves nothing";

        String finalText = Files.readString(snap(dir));
        GlobalCampaignState finalState = CampaignPersistence.fromJson(finalText);
        assert finalState.campaignId.endsWith(String.valueOf(rounds - 1))
            : "the last installed snapshot must be complete, got tail " + tail(finalState.campaignId);
        try (var files = Files.list(dir)) {
            assert files.noneMatch(f -> f.getFileName().toString().endsWith(".json.tmp"))
                : "a failed/aborted save must not leave a temp file behind";
        }
    }

    private static void saveFailureCleansTemp() throws Exception {
        Path dir = Files.createTempDirectory("aco-save-fail-");
        CampaignPersistence p = new CampaignPersistence(dir);
        p.save(freshState());
        // Make the install target a non-empty directory so ATOMIC_MOVE must fail.
        Files.deleteIfExists(snap(dir));
        Files.createDirectories(snap(dir));
        Files.writeString(snap(dir).resolve("blocker"), "x");
        boolean threw = false;
        try {
            p.save(freshState());
        } catch (IOException expected) {
            threw = true;
        }
        assert threw : "a save that cannot install must report the failure";
        try (var files = Files.list(dir)) {
            assert files.noneMatch(f -> f.getFileName().toString().endsWith(".json.tmp"))
                : "the failed save must delete its temp file";
        }
    }

    private static void concurrentLeaseWrites() throws Exception {
        Path dir = Files.createTempDirectory("aco-lease-atomic-");
        CampaignSectorStore store = new CampaignSectorStore(
            dir, "default", CampaignSectorStore.STRUCTURAL_ONLY);
        String key = "groundZero";
        store.writeLease(key, "A".repeat(512));

        int writers = 4, rounds = 60;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();

        for (int w = 0; w < writers; w++) {
            final String grant = (w % 2 == 0) ? "A".repeat(512) : "B".repeat(512);
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        store.writeLease(key, grant);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }, "lease-" + w).start();
        }

        start.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (done.getCount() > 0 && System.nanoTime() < deadline) {
            String lease = store.readLease(key);
            reads.incrementAndGet();
            assert lease != null && lease.length() == 512
                : "a reader observed a torn/empty lease (length=" + (lease == null ? -1 : lease.length()) + ")";
        }
        assert done.await(20, TimeUnit.SECONDS) : "lease writers did not finish";
        assert failure.get() == null : "concurrent lease writes failed: " + failure.get();
        assert reads.get() > 0 : "the lease reader never ran; the test proves nothing";

        String last = store.readLease(key);
        assert last.length() == 512 && (last.charAt(0) == 'A' || last.charAt(0) == 'B')
            : "the final lease must be one complete grant";
        try (var files = Files.list(store.sectorDir(key))) {
            assert files.noneMatch(f -> f.getFileName().toString().startsWith("lease-"))
                : "lease writes must not leave temp files";
        }
    }

    private static void oversizedPayloadRejected() {
        byte[] tooBig = new byte[AcoFrame.MAX_PAYLOAD + 1];
        boolean threw = false;
        try {
            new AcoFrame((byte) 1, (byte) 1, 1, tooBig).encode();
        } catch (IllegalArgumentException expected) {
            threw = true;
            assert expected.getMessage().contains("payload-too-large")
                : "the failure must be explicit, got " + expected.getMessage();
        }
        assert threw : "an oversized payload must fail at the sender, not kill the connection";
        byte[] exact = new byte[AcoFrame.MAX_PAYLOAD];
        byte[] frame = new AcoFrame((byte) 1, (byte) 1, 1, exact).encode();
        assert frame.length == AcoFrame.HEADER_SIZE + AcoFrame.MAX_PAYLOAD
            : "a maximum payload must still be encodable, got " + frame.length;
    }

    /**
     * The lease journal lives in {@code <campaign>/leases}, a directory nothing else
     * creates. Opening it used to fail on the first run of a fresh campaign, which
     * left the registry with no journal ("lease durability disabled") for the whole
     * session — the exact window in which a crash loses every claim. The journal
     * must create its own directory and still be readable after a restart.
     */
    private static void freshDirectoryCreatesLeaseJournal() throws Exception {
        Path dir = Files.createTempDirectory("sc-fresh-campaign-");
        try {
            Path leases = dir.resolve("leases");
            assert !Files.exists(leases) : "precondition: a fresh campaign directory has no leases/ yet";

            SectorLeaseJournal journal = new SectorLeaseJournal(leases);
            journal.save(List.of(new SectorOwnership("groundZero")));

            assert Files.isRegularFile(leases.resolve("sector_leases.jsonl"))
                : "the journal file must exist after a save, not just the directory";

            // Re-open exactly as a restart would, and read the claim back.
            SectorLeaseJournal reopened = new SectorLeaseJournal(leases);
            List<SectorOwnership> loaded = reopened.load();
            assert loaded.size() == 1 : "expected exactly one durable lease, got " + loaded.size();
            assert "groundZero".equals(loaded.get(0).sectorKey)
                : "the durable lease must name groundZero, got " + loaded.get(0).sectorKey;
        } finally {
            deleteTree(dir);
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static String tail(String s) {
        return s == null ? "null" : s.substring(Math.max(0, s.length() - 12));
    }
}

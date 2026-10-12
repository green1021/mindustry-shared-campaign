package fixture;

import sc.GlobalLedger;
import sc.SectorHandover;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real-class harness for the P1 transaction layer (NOT a ticking engine or network test).
 * Compiles against build/classes so it exercises the shipped SectorHandover / GlobalLedger bytecode.
 */
public final class TransactionTest {
    static int checks;
    static final Path ROOT = Path.of(System.getProperty("sc.test.dir", "build/transaction-test"));

    static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }

    public static void main(String[] args) throws Exception {
        if (Files.exists(ROOT)) deleteTree(ROOT);
        Files.createDirectories(ROOT);

        handover();
        ledgerWalReplay();
        ledgerTornTail();
        ledgerSnapshot();
        ledgerCorruptSnapshotFailsClosed();

        System.out.println("TRANSACTION_OK checks=" + checks
            + " scope=sector-handover+global-ledger; no ticking engine, no network");
    }

    // ---- SectorHandover -------------------------------------------------------------------

    static void handover() throws Exception {
        Path dir = ROOT.resolve("handover");
        Files.createDirectories(dir);
        Path source = dir.resolve("sector.msav");
        byte[] payload = new byte[200_000];
        new Random(7).nextBytes(payload);
        Files.write(source, payload);

        Path packed = dir.resolve("sector.msav.gz");
        Path unpacked = dir.resolve("restored.msav");
        SectorHandover.saveCompressed(source, packed);
        check(Files.size(packed) > 0, "saveCompressed produces a framed artifact");

        SectorHandover.loadCompressed(packed, unpacked);
        check(Arrays.equals(payload, Files.readAllBytes(unpacked)), "roundtrip preserves bytes exactly");

        // CRC / truncation failures must never touch a pre-existing target.
        Path target = dir.resolve("live.msav");
        byte[] good = "PRE-EXISTING-GOOD-SAVE".getBytes(StandardCharsets.UTF_8);
        Files.write(target, good);

        byte[] corrupt = Files.readAllBytes(packed);
        corrupt[corrupt.length / 2] ^= 0x5A;
        Path corruptFile = dir.resolve("corrupt.gz");
        Files.write(corruptFile, corrupt);
        check(throwsLoad(corruptFile, target), "corrupted transfer is rejected");
        check(Arrays.equals(good, Files.readAllBytes(target)), "rejected transfer leaves the live target intact");

        byte[] truncated = Arrays.copyOf(Files.readAllBytes(packed), (int) Files.size(packed) / 2);
        Path truncatedFile = dir.resolve("truncated.gz");
        Files.write(truncatedFile, truncated);
        check(throwsLoad(truncatedFile, target), "truncated transfer is rejected");
        check(Arrays.equals(good, Files.readAllBytes(target)), "truncated transfer leaves the live target intact");

        Path badMagic = dir.resolve("badmagic.gz");
        Files.write(badMagic, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        check(throwsLoad(badMagic, target), "wrong container magic is rejected");
        check(Arrays.equals(good, Files.readAllBytes(target)), "bad-magic transfer leaves the live target intact");

        // Concurrent writers to the same target must not collide on a fixed temp name.
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    SectorHandover.saveCompressed(source, packed);
                } catch (Exception ex) {
                    failures.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        check(failures.get() == 0, "8 concurrent saveCompressed calls to one target all succeed");
        SectorHandover.loadCompressed(packed, unpacked);
        check(Arrays.equals(payload, Files.readAllBytes(unpacked)), "target after concurrent writes is a valid complete artifact");

        long leftovers = Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
        check(leftovers == 0, "no .tmp files leak after success or failure");
    }

    static boolean throwsLoad(Path source, Path target) {
        try {
            SectorHandover.loadCompressed(source, target);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    // ---- GlobalLedger: WAL durability + replay --------------------------------------------

    static void ledgerWalReplay() throws Exception {
        Path dir = ROOT.resolve("wal");
        Files.createDirectories(dir);
        // No auto-snapshot: every record must survive through the WAL alone.
        GlobalLedger crashed = GlobalLedger.open(dir, Integer.MAX_VALUE);
        crashed.commit("r1", "REQ|r1", "RES|r1|1|70", 1, 70);
        crashed.commit("r2", "REQ|r2", "RES|r2|2|40", 2, 40);
        crashed.commit("r3", "REQ|r3", "RES|r3|3|10", 3, 10);
        // Deliberately NOT closed: simulates a host killed after committing, before the reply.

        GlobalLedger recovered = GlobalLedger.open(dir, Integer.MAX_VALUE);
        check(recovered.size() == 3, "WAL replay restores all committed requests after a crash");
        check(recovered.revision() == 3 && recovered.balance() == 10,
            "WAL replay restores the monotonic revision and balance (no revision reset)");
        check("RES|r1|1|70".equals(recovered.find("r1").replyFrame),
            "committed reply is returned for an already-applied request (no double-debit)");
        check(recovered.replayedRecords() == 3 && recovered.truncatedBytes() == 0,
            "recovery reports 3 replayed records and no truncation");

        GlobalLedger.Txn again = recovered.commit("r1", "REQ|r1", "RES|r1|1|70", 9, 9);
        check(again.sequence == 1 && recovered.size() == 3 && recovered.sequence() == 3,
            "re-committing an existing id is idempotent and does not append a record");
        check(rejects(() -> recovered.commit("r1", "REQ|r1-DIFFERENT", "x", 4, 0), "conflict"),
            "same request id with a different frame is rejected as conflict");
        check(rejects(() -> recovered.commit("r4", "REQ|r4", "RES|r4", 3, 0), "revision"),
            "revision must advance monotonically");
        recovered.close();

        // A fresh open after a clean snapshot still sees the same durable answer.
        GlobalLedger afterSnapshot = GlobalLedger.open(dir);
        check(afterSnapshot.size() == 3 && afterSnapshot.revision() == 3
            && "RES|r1|1|70".equals(afterSnapshot.find("r1").replyFrame),
            "snapshot reload preserves idempotency table and revision");
        afterSnapshot.close();
    }

    // ---- GlobalLedger: torn WAL tail -------------------------------------------------------

    static void ledgerTornTail() throws Exception {
        Path dir = ROOT.resolve("torn");
        Files.createDirectories(dir);
        GlobalLedger writer = GlobalLedger.open(dir, Integer.MAX_VALUE);
        writer.commit("s1", "REQ|s1", "RES|s1|1|90", 1, 90);
        writer.commit("s2", "REQ|s2", "RES|s2|2|80", 2, 80);

        Path wal = dir.resolve("global.wal");
        // Torn length prefix: a record that says 50 bytes follow but the process died mid-write.
        Files.write(wal, new byte[]{0, 0, 0, 50, (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF},
            StandardOpenOption.APPEND);
        GlobalLedger recovered = GlobalLedger.open(dir, Integer.MAX_VALUE);
        check(recovered.size() == 2 && recovered.revision() == 2,
            "torn WAL tail is truncated without losing committed records");
        check(recovered.truncatedBytes() > 0, "torn tail bytes are reported");
        recovered.close();

        // Corrupt CRC on a fully-sized record must also be dropped, not trusted.
        Files.write(wal, new byte[]{0, 0, 0, 4, 0x11, 0x22, 0x33, 0x44, 1, 2, 3, 4},
            StandardOpenOption.APPEND);
        GlobalLedger afterCrc = GlobalLedger.open(dir, Integer.MAX_VALUE);
        check(afterCrc.size() == 2 && afterCrc.revision() == 2, "CRC-mismatched tail record is dropped");
        afterCrc.close();
    }

    // ---- GlobalLedger: snapshot compaction -------------------------------------------------

    static void ledgerSnapshot() throws Exception {
        Path dir = ROOT.resolve("snapshot");
        Files.createDirectories(dir);
        GlobalLedger ledger = GlobalLedger.open(dir, 4); // snapshot every 4 records
        ledger.commit("c1", "REQ|c1", "RES|c1|1|1", 1, 1);
        ledger.commit("c2", "REQ|c2", "RES|c2|2|2", 2, 2);
        ledger.commit("c3", "REQ|c3", "RES|c3|3|3", 3, 3);
        ledger.commit("c4", "REQ|c4", "RES|c4|4|4", 4, 4);
        check(Files.isRegularFile(dir.resolve("global.snapshot")), "snapshot is written after the interval");
        long walSize = Files.size(dir.resolve("global.wal"));
        check(walSize == 0, "WAL is truncated only after the snapshot is durable");
        ledger.close();

        GlobalLedger reopened = GlobalLedger.open(dir, 4);
        check(reopened.size() == 4 && reopened.revision() == 4 && reopened.replayedRecords() == 0,
            "snapshot alone restores the full idempotency table");
        reopened.commit("c5", "REQ|c5", "RES|c5|5|5", 5, 5);
        reopened.close();

        GlobalLedger finalOpen = GlobalLedger.open(dir, 4);
        check(finalOpen.size() == 5 && finalOpen.revision() == 5 && "RES|c5|5|5".equals(finalOpen.find("c5").replyFrame),
            "records written after a snapshot replay against the snapshot high-water mark");
        finalOpen.close();
    }

    // ---- GlobalLedger: fail closed on a corrupt snapshot -----------------------------------

    static void ledgerCorruptSnapshotFailsClosed() throws Exception {
        Path dir = ROOT.resolve("corrupt-snapshot");
        Files.createDirectories(dir);
        GlobalLedger ledger = GlobalLedger.open(dir);
        ledger.commit("x1", "REQ|x1", "RES|x1|1|1", 1, 1);
        ledger.close();

        Path snapshot = dir.resolve("global.snapshot");
        byte[] bytes = Files.readAllBytes(snapshot);
        bytes[bytes.length - 1] ^= 0x7F;
        Files.write(snapshot, bytes);
        check(rejectsIo(() -> GlobalLedger.open(dir)), "a corrupt snapshot fails closed instead of loading wrong state");
    }

    // ---- helpers ---------------------------------------------------------------------------

    interface Action { void run() throws Exception; }

    static boolean rejects(Action action, String reason) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException ex) {
            return reason.equals(ex.getMessage());
        } catch (Exception ex) {
            return false;
        }
    }

    interface IoAction { void run() throws IOException; }

    static boolean rejectsIo(IoAction action) {
        try {
            action.run();
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            });
        }
    }
}

package tests;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import sc.aco.SectorLeaseJournal;
import sc.aco.SectorOwnership;

/**
 * Durable sector-lease journal: atomic rewrite, exact field round-trip for all
 * eight lease fields, fail-closed load on any corrupt line, and the explicit
 * archiveCorrupt recovery path. A malformed line REFUSES the whole load; the
 * journal never silently drops records.
 */
public class SectorLeaseJournalTest {

    private interface Case { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("SectorLeaseJournalTest");
        Path root = Files.createTempDirectory("aco-journal-");
        List<String> failed = new ArrayList<>();
        try {
            run(failed, "missingFileLoadsEmpty", () -> missingFileLoadsEmpty(root.resolve("empty")));
            run(failed, "roundTripsExactLeaseFields", () -> roundTrips(root.resolve("round")));
            run(failed, "corruptLineRefusesWholeLoad", () -> corruptRefuses(root.resolve("corrupt")));
            run(failed, "archiveCorruptMovesAsideAndResets", () -> archiveResets(root.resolve("archive")));
        } finally {
            deleteRecursively(root);
        }
        if (!failed.isEmpty()) {
            System.out.println("[FAIL] " + failed.size() + " case(s) failed: " + failed);
            System.exit(1);
        }
    }

    private static void run(List<String> failed, String name, Case c) {
        try {
            c.run();
            System.out.println("[PASS] " + name);
        } catch (Throwable t) {
            failed.add(name);
            System.out.println("[FAIL] " + name + ": " + t);
            t.printStackTrace(System.out);
        }
    }

    private static void missingFileLoadsEmpty(Path dir) throws Exception {
        Files.createDirectories(dir);
        SectorLeaseJournal journal = new SectorLeaseJournal(dir);
        assert !Files.exists(journal.file()) : "no journal file before first save";
        assert journal.load().isEmpty() : "missing file must load as an empty lease set";
    }

    private static void roundTrips(Path dir) throws Exception {
        Files.createDirectories(dir);
        SectorLeaseJournal journal = new SectorLeaseJournal(dir);

        SectorOwnership held = new SectorOwnership("groundZero");
        held.kind = SectorOwnership.OwnerKind.HOST;
        held.state = SectorOwnership.LeaseState.ACTIVE;
        held.ownerSessionId = "sess-alpha";
        held.ownershipVersion = 3;
        held.revisionVersion = 2;
        held.grant = "grant-alpha";
        held.endpoint = "127.0.0.1:7777";

        SectorOwnership free = new SectorOwnership("repel-ing");
        free.kind = SectorOwnership.OwnerKind.NONE;
        free.state = SectorOwnership.LeaseState.FREE;
        free.ownerSessionId = null;
        free.ownershipVersion = 0;
        free.revisionVersion = 0;
        free.grant = null;
        free.endpoint = null;

        journal.save(List.of(held, free));
        assert Files.exists(journal.file()) : "journal file must exist after save";

        List<SectorOwnership> loaded = new SectorLeaseJournal(dir).load();
        assert loaded.size() == 2 : "two records must round-trip, got " + loaded.size();
        SectorOwnership a = loaded.get(0);
        SectorOwnership b = loaded.get(1);
        assert "groundZero".equals(a.sectorKey) : "records must be key-sorted, got " + a.sectorKey;
        assert "repel-ing".equals(b.sectorKey) : "records must be key-sorted, got " + b.sectorKey;
        assert a.kind == SectorOwnership.OwnerKind.HOST : "kind must round-trip";
        assert a.state == SectorOwnership.LeaseState.ACTIVE : "state must round-trip";
        assert "sess-alpha".equals(a.ownerSessionId) : "ownerSessionId must round-trip";
        assert a.ownershipVersion == 3 : "ownershipVersion must round-trip";
        assert a.revisionVersion == 2 : "revisionVersion must round-trip";
        assert "grant-alpha".equals(a.grant) : "grant must round-trip";
        assert "127.0.0.1:7777".equals(a.endpoint) : "endpoint must round-trip";
        assert b.kind == SectorOwnership.OwnerKind.NONE : "NONE kind must round-trip";
        assert b.state == SectorOwnership.LeaseState.FREE : "FREE state must round-trip";
        assert b.ownerSessionId == null : "absent ownerSession must stay null";
        assert b.grant == null : "absent grant must stay null";
        assert b.endpoint == null : "absent endpoint must stay null";
    }

    private static void corruptRefuses(Path dir) throws Exception {
        Files.createDirectories(dir);
        SectorLeaseJournal journal = new SectorLeaseJournal(dir);
        journal.save(List.of(simple("groundZero")));
        Files.writeString(journal.file(), "bogus|only|three|fields\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        try {
            journal.load();
            assert false : "a corrupt line must refuse the whole load";
        } catch (SectorLeaseJournal.LeaseJournalException e) {
            String msg = String.valueOf(e.getMessage());
            assert msg.contains("corrupt") : "refusal must name the corruption, got: " + msg;
        }
    }

    private static void archiveResets(Path dir) throws Exception {
        Files.createDirectories(dir);
        SectorLeaseJournal journal = new SectorLeaseJournal(dir);
        journal.save(List.of(simple("groundZero")));
        Files.writeString(journal.file(), "bogus|only|three|fields\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Path archived = journal.archiveCorrupt();
        assert archived != null && Files.exists(archived) : "archiveCorrupt must move the file aside";
        assert archived.getFileName().toString().startsWith(journal.file().getFileName().toString() + ".corrupt-")
                : "archive name must derive from the journal file, got " + archived.getFileName();
        assert !Files.exists(journal.file()) : "original journal must be gone after archive";
        assert new SectorLeaseJournal(dir).load().isEmpty() : "fresh journal must load empty";
    }

    private static SectorOwnership simple(String key) {
        SectorOwnership o = new SectorOwnership(key);
        o.kind = SectorOwnership.OwnerKind.NONE;
        o.state = SectorOwnership.LeaseState.FREE;
        o.ownerSessionId = null;
        o.grant = null;
        o.endpoint = null;
        return o;
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}

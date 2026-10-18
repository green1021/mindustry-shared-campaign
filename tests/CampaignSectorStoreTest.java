package tests;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import sc.aco.CampaignSectorStore;
import sc.aco.CampaignSectorStore.SnapshotInfo;
import sc.aco.SectorSnapshotCodec;

/**
 * Canonical sector snapshot store: exact-byte commits with SHA-256 + length
 * validation, idempotent re-send, refusal of checksum mismatches, revision
 * conflicts and stale revisions, lease grant markers, and latest-only pruning.
 * Cases run in sequence against one store and narrate a single revision story.
 */
public class CampaignSectorStoreTest {

    private static final String KEY = "groundZero";

    private interface Case { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("CampaignSectorStoreTest");
        Path root = Files.createTempDirectory("aco-store-");
        CampaignSectorStore store = new CampaignSectorStore(root, "campaign-a", CampaignSectorStore.STRUCTURAL_ONLY);
        List<String> failed = new ArrayList<>();
        try {
            run(failed, "commitThenReadExactBytes", () -> commitThenReadExactBytes(store));
            run(failed, "idempotentReSendReturnsExisting", () -> idempotentReSend(store));
            run(failed, "checksumMismatchRefused", () -> checksumMismatch(store));
            run(failed, "revisionConflictRefused", () -> revisionConflict(store));
            run(failed, "staleRevisionRefused", () -> staleRevision(store));
            run(failed, "leaseRoundTrip", () -> leaseRoundTrip(store));
            run(failed, "snapshotsPrunedToLatest", () -> pruned(store));
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

    private static void commitThenReadExactBytes(CampaignSectorStore store) {
        byte[] b1 = bytes("rev-one");
        SnapshotInfo info = store.commitSnapshot(KEY, 1, b1, sha(b1));
        assert info.revision() == 1 : "committed revision must be 1, got " + info.revision();
        assert info.length() == b1.length : "recorded length must match, got " + info.length();
        assert sha(b1).equals(info.sha()) : "recorded sha must match";
        assert store.latestRevision(KEY) == 1 : "latest must be 1, got " + store.latestRevision(KEY);
        byte[] read = store.readSnapshot(KEY);
        assert read != null && Arrays.equals(read, b1) : "read must return the exact committed bytes";
        assert store.info(KEY).revision() == 1 : "info must report the latest revision";
    }

    private static void idempotentReSend(CampaignSectorStore store) {
        byte[] b1 = bytes("rev-one");
        SnapshotInfo again = store.commitSnapshot(KEY, 1, b1, sha(b1));
        assert again.revision() == 1 && sha(b1).equals(again.sha())
                : "re-send of the same revision+bytes must return the existing snapshot";
        assert store.latestRevision(KEY) == 1 : "re-send must not advance state";
    }

    private static void checksumMismatch(CampaignSectorStore store) {
        byte[] b2 = bytes("rev-two");
        String wrong = "0".repeat(64);
        try {
            store.commitSnapshot(KEY, 2, b2, wrong);
            assert false : "a wrong claimed sha must be refused";
        } catch (SectorSnapshotCodec.SnapshotRejected e) {
            assert String.valueOf(e.getMessage()).contains("CHECKSUM_MISMATCH")
                    : "unexpected refusal: " + e.getMessage();
        }
        assert store.latestRevision(KEY) == 1 : "refused commit must not advance state";
    }

    private static void revisionConflict(CampaignSectorStore store) {
        byte[] b2 = bytes("rev-two");
        store.commitSnapshot(KEY, 2, b2, sha(b2));
        assert store.latestRevision(KEY) == 2 : "commit of rev 2 must advance";
        byte[] divergent = bytes("rev-two-divergent");
        try {
            store.commitSnapshot(KEY, 2, divergent, sha(divergent));
            assert false : "same revision with different bytes must be refused";
        } catch (SectorSnapshotCodec.SnapshotRejected e) {
            assert String.valueOf(e.getMessage()).contains("REVISION_CONFLICT")
                    : "unexpected refusal: " + e.getMessage();
        }
        assert store.latestRevision(KEY) == 2 && Arrays.equals(store.readSnapshot(KEY), b2)
                : "conflicting commit must not replace the stored snapshot";
    }

    private static void staleRevision(CampaignSectorStore store) {
        byte[] b1 = bytes("rev-one");
        try {
            store.commitSnapshot(KEY, 1, b1, sha(b1));
            assert false : "a stale revision must be refused";
        } catch (SectorSnapshotCodec.SnapshotRejected e) {
            assert String.valueOf(e.getMessage()).contains("STALE_REVISION")
                    : "unexpected refusal: " + e.getMessage();
        }
    }

    private static void leaseRoundTrip(CampaignSectorStore store) throws Exception {
        assert store.readLease(KEY) == null : "no lease before write";
        store.writeLease(KEY, "grant-x");
        assert "grant-x".equals(store.readLease(KEY)) : "lease must round-trip";
        store.writeLease(KEY, "grant-y");
        assert "grant-y".equals(store.readLease(KEY)) : "lease rewrite must replace the grant";
        store.clearLease(KEY);
        assert store.readLease(KEY) == null : "cleared lease must read null";
    }

    private static void pruned(CampaignSectorStore store) {
        Path dir = store.sectorDir(KEY);
        assert Files.exists(dir.resolve("snapshot.r2.msav")) : "latest snapshot file must exist";
        assert !Files.exists(dir.resolve("snapshot.r1.msav")) : "older snapshot must be pruned";
        assert store.latestRevision(KEY) == 2 : "latest must be 2";
    }

    private static byte[] bytes(String tag) {
        return ("STORE|" + tag).getBytes(StandardCharsets.UTF_8);
    }

    private static String sha(byte[] data) {
        return SectorSnapshotCodec.sha256(data);
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}

package tests;

import sc.aco.AcoController;
import sc.aco.GlobalResourceRequest;
import sc.aco.SectorRuntime;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression for the broadcast-desync defect documented in
 * {@code reports/2026-10-17-broadcast-desync.md}.
 *
 * <p>{@code AcoServer}'s resource / research / completion handlers answer the
 * requester <em>and</em> push an unsolicited {@code GLOBAL_STATE_SYNC} to every
 * session. A client that reads exactly one frame per request consumes the queued
 * broadcast as the response to its <em>next</em> request. The first probe run
 * showed a completion request being handed the campaign JSON snapshot instead of
 * {@code LOCKED|FAIL|0|}.
 *
 * <p>These tests assert the response a client gets back is the response to the
 * request it actually sent, across a broadcast-generating request and the request
 * that follows it.
 */
public class AcoBroadcastDesyncTest {

    /**
     * A sector a fresh campaign deterministically refuses. Only the starting
     * sector is granted on a new campaign (see
     * {@code GlobalCampaignState.unlockStartingSectors()}), so completing this one
     * always answers {@code LOCKED|FAIL|0|} regardless of progression. Pinning the
     * locked assertions here keeps this regression purely about frame alignment,
     * independent of the unlock seed.
     */
    private static final String LOCKED_SECTOR = "craters";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("AcoBroadcastDesyncTest");
        System.setProperty("arc.headless", "true");

        run("RequestAfterBroadcastIsNotDesynced", AcoBroadcastDesyncTest::testRequestAfterBroadcast);
        run("CompletionRequestNeverYieldsStateSync", AcoBroadcastDesyncTest::testNeverYieldsStateSync);
        run("InterleavedBroadcastsStayCorrelated", AcoBroadcastDesyncTest::testInterleavedBroadcastsStayCorrelated);
        run("RepeatedRequestsStayAligned", AcoBroadcastDesyncTest::testRepeatedRequestsStayAligned);

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

    private static final class StubRuntime implements SectorRuntime {
        final List<String> initialized = new ArrayList<>();

        @Override
        public boolean initializeSector(String sectorKey, String revision) {
            initialized.add(sectorKey + "@" + revision);
            return true;
        }

        @Override
        public boolean startP2(int port) {
            return true;
        }

        @Override
        public void stopP2() {
        }
    }

    private static final class Pair implements AutoCloseable {
        final AcoController host;
        final AcoController client;
        final int syncPort;
        private final Path hostDir;
        private final Path clientDir;

        Pair() throws Exception {
            syncPort = freePort();
            hostDir = Files.createTempDirectory("aco-desync-host-");
            clientDir = Files.createTempDirectory("aco-desync-client-");

            host = new AcoController(hostDir.toString());
            if (!host.hostCampaign(syncPort, syncPort + 1)) {
                throw new IllegalStateException("hostCampaign returned false");
            }
            Thread.sleep(200);

            client = new AcoController(clientDir.toString());
            if (!client.connectToAuthority("127.0.0.1", syncPort)) {
                throw new IllegalStateException("client handshake failed");
            }
        }

        @Override
        public void close() throws Exception {
            client.dispose();
            host.dispose();
            deleteRecursively(hostDir);
            deleteRecursively(clientDir);
        }
    }

    /** Transaction ids are opaque strings here; uniqueness makes retries visible. */
    private static String credit(Pair p, String opId, long amount) {
        return credit(p, opId, 0, amount);
    }

    /** Same, with the caller's expected global revision for chained requests. */
    private static String credit(Pair p, String opId, long expectedRevision, long amount) {
        // No session identity: the server binds it from the connection.
        return p.client.requestResource(opId, "groundZero", expectedRevision, "copper", amount,
            GlobalResourceRequest.OperationType.CREDIT);
    }

    private static void testRequestAfterBroadcast() throws Exception {
        try (Pair p = new Pair()) {
            String r1 = credit(p, "op-1", 100);
            assert r1 != null : "resource request must get a response";
            assert r1.startsWith("op-1|true|") : "resource request should succeed, got " + r1;

            // The handler above broadcast GLOBAL_STATE_SYNC. The next request must
            // still receive its own response, not the queued broadcast.
            String r2 = p.client.requestSectorCompletion(LOCKED_SECTOR, "op-2");
            assert r2 != null : "completion request must get a response";
            assert !r2.startsWith("{") : "completion response was the state-sync JSON: " + r2;
            assert r2.equals("LOCKED|FAIL|0|") : "locked sector must refuse completion, got " + r2;
        }
    }

    /** The JSON snapshot must never be mistaken for a protocol response. */
    private static void testNeverYieldsStateSync() throws Exception {
        try (Pair p = new Pair()) {
            credit(p, "op-1", 50);
            credit(p, "op-2", 25);
            String r3 = p.client.requestSectorCompletion(LOCKED_SECTOR, "op-3");
            assert r3 != null && !r3.contains("\"campaignId\"")
                : "state-sync JSON leaked into a response slot: " + r3;
        }
    }

    /**
     * Regression for broadcast/response interleaving: a state-changing request
     * emits an unsolicited GLOBAL_STATE_SYNC to the same socket, and the next
     * request must still receive exactly its own response, in order, with the
     * client's shared view caught up. (Replaces the old skipped-frame counter
     * test: the framed transport no longer has a "skipped frame" concept; the
     * observable invariant is strict one-to-one correlation.)
     */
    private static void testInterleavedBroadcastsStayCorrelated() throws Exception {
        try (Pair p = new Pair()) {
            String r1 = credit(p, "op-1", 100);
            assert r1 != null : "resource request must get a response";
            assert r1.startsWith("op-1|true|") : "resource request should succeed, got " + r1;
            long revision = Long.parseLong(r1.split("\\|", -1)[3]);

            String r2 = p.client.requestSectorCompletion(LOCKED_SECTOR, "op-2");
            assert r2 != null : "completion request must get a response";
            assert r2.equals("LOCKED|FAIL|0|") : "the interleaved broadcast must not leak into the response slot: " + r2;

            // Chain the revision the first credit advanced to: the campaign is at
            // revision 1 now, and a request still claiming 0 is legitimately stale.
            String r3 = credit(p, "op-3", revision, 50);
            assert r3 != null : "credit after the interleaved broadcast must get a response";
            assert r3.startsWith("op-3|true|") : "response must correlate to its own op, got " + r3;
            assert Long.parseLong(r3.split("\\|", -1)[3]) > revision
                : "the client view must include the credit it just made";
        }
    }

    /** Alternating request kinds must not drift by even one frame. */
    private static void testRepeatedRequestsStayAligned() throws Exception {
        try (Pair p = new Pair()) {
            long revision = 0;
            for (int i = 0; i < 4; i++) {
                String op = "op-" + i;
                String res = p.client.requestResource(op, "groundZero", revision, "copper", 10,
                    GlobalResourceRequest.OperationType.CREDIT);
                assert res != null : "round " + i + " resource response was null";
                assert res.startsWith(op + "|true|") : "round " + i + " resource response: " + res;
                // Chain the revision: each successful credit advances it, and the
                // next request must still see that advance in its own response.
                revision = Long.parseLong(res.split("\\|", -1)[3]);

                // Distinct id: AcoServer dedupes completions by operation id, and a
                // reused id would be answered from the seen-set rather than by
                // evaluating the request.
                String done = p.client.requestSectorCompletion(LOCKED_SECTOR, "done-" + i);
                assert done != null : "round " + i + " completion response was null";
                assert done.equals("LOCKED|FAIL|0|") : "round " + i + " completion response: " + done;
            }
            assert revision == 4 : "four credits should reach revision 4, got " + revision;
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}

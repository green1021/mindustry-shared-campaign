package tests;

import sc.aco.AcoController;
import sc.aco.SectorRuntime;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 9A vertical slice, driven end to end over a real TCP socket.
 *
 * <p>A real authority ({@code AcoController.hostCampaign}) runs on localhost with a
 * stub {@link SectorRuntime} substituted for the game runtime, and a real client
 * controller performs the claim -> join -> release lifecycle through the same
 * {@code roundTrip} path the UI wiring uses.
 *
 * <p>Scope: this verifies the ACO control plane (framing, routing, ownership
 * bookkeeping, endpoint advertisement) in isolation. The production
 * {@link sc.aco.SectorRuntimeAdapter} it stands in for actually binds a Mindustry
 * {@code NetServer} and loads a sector world, which needs a live game runtime and
 * remains ENVIRONMENT-BLOCKED on this headless host. Nothing here should be read
 * as evidence that a real P2 sector host works.
 */
public class AcoVerticalSliceTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("AcoVerticalSliceTest");
        System.setProperty("arc.headless", "true");

        run("ClaimGrantsOwnershipAndDrivesRuntime", AcoVerticalSliceTest::testClaimGrantsOwnership);
        run("JoinResolvesClaimedEndpoint", AcoVerticalSliceTest::testJoinResolvesEndpoint);
        run("UnclaimedJoinRejected", AcoVerticalSliceTest::testUnclaimedJoinRejected);
        run("ReleaseFreesSector", AcoVerticalSliceTest::testReleaseFreesSector);
        run("StaleReleaseRejected", AcoVerticalSliceTest::testStaleReleaseRejected);
        run("SecondClaimOnHeldSectorRejected", AcoVerticalSliceTest::testSecondClaimRejected);

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

    /** Records what the authority asked the (substituted) game runtime to do. */
    private static final class StubRuntime implements SectorRuntime {
        final List<String> initialized = new ArrayList<>();
        final List<Integer> p2Starts = new ArrayList<>();

        @Override
        public boolean initializeSector(String sectorKey, String revision) {
            initialized.add(sectorKey + "@" + revision);
            return true;
        }

        @Override
        public boolean startP2(int port) {
            p2Starts.add(port);
            return true;
        }

        @Override
        public void stopP2() {
        }
    }

    /** Live authority + connected client over a real localhost socket. */
    private static final class Pair implements AutoCloseable {
        final AcoController host;
        final AcoController client;
        final StubRuntime stub = new StubRuntime();
        final int syncPort;
        final int sectorPort;
        private final Path hostDir;
        private final Path clientDir;

        Pair() throws Exception {
            syncPort = freePort();
            sectorPort = syncPort + 1;
            hostDir = Files.createTempDirectory("aco-slice-host-");
            clientDir = Files.createTempDirectory("aco-slice-client-");

            host = new AcoController(hostDir.toString());
            host.setSectorRuntime(stub);
            if (!host.hostCampaign(syncPort, sectorPort)) {
                throw new IllegalStateException("hostCampaign returned false");
            }
            Thread.sleep(200); // let the accept loop bind

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

    private static void testClaimGrantsOwnership() throws Exception {
        try (Pair p = new Pair()) {
            String resp = p.client.requestClaim("groundZero", "sess-A", "dev-1", 7);
            assert resp != null : "claim must get a response";
            String[] f = resp.split("\\|", -1);
            assert f.length == 5 : "claim response field count, got '" + resp + "'";
            assert f[0].equals("groundZero") : "sectorKey echoed, got " + f[0];
            assert f[1].equals("sess-A") : "sessionId echoed, got " + f[1];
            assert f[2].equals("OK") : "claim should succeed, got " + resp;
            assert Long.parseLong(f[3]) == 1 : "first claim should yield ownershipVersion 1, got " + f[3];
            assert Long.parseLong(f[4]) == 7 : "revisionVersion should echo the request, got " + f[4];

            // The authority must have driven the game runtime: initialize the sector
            // revision, then start P2 on the sector port passed to hostCampaign.
            assert p.stub.initialized.equals(List.of("groundZero@7"))
                : "runtime should initialize the requested sector revision, got " + p.stub.initialized;
            assert p.stub.p2Starts.equals(List.of(p.sectorPort))
                : "runtime should start P2 on " + p.sectorPort + ", got " + p.stub.p2Starts;

            assert p.host.isHosting() : "authority should report hosting after a successful claim";
        }
    }

    private static void testJoinResolvesEndpoint() throws Exception {
        try (Pair p = new Pair()) {
            p.client.requestClaim("groundZero", "sess-A", "dev-1", 3);

            String resp = p.client.requestJoin("groundZero");
            assert resp != null : "join must get a response";
            String[] f = resp.split("\\|", -1);
            assert f.length == 3 : "join response field count, got '" + resp + "'";
            assert f[0].equals("groundZero") : "sectorKey echoed, got " + f[0];
            assert f[1].equals("127.0.0.1:" + p.sectorPort)
                : "join should resolve the advertised P2 endpoint, got " + f[1];
            assert Long.parseLong(f[2]) == 1 : "join should report ownershipVersion 1, got " + f[2];
        }
    }

    private static void testUnclaimedJoinRejected() throws Exception {
        try (Pair p = new Pair()) {
            String resp = p.client.requestJoin("neverClaimed");
            assert resp != null : "join must get a response";
            assert resp.startsWith("FAIL") : "join on unclaimed sector must fail, got " + resp;
        }
    }

    private static void testReleaseFreesSector() throws Exception {
        try (Pair p = new Pair()) {
            String claim = p.client.requestClaim("groundZero", "sess-A", "dev-1", 2);
            long version = Long.parseLong(claim.split("\\|", -1)[3]);

            String resp = p.client.requestRelease("groundZero", "sess-A", version);
            assert resp != null : "release must get a response";
            String[] f = resp.split("\\|", -1);
            assert f[2].equals("OK") : "release by owner should succeed, got " + resp;
            assert Long.parseLong(f[3]) == version + 1
                : "release should bump ownershipVersion to " + (version + 1) + ", got " + f[3];

            // A released sector must no longer resolve to a host.
            String join = p.client.requestJoin("groundZero");
            assert join != null && join.startsWith("FAIL")
                : "join after release must fail, got " + join;
        }
    }

    private static void testStaleReleaseRejected() throws Exception {
        try (Pair p = new Pair()) {
            p.client.requestClaim("groundZero", "sess-A", "dev-1", 1);

            String resp = p.client.requestRelease("groundZero", "sess-A", 999L);
            assert resp != null : "release must get a response";
            assert resp.contains("FAIL") : "stale ownershipVersion must be rejected, got " + resp;

            // The rejected release must not have freed the sector.
            String join = p.client.requestJoin("groundZero");
            assert join != null && !join.startsWith("FAIL")
                : "sector must still be held after a rejected release, got " + join;
        }
    }

    private static void testSecondClaimRejected() throws Exception {
        try (Pair p = new Pair()) {
            String first = p.client.requestClaim("groundZero", "sess-A", "dev-1", 1);
            assert first.contains("|OK|") : "first claim should succeed, got " + first;

            String second = p.client.requestClaim("groundZero", "sess-B", "dev-2", 2);
            assert second != null : "second claim must get a response";
            assert second.contains("FAIL") : "claim on a held sector must be rejected, got " + second;

            // The original owner must be untouched by the rejected claim.
            String join = p.client.requestJoin("groundZero");
            assert join != null && !join.startsWith("FAIL")
                : "sector should still resolve after a rejected claim, got " + join;
            assert join.split("\\|", -1)[2].equals("1")
                : "ownershipVersion must be unchanged by the rejected claim, got " + join;
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

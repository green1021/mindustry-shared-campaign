package tests;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import sc.aco.AcoController;
import sc.aco.CampaignSectorStore;
import sc.aco.GlobalCampaignState;
import sc.aco.SectorHandoffManager;
import sc.aco.SectorOwnership;
import sc.aco.SectorWorldOps;

/**
 * MVP vertical slice over real control-plane TCP: one authority plus two client
 * controllers (three real loopback connections in ONE JVM) exercise claim,
 * exclusive ownership, durable checkpointing, handoff abort on a failed save,
 * full takeover with exact-snapshot load, a failed host load parked as
 * RESERVED, an operator retry, the global-state broadcast, and the
 * claim-never-loads-the-host-world boundary.
 *
 * <p>Honesty boundary: the world/runtime halves are STUBS
 * ({@link SectorWorldOps} on clients, {@link SectorHandoffManager.HostRuntimeOps}
 * on the host). This proves the control plane and the ownership/persistence
 * orchestration over real sockets; it is NOT evidence that a real sector runs
 * on a client machine's game runtime, and it is NOT a two-JVM/two-machine test.
 * The production runtime halves are covered separately by SectorLoadTest and
 * P2ServerBindTest.
 *
 * <p>Architecture boundary under test: in production,
 * {@code HostRuntimeOps.loadSnapshot} has exactly one caller
 * (SectorHandoffManager.hostLoad, reachable only through an accepted handoff
 * after snapshot commit + client release). This suite asserts the observable
 * consequence with counters: accepting a client claim adds zero host loads,
 * even while the host is already serving another sector.
 */
public class AcoVerticalSliceTest {

    private static final String KEY = "groundZero";
    private static final String KEY2 = "craters";
    private static final int P2_PORT = 47811;

    /** Host load attempts before the first handoff request; must stay 0. */
    private static long hostLoadsBeforeFirstHandoff = -1;

    private interface Case { void run() throws Exception; }

    private interface Cond { boolean ok(); }

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("AcoVerticalSliceTest");
        Path root = Files.createTempDirectory("aco-slice-");
        Fixture f = new Fixture(root);
        List<String> failed = new ArrayList<>();
        try {
            run(failed, "clientClaimStartsLocalSector", f::clientClaim);
            run(failed, "secondClientClaimRefused", f::secondClaimRefused);
            run(failed, "checkpointCommitsExactSnapshot", f::checkpoint);
            run(failed, "handoffFailedSaveKeepsOwnership", f::failedSaveHandoff);
            run(failed, "handoffServesExactSnapshot", f::successfulHandoff);
            run(failed, "joinResolvesHostEndpoint", f::joinResolves);
            run(failed, "completionSyncUnlocksCraters", f::completionSync);
            run(failed, "claimAfterUnlockNeverLoadsHostWorld", f::claimCraters);
            run(failed, "hostLoadFailureParksReservedLease", f::failedHostLoad);
            run(failed, "retryHostServesExactSnapshot", f::retryHost);
        } finally {
            f.close();
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

    private static boolean waitFor(Cond cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean ok = cond.ok();
        while (!ok && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            ok = cond.ok();
        }
        return ok;
    }

    static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    static byte[] snapshotBytes(String key, long revision) {
        return ("SLICE-SNAPSHOT|" + key + "|r" + revision).getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ fixture

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final AcoController host;
        final AcoController clientA;
        final AcoController clientB;
        final SliceWorld worldA;
        final SliceWorld worldB;
        final HostWorld hostWorld;
        final int syncPort;
        long versionAfterClaim = -1;

        Fixture(Path root) throws Exception {
            this.root = root;
            this.syncPort = freePort();

            this.host = new AcoController(root.resolve("host").toString());
            this.hostWorld = new HostWorld(host);
            host.setHostRuntimeOps(hostWorld); // must precede hostCampaign: the manager captures it at construction
            assert host.hostCampaign(syncPort, syncPort + 1) : "host campaign must start";

            this.clientA = new AcoController(root.resolve("client-a").toString());
            assert clientA.connectToAuthority("127.0.0.1", syncPort) : "client A must connect";
            this.worldA = new SliceWorld(clientA.getSectorStore());
            clientA.setLocalWorldOps(worldA);

            this.clientB = new AcoController(root.resolve("client-b").toString());
            assert clientB.connectToAuthority("127.0.0.1", syncPort) : "client B must connect";
            this.worldB = new SliceWorld(clientB.getSectorStore());
            clientB.setLocalWorldOps(worldB);
        }

        /** Claim succeeds and loads ONLY the client's own world; host stays untouched. */
        void clientClaim() {
            String err = clientA.claimLocalSector(KEY, 0);
            assert err == null : "claim must succeed, got: " + err;
            assert worldA.loadCount(KEY) == 1 : "client A must load the sector locally, loads=" + worldA.loadCount(KEY);
            SectorOwnership own = host.getRegistry().get(KEY);
            assert own != null : "authority must record a lease";
            assert own.state == SectorOwnership.LeaseState.ACTIVE : "lease must be ACTIVE, got " + own.state;
            assert own.kind == SectorOwnership.OwnerKind.CLIENT : "lease must be CLIENT-owned, got " + own.kind;
            assert own.ownerSessionId != null && !own.ownerSessionId.isEmpty() : "owner session must be recorded";
            versionAfterClaim = own.ownershipVersion;
            assert clientA.localSession() != null && clientA.localSession().isActive() : "client session must be active";
            assert hostWorld.totalLoads() == 0 : "host must not load any world for a client claim";
            assert worldB.loadCount(KEY) == 0 : "client B must not load anything";
        }

        /** A second claimant is refused; the first lease is untouched. */
        void secondClaimRefused() {
            String err = clientB.claimLocalSector(KEY, 0);
            assert "SECTOR_NOT_CLAIMABLE".equals(err) : "second claim must be refused, got: " + err;
            assert worldB.loadCount(KEY) == 0 : "refused claimant must not load a world";
            SectorOwnership own = host.getRegistry().get(KEY);
            assert own != null && own.state == SectorOwnership.LeaseState.ACTIVE : "original lease must stand";
            assert own.kind == SectorOwnership.OwnerKind.CLIENT : "original lease must stay CLIENT-owned";
            assert own.ownershipVersion == versionAfterClaim : "ownership version must not change on refusal";
            assert clientB.localSession() == null || !clientB.localSession().isActive() : "B session must not be active";
        }

        /** Checkpoint commits exactly the bytes the client saved, at rev 1. */
        void checkpoint() {
            String err = clientA.checkpointLocalSector();
            assert err == null : "checkpoint must commit, got: " + err;
            CampaignSectorStore store = host.getSectorStore();
            assert store.latestRevision(KEY) == 1 : "authority must hold revision 1, got " + store.latestRevision(KEY);
            byte[] stored = store.readSnapshot(KEY);
            assert stored != null && Arrays.equals(stored, worldA.bytesFor(KEY, 1))
                : "stored snapshot must equal the exact bytes the client saved";
            assert worldA.saveCount(KEY) == 1 : "one save so far, got " + worldA.saveCount(KEY);
        }

        /** A failed save aborts the handoff and the client keeps ownership. */
        void failedSaveHandoff() {
            hostLoadsBeforeFirstHandoff = hostWorld.totalLoads();
            worldA.failNextSave = true;
            String err = host.requestHandoff(KEY);
            assert err == null : "handoff must start, got: " + err;
            assert waitFor(() -> "ABORTED".equals(host.handoffPhase(KEY)), 8000)
                : "handoff must abort, phase=" + host.handoffPhase(KEY) + " failure=" + host.handoffFailure(KEY);
            assert "SAVE_FAILED".equals(host.handoffFailure(KEY))
                : "failure must be SAVE_FAILED, got " + host.handoffFailure(KEY);
            SectorOwnership own = host.getRegistry().get(KEY);
            assert own != null && own.state == SectorOwnership.LeaseState.ACTIVE
                : "client must keep the lease after a failed save, got " + (own == null ? null : own.state);
            assert own.kind == SectorOwnership.OwnerKind.CLIENT : "lease must stay CLIENT-owned";
            assert own.ownershipVersion == versionAfterClaim : "ownership version must be unchanged";
            assert clientA.localSession() != null && clientA.localSession().isActive()
                : "client must keep simulating after the abort";
            assert worldA.lefts == 0 : "client must not leave its world (lefts=" + worldA.lefts + ")";
            assert worldA.saveCount(KEY) == 2 : "failed save still counts one attempt, got " + worldA.saveCount(KEY);
            assert hostWorld.totalLoads() == 0 : "host must not load after a failed save";
        }

        /** Full takeover: host loads exactly the committed rev-2 snapshot and serves it. */
        void successfulHandoff() {
            String err = host.requestHandoff(KEY);
            assert err == null : "handoff must start, got: " + err;
            assert waitFor(() -> "SERVING".equals(host.handoffPhase(KEY)), 8000)
                : "handoff must complete, phase=" + host.handoffPhase(KEY) + " failure=" + host.handoffFailure(KEY);
            CampaignSectorStore store = host.getSectorStore();
            byte[] committed = store.readSnapshot(KEY);
            assert committed != null && Arrays.equals(hostWorld.lastLoaded(KEY), committed)
                : "host must load exactly the committed snapshot bytes";
            assert Arrays.equals(committed, worldA.bytesFor(KEY, 2))
                : "committed snapshot must equal the client's saved rev-2 bytes";
            SectorOwnership own = host.getRegistry().get(KEY);
            assert own != null && own.kind == SectorOwnership.OwnerKind.HOST : "lease must be HOST-owned";
            assert own.state == SectorOwnership.LeaseState.ACTIVE : "host lease must be ACTIVE";
            assert own.endpoint != null : "host endpoint must be recorded";
            assert own.ownershipVersion != versionAfterClaim : "ownership must wrap to a new version";
            assert clientA.localSession() == null || !clientA.localSession().isActive()
                : "client session must no longer be active";
            assert worldA.lefts == 1 : "client must have left its world once, lefts=" + worldA.lefts;
            assert worldA.saveCount(KEY) == 3 : "checkpoint + failed + final saves, got " + worldA.saveCount(KEY);
            assert hostWorld.loadCount(KEY) == 1 : "host must load exactly once, got " + hostWorld.loadCount(KEY);
        }

        /** Another client resolves the host's advertised endpoint through join. */
        void joinResolves() {
            String resp = clientB.requestJoin(KEY);
            assert resp != null : "join must get a response";
            assert resp.startsWith(KEY + "|OK|") : "join must resolve OK, got: " + resp;
            String[] parts = resp.split("\\|", -1);
            assert parts.length >= 3 && parts[2].equals(hostWorld.endpointFor(KEY))
                : "join endpoint must be the host's P2 endpoint, got: " + resp;
        }

        /** Completing the starting sector broadcasts a sync that unlocks craters. */
        void completionSync() {
            String resp = clientB.requestSectorCompletion(KEY, "slice-op-1");
            assert resp != null && resp.contains("SUCCESS") : "completion must succeed, got: " + resp;
            assert waitFor(() -> {
                GlobalCampaignState view = clientA.getGlobalState();
                return view != null && view.completedSectors.contains(KEY)
                        && view.unlockedSectors.contains(KEY2) && view.globalRevision >= 1;
            }, 5000) : "connected client must receive the global-state sync (unlocks " + KEY2 + ")";
        }

        /** Boundary: a claim for craters loads only the client world — host loads unchanged. */
        void claimCraters() {
            long loadsBefore = hostWorld.totalLoads();
            String err = clientA.claimLocalSector(KEY2, 0);
            assert err == null : "claim of the unlocked sector must succeed, got: " + err;
            assert hostWorld.totalLoads() == loadsBefore
                : "a client claim must never trigger a host world load (before=" + loadsBefore
                        + " after=" + hostWorld.totalLoads() + ")";
            assert hostLoadsBeforeFirstHandoff == 0
                : "host must have loaded no world before the first handoff, was " + hostLoadsBeforeFirstHandoff;
            assert worldA.loadCount(KEY2) == 1 : "client A must load craters locally, loads=" + worldA.loadCount(KEY2);
            SectorOwnership own = host.getRegistry().get(KEY2);
            assert own != null && own.kind == SectorOwnership.OwnerKind.CLIENT
                    && own.state == SectorOwnership.LeaseState.ACTIVE : "craters lease must be CLIENT-owned";
        }

        /** A host-side load failure parks the lease RESERVED and reports HOST_LOAD_FAILED. */
        void failedHostLoad() {
            hostWorld.failNextLoad = true;
            String err = host.requestHandoff(KEY2);
            assert err == null : "handoff must start, got: " + err;
            assert waitFor(() -> "FAILED".equals(host.handoffPhase(KEY2)), 8000)
                : "host load failure must mark FAILED, phase=" + host.handoffPhase(KEY2);
            assert "HOST_LOAD_FAILED".equals(host.handoffFailure(KEY2))
                : "failure must be HOST_LOAD_FAILED, got " + host.handoffFailure(KEY2);
            SectorOwnership own = host.getRegistry().get(KEY2);
            assert own != null && own.state == SectorOwnership.LeaseState.RESERVED
                : "failed load must park the lease RESERVED, got " + (own == null ? null : own.state);
            assert own.kind == SectorOwnership.OwnerKind.HOST : "parked lease must stay HOST-kind";
            assert hostWorld.loadCount(KEY2) == 1 : "exactly one host load attempt, got " + hostWorld.loadCount(KEY2);
            assert !clientA.localSession().isActive() : "client side finished before the host load failed";
            assert worldA.lefts == 2 : "client left both handoff worlds, lefts=" + worldA.lefts;
        }

        /** Operator retry serves the parked lease and loads the exact snapshot. */
        void retryHost() {
            String err = host.retryHandoff(KEY2);
            assert err == null : "retry must serve, got: " + err;
            assert waitFor(() -> "SERVING".equals(host.handoffPhase(KEY2)), 8000)
                : "retry must reach SERVING, phase=" + host.handoffPhase(KEY2) + " failure=" + host.handoffFailure(KEY2);
            CampaignSectorStore store = host.getSectorStore();
            byte[] committed = store.readSnapshot(KEY2);
            assert committed != null && Arrays.equals(hostWorld.lastLoaded(KEY2), committed)
                : "host must load exactly the committed snapshot bytes";
            assert Arrays.equals(committed, worldA.bytesFor(KEY2, 1))
                : "committed snapshot must equal the client's saved rev-1 bytes";
            assert hostWorld.loadCount(KEY2) == 2 : "two host load attempts total, got " + hostWorld.loadCount(KEY2);
            SectorOwnership own = host.getRegistry().get(KEY2);
            assert own != null && own.kind == SectorOwnership.OwnerKind.HOST
                    && own.state == SectorOwnership.LeaseState.ACTIVE : "craters lease must be HOST-owned after retry";
            assert own.endpoint != null : "host endpoint must be recorded after serving";
            assert worldA.saveCount(KEY2) == 1 : "client saved craters exactly once, got " + worldA.saveCount(KEY2);
        }

        @Override
        public void close() {
            try { clientA.dispose(); } catch (Throwable ignored) { }
            try { clientB.dispose(); } catch (Throwable ignored) { }
            try { host.dispose(); } catch (Throwable ignored) { }
            deleteRecursively(root);
        }
    }

    // -------------------------------------------------------------- stub world

    /** Client-side stub: records calls and commits real bytes through the real store. */
    private static final class SliceWorld implements SectorWorldOps {
        final CampaignSectorStore store;
        final Map<String, Integer> loads = new HashMap<>();
        final Map<String, Integer> saves = new HashMap<>();
        final Map<String, Map<Long, byte[]>> savedBytes = new HashMap<>();
        int lefts = 0;
        boolean failNextSave = false;

        SliceWorld(CampaignSectorStore store) {
            this.store = store;
        }

        synchronized int loadCount(String key) {
            Integer n = loads.get(key);
            return n == null ? 0 : n;
        }

        synchronized int saveCount(String key) {
            Integer n = saves.get(key);
            return n == null ? 0 : n;
        }

        synchronized byte[] bytesFor(String key, long revision) {
            Map<Long, byte[]> m = savedBytes.get(key);
            byte[] b = m == null ? null : m.get(revision);
            assert b != null : "no saved bytes recorded for " + key + " rev " + revision;
            return b;
        }

        @Override
        public synchronized boolean loadWorld(String sectorKey, boolean allowBootstrap) {
            loads.merge(sectorKey, 1, Integer::sum);
            return true;
        }

        @Override
        public synchronized boolean saveWorld(String sectorKey, long revision) {
            saves.merge(sectorKey, 1, Integer::sum);
            if (failNextSave) {
                failNextSave = false;
                return false;
            }
            byte[] bytes = snapshotBytes(sectorKey, revision);
            try {
                store.commitSnapshot(sectorKey, revision, bytes, sha256(bytes));
            } catch (RuntimeException e) {
                System.out.println("  [stub] saveWorld commit refused: " + e.getMessage());
                return false;
            }
            savedBytes.computeIfAbsent(sectorKey, k -> new HashMap<>()).put(revision, bytes);
            return true;
        }

        @Override
        public synchronized boolean leaveWorld() {
            lefts++;
            return true;
        }
    }

    /** Host-side stub: counts load attempts, records the bytes it was asked to load. */
    private static final class HostWorld implements SectorHandoffManager.HostRuntimeOps {
        final AcoController controller;
        final Map<String, Integer> loads = new HashMap<>();
        final Map<String, byte[]> lastLoaded = new HashMap<>();
        final Map<String, String> endpoints = new HashMap<>();
        volatile boolean failNextLoad = false;

        HostWorld(AcoController controller) {
            this.controller = controller;
        }

        synchronized int loadCount(String key) {
            Integer n = loads.get(key);
            return n == null ? 0 : n;
        }

        synchronized long totalLoads() {
            long total = 0;
            for (Integer n : loads.values()) total += n;
            return total;
        }

        synchronized byte[] lastLoaded(String key) {
            return lastLoaded.get(key);
        }

        synchronized String endpointFor(String key) {
            return endpoints.get(key);
        }

        @Override
        public synchronized boolean loadSnapshot(String sectorKey) throws Exception {
            loads.merge(sectorKey, 1, Integer::sum);
            CampaignSectorStore store = controller.getSectorStore();
            byte[] bytes = store == null ? null : store.readSnapshot(sectorKey);
            lastLoaded.put(sectorKey, bytes);
            if (failNextLoad) {
                failNextLoad = false;
                return false;
            }
            return bytes != null;
        }

        @Override
        public synchronized String startServer(String sectorKey) {
            String endpoint = "127.0.0.1:" + P2_PORT;
            endpoints.put(sectorKey, endpoint);
            return endpoint;
        }

        @Override
        public synchronized void stopServer(String sectorKey) {
            // stub: no runtime to stop
        }
    }
}

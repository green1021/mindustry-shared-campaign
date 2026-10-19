package sc.aco;

import tests.TestGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Regression suite for the handoff monitor-stall defect.
 *
 * <p>The original defect: {@code AcoController.retryHandoff()} (synchronized) called
 * {@code SectorHandoffManager.retryHost()} (synchronized), which called
 * {@code hostLoad() -> HostRuntimeOps.loadSnapshot() -> GameSectorWorld.runOnGameThread()}
 * and waited up to 60 s on a latch <em>while still holding the monitor</em>. The UI polls
 * the handoff phase from the render thread, so a blocked load froze the status path for
 * the whole load. The same shape was reachable through {@code onSnapshotCommitted} and
 * {@code onReleased} via {@code maybeAdvance}.
 *
 * <p>These tests pin the fixed contract with a deliberately blocking fake runtime:
 * <ul>
 *   <li>status reads ({@code phase}/{@code failure}/{@code handoffId}) return within
 *       {@link #STATUS_DEADLINE_MS} while a load is blocked, and report {@code LOADING};</li>
 *   <li>the production controller API ({@code retryHandoff}, {@code handoffPhase},
 *       {@code isHandoffRetryable}) is non-blocking for the same condition;</li>
 *   <li>exactly one load/server start happens per handoff, duplicate notifications are
 *       ignored, concurrent retries start one load, a rejected dispatch fails loudly
 *       instead of staying {@code LOADING}, and an abandoned attempt cannot resurrect a
 *       disposed handoff or bind a server afterwards.</li>
 * </ul>
 *
 * <p>Honesty boundary: {@code HostRuntimeOps} is a fake. This proves the lock/executor
 * contract and the state machine, not that a real sector world loads on a real machine.
 */
public final class HandoffConcurrencyTest {

    /**
     * Bound for a UI-facing status read while a load is blocked. A lock-free read is
     * sub-millisecond; the bound is deliberately generous so a slow CI host does not
     * flake, while any monitor held across the blocked load (which waits on a latch that
     * is only released later) would exceed it by orders of magnitude.
     */
    private static final long STATUS_DEADLINE_MS = 250;
    private static final String KEY = "groundZero";
    private static final String SESSION = "sess-owner";

    private interface Case {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        TestGuard.assertEnabled("HandoffConcurrencyTest");
        List<String> failed = new ArrayList<>();
        run(failed, "blockedLoadKeepsStatusResponsive", HandoffConcurrencyTest::blockedLoadKeepsStatusResponsive);
        run(failed, "retryFromReservedStaysResponsive", HandoffConcurrencyTest::retryFromReservedStaysResponsive);
        run(failed, "failedLoadThenRetryServes", HandoffConcurrencyTest::failedLoadThenRetryServes);
        run(failed, "controllerStatusAndRetryDoNotBlock", HandoffConcurrencyTest::controllerStatusAndRetryDoNotBlock);
        run(failed, "concurrentRetriesStartOneLoad", HandoffConcurrencyTest::concurrentRetriesStartOneLoad);
        run(failed, "duplicateNotificationsDoNotReload", HandoffConcurrencyTest::duplicateNotificationsDoNotReload);
        run(failed, "rejectedDispatchFailsExplicitly", HandoffConcurrencyTest::rejectedDispatchFailsExplicitly);
        run(failed, "disposeWhileLoadPending", HandoffConcurrencyTest::disposeWhileLoadPending);
        run(failed, "retryGuardsPreserved", HandoffConcurrencyTest::retryGuardsPreserved);
        run(failed, "loadTimeoutFencesStuckAttempt", HandoffConcurrencyTest::loadTimeoutFencesStuckAttempt);
        run(failed, "staleFailureCannotParkNewerLease", HandoffConcurrencyTest::staleFailureCannotParkNewerLease);
        run(failed, "staleServerStartCannotBreakLiveEndpoint", HandoffConcurrencyTest::staleServerStartCannotBreakLiveEndpoint);
        run(failed, "beginAfterCloseRefused", HandoffConcurrencyTest::beginAfterCloseRefused);
        run(failed, "parkRequiresMatchingVersion", HandoffConcurrencyTest::parkRequiresMatchingVersion);
        if (!failed.isEmpty()) {
            System.out.println("[FAIL] " + failed.size() + " case(s) failed: " + failed);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ cases

    /** Test A: a blocked host load must not delay the status path used by the UI. */
    private static void blockedLoadKeepsStatusResponsive() throws Exception {
        SectorRegistry registry = new SectorRegistry();
        BlockingOps ops = new BlockingOps();
        SectorHandoffManager m = newManager(registry, ops);
        try {
            assert registry.claimSector(KEY, SESSION, 0) != null : "client claim must succeed";
            m.phase(KEY); // warm up: measure the steady-state read, not first-call class loading
            assert m.begin(KEY) == null : "handoff must start";
            m.onSnapshotCommitted(KEY, 4, "sha4");
            releaseClientLease(registry);
            m.onReleased(KEY);
            assert ops.awaitLoadStarted() : "host load never started";

            Timed<String> phase = timed(() -> m.phase(KEY));
            Timed<String> failure = timed(() -> m.failure(KEY));
            Timed<Long> id = timed(() -> m.handoffId(KEY));
            assert "LOADING".equals(phase.value) : "phase while loading = " + phase.value;
            assert failure.value == null : "no failure while loading, got " + failure.value;
            assert id.value > 0 : "handoff id must be published, got " + id.value;
            assert phase.millis <= STATUS_DEADLINE_MS : "phase() blocked " + phase.millis + " ms during load";
            assert failure.millis <= STATUS_DEADLINE_MS : "failure() blocked " + failure.millis + " ms during load";
            assert id.millis <= STATUS_DEADLINE_MS : "handoffId() blocked " + id.millis + " ms during load";

            ops.release.countDown();
            assert waitFor(() -> "SERVING".equals(m.phase(KEY)), 8000)
                    : "handoff must finish SERVING, phase=" + m.phase(KEY) + " failure=" + m.failure(KEY);
            assert ops.loads.get() == 1 : "duplicate host load: " + ops.loads.get();
            assert ops.starts.get() == 1 : "duplicate P2 server start: " + ops.starts.get();
            SectorOwnership own = registry.get(KEY);
            assert own.state == SectorOwnership.LeaseState.ACTIVE : "lease must be ACTIVE, was " + own.state;
            assert own.kind == SectorOwnership.OwnerKind.HOST : "lease must be HOST, was " + own.kind;
            assert BlockingOps.ENDPOINT.equals(own.endpoint) : "endpoint must be advertised, was " + own.endpoint;
        } finally {
            ops.release.countDown();
            m.close();
        }
    }

    /** Test B: retry of a RESERVED lease stays responsive while the runtime load blocks. */
    private static void retryFromReservedStaysResponsive() throws Exception {
        SectorRegistry registry = new SectorRegistry();
        BlockingOps ops = new BlockingOps();
        SectorHandoffManager m = newManager(registry, ops);
        ExecutorService probe = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "handoff-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            assert registry.claimSectorAsHost(KEY, 0) != null : "host claim must succeed";
            assert registry.reserveHostLease(KEY) == SectorRegistry.Result.OK : "lease must park RESERVED";
            m.phase(KEY);
            assert m.retryHost(KEY) == null : "retry must be accepted";
            assert ops.awaitLoadStarted() : "retry load never started";

            // Query from a different thread with a hard deadline: if the retry path held a
            // monitor the status read would never finish while the latch is closed.
            Future<Timed<String>> f = probe.submit(() -> timed(() -> m.phase(KEY)));
            Timed<String> phase = f.get(STATUS_DEADLINE_MS, TimeUnit.MILLISECONDS);
            Future<Timed<String>> ff = probe.submit(() -> timed(() -> m.failure(KEY)));
            Timed<String> failure = ff.get(STATUS_DEADLINE_MS, TimeUnit.MILLISECONDS);
            assert "LOADING".equals(phase.value) : "phase during retry load = " + phase.value;
            assert failure.value == null : "no failure while retrying, got " + failure.value;

            ops.release.countDown();
            assert waitFor(() -> "SERVING".equals(m.phase(KEY)), 8000)
                    : "retry must serve, phase=" + m.phase(KEY) + " failure=" + m.failure(KEY);
            assert ops.loads.get() == 1 : "duplicate host load: " + ops.loads.get();
        } finally {
            ops.release.countDown();
            probe.shutdownNow();
            m.close();
        }
    }

    /** A real failed load parks the lease RESERVED and a later retry serves the sector. */
    private static void failedLoadThenRetryServes() throws Exception {
        SectorRegistry registry = new SectorRegistry();
        BlockingOps ops = new BlockingOps();
        ops.block = false;
        ops.failLoad = true;
        SectorHandoffManager m = newManager(registry, ops);
        try {
            assert registry.claimSector(KEY, SESSION, 0) != null : "client claim must succeed";
            assert m.begin(KEY) == null : "handoff must start";
            m.onSnapshotCommitted(KEY, 7, "sha7");
            releaseClientLease(registry);
            m.onReleased(KEY);
            assert waitFor(() -> "FAILED".equals(m.phase(KEY)), 8000)
                    : "failed load must end FAILED, phase=" + m.phase(KEY);
            assert "HOST_LOAD_FAILED".equals(m.failure(KEY)) : "failure = " + m.failure(KEY);
            assert registry.get(KEY).state == SectorOwnership.LeaseState.RESERVED
                    : "a failed load must park the lease RESERVED, was " + registry.get(KEY).state;

            ops.failLoad = false;
            assert m.retryHost(KEY) == null : "retry must be accepted after a failed load";
            assert waitFor(() -> "SERVING".equals(m.phase(KEY)), 8000)
                    : "retry after failure must serve, phase=" + m.phase(KEY) + " failure=" + m.failure(KEY);
            assert ops.loads.get() == 2 : "expected one failed + one successful load, got " + ops.loads.get();
            assert ops.starts.get() == 1 : "server must start once, got " + ops.starts.get();
        } finally {
            ops.release.countDown();
            m.close();
        }
    }

    /** Test C: the production controller API must not block the caller during a load. */
    private static void controllerStatusAndRetryDoNotBlock() throws Exception {
        Path root = Files.createTempDirectory("aco-handoff-conc-");
        BlockingOps ops = new BlockingOps();
        int port = freePort();
        AcoController controller = new AcoController(root.resolve("host").toString());
        try {
            controller.setHostRuntimeOps(ops);
            assert controller.hostCampaign(port, port + 1) : "host campaign must start";
            SectorRegistry registry = controller.getRegistry();
            assert registry.claimSectorAsHost(KEY, 0) != null : "host claim must succeed";
            assert registry.reserveHostLease(KEY) == SectorRegistry.Result.OK : "lease must park RESERVED";

            Timed<String> accepted = timed(() -> controller.retryHandoff(KEY));
            assert accepted.value == null : "retry refused: " + accepted.value;
            assert accepted.millis <= STATUS_DEADLINE_MS
                    : "retryHandoff() blocked the caller " + accepted.millis + " ms";
            assert ops.awaitLoadStarted() : "controller retry never reached the runtime";

            Timed<String> phase = timed(() -> controller.handoffPhase(KEY));
            Timed<String> failure = timed(() -> controller.handoffFailure(KEY));
            Timed<Boolean> retryable = timed(() -> controller.isHandoffRetryable(KEY));
            assert "LOADING".equals(phase.value) : "controller phase while loading = " + phase.value;
            assert failure.value == null : "controller failure while loading = " + failure.value;
            assert !retryable.value : "a loading handoff must not be retryable";
            assert phase.millis <= STATUS_DEADLINE_MS : "handoffPhase() blocked " + phase.millis + " ms";
            assert failure.millis <= STATUS_DEADLINE_MS : "handoffFailure() blocked " + failure.millis + " ms";
            assert retryable.millis <= STATUS_DEADLINE_MS : "isHandoffRetryable() blocked " + retryable.millis + " ms";

            ops.release.countDown();
            assert waitFor(() -> "SERVING".equals(controller.handoffPhase(KEY)), 8000)
                    : "controller handoff must serve, phase=" + controller.handoffPhase(KEY);
        } finally {
            ops.release.countDown();
            controller.dispose();
        }
    }

    /** Two rapid retries: exactly one load, the other refused with LOAD_IN_PROGRESS. */
    private static void concurrentRetriesStartOneLoad() throws Exception {
        SectorRegistry registry = new SectorRegistry();
        BlockingOps ops = new BlockingOps();
        SectorHandoffManager m = newManager(registry, ops);
        ExecutorService two = Executors.newFixedThreadPool(2);
        try {
            assert registry.claimSectorAsHost(KEY, 0) != null : "host claim must succeed";
            assert registry.reserveHostLease(KEY) == SectorRegistry.Result.OK : "lease must park RESERVED";
            CountDownLatch go = new CountDownLatch(1);
            Callable<String> retry = () -> {
                go.await();
                return m.retryHost(KEY);
            };
            Future<String> a = two.submit(retry);
            Future<String> b = two.submit(retry);
            go.countDown();
            String ra = a.get(10, TimeUnit.SECONDS);
            String rb = b.get(10, TimeUnit.SECONDS);
            int accepted = (ra == null ? 1 : 0) + (rb == null ? 1 : 0);
            assert accepted == 1 : "exactly one retry may be accepted, got " + ra + " / " + rb;
            String refused = ra == null ? rb : ra;
            assert "LOAD_IN_PROGRESS".equals(refused)
                    : "losing retry must be refused with LOAD_IN_PROGRESS, got " + refused;
            assert ops.awaitLoadStarted() : "the accepted retry must start exactly one load";
            assert "LOADING".equals(m.phase(KEY)) : "phase must stay LOADING, was " + m.phase(KEY);
        } finally {
            ops.release.countDown();
            two.shutdownNow();
        }
        assert waitFor(() -> "SERVING".equals(m.phase(KEY)), 8000)
                : "handoff must serve, phase=" + m.phase(KEY) + " failure=" + m.failure(KEY);
        assert ops.loads.get() == 1 : "two rapid retries must not start two loads: " + ops.loads.get();
        m.close();
    }

    /** Repeated snapshot/release notifications must not start a second load. */
    private static void duplicateNotificationsDoNotReload() throws Exception {
        SectorRegistry registry = new SectorRegistry();
        BlockingOps ops = new BlockingOps();
        SectorHandoffManager m = newManager(registry, ops);
        try {
            assert registry.claimSector(KEY, SESSION, 0) != null : "client claim must succeed";
            assert m.begin(KEY) == null : "handoff must start";
            m.onSnapshotCommitted(KEY, 5, "sha5");
            releaseClientLease(registry);
            m.onReleased(KEY);
            assert ops.awaitLoadStarted() : "host load never started";

            m.onSnapshotCommitted(KEY, 5, "sha5");
            m.onReleased(KEY);
            m.onReleased(KEY);
            assert "LOADING".equals(m.phase(KEY)) : "duplicate notifications changed the phase: " + m.phase(KEY);

            ops.release.countDown();
            assert waitFor(() -> "SERVING".equals(m.phase(KEY)), 8000) : "phase=" + m.phase(KEY);
            assert ops.loads.get() == 1 : "duplicate notifications started extra loads: " + ops.loads.get();
            assert ops.starts.get() == 1 : "duplicate notifications started extra servers: " + ops.starts.get();
        } finally {
            ops.release.countDown();
            m.close();
        }
    }

    /** A rejected dispatch must fail loudly, never leave the record in LOADING. */
    private static void rejectedDispatchFailsExplicitly() throws Exception {
        SectorRegistry registry = new SectorRegistry();
        BlockingOps ops = new BlockingOps();
        ExecutorService dead = Executors.newSingleThreadExecutor();
        dead.shutdown();
        SectorHandoffManager m = new SectorHandoffManager(registry, ops, (s, t, p) -> true, (t, p) -> {
        }, System::currentTimeMillis, dead);
        try {
            assert registry.claimSectorAsHost(KEY, 0) != null : "host claim must succeed";
            assert registry.reserveHostLease(KEY) == SectorRegistry.Result.OK : "lease must park RESERVED";
            String err = m.retryHost(KEY);
            assert "LOAD_NOT_DISPATCHED".equals(err) : "expected LOAD_NOT_DISPATCHED, got " + err;
            assert "FAILED".equals(m.phase(KEY)) : "rejected dispatch must not stay LOADING, was " + m.phase(KEY);
            assert "LOAD_NOT_DISPATCHED".equals(m.failure(KEY)) : "failure = " + m.failure(KEY);
            assert ops.loads.get() == 0 : "rejected dispatch must not run a load";
        } finally {
            dead.shutdownNow();
            m.close();
        }
    }

    /** Disposal while a load is pending: no resurrection, no server, lease stays recoverable. */
    private static void disposeWhileLoadPending() throws Exception {
        SectorRegistry registry = new SectorRegistry();
        BlockingOps ops = new BlockingOps();
        ExecutorService injected = Executors.newSingleThreadExecutor();
        SectorHandoffManager m = new SectorHandoffManager(registry, ops, (s, t, p) -> true, (t, p) -> {
        }, System::currentTimeMillis, injected);
        try {
            assert registry.claimSectorAsHost(KEY, 0) != null : "host claim must succeed";
            assert registry.reserveHostLease(KEY) == SectorRegistry.Result.OK : "lease must park RESERVED";
            assert m.retryHost(KEY) == null : "retry must be accepted";
            assert ops.awaitLoadStarted() : "host load never started";

            m.close();
            assert "FAILED".equals(m.phase(KEY)) : "close must fail the pending handoff, was " + m.phase(KEY);
            assert "DISPOSED".equals(m.failure(KEY)) : "failure = " + m.failure(KEY);
            assert registry.get(KEY).state == SectorOwnership.LeaseState.RESERVED
                    : "dispose must park the host lease, was " + registry.get(KEY).state;

            // Let the abandoned load finish: it must not resurrect the handoff or bind a server.
            ops.release.countDown();
            injected.shutdown();
            assert injected.awaitTermination(10, TimeUnit.SECONDS) : "abandoned load must finish";
            assert "FAILED".equals(m.phase(KEY)) : "a stale completion overwrote the terminal phase: " + m.phase(KEY);
            assert "DISPOSED".equals(m.failure(KEY)) : "a stale completion overwrote the failure: " + m.failure(KEY);
            assert ops.starts.get() == 0 : "a disposed handoff must not start a server, starts=" + ops.starts.get();
            assert registry.get(KEY).state == SectorOwnership.LeaseState.RESERVED
                    : "the lease must stay RESERVED, was " + registry.get(KEY).state;
        } finally {
            ops.release.countDown();
            injected.shutdownNow();
            m.close();
        }
    }

    /** Ownership guards on the retry entry point are unchanged by the async refactor. */
    private static void retryGuardsPreserved() {
        SectorRegistry registry = new SectorRegistry();
        BlockingOps ops = new BlockingOps();
        SectorHandoffManager m = newManager(registry, ops);
        try {
            assert "INVALID_SECTOR_KEY".equals(m.retryHost("bad|key"))
                    : "an invalid key must be refused before touching the registry";
            assert "NOT_RESERVED".equals(m.retryHost(KEY)) : "a sector with no lease must not be retryable";
            assert registry.claimSector(KEY, SESSION, 0) != null : "client claim must succeed";
            assert "NOT_RESERVED".equals(m.retryHost(KEY)) : "a client-owned sector must not be retryable";
            assert "NONE".equals(m.phase(KEY)) : "a refused retry must not create a handoff record";
        } finally {
            m.close();
        }
    }

    /**
     * A load that never returns must not wedge the sector: the watchdog fences the stuck
     * attempt, parks the lease, and a retry then serves. The abandoned attempt finishing
     * later must not stop the live server (the P2 runtime is process-global).
     */
    private static void loadTimeoutFencesStuckAttempt() throws Exception {
        SectorRegistry registry = new SectorRegistry();
        FakeClock clock = new FakeClock();
        PhasedOps ops = new PhasedOps(false); // attempt 1 eventually "succeeds", too late
        SectorHandoffManager m = newManager(registry, ops, clock);
        try {
            assert registry.claimSector(KEY, SESSION, 0) != null : "client claim must succeed";
            assert m.begin(KEY) == null : "handoff must start";
            m.onSnapshotCommitted(KEY, 5, "sha5");
            releaseClientLease(registry);
            m.onReleased(KEY);
            assert ops.firstEntered.await(5, TimeUnit.SECONDS) : "first host load never started";
            assert "LOADING".equals(m.phase(KEY)) : "expected LOADING, was " + m.phase(KEY);

            clock.advance(600_000); // past the handoff/load timeout
            m.tick();
            assert "FAILED".equals(m.phase(KEY)) : "a stuck load must be fenced to FAILED, was " + m.phase(KEY);
            assert "LOAD_TIMEOUT".equals(m.failure(KEY)) : "expected LOAD_TIMEOUT, was " + m.failure(KEY);
            assert registry.get(KEY).state == SectorOwnership.LeaseState.RESERVED
                    : "the wedged lease must be parked RESERVED, was " + registry.get(KEY).state;

            assert m.retryHost(KEY) == null : "retry must be accepted after a load timeout";
            assert waitFor(() -> "SERVING".equals(m.phase(KEY)), 5000)
                    : "retry did not serve after a stuck load, phase=" + m.phase(KEY);
            assert ops.starts.get() == 1 : "exactly one server start expected, was " + ops.starts.get();

            // The abandoned attempt finishes now; it is stale and must change nothing.
            ops.releaseFirst.countDown();
            assert waitFor(() -> ops.loads.get() >= 2, 5000) : "retry load never ran";
            Thread.sleep(200);
            assert "SERVING".equals(m.phase(KEY)) : "a late completion overwrote SERVING: " + m.phase(KEY);
            assert ops.starts.get() == 1 : "a late completion started a second server: " + ops.starts.get();
            assert ops.stops.get() == 0 : "a late completion stopped the live server: " + ops.stops.get();
            SectorOwnership own = registry.get(KEY);
            assert own.state == SectorOwnership.LeaseState.ACTIVE && own.kind == SectorOwnership.OwnerKind.HOST
                    : "the served lease must stay ACTIVE/HOST, was " + own.state + "/" + own.kind;
        } finally {
            ops.releaseFirst.countDown();
            m.close();
        }
    }

    /**
     * A late failure from a fenced attempt must not park the lease that the newer retry
     * holds: the park is fenced by the ownership version the attempt actually claimed.
     */
    private static void staleFailureCannotParkNewerLease() throws Exception {
        SectorRegistry registry = new SectorRegistry();
        FakeClock clock = new FakeClock();
        PhasedOps ops = new PhasedOps(true); // attempt 1 fails, but only after the retry served
        SectorHandoffManager m = newManager(registry, ops, clock);
        try {
            assert registry.claimSector(KEY, SESSION, 0) != null : "client claim must succeed";
            assert m.begin(KEY) == null : "handoff must start";
            m.onSnapshotCommitted(KEY, 5, "sha5");
            releaseClientLease(registry);
            m.onReleased(KEY);
            assert ops.firstEntered.await(5, TimeUnit.SECONDS) : "first host load never started";
            clock.advance(600_000);
            m.tick();
            assert "FAILED".equals(m.phase(KEY)) : "stuck load must fail, was " + m.phase(KEY);

            assert m.retryHost(KEY) == null : "retry must be accepted";
            assert waitFor(() -> "SERVING".equals(m.phase(KEY)), 5000) : "retry did not serve";
            long servedVersion = registry.get(KEY).ownershipVersion;

            ops.releaseFirst.countDown(); // attempt 1 now reports a late failure
            assert waitFor(() -> ops.loads.get() >= 2, 5000) : "retry load never ran";
            Thread.sleep(200);
            SectorOwnership own = registry.get(KEY);
            assert own.state == SectorOwnership.LeaseState.ACTIVE
                    : "a stale failure parked the newer lease: " + own.state;
            assert own.ownershipVersion == servedVersion : "the live lease version changed";
            assert "SERVING".equals(m.phase(KEY)) : "a stale failure overwrote SERVING: " + m.phase(KEY);
            assert ops.stops.get() == 0 : "a stale failure stopped the live server";
        } finally {
            ops.releaseFirst.countDown();
            m.close();
        }
    }

    /**
     * A stale attempt that returns from {@code startServer} after a retry already serves
     * must neither overwrite the advertised endpoint nor stop the live server. {@code
     * startP2} is idempotent, so the stale attempt holds a port it never bound.
     */
    private static void staleServerStartCannotBreakLiveEndpoint() throws Exception {
        SectorRegistry registry = new SectorRegistry();
        FakeClock clock = new FakeClock();
        PhasedOps ops = new PhasedOps(false);
        ops.blockFirstStart = true;
        ops.releaseFirst.countDown(); // the first load returns at once; the start is what blocks
        SectorHandoffManager m = newManager(registry, ops, clock);
        try {
            assert registry.claimSector(KEY, SESSION, 0) != null : "client claim must succeed";
            assert m.begin(KEY) == null : "handoff must start";
            m.onSnapshotCommitted(KEY, 5, "sha5");
            releaseClientLease(registry);
            m.onReleased(KEY);
            assert ops.firstStartEntered.await(5, TimeUnit.SECONDS) : "first server start never ran";

            // The attempt is stuck inside startServer; the watchdog fences it.
            clock.advance(600_000);
            m.tick();
            assert "FAILED".equals(m.phase(KEY)) : "stuck start must be fenced, was " + m.phase(KEY);

            assert m.retryHost(KEY) == null : "retry must be accepted";
            assert waitFor(() -> "SERVING".equals(m.phase(KEY)), 5000) : "retry did not serve";
            String served = registry.get(KEY).endpoint;
            assert PhasedOps.ENDPOINT.equals(served) : "expected the retry endpoint, was " + served;

            ops.releaseFirstStart.countDown(); // stale attempt returns with a port it never bound
            assert ops.firstStartReturned.await(5, TimeUnit.SECONDS) : "stale start never returned";
            Thread.sleep(200);
            assert PhasedOps.ENDPOINT.equals(registry.get(KEY).endpoint)
                    : "a stale attempt overwrote the live endpoint with " + registry.get(KEY).endpoint;
            assert ops.stops.get() == 0 : "a stale attempt stopped the live server";
            assert "SERVING".equals(m.phase(KEY)) : "a stale start overwrote SERVING: " + m.phase(KEY);
        } finally {
            ops.releaseFirstStart.countDown();
            m.close();
        }
    }

    /** A disposed manager must not accept new handoffs only to dispatch them into a dead executor. */
    private static void beginAfterCloseRefused() {
        SectorRegistry registry = new SectorRegistry();
        SectorHandoffManager m = newManager(registry, new BlockingOps());
        try {
            assert registry.claimSector(KEY, SESSION, 0) != null : "client claim must succeed";
            m.close();
            assert "NOT_AVAILABLE".equals(m.begin(KEY)) : "begin() after close must be refused, was " + m.begin(KEY);
            assert "NONE".equals(m.phase(KEY)) : "a refused begin must not publish a record";
        } finally {
            m.close();
        }
    }

    /** The registry-level park is version-fenced, so a stale park can never take a newer lease. */
    private static void parkRequiresMatchingVersion() throws Exception {
        SectorRegistry registry = new SectorRegistry();
        assert registry.claimSector(KEY, SESSION, 0) != null : "client claim must succeed";
        long v1 = registry.get(KEY).ownershipVersion;
        assert registry.releaseSector(KEY, SESSION, v1, registry.get(KEY).grant) == SectorRegistry.Result.OK
                : "release must succeed";
        assert registry.claimSectorAsHost(KEY, 0) != null : "host claim must succeed";
        long v2 = registry.get(KEY).ownershipVersion;

        assert registry.reserveHostLease(KEY, v1) == SectorRegistry.Result.STALE_VERSION
                : "park with a stale version must be refused";
        assert registry.get(KEY).state == SectorOwnership.LeaseState.ACTIVE
                : "a refused park must leave the lease ACTIVE";
        assert registry.reserveHostLease(KEY, v2) == SectorRegistry.Result.OK
                : "park with the current version must succeed";
        assert registry.get(KEY).state == SectorOwnership.LeaseState.RESERVED : "lease must be RESERVED";
    }

    // -------------------------------------------------------------- fixtures

    /** Deterministic monotonic clock for the watchdog tests. */
    private static final class FakeClock implements java.util.function.LongSupplier {
        private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);

        @Override
        public long getAsLong() {
            return now.get();
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    /**
     * Host runtime whose first load blocks (and survives interruption, to model a wedged
     * game-thread wait); every later load succeeds immediately.
     */
    private static final class PhasedOps implements SectorHandoffManager.HostRuntimeOps {
        static final String ENDPOINT = "127.0.0.1:47811";
        static final String ALT_ENDPOINT = "127.0.0.1:47812";

        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch firstStartEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstStart = new CountDownLatch(1);
        final CountDownLatch firstStartReturned = new CountDownLatch(1);
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final boolean firstFails;
        volatile boolean blockFirstStart;

        PhasedOps(boolean firstFails) {
            this.firstFails = firstFails;
        }

        @Override
        public boolean loadSnapshot(String sectorKey) {
            if (loads.incrementAndGet() == 1) {
                firstEntered.countDown();
                awaitUninterruptibly(releaseFirst);
                return !firstFails;
            }
            return true;
        }

        @Override
        public String startServer(String sectorKey) {
            if (starts.incrementAndGet() == 1 && blockFirstStart) {
                firstStartEntered.countDown();
                awaitUninterruptibly(releaseFirstStart);
                firstStartReturned.countDown();
                return ALT_ENDPOINT; // a port this attempt never actually bound
            }
            return ENDPOINT;
        }

        @Override
        public void stopServer(String sectorKey) {
            stops.incrementAndGet();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static SectorHandoffManager newManager(SectorRegistry registry, BlockingOps ops) {
        return new SectorHandoffManager(registry, ops, (s, t, p) -> true, (t, p) -> {
        });
    }

    private static SectorHandoffManager newManager(SectorRegistry registry, SectorHandoffManager.HostRuntimeOps ops,
            FakeClock clock) {
        return new SectorHandoffManager(registry, ops, (s, t, p) -> true, (t, p) -> {
        }, clock, null);
    }

    private static void releaseClientLease(SectorRegistry registry) {
        SectorOwnership own = registry.get(KEY);
        assert own != null && own.kind == SectorOwnership.OwnerKind.CLIENT : "expected a client lease, got " + own;
        SectorRegistry.Result r = registry.releaseSector(KEY, SESSION, own.ownershipVersion, own.grant);
        assert r == SectorRegistry.Result.OK : "client release failed: " + r;
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

    private static boolean waitFor(java.util.function.BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean ok = cond.getAsBoolean();
        while (!ok && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            ok = cond.getAsBoolean();
        }
        return ok;
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static final class Timed<T> {
        final T value;
        final long millis;

        Timed(T value, long millis) {
            this.value = value;
            this.millis = millis;
        }
    }

    private static <T> Timed<T> timed(Supplier<T> call) {
        long t0 = System.nanoTime();
        T value = call.get();
        return new Timed<>(value, (System.nanoTime() - t0) / 1_000_000L);
    }

    /** Fake host runtime: blocks inside loadSnapshot until the test releases it. */
    private static final class BlockingOps implements SectorHandoffManager.HostRuntimeOps {
        static final String ENDPOINT = "127.0.0.1:47811";

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        volatile boolean block = true;
        volatile boolean failLoad;

        @Override
        public boolean loadSnapshot(String sectorKey) throws Exception {
            loads.incrementAndGet();
            entered.countDown();
            if (block && !release.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch was never released");
            }
            return !failLoad;
        }

        @Override
        public String startServer(String sectorKey) {
            starts.incrementAndGet();
            return ENDPOINT;
        }

        @Override
        public void stopServer(String sectorKey) {
            stops.incrementAndGet();
        }

        boolean awaitLoadStarted() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }
    }
}

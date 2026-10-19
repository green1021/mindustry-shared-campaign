package sc.aco;

import arc.util.Log;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Host-takeover handoff state machine (authority side).
 *
 * <p>Taking over a client-owned sector is a protocol, not a bare registry edit:
 *
 * <ol>
 *   <li>the authority notices the client to leave ({@code SECTOR_HANDOFF_REQUEST});
 *   <li>the client saves the exact running world, commits the full validated
 *       {@code .msav} snapshot to the campaign store, and releases its local writer
 *       lease (its machine actually stops simulating);
 *   <li>the host claims the lease only <em>after</em> the snapshot is committed and
 *       the release is acked ({@link #onSnapshotCommitted} + {@link #onReleased});
 *   <li>the host process loads that exact stored snapshot and starts an ordinary
 *       Mindustry P2 server for it ({@link HostRuntimeOps});
 *   <li>the advertised endpoint is recorded on the lease, which is what makes the
 *       sector joinable for other clients.
 * </ol>
 *
 * <p>Failure rule: at no point may two machines simulate the sector. If load or
 * server start fails, the lease is parked as RESERVED (claims blocked, retry only
 * via an explicit host retry / operator action) -- the last good snapshot is kept.
 * If the owner goes away before releasing, the handoff aborts and the sector stays
 * ORPHANED (no silent reassignment). Notifications are best-effort; correctness
 * does not depend on the client receiving them, only on the authority refusing to
 * advance without snapshot+release.
 *
 * <p><b>Locking contract.</b> {@link #stateLock} protects only the handoff records in
 * {@link #pending}; it is never held across snapshot I/O, game-thread dispatch/server
 * startup ({@link HostRuntimeOps}), socket writes ({@link SessionNotifier},
 * {@link Broadcaster}) or journal writes. Slow work runs as a {@link LoadAttempt} on a
 * dedicated loader thread, so the status readers used by the UI ({@link #phase},
 * {@link #failure}, {@link #handoffId}) stay responsive while a load is in flight.
 * Every mutating step is a short transition validated against the attempt's fencing
 * token (handoff id + monotonically increasing attempt number), so a stale attempt can
 * never complete a newer handoff, mark the wrong handoff SERVING, or park a newer lease.
 */
public class SectorHandoffManager {

    /** Sends a directed control message to one connection; false when no route. */
    public interface SessionNotifier {
        boolean send(String sessionId, AcoMessageType type, String payload);
    }

    /** Sector event channel (SECTOR_HOST_CHANGED): {@code key|EVENT|...}. */
    public interface Broadcaster {
        void broadcast(AcoMessageType type, String payload);
    }

    /**
     * Host-machine world/runtime operations. Production implementations must run
     * {@code Vars} work on the game thread internally and return only after it
     * completed. All methods may throw; the manager treats a throw as failure.
     */
    public interface HostRuntimeOps {
        /** Load the stored snapshot for the sector into the host's {@code Vars.world}. */
        boolean loadSnapshot(String sectorKey) throws Exception;

        /** Start the sector's P2 server; return the advertised reachable endpoint, or null. */
        String startServer(String sectorKey) throws Exception;

        /** Stop the sector's P2 server (best effort). */
        void stopServer(String sectorKey);
    }

    public enum Phase { NONE, AWAIT_SNAPSHOT, AWAIT_RELEASE, LOADING, SERVING, FAILED, ABORTED }

    private static final class Handoff {
        final String sectorKey;
        final long id;
        final String ownerSession;
        final long startedAt;
        long snapshotRevision = -1;
        String snapshotSha;
        boolean releaseDone;
        /** Monotonic per-handoff load attempt counter; the fencing token for slow work. */
        long attempt;
        /** Clock reading when {@link #attempt} was dispatched; drives the load watchdog. */
        long loadStartedAt;
        Phase phase = Phase.AWAIT_SNAPSHOT;
        String failure;

        Handoff(String sectorKey, long id, String ownerSession, long startedAt) {
            this.sectorKey = sectorKey;
            this.id = id;
            this.ownerSession = ownerSession;
            this.startedAt = startedAt;
        }
    }

    /**
     * Immutable description of one host load, captured while {@link #stateLock} is held
     * and executed after the lock is released. {@code handoffId + attempt} fence the
     * result: only the attempt that still owns the record may commit any state.
     */
    static final class LoadAttempt {
        final String sectorKey;
        final long handoffId;
        final long attempt;
        final long snapshotRevision;
        /**
         * {@code ownershipVersion} of the HOST lease this attempt claimed, or -1 before
         * the claim. Terminal handling uses it so a late attempt can only park a lease it
         * actually took, never one a newer retry is holding.
         */
        long claimVersion = -1;

        LoadAttempt(String sectorKey, long handoffId, long attempt, long snapshotRevision) {
            this.sectorKey = sectorKey;
            this.handoffId = handoffId;
            this.attempt = attempt;
            this.snapshotRevision = snapshotRevision;
        }
    }

    /** Deferred outbound message, dispatched only after the state lock has been released. */
    private static final class Outbound {
        final String sessionId; // null => broadcast
        final AcoMessageType type;
        final String payload;

        Outbound(String sessionId, AcoMessageType type, String payload) {
            this.sessionId = sessionId;
            this.type = type;
            this.payload = payload;
        }
    }

    private final SectorRegistry registry;
    private final HostRuntimeOps ops; // nullable: authority-only deployments refuse at load stage
    private final SessionNotifier notifier;
    private final Broadcaster broadcaster;
    private final LongSupplier clock;

    /** Guards {@link #pending} and every mutable field of its records. Never held across slow work. */
    private final Object stateLock = new Object();
    private final Map<String, Handoff> pending = new LinkedHashMap<>();

    /**
     * Lock-free published snapshot of {@link #pending} for status readers.
     *
     * <p>The UI polls phase/failure from the render thread. Reading them under
     * {@link #stateLock} is cheap but not free: a transition can be waiting on the
     * registry monitor (which performs journal writes) while holding this lock, and any
     * such wait would surface as input/render hitching. Every mutation publishes an
     * immutable {@link View} here while the lock is already held, so {@link #phase},
     * {@link #failure} and {@link #handoffId} never acquire a monitor at all.
     */
    private final Map<String, View> view = new java.util.concurrent.ConcurrentHashMap<>();

    /** Immutable status snapshot; safe to read from any thread. */
    public static final class View {
        public final String phase;
        public final String failure;
        public final long handoffId;

        View(String phase, String failure, long handoffId) {
            this.phase = phase;
            this.failure = failure;
            this.handoffId = handoffId;
        }
    }

    private final AtomicLong ids = new AtomicLong(1);
    private volatile long timeoutMs = 120_000;
    private volatile boolean closed;

    /**
     * Serialises host loads. One thread is deliberate: a dedicated loader must not be
     * the shared {@code aco-action} worker (a 60 s game-thread wait there would starve
     * claim/checkpoint/exit), and {@link HostRuntimeOps} mutates process-global
     * {@code Vars.world}/{@code Vars.logic}, so two loads can never run at once anyway.
     */
    private volatile ExecutorService loadExecutor;
    private final boolean ownsExecutor;

    public SectorHandoffManager(SectorRegistry registry, HostRuntimeOps ops, SessionNotifier notifier, Broadcaster broadcaster) {
        this(registry, ops, notifier, broadcaster, System::currentTimeMillis, null);
    }

    public SectorHandoffManager(SectorRegistry registry, HostRuntimeOps ops, SessionNotifier notifier, Broadcaster broadcaster,
            LongSupplier clock) {
        this(registry, ops, notifier, broadcaster, clock, null);
    }

    /** Test seam: inject the loader so a blocked load is deterministic. */
    SectorHandoffManager(SectorRegistry registry, HostRuntimeOps ops, SessionNotifier notifier, Broadcaster broadcaster,
            LongSupplier clock, ExecutorService loadExecutor) {
        this.registry = registry;
        this.ops = ops;
        this.notifier = notifier;
        this.broadcaster = broadcaster;
        this.clock = clock;
        this.ownsExecutor = loadExecutor == null;
        this.loadExecutor = loadExecutor == null ? newLoadExecutor() : loadExecutor;
    }

    private static ExecutorService newLoadExecutor() {
        AtomicInteger seq = new AtomicInteger(1);
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aco-handoff-load-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = Math.max(5_000, timeoutMs);
    }

    // ------------------------------------------------------------------- begin

    /**
     * Start a takeover of a client-owned sector.
     *
     * @return null when the handoff started, otherwise a refusal reason:
     *         {@code SECTOR_NOT_CLIENT_OWNED}, {@code ALREADY_PENDING}, {@code NO_ROUTE}
     */
    public String begin(String sectorKey) {
        if (!SectorSnapshotCodec.validSectorKey(sectorKey)) return "INVALID_SECTOR_KEY";
        final Handoff h;
        synchronized (stateLock) {
            if (closed) return "NOT_AVAILABLE";
            SectorOwnership own = registry.get(sectorKey);
            if (own == null || !own.active() || own.kind != SectorOwnership.OwnerKind.CLIENT || own.ownerSessionId == null) {
                return "SECTOR_NOT_CLIENT_OWNED";
            }
            Handoff existing = pending.get(sectorKey);
            if (existing != null && (existing.phase == Phase.AWAIT_SNAPSHOT || existing.phase == Phase.AWAIT_RELEASE
                    || existing.phase == Phase.LOADING)) {
                return "ALREADY_PENDING";
            }
            h = new Handoff(sectorKey, ids.getAndIncrement(), own.ownerSessionId, clock.getAsLong());
            pending.put(sectorKey, h);
            publishLocked(h);
        }
        Log.info("HANDOFF_BEGIN id=@ sector=@ owner=@", h.id, sectorKey, h.ownerSession);
        // Socket write, deliberately outside the state lock.
        boolean sent = notifier != null && notifier.send(h.ownerSession, AcoMessageType.SECTOR_HANDOFF_REQUEST, sectorKey + "|" + h.id);
        if (!sent) {
            synchronized (stateLock) {
                if (h.phase == Phase.AWAIT_SNAPSHOT) {
                    h.phase = Phase.ABORTED;
                    h.failure = "NO_ROUTE";
                    publishLocked(h);
                }
            }
            broadcast(AcoMessageType.SECTOR_HANDOFF_ABORT, sectorKey + "|ABORTED|NO_ROUTE");
            return "NO_ROUTE";
        }
        return null;
    }

    // ---------------------------------------------------------------- progress

    /** Called by the server after a validated snapshot for the sector was committed. */
    public void onSnapshotCommitted(String sectorKey, long revision, String sha) {
        LoadAttempt attempt;
        synchronized (stateLock) {
            Handoff h = pending.get(sectorKey);
            if (h == null || h.phase != Phase.AWAIT_SNAPSHOT) return;
            h.snapshotRevision = revision;
            h.snapshotSha = sha;
            h.phase = Phase.AWAIT_RELEASE;
            attempt = maybeAdvanceLocked(h);
            publishLocked(h);
        }
        Log.info("HANDOFF_SNAPSHOT_COMMITTED sector=@ revision=@", sectorKey, revision);
        if (attempt != null) submitLoad(attempt);
    }

    /** Called by the server after the owner connection released its lease. */
    public void onReleased(String sectorKey) {
        LoadAttempt attempt;
        boolean snapshotCommitted;
        synchronized (stateLock) {
            Handoff h = pending.get(sectorKey);
            if (h == null || (h.phase != Phase.AWAIT_SNAPSHOT && h.phase != Phase.AWAIT_RELEASE)) return;
            h.releaseDone = true;
            snapshotCommitted = h.snapshotRevision >= 0;
            attempt = maybeAdvanceLocked(h);
            publishLocked(h);
        }
        Log.info("HANDOFF_RELEASED sector=@ snapshotCommitted=@", sectorKey, snapshotCommitted);
        if (attempt != null) submitLoad(attempt);
    }

    /** Client-side failure or explicit abort ({@code SECTOR_HANDOFF_ABORT} from the owner). */
    public void abort(String sectorKey, String reason) {
        List<Outbound> out = new ArrayList<>(2);
        synchronized (stateLock) {
            Handoff h = pending.get(sectorKey);
            if (h == null || h.phase == Phase.SERVING || h.phase == Phase.LOADING) return; // too late: let the load path conclude
            if (h.phase == Phase.AWAIT_SNAPSHOT) {
                out.add(new Outbound(h.ownerSession, AcoMessageType.SECTOR_HANDOFF_ABORT, sectorKey + "|ABORT|" + reason));
            }
            h.phase = Phase.ABORTED;
            h.failure = reason;
            publishLocked(h);
            out.add(new Outbound(null, AcoMessageType.SECTOR_HANDOFF_ABORT, sectorKey + "|ABORTED|" + reason));
        }
        Log.warn("HANDOFF_ABORTED sector=@ reason=@", sectorKey, reason);
        flush(out);
    }

    /** Owner connection went away. Abort unless the handoff already passed the release. */
    public void onOwnerDisconnected(String sessionId) {
        if (sessionId == null) return;
        List<Outbound> out = new ArrayList<>(2);
        synchronized (stateLock) {
            for (Handoff h : pending.values()) {
                if (h.ownerSession.equals(sessionId)
                        && (h.phase == Phase.AWAIT_SNAPSHOT || h.phase == Phase.AWAIT_RELEASE)) {
                    h.phase = Phase.ABORTED;
                    h.failure = "OWNER_DISCONNECTED";
                    publishLocked(h);
                    out.add(new Outbound(null, AcoMessageType.SECTOR_HANDOFF_ABORT,
                            h.sectorKey + "|ABORTED|OWNER_DISCONNECTED"));
                }
            }
        }
        for (Outbound o : out) Log.warn("HANDOFF_ABORTED sector=@ reason=OWNER_DISCONNECTED", o.payload.split("\\|")[0]);
        flush(out);
    }

    /** Periodic timeout sweep; call from a watchdog (or tests) with a monotonic ms clock. */
    public void tick() {
        long now = clock.getAsLong();
        long timeout = timeoutMs;
        List<Outbound> out = new ArrayList<>(2);
        List<String> parked = new ArrayList<>(1);
        boolean timedOutLoad = false;
        synchronized (stateLock) {
            if (closed) return;
            for (Handoff h : pending.values()) {
                if ((h.phase == Phase.AWAIT_SNAPSHOT || h.phase == Phase.AWAIT_RELEASE) && now - h.startedAt > timeout) {
                    if (h.phase == Phase.AWAIT_SNAPSHOT) {
                        out.add(new Outbound(h.ownerSession, AcoMessageType.SECTOR_HANDOFF_ABORT,
                                h.sectorKey + "|ABORT|TIMEOUT"));
                    }
                    h.phase = Phase.ABORTED;
                    h.failure = "TIMEOUT";
                    publishLocked(h);
                    out.add(new Outbound(null, AcoMessageType.SECTOR_HANDOFF_ABORT, h.sectorKey + "|ABORTED|TIMEOUT"));
                } else if (h.phase == Phase.LOADING && now - h.loadStartedAt > timeout) {
                    // A load that never returns must not wedge the sector forever: the
                    // record would stay LOADING, so retry would answer LOAD_IN_PROGRESS
                    // and begin() ALREADY_PENDING with no way out. Bumping the attempt
                    // fences the hung task, so its late completion is discarded instead
                    // of overwriting the retry that replaces it.
                    h.attempt++;
                    h.phase = Phase.FAILED;
                    h.failure = "LOAD_TIMEOUT";
                    publishLocked(h);
                    parked.add(h.sectorKey);
                    timedOutLoad = true;
                }
            }
        }
        for (Outbound o : out) {
            if (o.sessionId == null) Log.warn("HANDOFF_ABORTED sector=@ reason=TIMEOUT", o.payload.split("\\|")[0]);
        }
        flush(out);
        for (String key : parked) {
            Log.warn("HANDOFF_LOAD_TIMEOUT sector=@", key);
            parkIfHostActive(key);
        }
        if (timedOutLoad) replaceStuckLoader();
    }

    /**
     * Drop a loader thread that is stuck past the watchdog deadline and start a fresh
     * single-thread loader. Without this, one hung game-thread wait would queue every
     * later retry behind it and the sector would stay unserviceable even though the
     * record was fenced back to FAILED. The abandoned thread is interrupted; if it is
     * truly wedged it leaks one daemon thread and can no longer mutate manager state
     * (its attempt id is stale).
     */
    private void replaceStuckLoader() {
        if (!ownsExecutor) return;
        ExecutorService old = loadExecutor;
        loadExecutor = newLoadExecutor();
        if (old != null) old.shutdownNow();
        Log.warn("HANDOFF_LOADER_REPLACED after load timeout");
    }

    /**
     * Retry a failed host load/start while the lease is parked as RESERVED.
     *
     * <p>The returned value only describes whether the retry was <em>accepted</em>;
     * the load itself runs on the loader thread and its outcome is observable through
     * {@link #phase}/{@link #failure}. A non-null return means the retry was refused
     * outright (or could not be dispatched) and no load is running for it.
     *
     * @return null when the retry was accepted/dispatched, otherwise a refusal/failure reason
     */
    public String retryHost(String sectorKey) {
        if (!SectorSnapshotCodec.validSectorKey(sectorKey)) return "INVALID_SECTOR_KEY";
        LoadAttempt attempt;
        synchronized (stateLock) {
            if (closed) return "NOT_AVAILABLE";
            SectorOwnership own = registry.get(sectorKey);
            if (own == null || own.state != SectorOwnership.LeaseState.RESERVED) return "NOT_RESERVED";
            Handoff h = pending.get(sectorKey);
            if (h != null && h.phase == Phase.LOADING) return "LOAD_IN_PROGRESS";
            if (h == null) {
                h = new Handoff(sectorKey, ids.getAndIncrement(), SectorOwnership.HOST_SESSION, clock.getAsLong());
                pending.put(sectorKey, h);
            }
            if (h.snapshotRevision < 0) h.snapshotRevision = own.revisionVersion;
            h.releaseDone = true;
            h.failure = null;
            h.phase = Phase.AWAIT_RELEASE;
            if (h.snapshotRevision < 0) {
                h.phase = Phase.FAILED;
                h.failure = "NO_SNAPSHOT";
                publishLocked(h);
                return "NO_SNAPSHOT";
            }
            attempt = beginLoadLocked(h);
        }
        Log.info("HANDOFF_RETRY sector=@ id=@ attempt=@", sectorKey, attempt.handoffId, attempt.attempt);
        return submitLoad(attempt);
    }

    // ------------------------------------------------------------------- view

    /**
     * Status readers below are deliberately lock-free: they read the published
     * {@link View} and therefore cannot be delayed by a transition that is waiting on
     * the registry monitor or by a load in flight. A missing entry means "no handoff",
     * which is exactly what the UI renders.
     */
    public String phase(String sectorKey) {
        View v = view.get(sectorKey);
        return v == null ? Phase.NONE.name() : v.phase;
    }

    public String failure(String sectorKey) {
        View v = view.get(sectorKey);
        return v == null ? null : v.failure;
    }

    public long handoffId(String sectorKey) {
        View v = view.get(sectorKey);
        return v == null ? -1 : v.handoffId;
    }

    // --------------------------------------------------------------- internals

    /** Publish the lock-free status snapshot for one record. Called with {@link #stateLock} held. */
    private void publishLocked(Handoff h) {
        view.put(h.sectorKey, new View(h.phase.name(), h.failure, h.id));
    }

    /** Called with {@link #stateLock} held. */
    private LoadAttempt maybeAdvanceLocked(Handoff h) {
        if (h.phase == Phase.AWAIT_SNAPSHOT && h.snapshotRevision >= 0) {
            h.phase = Phase.AWAIT_RELEASE;
        }
        if (h.phase == Phase.AWAIT_RELEASE && h.snapshotRevision >= 0 && h.releaseDone) {
            return beginLoadLocked(h);
        }
        return null;
    }

    /** Called with {@link #stateLock} held: reserve the record for one load attempt. */
    private LoadAttempt beginLoadLocked(Handoff h) {
        h.attempt++;
        h.phase = Phase.LOADING;
        h.loadStartedAt = clock.getAsLong();
        publishLocked(h);
        return new LoadAttempt(h.sectorKey, h.id, h.attempt, h.snapshotRevision);
    }

    /** Dispatch a captured attempt; never blocks and never leaves the record in LOADING on rejection. */
    private String submitLoad(LoadAttempt attempt) {
        ExecutorService executor = loadExecutor;
        if (executor == null) {
            finishLoad(attempt, null, "NO_LOAD_EXECUTOR");
            return "NO_LOAD_EXECUTOR";
        }
        try {
            executor.execute(() -> runLoad(attempt));
            return null;
        } catch (RejectedExecutionException e) {
            // Shutdown started (or the queue refused): the work will never run, so the
            // record must not stay in LOADING forever.
            Log.err("HANDOFF_DISPATCH_REJECTED sector=@ : @", attempt.sectorKey, e.getMessage());
            finishLoad(attempt, null, "LOAD_NOT_DISPATCHED");
            return "LOAD_NOT_DISPATCHED";
        }
    }

    /** True while this exact attempt still owns the record (called without holding the lock). */
    private boolean isCurrent(LoadAttempt attempt) {
        synchronized (stateLock) {
            Handoff h = pending.get(attempt.sectorKey);
            return h != null && h.id == attempt.handoffId && h.attempt == attempt.attempt && h.phase == Phase.LOADING;
        }
    }

    /**
     * The slow half of the handoff. Runs on the loader thread with <em>no</em> monitor
     * held; every ownership transition is validated by the registry itself (session +
     * grant + ownershipVersion), and the terminal state write is fenced against
     * {@code handoffId + attempt}.
     */
    private void runLoad(LoadAttempt attempt) {
        if (!isCurrent(attempt)) {
            Log.warn("HANDOFF_STALE_ATTEMPT sector=@ id=@ attempt=@ (abandoned before start)",
                    attempt.sectorKey, attempt.handoffId, attempt.attempt);
            return;
        }
        if (attempt.snapshotRevision < 0) {
            finishLoad(attempt, null, "NO_SNAPSHOT");
            return;
        }
        // 1) Record the accepted revision before ownership moves (restart-safe: the
        //    lease and the revision agree on what the last good world is).
        try {
            SectorRegistry.Result rev = registry.setAcceptedRevision(attempt.sectorKey, attempt.snapshotRevision);
            if (rev != SectorRegistry.Result.OK) {
                finishLoad(attempt, null, "REVISION_RECORD_REFUSED:" + rev.name());
                return;
            }
        } catch (Exception e) {
            Log.err("HANDOFF_REVISION_FAILED sector=@ : @", attempt.sectorKey, e.getMessage());
            finishLoad(attempt, null, "PERSISTENCE_FAILURE");
            return;
        }
        // 2) Transfer ownership to the host (FREE -> HOST ACTIVE).
        try {
            String grant = registry.claimSectorAsHost(attempt.sectorKey, attempt.snapshotRevision);
            if (grant == null) {
                finishLoad(attempt, null, "HOST_CLAIM_REFUSED");
                return;
            }
            SectorOwnership claimed = registry.get(attempt.sectorKey);
            if (claimed != null) attempt.claimVersion = claimed.ownershipVersion;
        } catch (Exception e) {
            Log.err("HANDOFF_CLAIM_FAILED sector=@ : @", attempt.sectorKey, e.getMessage());
            finishLoad(attempt, null, "PERSISTENCE_FAILURE");
            return;
        }
        // Superseded (dispose) while the claim was happening: never start a runtime for
        // an attempt that no longer owns the record.
        if (!isCurrent(attempt)) {
            Log.warn("HANDOFF_STALE_ATTEMPT sector=@ id=@ attempt=@ (abandoned after claim)",
                    attempt.sectorKey, attempt.handoffId, attempt.attempt);
            finishLoad(attempt, null, "SUPERSEDED");
            return;
        }
        // 3) The host machine loads the exact stored snapshot.
        boolean loaded = false;
        try {
            loaded = ops != null && ops.loadSnapshot(attempt.sectorKey);
        } catch (Exception e) {
            Log.err("HANDOFF_LOAD_FAILED sector=@ : @", attempt.sectorKey, e);
        }
        if (!loaded) {
            finishLoad(attempt, null, "HOST_LOAD_FAILED");
            return;
        }
        // Superseded while the load was running (dispose/close): do not start a runtime
        // for an attempt that no longer owns the record. Without this check a disposed
        // controller could still bind a P2 server and only stop it afterwards.
        if (!isCurrent(attempt)) {
            Log.warn("HANDOFF_STALE_ATTEMPT sector=@ id=@ attempt=@ (abandoned after load)",
                    attempt.sectorKey, attempt.handoffId, attempt.attempt);
            finishLoad(attempt, null, "SUPERSEDED");
            return;
        }
        // 4) Ordinary Mindustry P2 host for the sector.
        String endpoint = null;
        try {
            endpoint = ops.startServer(attempt.sectorKey);
        } catch (Exception e) {
            Log.err("HANDOFF_SERVER_FAILED sector=@ : @", attempt.sectorKey, e);
        }
        if (endpoint == null) {
            stopQuietly(attempt.sectorKey);
            finishLoad(attempt, null, "SERVER_START_FAILED");
            return;
        }
        // Superseded while starting the server: startP2() is idempotent, so this attempt
        // may have observed the runtime the retry started and is holding a port it never
        // bound. Recording that endpoint would point the sector at a dead port, so a
        // superseded attempt never writes it.
        if (!isCurrent(attempt)) {
            Log.warn("HANDOFF_STALE_ATTEMPT sector=@ id=@ attempt=@ (abandoned after server start)",
                    attempt.sectorKey, attempt.handoffId, attempt.attempt);
            finishLoad(attempt, endpoint, "SUPERSEDED");
            return;
        }
        // 5) Record the advertised endpoint; this is what makes the sector joinable.
        SectorRegistry.Result ep;
        try {
            ep = registry.setEndpoint(attempt.sectorKey, SectorOwnership.HOST_SESSION, endpoint);
        } catch (Exception e) {
            ep = SectorRegistry.Result.BAD_REQUEST;
        }
        if (ep != SectorRegistry.Result.OK) {
            stopQuietly(attempt.sectorKey);
            finishLoad(attempt, null, "ENDPOINT_RECORD_FAILED");
            return;
        }
        finishLoad(attempt, endpoint, null);
    }

    /**
     * Commit the terminal state of one attempt. Parking happens before the phase write
     * so a reader never observes FAILED while the lease is still HOST ACTIVE; the phase
     * write itself is fenced, so a stale attempt can never overwrite a newer handoff,
     * mark the wrong handoff SERVING, or park a lease it does not own.
     */
    private void finishLoad(LoadAttempt attempt, String endpoint, String failure) {
        // Park the lease before publishing FAILED, so a reader never sees FAILED while the
        // lease is still HOST ACTIVE. Done outside the state lock (journal write) and
        // fenced by the version this attempt claimed, so it can only park its own lease.
        if (failure != null && attempt.claimVersion >= 0) {
            parkIfHostActive(attempt.sectorKey, attempt.claimVersion);
        }
        boolean stale;
        boolean stopStale = false;
        synchronized (stateLock) {
            Handoff h = pending.get(attempt.sectorKey);
            stale = h == null || h.id != attempt.handoffId || h.attempt != attempt.attempt || h.phase != Phase.LOADING;
            if (!stale) {
                if (failure == null) {
                    h.phase = Phase.SERVING;
                    h.failure = null;
                } else {
                    h.phase = Phase.FAILED;
                    h.failure = failure;
                }
                publishLocked(h);
            } else if (endpoint != null) {
                // Never stop a server that a newer attempt is serving or about to serve:
                // the P2 runtime is process-global, so an unconditional stop here would
                // kill the live endpoint of the retry that replaced this attempt.
                stopStale = !hasLiveHandoffLocked(attempt.sectorKey);
            }
        }
        if (stale) {
            Log.warn("HANDOFF_STALE_ATTEMPT sector=@ id=@ attempt=@ (result discarded: @)",
                    attempt.sectorKey, attempt.handoffId, attempt.attempt, failure == null ? "SERVING" : failure);
            if (stopStale) stopQuietly(attempt.sectorKey);
            return;
        }
        if (failure == null) {
            long version = -1;
            SectorOwnership own = registry.get(attempt.sectorKey);
            if (own != null) version = own.ownershipVersion;
            Log.info("HANDOFF_COMPLETE sector=@ endpoint=@", attempt.sectorKey, endpoint);
            broadcast(AcoMessageType.SECTOR_HOST_CHANGED,
                    attempt.sectorKey + "|HOST|" + endpoint + "|" + version);
        } else {
            Log.warn("HANDOFF_FAILED sector=@ reason=@", attempt.sectorKey, failure);
            broadcast(AcoMessageType.SECTOR_HOST_CHANGED, attempt.sectorKey + "|FAILED|" + failure);
        }
    }

    /** Park a host lease whose runtime failed; claims stay blocked until retry/forceFree. */
    private void parkIfHostActive(String sectorKey) {
        parkIfHostActive(sectorKey, -1);
    }

    /**
     * Version-fenced park. {@code expectedVersion < 0} means "whatever lease is HOST
     * ACTIVE right now", which is only correct for a deliberate teardown (dispose/watchdog);
     * the normal failure path passes the version it claimed.
     */
    private void parkIfHostActive(String sectorKey, long expectedVersion) {
        try {
            SectorOwnership own = registry.get(sectorKey);
            if (own == null || own.state != SectorOwnership.LeaseState.ACTIVE
                    || own.kind != SectorOwnership.OwnerKind.HOST) {
                return;
            }
            SectorRegistry.Result r = registry.reserveHostLease(sectorKey, expectedVersion);
            if (r == SectorRegistry.Result.STALE_VERSION) {
                Log.warn("HANDOFF_PARK_SUPERSEDED sector=@ expected=@ ", sectorKey, expectedVersion);
            }
        } catch (Exception e) {
            Log.err("HANDOFF_PARK_FAILED sector=@ : @", sectorKey, e.getMessage());
        }
    }

    /** True when a handoff for this sector is actively loading or serving. Lock held. */
    private boolean hasLiveHandoffLocked(String sectorKey) {
        Handoff h = pending.get(sectorKey);
        return h != null && (h.phase == Phase.LOADING || h.phase == Phase.SERVING);
    }

    private void stopQuietly(String sectorKey) {
        try {
            if (ops != null) ops.stopServer(sectorKey);
        } catch (Exception ignored) {
        }
    }

    /**
     * Stop accepting work, fail any handoff still waiting on a load, and release the
     * loader. A load that is mid-flight is abandoned by fence: it can no longer write
     * state, and it stops the runtime it started.
     */
    public void close() {
        List<String> abandoned = new ArrayList<>(1);
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            for (Handoff h : pending.values()) {
                if (h.phase == Phase.LOADING) {
                    h.phase = Phase.FAILED;
                    h.failure = "DISPOSED";
                    publishLocked(h);
                    abandoned.add(h.sectorKey);
                }
            }
        }
        for (String key : abandoned) {
            Log.warn("HANDOFF_ABANDONED sector=@ reason=DISPOSED", key);
            parkIfHostActive(key);
        }
        if (ownsExecutor && loadExecutor != null) loadExecutor.shutdownNow();
    }

    private void flush(List<Outbound> out) {
        for (Outbound o : out) {
            try {
                if (o.sessionId == null) {
                    if (broadcaster != null) broadcaster.broadcast(o.type, o.payload);
                } else if (notifier != null) {
                    notifier.send(o.sessionId, o.type, o.payload);
                }
            } catch (RuntimeException e) {
                Log.err("HANDOFF_NOTIFY_FAILED : @", e.getMessage());
            }
        }
    }

    private void broadcast(AcoMessageType type, String payload) {
        try {
            if (broadcaster != null) broadcaster.broadcast(type, payload);
        } catch (RuntimeException e) {
            Log.err("HANDOFF_BROADCAST_FAILED : @", e.getMessage());
        }
    }
}

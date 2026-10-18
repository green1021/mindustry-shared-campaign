package sc.aco;

import arc.util.Log;
import java.util.LinkedHashMap;
import java.util.Map;
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
        Phase phase = Phase.AWAIT_SNAPSHOT;
        String failure;

        Handoff(String sectorKey, long id, String ownerSession, long startedAt) {
            this.sectorKey = sectorKey;
            this.id = id;
            this.ownerSession = ownerSession;
            this.startedAt = startedAt;
        }
    }

    private final SectorRegistry registry;
    private final HostRuntimeOps ops; // nullable: authority-only deployments refuse at load stage
    private final SessionNotifier notifier;
    private final Broadcaster broadcaster;
    private final LongSupplier clock;

    private final Map<String, Handoff> pending = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private long timeoutMs = 120_000;

    public SectorHandoffManager(SectorRegistry registry, HostRuntimeOps ops, SessionNotifier notifier, Broadcaster broadcaster) {
        this(registry, ops, notifier, broadcaster, System::currentTimeMillis);
    }

    public SectorHandoffManager(SectorRegistry registry, HostRuntimeOps ops, SessionNotifier notifier, Broadcaster broadcaster,
            LongSupplier clock) {
        this.registry = registry;
        this.ops = ops;
        this.notifier = notifier;
        this.broadcaster = broadcaster;
        this.clock = clock;
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
    public synchronized String begin(String sectorKey) {
        if (!SectorSnapshotCodec.validSectorKey(sectorKey)) return "INVALID_SECTOR_KEY";
        SectorOwnership own = registry.get(sectorKey);
        if (own == null || !own.active() || own.kind != SectorOwnership.OwnerKind.CLIENT || own.ownerSessionId == null) {
            return "SECTOR_NOT_CLIENT_OWNED";
        }
        Handoff existing = pending.get(sectorKey);
        if (existing != null && (existing.phase == Phase.AWAIT_SNAPSHOT || existing.phase == Phase.AWAIT_RELEASE
                || existing.phase == Phase.LOADING)) {
            return "ALREADY_PENDING";
        }

        Handoff h = new Handoff(sectorKey, ids.getAndIncrement(), own.ownerSessionId, clock.getAsLong());
        pending.put(sectorKey, h);
        Log.info("HANDOFF_BEGIN id=@ sector=@ owner=@", h.id, sectorKey, h.ownerSession);
        boolean sent = notifier != null && notifier.send(h.ownerSession, AcoMessageType.SECTOR_HANDOFF_REQUEST, sectorKey + "|" + h.id);
        if (!sent) {
            h.phase = Phase.ABORTED;
            h.failure = "NO_ROUTE";
            broadcast(AcoMessageType.SECTOR_HANDOFF_ABORT, sectorKey + "|ABORTED|NO_ROUTE");
            return "NO_ROUTE";
        }
        return null;
    }

    // ---------------------------------------------------------------- progress

    /** Called by the server after a validated snapshot for the sector was committed. */
    public synchronized void onSnapshotCommitted(String sectorKey, long revision, String sha) {
        Handoff h = pending.get(sectorKey);
        if (h == null || h.phase != Phase.AWAIT_SNAPSHOT) return;
        h.snapshotRevision = revision;
        h.snapshotSha = sha;
        h.phase = Phase.AWAIT_RELEASE;
        Log.info("HANDOFF_SNAPSHOT_COMMITTED sector=@ revision=@", sectorKey, revision);
        maybeAdvance(h);
    }

    /** Called by the server after the owner connection released its lease. */
    public synchronized void onReleased(String sectorKey) {
        Handoff h = pending.get(sectorKey);
        if (h == null || (h.phase != Phase.AWAIT_SNAPSHOT && h.phase != Phase.AWAIT_RELEASE)) return;
        h.releaseDone = true;
        Log.info("HANDOFF_RELEASED sector=@ snapshotCommitted=@", sectorKey, h.snapshotRevision >= 0);
        maybeAdvance(h);
    }

    /** Client-side failure or explicit abort ({@code SECTOR_HANDOFF_ABORT} from the owner). */
    public synchronized void abort(String sectorKey, String reason) {
        Handoff h = pending.get(sectorKey);
        if (h == null || h.phase == Phase.SERVING || h.phase == Phase.LOADING) return; // too late: let the load path conclude
        if (h.phase == Phase.AWAIT_SNAPSHOT) {
            notifierSend(h.ownerSession, AcoMessageType.SECTOR_HANDOFF_ABORT, sectorKey + "|ABORT|" + reason);
        }
        h.phase = Phase.ABORTED;
        h.failure = reason;
        broadcast(AcoMessageType.SECTOR_HANDOFF_ABORT, sectorKey + "|ABORTED|" + reason);
        Log.warn("HANDOFF_ABORTED sector=@ reason=@", sectorKey, reason);
    }

    /** Owner connection went away. Abort unless the handoff already passed the release. */
    public synchronized void onOwnerDisconnected(String sessionId) {
        if (sessionId == null) return;
        for (Handoff h : pending.values()) {
            if (h.ownerSession.equals(sessionId)
                    && (h.phase == Phase.AWAIT_SNAPSHOT || h.phase == Phase.AWAIT_RELEASE)) {
                h.phase = Phase.ABORTED;
                h.failure = "OWNER_DISCONNECTED";
                broadcast(AcoMessageType.SECTOR_HANDOFF_ABORT, h.sectorKey + "|ABORTED|OWNER_DISCONNECTED");
                Log.warn("HANDOFF_ABORTED sector=@ reason=OWNER_DISCONNECTED", h.sectorKey);
            }
        }
    }

    /** Periodic timeout sweep; call from a watchdog (or tests) with a monotonic ms clock. */
    public synchronized void tick() {
        long now = clock.getAsLong();
        for (Handoff h : pending.values()) {
            if ((h.phase == Phase.AWAIT_SNAPSHOT || h.phase == Phase.AWAIT_RELEASE) && now - h.startedAt > timeoutMs) {
                if (h.phase == Phase.AWAIT_SNAPSHOT) {
                    notifierSend(h.ownerSession, AcoMessageType.SECTOR_HANDOFF_ABORT, h.sectorKey + "|ABORT|TIMEOUT");
                }
                h.phase = Phase.ABORTED;
                h.failure = "TIMEOUT";
                broadcast(AcoMessageType.SECTOR_HANDOFF_ABORT, h.sectorKey + "|ABORTED|TIMEOUT");
                Log.warn("HANDOFF_ABORTED sector=@ reason=TIMEOUT", h.sectorKey);
            }
        }
    }

    /**
     * Retry a failed host load/start while the lease is parked as RESERVED.
     *
     * @return null on success (phase SERVING), otherwise a refusal/failure reason
     */
    public synchronized String retryHost(String sectorKey) {
        SectorOwnership own = registry.get(sectorKey);
        if (own == null || own.state != SectorOwnership.LeaseState.RESERVED) return "NOT_RESERVED";
        Handoff h = pending.get(sectorKey);
        if (h == null) {
            h = new Handoff(sectorKey, ids.getAndIncrement(), SectorOwnership.HOST_SESSION, clock.getAsLong());
            pending.put(sectorKey, h);
        }
        if (h.snapshotRevision < 0) h.snapshotRevision = own.revisionVersion;
        h.phase = Phase.AWAIT_RELEASE;
        h.releaseDone = true;
        hostLoad(h);
        return h.phase == Phase.SERVING ? null : h.failure;
    }

    // ------------------------------------------------------------------- view

    public synchronized String phase(String sectorKey) {
        Handoff h = pending.get(sectorKey);
        return h == null ? Phase.NONE.name() : h.phase.name();
    }

    public synchronized String failure(String sectorKey) {
        Handoff h = pending.get(sectorKey);
        return h == null ? null : h.failure;
    }

    public synchronized long handoffId(String sectorKey) {
        Handoff h = pending.get(sectorKey);
        return h == null ? -1 : h.id;
    }

    // --------------------------------------------------------------- internals

    private void maybeAdvance(Handoff h) {
        if (h.phase == Phase.AWAIT_SNAPSHOT && h.snapshotRevision >= 0) {
            h.phase = Phase.AWAIT_RELEASE;
        }
        if (h.phase == Phase.AWAIT_RELEASE && h.snapshotRevision >= 0 && h.releaseDone) {
            hostLoad(h);
        }
    }

    private void hostLoad(Handoff h) {
        h.phase = Phase.LOADING;
        if (h.snapshotRevision < 0) {
            fail(h, "NO_SNAPSHOT");
            return;
        }
        // 1) Record the accepted revision before ownership moves (restart-safe: the
        //    lease and the revision agree on what the last good world is).
        SectorRegistry.Result rev = registry.setAcceptedRevision(h.sectorKey, h.snapshotRevision);
        if (rev != SectorRegistry.Result.OK) {
            fail(h, "REVISION_RECORD_REFUSED:" + rev.name());
            return;
        }
        // 2) Transfer ownership to the host (FREE -> HOST ACTIVE).
        String grant;
        try {
            grant = registry.claimSectorAsHost(h.sectorKey, h.snapshotRevision);
        } catch (SectorLeaseJournal.LeaseJournalException e) {
            fail(h, "PERSISTENCE_FAILURE");
            return;
        }
        if (grant == null) {
            fail(h, "HOST_CLAIM_REFUSED");
            return;
        }
        // 3) The host machine loads the exact stored snapshot.
        boolean loaded = false;
        try {
            loaded = ops != null && ops.loadSnapshot(h.sectorKey);
        } catch (Exception e) {
            Log.err("HANDOFF_LOAD_FAILED sector=@ : @", h.sectorKey, e);
        }
        if (!loaded) {
            park(h);
            fail(h, "HOST_LOAD_FAILED");
            return;
        }
        // 4) Ordinary Mindustry P2 host for the sector.
        String endpoint = null;
        try {
            endpoint = ops.startServer(h.sectorKey);
        } catch (Exception e) {
            Log.err("HANDOFF_SERVER_FAILED sector=@ : @", h.sectorKey, e);
        }
        if (endpoint == null) {
            try {
                ops.stopServer(h.sectorKey);
            } catch (Exception ignored) {
            }
            park(h);
            fail(h, "SERVER_START_FAILED");
            return;
        }
        // 5) Record the advertised endpoint; this is what makes the sector joinable.
        SectorRegistry.Result ep;
        try {
            ep = registry.setEndpoint(h.sectorKey, SectorOwnership.HOST_SESSION, endpoint);
        } catch (SectorLeaseJournal.LeaseJournalException e) {
            ep = SectorRegistry.Result.BAD_REQUEST;
        }
        if (ep != SectorRegistry.Result.OK) {
            try {
                ops.stopServer(h.sectorKey);
            } catch (Exception ignored) {
            }
            park(h);
            fail(h, "ENDPOINT_RECORD_FAILED");
            return;
        }
        h.phase = Phase.SERVING;
        Log.info("HANDOFF_COMPLETE sector=@ endpoint=@", h.sectorKey, endpoint);
        broadcast(AcoMessageType.SECTOR_HOST_CHANGED,
                h.sectorKey + "|HOST|" + endpoint + "|" + registry.get(h.sectorKey).ownershipVersion);
    }

    /** Park a host lease whose runtime failed; claims stay blocked until retry/forceFree. */
    private void park(Handoff h) {
        try {
            registry.reserveHostLease(h.sectorKey);
        } catch (SectorLeaseJournal.LeaseJournalException e) {
            Log.err("HANDOFF_PARK_FAILED sector=@ : @", h.sectorKey, e.getMessage());
        }
    }

    private void fail(Handoff h, String reason) {
        h.phase = Phase.FAILED;
        h.failure = reason;
        Log.warn("HANDOFF_FAILED sector=@ reason=@", h.sectorKey, reason);
        broadcast(AcoMessageType.SECTOR_HOST_CHANGED, h.sectorKey + "|FAILED|" + reason);
    }

    private void notifierSend(String sessionId, AcoMessageType type, String payload) {
        if (notifier != null) notifier.send(sessionId, type, payload);
    }

    private void broadcast(AcoMessageType type, String payload) {
        if (broadcaster != null) broadcaster.broadcast(type, payload);
    }
}

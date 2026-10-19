package sc.aco;

import arc.util.Log;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authoritative executor of global resource transactions.
 *
 * <h2>Idempotency</h2>
 * Every recorded operation is stored as an {@link IdempotentEntry} keyed by
 * operation id. The entry carries the full {@link RequestFingerprint}. A repeated
 * operation id returns the original {@link TransactionResult} when the incoming
 * fingerprint is identical, and fail-stop {@code IDEMPOTENCY_PAYLOAD_MISMATCH}
 * (no state change) when any protected field differs. Stored entries that carry
 * no fingerprint (legacy snapshot data, foreign values) are never treated as a
 * match: {@code INCOMPATIBLE_IDEMPOTENCY_TYPE}.
 *
 * <h2>Commit protocol (WAL-first)</h2>
 * For a state-mutating transaction:
 * <ol>
 *   <li>append the full record to the write-ahead log and force it to disk —
 *       <em>this is the commit point</em>;</li>
 *   <li>apply the mutation to the in-memory state;</li>
 *   <li>write the snapshot (a checkpoint, not the commit).</li>
 * </ol>
 * A WAL append failure happens before any state change: the request is answered
 * {@code PERSISTENCE_FAILURE} and nothing is committed, so a retry is safe. A
 * snapshot failure <em>after</em> a durable WAL append cannot lose the operation —
 * recovery replays the WAL — so the request still answers with the committed
 * result; the failure is counted ({@link #snapshotFailureCount()}) and logged, and
 * the next successful save (or {@code CampaignPersistence.recover()}) reconciles
 * the snapshot. No path reports an error for an operation that may already be
 * durable. Rejected (non-mutating) transactions never touch the WAL and never
 * change state, so they are simply re-evaluated after a restart.
 */
public class GlobalBank {
    /**
     * Ceiling on distinct resource keys in the bank table. The table is persisted in
     * the campaign snapshot and shipped in every state sync, so an unbounded key set
     * is both a disk-growth and a frame-size problem: a client that credits one new
     * (valid, harmless-looking) item name per request would grow it forever.
     */
    public static final int MAX_BANK_ITEMS = 256;
    /** Content-name shape for a resource key; bounded before it reaches any map. */
    private static final java.util.regex.Pattern RESOURCE_NAME =
        java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    private final GlobalCampaignState state;
    private final CampaignPersistence persistence;
    private final AtomicLong snapshotFailures = new AtomicLong();

    public GlobalBank(GlobalCampaignState state, CampaignPersistence persistence) {
        this.state = state;
        this.persistence = persistence;
    }

    /**
     * Serialize every mutation of this campaign state on the state object itself.
     * The bank and the authority's progression handlers (research/completion) share
     * that monitor, so a snapshot save can never observe a half-applied promotion
     * or a replayed operation. The lock is per campaign state, not global.
     */
    public TransactionResult process(GlobalResourceRequest req) {
        synchronized (state) {
            return processLocked(req);
        }
    }

    private TransactionResult processLocked(GlobalResourceRequest req) {
        if (req == null) {
            return new TransactionResult("null", false, "INVALID_REQUEST", 0, 0);
        }

        if (req.operationId() == null || req.operationId().isBlank()
            || req.sessionId() == null || req.sessionId().isBlank()
            || req.sectorId() == null || req.sectorId().isBlank()
            || req.resourceType() == null || req.resourceType().isBlank()
            || !RESOURCE_NAME.matcher(req.resourceType()).matches()
            || req.operationType() == null) {
            String opId = req.operationId() != null ? req.operationId() : "unknown";
            return new TransactionResult(opId, false, "INVALID_REQUEST_FIELDS",
                state.globalRevision, getBalance(req.resourceType()));
        }

        if (req.amount() <= 0) {
            return new TransactionResult(req.operationId(), false, "INVALID_AMOUNT",
                state.globalRevision, getBalance(req.resourceType()));
        }

        RequestFingerprint incoming = new RequestFingerprint(
            req.operationId(), req.sessionId(), req.sectorId(), req.expectedGlobalRevision(),
            req.resourceType(), req.amount(), req.operationType().name());

        Object stored = state.processedOperations.get(req.operationId());
        if (stored != null) {
            if (stored instanceof IdempotentEntry entry) {
                if (!entry.fingerprint().equals(incoming)) {
                    return new TransactionResult(req.operationId(), false, "IDEMPOTENCY_PAYLOAD_MISMATCH",
                        state.globalRevision, getBalance(req.resourceType()));
                }
                // Identical retry: return the original result, apply nothing.
                return entry.result();
            }
            // Legacy or foreign entry (e.g. a completion marker, or a pre-fingerprint
            // snapshot record): its payload cannot be verified, so the duplicate is
            // never treated as the same request.
            return new TransactionResult(req.operationId(), false, "INCOMPATIBLE_IDEMPOTENCY_TYPE",
                state.globalRevision, getBalance(req.resourceType()));
        }

        // New key at capacity: refuse instead of growing the persisted table. Placed
        // after the duplicate lookup so a retry of an already-recorded operation is
        // still answered from its entry rather than hitting this ceiling.
        if (!state.globalBank.containsKey(req.resourceType())
                && state.globalBank.size() >= MAX_BANK_ITEMS) {
            return new TransactionResult(req.operationId(), false, "BANK_CAPACITY",
                state.globalRevision, 0L);
        }

        if (req.expectedGlobalRevision() != state.globalRevision) {
            TransactionResult rej = new TransactionResult(req.operationId(), false, "STALE_REVISION",
                state.globalRevision, getBalance(req.resourceType()));
            state.recordProcessed(req.operationId(), new IdempotentEntry(incoming, rej));
            return rej;
        }

        long current = getBalance(req.resourceType());
        long newBalance = current;
        boolean success = false;
        String error = null;

        switch (req.operationType()) {
            case CREDIT, DEPOSIT -> {
                if (current > Long.MAX_VALUE - req.amount()) {
                    error = "OVERFLOW";
                } else {
                    newBalance = current + req.amount();
                    success = true;
                }
            }
            case DEBIT, WITHDRAW -> {
                if (current < req.amount()) {
                    error = "INSUFFICIENT_BALANCE";
                } else {
                    newBalance = current - req.amount();
                    success = true;
                }
            }
            default -> error = "UNSUPPORTED_OPERATION_TYPE";
        }

        TransactionResult result = new TransactionResult(req.operationId(), success, error,
            success ? state.globalRevision + 1 : state.globalRevision,
            success ? newBalance : current);

        if (!success) {
            state.recordProcessed(req.operationId(), new IdempotentEntry(incoming, result));
            return result;
        }

        // Phase 1 — durable commit point: the WAL record must land before any
        // in-memory mutation. Failure here means nothing happened; the caller may
        // safely retry the same operation id.
        try {
            persistence.appendWAL(new TransactionLogEntry(req, result, System.currentTimeMillis()));
        } catch (IOException e) {
            Log.err("ACO_WAL_APPEND_FAILED op=@", req.operationId(), e);
            return new TransactionResult(req.operationId(), false, "PERSISTENCE_FAILURE",
                state.globalRevision, current);
        }

        // Phase 2 — apply to memory. The operation is now committed and will replay
        // as committed after a crash, so no later failure may be reported as a
        // rejection.
        state.globalBank.put(req.resourceType(), newBalance);
        state.globalRevision++;
        state.recordProcessed(req.operationId(), new IdempotentEntry(incoming, result));

        // Phase 3 — snapshot checkpoint. A failure here cannot lose the committed
        // operation (the WAL holds it) and recovery reconciles, so the committed
        // result is still returned.
        try {
            persistence.save(state);
        } catch (IOException e) {
            snapshotFailures.incrementAndGet();
            Log.err("ACO_SNAPSHOT_SAVE_FAILED op=@ (operation committed in WAL; recovery will reconcile the snapshot)",
                req.operationId(), e);
        }

        return result;
    }

    /** Snapshot saves that failed after a durable WAL commit; recovered by replay. */
    public long snapshotFailureCount() {
        return snapshotFailures.get();
    }

    public long getBalance(String resourceType) {
        if (resourceType == null) return 0L;
        return state.globalBank.getOrDefault(resourceType, 0L);
    }

    /** A recorded operation: the full payload fingerprint plus the served result. */
    public record IdempotentEntry(RequestFingerprint fingerprint, TransactionResult result) {
    }
}

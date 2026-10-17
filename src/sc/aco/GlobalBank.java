package sc.aco;

import arc.util.Log;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalBank {
    private final GlobalCampaignState state;
    private final CampaignPersistence persistence;

    public GlobalBank(GlobalCampaignState state, CampaignPersistence persistence) {
        this.state = state;
        this.persistence = persistence;
    }

    public synchronized TransactionResult process(GlobalResourceRequest req) {
        if (req == null) {
            return new TransactionResult("null", false, "INVALID_REQUEST", 0, 0);
        }
        if (req.operationId() == null || req.operationId().isEmpty() ||
            req.sessionId() == null || req.sessionId().isEmpty() ||
            req.sectorId() == null || req.sectorId().isEmpty() ||
            req.resourceType() == null || req.resourceType().isEmpty() ||
            req.operationType() == null) {
            String opId = req.operationId() != null ? req.operationId() : "unknown";
            return new TransactionResult(opId, false, "MISSING_REQUIRED_FIELDS", state.globalRevision, 0);
        }

        Log.info("BANK_REQ: " + req.operationType() + " val: " + req.amount() + " cur: " + getBalance(req.resourceType()));
        // Idempotency check
        if (state.processedOperations.containsKey(req.operationId())) {
            Object op = state.processedOperations.get(req.operationId());
            if (op instanceof TransactionResult) {
                Log.info("GLOBAL_BANK: Duplicate operation @", req.operationId());
                return (TransactionResult) op;
            }
            Log.err("GLOBAL_BANK: Incompatible idempotency type for @", req.operationId());
            return new TransactionResult(req.operationId(), false, "INCOMPATIBLE_IDEMPOTENCY_TYPE", state.globalRevision, getBalance(req.resourceType()));
        }

        // Revision validation
        if (req.expectedGlobalRevision() != state.globalRevision) {
            TransactionResult rej = new TransactionResult(req.operationId(), false, "STALE_REVISION", state.globalRevision, getBalance(req.resourceType()));
            state.processedOperations.put(req.operationId(), rej);
            return rej;
        }

        long current = getBalance(req.resourceType());
        long delta = req.amount();
        long newBalance = current;
        boolean success = false;
        String error = null;

        switch (req.operationType()) {
            case CREDIT:
                if (delta <= 0) {
                    error = "INVALID_AMOUNT";
                } else if (current > Long.MAX_VALUE - delta) {
                    error = "OVERFLOW";
                } else {
                    newBalance = current + delta;
                    success = true;
                }
                break;
            case DEBIT:
                if (delta <= 0) {
                    error = "INVALID_AMOUNT";
                } else if (current < delta) {
                    error = "INSUFFICIENT_BALANCE";
                } else {
                    newBalance = current - delta;
                    success = true;
                }
                break;
            case WITHDRAW:
                if (delta <= 0) {
                    error = "INVALID_AMOUNT";
                } else if (current >= delta) {
                    newBalance = current - delta;
                    success = true;
                } else {
                    error = "INSUFFICIENT_BALANCE";
                }
                break;
            case DEPOSIT:
                if (delta <= 0) {
                    error = "INVALID_AMOUNT";
                } else if (current > Long.MAX_VALUE - delta) {
                    error = "OVERFLOW";
                } else {
                    newBalance = current + delta;
                    success = true;
                }
                break;
        }

        TransactionResult result = new TransactionResult(req.operationId(), success, error, success ? state.globalRevision + 1 : state.globalRevision, success ? newBalance : current);

        if (success) {
            try {
                persistence.appendWAL(new TransactionLogEntry(req, result, System.currentTimeMillis()));
                persistence.save(state);
            } catch (IOException e) {
                Log.err("PERSISTENCE_FAILURE", e);
                return new TransactionResult(req.operationId(), false, "PERSISTENCE_FAILURE", state.globalRevision, current);
            }
            state.globalBank.put(req.resourceType(), newBalance);
            state.globalRevision++;
            state.processedOperations.put(req.operationId(), result);
        } else {
            state.processedOperations.put(req.operationId(), result);
        }

        return result;
    }

    public long getBalance(String resourceType) {
        if (resourceType == null) return 0L;
        return state.globalBank.getOrDefault(resourceType, 0L);
    }
}
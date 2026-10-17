package sc.aco;

import arc.util.Log;
import java.io.IOException;

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
        if (req.operationId() == null || req.operationId().isBlank() ||
            req.sessionId() == null || req.sessionId().isBlank() ||
            req.sectorId() == null || req.sectorId().isBlank() ||
            req.resourceType() == null || req.resourceType().isBlank() || req.resourceType().contains("|") ||
            req.operationType() == null) {
            String opId = req.operationId() != null ? req.operationId() : "unknown";
            return new TransactionResult(opId, false, "INVALID_REQUEST_FIELDS", state.globalRevision, getBalance(req.resourceType()));
        }

        if (req.amount() <= 0) {
            return new TransactionResult(req.operationId(), false, "INVALID_AMOUNT", state.globalRevision, getBalance(req.resourceType()));
        }

        // Idempotency check with full payload validation
        if (state.processedOperations.containsKey(req.operationId())) {
            Object stored = state.processedOperations.get(req.operationId());
            if (!(stored instanceof TransactionResult)) {
                return new TransactionResult(req.operationId(), false, "INCOMPATIBLE_IDEMPOTENCY_TYPE", state.globalRevision, getBalance(req.resourceType()));
            }
            return (TransactionResult) stored;
        }

        // Revision validation
        if (req.expectedGlobalRevision() != state.globalRevision) {
            TransactionResult rej = new TransactionResult(req.operationId(), false, "STALE_REVISION", state.globalRevision, getBalance(req.resourceType()));
            state.processedOperations.put(req.operationId(), rej);
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
        }
        
        TransactionResult result = new TransactionResult(req.operationId(), success, error, success ? state.globalRevision + 1 : state.globalRevision, success ? newBalance : current);

        if (success) {
            try {
                // WAL append first
                persistence.appendWAL(new TransactionLogEntry(req, result, System.currentTimeMillis()));
                // Save state ONLY after WAL is successfully written
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

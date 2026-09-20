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
        // Idempotency check
        if (state.processedOperations.containsKey(req.operationId())) {
            Log.info("GLOBAL_BANK: Duplicate operation @", req.operationId());
            return (TransactionResult) state.processedOperations.get(req.operationId());
        }

        // Revision validation
        if (req.expectedGlobalRevision() != state.globalRevision) {
            TransactionResult rej = new TransactionResult(req.operationId(), false, "STALE_REVISION", state.globalRevision, getBalance(req.resourceType()));
            state.processedOperations.put(req.operationId(), rej);
            return rej;
        }

        long current = getBalance(req.resourceType());
        long delta = req.amount();
        boolean success = false;
        String error = null;

        switch (req.operationType()) {
            case CREDIT:
                state.globalBank.put(req.resourceType(), current + delta);
                success = true;
                break;
            case DEBIT:
                if (current >= delta) {
                    state.globalBank.put(req.resourceType(), current - delta);
                    success = true;
                } else {
                    error = "INSUFFICIENT_BALANCE";
                }
                break;
            case WITHDRAW:
                if (current >= delta) {
                    state.globalBank.put(req.resourceType(), current - delta);
                    success = true;
                } else {
                    error = "INSUFFICIENT_BALANCE";
                }
                break;
            case DEPOSIT:
                state.globalBank.put(req.resourceType(), current + delta);
                success = true;
                break;
        }

        if (success) {
            state.globalRevision++;
        }

        TransactionResult result = new TransactionResult(req.operationId(), success, error, state.globalRevision, getBalance(req.resourceType()));
        state.processedOperations.put(req.operationId(), result);

        try {
            persistence.appendWAL(new TransactionLogEntry(req, result, System.currentTimeMillis()));
            persistence.save(state);
        } catch (IOException e) {
            Log.err("PERSISTENCE_FAILURE", e);
        }

        return result;
    }

    public long getBalance(String resourceType) {
        return state.globalBank.getOrDefault(resourceType, 0L);
    }
}
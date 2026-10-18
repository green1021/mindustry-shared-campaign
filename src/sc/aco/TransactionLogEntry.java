package sc.aco;

import java.util.Map;

/**
 * One durable, replayable resource-transaction record (a write-ahead log line).
 *
 * <p>The record carries every field of the originating
 * {@link GlobalResourceRequest} — not just its outcome — so crash recovery can
 * rebuild the exact {@link RequestFingerprint} that idempotency checks compare
 * against.
 *
 * <p>{@link #fromJson(String)} is a strict reader: a record that is missing
 * fields, carries a malformed number/boolean, an unknown operation type, or
 * trailing garbage throws {@link IllegalArgumentException} instead of being
 * silently parsed into an empty entry. (The previous implementation was a stub
 * that returned an empty entry, so every WAL line was dropped on replay and the
 * data silently lost.)
 */
public class TransactionLogEntry {
    public String operationId;
    public long globalRevision;
    public String operationType;
    public String resourceType;
    public long amount;
    public String sessionId;
    public String sectorId;
    public long expectedGlobalRevision;
    public boolean success;
    public String error;
    public long newBalance;
    public long timestamp;

    public TransactionLogEntry() {
    }

    public TransactionLogEntry(GlobalResourceRequest req, TransactionResult res, long timestamp) {
        this.operationId = req.operationId();
        this.globalRevision = res.newRevision();
        this.operationType = req.operationType().name();
        this.resourceType = req.resourceType();
        this.amount = req.amount();
        this.sessionId = req.sessionId();
        this.sectorId = req.sectorId();
        this.expectedGlobalRevision = req.expectedGlobalRevision();
        this.success = res.success();
        this.error = res.error();
        this.newBalance = res.newBalance();
        this.timestamp = timestamp;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"operationId\":\"").append(FlatJson.escape(operationId)).append("\",");
        sb.append("\"globalRevision\":").append(globalRevision).append(",");
        sb.append("\"operationType\":\"").append(FlatJson.escape(operationType)).append("\",");
        sb.append("\"resourceType\":\"").append(FlatJson.escape(resourceType)).append("\",");
        sb.append("\"amount\":").append(amount).append(",");
        sb.append("\"sessionId\":\"").append(FlatJson.escape(sessionId)).append("\",");
        sb.append("\"sectorId\":\"").append(FlatJson.escape(sectorId)).append("\",");
        sb.append("\"expectedGlobalRevision\":").append(expectedGlobalRevision).append(",");
        sb.append("\"success\":").append(success).append(",");
        sb.append("\"error\":\"").append(FlatJson.escape(error != null ? error : "")).append("\",");
        sb.append("\"newBalance\":").append(newBalance).append(",");
        sb.append("\"timestamp\":").append(timestamp);
        sb.append("}");
        return sb.toString();
    }

    /**
     * Strict round-trip reader for {@link #toJson}. Every serialized field must be
     * present and valid; escaped strings are unescaped; numbers and the success
     * boolean are validated. A malformed record is rejected, never accepted as an
     * incomplete entry.
     *
     * @throws IllegalArgumentException when the line is not a complete, valid record
     */
    public static TransactionLogEntry fromJson(String json) {
        Map<String, FlatJson.Value> m = FlatJson.parseObjectStrict(json);

        TransactionLogEntry entry = new TransactionLogEntry();
        entry.operationId = FlatJson.requireString(m, "operationId");
        entry.globalRevision = FlatJson.requireLong(m, "globalRevision");
        entry.operationType = FlatJson.requireString(m, "operationType");
        entry.resourceType = FlatJson.requireString(m, "resourceType");
        entry.amount = FlatJson.requireLong(m, "amount");
        entry.sessionId = FlatJson.requireString(m, "sessionId");
        entry.sectorId = FlatJson.requireString(m, "sectorId");
        entry.expectedGlobalRevision = FlatJson.requireLong(m, "expectedGlobalRevision");
        entry.success = FlatJson.requireBoolean(m, "success");
        entry.error = FlatJson.requireString(m, "error");
        entry.newBalance = FlatJson.requireLong(m, "newBalance");
        entry.timestamp = FlatJson.requireLong(m, "timestamp");

        if (entry.operationId.isEmpty()) {
            throw new IllegalArgumentException("WAL record has an empty operationId");
        }
        try {
            GlobalResourceRequest.OperationType.valueOf(entry.operationType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("WAL record has an unknown operationType: " + entry.operationType);
        }
        return entry;
    }
}

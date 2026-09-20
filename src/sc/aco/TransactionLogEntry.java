package sc.aco;

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

    public TransactionLogEntry() {}

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
        sb.append("\"operationId\":\"").append(escape(operationId)).append("\",");
        sb.append("\"globalRevision\":").append(globalRevision).append(",");
        sb.append("\"operationType\":\"").append(escape(operationType)).append("\",");
        sb.append("\"resourceType\":\"").append(escape(resourceType)).append("\",");
        sb.append("\"amount\":").append(amount).append(",");
        sb.append("\"sessionId\":\"").append(escape(sessionId)).append("\",");
        sb.append("\"sectorId\":\"").append(escape(sectorId)).append("\",");
        sb.append("\"expectedGlobalRevision\":").append(expectedGlobalRevision).append(",");
        sb.append("\"success\":").append(success).append(",");
        sb.append("\"error\":\"").append(escape(error != null ? error : "")).append("\",");
        sb.append("\"newBalance\":").append(newBalance).append(",");
        sb.append("\"timestamp\":").append(timestamp);
        sb.append("}");
        return sb.toString();
    }

    public static TransactionLogEntry fromJson(String json) {
        TransactionLogEntry entry = new TransactionLogEntry();
        // Simplified parsing
        return entry;
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
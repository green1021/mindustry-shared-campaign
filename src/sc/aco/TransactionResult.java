package sc.aco;

public record TransactionResult(String operationId, boolean success, String error, long newRevision, long newBalance) {
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"operationId\":\"").append(escape(operationId)).append("\",");
        sb.append("\"success\":").append(success).append(",");
        sb.append("\"error\":\"").append(escape(error != null ? error : "")).append("\",");
        sb.append("\"newRevision\":").append(newRevision).append(",");
        sb.append("\"newBalance\":").append(newBalance);
        sb.append("}");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
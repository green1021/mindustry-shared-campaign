package sc.aco;

import java.util.Map;
import java.util.Objects;

/**
 * The complete identity of a global resource operation: every field whose value
 * must match for a repeated {@code operationId} to count as the same request.
 *
 * <p>Built from <em>all</em> payload fields of {@link GlobalResourceRequest}
 * (operation id, session id, sector id, expected global revision, resource type,
 * amount, operation type). A duplicate operation id whose payload differs in any
 * of these fields is a distinct, invalid request: it is rejected with
 * {@code IDEMPOTENCY_PAYLOAD_MISMATCH} instead of being served the stored result.
 *
 * <p>The {@code sessionId} in the fingerprint is the <em>server-derived</em>
 * connection identity (see {@code AcoServer.handleResourceRequest}), never a
 * value supplied by the client.
 */
public record RequestFingerprint(
    String operationId,
    String sessionId,
    String sectorId,
    long expectedGlobalRevision,
    String resourceType,
    long amount,
    String opType
) {
    public RequestFingerprint {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(sectorId);
        Objects.requireNonNull(resourceType);
        Objects.requireNonNull(opType);
    }

    /** Flat JSON object carrying every field; round-trips through {@link #fromJson}. */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"operationId\":\"").append(FlatJson.escape(operationId)).append("\",");
        sb.append("\"sessionId\":\"").append(FlatJson.escape(sessionId)).append("\",");
        sb.append("\"sectorId\":\"").append(FlatJson.escape(sectorId)).append("\",");
        sb.append("\"expectedGlobalRevision\":").append(expectedGlobalRevision).append(",");
        sb.append("\"resourceType\":\"").append(FlatJson.escape(resourceType)).append("\",");
        sb.append("\"amount\":").append(amount).append(",");
        sb.append("\"opType\":\"").append(FlatJson.escape(opType)).append("\"");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Parse the JSON produced by {@link #toJson}.
     *
     * @throws IllegalArgumentException when any field is missing or malformed
     */
    public static RequestFingerprint fromJson(String json) {
        Map<String, FlatJson.Value> m = FlatJson.parseObjectStrict(json);
        return new RequestFingerprint(
            FlatJson.requireString(m, "operationId"),
            FlatJson.requireString(m, "sessionId"),
            FlatJson.requireString(m, "sectorId"),
            FlatJson.requireLong(m, "expectedGlobalRevision"),
            FlatJson.requireString(m, "resourceType"),
            FlatJson.requireLong(m, "amount"),
            FlatJson.requireString(m, "opType"));
    }
}

package sc.aco;

import java.util.Objects;

public record RequestFingerprint(String operationId, String sectorId, String resourceType, long amount, String opType) {
    public RequestFingerprint {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(sectorId);
        Objects.requireNonNull(resourceType);
        Objects.requireNonNull(opType);
    }
}

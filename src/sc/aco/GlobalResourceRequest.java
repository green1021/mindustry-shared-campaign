package sc.aco;

public record GlobalResourceRequest(
    String operationId,
    String sessionId,
    String sectorId,
    long expectedGlobalRevision,
    String resourceType,
    long amount,
    OperationType operationType
) {
    public enum OperationType { CREDIT, DEBIT, WITHDRAW, DEPOSIT }
}
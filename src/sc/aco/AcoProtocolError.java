package sc.aco;

public enum AcoProtocolError {
    PROTOCOL_VERSION_MISMATCH(1),
    INVALID_MESSAGE(2),
    INVALID_STATE(3),
    INVALID_CAMPAIGN(4),
    PAYLOAD_TOO_LARGE(5),
    TIMEOUT(6),
    SERVER_ERROR(7);

    public final byte code;
    AcoProtocolError(int code) { this.code = (byte) code; }
}

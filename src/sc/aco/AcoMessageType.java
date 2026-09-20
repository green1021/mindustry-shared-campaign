package sc.aco;

public enum AcoMessageType {
    CLIENT_HELLO(1),
    SERVER_WELCOME(2),
    PING(3),
    PONG(4),
    SAVE_TRANSFER_BEGIN(6),
    SAVE_TRANSFER_CHUNK(7),
    SAVE_TRANSFER_END(8),
    SAVE_TRANSFER_ACK(9),
    P2_INFO(10),
    SECTOR_MIGRATION_REQUEST(11),
    SECTOR_MIGRATION_RESULT(12),
    SECTOR_MIGRATION_CONFIRM(13),
    SECTOR_CLAIM_REQUEST(14),
    SECTOR_CLAIM_RESULT(15),
    SECTOR_RELEASE(16),
    SECTOR_HOST_CHANGED(17),
    GLOBAL_STATE_SYNC(18),
    SECTOR_JOIN_REQUEST(19),
    GLOBAL_STATE_SNAPSHOT(20),
    GLOBAL_RESOURCE_REQUEST(21),
    GLOBAL_RESOURCE_RESPONSE(22),
    RESEARCH_REQUEST(23),
    RESEARCH_RESPONSE(24),
    SECTOR_UNLOCK_UPDATE(25),
    SECTOR_COMPLETION_EVENT(26);

    public final byte id;
    AcoMessageType(int id) { this.id = (byte) id; }
    public static AcoMessageType fromId(byte id) {
        for (AcoMessageType t : values()) if (t.id == id) return t;
        return null;
    }
}

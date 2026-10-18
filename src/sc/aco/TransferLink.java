package sc.aco;

/**
 * Narrow view of the control-plane link that a {@link LocalSectorSession} needs.
 *
 * <p>Implemented by the real socket transport ({@link AcoControlConnection}) and by
 * test doubles. Keeping the session behind this interface is what lets the
 * claim/checkpoint/release/handoff logic run (and be tested) without a live
 * authority, while production always goes through the real connection.
 */
public interface TransferLink {

    /**
     * Send one request and wait for one of {@code expectedResponses}; returns the
     * response payload, or null on timeout/disconnect/malformed exchange.
     */
    String request(AcoMessageType type, String payload, long timeoutMs, byte... expectedResponses);

    /** Fire-and-forget frame; false when the link is down. */
    boolean send(AcoMessageType type, String payload);

    /**
     * Transfer a full snapshot (BEGIN/CHUNK* /END) and wait for the authority's
     * {@code SECTOR_SNAPSHOT_ACK}; true only when the authority confirmed that the
     * snapshot was validated and committed.
     */
    boolean transferSnapshot(String sectorKey, long revision, byte[] bytes);

    boolean isOpen();
}

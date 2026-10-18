package sc.aco;

/**
 * Lease/ownership record for one sector.
 *
 * <p>MVP model: a sector has at most <b>one active lease</b>. A lease is granted by
 * the campaign authority (control plane) as a capability: the owner connection must
 * present the {@link #grant} token to release, commit into, or reattach to the
 * sector. Ownership metadata is <em>not</em> a world transfer -- the sector world
 * lives on the owner machine (see {@code LocalSectorSession}) and reaches the
 * campaign through validated snapshot transfers.
 *
 * <ul>
 *   <li>{@link OwnerKind} -- who holds it: nobody, a client connection, or the host.
 *   <li>{@link LeaseState} -- ACTIVE (owner attached), ORPHANED (owner disconnected /
 *       authority restarted: blocks new claims until reattach or host reclaim),
 *       RESERVED (handoff/load failure: blocks claims so a retry cannot be raced),
 *       or FREE (claimable; the version counter is retained).
 * </ul>
 *
 * <p>{@link #ownershipVersion} increases monotonically across claims, releases,
 * host takeovers and force-frees; it never resets when a sector becomes FREE, so a
 * stale release/claim attempt fails deterministically instead of aliasing a newer
 * lease. {@link #revisionVersion} is the last world revision the authority has
 * <em>accepted</em> for the sector (0 = never committed).
 */
public class SectorOwnership {
    public enum OwnerKind { NONE, CLIENT, HOST }
    public enum LeaseState { ACTIVE, ORPHANED, RESERVED, FREE }

    /** ownerSessionId used for host-held leases (the host is the authority machine). */
    public static final String HOST_SESSION = "host-local";

    public final String sectorKey;
    public OwnerKind kind = OwnerKind.NONE;
    public LeaseState state = LeaseState.FREE;
    public String ownerSessionId;
    public long ownershipVersion = 0;
    public long revisionVersion = 0;
    public String grant;
    public String endpoint; // host:port of the active P2 server, only while HOST ACTIVE

    public SectorOwnership(String sectorKey) {
        this.sectorKey = sectorKey;
    }

    public boolean active() {
        return state == LeaseState.ACTIVE;
    }

    /** A fresh claim may only start from FREE (ORPHANED/RESERVED block with intent). */
    public boolean claimable() {
        return state == LeaseState.FREE && kind == OwnerKind.NONE;
    }

    public boolean owns(String sessionId) {
        return state == LeaseState.ACTIVE && ownerSessionId != null && ownerSessionId.equals(sessionId);
    }

    /** Independent copy used for journal snapshots and rollbacks (no shared mutable state). */
    public SectorOwnership copy() {
        SectorOwnership c = new SectorOwnership(sectorKey);
        c.kind = kind;
        c.state = state;
        c.ownerSessionId = ownerSessionId;
        c.ownershipVersion = ownershipVersion;
        c.revisionVersion = revisionVersion;
        c.grant = grant;
        c.endpoint = endpoint;
        return c;
    }
}

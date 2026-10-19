package sc.aco;

import arc.util.Log;
import java.security.SecureRandom;
import java.util.*;

/**
 * In-memory lease table with optional write-through durability.
 *
 * <p>This registry is the single authority for <em>who may run a sector's world</em>.
 * It stores control-plane facts only: it never loads a world, never starts a P2
 * server, and never touches {@code Vars.*} -- the owner machine does that. A claim
 * here is a grant/lease, not a world transfer.
 *
 * <p>Every transition is a freshly constructed {@link SectorOwnership} so a failed
 * journal write can be rolled back without aliasing. With a {@link SectorLeaseJournal}
 * attached, the journal is rewritten <em>before</em> the operation is acknowledged;
 * if that write fails the operation fails (and is rolled back), because an
 * acknowledged lease the journal does not know about is how a restart hands one
 * sector to two owners.
 *
 * <p>Monotonicity: {@link SectorOwnership#ownershipVersion} increases on every
 * claim/release/takeover/force-free and is retained when a sector becomes FREE, so
 * stale requests fail instead of aliasing a newer lease.
 */
public class SectorRegistry {

    public enum Result { OK, BAD_REQUEST, NO_LEASE, NOT_OWNER, STALE_VERSION, BAD_GRANT, NOT_ORPHANED }

    private final Map<String, SectorOwnership> registry = new HashMap<>();
    private final SectorLeaseJournal journal; // nullable: tests run without durability
    private volatile boolean journalUnavailable = false;

    private static final SecureRandom RNG = new SecureRandom();

    public SectorRegistry() {
        this(null);
    }

    public SectorRegistry(SectorLeaseJournal journal) {
        this.journal = journal;
        if (journal != null) {
            try {
                for (SectorOwnership o : journal.load()) {
                    if (o.state == SectorOwnership.LeaseState.ACTIVE) {
                        // An ACTIVE lease after a restart has no attached owner: it must
                        // block claims until the original holder reattaches or the host
                        // explicitly reclaims it.
                        o.state = SectorOwnership.LeaseState.ORPHANED;
                        Log.warn("LEASE_RECOVERED_ORPHANED sector=@ kind=@ version=@", o.sectorKey, o.kind, o.ownershipVersion);
                    }
                    registry.put(o.sectorKey, o);
                }
            } catch (SectorLeaseJournal.LeaseJournalException e) {
                journalUnavailable = true;
                Log.err("LEASE_JOURNAL_UNREADABLE: @ (all lease mutations refused until reset)", e.getMessage());
            }
        }
    }

    public synchronized SectorOwnership get(String sectorKey) {
        return registry.get(sectorKey);
    }

    public synchronized List<SectorOwnership> all() {
        return new ArrayList<>(registry.values());
    }

    public synchronized boolean journalUnavailable() {
        return journalUnavailable;
    }

    /**
     * Operator-facing recovery after a corrupt journal was detected: archive the
     * corrupt file (preserved, never deleted) and start from an empty lease set.
     * This is the only path that discards recovered lease knowledge, and it is
     * explicit -- never automatic.
     */
    public synchronized java.nio.file.Path resetAfterCorruptJournal() {
        java.nio.file.Path archived = journal == null ? null : journal.archiveCorrupt();
        registry.clear();
        journalUnavailable = false;
        Log.warn("LEASE_JOURNAL_RESET archived=@", archived);
        return archived;
    }

    // ------------------------------------------------------------------ claims

    /** Client claim: control-plane grant only. Returns the grant token or null when refused. */
    public synchronized String claimSector(String sectorKey, String sessionId, long expectedRevision) {
        if (sectorKey == null || sessionId == null || expectedRevision < 0) return null;
        checkJournal();
        SectorOwnership prior = registry.get(sectorKey);
        if (prior != null && !prior.claimable()) return null;

        SectorOwnership next = prior != null ? prior.copy() : new SectorOwnership(sectorKey);
        next.kind = SectorOwnership.OwnerKind.CLIENT;
        next.state = SectorOwnership.LeaseState.ACTIVE;
        next.ownerSessionId = sessionId;
        next.ownershipVersion = (prior == null ? 0 : prior.ownershipVersion) + 1;
        // The authority's accepted revision wins over the client's claim number:
        // the sector world must continue from what the campaign actually has.
        next.revisionVersion = prior == null ? 0 : prior.revisionVersion;
        next.grant = grantFor(next.ownershipVersion);
        next.endpoint = null;

        registry.put(sectorKey, next);
        try {
            persist();
        } catch (RuntimeException e) {
            restore(sectorKey, prior);
            throw e;
        }
        return next.grant;
    }

    /** Host takeover claim into a FREE or RESERVED sector. Returns grant or null. */
    public synchronized String claimSectorAsHost(String sectorKey, long revision) {
        if (sectorKey == null || revision < 0) return null;
        checkJournal();
        SectorOwnership prior = registry.get(sectorKey);
        if (prior != null
                && !(prior.claimable() || prior.state == SectorOwnership.LeaseState.RESERVED)) {
            return null;
        }

        SectorOwnership next = prior != null ? prior.copy() : new SectorOwnership(sectorKey);
        next.kind = SectorOwnership.OwnerKind.HOST;
        next.state = SectorOwnership.LeaseState.ACTIVE;
        next.ownerSessionId = SectorOwnership.HOST_SESSION;
        next.ownershipVersion = (prior == null ? 0 : prior.ownershipVersion) + 1;
        next.revisionVersion = prior == null ? 0 : prior.revisionVersion;
        next.grant = grantFor(next.ownershipVersion);
        next.endpoint = null;

        registry.put(sectorKey, next);
        try {
            persist();
        } catch (RuntimeException e) {
            restore(sectorKey, prior);
            throw e;
        }
        return next.grant;
    }

    // ----------------------------------------------------------------- releases

    /**
     * Release requires: the active owner connection, the capability grant, and the
     * exact current ownership version. Anything else leaves the lease untouched.
     */
    public synchronized Result releaseSector(String sectorKey, String sessionId, long ownershipVersion, String grant) {
        if (sectorKey == null || sessionId == null) return Result.BAD_REQUEST;
        checkJournal();
        SectorOwnership prior = registry.get(sectorKey);
        if (prior == null) return Result.NO_LEASE;
        if (prior.state != SectorOwnership.LeaseState.ACTIVE || !sessionId.equals(prior.ownerSessionId)) return Result.NOT_OWNER;
        if (grant == null || !grant.equals(prior.grant)) return Result.BAD_GRANT;
        if (prior.ownershipVersion != ownershipVersion) return Result.STALE_VERSION;

        SectorOwnership next = prior.copy();
        next.kind = SectorOwnership.OwnerKind.NONE;
        next.state = SectorOwnership.LeaseState.FREE;
        next.ownerSessionId = null;
        next.grant = null;
        next.endpoint = null;
        next.ownershipVersion = prior.ownershipVersion + 1;

        registry.put(sectorKey, next);
        try {
            persist();
        } catch (RuntimeException e) {
            restore(sectorKey, prior);
            throw e;
        }
        return Result.OK;
    }

    /** Park a lease whose owner connection went away (disconnect). Keeps grant + versions. */
    public synchronized List<String> orphanSession(String sessionId) {
        if (sessionId == null) return List.of();
        checkJournal();
        Map<String, SectorOwnership> priors = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        for (SectorOwnership o : registry.values()) {
            if (o.state == SectorOwnership.LeaseState.ACTIVE && sessionId.equals(o.ownerSessionId)) {
                priors.put(o.sectorKey, o);
                SectorOwnership next = o.copy();
                next.state = SectorOwnership.LeaseState.ORPHANED;
                registry.put(o.sectorKey, next);
                changed.add(o.sectorKey);
            }
        }
        if (changed.isEmpty()) return changed;
        try {
            persist();
        } catch (RuntimeException e) {
            priors.forEach((k, v) -> registry.put(k, v));
            throw e;
        }
        return changed;
    }

    /** Reattach an ORPHANED lease to a new connection presenting the original grant. */
    public synchronized Result reattach(String sectorKey, String grant, String newSessionId) {
        if (sectorKey == null || grant == null || newSessionId == null) return Result.BAD_REQUEST;
        checkJournal();
        SectorOwnership prior = registry.get(sectorKey);
        if (prior == null) return Result.NO_LEASE;
        if (prior.state != SectorOwnership.LeaseState.ORPHANED) return Result.NOT_ORPHANED;
        if (!grant.equals(prior.grant)) return Result.BAD_GRANT;

        SectorOwnership next = prior.copy();
        next.state = SectorOwnership.LeaseState.ACTIVE;
        next.ownerSessionId = newSessionId;

        registry.put(sectorKey, next);
        try {
            persist();
        } catch (RuntimeException e) {
            restore(sectorKey, prior);
            throw e;
        }
        return Result.OK;
    }

    /** Host/operator reclaim: ORPHANED or RESERVED (or already FREE) becomes FREE again. */
    public synchronized Result forceFree(String sectorKey) {
        if (sectorKey == null) return Result.BAD_REQUEST;
        checkJournal();
        SectorOwnership prior = registry.get(sectorKey);
        if (prior == null) return Result.NO_LEASE;
        if (prior.state == SectorOwnership.LeaseState.ACTIVE) return Result.NOT_ORPHANED;
        if (prior.state == SectorOwnership.LeaseState.FREE) return Result.OK;

        SectorOwnership next = prior.copy();
        next.kind = SectorOwnership.OwnerKind.NONE;
        next.state = SectorOwnership.LeaseState.FREE;
        next.ownerSessionId = null;
        next.grant = null;
        next.endpoint = null;
        next.ownershipVersion = prior.ownershipVersion + 1;

        registry.put(sectorKey, next);
        try {
            persist();
        } catch (RuntimeException e) {
            restore(sectorKey, prior);
            throw e;
        }
        return Result.OK;
    }

    /** Park a host lease after a failed host-side load so a retry cannot be raced (RESERVED). */
    public synchronized Result reserveHostLease(String sectorKey) {
        return reserveHostLease(sectorKey, -1);
    }

    /**
     * Version-fenced park: only reserves the lease when it is still the exact
     * {@code expectedOwnershipVersion} the caller took. A late attempt must not park a
     * newer retry's lease, and the check + write happen under one monitor so there is no
     * check-then-act window.
     */
    public synchronized Result reserveHostLease(String sectorKey, long expectedOwnershipVersion) {
        if (sectorKey == null) return Result.BAD_REQUEST;
        checkJournal();
        SectorOwnership prior = registry.get(sectorKey);
        if (prior == null) return Result.NO_LEASE;
        if (prior.state != SectorOwnership.LeaseState.ACTIVE
                || prior.kind != SectorOwnership.OwnerKind.HOST) {
            return Result.NOT_OWNER;
        }
        if (expectedOwnershipVersion >= 0 && prior.ownershipVersion != expectedOwnershipVersion) {
            return Result.STALE_VERSION;
        }
        SectorOwnership next = prior.copy();
        next.state = SectorOwnership.LeaseState.RESERVED;
        next.ownerSessionId = null;
        // grant stays so the operator path can be logged; the lease is not reattachable
        // from RESERVED (reattach requires ORPHANED), it only resolves via hostClaim/forceFree.
        registry.put(sectorKey, next);
        try {
            persist();
        } catch (RuntimeException e) {
            restore(sectorKey, prior);
            throw e;
        }
        return Result.OK;
    }

    /** Record the advertised P2 endpoint for an active host lease (after a successful load). */
    public synchronized Result setEndpoint(String sectorKey, String sessionId, String endpoint) {
        if (sectorKey == null || sessionId == null || endpoint == null) return Result.BAD_REQUEST;
        checkJournal();
        SectorOwnership prior = registry.get(sectorKey);
        if (prior == null) return Result.NO_LEASE;
        if (!prior.owns(sessionId) || prior.kind != SectorOwnership.OwnerKind.HOST) return Result.NOT_OWNER;

        SectorOwnership next = prior.copy();
        next.endpoint = endpoint;
        registry.put(sectorKey, next);
        try {
            persist();
        } catch (RuntimeException e) {
            restore(sectorKey, prior);
            throw e;
        }
        return Result.OK;
    }

    /** Record a newly accepted (validated) world revision for the sector. */
    public synchronized Result setAcceptedRevision(String sectorKey, long revision) {
        if (sectorKey == null || revision < 0) return Result.BAD_REQUEST;
        checkJournal();
        SectorOwnership prior = registry.get(sectorKey);
        if (prior == null) return Result.NO_LEASE;
        if (revision < prior.revisionVersion) return Result.STALE_VERSION;

        SectorOwnership next = prior.copy();
        next.revisionVersion = revision;
        registry.put(sectorKey, next);
        try {
            persist();
        } catch (RuntimeException e) {
            restore(sectorKey, prior);
            throw e;
        }
        return Result.OK;
    }

    // ------------------------------------------------------------------ internals

    private void checkJournal() {
        if (journal != null && journalUnavailable) {
            throw new SectorLeaseJournal.LeaseJournalException("lease-journal-unavailable");
        }
    }

    private void persist() {
        if (journal != null) journal.save(registry.values());
    }

    private void restore(String sectorKey, SectorOwnership prior) {
        if (prior == null) {
            registry.remove(sectorKey);
        } else {
            registry.put(sectorKey, prior);
        }
    }

    private static String grantFor(long version) {
        byte[] r = new byte[16];
        RNG.nextBytes(r);
        return "g-" + version + "-" + java.util.HexFormat.of().formatHex(r);
    }
}

package sc.aco;

import arc.util.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * Campaign Authority -- the control plane.
 *
 * <p>It owns campaign-global progression and sector <em>leases</em>: who may run a
 * sector's world, with monotonic versions and capability grants. It must never load
 * a sector world into this process, never start a P2 server for a client's sector,
 * and never treat an ownership row as a world transfer. The previous
 * {@code handleClaimRequest} initialized the sector runtime and started P2 here --
 * i.e. a remote client's claim made the host process load the client's sector.
 * That inverted ownership is gone: the authority answers with a lease and the
 * claimant's own machine runs the world ({@code LocalSectorSession}). The only
 * world loading this process does is the host's explicit takeover, driven by
 * {@code SectorHandoffManager}.
 */
public class CampaignAuthority {
    private final SectorRegistry registry;
    private final CampaignPersistence persistence;

    /** Claim eligibility (wired to campaign unlocks; default allow for unit tests). */
    private Predicate<String> claimPolicy = key -> true;

    public CampaignAuthority(SectorRegistry registry, CampaignPersistence persistence) {
        this.registry = registry;
        this.persistence = persistence;
    }

    /** Configure which sectors may be claimed at all (for example: unlocked ones). */
    public void setClaimPolicy(Predicate<String> policy) {
        if (policy != null) this.claimPolicy = policy;
    }

    /**
     * The persistence rooted at the campaign's configured storage directory.
     *
     * <p>Exposed so {@link AcoServer} persists state to the same directory the
     * authority recovers from. It previously built its own
     * {@code new CampaignPersistence(Paths.get("."))}, which wrote the campaign
     * state into the process working directory instead.
     */
    public CampaignPersistence getPersistence() {
        return persistence;
    }

    public SectorRegistry getRegistry() {
        return registry;
    }

    public SectorOwnership getOwnership(String sectorKey) {
        return registry.get(sectorKey);
    }

    static boolean validKey(String sectorKey) {
        return SectorSnapshotCodec.validSectorKey(sectorKey);
    }

    private static void send(OutputStream out, AcoMessageType type, int msgId, String payload) throws IOException {
        out.write(new AcoFrame((byte) 1, type.id, msgId, payload.getBytes(StandardCharsets.UTF_8)).encode());
    }

    /**
     * Handle a sector claim from a connected client.
     *
     * <p>Grants a lease (control plane only). Response payload:
     * {@code sectorKey|OK|ownershipVersion|revisionVersion|grant} or
     * {@code sectorKey|FAIL|REASON}. A retry from the same still-owning connection
     * is idempotent and returns the same lease.
     *
     * @return the granted lease, or null when the claim was refused
     */
    public SectorOwnership handleClaimRequest(String sectorKey, String connSessionId, long revision, int msgId, OutputStream out) throws IOException {
        Log.info("CLAIM_REQUEST: conn=@ sector=@ revision=@", connSessionId, sectorKey, revision);
        try {
            if (!validKey(sectorKey)) {
                send(out, AcoMessageType.SECTOR_CLAIM_RESULT, msgId, sectorKey + "|FAIL|INVALID_SECTOR_KEY");
                return null;
            }
            if (!claimPolicy.test(sectorKey)) {
                send(out, AcoMessageType.SECTOR_CLAIM_RESULT, msgId, sectorKey + "|FAIL|SECTOR_NOT_CLAIMABLE");
                return null;
            }

            SectorOwnership existing = registry.get(sectorKey);
            if (existing != null && existing.owns(connSessionId)) {
                // Idempotent retry: same owner, same lease, same grant.
                send(out, AcoMessageType.SECTOR_CLAIM_RESULT, msgId, sectorKey + "|OK|" + existing.ownershipVersion
                        + "|" + existing.revisionVersion + "|" + existing.grant);
                return existing;
            }

            String grant = registry.claimSector(sectorKey, connSessionId, revision);
            if (grant == null) {
                send(out, AcoMessageType.SECTOR_CLAIM_RESULT, msgId, sectorKey + "|FAIL|SECTOR_NOT_CLAIMABLE");
                return null;
            }
            SectorOwnership own = registry.get(sectorKey);
            send(out, AcoMessageType.SECTOR_CLAIM_RESULT, msgId, sectorKey + "|OK|" + own.ownershipVersion
                    + "|" + own.revisionVersion + "|" + own.grant);
            return own;
        } catch (SectorLeaseJournal.LeaseJournalException e) {
            Log.err("CLAIM_REFUSED_PERSISTENCE: @", e.getMessage());
            send(out, AcoMessageType.SECTOR_CLAIM_RESULT, msgId, sectorKey + "|FAIL|PERSISTENCE_FAILURE");
            return null;
        }
    }

    /**
     * Handle a sector release: the owner connection must present the grant and the
     * exact current ownership version. Response payload:
     * {@code sectorKey|OK|newVersion} or {@code sectorKey|FAIL|REASON}.
     */
    public SectorRegistry.Result handleReleaseRequest(String sectorKey, String connSessionId, long ownershipVersion, String grant, int msgId, OutputStream out) throws IOException {
        Log.info("RELEASE_REQUEST: conn=@ sector=@ ver=@", connSessionId, sectorKey, ownershipVersion);
        try {
            if (!validKey(sectorKey)) {
                send(out, AcoMessageType.SECTOR_RELEASE, msgId, sectorKey + "|FAIL|INVALID_SECTOR_KEY");
                return SectorRegistry.Result.BAD_REQUEST;
            }
            SectorRegistry.Result r = registry.releaseSector(sectorKey, connSessionId, ownershipVersion, grant);
            if (r == SectorRegistry.Result.OK) {
                send(out, AcoMessageType.SECTOR_RELEASE, msgId, sectorKey + "|OK|" + registry.get(sectorKey).ownershipVersion);
            } else {
                send(out, AcoMessageType.SECTOR_RELEASE, msgId, sectorKey + "|FAIL|" + r.name());
            }
            return r;
        } catch (SectorLeaseJournal.LeaseJournalException e) {
            Log.err("RELEASE_REFUSED_PERSISTENCE: @", e.getMessage());
            send(out, AcoMessageType.SECTOR_RELEASE, msgId, sectorKey + "|FAIL|PERSISTENCE_FAILURE");
            return SectorRegistry.Result.NOT_OWNER;
        }
    }

    /**
     * Reattach an ORPHANED lease to a new connection that presents the original
     * grant (client reconnected, or the authority restarted). Response payload:
     * {@code sectorKey|OK|kind|ownershipVersion|revisionVersion|grant} or FAIL.
     */
    public SectorRegistry.Result handleReattachRequest(String sectorKey, String connSessionId, String grant, int msgId, OutputStream out) throws IOException {
        Log.info("REATTACH_REQUEST: conn=@ sector=@", connSessionId, sectorKey);
        try {
            if (!validKey(sectorKey)) {
                send(out, AcoMessageType.SECTOR_REATTACH, msgId, sectorKey + "|FAIL|INVALID_SECTOR_KEY");
                return SectorRegistry.Result.BAD_REQUEST;
            }
            SectorRegistry.Result r = registry.reattach(sectorKey, grant, connSessionId);
            if (r == SectorRegistry.Result.OK) {
                SectorOwnership own = registry.get(sectorKey);
                send(out, AcoMessageType.SECTOR_REATTACH, msgId, sectorKey + "|OK|" + own.kind.name()
                        + "|" + own.ownershipVersion + "|" + own.revisionVersion + "|" + own.grant);
            } else {
                send(out, AcoMessageType.SECTOR_REATTACH, msgId, sectorKey + "|FAIL|" + r.name());
            }
            return r;
        } catch (SectorLeaseJournal.LeaseJournalException e) {
            Log.err("REATTACH_REFUSED_PERSISTENCE: @", e.getMessage());
            send(out, AcoMessageType.SECTOR_REATTACH, msgId, sectorKey + "|FAIL|PERSISTENCE_FAILURE");
            return SectorRegistry.Result.NOT_OWNER;
        }
    }

    /**
     * Join resolution: a client may join a sector only when the <em>host</em> has an
     * active P2 server for it. Client-held sectors are not joinable (the MVP has no
     * client-to-client play); response (SECTOR_JOIN_RESPONSE) carries the host's advertised endpoint.
     * Payload: {@code sectorKey|OK|endpoint|ownershipVersion} or
     * {@code sectorKey|FAIL|SECTOR_NOT_HOSTED}.
     */
    public boolean handleJoinRequest(String sectorKey, String connSessionId, int msgId, OutputStream out) throws IOException {
        SectorOwnership own = registry.get(sectorKey);
        if (own != null && own.active() && own.kind == SectorOwnership.OwnerKind.HOST && own.endpoint != null) {
            send(out, AcoMessageType.SECTOR_JOIN_RESPONSE, msgId, sectorKey + "|OK|" + own.endpoint + "|" + own.ownershipVersion);
            return true;
        }
        send(out, AcoMessageType.SECTOR_JOIN_RESPONSE, msgId, sectorKey + "|FAIL|SECTOR_NOT_HOSTED");
        return false;
    }
}

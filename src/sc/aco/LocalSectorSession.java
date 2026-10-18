package sc.aco;

import arc.util.Log;

/**
 * Client-side local sector session: the half of the product promise that runs on
 * the <em>owner's own machine</em>.
 *
 * <p>The old ACO flow made the host process load the client's sector. Here the
 * opposite happens: the authority grants a lease over the control plane, and this
 * session loads the sector world in the local Mindustry process
 * ({@link SectorWorldOps}), checkpoints real validated snapshots through
 * {@link TransferLink#transferSnapshot}, and implements the client side of the
 * handoff protocol (save exact world -> commit snapshot -> leave local world ->
 * release lease), in that order, so that at no point two machines simulate the
 * same sector.
 *
 * <p>All methods return null on success or a machine-readable reason on failure;
 * the phase is always observable via {@link #phase()}. The class never touches
 * {@code Vars.*} itself -- the injected {@link SectorWorldOps} does, on the game
 * thread.
 */
public final class LocalSectorSession {

    public enum Phase { IDLE, ACTIVE, HANDING_OFF, ERROR }

    /** Reads the locally committed snapshot bytes for a sector (production: CampaignSectorStore). */
    @FunctionalInterface
    public interface SnapshotReader {
        byte[] read(String sectorKey) throws Exception;
    }

    static final long REQUEST_TIMEOUT_MS = 10_000;

    private final TransferLink link;
    private final SectorWorldOps world;
    private final SnapshotReader snapshots;

    private Phase phase = Phase.IDLE;
    private String sectorKey;
    private long ownershipVersion = -1;
    private long lastCommittedRevision = 0;
    private String grant;
    private String lastError;
    private boolean leftWorld;

    public LocalSectorSession(TransferLink link, SectorWorldOps world, SnapshotReader snapshots) {
        this.link = link;
        this.world = world;
        this.snapshots = snapshots;
    }

    // ------------------------------------------------------------------- claim

    /**
     * Atomically claim the sector from the authority and load it locally.
     *
     * @return null on success, otherwise a refusal/failure reason
     *         ({@code NETWORK}, {@code WORLD_LOAD_FAILED}, or the authority's reason)
     */
    public synchronized String claim(String key, long revisionHint) {
        if (phase != Phase.IDLE) return lastError = "ALREADY_ACTIVE:" + phase;
        if (!SectorSnapshotCodec.validSectorKey(key)) return lastError = "INVALID_SECTOR_KEY";
        if (world == null || snapshots == null) return lastError = "NO_WORLD_OPS";
        Log.info("LOCAL_CLAIM sector=@", key);
        String resp = link.request(AcoMessageType.SECTOR_CLAIM_REQUEST, key + "|" + Math.max(0, revisionHint),
                REQUEST_TIMEOUT_MS, AcoMessageType.SECTOR_CLAIM_RESULT.id);
        if (resp == null) {
            phase = Phase.ERROR;
            return lastError = "NETWORK";
        }
        String[] f = resp.split("\\|", -1);
        if (f.length < 2 || !f[0].equals(key)) {
            phase = Phase.ERROR;
            return lastError = "MALFORMED_RESPONSE";
        }
        if (!"OK".equals(f[1])) {
            phase = Phase.IDLE;
            return lastError = f.length >= 3 ? f[2] : "REFUSED";
        }
        if (f.length != 5) {
            // Leaked lease risk: we cannot release what we cannot name.
            phase = Phase.ERROR;
            return lastError = "MALFORMED_GRANT";
        }
        long ver = parseLong(f[2]);
        long rev = parseLong(f[3]);
        if (ver < 0 || rev < 0) {
            phase = Phase.ERROR;
            return lastError = "MALFORMED_GRANT";
        }
        this.sectorKey = key;
        this.ownershipVersion = ver;
        this.lastCommittedRevision = rev;
        this.grant = f[4];
        this.leftWorld = false;

        if (!world.loadWorld(key, true)) {
            // Lease granted but the world could not run here: release it so the
            // sector is not stuck owned by a client that cannot simulate it.
            releaseBestEffort();
            phase = Phase.IDLE;
            return lastError = "WORLD_LOAD_FAILED";
        }
        phase = Phase.ACTIVE;
        lastError = null;
        Log.info("LOCAL_CLAIM_OK sector=@ version=@ revision=@", key, ver, rev);
        return null;
    }

    // -------------------------------------------------------------- checkpoint

    /**
     * Save the currently running world and commit it to the campaign's canonical
     * store through the authority. Safe at any time while ACTIVE.
     *
     * @return null when the snapshot is validated and committed by the authority
     */
    public synchronized String checkpoint() {
        if (phase != Phase.ACTIVE) return "NOT_ACTIVE";
        return checkpointInternal();
    }

    private String checkpointInternal() {
        long rev = lastCommittedRevision + 1;
        if (!world.saveWorld(sectorKey, rev)) {
            Log.warn("LOCAL_CHECKPOINT_SAVE_FAILED sector=@ revision=@", sectorKey, rev);
            return "SAVE_FAILED";
        }
        byte[] bytes;
        try {
            bytes = snapshots.read(sectorKey);
        } catch (Exception e) {
            Log.err("LOCAL_CHECKPOINT_READ_FAILED sector=@", sectorKey, e);
            return "SNAPSHOT_UNREADABLE";
        }
        if (bytes == null) return "SNAPSHOT_MISSING";
        if (!link.transferSnapshot(sectorKey, rev, bytes)) {
            Log.warn("LOCAL_CHECKPOINT_TRANSFER_FAILED sector=@ revision=@", sectorKey, rev);
            // The local snapshot at `rev` exists but the authority did not accept it;
            // lastCommittedRevision stays, and the next attempt saves rev+1.
            return "TRANSFER_FAILED";
        }
        lastCommittedRevision = rev;
        Log.info("LOCAL_CHECKPOINT_COMMITTED sector=@ revision=@", sectorKey, rev);
        return null;
    }

    // ---------------------------------------------------------------- release

    /** Release an ACTIVE lease after leaving the local world. */
    public synchronized String release(String reason) {
        if (phase != Phase.ACTIVE) return "NOT_ACTIVE";

        // 1. Checkpoint first: ensures authority has our latest state
        String chkErr = checkpointInternal();
        if (chkErr != null) {
            Log.warn("LOCAL_RELEASE_CHECKPOINT_FAILED sector=@ reason=@", sectorKey, chkErr);
            return "CHECKPOINT_FAILED:" + chkErr;
        }

        // 2. Stop local gameplay and confirm it left active gameplay
        if (!world.leaveWorld()) {
            Log.warn("LOCAL_RELEASE_LEAVE_FAILED sector=@", sectorKey);
            return "LEAVE_FAILED";
        }
        leftWorld = true;

        // 3. Only after leaving is confirmed, send lease-release request
        String relErr = releaseInternal();
        if (relErr == null) {
            Log.info("LOCAL_RELEASE_COMPLETE sector=@", sectorKey);
            reset();
            return null;
        }

        // 4. If release fails after world is already stopped, retain retryable state
        phase = Phase.ERROR;
        lastError = "RELEASE_FAILED:" + relErr;
        Log.warn("LOCAL_RELEASE_NETWORK_FAILED sector=@ reason=@", sectorKey, relErr);
        return lastError;
    }

    /**
     * Retry a release after a handoff that already left the local world but could
     * not confirm the release (phase ERROR, leavings retained).
     */
    public synchronized String retryRelease() {
        if (phase != Phase.ERROR || !leftWorld || sectorKey == null) return "NOT_RETRYABLE";
        String err = releaseInternal();
        if (err == null) reset();
        return err;
    }

    private String releaseInternal() {
        String resp = link.request(AcoMessageType.SECTOR_RELEASE,
                sectorKey + "|" + ownershipVersion + "|" + grant,
                REQUEST_TIMEOUT_MS, AcoMessageType.SECTOR_RELEASE.id);
        if (resp == null) return "NETWORK";
        String[] f = resp.split("\\|", -1);
        if (f.length >= 2 && "OK".equals(f[1])) return null;
        return f.length >= 3 ? f[2] : "RELEASE_REFUSED";
    }

    private void releaseBestEffort() {
        try {
            String err = releaseInternal();
            if (err != null) {
                Log.warn("LOCAL_RELEASE_BESTEFFORT_FAILED sector=@ reason=@", sectorKey, err);
            }
        } catch (Exception e) {
            Log.warn("LOCAL_RELEASE_BESTEFFORT_ERROR sector=@ reason=@", sectorKey, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- handoff

    /**
     * Client side of the host-takeover handoff. Runs the exact ordered protocol:
     * save the running world -> commit the snapshot to the authority -> leave the
     * local world -> release the lease. Any failure before the local world was left
     * aborts the handoff and the client keeps playing (the world is intact).
     *
     * @return null when the full sequence succeeded
     */
    public synchronized String onHandoffRequest(String payload) {
        String[] f = payload == null ? new String[0] : payload.split("\\|", -1);
        if (f.length != 2) return "MALFORMED_HANDOFF";
        String key = f[0];
        if (phase != Phase.ACTIVE || !key.equals(sectorKey)) {
            link.send(AcoMessageType.SECTOR_HANDOFF_ABORT, key + "|ABORT|NOT_ACTIVE");
            return "NOT_ACTIVE";
        }
        Log.info("LOCAL_HANDOFF_BEGIN sector=@ handoff=@", key, f[1]);
        phase = Phase.HANDING_OFF;

        long rev = lastCommittedRevision + 1;
        if (!world.saveWorld(key, rev)) {
            phase = Phase.ACTIVE;
            link.send(AcoMessageType.SECTOR_HANDOFF_ABORT, key + "|ABORT|SAVE_FAILED");
            return "SAVE_FAILED";
        }
        byte[] bytes;
        try {
            bytes = snapshots.read(key);
        } catch (Exception e) {
            bytes = null;
        }
        if (bytes == null || !link.transferSnapshot(key, rev, bytes)) {
            phase = Phase.ACTIVE;
            link.send(AcoMessageType.SECTOR_HANDOFF_ABORT, key + "|ABORT|TRANSFER_FAILED");
            return "TRANSFER_FAILED";
        }
        // Snapshot is safely committed; only now stop simulating.
        if (!world.leaveWorld()) {
            phase = Phase.ACTIVE; // Resume active play if we couldn't leave
            link.send(AcoMessageType.SECTOR_HANDOFF_ABORT, key + "|ABORT|LEAVE_FAILED");
            Log.warn("LOCAL_HANDOFF_LEAVE_FAILED sector=@", key);
            return "LEAVE_FAILED";
        }
        leftWorld = true;
        lastCommittedRevision = rev;

        String err = releaseInternal();
        if (err == null) {
            Log.info("LOCAL_HANDOFF_COMPLETE sector=@ revision=@", key, rev);
            reset();
            return null;
        }
        phase = Phase.ERROR;
        lastError = "RELEASE_FAILED:" + err;
        Log.warn("LOCAL_HANDOFF_RELEASE_FAILED sector=@ reason=@", key, err);
        return lastError;
    }

    /** Authority aborted the handoff; resume play if the local world was never left. */
    public synchronized void onHandoffAbort(String payload) {
        if (phase != Phase.HANDING_OFF) return;
        if (leftWorld) {
            // We already stopped simulating; without a release the lease will be
            // orphaned and the authority/operator resolves it.
            phase = Phase.ERROR;
            lastError = "ABORTED_AFTER_LEAVE";
        } else {
            phase = Phase.ACTIVE;
        }
        Log.warn("LOCAL_HANDOFF_ABORTED sector=@ left=@", sectorKey, leftWorld);
    }

    private void reset() {
        phase = Phase.IDLE;
        sectorKey = null;
        ownershipVersion = -1;
        grant = null;
        leftWorld = false;
        lastError = null;
    }

    // ------------------------------------------------------------------- view

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized boolean isActive() {
        return phase == Phase.ACTIVE;
    }

    public synchronized String sectorKey() {
        return sectorKey;
    }

    public synchronized long ownershipVersion() {
        return ownershipVersion;
    }

    public synchronized long lastCommittedRevision() {
        return lastCommittedRevision;
    }

    public synchronized String lastError() {
        return lastError;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

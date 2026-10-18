package sc.aco;

import arc.files.Fi;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import mindustry.io.SaveIO;
import mindustry.io.SaveMeta;

/**
 * Normal (non-disposable) per-campaign storage for sector world snapshots.
 *
 * <p>Layout: {@code <storageDir>/sector_worlds/<campaign>/<planet~sectorId>/} with
 * {@code snapshot.r<revision>.msav} (the only current snapshot), {@code latest}
 * (the committed revision), and {@code lease} (the capability token of the local
 * writer, when the local machine is simulating the sector).
 *
 * <p>This is the production counterpart of the guarded disposable-workspace
 * {@code sc.SectorStore}: it keeps the same protections (validated keys, no
 * symlinks anywhere on the path, temp file + fsync + ATOMIC_MOVE, atomic
 * `latest`) without requiring a disposable workspace, because sector snapshots
 * are real campaign data that must survive normally.
 *
 * <p>Commit rules: the SHA-256 is recomputed over the actual bytes; a stale
 * revision (lower than the committed one) and a revision conflict (same revision,
 * different content) are refused; a re-send of the identical revision+content is
 * idempotent. Metadata validation (campaign/sector/revision tags) is delegated to a
 * {@link SnapshotMetaValidator}: production uses {@link SaveMetaValidator} which
 * reads the real {@code .msav} tags via {@code SaveIO.getMeta}; headless component
 * tests inject {@link #STRUCTURAL_ONLY} and say so.
 */
public final class CampaignSectorStore {

    /** Validates a staged snapshot file; must throw when the file is not acceptable. */
    public interface SnapshotMetaValidator {
        void validate(Path file, String sectorKey, long revision) throws Exception;
    }

    /** Structural validation only (SHA/length are always checked by the store itself). For tests. */
    public static final SnapshotMetaValidator STRUCTURAL_ONLY = (file, key, revision) -> {
    };

    /**
     * Production validator: the snapshot must be a real Mindustry save whose tags
     * identify this campaign, this exact sector and this revision. Content-aware
     * (requires a loaded game process, which is where the authority runs).
     */
    public static final class SaveMetaValidator implements SnapshotMetaValidator {
        private final String campaignId;

        public SaveMetaValidator(String campaignId) {
            this.campaignId = campaignId;
        }

        @Override
        public void validate(Path file, String sectorKey, long revision) throws Exception {
            SaveMeta m = SaveIO.getMeta(new Fi(file.toFile()));
            if (m == null || m.tags == null) throw new SectorSnapshotCodec.SnapshotRejected("SNAPSHOT_META_MISSING");
            String c = m.tags.get("sc-campaign");
            String s = m.tags.get("sc-sector");
            String r = m.tags.get("sc-revision");
            if (!campaignId.equals(c)) throw new SectorSnapshotCodec.SnapshotRejected("SNAPSHOT_CAMPAIGN_MISMATCH");
            if (!sectorKey.equals(s)) throw new SectorSnapshotCodec.SnapshotRejected("SNAPSHOT_SECTOR_MISMATCH");
            if (!String.valueOf(revision).equals(r)) throw new SectorSnapshotCodec.SnapshotRejected("SNAPSHOT_REVISION_MISMATCH");
        }
    }

    /** Committed snapshot facts as (revision, sha256 hex, byte length). */
    public record SnapshotInfo(long revision, String sha, int length) {
    }

    private final Path root;
    private final String campaignId;
    private final SnapshotMetaValidator validator;

    public CampaignSectorStore(Path storageDir, String campaignId, SnapshotMetaValidator validator) throws IOException {
        if (campaignId == null || !campaignId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IOException("invalid-campaign-id");
        }
        Path dir = storageDir.toAbsolutePath().normalize();
        noLinks(dir);
        if (!Files.isDirectory(dir)) throw new IOException("storage-directory-missing: " + dir);
        this.root = dir.resolve("sector_worlds").resolve(campaignId);
        noLinks(root.getParent());
        noLinks(root);
        Files.createDirectories(root);
        noLinks(root);
        this.campaignId = campaignId;
        this.validator = validator == null ? STRUCTURAL_ONLY : validator;
    }

    public String campaignId() {
        return campaignId;
    }

    public Path root() {
        return root;
    }

    public Path sectorDir(String sectorKey) {
        requireKey(sectorKey);
        Path dir = root.resolve(sectorKey.replace(':', '~'));
        noLinks(dir.getParent());
        noLinks(dir);
        return dir;
    }

    private Path snapshotFile(String sectorKey, long revision) {
        return sectorDir(sectorKey).resolve("snapshot.r" + revision + ".msav");
    }

    // ------------------------------------------------------------------- reads

    /** Committed revision for the sector; 0 = no snapshot has ever been committed. */
    public synchronized long latestRevision(String sectorKey) {
        requireKey(sectorKey);
        Path latest = sectorDir(sectorKey).resolve("latest");
        if (!Files.isRegularFile(latest, LinkOption.NOFOLLOW_LINKS)) return 0;
        try {
            String s = Files.readString(latest).trim();
            long rev = Long.parseLong(s);
            if (rev < 1) throw new NumberFormatException("non-positive");
            return rev;
        } catch (IOException | NumberFormatException e) {
            // A corrupt `latest` must not silently read as "no snapshot" (revision 0):
            // that would let a fresh world overwrite committed data.
            throw new SectorSnapshotCodec.SnapshotRejected("LATEST_CORRUPT: " + e.getMessage());
        }
    }

    /** Bytes of the committed snapshot, or null when none exists. */
    public synchronized byte[] readSnapshot(String sectorKey) {
        long rev = latestRevision(sectorKey);
        if (rev == 0) return null;
        Path f = snapshotFile(sectorKey, rev);
        noLinks(f);
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            throw new SectorSnapshotCodec.SnapshotRejected("SNAPSHOT_FILE_MISSING revision=" + rev);
        }
        try {
            return Files.readAllBytes(f);
        } catch (IOException e) {
            throw new SectorSnapshotCodec.SnapshotRejected("SNAPSHOT_UNREADABLE: " + e.getMessage());
        }
    }

    /** Facts about the committed snapshot (sha recomputed from the actual bytes), or null. */
    public synchronized SnapshotInfo info(String sectorKey) {
        byte[] bytes = readSnapshot(sectorKey);
        if (bytes == null) return null;
        long rev = latestRevision(sectorKey);
        return new SnapshotInfo(rev, SectorSnapshotCodec.sha256(bytes), bytes.length);
    }

    // ----------------------------------------------------------------- commits

    /**
     * Validate and atomically commit snapshot bytes for the sector.
     *
     * @throws SectorSnapshotCodec.SnapshotRejected when refused (checksum, stale
     *         revision, conflict, size, or metadata validation failure)
     */
    public synchronized SnapshotInfo commitSnapshot(String sectorKey, long revision, byte[] bytes, String claimedSha) {
        requireKey(sectorKey);
        if (revision < 1) throw new SectorSnapshotCodec.SnapshotRejected("INVALID_REVISION");
        if (bytes == null || bytes.length == 0) throw new SectorSnapshotCodec.SnapshotRejected("EMPTY_SNAPSHOT");
        if (bytes.length > SectorSnapshotCodec.MAX_SNAPSHOT_BYTES) throw new SectorSnapshotCodec.SnapshotRejected("SNAPSHOT_TOO_LARGE");
        String sha = SectorSnapshotCodec.sha256(bytes);
        if (claimedSha != null && !SectorSnapshotCodec.constantTimeEquals(sha, claimedSha)) {
            throw new SectorSnapshotCodec.SnapshotRejected("CHECKSUM_MISMATCH");
        }
        long current = latestRevision(sectorKey);
        if (revision < current) throw new SectorSnapshotCodec.SnapshotRejected("STALE_REVISION current=" + current);
        if (revision == current) {
            SnapshotInfo existing = info(sectorKey);
            if (existing != null && SectorSnapshotCodec.constantTimeEquals(existing.sha(), sha)) {
                return existing; // idempotent re-send
            }
            throw new SectorSnapshotCodec.SnapshotRejected("REVISION_CONFLICT");
        }

        Path dir = sectorDir(sectorKey);
        Path temp = null;
        try {
            Files.createDirectories(dir);
            noLinks(dir);
            temp = Files.createTempFile(dir, "snapshot-", ".tmp");
            try (FileChannel c = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                c.write(java.nio.ByteBuffer.wrap(bytes));
                c.force(true);
            }
            return commitStaged(temp, sectorKey, revision, sha, bytes.length, current);
        } catch (IOException e) {
            throw new SectorSnapshotCodec.SnapshotRejected("SNAPSHOT_WRITE_FAILED: " + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private SnapshotInfo commitStaged(Path staged, String sectorKey, long revision, String sha, int length, long priorRevision)
            throws IOException {
        try {
            validator.validate(staged, sectorKey, revision);
        } catch (SectorSnapshotCodec.SnapshotRejected e) {
            throw e;
        } catch (Exception e) {
            throw new SectorSnapshotCodec.SnapshotRejected("SNAPSHOT_META_VALIDATION_FAILED: " + e.getMessage());
        }
        Path target = snapshotFile(sectorKey, revision);
        noLinks(target);
        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        // `latest` moves after the snapshot is safely in place; a crash in between
        // leaves the older `latest` pointing at an intact older snapshot.
        Path latest = sectorDir(sectorKey).resolve("latest");
        Path latestTemp = Files.createTempFile(sectorDir(sectorKey), "latest-", ".tmp");
        try {
            Files.writeString(latestTemp, Long.toString(revision));
            noLinks(latest);
            Files.move(latestTemp, latest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(latestTemp);
        }

        // Superseded snapshots are pruned only after the new one is fully committed.
        if (priorRevision > 0 && priorRevision != revision) {
            Files.deleteIfExists(snapshotFile(sectorKey, priorRevision));
        }
        pruneOlderSnapshots(sectorKey, revision);
        return new SnapshotInfo(revision, sha, length);
    }

    private void pruneOlderSnapshots(String sectorKey, long keepRevision) {
        Path dir = sectorDir(sectorKey);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "snapshot.r*.msav")) {
            List<Path> stale = new ArrayList<>();
            for (Path p : ds) {
                String name = p.getFileName().toString();
                try {
                    long rev = Long.parseLong(name.substring("snapshot.r".length(), name.length() - ".msav".length()));
                    if (rev != keepRevision) stale.add(p);
                } catch (NumberFormatException ignored) {
                    // not a revision-named snapshot; leave it for the operator
                }
            }
            for (Path p : stale) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------- lease

    /** Durable local-writer lease record (capability grant). Not a transfer. */
    public synchronized void writeLease(String sectorKey, String grant) throws IOException {
        requireKey(sectorKey);
        Path dir = sectorDir(sectorKey);
        Files.createDirectories(dir);
        noLinks(dir);
        Path f = dir.resolve("lease");
        noLinks(f);
        Files.writeString(f, grant, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    public synchronized String readLease(String sectorKey) {
        requireKey(sectorKey);
        Path f = sectorDir(sectorKey).resolve("lease");
        noLinks(f);
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            String s = Files.readString(f).trim();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            throw new SectorSnapshotCodec.SnapshotRejected("LEASE_UNREADABLE: " + e.getMessage());
        }
    }

    public synchronized void clearLease(String sectorKey) throws IOException {
        requireKey(sectorKey);
        Files.deleteIfExists(sectorDir(sectorKey).resolve("lease"));
    }

    // ---------------------------------------------------------------- internals

    private static void requireKey(String sectorKey) {
        if (!SectorSnapshotCodec.validSectorKey(sectorKey)) {
            throw new SectorSnapshotCodec.SnapshotRejected("INVALID_SECTOR_KEY");
        }
    }

    private static void noLinks(Path p) {
        for (Path q = p; q != null; q = q.getParent()) {
            if (Files.isSymbolicLink(q)) throw new SectorSnapshotCodec.SnapshotRejected("SYMLINK_REFUSED: " + q);
        }
    }
}

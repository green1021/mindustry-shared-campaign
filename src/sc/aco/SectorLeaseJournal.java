package sc.aco;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Fail-closed durable store for sector lease records.
 *
 * <p>Without durability the ownership registry is in-memory only, so an authority
 * restart forgets that a client still holds (and is still simulating) a sector --
 * and answers the next claim from another player with "free", producing two writers
 * for one world. This journal persists every lease transition as a whole-file
 * atomic rewrite before the operation is acknowledged.
 *
 * <p>Format: one pipe-delimited record per line
 * {@code key|kind|state|ownerSession|ownershipVersion|revisionVersion|grant|endpoint}
 * (nullable fields empty). A single malformed line refuses the <em>entire</em>
 * load: a partially parsed lease set could silently hand one sector to two owners
 * after a restart, which is exactly the failure this file exists to prevent.
 * The operator-facing recovery is an explicit archive-and-reset
 * ({@link #archiveCorrupt()}), never an automatic guess.
 */
public class SectorLeaseJournal {

    /** Raised on corrupt/unreadable journal content or on write failure; operations must refuse. */
    public static class LeaseJournalException extends RuntimeException {
        public LeaseJournalException(String message) {
            super(message);
        }

        public LeaseJournalException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final Path file;

    public SectorLeaseJournal(Path directory) throws IOException {
        Path dir = directory.toAbsolutePath().normalize();
        noLinks(dir);
        // The journal lives in a subdirectory of the campaign directory, and that
        // subdirectory is created by nobody else. A missing directory used to
        // disable lease durability for the whole first session of a fresh
        // campaign (the open failed, the registry ran with no journal), which is
        // exactly the window where a crash loses every claim. Create it here; if
        // that fails the caller still gets the degraded, explicitly-logged path.
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IOException("lease-journal-directory-unusable: " + dir + ": " + e.getMessage(), e);
        }
        if (!Files.isDirectory(dir)) throw new IOException("lease-journal-directory-missing: " + dir);
        this.file = dir.resolve("sector_leases.jsonl");
        noLinks(file);
    }

    public Path file() {
        return file;
    }

    /** Load all records; missing file means an empty (fresh) lease set. Throws on any corruption. */
    public synchronized List<SectorOwnership> load() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new ArrayList<>();
        List<SectorOwnership> out = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LeaseJournalException("lease-journal-unreadable: " + e.getMessage(), e);
        }
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            if (line.trim().isEmpty()) continue;
            out.add(parseLine(line, lineNo));
        }
        return out;
    }

    /** Atomically rewrite the whole lease set (temp + fsync + ATOMIC_MOVE). */
    public synchronized void save(Collection<SectorOwnership> entries) {
        List<SectorOwnership> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> a.sectorKey.compareTo(b.sectorKey));
        StringBuilder sb = new StringBuilder();
        for (SectorOwnership o : sorted) {
            sb.append(serialize(o)).append('\n');
        }
        Path dir = file.getParent();
        Path temp = null;
        try {
            noLinks(dir);
            temp = Files.createTempFile(dir, "sector_leases-", ".tmp");
            try (FileChannel c = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                c.write(StandardCharsets.UTF_8.encode(sb.toString()));
                c.force(true);
            }
            noLinks(file);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // The rename is atomic, but without a directory fsync it is not durable:
            // after a power loss the journal could name the previous owner (or none)
            // while the authority already acknowledged the new lease.
            syncDir(dir);
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
            throw new LeaseJournalException("lease-journal-write-failed: " + e.getMessage(), e);
        }
    }

    /**
     * Operator-facing recovery for a corrupt journal: move it aside (never delete)
     * and report where it went. The caller is responsible for deciding what the
     * lease set should be afterwards.
     */
    public synchronized Path archiveCorrupt() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        Path target = file.resolveSibling("sector_leases.jsonl.corrupt-" + System.currentTimeMillis());
        try {
            noLinks(file);
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new LeaseJournalException("lease-journal-archive-failed: " + e.getMessage(), e);
        }
        return target;
    }

    static String serialize(SectorOwnership o) {
        return String.join("|",
                o.sectorKey,
                o.kind.name(),
                o.state.name(),
                o.ownerSessionId == null ? "" : o.ownerSessionId,
                Long.toString(o.ownershipVersion),
                Long.toString(o.revisionVersion),
                o.grant == null ? "" : o.grant,
                o.endpoint == null ? "" : o.endpoint);
    }

    static SectorOwnership parseLine(String line, int lineNo) {
        String[] f = line.split("\\|", -1);
        if (f.length != 8) {
            throw new LeaseJournalException("lease-journal-corrupt line " + lineNo + ": expected 8 fields, got " + f.length);
        }
        String key = f[0];
        if (!SectorSnapshotCodec.validSectorKey(key)) {
            throw new LeaseJournalException("lease-journal-corrupt line " + lineNo + ": bad sector key");
        }
        SectorOwnership o = new SectorOwnership(key);
        try {
            o.kind = SectorOwnership.OwnerKind.valueOf(f[1]);
            o.state = SectorOwnership.LeaseState.valueOf(f[2]);
        } catch (IllegalArgumentException e) {
            throw new LeaseJournalException("lease-journal-corrupt line " + lineNo + ": bad enum: " + e.getMessage());
        }
        o.ownerSessionId = f[3].isEmpty() ? null : f[3];
        try {
            o.ownershipVersion = Long.parseLong(f[4]);
            o.revisionVersion = Long.parseLong(f[5]);
        } catch (NumberFormatException e) {
            throw new LeaseJournalException("lease-journal-corrupt line " + lineNo + ": bad number");
        }
        if (o.ownershipVersion < 0 || o.revisionVersion < 0) {
            throw new LeaseJournalException("lease-journal-corrupt line " + lineNo + ": negative version");
        }
        o.grant = f[6].isEmpty() ? null : f[6];
        o.endpoint = f[7].isEmpty() ? null : f[7];
        // State/kind coherence: ACTIVE/ORPHANED/RESERVED leases must have an owner kind
        // and a grant; FREE must not.
        if (o.state == SectorOwnership.LeaseState.FREE && (o.kind != SectorOwnership.OwnerKind.NONE || o.grant != null)) {
            throw new LeaseJournalException("lease-journal-corrupt line " + lineNo + ": FREE lease carries owner state");
        }
        if (o.state != SectorOwnership.LeaseState.FREE && (o.kind == SectorOwnership.OwnerKind.NONE || o.grant == null)) {
            throw new LeaseJournalException("lease-journal-corrupt line " + lineNo + ": non-free lease without owner/grant");
        }
        // Endpoints only exist on an ACTIVE host lease.
        if (o.endpoint != null && !(o.state == SectorOwnership.LeaseState.ACTIVE && o.kind == SectorOwnership.OwnerKind.HOST)) {
            throw new LeaseJournalException("lease-journal-corrupt line " + lineNo + ": endpoint on non-host lease");
        }
        return o;
    }

    private static void syncDir(Path dir) {
        try (FileChannel fc = FileChannel.open(dir, StandardOpenOption.READ)) {
            fc.force(true);
        } catch (IOException e) {
            // Not supported on every filesystem; the rename itself is still atomic.
        }
    }

    static void noLinks(Path p) {
        for (Path q = p; q != null; q = q.getParent()) {
            if (Files.isSymbolicLink(q)) throw new LeaseJournalException("lease-journal-symlink");
        }
    }
}

package sc.aco;

import arc.util.Log;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CampaignPersistence {
    /**
     * Compaction threshold: the WAL is only rewritten once it exceeds this size, so
     * growth is bounded while small logs stay byte-identical across a recovery.
     */
    public static final long WAL_COMPACT_MIN_BYTES = 1L << 20;

    private final Path stateFile;
    private final Path logFile;
    public Path getStateFile() { return stateFile; }

    /** The kind of refusal that engaged the write-refusing recovery latch. */
    public enum RecoveryRefusal {
        /** A terminated WAL record failed strict parsing: possible in-place corruption of a committed record. */
        WAL_CORRUPTION,
        /** An unterminated partial tail could not be preserved and the WAL could not be truncated. */
        WAL_TAIL_UNREPAIRABLE,
        /** campaign.json exists and is non-empty but fails strict parsing; refusing to start from a blank state. */
        SNAPSHOT_CORRUPTION
    }

    /**
     * Thrown when recovery refuses to proceed because continuing could silently
     * discard or overwrite potentially committed data. Files on disk are left
     * untouched, and the instance latches into a write-refusing state so a caller
     * that ignores this failure cannot save()/appendWAL() over recoverable data.
     * A later clean {@link #recover()} clears the latch.
     */
    public static class RecoveryException extends IOException {
        public final RecoveryRefusal kind;
        /** 1-based WAL record number the refusal refers to, or -1 when not applicable. */
        public final long lineNumber;

        public RecoveryException(RecoveryRefusal kind, long lineNumber, String message) {
            super(message);
            this.kind = kind;
            this.lineNumber = lineNumber;
        }
    }

    /** Non-null while this instance refuses all writes after a refused recovery. */
    private RecoveryException refusal;

    /** True while this instance refuses save()/appendWAL() after a refused recovery. */
    public boolean isRecoveryRefused() {
        return refusal != null;
    }


    public CampaignPersistence(Path root) {
        this.stateFile = root.resolve("campaign.json");
        this.logFile = root.resolve("transaction_log.jsonl");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create persistence directory", e);
        }
    }

    /**
     * Durably install a snapshot: write a uniquely named temp file, fsync it, rename
     * it over campaign.json, then fsync the directory so the rename itself survives a
     * power loss. Serialized per instance because two concurrent saves would
     * otherwise race on one temp path and could install an older serialization after
     * a newer one.
     *
     * <p>On Linux {@code force(true)} on a read-opened channel does issue fsync(2)
     * (verified with strace), but that is platform behaviour the JDK documents as
     * "no effect"; every channel here is opened for writing so the flush is
     * guaranteed by contract rather than by accident.
     */
    public void save(GlobalCampaignState state) throws IOException {
        synchronized (this) {
            if (refusal != null) {
                throw new RecoveryException(refusal.kind, refusal.lineNumber,
                    "write refused until a clean recovery: " + refusal.getMessage());
            }
            Path tmp = Files.createTempFile(stateFile.getParent(), "campaign-", ".json.tmp");
            boolean installed = false;
            try {
                byte[] json = toJson(state).getBytes(StandardCharsets.UTF_8);
                try (FileChannel fc = FileChannel.open(tmp, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(json);
                    while (buf.hasRemaining()) fc.write(buf);
                    fc.force(true);
                }
                Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                syncDir(stateFile.getParent());
                installed = true;
            } finally {
                // Any failure - including a RuntimeException out of toJson - must not
                // leave a temp file behind for the next recovery to trip over.
                if (!installed) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                        // the original failure is the one worth reporting
                    }
                }
            }
        }
        // The snapshot is durable: records it already contains are dead weight.
        // Records above the snapshot revision (including an in-flight commit that
        // appended before mutating) are kept, so compaction can never drop an
        // operation the snapshot does not carry yet. Compaction is threshold-gated:
        // rewriting a small, already-correct log on every save would add an fsync
        // per transaction and destroy the log's short-horizon audit value.
        maybeCompactWAL(state.globalRevision);
    }

    public void appendWAL(TransactionLogEntry entry) throws IOException {
        synchronized (this) {
            if (refusal != null) {
                throw new RecoveryException(refusal.kind, refusal.lineNumber,
                    "write refused until a clean recovery: " + refusal.getMessage());
            }
            byte[] line = (entry.toJson() + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel fc = FileChannel.open(logFile, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(line);
                while (buf.hasRemaining()) fc.write(buf);
                fc.force(true);
            }
        }
    }

    public Optional<GlobalCampaignState> loadState() throws IOException {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        
        String json = Files.readString(stateFile);
        // A zero-length snapshot holds no recoverable data (the atomic rename in
        // save() can never produce one); treat it as absent before the gate below.
        if (json.trim().isEmpty()) return Optional.empty();
        if (!isPlausibleSnapshot(json)) {
            refusal = new RecoveryException(RecoveryRefusal.SNAPSHOT_CORRUPTION, -1,
                "campaign.json is present (" + json.length() + " chars) but is not a plausible snapshot document");
            Log.err("ACO recovery refused: " + refusal.getMessage());
            throw refusal;
        }
        try {
            GlobalCampaignState state = fromJson(json);
            return Optional.of(state);
        } catch (Exception e) {
            // The snapshot exists and is non-empty: it may be recoverable, and a
            // later save() would overwrite it. Fail closed instead of silently
            // starting from a blank state, which would permanently lose every
            // committed operation the snapshot carries.
            refusal = new RecoveryException(RecoveryRefusal.SNAPSHOT_CORRUPTION, -1,
                "campaign.json is present (" + json.length() + " chars) but fails to parse: " + e);
            Log.err("ACO recovery refused: " + refusal.getMessage());
            throw refusal;
        }
    }

    public List<TransactionLogEntry> loadWAL() throws IOException {
        // Fail-closed recovery policy:
        //  - terminated + malformed -> refuse: the writer terminates every record
        //    before its fsync returns, so this may be a committed record corrupted
        //    in place; nothing is rewritten and no snapshot is saved.
        //  - unterminated + malformed -> torn write (a prefix of an intended
        //    record, never committed): preserve the bytes to a sidecar, truncate
        //    the WAL back to its last complete record, replay the rest.
        //  - unterminated + parseable -> a complete record whose final newline
        //    never reached the disk: redo it, then restore the terminator.
        // Known limitation: bytes alone cannot distinguish an in-place-corrupted
        // record that also lost its terminator from a torn write; such bytes are
        // treated as torn and preserved to the sidecar, never silently discarded.
        List<TransactionLogEntry> entries = new ArrayList<>();
        if (!Files.exists(logFile)) return entries;

        byte[] raw = Files.readAllBytes(logFile);
        int pos = 0;      // byte offset of the current record
        int record = 0;   // 1-based record number for diagnostics
        while (pos < raw.length) {
            int nl = indexOfNewline(raw, pos);
            boolean terminated = nl >= 0;
            int end = terminated ? nl : raw.length;
            String line = new String(raw, pos, end - pos, StandardCharsets.UTF_8).trim();
            record++;

            if (!line.isEmpty()) {
                TransactionLogEntry entry = null;
                Exception parseError = null;
                try {
                    entry = TransactionLogEntry.fromJson(line);
                    if (entry == null || entry.operationId == null) {
                        throw new IllegalArgumentException("record reconstructed without an operationId");
                    }
                } catch (Exception e) {
                    parseError = e;
                }

                if (parseError == null) {
                    entries.add(entry);
                    if (!terminated) {
                        repairMissingTerminator();
                    }
                } else if (terminated) {
                    refusal = new RecoveryException(RecoveryRefusal.WAL_CORRUPTION, record,
                        "malformed WAL record at line " + record + ": " + parseError
                            + " :: " + abbreviate(line));
                    Log.err("ACO recovery refused: " + refusal.getMessage());
                    throw refusal;
                } else {
                    preserveAndTruncateTornTail(raw, pos);
                }
            } else if (!terminated) {
                // A whitespace-only unterminated tail is the same partial-write
                // situation: preserve and truncate.
                preserveAndTruncateTornTail(raw, pos);
            }

            if (!terminated) break;
            pos = nl + 1;
        }
        return entries;
    }

    public GlobalCampaignState recover() throws IOException {
        // A clean recovery is the sanctioned re-evaluation point: clear any latch
        // from a previous refused attempt. If this attempt also refuses, the latch
        // is re-engaged before anything on disk is touched and the save() below is
        // never reached, so a snapshot is never written from a state that silently
        // omits a possibly committed record.
        refusal = null;
        GlobalCampaignState state = loadState().orElseGet(() -> {
            GlobalCampaignState s = new GlobalCampaignState();
            s.campaignId = "default";
            s.globalRevision = 0;
            return s;
        });

        List<TransactionLogEntry> wal = loadWAL();
        for (TransactionLogEntry entry : wal) {
            // Idempotent WAL replay: apply ONLY if the snapshot does not already
            // reflect the operation. Two independent guards are needed now that the
            // idempotency window is bounded: the operation id (exact record) and the
            // snapshot revision (an entry at or below the snapshot's revision was
            // folded into it even if its id has since been evicted, because the
            // revision is assigned under the same lock that serializes the save).
            if (entry.globalRevision <= state.globalRevision) {
                continue;
            }
            if (state.processedOperations.containsKey(entry.operationId)) {
                continue;
            }
            TransactionResult res = new TransactionResult(
                entry.operationId,
                entry.success,
                entry.error,
                entry.globalRevision,
                entry.newBalance
            );
            // Rebuild the fingerprint-bearing entry so post-restart retries are
            // verified exactly like pre-restart ones: an identical payload is served
            // the committed result, a tampered payload gets
            // IDEMPOTENCY_PAYLOAD_MISMATCH.
            RequestFingerprint fp = new RequestFingerprint(
                entry.operationId, entry.sessionId, entry.sectorId,
                entry.expectedGlobalRevision, entry.resourceType, entry.amount, entry.operationType);
            state.recordProcessed(entry.operationId, new GlobalBank.IdempotentEntry(fp, res));

            if (entry.success) {
                // newBalance is the absolute post-operation balance recorded at commit
                // time: replaying entries in WAL order converges even across several
                // committed-but-unsnapshotted operations.
                state.globalBank.put(entry.resourceType, entry.newBalance);
                if (entry.globalRevision > state.globalRevision) {
                    state.globalRevision = entry.globalRevision;
                }
            }
        }

        // Save reconciled state back to the snapshot. Reached only after the WAL
        // scanned clean, or after an uncommitted torn tail was preserved and
        // dropped; any refusal throws above and never overwrites the snapshot.
        save(state);
        return state;
    }

    private static int indexOfNewline(byte[] data, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == '\n') return i;
        }
        return -1;
    }

    /** Root fields any legitimate snapshot must contain at least one of. */
    private static final String[] KNOWN_SNAPSHOT_FIELDS = {
        "campaignId", "globalRevision", "unlockedSectors", "completedSectors",
        "techTree", "globalBank", "processedOperations"
    };

    /**
     * Gate against the lenient snapshot reader: fromJson() tolerates arbitrary
     * text by treating it as a mostly empty document, so structurally invalid
     * content would otherwise be silently replaced by a blank state that a later
     * save() persists over recoverable data. Accept only a brace-balanced JSON
     * object naming at least one known root field.
     */
    private static boolean isPlausibleSnapshot(String json) {
        if (json == null) return false;
        String t = json.trim();
        if (t.length() < 2 || t.charAt(0) != '{' || t.charAt(t.length() - 1) != '}') return false;
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"' && (i == 0 || t.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
                continue;
            }
            if (inQuote) continue;
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) return false;
            }
        }
        if (depth != 0 || inQuote) return false;
        for (String field : KNOWN_SNAPSHOT_FIELDS) {
            if (t.contains("\"" + field + "\"")) return true;
        }
        return false;
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "...(truncated)";
    }

    /** Restore a missing record terminator so future appends cannot concatenate onto the last record. */
    private void repairMissingTerminator() throws IOException {
        try (FileChannel ch = FileChannel.open(logFile, StandardOpenOption.WRITE)) {
            ch.position(ch.size());
            ch.write(java.nio.ByteBuffer.wrap(new byte[] {'\n'}));
            ch.force(true);
        }
        Log.err("WAL final record was missing its terminator (incomplete fsync); terminator restored");
    }

    /**
     * Non-destructive repair for an unterminated torn tail: preserve the exact
     * bytes to a sidecar file first, then truncate the WAL back to its last
     * complete record. If either step fails, nothing is truncated and recovery
     * refuses instead.
     */
    private void preserveAndTruncateTornTail(byte[] raw, int tailStart) throws IOException {
        byte[] tail = Arrays.copyOfRange(raw, tailStart, raw.length);
        Path sidecar = logFile.resolveSibling(
            logFile.getFileName().toString() + ".torn-tail-" + System.currentTimeMillis());
        try {
            Files.write(sidecar, tail);
            try (FileChannel ch = FileChannel.open(logFile, StandardOpenOption.WRITE)) {
                ch.truncate(tailStart);
                ch.force(true);
            }
        } catch (IOException e) {
            refusal = new RecoveryException(RecoveryRefusal.WAL_TAIL_UNREPAIRABLE, -1,
                "torn WAL tail could not be preserved and truncated: " + e);
            Log.err("ACO recovery refused: " + refusal.getMessage());
            throw refusal;
        }
        Log.err("WAL torn tail (" + tail.length + " bytes, never committed) preserved as "
            + sidecar.getFileName() + "; WAL truncated to its last complete record");
    }

    /**
     * Drop WAL records that the durable snapshot already contains.
     *
     * <p>Before this existed the log was append-only for the life of a campaign:
     * every resource transaction stayed on disk forever and every restart replayed
     * the whole history, even though each save had already folded those records into
     * campaign.json. Compaction is revision-based, not "truncate everything": a
     * record is dropped only when {@code record.globalRevision <= snapshotRevision}.
     * A concurrent commit appends its record before mutating and saves after, so an
     * in-flight record always carries a revision above the current snapshot and is
     * never dropped.
     *
     * <p>Failures are logged and leave the existing log untouched: losing the
     * compaction opportunity is harmless (the next save retries), while truncating
     * on a partial read would not be.
     */
    public synchronized void compactWAL(long snapshotRevision) {
        if (refusal != null) return;
        if (!Files.exists(logFile)) return;
        try {
            byte[] raw = Files.readAllBytes(logFile);
            StringBuilder keep = new StringBuilder(raw.length);
            boolean dropped = false;
            int pos = 0;
            while (pos < raw.length) {
                int nl = indexOfNewline(raw, pos);
                boolean terminated = nl >= 0;
                int end = terminated ? nl : raw.length;
                String trimmed = new String(raw, pos, end - pos, StandardCharsets.UTF_8).trim();
                if (!trimmed.isEmpty()) {
                    boolean covered = false;
                    try {
                        TransactionLogEntry e = TransactionLogEntry.fromJson(trimmed);
                        covered = e != null && e.globalRevision <= snapshotRevision;
                    } catch (RuntimeException ignored) {
                        // Unreadable record: keep it so recovery still gets the chance
                        // to refuse on it instead of this method silently discarding it.
                    }
                    if (covered) {
                        dropped = true;
                    } else {
                        keep.append(trimmed);
                        // An unterminated tail must stay unterminated; terminating it
                        // would turn a torn write into a malformed committed record.
                        if (terminated) keep.append('\n');
                    }
                }
                pos = terminated ? nl + 1 : raw.length;
            }
            if (!dropped) return;
            Path tmp = Files.createTempFile(logFile.getParent(), "transaction_log-", ".tmp");
            try {
                try (FileChannel fc = FileChannel.open(tmp, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(
                        keep.toString().getBytes(StandardCharsets.UTF_8));
                    while (buf.hasRemaining()) fc.write(buf);
                    fc.force(true);
                }
                Files.move(tmp, logFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                syncDir(logFile.getParent());
            } finally {
                Files.deleteIfExists(tmp);
            }
            Log.info("ACO_WAL_COMPACTED upToRevision=@ bytes=@ -> @", snapshotRevision, raw.length, keep.length());
        } catch (IOException e) {
            Log.err("ACO_WAL_COMPACT_FAILED upToRevision=@ (log left intact)", snapshotRevision, e);
        }
    }

    /**
     * Compact only once the log has actually grown past {@link #WAL_COMPACT_MIN_BYTES}.
     * Growth is bounded by that threshold plus the records of the save in flight.
     */
    private void maybeCompactWAL(long snapshotRevision) {
        try {
            if (!Files.exists(logFile) || Files.size(logFile) <= WAL_COMPACT_MIN_BYTES) return;
        } catch (IOException e) {
            return;
        }
        compactWAL(snapshotRevision);
    }

    /** Explicit full clear, kept for operators/tests; same atomic path as compaction. */
    public void clearWAL() throws IOException {
        compactWAL(Long.MAX_VALUE);
    }

    /** fsync a directory so a rename inside it is durable. */
    private static void syncDir(Path dir) {
        try (FileChannel fc = FileChannel.open(dir, StandardOpenOption.READ)) {
            fc.force(true);
        } catch (IOException e) {
            // Directory fsync is unsupported on some filesystems (e.g. Windows).
            // The rename is still atomic; only its power-loss durability is weaker.
            Log.warn("ACO_DIR_SYNC_FAILED dir=@ : @", dir, e.getMessage());
        }
    }

    /**
     * Serialize a campaign state. Static and instance-free so the same codec is
     * used for the on-disk snapshot and for the {@code GLOBAL_STATE_SYNC}
     * wire payload a client parses (see {@link AcoClient#handleGlobalStateSync}).
     */
    public static String toJson(GlobalCampaignState state) {
        return toJson(state, true);
    }

    /**
     * The wire form of a campaign sync: identical to {@link #toJson} except that
     * the idempotency table is omitted. Clients fold the campaign view; they never
     * replay authority-side operation ids, and with a bounded-but-large window the
     * table would push {@code GLOBAL_STATE_SYNC} past {@link AcoFrame#MAX_PAYLOAD}
     * and tear down every connection with an oversized frame.
     */
    public static String toSyncJson(GlobalCampaignState state) {
        return toJson(state, false);
    }

    private static String toJson(GlobalCampaignState state, boolean withProcessed) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"campaignId\":\"").append(escape(state.campaignId)).append("\",");
        sb.append("\"globalRevision\":").append(state.globalRevision).append(",");
        
        sb.append("\"unlockedSectors\":[");
        boolean first = true;
        for (String s : sorted(state.unlockedSectors)) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(s)).append("\"");
            first = false;
        }
        sb.append("],");
        
        sb.append("\"completedSectors\":[");
        first = true;
        for (String s : sorted(state.completedSectors)) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(s)).append("\"");
            first = false;
        }
        sb.append("],");
        
        sb.append("\"techTree\":{");
        first = true;
        for (Map.Entry<String, Boolean> e : new java.util.TreeMap<>(state.techTree).entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(e.getKey())).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("},");
        
        sb.append("\"globalBank\":{");
        first = true;
        for (Map.Entry<String, Long> e : new java.util.TreeMap<>(state.globalBank).entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(e.getKey())).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("},");
        
        if (!withProcessed) {
            // Drop the separator left by the previous field, then close the object.
            if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
            sb.append("}");
            return sb.toString();
        }
        sb.append("\"processedOperations\":{");
        first = true;
        // Window order, not hash order: the table has to be reproducible and its
        // oldest entries have to be identifiable after a reload.
        for (String opKey : orderedProcessedKeys(state)) {
            Object value = state.processedOperations.get(opKey);
            if (!first) sb.append(",");
            sb.append("\"").append(escape(opKey)).append("\":");
            if (value instanceof GlobalBank.IdempotentEntry entry) {
                // Full idempotency record: the payload fingerprint plus the result it
                // serves. Both must survive the snapshot, or a restart would forget
                // what the operation was and could not verify retries.
                sb.append("{\"fingerprint\":").append(entry.fingerprint().toJson());
                sb.append(",\"result\":").append(entry.result().toJson()).append("}");
            } else if (value instanceof TransactionResult res) {
                // Legacy bare result (pre-fingerprint snapshot): preserved verbatim so
                // recovery can keep it explicitly fingerprint-less.
                sb.append(res.toJson());
            } else if (value instanceof Boolean b) {
                sb.append(b.booleanValue());
            } else {
                // Legacy marker or unknown value: persisted as null and restored as the
                // explicit legacy marker; never as a fingerprint match.
                sb.append("null");
            }
            first = false;
        }
        sb.append("}");
        sb.append("}");
        return sb.toString();
    }

    /** Parse a campaign state produced by {@link #toJson}. Static, see above. */
    public static GlobalCampaignState fromJson(String json) {
        GlobalCampaignState state = new GlobalCampaignState();
        if (json == null || json.trim().isEmpty()) return state;

        Map<String, String> root = parseSimpleJson(json);
        state.campaignId = root.getOrDefault("campaignId", "default");
        state.globalRevision = Long.parseLong(root.getOrDefault("globalRevision", "0"));

        if (root.containsKey("unlockedSectors")) {
            for (String s : parseJsonArray(root.get("unlockedSectors"))) {
                state.unlockedSectors.add(s);
            }
        }

        if (root.containsKey("completedSectors")) {
            for (String s : parseJsonArray(root.get("completedSectors"))) {
                state.completedSectors.add(s);
            }
        }

        if (root.containsKey("techTree")) {
            for (Map.Entry<String, String> e : parseSimpleJson(root.get("techTree")).entrySet()) {
                state.techTree.put(e.getKey(), Boolean.parseBoolean(e.getValue()));
            }
        }

        if (root.containsKey("globalBank")) {
            for (Map.Entry<String, String> e : parseSimpleJson(root.get("globalBank")).entrySet()) {
                state.globalBank.put(e.getKey(), Long.parseLong(e.getValue()));
            }
        }

        // processedOperations is parsed from the RAW document text (not through the
        // quote-stripping scalar reader above): its values are nested objects whose
        // quoted strings and escapes must survive intact for the strict fingerprint
        // parser to accept them.
        String rawOps = extractRawObjectField(json, "processedOperations");
        if (rawOps != null) {
            for (Map.Entry<String, String> e : splitRawValues(rawOps).entrySet()) {
                state.recordProcessed(e.getKey(), parseProcessedOperation(e.getKey(), e.getValue()));
            }
        }

        return state;
    }

    private static java.util.List<String> sorted(java.util.Collection<String> in) {
        java.util.List<String> out = new java.util.ArrayList<>(in);
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * The idempotency keys in window order (oldest first), followed by any key that
     * was placed in the map without going through {@code recordProcessed} (legacy
     * snapshots built by tests) so no entry is ever silently dropped from the file.
     */
    private static java.util.List<String> orderedProcessedKeys(GlobalCampaignState state) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String k : state.processedOrderSnapshot()) {
            if (state.processedOperations.containsKey(k) && !keys.contains(k)) keys.add(k);
        }
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (String k : state.processedOperations.keySet()) {
            if (!keys.contains(k)) rest.add(k);
        }
        java.util.Collections.sort(rest);
        keys.addAll(rest);
        return keys;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static Map<String, String> parseSimpleJson(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null) return map;
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        int depth = 0;
        boolean inQuote = false;
        StringBuilder key = new StringBuilder();
        StringBuilder val = new StringBuilder();
        boolean parsingKey = true;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
                continue;
            }
            if (!inQuote) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
                else if (c == ':' && depth == 0 && parsingKey) {
                    parsingKey = false;
                    continue;
                } else if (c == ',' && depth == 0) {
                    String k = key.toString().trim();
                    String v = val.toString().trim();
                    if (!k.isEmpty()) map.put(k, v);
                    key.setLength(0);
                    val.setLength(0);
                    parsingKey = true;
                    continue;
                }
            }
            if (parsingKey) key.append(c);
            else val.append(c);
        }
        String k = key.toString().trim();
        String v = val.toString().trim();
        if (!k.isEmpty()) map.put(k, v);
        return map;
    }

    /**
     * Parse one persisted {@code processedOperations} value. Three shapes exist and
     * each is handled explicitly; none is ever silently converted into a matching
     * fingerprint:
     * <ul>
     *   <li>fingerprint envelope {@code {"fingerprint":{...},"result":{...}}} —
     *       restored as the exact {@link GlobalBank.IdempotentEntry};</li>
     *   <li>legacy bare result object (pre-fingerprint snapshot) — kept as a
     *       {@link TransactionResult}; a duplicate id on such a record is refused
     *       with {@code INCOMPATIBLE_IDEMPOTENCY_TYPE} by the bank;</li>
     *   <li>boolean completion marker or {@code null} (unrecorded legacy value) —
     *       kept as a marker ({@link LegacyOperation#INSTANCE}); duplicates refused.</li>
     * </ul>
     * An unreadable value degrades to {@link LegacyOperation#INSTANCE} (safe: a
     * duplicate is refused rather than mis-matched) and is logged, never dropped.
     */
    private static Object parseProcessedOperation(String opId, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("{")) {
            try {
                if (value.contains("\"fingerprint\"")) {
                    Map<String, String> envelope = splitRawValues(value);
                    RequestFingerprint fp = RequestFingerprint.fromJson(envelope.get("fingerprint"));
                    TransactionResult res = parseTransactionResultStrict(envelope.get("result"));
                    return new GlobalBank.IdempotentEntry(fp, res);
                }
                return parseTransactionResultStrict(value);
            } catch (RuntimeException ex) {
                Log.err("Unreadable processedOperations entry @" + opId
                    + " (kept as legacy marker, duplicates will be refused): " + value);
                return LegacyOperation.INSTANCE;
            }
        }
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.parseBoolean(value);
        }
        // "null" or anything unrecognized: the legacy serializer wrote null for
        // every value it could not represent. Keep the key so the operation id stays
        // occupied; refuse to pretend it matches a fingerprint.
        return LegacyOperation.INSTANCE;
    }

    private static TransactionResult parseTransactionResultStrict(String json) {
        Map<String, FlatJson.Value> m = FlatJson.parseObjectStrict(json);
        return new TransactionResult(
            FlatJson.requireString(m, "operationId"),
            FlatJson.requireBoolean(m, "success"),
            FlatJson.requireString(m, "error"),
            FlatJson.requireLong(m, "newRevision"),
            FlatJson.requireLong(m, "newBalance"));
    }

    /**
     * Extract the raw (verbatim, quotes intact) text of one object-valued root
     * field, e.g. {@code processedOperations}, bounded by balanced braces.
     */
    private static String extractRawObjectField(String json, String field) {
        String needle = "\"" + field + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int i = at + needle.length();
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != ':') return null;
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '{') return null;
        int start = i;
        int depth = 0;
        boolean inStr = false;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
            i++;
        }
        return null;
    }

    /**
     * Split {@code {"key":<raw>,...}} into key to verbatim value text. Unlike the
     * scalar reader, quotes and escapes inside values are preserved so nested
     * objects can be re-parsed by strict readers.
     */
    private static Map<String, String> splitRawValues(String json) {
        // Insertion-ordered: a legacy snapshot can carry more markers than the
        // bounded window, and trimming must drop the OLDEST ones. With HashMap the
        // survivor set was arbitrary, so the newest markers could be discarded.
        Map<String, String> map = new java.util.LinkedHashMap<>();
        if (json == null) return map;
        String s = json.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);

        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            if (i >= n) break;
            if (s.charAt(i) != '"') break; // malformed key; caller treats it as legacy
            StringBuilder key = new StringBuilder();
            i++;
            while (i < n) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < n) {
                    key.append(c).append(s.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    i++;
                    break;
                }
                key.append(c);
                i++;
            }
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= n || s.charAt(i) != ':') break;
            i++;
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            int start = i;
            if (i < n && s.charAt(i) == '"') {
                i++;
                while (i < n) {
                    char c = s.charAt(i);
                    if (c == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (c == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
            } else {
                int depth = 0;
                while (i < n) {
                    char c = s.charAt(i);
                    if (c == '"') {
                        i++;
                        while (i < n) {
                            char d = s.charAt(i);
                            if (d == '\\' && i + 1 < n) {
                                i += 2;
                                continue;
                            }
                            if (d == '"') break;
                            i++;
                        }
                    } else if (c == '{' || c == '[') {
                        depth++;
                    } else if (c == '}' || c == ']') {
                        if (depth == 0) break;
                        depth--;
                    } else if (c == ',' && depth == 0) {
                        break;
                    }
                    i++;
                }
            }
            map.put(key.toString(), s.substring(start, i).trim());
        }
        return map;
    }

    /**
     * Explicit marker for a persisted operation record whose payload metadata is
     * unrecoverable (legacy {@code null} entries, unknown value shapes). It keeps
     * the operation id occupied — a duplicate request is refused with
     * {@code INCOMPATIBLE_IDEMPOTENCY_TYPE} — without ever pretending to be a
     * verifiable fingerprint.
     */
    public static final class LegacyOperation {
        public static final LegacyOperation INSTANCE = new LegacyOperation();

        private LegacyOperation() {
        }
    }

    private static List<String> parseJsonArray(String json) {
        List<String> list = new ArrayList<>();
        if (json == null) return list;
        json = json.trim();
        if (json.startsWith("[")) json = json.substring(1);
        if (json.endsWith("]")) json = json.substring(0, json.length() - 1);

        boolean inQuote = false;
        StringBuilder elem = new StringBuilder();

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
                continue;
            }
            if (!inQuote && c == ',') {
                String s = elem.toString().trim();
                if (!s.isEmpty()) list.add(s);
                elem.setLength(0);
                continue;
            }
            elem.append(c);
        }
        String s = elem.toString().trim();
        if (!s.isEmpty()) list.add(s);
        return list;
    }
}
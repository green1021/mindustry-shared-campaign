package sc.aco;

import arc.util.Log;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CampaignPersistence {
    private final Path stateFile;
    private final Path logFile;
    public Path getStateFile() { return stateFile; }

    public CampaignPersistence(Path root) {
        this.stateFile = root.resolve("campaign.json");
        this.logFile = root.resolve("transaction_log.jsonl");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create persistence directory", e);
        }
    }

    public void save(GlobalCampaignState state) throws IOException {
        Path tmp = stateFile.resolveSibling("campaign.json.tmp");
        try {
            String json = toJson(state);
            Files.writeString(tmp, json);
            
            try (FileChannel fc = FileChannel.open(tmp, StandardOpenOption.READ)) {
                fc.force(true);
            }
            
            Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            
            try (FileChannel fc = FileChannel.open(stateFile.getParent(), StandardOpenOption.READ)) {
                fc.force(true);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    public void appendWAL(TransactionLogEntry entry) throws IOException {
        String line = entry.toJson();
        Files.write(logFile, (line + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        
        try (FileChannel fc = FileChannel.open(logFile, StandardOpenOption.READ)) {
            fc.force(true);
        }
    }

    public Optional<GlobalCampaignState> loadState() throws IOException {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        
        String json = Files.readString(stateFile);
        if (json.trim().isEmpty()) return Optional.empty();
        
        try {
            GlobalCampaignState state = fromJson(json);
            return Optional.of(state);
        } catch (Exception e) {
            Log.err("Failed to parse campaign.json", e);
            return Optional.empty();
        }
    }

    public List<TransactionLogEntry> loadWAL() throws IOException {
        List<TransactionLogEntry> entries = new ArrayList<>();
        if (!Files.exists(logFile)) return entries;
        
        List<String> lines = Files.readAllLines(logFile);
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                TransactionLogEntry entry = TransactionLogEntry.fromJson(line);
                if (entry != null && entry.operationId != null) {
                    entries.add(entry);
                }
            } catch (Exception e) {
                Log.err("Skipping malformed WAL line: " + line);
            }
        }
        return entries;
    }

    public GlobalCampaignState recover() throws IOException {
        GlobalCampaignState state = loadState().orElseGet(() -> {
            GlobalCampaignState s = new GlobalCampaignState();
            s.campaignId = "default";
            s.globalRevision = 0;
            return s;
        });

        List<TransactionLogEntry> wal = loadWAL();
        for (TransactionLogEntry entry : wal) {
            // Idempotent WAL replay: apply ONLY if the snapshot does not already
            // reflect the operation. A second recovery must not re-apply anything,
            // and neither must a snapshot that already contains the entry.
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
            state.processedOperations.put(entry.operationId, new GlobalBank.IdempotentEntry(fp, res));

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

        // Save reconciled state back to snapshot
        save(state);
        return state;
    }

    public void clearWAL() throws IOException {
        if (Files.exists(logFile)) {
            Files.write(logFile, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    /**
     * Serialize a campaign state. Static and instance-free so the same codec is
     * used for the on-disk snapshot and for the {@code GLOBAL_STATE_SYNC}
     * wire payload a client parses (see {@link AcoClient#handleGlobalStateSync}).
     */
    public static String toJson(GlobalCampaignState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"campaignId\":\"").append(escape(state.campaignId)).append("\",");
        sb.append("\"globalRevision\":").append(state.globalRevision).append(",");
        
        sb.append("\"unlockedSectors\":[");
        boolean first = true;
        for (String s : state.unlockedSectors) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(s)).append("\"");
            first = false;
        }
        sb.append("],");
        
        sb.append("\"completedSectors\":[");
        first = true;
        for (String s : state.completedSectors) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(s)).append("\"");
            first = false;
        }
        sb.append("],");
        
        sb.append("\"techTree\":{");
        first = true;
        for (Map.Entry<String, Boolean> e : state.techTree.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(e.getKey())).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("},");
        
        sb.append("\"globalBank\":{");
        first = true;
        for (Map.Entry<String, Long> e : state.globalBank.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(e.getKey())).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("},");
        
        sb.append("\"processedOperations\":{");
        first = true;
        for (Map.Entry<String, Object> e : state.processedOperations.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(e.getKey())).append("\":");
            if (e.getValue() instanceof GlobalBank.IdempotentEntry entry) {
                // Full idempotency record: the payload fingerprint plus the result it
                // serves. Both must survive the snapshot, or a restart would forget
                // what the operation was and could not verify retries.
                sb.append("{\"fingerprint\":").append(entry.fingerprint().toJson());
                sb.append(",\"result\":").append(entry.result().toJson()).append("}");
            } else if (e.getValue() instanceof TransactionResult res) {
                // Legacy bare result (pre-fingerprint snapshot): preserved verbatim so
                // recovery can keep it explicitly fingerprint-less.
                sb.append(res.toJson());
            } else if (e.getValue() instanceof Boolean b) {
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
                state.processedOperations.put(e.getKey(), parseProcessedOperation(e.getKey(), e.getValue()));
            }
        }

        return state;
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
        Map<String, String> map = new HashMap<>();
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
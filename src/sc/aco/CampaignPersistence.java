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
            // Idempotent WAL replay: apply ONLY if not already reflected in state
            if (!state.processedOperations.containsKey(entry.operationId)) {
                TransactionResult res = new TransactionResult(
                    entry.operationId,
                    entry.success,
                    entry.error,
                    entry.globalRevision,
                    entry.newBalance
                );
                state.processedOperations.put(entry.operationId, res);

                if (entry.success) {
                    if ("CREDIT".equals(entry.operationType) || "DEPOSIT".equals(entry.operationType)) {
                        long cur = state.globalBank.getOrDefault(entry.resourceType, 0L);
                        state.globalBank.put(entry.resourceType, cur + entry.amount);
                    } else if ("DEBIT".equals(entry.operationType) || "WITHDRAW".equals(entry.operationType)) {
                        long cur = state.globalBank.getOrDefault(entry.resourceType, 0L);
                        state.globalBank.put(entry.resourceType, Math.max(0, cur - entry.amount));
                    }
                    if (entry.globalRevision > state.globalRevision) {
                        state.globalRevision = entry.globalRevision;
                    }
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

    public String toJson(GlobalCampaignState state) {
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
            if (e.getValue() instanceof TransactionResult res) {
                sb.append(res.toJson());
            } else {
                sb.append("null");
            }
            first = false;
        }
        sb.append("}");
        sb.append("}");
        return sb.toString();
    }

    public GlobalCampaignState fromJson(String json) {
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

        if (root.containsKey("processedOperations")) {
            Map<String, String> ops = parseJsonObjectMap(root.get("processedOperations"));
            for (Map.Entry<String, String> e : ops.entrySet()) {
                Map<String, String> resMap = parseSimpleJson(e.getValue());
                TransactionResult res = new TransactionResult(
                    resMap.getOrDefault("operationId", e.getKey()),
                    Boolean.parseBoolean(resMap.getOrDefault("success", "false")),
                    resMap.get("error"),
                    Long.parseLong(resMap.getOrDefault("newRevision", "0")),
                    Long.parseLong(resMap.getOrDefault("newBalance", "0"))
                );
                state.processedOperations.put(e.getKey(), res);
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

    private static Map<String, String> parseJsonObjectMap(String json) {
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
                if (parsingKey) continue;
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
package sc.aco;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strict parser/serializer for the project's <em>flat</em> JSON objects: the
 * one-level {@code {"key":string|number|boolean,...}} shape used by
 * {@link RequestFingerprint}, {@link TransactionResult} and
 * {@link TransactionLogEntry} persistence.
 *
 * <p>It exists because the previous WAL reader was a stub that accepted anything
 * and every replayed record vanished. This parser rejects missing fields,
 * malformed numbers, malformed booleans, type-confused values, duplicate keys,
 * unknown escapes, unterminated strings, nested structures and trailing garbage,
 * so a corrupt record cannot masquerade
 * as a valid one.
 *
 * <p>String escapes handled: quote, backslash, and the control escapes (n, r, t, b, f, slash), plus four-hex-digit unicode escapes.
 * An unknown escape is malformed, not tolerated.
 */
final class FlatJson {

    private FlatJson() {
    }

    /** Parse one flat JSON object; values carry their JSON type (strictly validated). */
    static Map<String, Value> parseObjectStrict(String json) {
        if (json == null) throw new IllegalArgumentException("null json");
        int i = skipWs(json, 0);
        if (i >= json.length() || json.charAt(i) != '{') throw new IllegalArgumentException("expected '{'");
        i++;
        Map<String, Value> map = new LinkedHashMap<>();
        i = skipWs(json, i);
        if (i < json.length() && json.charAt(i) == '}') {
            i = skipWs(json, i + 1);
            if (i != json.length()) throw new IllegalArgumentException("trailing garbage");
            return map;
        }
        while (true) {
            i = skipWs(json, i);
            if (i >= json.length() || json.charAt(i) != '"') throw new IllegalArgumentException("expected key");
            Str key = readString(json, i);
            i = skipWs(json, key.end);
            if (i >= json.length() || json.charAt(i) != ':') throw new IllegalArgumentException("expected ':' after key");
            i = skipWs(json, i + 1);
            if (i >= json.length()) throw new IllegalArgumentException("missing value");
            char c = json.charAt(i);
            Value value;
            if (c == '"') {
                Str s = readString(json, i);
                value = new Value(Value.Kind.STRING, s.value);
                i = s.end;
            } else if (c == '{' || c == '[') {
                throw new IllegalArgumentException("nested structures are not supported in flat json");
            } else {
                int start = i;
                while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') i++;
                String lit = json.substring(start, i).trim();
                if (lit.isEmpty()) throw new IllegalArgumentException("empty value for key " + key.value);
                if (lit.equals("true") || lit.equals("false")) {
                    value = new Value(Value.Kind.BOOLEAN, lit);
                } else if (STRICT_INT.matcher(lit).matches()) {
                    value = new Value(Value.Kind.NUMBER, lit);
                } else {
                    throw new IllegalArgumentException("malformed literal for key " + key.value + ": " + lit);
                }
            }
            if (map.containsKey(key.value)) throw new IllegalArgumentException("duplicate key: " + key.value);
            map.put(key.value, value);
            i = skipWs(json, i);
            if (i >= json.length()) throw new IllegalArgumentException("unterminated object");
            char d = json.charAt(i);
            if (d == ',') {
                i++;
                continue;
            }
            if (d == '}') {
                i = skipWs(json, i + 1);
                if (i != json.length()) throw new IllegalArgumentException("trailing garbage");
                return map;
            }
            throw new IllegalArgumentException("expected ',' or '}'");
        }
    }

    static String requireString(Map<String, Value> map, String key) {
        Value v = map.get(key);
        if (v == null) throw new IllegalArgumentException("missing field: " + key);
        if (v.kind != Value.Kind.STRING) {
            throw new IllegalArgumentException("field " + key + " is not a string: " + v.text);
        }
        return v.text;
    }

    static long requireLong(Map<String, Value> map, String key) {
        Value v = map.get(key);
        if (v == null) throw new IllegalArgumentException("missing field: " + key);
        if (v.kind != Value.Kind.NUMBER) {
            throw new IllegalArgumentException("field " + key + " is not a number: " + v.text);
        }
        try {
            return Long.parseLong(v.text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("field " + key + " is not a number: " + v.text);
        }
    }

    static boolean requireBoolean(Map<String, Value> map, String key) {
        Value v = map.get(key);
        if (v == null) throw new IllegalArgumentException("missing field: " + key);
        if (v.kind != Value.Kind.BOOLEAN) {
            throw new IllegalArgumentException("field " + key + " is not a boolean: " + v.text);
        }
        return "true".equals(v.text);
    }

    /** Escape for the flat serializer; inverse of the string reader below. */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private record Str(String value, int end) {
    }

    /** One parsed value: its JSON type plus its text (strings unescaped). */
    record Value(Kind kind, String text) {
        enum Kind { STRING, NUMBER, BOOLEAN }
    }

    /** Strict JSON integer: optional minus, no leading zeros. */
    private static final java.util.regex.Pattern STRICT_INT =
            java.util.regex.Pattern.compile("-?(0|[1-9][0-9]*)");

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** Read a quoted string starting at the quote; unescape on the way in. */
    private static Str readString(String json, int i) {
        StringBuilder sb = new StringBuilder();
        i++; // opening quote
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') return new Str(sb.toString(), i + 1);
            if (c == '\\') {
                if (i + 1 >= json.length()) throw new IllegalArgumentException("dangling escape");
                char e = json.charAt(i + 1);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 5 >= json.length()) throw new IllegalArgumentException("bad \\u escape");
                        String hex = json.substring(i + 2, i + 6);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ex) {
                            throw new IllegalArgumentException("bad \\u escape: " + hex);
                        }
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("unknown escape: \\" + e);
                }
                i += 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        throw new IllegalArgumentException("unterminated string");
    }
}

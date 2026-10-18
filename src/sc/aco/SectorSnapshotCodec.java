package sc.aco;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Wire format helpers for validated full-`.msav` snapshot transfers, plus the
 * shared sector-key grammar.
 *
 * <p>A snapshot moves over the control plane as three frame kinds:
 * {@code SECTOR_SNAPSHOT_BEGIN} (key|revision|totalLength|sha256),
 * {@code SECTOR_SNAPSHOT_CHUNK} (key|seq|base64), and
 * {@code SECTOR_SNAPSHOT_END} (key|sha256). The receiver ACKs the END frame on the
 * same connection, referencing the END frame's message id, so a sender blocks only
 * on the ACK. Chunk payloads are base64 so every transfer frame stays a UTF-8
 * string and fits {@link AcoFrame#MAX_PAYLOAD}: raw chunks are capped at
 * {@link #CHUNK_RAW_BYTES} (40960 raw bytes -> 54616 base64 chars + header).
 *
 * <p>The receiver never trusts the sender's bytes blindly: it re-computes the
 * SHA-256 over the assembled snapshot, checks the declared length, and only then
 * lets the store validate and commit. Ownership metadata is not a transfer; this
 * codec moves the actual world.
 */
public final class SectorSnapshotCodec {

    /** Hard cap for one snapshot (full `.msav`); larger transfers are refused. */
    public static final int MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024;

    /** Raw bytes per chunk; base64 expansion must stay safely under AcoFrame.MAX_PAYLOAD. */
    public static final int CHUNK_RAW_BYTES = 40960;

    /** Sector keys are `planet:sectorId` style; ':' is part of the real key grammar. */
    public static final String KEY_REGEX = "[A-Za-z0-9_:-]{1,64}";

    private SectorSnapshotCodec() {
    }

    public static boolean validSectorKey(String key) {
        return key != null && key.matches(KEY_REGEX);
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha-256-unavailable", e);
        }
    }

    /** Constant-time comparison for hex digests (avoids leaking match prefixes). */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    /** Thrown by the store/assembly layer when an incoming snapshot is refused. */
    public static class SnapshotRejected extends RuntimeException {
        public SnapshotRejected(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------- begin

    public static String beginPayload(String key, long revision, int totalLength, String sha) {
        return key + "|" + revision + "|" + totalLength + "|" + sha;
    }

    public record Begin(String key, long revision, int totalLength, String sha) {
    }

    /** Returns null for malformed input (never throws) so callers can answer with a clean refusal. */
    public static Begin parseBegin(String payload) {
        String[] f = payload.split("\\|", -1);
        if (f.length != 4) return null;
        if (!validSectorKey(f[0])) return null;
        long revision;
        int total;
        try {
            revision = Long.parseLong(f[1]);
            total = Integer.parseInt(f[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (revision < 0 || total <= 0 || total > MAX_SNAPSHOT_BYTES) return null;
        if (!f[3].matches("[0-9a-f]{64}")) return null;
        return new Begin(f[0], revision, total, f[3]);
    }

    // ------------------------------------------------------------------- chunk

    public static String chunkPayload(String key, int seq, byte[] raw) {
        return key + "|" + seq + "|" + Base64.getEncoder().encodeToString(raw);
    }

    public record Chunk(String key, int seq, byte[] raw) {
    }

    public static Chunk parseChunk(String payload) {
        String[] f = payload.split("\\|", -1);
        if (f.length != 3) return null;
        if (!validSectorKey(f[0])) return null;
        int seq;
        try {
            seq = Integer.parseInt(f[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (seq < 0) return null;
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(f[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (raw.length == 0 || raw.length > CHUNK_RAW_BYTES) return null;
        return new Chunk(f[0], seq, raw);
    }

    // --------------------------------------------------------------------- end

    public static String endPayload(String key, String sha) {
        return key + "|" + sha;
    }

    public record End(String key, String sha) {
    }

    public static End parseEnd(String payload) {
        String[] f = payload.split("\\|", -1);
        if (f.length != 2) return null;
        if (!validSectorKey(f[0])) return null;
        if (!f[1].matches("[0-9a-f]{64}")) return null;
        return new End(f[0], f[1]);
    }

    // --------------------------------------------------------------------- ack

    /** ACK payload: {@code key|OK|revision} or {@code key|FAIL|reason}. */
    public static String ackPayload(String key, boolean ok, String detail) {
        return key + "|" + (ok ? "OK" : "FAIL") + "|" + detail;
    }
}

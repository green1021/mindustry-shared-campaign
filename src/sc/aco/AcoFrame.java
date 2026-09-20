package sc.aco;

import java.nio.ByteBuffer;

public record AcoFrame(byte version, byte type, int msgId, byte[] payload) {
    public static final int HEADER_SIZE = 1 + 1 + 4 + 4;
    public static final int MAX_PAYLOAD = 65536;

    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buf.put(version);
        buf.put(type);
        buf.putInt(msgId);
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }

    public static AcoFrame decode(ByteBuffer buf) {
        if (buf.remaining() < HEADER_SIZE) return null;
        buf.mark();
        byte v = buf.get();
        byte t = buf.get();
        int id = buf.getInt();
        int len = buf.getInt();
        if (len < 0 || len > MAX_PAYLOAD) throw new IllegalArgumentException("invalid-len");
        if (buf.remaining() < len) {
            buf.reset();
            return null;
        }
        byte[] p = new byte[len];
        buf.get(p);
        return new AcoFrame(v, t, id, p);
    }
}

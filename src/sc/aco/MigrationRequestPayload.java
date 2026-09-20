package sc.aco;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Protocol: [CampaignID Len(4)][CampaignID][SessionID Len(4)][SessionID][MigrationID Len(4)][MigrationID][SourceLen(4)][Source][TargetLen(4)][Target] */
public class MigrationRequestPayload {
    public final String campaignId, sessionId, migrationId, source, target;

    public MigrationRequestPayload(String campaignId, String sessionId, String migrationId, String source, String target) {
        this.campaignId = campaignId; this.sessionId = sessionId; this.migrationId = migrationId;
        this.source = source; this.target = target;
    }

    public static MigrationRequestPayload decode(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        return new MigrationRequestPayload(readStr(buf), readStr(buf), readStr(buf), readStr(buf), readStr(buf));
    }

    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(5 * 4 + campaignId.length() + sessionId.length() + migrationId.length() + source.length() + target.length());
        writeStr(buf, campaignId);
        writeStr(buf, sessionId);
        writeStr(buf, migrationId);
        writeStr(buf, source);
        writeStr(buf, target);
        return buf.array();
    }

    private static void writeStr(ByteBuffer b, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        b.putInt(bytes.length);
        b.put(bytes);
    }

    private static String readStr(ByteBuffer b) {
        int len = b.getInt();
        byte[] s = new byte[len];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }
}

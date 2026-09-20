package sc.aco;

import java.util.UUID;
import java.io.OutputStream;

public class AcoSession {
    public String sessionId;
    public AcoSessionState state = AcoSessionState.CONNECTING;
    public byte protocolVersion;
    public String campaignId;
    public long lastActivity = System.currentTimeMillis();
    public OutputStream out;

    public AcoSession(OutputStream out) { this.out = out; }

    public void heartbeat() { this.lastActivity = System.currentTimeMillis(); }
}

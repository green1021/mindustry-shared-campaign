package sc.aco;

import java.nio.file.Path;
import mindustry.type.Sector;

public class SectorOwnership {
    public final String sectorKey;
    public String ownerSessionId;
    public long ownershipVersion = 0;
    public long revisionVersion = 0;
    public String endpoint; // Host:Port
    public long lastHeartbeat;

    public SectorOwnership(String sectorKey) {
        this.sectorKey = sectorKey;
    }
}

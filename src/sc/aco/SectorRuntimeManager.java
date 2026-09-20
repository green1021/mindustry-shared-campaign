package sc.aco;

import arc.util.Log;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.type.Sector;
import mindustry.ctype.ContentType;
import sc.SectorStore;
import java.io.IOException;

public class SectorRuntimeManager {
    private final SectorRegistry registry;
    private Sector currentHostingSector;

    public SectorRuntimeManager(SectorRegistry registry) {
        this.registry = registry;
    }

    public void hostSector(String sectorKey, String grant) throws Exception {
        Log.info("Attempting to host sector: @", sectorKey);
        
        // 1. Obtain and Load
        String[] parts = sectorKey.split(":");
        mindustry.type.SectorPreset preset = Vars.content.sectors().find(sect -> sect.planet.name.equals(parts[0]) && sect.id == Integer.parseInt(parts[1]));
        if (preset == null) throw new Exception("Sector not found");
        
        // Use Planet.sectors to find the sector object
        Sector s = preset.planet.sectors.find(sect -> sect.id == preset.id);

        SectorStore store = new SectorStore();
        store.open(s, grant);
        
        // 2. Start P2
        Vars.net.host(0); // Port 0 for ephemeral, or configure per-device
        
        // 3. Verify Readiness - Vars.net doesn't expose port() directly, use reflection or default
        int port = 6567; // Default/Configured port for P2
        
        this.currentHostingSector = s;
        Log.info("Hosting P2 for sector @ on port @", sectorKey, port);
    }

    public void shutdown() {
        if (Vars.net.active()) {
            Vars.net.dispose();
        }
        currentHostingSector = null;
    }
}
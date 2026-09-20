package sc.aco;

import arc.util.Log;
import mindustry.Vars;
import mindustry.game.Gamemode;
import mindustry.type.SectorPreset;
import sc.SectorStore;

public class SectorRuntimeAdapter {

    public boolean initializeSector(String sectorKey, String revision) throws Exception {
        SectorPreset preset = Vars.content.sectors().find(s -> s.toString().equals(sectorKey));
        if (preset == null) {
            Log.err("Sector not found: @", sectorKey);
            return false;
        }

        // Initialize world for the sector
        Vars.world.loadSector(preset.sector);
        
        // Load authoritative state
        SectorStore store = new SectorStore();
        store.open(preset.sector, "test-grant"); // Valid grant required
        
        Log.info("World initialized for sector: @", sectorKey);
        return true;
    }

    public boolean startP2(int port) {
        try {
            Vars.net.host(port);
            return Vars.net.active();
        } catch (Exception e) {
            Log.err("Failed to host P2", e);
            return false;
        }
    }

    public void stopP2() {
        Vars.net.dispose();
    }
}
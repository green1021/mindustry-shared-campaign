package tests;

import mindustry.Vars;
import mindustry.type.SectorPreset;
import mindustry.content.Planets;
import arc.util.Log;
import java.io.File;

public class RuntimeContentTest {
    public static void main(String[] args) {
        try {
            // Manually set MINDUSTRY_HOME for testing purposes if necessary
            // or rely on environment if already loaded
            Log.info("Testing Mindustry Runtime Content...");
            
            // Check if Vars.content is loaded
            if (Vars.content == null) throw new RuntimeException("Vars.content is null");
            
            // Verify content
            SectorPreset groundZero = Vars.content.sectors().find(s -> s.name.equals("groundZero"));
            if (groundZero == null) throw new RuntimeException("groundZero SectorPreset not found");
            if (groundZero.planet != Planets.serpulo) throw new RuntimeException("groundZero not on Serpulo");
            
            Log.info("Vanilla content verified: groundZero found on Serpulo");
            
            // World Init Check
            Vars.world.loadSector(groundZero.sector);
            Log.info("World initialized successfully");
            
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}

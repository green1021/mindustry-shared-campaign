package tests;

import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.World;
import mindustry.type.SectorPreset;
import arc.util.Log;
import arc.files.Fi;
import mindustry.content.Planets;

public class RealRuntimeTest {
    public static void main(String[] args) {
        try {
            Log.info("=== REAL RUNTIME TEST START ===");
            
            // Force headless content loading
            System.setProperty("arc.headless", "true");
            System.setProperty("arc.backend", "headless");
            
            // Minimal required settings
            Vars.customMapDirectory = new Fi("maps");
            Vars.modDirectory = new Fi("mods");
            
            // Explicitly initialize content
            ContentLoader loader = new ContentLoader();
            Vars.content = loader;
            loader.createBaseContent();
            
            if (Vars.content == null) {
                throw new RuntimeException("Vars.content is null");
            }
            
            Log.info("Vars.content loaded: " + Vars.content.sectors().size + " sectors");
            
            // Find groundZero
            SectorPreset groundZero = Vars.content.sectors().find(s -> s.name.equals("groundZero"));
            if (groundZero == null) {
                throw new RuntimeException("groundZero SectorPreset not found");
            }
            
            Log.info("Found groundZero SectorPreset: " + groundZero.name + " on " + groundZero.planet.name);
            
            // Initialize World
            Vars.world = new World();
            Log.info("World instance created");
            
            // Test that we can initialize the sector world
            Vars.world.loadSector(groundZero.sector);
            Log.info("World initialized successfully for sector: " + groundZero.name);
            
            // Test P2 startup
            int testPort = 6567;
            Vars.net.host(testPort);
            Log.info("P2 hosted on port: " + testPort);
            
            if (!Vars.net.active()) {
                throw new RuntimeException("P2 not active after host()");
            }
            
            Log.info("P2 active: true");
            
            // Cleanup
            Vars.net.dispose();
            Log.info("P2 disposed");
            
            Log.info("=== ALL REAL RUNTIME TESTS PASSED ===");
            System.exit(0);
            
        } catch (Exception e) {
            Log.err("REAL RUNTIME TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
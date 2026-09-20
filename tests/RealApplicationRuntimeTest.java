package tests;

import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.type.SectorPreset;
import arc.util.Log;

public class RealApplicationRuntimeTest {
    public static void main(String[] args) {
        // This test must be run by the real application thread or with proper hooks
        // We simulate the post-init hook here
        try {
            Log.info("=== REAL APPLICATION RUNTIME TEST START ===");
            
            if (Vars.content == null) {
                throw new RuntimeException("Vars.content is null");
            }
            
            Log.info("Vars.content loaded: " + Vars.content.sectors().size + " sectors");
            
            // Find groundZero
            SectorPreset groundZero = Vars.content.sectors().find(s -> s.name.equals("groundZero"));
            if (groundZero == null) {
                throw new RuntimeException("groundZero not found");
            }
            
            Log.info("Found groundZero: " + groundZero.name);
            
            // Test World init - now safe because the application is fully booted
            Vars.world.loadSector(groundZero.sector);
            Log.info("World initialized successfully");
            
            // Test P2 startup
            int testPort = 6567;
            Vars.net.host(testPort);
            
            if (!Vars.net.active()) {
                throw new RuntimeException("P2 not active");
            }
            
            Log.info("P2 active: true, Port: " + testPort);
            
            // Cleanup
            Vars.net.dispose();
            Log.info("P2 disposed");
            
            Log.info("=== ALL REAL RUNTIME TESTS PASSED ===");
            System.exit(0);
            
        } catch (Exception e) {
            Log.err("TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
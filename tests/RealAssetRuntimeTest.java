package tests;

import mindustry.Vars;
import mindustry.server.ServerLauncher;
import mindustry.type.SectorPreset;
import arc.util.Log;
import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;

public class RealAssetRuntimeTest {
    public static void main(String[] args) {
        try {
            Log.info("=== REAL ASSET RUNTIME TEST START ===");
            
            // Set the data directory to our extracted assets
            System.setProperty("arc.headless", "true");
            System.setProperty("arc.backend", "headless");
            System.setProperty("user.dir", "/root/mindustry-shared-campaign/unpacked-desktop");
            
            // ServerLauncher handles full initialization
            ServerLauncher launcher = new ServerLauncher();
            
            // Start the headless application lifecycle
            HeadlessApplication app = new HeadlessApplication(launcher);
            
            // Wait for init to complete - the app runs in a separate thread
            // We need to wait until the application loop has initialized everything
            Thread.sleep(8000);
            
            if (Vars.content == null) {
                throw new RuntimeException("Vars.content is null");
            }
            
            Log.info("Vars.content loaded: " + Vars.content.sectors().size + " sectors");
            
            // Find groundZero
            SectorPreset groundZero = Vars.content.sectors().find(s -> s.name.equals("groundZero"));
            if (groundZero == null) {
                // List available sectors
                for (SectorPreset s : Vars.content.sectors()) {
                    Log.info("Available sector: " + s.name + " on " + s.planet.name);
                }
                throw new RuntimeException("groundZero not found");
            }
            
            Log.info("Found groundZero: " + groundZero.name + " on " + groundZero.planet.name);
            
            // Test World init
            Vars.world.loadSector(groundZero.sector);
            Log.info("World initialized successfully");
            
            // Test P2 startup - we need net to be initialized
            if (Vars.net == null) {
                throw new RuntimeException("Vars.net is null - network not initialized");
            }
            
            int testPort = 6567;
            Vars.net.host(testPort);
            
            if (!Vars.net.active()) {
                throw new RuntimeException("P2 not active");
            }
            
            Log.info("P2 active: true, Port: " + testPort);
            
            // Dispose
            Vars.net.dispose();
            app.exit();
            
            Log.info("=== ALL REAL RUNTIME TESTS PASSED ===");
            System.exit(0);
            
        } catch (Exception e) {
            Log.err("TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
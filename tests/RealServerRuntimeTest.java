package tests;

import mindustry.Vars;
import mindustry.server.ServerLauncher;
import mindustry.type.SectorPreset;
import arc.util.Log;
import arc.Core;
import arc.Application;
import arc.ApplicationListener;

public class RealServerRuntimeTest {
    public static void main(String[] args) {
        try {
            Log.info("=== REAL SERVER RUNTIME TEST START ===");
            
            // Set headless environment variables
            System.setProperty("arc.headless", "true");
            System.setProperty("arc.backend", "headless");
            
            // The ServerLauncher handles the full initialization of Vars
            // It implements ApplicationListener, and init() triggers content loading
            ServerLauncher launcher = new ServerLauncher();
            
            // Use arc.Core.app to simulate the lifecycle
            // We need to inject a headless backend
            arc.backend.headless.HeadlessApplication app = new arc.backend.headless.HeadlessApplication(launcher);
            
            // Wait for init
            Thread.sleep(2000);
            
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
            
            // Test World init
            Vars.world.loadSector(groundZero.sector);
            Log.info("World initialized successfully");
            
            // Test P2 startup
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
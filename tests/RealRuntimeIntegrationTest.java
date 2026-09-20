package tests;

import mindustry.Vars;
import mindustry.server.ServerLauncher;
import mindustry.type.SectorPreset;
import arc.util.Log;
import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import arc.ApplicationListener;

public class RealRuntimeIntegrationTest implements ApplicationListener {
    private boolean testPassed = false;
    private boolean testStarted = false;
    private Thread mainThread;
    private HeadlessApplication app;

    public static void main(String[] args) {
        RealRuntimeIntegrationTest test = new RealRuntimeIntegrationTest();
        test.mainThread = Thread.currentThread();
        test.run();
    }

    public void run() {
        try {
            Log.info("=== REAL RUNTIME INTEGRATION TEST START ===");
            
            // Set the data directory to our extracted assets
            System.setProperty("arc.headless", "true");
            System.setProperty("arc.backend", "headless");
            System.setProperty("user.dir", "/root/mindustry-shared-campaign/unpacked-desktop");
            
            // Create our test listener
            ServerLauncher launcher = new ServerLauncher();
            
            // Start the headless application lifecycle with our test listener
            // We need to chain our test after the launcher init
            app = new HeadlessApplication(launcher);
            
            // Wait for initialization - the ApplicationListener init() runs on the app thread
            // We need to wait for the app thread to finish initializing
            synchronized (this) {
                while (!testPassed && !testStarted) {
                    this.wait(10000);
                }
            }
            
            if (!testPassed) {
                throw new RuntimeException("Test timed out or failed");
            }
            
            app.exit();
            Log.info("=== ALL REAL RUNTIME TESTS PASSED ===");
            System.exit(0);
            
        } catch (Exception e) {
            Log.err("TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    @Override
    public void init() {
        // This runs on the application thread AFTER ServerLauncher.init()
        new Thread(() -> {
            try {
                testStarted = true;
                runTest();
            } catch (Exception e) {
                Log.err("Test exception: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private void runTest() {
        try {
            // At this point, ServerLauncher.init() has completed
            // Vars.content, Vars.net, etc. should be initialized
            
            if (Vars.content == null) {
                throw new RuntimeException("Vars.content is null");
            }
            
            Log.info("Vars.content loaded: " + Vars.content.sectors().size + " sectors");
            
            // Find groundZero
            SectorPreset groundZero = Vars.content.sectors().find(s -> s.name.equals("groundZero"));
            if (groundZero == null) {
                for (SectorPreset s : Vars.content.sectors()) {
                    Log.info("Available sector: " + s.name + " on " + s.planet.name);
                }
                throw new RuntimeException("groundZero not found");
            }
            
            Log.info("Found groundZero: " + groundZero.name + " on " + groundZero.planet.name);
            
            // Test World init
            Vars.world.loadSector(groundZero.sector);
            Log.info("World initialized successfully");
            
            // Test P2 startup
            if (Vars.net == null) {
                throw new RuntimeException("Vars.net is null - network not initialized");
            }
            
            int testPort = 6567;
            Vars.net.host(testPort);
            
            if (!Vars.net.active()) {
                throw new RuntimeException("P2 not active");
            }
            
            Log.info("P2 active: true, Port: " + testPort);
            
            // Test shutdown/restart
            Vars.net.dispose();
            Log.info("P2 disposed");
            
            Vars.net.host(testPort);
            Vars.net.dispose();
            Log.info("P2 restart test passed");
            
            testPassed = true;
            synchronized (this) {
                this.notifyAll();
            }
            
        } catch (Exception e) {
            Log.err("Test exception: " + e.getMessage());
            e.printStackTrace();
            synchronized (this) {
                this.notifyAll();
            }
        }
    }

    @Override
    public void resize(int width, int height) {}
    @Override
    public void update() {}
    @Override
    public void dispose() {}
}
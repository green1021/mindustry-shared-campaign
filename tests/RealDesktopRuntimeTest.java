package tests;

import mindustry.Vars;
import mindustry.desktop.DesktopLauncher;
import mindustry.type.SectorPreset;
import arc.util.Log;
import arc.ApplicationListener;
import arc.Events;
import mindustry.game.EventType;

public class RealDesktopRuntimeTest implements ApplicationListener {
    private boolean initialized = false;

    public static void main(String[] args) {
        // Set environment
        System.setProperty("user.dir", "/root/mindustry-shared-campaign/unpacked-desktop");
        
        // Load the DesktopLauncher
        String[] emptyArgs = {"--headless", "--autoexit"};
        DesktopLauncher.main(emptyArgs);
    }

    @Override
    public void init() {
        if (initialized) return;
        initialized = true;
        
        new Thread(() -> {
            try {
                Log.info("=== REAL DESKTOP RUNTIME INTEGRATION TEST START ===");
                
                // Wait for content load event
                Events.on(EventType.ContentInitEvent.class, e -> {
                    Log.info("Content initialized, sector count: " + Vars.content.sectors().size);
                    
                    SectorPreset groundZero = Vars.content.sectors().find(s -> s.name.equals("groundZero"));
                    if (groundZero == null) {
                        Log.err("groundZero not found!");
                        System.exit(1);
                    }
                    
                    Log.info("Found groundZero: " + groundZero.name);
                    
                    // World load
                    Vars.world.loadSector(groundZero.sector);
                    Log.info("World initialized successfully");
                    
                    // Host P2
                    int testPort = 6567;
                    try {
                        Vars.net.host(testPort);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        System.exit(1);
                    }
                    
                    if (Vars.net.active()) {
                        Log.info("P2 active on port: " + testPort);
                        Vars.net.dispose();
                        Log.info("P2 disposed, test passed");
                        System.exit(0);
                    } else {
                        Log.err("P2 failed to host");
                        System.exit(1);
                    }
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                System.exit(1);
            }
        }).start();
    }
}

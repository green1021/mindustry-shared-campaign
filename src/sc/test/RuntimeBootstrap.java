package sc.test;

import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.World;
import mindustry.type.SectorPreset;
import arc.ApplicationListener;
import arc.backend.headless.HeadlessApplication;

/**
 * Step 1: Real Runtime Content & Lifecycle Bootstrap using server-release.jar
 * Follows ServerLauncher.init() sequence
 */
public class RuntimeBootstrap {
    public static void main(String[] args) {
        // Initialize headless context using server-release
        new HeadlessApplication(new ApplicationListener() {
            @Override
            public void init() {
                try {
                    // Follow ServerLauncher.init() sequence
                    Vars.headless = true;
                    // Skip locale loading (no bundles in headless)
                    Vars.loadLocales = false;
                    
                    // Initialize net before content (as ServerLauncher does)
                    Vars.net = new mindustry.net.Net(new mindustry.net.ArcNetProvider());
                    
                    Vars.loadSettings();
                    Vars.init();  // This initializes Vars.content
                    Vars.content.createBaseContent();
                    Vars.content.init();
                    
                    // Initialize core subsystems needed for sector loading
                    Vars.logic = new mindustry.core.Logic();
                    Vars.spawner = new mindustry.ai.WaveSpawner();
                    Vars.collisions = new mindustry.entities.EntityCollisions();
                    
                    // 2. Resolve groundZero
                    SectorPreset groundZero = Vars.content.sectors().find(s -> s.name.equals("groundZero"));
                    if (groundZero == null) throw new RuntimeException("Could not find groundZero");
                    
                    // 3. Initialize World
                    Vars.world = new World();
                    // SectorPreset has a 'sector' field after initialization
                    if (groundZero.sector == null) {
                        throw new RuntimeException("groundZero sector not initialized");
                    }
                    Vars.world.loadSector(groundZero.sector);
                    
                    System.out.println("VERIFIED: groundZero resolved and loaded.");
                    System.exit(0);
                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }
        });
    }
}
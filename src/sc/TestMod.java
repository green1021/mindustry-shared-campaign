package sc;

import mindustry.mod.Mod;
import mindustry.Vars;
import mindustry.type.SectorPreset;
import arc.util.Log;
import arc.Events;
import mindustry.game.EventType;

public class TestMod extends Mod {
    public TestMod() {
        Events.on(EventType.ServerLoadEvent.class, e -> {
            try {
                Log.info("=== TEST MOD: RUNTIME INTEGRATION START ===");
                
                // Find groundZero
                SectorPreset groundZero = Vars.content.sectors().find(s -> s.name.equals("groundZero"));
                if (groundZero == null) throw new RuntimeException("groundZero not found");
                
                Log.info("Found groundZero: " + groundZero.name);
                
                // Init world
                Vars.world.loadSector(groundZero.sector);
                Log.info("World initialized successfully");
                
                // P2 test
                int testPort = 6567;
                Vars.net.host(testPort);
                if (Vars.net.active()) {
                    Log.info("P2 active on port: " + testPort);
                    Vars.net.dispose();
                    Log.info("P2 disposed, test passed");
                } else {
                    Log.err("P2 failed");
                }
                
                Log.info("=== TEST MOD: SUCCESS ===");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }
}
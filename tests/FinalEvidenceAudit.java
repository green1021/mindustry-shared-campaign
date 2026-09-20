package tests;

import mindustry.Vars;
import mindustry.server.ServerLauncher;
import mindustry.type.SectorPreset;
import mindustry.net.NetworkIO;
import mindustry.net.Net;
import arc.util.Log;
import arc.ApplicationListener;

public class FinalEvidenceAudit implements ApplicationListener {
    public static void main(String[] args) {
        System.setProperty("user.dir", "/root/mindustry-shared-campaign/unpacked-desktop");
        ServerLauncher launcher = new ServerLauncher();
        new arc.backend.headless.HeadlessApplication(launcher);
    }

    @Override
    public void init() {
        try {
            Log.info("=== FINAL EVIDENCE AUDIT START ===");
            
            // 1. Asset/Content verification
            Log.info("Vars.content sectors count: " + Vars.content.sectors().size);
            SectorPreset gZero = Vars.content.sectors().find(s -> s.name.equals("groundZero"));
            if (gZero != null) {
                Log.info("Evidence: groundZero SectorPreset resolved: " + gZero.name + " on " + gZero.planet.name);
            } else {
                Log.err("Evidence: groundZero FAILED to resolve");
                System.exit(1);
            }

            // 2. Real World initialization
            Vars.world.loadSector(gZero.sector);
            Log.info("Evidence: Vars.world.loadSector(groundZero) SUCCESS");
            Log.info("Evidence: Vars.state=" + Vars.state + ", Vars.state.map=" + (Vars.state != null ? Vars.state.map : "null"));

            // 3. Real P2 socket lifecycle
            int testPort = 6567;
            Vars.net.host(testPort);
            Log.info("Evidence: P2 listening on port " + testPort + ": " + Vars.net.active());
            Vars.net.dispose();
            Log.info("Evidence: P2 disposed (port released)");
            Vars.net.host(testPort);
            Log.info("Evidence: P2 rebound on " + testPort + ": " + Vars.net.active());
            Vars.net.dispose();

            Log.info("=== FINAL EVIDENCE AUDIT COMPLETE ===");
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}

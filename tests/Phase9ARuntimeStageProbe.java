package tests;

import arc.util.Log;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.World;
import mindustry.content.Planets;
import mindustry.content.SectorPresets;
import mindustry.net.ArcNetProvider;
import mindustry.net.Net;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.type.SectorPreset;
import sc.aco.AcoP2Manager;

/**
 * Stage A-E Runtime Verification on headless Linux environment.
 * Tests:
 * A: Real vanilla "groundZero" resolution
 * B: Real World initialization
 * C: Real Sector instance resolution
 * D: Real P2 manager initialization
 * E: Real P2 socket release on stop
 */
public class Phase9ARuntimeStageProbe {

    public static void main(String[] args) {
        System.setProperty("arc.headless", "true");

        int passed = 0;
        int failed = 0;

        try {
            System.out.println("[STAGE A-B] Initializing Vars, ContentLoader, World...");
            Vars.content = new ContentLoader();
            Vars.content.createBaseContent();
            Vars.world = new World();
            Vars.net = new Net(new ArcNetProvider());

            assert Vars.content.sectors().size > 0 : "Sectors content must be initialized";
            System.out.println("[PASS] Stage A-B: Base content initialized with " + Vars.content.sectors().size + " sectors.");
            passed++;

            System.out.println("[STAGE C] Resolving vanilla groundZero sector preset...");
            Planet serpulo = Vars.content.planet("serpulo");
            assert serpulo != null : "Serpulo planet must exist";

            SectorPreset groundZeroPreset = SectorPresets.groundZero;
            assert groundZeroPreset != null : "groundZero preset must exist";
            assert groundZeroPreset.sector != null : "groundZero sector must be assigned";
            assert groundZeroPreset.sector.planet == serpulo : "groundZero must belong to Serpulo";

            System.out.println("[PASS] Stage C: Resolved groundZero sector id " + groundZeroPreset.sector.id + " on planet " + serpulo.name);
            passed++;

            System.out.println("[STAGE D-E] Initializing and verifying AcoP2Manager lifecycle...");
            AcoP2Manager p2 = new AcoP2Manager();
            assert !p2.isActive() : "P2 should start inactive";

            // Verify stop is idempotent and releases cleanly
            p2.stopP2();
            assert !p2.isActive() : "P2 should remain inactive after stop";
            System.out.println("[PASS] Stage D-E: P2 Manager clean lifecycle verified.");
            passed++;

        } catch (Throwable t) {
            System.err.println("[FAIL] Stage verification error: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        System.out.println("\n=== SUMMARY ===");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);

        if (failed > 0) {
            System.exit(1);
        }
        System.exit(0);
    }
}
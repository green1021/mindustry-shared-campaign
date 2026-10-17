package tests;

import arc.ApplicationListener;
import arc.backend.headless.HeadlessApplication;
import mindustry.Vars;
import mindustry.core.NetServer;
import mindustry.core.World;
import mindustry.content.SectorPresets;
import mindustry.type.Sector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real sector world loading inside a headless Mindustry runtime — acceptance
 * criterion C of PHASE9A_PRE_AUDIT.md.
 *
 * <p>Proves that {@code Vars.world.loadSector(preset.sector)} builds a genuine
 * playable world (real tile grid, campaign state, sector identity) without a
 * display, so the "needs a live game loop" reading of row C was wrong.
 *
 * <p>This stops short of {@code SectorStore.open}: authoritative entry needs a
 * disposable M4 workspace (marker + campaign id + lease + eligible save), which is
 * a separate opt-in concern and not part of "does the world load".
 */
public class SectorLoadTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("SectorLoadTest");
        System.setProperty("arc.headless", "true");

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        new HeadlessApplication(new ApplicationListener() {
            @Override
            public void init() {
                try {
                    bootRuntime();

                    run("LoadSectorBuildsRealWorld", SectorLoadTest::testLoadBuildsWorld);
                    run("LoadSectorSetsCampaignState", SectorLoadTest::testCampaignState);

                    System.out.println("\n=== SUMMARY ===");
                    System.out.println("Passed: " + passed);
                    System.out.println("Failed: " + failed);
                    done.countDown();
                    System.exit(failed > 0 ? 1 : 0);
                } catch (Throwable t) {
                    error.set(t);
                    done.countDown();
                    System.exit(1);
                }
            }

            @Override
            public void update() {
                try {
                    Thread.sleep(16);
                } catch (InterruptedException ignored) {
                }
            }
        });

        done.await(80, TimeUnit.SECONDS);
        if (error.get() != null) {
            error.get().printStackTrace();
            System.exit(1);
        }
    }

    private static void bootRuntime() {
        Vars.platform = new mindustry.core.Platform() {
            @Override
            public mindustry.net.Net.NetProvider getNet() {
                return new mindustry.net.ArcNetProvider();
            }
        };
        Vars.net = new mindustry.net.Net(Vars.platform.getNet());
        Vars.loadLocales = false;
        Vars.headless = true;
        Vars.loadSettings();
        Vars.init();
        Vars.content.createBaseContent();
        Vars.content.init();
        Vars.logic = new mindustry.core.Logic();
        Vars.spawner = new mindustry.ai.WaveSpawner();
        Vars.collisions = new mindustry.entities.EntityCollisions();
        Vars.world = new World();

        Vars.netServer = new NetServer();
        Vars.netServer.init();
        Vars.netServer.openServer();
        arc.Core.app.addListener(Vars.netServer);
        arc.Events.fire(new mindustry.game.EventType.ServerLoadEvent());
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void run(String name, Check check) {
        System.out.println("[RUN] " + name);
        try {
            check.run();
            System.out.println("[PASS] " + name);
            passed++;
        } catch (AssertionError e) {
            System.out.println("[FAIL] " + name + ": " + e.getMessage());
            failed++;
        } catch (Exception e) {
            System.out.println("[ERROR] " + name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            failed++;
        }
    }

    private static void testLoadBuildsWorld() throws Exception {
        Sector sector = SectorPresets.groundZero.sector;
        assert sector != null : "groundZero preset should resolve to a sector";

        Vars.world.loadSector(sector);

        int w = Vars.world.width(), h = Vars.world.height();
        System.out.println("  loaded sector=" + sector + " size=" + w + "x" + h);
        assert w >= 64 && h >= 64 : "loadSector should build a real map, got " + w + "x" + h;
        assert Vars.world.tiles != null : "loadSector should allocate a tile grid";
        assert Vars.world.tiles.width == w && Vars.world.tiles.height == h
            : "tile grid should match the world size";
        assert Vars.world.tiles.get(0, 0) != null : "loaded world should expose tiles";

        // A generated world has floors; a stub default world would be all-null.
        long floored = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (Vars.world.tiles.get(x, y).floor() != null) floored++;
            }
        }
        System.out.println("  tiles with a floor: " + floored + "/" + ((long) w * h));
        assert floored == (long) w * h : "a generated sector should give every tile a floor";
    }

    private static void testCampaignState() throws Exception {
        Sector sector = SectorPresets.groundZero.sector;
        Vars.world.loadSector(sector);

        assert Vars.state.isCampaign() : "loading a sector should put the game in campaign state";
        assert Vars.state.getSector() == sector : "the loaded sector should be the active sector";
        System.out.println("  isCampaign=" + Vars.state.isCampaign() + " active=" + Vars.state.getSector().id);
    }
}

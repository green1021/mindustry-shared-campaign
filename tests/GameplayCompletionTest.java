package tests;

import arc.ApplicationListener;
import arc.Events;
import arc.backend.headless.HeadlessApplication;
import mindustry.Vars;
import mindustry.content.SectorPresets;
import mindustry.game.EventType;
import mindustry.type.Sector;
import sc.aco.AcoController;
import sc.aco.AcoSocketClient;
import sc.aco.GameplayAdapter;
import sc.aco.GlobalCampaignState;
import sc.aco.SectorRuntime;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Row H of PHASE9A_PRE_AUDIT: a gameplay-originated sector completion.
 *
 * <p>Exercises the full intended path with no test doubles in the middle: a real
 * {@link EventType.SectorCaptureEvent} is fired inside a headless Mindustry runtime,
 * the production {@link GameplayAdapter} turns it into a request on a real
 * {@link AcoSocketClient} (a live socket to a live authority), and the authority's
 * {@code GLOBAL_STATE_SYNC} broadcast folds back into the client's local campaign
 * view.
 *
 * <p>This closes two production gaps at once: {@code AcoClient.sendFrame} had no
 * transport (the "integration wrapper" it was waiting for is {@code AcoSocketClient}),
 * and nothing in {@code src/} ever constructed a {@code GameplayAdapter}, so no
 * gameplay event had ever reached the authority.
 *
 * <p>One authority/client/adapter pair is built for the whole class: {@code Events.on}
 * has no unsubscribe, so a per-test registration would stack listeners that fire
 * into already-disposed clients. The tests therefore share the pair and assert on
 * successive revisions rather than on isolated worlds.
 */
public class GameplayCompletionTest {

    private static int passed = 0;
    private static int failed = 0;
    private static AcoController host;
    private static AcoSocketClient client;
    private static GameplayAdapter adapter;
    private static Path hostDir;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("GameplayCompletionTest");
        System.setProperty("arc.headless", "true");

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        new HeadlessApplication(new ApplicationListener() {
            @Override
            public void init() {
                try {
                    bootRuntime();
                    startPair();

                    run("CaptureEventCompletesThroughAdapter", GameplayCompletionTest::testCaptureCompletes);
                    run("RepeatedCapturesUseDistinctOperations", GameplayCompletionTest::testDistinctOperations);

                    stopPair();
                    System.out.println("\n=== SUMMARY ===");
                    System.out.println("Passed: " + passed);
                    System.out.println("Failed: " + failed);
                    done.countDown();
                    System.exit(failed > 0 ? 1 : 0);
                } catch (Throwable t) {
                    error.set(t);
                    try {
                        stopPair();
                    } catch (Exception ignored) {
                    }
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
        Vars.world = new mindustry.core.World();
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

    private static final class StubRuntime implements SectorRuntime {
        final List<String> initialized = new ArrayList<>();

        @Override
        public boolean initializeSector(String sectorKey, String revision) {
            initialized.add(sectorKey + "@" + revision);
            return true;
        }

        @Override
        public boolean startP2(int port) {
            return true;
        }

        @Override
        public void stopP2() {
        }
    }

    /** Start a fresh authority, connect a real socket client, wire the adapter. */
    private static void startPair() throws Exception {
        int syncPort = freePort();
        hostDir = Files.createTempDirectory("aco-gameplay-host-");

        host = new AcoController(hostDir.toString());
        host.setSectorRuntime(new StubRuntime());
        if (!host.hostCampaign(syncPort, syncPort + 1)) {
            throw new IllegalStateException("hostCampaign returned false");
        }
        Thread.sleep(200);

        client = new AcoSocketClient();
        client.connect("127.0.0.1", syncPort);
        adapter = new GameplayAdapter(client);
        adapter.init();
    }

    private static void stopPair() throws Exception {
        if (client != null) client.disconnect();
        if (host != null) host.dispose();
        client = null;
        host = null;
        adapter = null;
        if (hostDir != null) {
            deleteRecursively(hostDir);
            hostDir = null;
        }
    }

    private static void testCaptureCompletes() throws Exception {
        Sector sector = SectorPresets.groundZero.sector;
        assert sector != null : "groundZero preset should resolve to a sector";
        assert sector.preset.name.equals(GlobalCampaignState.STARTING_SECTOR)
            : "expected the starting sector, got " + sector.preset.name;

        // The exact production path: a capture event, translated by the adapter,
        // written to the socket by the client transport.
        Events.fire(new EventType.SectorCaptureEvent(sector, false));

        GlobalCampaignState view = awaitCompleted(sector.preset.name, 5000);
        assert view != null
            : "the authority's broadcast should fold groundZero completion into the local view";
        System.out.println("  local view revision=" + view.globalRevision
            + " completed=" + view.completedSectors);
        assert view.globalRevision >= 1 : "an accepted completion must advance the revision";
    }

    /**
     * Two distinct captures must both be applied. With the bare completion call the
     * authority sees a single shared {@code "default-op"} id and answers the second
     * from its seen-set without advancing anything — so the revision advancing by
     * exactly two is the signal that the adapter minted a unique operation id per
     * attempt.
     */
    private static void testDistinctOperations() throws Exception {
        long before = client.getLocalCache().globalRevision;
        Sector sector = SectorPresets.groundZero.sector;
        Events.fire(new EventType.SectorCaptureEvent(sector, false));
        Events.fire(new EventType.SectorCaptureEvent(sector, false));

        GlobalCampaignState view = awaitRevision(before + 2, 5000);
        assert view != null
            : "two distinct captures should both apply, expected revision " + (before + 2)
              + " but saw " + client.getLocalCache().globalRevision;
        System.out.println("  local view revision " + before + " -> " + view.globalRevision);
    }

    /** Poll the client's folded view until it records the sector as completed. */
    private static GlobalCampaignState awaitCompleted(String sector, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            GlobalCampaignState view = client.getLocalCache();
            if (view.completedSectors.contains(sector)) return view;
            Thread.sleep(25);
        }
        return null;
    }

    private static GlobalCampaignState awaitRevision(long min, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            GlobalCampaignState view = client.getLocalCache();
            if (view.globalRevision >= min) return view;
            Thread.sleep(25);
        }
        return null;
    }

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}

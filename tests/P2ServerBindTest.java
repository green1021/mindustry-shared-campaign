package tests;

import arc.ApplicationListener;
import arc.backend.headless.HeadlessApplication;
import mindustry.Vars;
import mindustry.core.NetServer;
import mindustry.core.World;
import sc.aco.SectorRuntimeAdapter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real P2 host bind inside a headless Mindustry runtime.
 *
 * <p>Drives the <em>production</em> {@link SectorRuntimeAdapter} (not a stub) after
 * booting a headless {@code HeadlessApplication} in the ServerLauncher init order.
 * It proves that {@code Vars.net.host(port)} binds a listener and that the
 * adapter's stop path releases it — the P2 socket half of "real P2 hosting".
 *
 * <p>This does <b>not</b> prove sector entry: {@code initializeSector} needs a full
 * disposable M4 {@code SectorStore} workspace (marker, campaign id, lease, eligible
 * live campaign save) and is exercised separately. A real desktop client joining a
 * live P2 server remains ENVIRONMENT-BLOCKED on a headless host.
 */
public class P2ServerBindTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("P2ServerBindTest");
        System.setProperty("arc.headless", "true");

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        new HeadlessApplication(new ApplicationListener() {
            @Override
            public void init() {
                try {
                    bootRuntime();

                    run("StartP2BindsRealListener", P2ServerBindTest::testStartBindsListener);
                    run("StopP2ReleasesListener", P2ServerBindTest::testStopReleasesListener);

                    System.out.println("\n=== SUMMARY ===");
                    System.out.println("Passed: " + passed);
                    System.out.println("Failed: " + failed);
                    if (failed > 0) {
                        done.countDown();
                        System.exit(1);
                    }
                    done.countDown();
                    System.exit(0);
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

    /** ServerLauncher init order, as reproduced in the headless P2 host scaffold. */
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

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void testStartBindsListener() throws Exception {
        int port = freePort();
        SectorRuntimeAdapter adapter = new SectorRuntimeAdapter();
        try {
            assert adapter.startP2(port) : "startP2 should report success";
            assert Vars.net.active() : "Vars.net should report active after host";
            assert Vars.net.server() : "Vars.net should report a server after host";

            Thread.sleep(300);
            try (Socket c = new Socket()) {
                c.connect(new InetSocketAddress("127.0.0.1", port), 1500);
                assert c.isConnected() : "a TCP client should reach the P2 listener";
            }
        } finally {
            adapter.stopP2();
        }
    }

    private static void testStopReleasesListener() throws Exception {
        int port = freePort();
        SectorRuntimeAdapter adapter = new SectorRuntimeAdapter();
        assert adapter.startP2(port) : "startP2 should report success";
        Thread.sleep(300);
        adapter.stopP2();
        Thread.sleep(300);

        assert !Vars.net.server() : "Vars.net should report no server after stopP2";

        // A fresh bind on the same port must succeed: the listener was released.
        try (ServerSocket rebind = new ServerSocket()) {
            rebind.setReuseAddress(true);
            rebind.bind(new InetSocketAddress("127.0.0.1", port));
            assert rebind.isBound() : "the port should be free to rebind after stopP2";
        }
    }
}

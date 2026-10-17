package tests;

import arc.util.Log;
import sc.CampaignActions;
import sc.SharedCampaignUI;
import sc.aco.AcoController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless verification that the ACO control plane is wired into the mod's
 * action seams and that a real localhost client <-> authority handshake works
 * through the wired path.
 *
 * <p>No display, no Mindustry instance: this exercises exactly the code the UI
 * buttons call, plus a real TCP PING/PONG round trip.
 */
public class AcoWiringTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("AcoWiringTest");
        System.setProperty("arc.headless", "true");

        run("UiSeamDispatchesHost", AcoWiringTest::testUiSeamDispatchesHost);
        run("UiSeamDispatchesConnect", AcoWiringTest::testUiSeamDispatchesConnect);
        run("PortAndAddressParsing", AcoWiringTest::testPortAndAddressParsing);
        run("ControllerLifecycle", AcoWiringTest::testControllerLifecycle);
        run("LocalhostHandshake", AcoWiringTest::testLocalhostHandshake);

        System.out.println("\n=== SUMMARY ===");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
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

    private static final class RecordingActions implements CampaignActions {
        final List<String> calls = new ArrayList<>();

        @Override
        public void hostCampaignAction(int syncPort, int sectorPort) {
            calls.add("host:" + syncPort + ":" + sectorPort);
        }

        @Override
        public void connectAction(String host, int port) {
            calls.add("connect:" + host + ":" + port);
        }
    }

    private static void testUiSeamDispatchesHost() {
        SharedCampaignUI ui = new SharedCampaignUI();
        RecordingActions rec = new RecordingActions();
        ui.setActions(rec);

        assert ui.hostFromUi("7000", "7001") : "hostFromUi should dispatch";
        assert rec.calls.equals(List.of("host:7000:7001"))
            : "Host action should reach the seam once with parsed ports, got " + rec.calls;
    }

    private static void testUiSeamDispatchesConnect() {
        SharedCampaignUI ui = new SharedCampaignUI();
        RecordingActions rec = new RecordingActions();
        ui.setActions(rec);

        assert ui.connectFromUi("192.168.1.5:9999") : "Valid address should dispatch";
        assert ui.connectFromUi("example.org") : "Bare host should dispatch with default port";
        assert !ui.connectFromUi("   ") : "Blank address must be rejected";
        assert !ui.connectFromUi(null) : "Null address must be rejected";

        assert rec.calls.equals(List.of("connect:192.168.1.5:9999", "connect:example.org:6567"))
            : "Connect parsing wrong, got " + rec.calls;
    }

    private static void testPortAndAddressParsing() {
        assert SharedCampaignUI.parsePort("", 6567) == 6567 : "Blank port falls back";
        assert SharedCampaignUI.parsePort("abc", 6567) == 6567 : "Non-numeric port falls back";
        assert SharedCampaignUI.parsePort("0", 6567) == 6567 : "Port 0 falls back";
        assert SharedCampaignUI.parsePort("70000", 6567) == 6567 : "Out-of-range port falls back";
        assert SharedCampaignUI.parsePort("8080", 6567) == 8080 : "Valid port honored";

        String[] a = SharedCampaignUI.parseAddress("10.0.0.1:25565", 6567);
        assert a != null && a[0].equals("10.0.0.1") && a[1].equals("25565") : "host:port parse failed";
        String[] b = SharedCampaignUI.parseAddress("10.0.0.1", 6567);
        assert b != null && b[1].equals("6567") : "Bare host should use default port";
        String[] c = SharedCampaignUI.parseAddress("10.0.0.1:", 6567);
        assert c != null && c[1].equals("6567") : "Trailing colon should use default port";
    }

    private static void testControllerLifecycle() throws Exception {
        Path dir = Files.createTempDirectory("aco-wiring-lifecycle-");
        AcoController controller = new AcoController(dir.toString());
        controller.init();

        String status = controller.getStatus();
        assert status.contains("init=true") : "Controller must report initialized, got " + status;
        assert !controller.isHosting() : "Inert init must not bind the sync port";
        assert !controller.isConnected() : "Fresh controller must not report a connection";
        assert controller.getGlobalRevision() == 0 : "Fresh campaign revision must be 0";

        controller.dispose();
        assert !controller.isHosting() : "Disposed controller must not be hosting";

        // Idempotent dispose must not throw.
        controller.dispose();

        deleteRecursively(dir);
    }

    private static void testLocalhostHandshake() throws Exception {
        Path hostDir = Files.createTempDirectory("aco-wiring-host-");
        Path clientDir = Files.createTempDirectory("aco-wiring-client-");

        // Pick a free port by binding then releasing.
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        AcoController host = new AcoController(hostDir.toString());
        AcoController client = new AcoController(clientDir.toString());
        try {
            assert host.hostCampaign(port, port + 1) : "Host should start the authority";
            assert host.isHosting() : "Host should report hosting";
            Thread.sleep(200); // allow the accept loop to bind

            assert client.connectToAuthority("127.0.0.1", port)
                : "Client should complete a real PING/PONG handshake against the live authority";
            assert client.isConnected() : "Client should report connected after handshake";

            // A second client on a fresh controller proves the authority accepts
            // more than one session (accept loop still alive).
            AcoController client2 = new AcoController(clientDir.toString() + "-2");
            assert client2.connectToAuthority("127.0.0.1", port) : "Second client should also handshake";
            client2.dispose();

            // Connecting to a dead port must fail cleanly, not hang or throw.
            int deadPort;
            try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
                deadPort = probe.getLocalPort();
            }
            AcoController dead = new AcoController(clientDir.toString() + "-dead");
            assert !dead.connectToAuthority("127.0.0.1", deadPort)
                : "Connecting to a closed port must return false";
            dead.dispose();
        } finally {
            client.dispose();
            host.dispose();
        }

        deleteRecursively(hostDir);
        deleteRecursively(clientDir);
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

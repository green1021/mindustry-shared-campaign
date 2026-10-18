package tests;

import sc.aco.AcoController;
import sc.aco.SectorRuntime;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Regression for the persistence split-brain: {@code AcoServer} used to build a
 * throwaway {@code new CampaignPersistence(java.nio.file.Paths.get("."))} instead of
 * serializing through the authority's own persistence. A successful sector
 * completion therefore wrote {@code campaign.json} into the process working
 * directory, while the authority recovered its state from the controller's
 * configured storage directory — two divergent campaign states on one machine.
 *
 * <p>This test seeds an unlocked sector (a fresh campaign unlocks nothing, so the
 * completion handler's persist branch is otherwise unreachable), drives a real
 * completion over TCP, and asserts the state landed in the configured directory and
 * not in the process CWD.
 */
public class AcoPersistenceLocationTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("AcoPersistenceLocationTest");
        System.setProperty("arc.headless", "true");

        run("CompletionPersistsToConfiguredDir", AcoPersistenceLocationTest::testPersistsToConfiguredDir);
        run("CompletionLeavesCwdClean", AcoPersistenceLocationTest::testCwdClean);

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

    private static final class StubRuntime implements SectorRuntime {
        @Override
        public boolean initializeSector(String sectorKey, String revision) {
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

    /** Host + client with the host's storage dir seeded with one unlocked sector. */
    private static final class Pair implements AutoCloseable {
        final AcoController host;
        final AcoController client;
        final Path hostDir;
        final Path clientDir;
        final Path cwdState = Paths.get("campaign.json");
        final boolean cwdStateExistedBefore;
        private final Path createdByUs;

        Pair() throws Exception {
            int syncPort = freePort();
            hostDir = Files.createTempDirectory("aco-persist-host-");
            clientDir = Files.createTempDirectory("aco-persist-client-");

            // A fresh campaign unlocks nothing; seed "craters" so the completion
            // handler's unlocked check passes and it reaches the persist branch.
            Files.writeString(hostDir.resolve("campaign.json"),
                "{\"campaignId\":\"default\",\"globalRevision\":0,\"unlockedSectors\":[\"craters\"],"
                + "\"completedSectors\":[],\"globalBank\":{},\"techTree\":{}}");

            cwdStateExistedBefore = Files.exists(cwdState);

            host = new AcoController(hostDir.toString());
            if (!host.hostCampaign(syncPort, syncPort + 1)) {
                throw new IllegalStateException("hostCampaign returned false");
            }
            Thread.sleep(200);

            client = new AcoController(clientDir.toString());
            if (!client.connectToAuthority("127.0.0.1", syncPort)) {
                throw new IllegalStateException("client handshake failed");
            }

            // Drive the completion whose persistence we are checking.
            String resp = client.requestSectorCompletion("craters", "op-persist-1");
            if (resp == null || !resp.startsWith("craters|SUCCESS|")) {
                throw new IllegalStateException("completion did not succeed: " + resp);
            }

            // If the defect regressed, a file appeared in the CWD. Remember it so
            // close() can remove only what this test created.
            createdByUs = (!cwdStateExistedBefore && Files.exists(cwdState)) ? cwdState : null;
        }

        @Override
        public void close() throws Exception {
            client.dispose();
            host.dispose();
            if (createdByUs != null) {
                Files.deleteIfExists(createdByUs);
            }
            deleteRecursively(hostDir);
            deleteRecursively(clientDir);
        }
    }

    private static void testPersistsToConfiguredDir() throws Exception {
        try (Pair p = new Pair()) {
            Path stateFile = p.hostDir.resolve("campaign.json");
            assert Files.exists(stateFile) : "campaign state must persist into " + p.hostDir;
            String json = Files.readString(stateFile);
            assert json.contains("\"craters\"") : "persisted state should record the completed sector";
            assert json.contains("\"globalRevision\":1") : "persisted state should carry the bumped revision: " + json;
        }
    }

    private static void testCwdClean() throws Exception {
        try (Pair p = new Pair()) {
            assert !Files.exists(p.cwdState)
                : "campaign state leaked into the process CWD (" + p.cwdState.toAbsolutePath() + ") "
                    + "instead of the configured storage directory";
        }
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

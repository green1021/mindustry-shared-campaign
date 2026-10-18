package tests;

import sc.aco.AcoClient;
import sc.aco.AcoController;
import sc.aco.AcoFrame;
import sc.aco.AcoMessageType;
import sc.aco.GlobalCampaignState;
import sc.aco.GlobalResourceRequest;
import sc.aco.SectorRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rows K/L of {@code PHASE9A_PRE_AUDIT.md}: a {@code GLOBAL_STATE_SYNC} broadcast
 * is received by a client and folded into its local campaign view.
 *
 * <p>Row K was recorded as UNTESTED because the broadcast was fire-and-forget in
 * {@code AcoServer} — no client-side receiver existed — and row L as NOT
 * IMPLEMENTED. {@link AcoClient#handleGlobalStateSync} now parses the broadcast
 * payload with the same codec the authority persists
 * ({@link sc.aco.CampaignPersistence#toJson}) and folds it into a revision-guarded
 * cache. These tests drive a real broadcast over a real socket into that receiver
 * and assert the view advances.
 */
public class AcoClientStateSyncTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("AcoClientStateSyncTest");
        System.setProperty("arc.headless", "true");

        run("BroadcastAdvancesLocalView", AcoClientStateSyncTest::testBroadcastAdvancesView);
        run("StaleOrEqualSyncDoesNotRollBack", AcoClientStateSyncTest::testStaleSyncRejected);
        run("MalformedPayloadIsIgnored", AcoClientStateSyncTest::testMalformedPayloadIgnored);

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

    /**
     * A production-shaped {@link AcoClient}: it owns a real socket to the
     * authority and dispatches unsolicited {@code GLOBAL_STATE_SYNC} frames to
     * {@link AcoClient#handleGlobalStateSync} exactly as the mod would.
     */
    private static final class SocketClient extends AcoClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final AtomicInteger syncFrames = new AtomicInteger();

        SocketClient(String host, int port) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(0);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            Thread reader = new Thread(this::readLoop, "aco-state-sync-reader");
            reader.setDaemon(true);
            reader.start();
        }

        @Override
        protected void sendFrame(AcoFrame f) throws IOException {
            synchronized (out) {
                out.write(f.encode());
                out.flush();
            }
        }

        private void readLoop() {
            try {
                while (!socket.isClosed()) {
                    AcoFrame f = readFrame(in);
                    if (f == null) break;
                    if (f.type() == AcoMessageType.GLOBAL_STATE_SYNC.id) {
                        syncFrames.incrementAndGet();
                        handleGlobalStateSync(f);
                    }
                }
            } catch (IOException ignored) {
                // socket closed under us; the test is done with this client
            }
        }

        int syncFrameCount() {
            return syncFrames.get();
        }

        /** Block until the local view reaches {@code revision}, or fail the caller. */
        boolean awaitRevision(long revision, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (getLocalCache().globalRevision >= revision) return true;
                Thread.sleep(25);
            }
            return false;
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }

        private static AcoFrame readFrame(InputStream in) throws IOException {
            byte[] header = new byte[AcoFrame.HEADER_SIZE];
            if (!readFully(in, header)) return null;
            ByteBuffer hb = ByteBuffer.wrap(header);
            int len = hb.getInt(6);
            if (len < 0 || len > AcoFrame.MAX_PAYLOAD) return null;
            byte[] body = new byte[len];
            if (!readFully(in, body)) return null;
            return new AcoFrame(hb.get(0), hb.get(1), hb.getInt(2), body);
        }

        private static boolean readFully(InputStream in, byte[] buf) throws IOException {
            int total = 0;
            while (total < buf.length) {
                int r = in.read(buf, total, buf.length - total);
                if (r == -1) return false;
                total += r;
            }
            return true;
        }
    }

    private static final class Host implements AutoCloseable {
        final AcoController host;
        final AcoController producer;
        final int syncPort;
        private final Path hostDir;
        private final Path producerDir;

        Host() throws Exception {
            syncPort = freePort();
            hostDir = Files.createTempDirectory("aco-state-sync-host-");
            producerDir = Files.createTempDirectory("aco-state-sync-producer-");

            host = new AcoController(hostDir.toString());
            host.setSectorRuntime(new StubRuntime());
            if (!host.hostCampaign(syncPort, syncPort + 1)) {
                throw new IllegalStateException("hostCampaign returned false");
            }
            Thread.sleep(200);

            producer = new AcoController(producerDir.toString());
            if (!producer.connectToAuthority("127.0.0.1", syncPort)) {
                throw new IllegalStateException("producer handshake failed");
            }
        }

        @Override
        public void close() throws Exception {
            producer.dispose();
            host.dispose();
            deleteRecursively(hostDir);
            deleteRecursively(producerDir);
        }
    }

    private static void testBroadcastAdvancesView() throws Exception {
        try (Host h = new Host(); SocketClient view = new SocketClient("127.0.0.1", h.syncPort)) {
            // The raw socket registers as a session on accept; give the server a
            // beat to do so before the mutation that triggers the broadcast.
            Thread.sleep(200);
            assert view.getLocalCache().globalRevision == 0
                : "a fresh view should start at revision 0, got " + view.getLocalCache().globalRevision;

            String res = h.producer.requestResource("op-1", "groundZero", 0,
                "copper", 100, GlobalResourceRequest.OperationType.CREDIT);
            assert res != null && res.startsWith("op-1|true|")
                : "credit should succeed, got " + res;

            assert view.awaitRevision(1, 5000)
                : "local view should receive the GLOBAL_STATE_SYNC broadcast, still at revision "
                    + view.getLocalCache().globalRevision;

            GlobalCampaignState cache = view.getLocalCache();
            assert cache.campaignId.equals("default")
                : "synced view should carry the campaign id, got " + cache.campaignId;
            assert cache.globalBank.getOrDefault("copper", 0L) == 100L
                : "synced view should carry the credited balance, got " + cache.globalBank.get("copper");
            assert view.syncFrameCount() >= 1
                : "a real GLOBAL_STATE_SYNC frame should have arrived, saw " + view.syncFrameCount();
        }
    }

    private static void testStaleSyncRejected() throws Exception {
        AcoClient view = new AcoClient();

        GlobalCampaignState fresh = new GlobalCampaignState();
        fresh.campaignId = "default";
        fresh.globalRevision = 5;
        fresh.globalBank.put("copper", 500L);
        assert view.updateLocalCache(fresh) : "a newer revision should apply";
        assert view.getLocalCache().globalRevision == 5 : "view should hold revision 5";

        GlobalCampaignState stale = new GlobalCampaignState();
        stale.campaignId = "default";
        stale.globalRevision = 3;
        stale.globalBank.put("copper", 50L);
        assert !view.updateLocalCache(stale) : "a lower revision must be rejected";
        assert view.getLocalCache().globalRevision == 5
            : "a replayed/stale sync must not roll the view back, got " + view.getLocalCache().globalRevision;
        assert view.getLocalCache().globalBank.getOrDefault("copper", 0L) == 500L
            : "a stale sync must not overwrite the balance";

        GlobalCampaignState equal = new GlobalCampaignState();
        equal.campaignId = "default";
        equal.globalRevision = 5;
        equal.globalBank.put("copper", 999L);
        assert !view.updateLocalCache(equal) : "an equal revision must not re-apply";
        assert view.getLocalCache().globalBank.getOrDefault("copper", 0L) == 500L
            : "an equal revision must leave the view untouched";
    }

    private static void testMalformedPayloadIgnored() throws Exception {
        AcoClient view = new AcoClient();

        GlobalCampaignState good = new GlobalCampaignState();
        good.campaignId = "default";
        good.globalRevision = 7;
        good.globalBank.put("copper", 700L);
        assert view.updateLocalCache(good) : "seed the view at revision 7";

        // Unparseable revision: fromJson throws NumberFormatException, which the
        // receiver must swallow rather than tear the connection down.
        byte[] bad = "{\"globalRevision\":\"not-a-number\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        view.handleGlobalStateSync(new AcoFrame((byte) 1, AcoMessageType.GLOBAL_STATE_SYNC.id, 0, bad));

        assert view.getLocalCache().globalRevision == 7
            : "a malformed sync must leave the view at revision 7, got " + view.getLocalCache().globalRevision;
        assert view.getLocalCache().globalBank.getOrDefault("copper", 0L) == 700L
            : "a malformed sync must not corrupt the view";
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

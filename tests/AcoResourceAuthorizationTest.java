package tests;

import sc.aco.AcoController;
import sc.aco.AcoFrame;
import sc.aco.AcoMessageType;
import sc.aco.GlobalResourceRequest;
import sc.aco.SectorRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side identity and authorization regression for the resource protocol.
 *
 * <p>The removed protocol accepted a seven-field payload containing
 * client-supplied session identity and a legacy four-field payload; the socket
 * handler also labelled connections {@code temp-<port>}. The canonical protocol
 * now carries no session field at all (six fields): the authority derives the
 * peer identity from the server-generated connection id and authorizes the
 * sector server-side. These tests drive real sockets and assert that
 * (a) only the connection bound to a sector may transact on it,
 * (b) a failed claim never binds, and
 * (c) malformed, legacy and forged payloads are rejected explicitly.
 *
 * <p>Documented limitation (not hidden): connections are bound and authorized
 * server-side, but no cryptographic peer authentication exists yet — the
 * transport cannot prove WHO runs a connection. The tests therefore pin
 * connection-level behavior only.
 */
public class AcoResourceAuthorizationTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("AcoResourceAuthorizationTest");
        System.setProperty("arc.headless", "true");

        run("HostBoundConnectionAuthorizedOthersRejected", AcoResourceAuthorizationTest::testHostBoundAuthorized);
        run("FailedClaimDoesNotBindConnection", AcoResourceAuthorizationTest::testFailedClaimDoesNotBind);
        run("MalformedLegacyAndForgedPayloadsRejected", AcoResourceAuthorizationTest::testMalformedAndLegacyRejected);

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

    /** Authority + two connected clients; raw-socket access to the sync port. */
    private static final class Fixture implements AutoCloseable {
        final AcoController host;
        final AcoController clientA;
        final AcoController clientB;
        final int syncPort;
        private final Path hostDir;
        private final Path dirA;
        private final Path dirB;

        Fixture() throws Exception {
            syncPort = freePort();
            hostDir = Files.createTempDirectory("aco-authz-host-");
            dirA = Files.createTempDirectory("aco-authz-a-");
            dirB = Files.createTempDirectory("aco-authz-b-");

            host = new AcoController(hostDir.toString());
            if (!host.hostCampaign(syncPort, syncPort + 1)) {
                throw new IllegalStateException("hostCampaign returned false");
            }
            Thread.sleep(200);

            clientA = connect(dirA);
            clientB = connect(dirB);
        }

        private AcoController connect(Path dir) throws Exception {
            AcoController c = new AcoController(dir.toString());
            if (!c.connectToAuthority("127.0.0.1", syncPort)) {
                throw new IllegalStateException("client handshake failed");
            }
            return c;
        }

        @Override
        public void close() throws Exception {
            clientA.dispose();
            clientB.dispose();
            host.dispose();
            deleteRecursively(hostDir);
            deleteRecursively(dirA);
            deleteRecursively(dirB);
        }
    }

    /** Minimal raw frame client: full control over payload bytes. */
    private static final class RawClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        RawClient(String host, int port) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(5000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            // Liveness handshake: the authority answers PING with PONG on any
            // accepted connection, so a timeout later means a real failure.
            synchronized (out) {
                out.write(new AcoFrame((byte) 1, AcoMessageType.PING.id, 0, new byte[0]).encode());
                out.flush();
            }
            readFrameIgnore();
        }

        private void readFrameIgnore() throws IOException {
            // consume the PONG (or whatever arrives) so it cannot be confused
            // with a response later
            readFrame(in);
        }

        /** Send one frame and return the payload of the next GLOBAL_RESOURCE_RESPONSE. */
        String send(byte typeId, String payload) throws IOException {
            synchronized (out) {
                out.write(new AcoFrame((byte) 1, typeId, 0, payload.getBytes(StandardCharsets.UTF_8)).encode());
                out.flush();
            }
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                AcoFrame f = readFrame(in);
                if (f == null) break;
                if (f.type() == AcoMessageType.GLOBAL_RESOURCE_RESPONSE.id) {
                    return new String(f.payload(), StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static AcoFrame readFrame(InputStream in) throws IOException {
        try {
            byte[] header = new byte[AcoFrame.HEADER_SIZE];
            int total = 0;
            while (total < AcoFrame.HEADER_SIZE) {
                int r = in.read(header, total, AcoFrame.HEADER_SIZE - total);
                if (r == -1) return null;
                total += r;
            }
            ByteBuffer hb = ByteBuffer.wrap(header);
            int len = hb.getInt(6);
            if (len < 0 || len > AcoFrame.MAX_PAYLOAD) return null;
            byte[] body = new byte[len];
            total = 0;
            while (total < len) {
                int r = in.read(body, total, len - total);
                if (r == -1) return null;
                total += r;
            }
            return new AcoFrame(hb.get(0), hb.get(1), hb.getInt(2), body);
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    private static void assertError(String response, String expectedError) {
        assert response != null : "no GLOBAL_RESOURCE_RESPONSE received (expected " + expectedError + ")";
        String[] f = response.split("\\|", -1);
        assert f.length >= 3 && f[2].equals(expectedError)
            : "expected " + expectedError + ", got " + response;
    }

    private static void testHostBoundAuthorized() throws Exception {
        try (Fixture f = new Fixture()) {
            String claim = f.clientA.requestClaim("groundZero", 7);
            assert claim != null : "claim must get a response";
            assert claim.contains("|OK|") : "claim should succeed, got " + claim;

            // The claiming connection may transact on the sector it hosts.
            String ok = f.clientA.requestResource("op-a1", "groundZero", 0, "copper", 50,
                GlobalResourceRequest.OperationType.CREDIT);
            assert ok != null && ok.startsWith("op-a1|true|")
                : "hosting connection should be authorized, got " + ok;

            // A different connection naming the same sector is refused even with a
            // perfectly formed payload: authorization is server-side and bound to
            // the hosting connection, not to any client-supplied identity.
            String forged = f.clientB.requestResource("op-b1", "groundZero", 1, "copper", 999,
                GlobalResourceRequest.OperationType.CREDIT);
            assert forged != null && forged.contains("UNAUTHORIZED_SECTOR")
                : "non-hosting connection must be refused with UNAUTHORIZED_SECTOR, got " + forged;

            // The refusal did not mutate state: the next host operation sees
            // revision 2 and balance 75 (50+25), not 1049.
            String next = f.clientA.requestResource("op-a2", "groundZero", 1, "copper", 25,
                GlobalResourceRequest.OperationType.CREDIT);
            assert next != null && next.startsWith("op-a2|true|") : "host follow-up should succeed, got " + next;
            String[] nf = next.split("\\|", -1);
            assert Long.parseLong(nf[3]) == 2 : "revision should be 2 (forged request ignored), got " + nf[3];
            assert Long.parseLong(nf[4]) == 75 : "balance should be 75 (forged credit absent), got " + nf[4];
        }
    }

    private static void testFailedClaimDoesNotBind() throws Exception {
        try (Fixture f = new Fixture()) {
            String first = f.clientA.requestClaim("groundZero", 1);
            assert first != null && first.contains("OK") : "first claim should succeed, got " + first;

            // B's claim on the held sector fails; the failed claim must not bind B.
            String second = f.clientB.requestClaim("groundZero", 1);
            assert second != null && second.contains("FAIL") : "claim on a held sector must fail, got " + second;

            String blocked = f.clientB.requestResource("op-b2", "groundZero", 0, "copper", 5,
                GlobalResourceRequest.OperationType.CREDIT);
            assert blocked != null && blocked.contains("UNAUTHORIZED_SECTOR")
                : "connection without a bound claim must stay unauthorized, got " + blocked;

            // And the real host is still the one authorized.
            String ok = f.clientA.requestResource("op-a3", "groundZero", 0, "copper", 5,
                GlobalResourceRequest.OperationType.CREDIT);
            assert ok != null && ok.startsWith("op-a3|true|")
                : "original host must remain authorized, got " + ok;
        }
    }

    private static void testMalformedAndLegacyRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            try (RawClient raw = new RawClient("127.0.0.1", f.syncPort)) {
                // canonical baseline: a well-formed request for a LOCKED sector is
                // refused by authorization (forged/unowned sector), not by shape.
                assertError(raw.send(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, "op-r1|craters|0|copper|10|CREDIT"),
                    "UNAUTHORIZED_SECTOR");
                // seven-field client-identity payload (removed format)
                assertError(raw.send(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, "op-r2|sess-X|groundZero|0|copper|10|CREDIT"),
                    "INVALID_REQUEST_FIELDS");
                // legacy four-field payload (removed format)
                assertError(raw.send(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, "copper|10|op-r3|CREDIT"),
                    "INVALID_REQUEST_FIELDS");
                // malformed numbers / unknown operation type / stray arity
                assertError(raw.send(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, "op-r4|groundZero|zero|copper|10|CREDIT"),
                    "INVALID_REQUEST_FIELDS");
                assertError(raw.send(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, "op-r5|groundZero|0|copper|ten|CREDIT"),
                    "INVALID_REQUEST_FIELDS");
                assertError(raw.send(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, "op-r6|groundZero|0|copper|10|TRANSFER"),
                    "INVALID_REQUEST_FIELDS");
                assertError(raw.send(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, "op-r7|groundZero|0"),
                    "INVALID_REQUEST_FIELDS");

                // positive control: the same raw connection, canonical payload for the
                // open starting sector, is accepted — the rejections above are
                // shape/authorization-driven, not a blanket refusal of the format.
                String ok = raw.send(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, "op-r0|groundZero|0|copper|10|CREDIT");
                assert ok != null && ok.startsWith("op-r0|true|")
                    : "canonical request should be accepted, got " + ok;
                String[] nf = ok.split("\\|", -1);
                assert Long.parseLong(nf[3]) == 1 : "canonical control should commit revision 1, got " + nf[3];
            }
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

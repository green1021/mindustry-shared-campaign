package tests;

import sc.aco.AcoController;
import sc.aco.AcoFrame;
import sc.aco.AcoMessageType;
import sc.aco.AcoServer;
import sc.aco.CampaignPersistence;
import sc.aco.GlobalBank;
import sc.aco.GlobalCampaignState;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Protocol and transport hardening.
 *
 * <p>Two kinds of cases live here. The codec cases exercise
 * {@link AcoFrame#encode()} / {@link AcoFrame#decode(ByteBuffer)} directly: a
 * decoder that allocates from a peer-supplied length is a remote OOM, so the
 * length checks have to happen before any allocation, and a partial frame must
 * rewind rather than consume bytes or return a half frame.
 *
 * <p>The remaining cases drive a real {@link AcoServer} over a real loopback
 * socket with hand-built frames, so the assertions are about what the server
 * does with hostile input (declared lengths, unknown types, oversized ids,
 * disconnect mid-frame), not about a mock. State claims are checked against the
 * <em>durable</em> snapshot read back through {@link CampaignPersistence}, which
 * is the same file a restart would load.
 */
public class ProtocolHardeningTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("ProtocolHardeningTest");
        System.setProperty("arc.headless", "true");

        // codec
        run("ValidFramesRoundTripAtPayloadLimit", ProtocolHardeningTest::validFramesRoundTrip);
        run("OversizePayloadRejected", ProtocolHardeningTest::oversizePayloadRejected);
        run("TruncatedHeaderReturnsNull", ProtocolHardeningTest::truncatedHeaderReturnsNull);
        run("TruncatedPayloadReturnsNullThenCompletes", ProtocolHardeningTest::truncatedPayloadRewinds);
        run("NegativeAndHugeLengthRejectedBeforeAllocation", ProtocolHardeningTest::invalidLengthsRejected);

        // transport against a live server
        run("PingPongCorrelatesAndPreservesOrder", ProtocolHardeningTest::pingPongOrdered);
        run("UnknownMessageTypeIgnoredWithoutStateChange", ProtocolHardeningTest::unknownTypeIgnored);
        run("MalformedResourceRequestCannotChangeBalance", ProtocolHardeningTest::malformedResourceNoStateChange);
        run("OperationIdAndNeighborListBounded", ProtocolHardeningTest::operationIdAndNeighborsBounded);
        run("InvalidSectorKeysRejectedOnEveryPath", ProtocolHardeningTest::invalidSectorKeysRejected);
        run("OversizeDeclaredLengthClosesOnlyThatConnection", ProtocolHardeningTest::oversizeDeclaredLength);
        run("AbruptDisconnectMidHeaderAndMidPayload", ProtocolHardeningTest::abruptDisconnect);
        run("BlockedConnectionDoesNotSerializeOthers", ProtocolHardeningTest::blockedConnectionIsolated);
        run("ConnectionCapRefusesExcessAndRecovers", ProtocolHardeningTest::connectionCap);

        // bounded tables
        run("StateSyncFitsOneFrameAtTableCapacity", ProtocolHardeningTest::stateSyncFitsFrame);
        run("BankCapacityRefusesNewKeysOnly", ProtocolHardeningTest::bankCapacity);
        run("TechCapacityRefusesNewEntriesOnly", ProtocolHardeningTest::techCapacity);

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

    // ------------------------------------------------------------------ codec

    private static void validFramesRoundTrip() {
        byte[] max = new byte[AcoFrame.MAX_PAYLOAD];
        for (int i = 0; i < max.length; i++) max[i] = (byte) (i * 31);

        AcoFrame frame = new AcoFrame((byte) 1, (byte) 21, 123456, max);
        byte[] encoded = frame.encode();
        assert encoded.length == AcoFrame.HEADER_SIZE + AcoFrame.MAX_PAYLOAD
            : "encoded length must be header+payload, got " + encoded.length;

        ByteBuffer one = ByteBuffer.wrap(encoded);
        AcoFrame decoded = AcoFrame.decode(one);
        assert decoded != null : "a complete maximum-size frame must decode";
        assert decoded.msgId() == 123456 && decoded.type() == 21 && decoded.version() == 1
            : "header fields must survive the round trip";
        assert Arrays.equals(decoded.payload(), max) : "payload bytes must survive the round trip";
        assert one.remaining() == 0 : "the whole frame must be consumed, " + one.remaining() + " left";

        // A decode must consume exactly one frame and leave the next untouched.
        ByteBuffer two = ByteBuffer.allocate(encoded.length + AcoFrame.HEADER_SIZE);
        two.put(encoded).put(rawFrame(3, 9, new byte[0])).flip();
        AcoFrame first = AcoFrame.decode(two);
        assert first != null && first.msgId() == 123456;
        assert two.remaining() == AcoFrame.HEADER_SIZE
            : "one decode must consume exactly one frame, " + two.remaining() + " left";
        AcoFrame second = AcoFrame.decode(two);
        assert second != null && second.msgId() == 9 && second.payload().length == 0
            : "the frame after the maximum-size one must decode on its own";

        AcoFrame empty = new AcoFrame((byte) 1, (byte) 3, 7, new byte[0]);
        assert empty.encode().length == AcoFrame.HEADER_SIZE : "an empty payload is legal";
    }

    private static void oversizePayloadRejected() {
        for (int size : new int[]{AcoFrame.MAX_PAYLOAD + 1, 4 * 1024 * 1024}) {
            try {
                new AcoFrame((byte) 1, (byte) 21, 1, new byte[size]).encode();
                assert false : "a " + size + "-byte payload was encoded into a frame";
            } catch (IllegalArgumentException e) {
                assert e.getMessage() != null && e.getMessage().startsWith("payload-too-large")
                    : "unexpected message: " + e.getMessage();
                assert e.getMessage().contains(String.valueOf(size))
                    : "the error must name the offending size: " + e.getMessage();
            }
        }
    }

    private static void truncatedHeaderReturnsNull() {
        for (int n = 0; n < AcoFrame.HEADER_SIZE; n++) {
            ByteBuffer partial = ByteBuffer.allocate(n);
            for (int i = 0; i < n; i++) partial.put((byte) i);
            partial.flip();
            int before = partial.position();
            assert AcoFrame.decode(partial) == null : "a " + n + "-byte header must not decode";
            assert partial.position() == before : "a short header must leave the buffer position alone";
        }
    }

    private static void truncatedPayloadRewinds() {
        ByteBuffer partial = ByteBuffer.allocate(AcoFrame.HEADER_SIZE + 10);
        partial.put((byte) 1).put((byte) 21).putInt(5).putInt(10).put("abcd".getBytes(StandardCharsets.UTF_8));
        partial.flip();
        assert AcoFrame.decode(partial) == null : "a frame with 4/10 payload bytes must not decode";
        assert partial.position() == 0
            : "a frame that needs more bytes must rewind so the caller can retry, position=" + partial.position();

        // Second read delivers the rest; the same buffer must now decode whole.
        partial.limit(partial.capacity());
        partial.position(AcoFrame.HEADER_SIZE + 4);
        partial.put("efghij".getBytes(StandardCharsets.UTF_8));
        partial.flip();
        AcoFrame frame = AcoFrame.decode(partial);
        assert frame != null : "the completed frame must decode";
        assert new String(frame.payload(), StandardCharsets.UTF_8).equals("abcdefghij")
            : "payload was reassembled wrong: " + new String(frame.payload(), StandardCharsets.UTF_8);
    }

    private static void invalidLengthsRejected() {
        // The JVM runs with -Xmx96m here: a decoder that allocated from the declared
        // length before validating it would fail this case with OutOfMemoryError
        // instead of the controlled IllegalArgumentException asserted below.
        for (int len : new int[]{-1, Integer.MIN_VALUE, AcoFrame.MAX_PAYLOAD + 1, Integer.MAX_VALUE}) {
            ByteBuffer header = ByteBuffer.allocate(AcoFrame.HEADER_SIZE);
            header.put((byte) 1).put((byte) 21).putInt(1).putInt(len).flip();
            try {
                AcoFrame.decode(header);
                assert false : "declared length " + len + " was accepted";
            } catch (IllegalArgumentException e) {
                assert "invalid-len".equals(e.getMessage()) : "unexpected message: " + e.getMessage();
            }
        }
    }

    // --------------------------------------------------------------- transport

    private static void pingPongOrdered() throws Exception {
        try (Host host = new Host(); Socket socket = host.raw()) {
            // Both frames in one write: they can arrive in a single read, which is
            // exactly the case where a shared or mis-synchronized writer mixes them.
            byte[] both = concat(rawFrame(3, 701, utf8("a")), rawFrame(3, 702, utf8("b")));
            socket.getOutputStream().write(both);
            socket.getOutputStream().flush();

            AcoFrame first = readFrame(socket, 3000);
            AcoFrame second = readFrame(socket, 3000);
            assert first.type() == AcoMessageType.PONG.id && first.msgId() == 701
                : "first pong must answer msgId 701, got type=" + first.type() + " id=" + first.msgId();
            assert second.type() == AcoMessageType.PONG.id && second.msgId() == 702
                : "second pong must answer msgId 702, got type=" + second.type() + " id=" + second.msgId();
        }
    }

    private static void unknownTypeIgnored() throws Exception {
        try (Host host = new Host(); Socket socket = host.raw()) {
            socket.getOutputStream().write(rawFrame(99, 11, utf8("ignore-me")));
            socket.getOutputStream().write(rawFrame(0, 12, utf8("also-ignore")));
            socket.getOutputStream().flush();

            // No response for unknown types, and the connection must stay usable.
            socket.getOutputStream().write(rawFrame(3, 13, new byte[0]));
            socket.getOutputStream().flush();
            AcoFrame pong = readFrame(socket, 3000);
            assert pong.type() == AcoMessageType.PONG.id && pong.msgId() == 13
                : "an unknown message type must not kill the connection";

            GlobalCampaignState durable = host.durable();
            assert durable.globalRevision == 0 : "unknown types must not move the revision";
            assert durable.globalBank.isEmpty() : "unknown types must not touch the bank";
        }
    }

    private static void malformedResourceNoStateChange() throws Exception {
        try (Host host = new Host(); Socket socket = host.raw()) {
            // Wrong arity.
            socket.getOutputStream().write(rawFrame(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, 21, utf8("junk")));
            socket.getOutputStream().flush();
            String arity = new String(readFrame(socket, 3000).payload(), StandardCharsets.UTF_8);
            assert arity.contains("INVALID_REQUEST_FIELDS") : "arity must be refused, got " + arity;
            assert arity.endsWith("|0|0") : "revision and balance must be untouched, got " + arity;

            // Right arity, unparseable number.
            socket.getOutputStream().write(rawFrame(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, 22,
                utf8("op-nan|groundZero|not-a-number|copper|5|CREDIT")));
            socket.getOutputStream().flush();
            String number = new String(readFrame(socket, 3000).payload(), StandardCharsets.UTF_8);
            assert number.contains("INVALID_REQUEST_FIELDS") : "a non-numeric revision must be refused, got " + number;
            assert number.endsWith("|0|0") : "no state change from a malformed field, got " + number;

            // Still at revision 0: a well-formed credit is the first mutation.
            socket.getOutputStream().write(rawFrame(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, 23,
                utf8("op-ok|groundZero|0|copper|5|CREDIT")));
            socket.getOutputStream().flush();
            String ok = new String(readFrame(socket, 3000).payload(), StandardCharsets.UTF_8);
            assert ok.startsWith("op-ok|true|") : "a valid credit must still work after malformed ones, got " + ok;
            assert ok.endsWith("|1|5") : "one credit of 5 must land at revision 1, balance 5, got " + ok;

            GlobalCampaignState durable = host.durable();
            assert durable.globalRevision == 1 && durable.globalBank.getOrDefault("copper", 0L) == 5L
                : "durable state must be exactly one credit of 5, got rev=" + durable.globalRevision
                    + " bank=" + durable.globalBank;
        }
    }

    private static void operationIdAndNeighborsBounded() throws Exception {
        try (Host host = new Host(); Socket socket = host.raw()) {
            String longOp = "x".repeat(AcoServer.MAX_OPERATION_ID + 1);
            socket.getOutputStream().write(rawFrame(AcoMessageType.SECTOR_COMPLETION_EVENT.id, 31,
                utf8("groundZero|" + longOp + "|")));
            socket.getOutputStream().flush();
            String refused = new String(readFrame(socket, 3000).payload(), StandardCharsets.UTF_8);
            assert refused.contains("INVALID_OPERATION_ID") : "an oversized operation id must be refused, got " + refused;

            StringBuilder csv = new StringBuilder();
            for (int i = 0; i < AcoServer.MAX_NEIGHBORS + 24; i++) {
                if (i > 0) csv.append(',');
                csv.append("n").append(i);
            }
            socket.getOutputStream().write(rawFrame(AcoMessageType.SECTOR_COMPLETION_EVENT.id, 32,
                utf8("groundZero|op-neighbors|" + csv)));
            socket.getOutputStream().flush();
            String done = new String(readFrame(socket, 3000).payload(), StandardCharsets.UTF_8);
            assert done.startsWith("groundZero|SUCCESS|") : "completing an unlocked sector must succeed, got " + done;

            GlobalCampaignState durable = host.durable();
            assert durable.completedSectors.contains("groundZero") : "the completion must be durable";
            assert durable.unlockedSectors.size() == 1 + AcoServer.MAX_NEIGHBORS
                : "exactly " + AcoServer.MAX_NEIGHBORS + " neighbours may be unlocked, got "
                    + durable.unlockedSectors.size() + " " + durable.unlockedSectors;
            assert durable.unlockedSectors.contains("n0") : "neighbours are taken in order, first one missing";
            assert durable.unlockedSectors.contains("n" + (AcoServer.MAX_NEIGHBORS - 1))
                : "the last accepted neighbour is missing";
            assert !durable.unlockedSectors.contains("n" + AcoServer.MAX_NEIGHBORS)
                : "a neighbour past the cap must not be unlocked";
        }
    }

    private static void invalidSectorKeysRejected() throws Exception {
        try (Host host = new Host(); Socket socket = host.raw()) {
            String bad = "k".repeat(65);
            assert bad.length() > AcoServer.MAX_CONTENT_NAME;

            String[][] cases = {
                {String.valueOf(AcoMessageType.SECTOR_CLAIM_REQUEST.id), bad + "|0"},
                {String.valueOf(AcoMessageType.SECTOR_RELEASE.id), bad + "|1|grant"},
                {String.valueOf(AcoMessageType.SECTOR_REATTACH.id), bad + "|grant"},
                {String.valueOf(AcoMessageType.SECTOR_JOIN_REQUEST.id), bad},
            };
            int msgId = 40;
            for (String[] c : cases) {
                socket.getOutputStream().write(rawFrame(Integer.parseInt(c[0]), ++msgId, utf8(c[1])));
                socket.getOutputStream().flush();
                String response = new String(readFrame(socket, 3000).payload(), StandardCharsets.UTF_8);
                assert response.contains("FAIL") && response.contains("INVALID_SECTOR_KEY")
                    : "path " + c[0] + " must refuse an oversized sector key, got " + response;
            }

            assert host.controller.getRegistry().get(bad) == null
                : "a refused key must never reach the registry";
            GlobalCampaignState durable = host.durable();
            assert durable.globalRevision == 0 : "refused keys must not move the revision";
        }
    }

    private static void oversizeDeclaredLength() throws Exception {
        try (Host host = new Host()) {
            Socket hostile = host.raw();
            hostile.getOutputStream().write(header(1, AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, 50,
                Integer.MAX_VALUE));
            hostile.getOutputStream().flush();
            try {
                AcoFrame answered = readFrame(hostile, 3000);
                assert false : "the server answered an impossible frame: type=" + answered.type();
            } catch (EOFException expected) {
                // The server closed the connection instead of allocating or spinning.
            } finally {
                hostile.close();
            }

            // The acceptor and every other session must be unaffected.
            try (Socket healthy = host.raw()) {
                healthy.getOutputStream().write(rawFrame(3, 51, new byte[0]));
                healthy.getOutputStream().flush();
                AcoFrame pong = readFrame(healthy, 3000);
                assert pong.type() == AcoMessageType.PONG.id && pong.msgId() == 51
                    : "the server must still serve new connections after a hostile one";
            }
        }
    }

    private static void abruptDisconnect() throws Exception {
        try (Host host = new Host()) {
            Socket midHeader = host.raw();
            midHeader.getOutputStream().write(new byte[]{1, 21, 0, 0});
            midHeader.getOutputStream().flush();
            midHeader.close();

            Socket midPayload = host.raw();
            midPayload.getOutputStream().write(header(1, AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, 60, 1000));
            midPayload.getOutputStream().write(new byte[5]);
            midPayload.getOutputStream().flush();
            midPayload.close();

            try (Socket healthy = host.raw()) {
                healthy.getOutputStream().write(rawFrame(3, 61, new byte[0]));
                healthy.getOutputStream().flush();
                AcoFrame pong = readFrame(healthy, 3000);
                assert pong.type() == AcoMessageType.PONG.id && pong.msgId() == 61
                    : "a half-frame disconnect must not take the server down";
            }
            GlobalCampaignState durable = host.durable();
            assert durable.globalRevision == 0 : "a half-sent request must not mutate state";
        }
    }

    private static void blockedConnectionIsolated() throws Exception {
        try (Host host = new Host(); Socket stuck = host.raw()) {
            // A complete header announcing 1000 payload bytes, then only 5 of them:
            // this connection sits inside a blocking read with a frame in flight.
            stuck.getOutputStream().write(header(1, AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, 70, 1000));
            stuck.getOutputStream().write(new byte[5]);
            stuck.getOutputStream().flush();

            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger answered = new AtomicInteger();
            List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < threads; i++) {
                final int id = i;
                Thread t = new Thread(() -> {
                    try (Socket s = host.raw()) {
                        start.await();
                        s.getOutputStream().write(rawFrame(3, 700 + id, new byte[0]));
                        s.getOutputStream().flush();
                        AcoFrame pong = readFrame(s, 3000);
                        if (pong.type() == AcoMessageType.PONG.id && pong.msgId() == 700 + id) {
                            answered.incrementAndGet();
                        } else {
                            failures.add("wrong response for " + id);
                        }
                    } catch (Exception e) {
                        failures.add(id + ":" + e.getClass().getSimpleName());
                    } finally {
                        done.countDown();
                    }
                }, "probe-" + i);
                t.setDaemon(true);
                t.start();
            }
            start.countDown();
            assert done.await(10, TimeUnit.SECONDS) : "independent connections did not finish in time";
            assert failures.isEmpty() : "independent connections were blocked or mis-answered: " + failures;
            assert answered.get() == threads
                : "all " + threads + " connections must be served while one is mid-frame, got " + answered.get();
        }
    }

    private static void connectionCap() throws Exception {
        try (Host host = new Host()) {
            List<Socket> sockets = new ArrayList<>();
            AtomicInteger answered = new AtomicInteger();
            try {
                for (int i = 0; i < AcoServer.MAX_CONNECTIONS + 6; i++) {
                    Socket s = host.raw();
                    sockets.add(s);
                    try {
                        s.getOutputStream().write(rawFrame(3, 800 + i, new byte[0]));
                        s.getOutputStream().flush();
                    } catch (IOException closedEarly) {
                        // Refused by the server: this is the expected shape at capacity.
                    }
                }
                for (int i = 0; i < sockets.size(); i++) {
                    Socket s = sockets.get(i);
                    try {
                        AcoFrame pong = readFrame(s, 1500);
                        if (pong.type() == AcoMessageType.PONG.id) answered.incrementAndGet();
                    } catch (IOException expected) {
                        // timed out or closed: refused connection
                    }
                }
            } finally {
                for (Socket s : sockets) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            assert answered.get() <= AcoServer.MAX_CONNECTIONS
                : "more than " + AcoServer.MAX_CONNECTIONS + " connections were served: " + answered.get();
            assert answered.get() > 0 : "the server served no connection at all";

            // After the flood drains, the server must accept again.
            boolean recovered = false;
            long deadline = System.currentTimeMillis() + 5000;
            while (!recovered && System.currentTimeMillis() < deadline) {
                try (Socket s = host.raw()) {
                    s.getOutputStream().write(rawFrame(3, 999, new byte[0]));
                    s.getOutputStream().flush();
                    recovered = readFrame(s, 1500).type() == AcoMessageType.PONG.id;
                } catch (IOException retry) {
                    // pool still draining; try again within the deadline
                }
            }
            assert recovered : "the server must accept connections again after the flood drains";
        }
    }

    // ---------------------------------------------------------- bounded tables

    private static void stateSyncFitsFrame() {
        GlobalCampaignState state = new GlobalCampaignState();
        state.campaignId = "capacity";
        state.globalRevision = 10_000;
        for (int i = 0; i < AcoServer.MAX_UNLOCKED_SECTORS; i++) state.unlockedSectors.add("sector-" + i);
        for (int i = 0; i < AcoServer.MAX_TECH_ENTRIES; i++) state.techTree.put("tech-node-" + i, true);
        for (int i = 0; i < GlobalBank.MAX_BANK_ITEMS; i++) {
            state.globalBank.put("resource-item-" + i, Long.MAX_VALUE / 2);
        }

        String sync = CampaignPersistence.toSyncJson(state);
        int bytes = sync.getBytes(StandardCharsets.UTF_8).length;
        assert bytes <= AcoFrame.MAX_PAYLOAD
            : "a full campaign no longer fits one GLOBAL_STATE_SYNC frame: " + bytes + " > " + AcoFrame.MAX_PAYLOAD;

        // The on-disk snapshot keeps the idempotency table as well; it must still be
        // a document this build can parse back to the same sizes.
        GlobalCampaignState back = CampaignPersistence.fromJson(CampaignPersistence.toJson(state));
        assert back.unlockedSectors.size() == AcoServer.MAX_UNLOCKED_SECTORS
            : "snapshot round trip lost unlocked sectors";
        assert back.techTree.size() == AcoServer.MAX_TECH_ENTRIES : "snapshot round trip lost tech entries";
        assert back.globalBank.size() == GlobalBank.MAX_BANK_ITEMS : "snapshot round trip lost bank entries";
    }

    private static void bankCapacity() throws Exception {
        try (Host host = new Host(state -> {
            for (int i = 0; i < GlobalBank.MAX_BANK_ITEMS; i++) state.globalBank.put("res-" + i, 1L);
        }); Socket socket = host.raw()) {
            socket.getOutputStream().write(rawFrame(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, 91,
                utf8("op-new|groundZero|1|brand-new-item|1|CREDIT")));
            socket.getOutputStream().flush();
            String refused = new String(readFrame(socket, 3000).payload(), StandardCharsets.UTF_8);
            assert refused.contains("BANK_CAPACITY") : "a new key at capacity must be refused, got " + refused;

            // An existing key still works: the cap is about table growth, not credits.
            socket.getOutputStream().write(rawFrame(AcoMessageType.GLOBAL_RESOURCE_REQUEST.id, 92,
                utf8("op-old|groundZero|1|res-0|4|CREDIT")));
            socket.getOutputStream().flush();
            String accepted = new String(readFrame(socket, 3000).payload(), StandardCharsets.UTF_8);
            assert accepted.startsWith("op-old|true|") : "crediting an existing key must still work, got " + accepted;

            GlobalCampaignState durable = host.durable();
            assert durable.globalBank.size() == GlobalBank.MAX_BANK_ITEMS
                : "the bank table must not grow past the cap, size=" + durable.globalBank.size();
            assert durable.globalBank.getOrDefault("res-0", 0L) == 5L
                : "the existing key must have been credited, got " + durable.globalBank.get("res-0");
            assert !durable.globalBank.containsKey("brand-new-item")
                : "the refused key must not appear in the durable table";
        }
    }

    private static void techCapacity() throws Exception {
        try (Host host = new Host(state -> {
            for (int i = 0; i < AcoServer.MAX_TECH_ENTRIES; i++) state.techTree.put("tech-" + i, true);
        }); Socket socket = host.raw()) {
            socket.getOutputStream().write(rawFrame(AcoMessageType.RESEARCH_REQUEST.id, 93, utf8("tech-new|op-1")));
            socket.getOutputStream().flush();
            String refused = new String(readFrame(socket, 3000).payload(), StandardCharsets.UTF_8);
            assert refused.contains("TECH_TABLE_FULL") : "a new tech at capacity must be refused, got " + refused;

            // Re-researching an existing node stays idempotent and grows nothing.
            socket.getOutputStream().write(rawFrame(AcoMessageType.RESEARCH_REQUEST.id, 94, utf8("tech-0|op-2")));
            socket.getOutputStream().flush();
            String accepted = new String(readFrame(socket, 3000).payload(), StandardCharsets.UTF_8);
            assert accepted.startsWith("tech-0|SUCCESS|") : "an existing tech must still answer SUCCESS, got " + accepted;

            GlobalCampaignState durable = host.durable();
            assert durable.techTree.size() == AcoServer.MAX_TECH_ENTRIES
                : "the tech table must not grow past the cap, size=" + durable.techTree.size();
            assert !durable.techTree.containsKey("tech-new") : "the refused tech must not be persisted";
        }
    }

    // ----------------------------------------------------------------- harness

    static final class Host implements AutoCloseable {
        final AcoController controller;
        final int port;
        final Path dir;

        Host() throws Exception {
            this(null);
        }

        Host(Consumer<GlobalCampaignState> seed) throws Exception {
            port = freePort();
            dir = Files.createTempDirectory("aco-proto-");
            if (seed != null) {
                GlobalCampaignState state = new GlobalCampaignState();
                state.campaignId = "protocol-test";
                state.globalRevision = 1;
                seed.accept(state);
                new CampaignPersistence(dir).save(state);
            }
            controller = new AcoController(dir.toString());
            assert controller.hostCampaign(port, port + 1)
                : "hostCampaign must bind on a free port";
        }

        GlobalCampaignState durable() throws IOException {
            return new CampaignPersistence(dir).recover();
        }

        Socket raw() throws IOException {
            Socket s = new Socket("127.0.0.1", port);
            s.setTcpNoDelay(true);
            return s;
        }

        @Override
        public void close() throws Exception {
            controller.dispose();
            deleteRecursively(dir);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Header written by hand so the codec's own writer is not under test here. */
    private static byte[] header(int version, int type, int msgId, int length) {
        ByteBuffer b = ByteBuffer.allocate(AcoFrame.HEADER_SIZE);
        b.put((byte) version).put((byte) type).putInt(msgId).putInt(length);
        return b.array();
    }

    private static byte[] rawFrame(int type, int msgId, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(AcoFrame.HEADER_SIZE + payload.length);
        b.put((byte) 1).put((byte) type).putInt(msgId).putInt(payload.length).put(payload);
        return b.array();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static AcoFrame readFrame(Socket socket, int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        InputStream in = socket.getInputStream();
        byte[] header = readN(in, AcoFrame.HEADER_SIZE);
        ByteBuffer b = ByteBuffer.wrap(header);
        byte version = b.get();
        byte type = b.get();
        int msgId = b.getInt();
        int length = b.getInt();
        if (length < 0 || length > AcoFrame.MAX_PAYLOAD) {
            throw new IOException("server declared an invalid payload length: " + length);
        }
        return new AcoFrame(version, type, msgId, readN(in, length));
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(out, off, n - off);
            if (read < 0) throw new EOFException("eof at " + off + "/" + n);
            off += read;
        }
        return out;
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}

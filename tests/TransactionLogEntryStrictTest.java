package tests;

import sc.aco.CampaignPersistence;
import sc.aco.TransactionLogEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Strict round-trip regression for {@link TransactionLogEntry}, the WAL record
 * reader.
 *
 * <p>The reader used to be a stub that returned an empty entry, so WAL replay
 * dropped every line and silently lost committed transactions. These tests pin
 * the strict contract: every serialized field parses and validates, escaped
 * strings survive, and an incomplete or malformed record throws instead of being
 * silently accepted.
 */
public class TransactionLogEntryStrictTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("TransactionLogEntryStrictTest");
        System.setProperty("arc.headless", "true");

        run("RoundTripThroughWalFile", TransactionLogEntryStrictTest::testRoundTripThroughWalFile);
        run("MissingFieldRejected", TransactionLogEntryStrictTest::testMissingFieldRejected);
        run("MalformedNumberAndBooleanRejected", TransactionLogEntryStrictTest::testMalformedNumberAndBooleanRejected);
        run("UnknownOperationTypeRejected", TransactionLogEntryStrictTest::testUnknownOperationTypeRejected);
        run("TrailingGarbageRejected", TransactionLogEntryStrictTest::testTrailingGarbageRejected);
        run("UnterminatedStringAndBadEscapeRejected", TransactionLogEntryStrictTest::testUnterminatedStringAndBadEscapeRejected);
        run("EmptyOperationIdRejected", TransactionLogEntryStrictTest::testEmptyOperationIdRejected);

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

    /** A complete, valid WAL line exactly as the serializer emits it. */
    private static final String VALID_LINE =
        "{\"operationId\":\"op-a\",\"globalRevision\":3,\"operationType\":\"CREDIT\",\"resourceType\":\"copper\","
        + "\"amount\":7,\"sessionId\":\"conn-1\",\"sectorId\":\"groundZero\",\"expectedGlobalRevision\":2,"
        + "\"success\":true,\"error\":\"\",\"newBalance\":107,\"timestamp\":1234567890}";

    private static void expectRejected(String json) {
        try {
            TransactionLogEntry parsed = TransactionLogEntry.fromJson(json);
            throw new AssertionError("malformed WAL record was accepted with operationId="
                + (parsed == null ? "<null>" : parsed.operationId));
        } catch (IllegalArgumentException expected) {
            // strict reader rejected the record — correct behavior
        }
    }

    private static void testRoundTripThroughWalFile() throws Exception {
        Path dir = Files.createTempDirectory("aco-wal-strict-");
        CampaignPersistence p = new CampaignPersistence(dir);
        TransactionLogEntry e = new TransactionLogEntry();
        e.operationId = "op-wal";
        e.globalRevision = 3;
        e.operationType = "CREDIT";
        e.resourceType = "copper";
        e.amount = 7;
        e.sessionId = "conn-1";
        e.sectorId = "groundZero";
        e.expectedGlobalRevision = 2;
        e.success = true;
        e.error = "tricky \"quoted\" back\\slash\nnewline\ttab";
        e.newBalance = 107;
        e.timestamp = 1234567890L;

        p.appendWAL(e);
        List<TransactionLogEntry> loaded = p.loadWAL();
        assert loaded.size() == 1 : "one WAL line must load as exactly one entry, got " + loaded.size();
        TransactionLogEntry r = loaded.get(0);
        assert "op-wal".equals(r.operationId) : "operationId round-trips";
        assert r.globalRevision == 3 : "globalRevision round-trips";
        assert "CREDIT".equals(r.operationType) : "operationType round-trips";
        assert "copper".equals(r.resourceType) : "resourceType round-trips";
        assert r.amount == 7 : "amount round-trips";
        assert "conn-1".equals(r.sessionId) : "sessionId round-trips";
        assert "groundZero".equals(r.sectorId) : "sectorId round-trips";
        assert r.expectedGlobalRevision == 2 : "expectedGlobalRevision round-trips";
        assert r.success : "success round-trips";
        assert "tricky \"quoted\" back\\slash\nnewline\ttab".equals(r.error)
            : "escaped error text round-trips, got " + r.error;
        assert r.newBalance == 107 : "newBalance round-trips";
        assert r.timestamp == 1234567890L : "timestamp round-trips";

        TransactionLogEntry d = TransactionLogEntry.fromJson(e.toJson());
        assert d.operationId.equals(e.operationId) && d.error.equals(e.error) && d.timestamp == e.timestamp
            : "toJson/fromJson round-trip must be lossless";
    }

    private static void testMissingFieldRejected() {
        expectRejected(VALID_LINE.replace("\"sessionId\":\"conn-1\",", ""));
        expectRejected(VALID_LINE.replace("\"timestamp\":1234567890", ""));
        expectRejected(VALID_LINE.replace("\"success\":true,", ""));
    }

    private static void testMalformedNumberAndBooleanRejected() {
        expectRejected(VALID_LINE.replace("\"amount\":7", "\"amount\":\"seven\""));
        expectRejected(VALID_LINE.replace("\"globalRevision\":3", "\"globalRevision\":3.5"));
        expectRejected(VALID_LINE.replace("\"success\":true", "\"success\":\"true\""));
        expectRejected(VALID_LINE.replace("\"success\":true", "\"success\":TRUE"));
        expectRejected(VALID_LINE.replace("\"timestamp\":1234567890", "\"timestamp\":12x"));
        // Type confusion: quoted values where a literal is required, and vice versa.
        expectRejected(VALID_LINE.replace("\"amount\":7", "\"amount\":\"7\""));
        expectRejected(VALID_LINE.replace("\"success\":true", "\"success\":\"false\""));
        expectRejected(VALID_LINE.replace("\"error\":\"\"", "\"error\":true"));
        // Duplicate keys must not silently overwrite.
        expectRejected(VALID_LINE.replace("\"timestamp\":1234567890", "\"timestamp\":1234567890,\"operationId\":\"op-a\""));
    }

    private static void testUnknownOperationTypeRejected() {
        expectRejected(VALID_LINE.replace("\"operationType\":\"CREDIT\"", "\"operationType\":\"TRANSFER\""));
        expectRejected(VALID_LINE.replace("\"operationType\":\"CREDIT\"", "\"operationType\":\"\""));
    }

    private static void testTrailingGarbageRejected() {
        expectRejected(VALID_LINE + " trailing");
        expectRejected(VALID_LINE + "}");
        expectRejected(VALID_LINE.substring(0, VALID_LINE.length() - 1));
    }

    private static void testUnterminatedStringAndBadEscapeRejected() {
        expectRejected(VALID_LINE.replace("\"error\":\"\"", "\"error\":\"oops"));
        expectRejected(VALID_LINE.replace("\"error\":\"\"", "\"error\":\"a\\qb\""));
        expectRejected(VALID_LINE.replace("\"error\":\"\"", "\"error\":\"ends with backslash\\\""));
    }

    private static void testEmptyOperationIdRejected() {
        expectRejected(VALID_LINE.replace("\"operationId\":\"op-a\"", "\"operationId\":\"\""));
    }
}

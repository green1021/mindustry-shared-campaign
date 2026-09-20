package sc.aco;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Migration Protocol Test - Standalone
 */
public class MigrationProtocolTest {

    public static void main(String[] args) throws Exception {
        System.setProperty("arc.headless", "true");
        
        int passed = 0;
        int failed = 0;
        
        try {
            testMigrationRequestPayload();
            passed++;
            System.out.println("[PASS] testMigrationRequestPayload");
        } catch (AssertionError e) {
            System.out.println("[FAIL] testMigrationRequestPayload: " + e.getMessage());
            failed++;
        }
        
        try {
            testMigrationIntegration();
            passed++;
            System.out.println("[PASS] testMigrationIntegration");
        } catch (AssertionError e) {
            System.out.println("[FAIL] testMigrationIntegration: " + e.getMessage());
            failed++;
        }
        
        System.out.println("\n=== SUMMARY ===");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        
        if (failed > 0) System.exit(1);
    }

    private static void testMigrationRequestPayload() throws Exception {
        MigrationRequestPayload req = new MigrationRequestPayload("camp1", "sess1", "mig1", "sectorA", "sectorB");
        byte[] encoded = req.toBytes();
        MigrationRequestPayload decoded = MigrationRequestPayload.decode(encoded);
        // Record fields are public, access via direct field access
        assert decoded.campaignId.equals("camp1") : "campaignId should match";
        assert decoded.sessionId.equals("sess1") : "sessionId should match";
        assert decoded.migrationId.equals("mig1") : "migrationId should match";
        assert decoded.source.equals("sectorA") : "source should match";
        assert decoded.target.equals("sectorB") : "target should match";
    }
    
    private static void testMigrationIntegration() throws Exception {
        // Create mock P2 manager and null SectorStore
        AcoP2Manager mockP2 = new AcoP2Manager();
        sc.SectorStore mockStore = null;
        AcoMigrationManager migrationManager = new AcoMigrationManager(mockP2, mockStore);
        
        GlobalCampaignState globalState = new GlobalCampaignState();
        globalState.campaignId = "test";
        
        SectorRegistry registry = new SectorRegistry();
        CampaignPersistence persistence = new CampaignPersistence(java.nio.file.Paths.get("/tmp/migration-test-" + System.currentTimeMillis()));
        GlobalBank bank = new GlobalBank(globalState, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(globalState, bank);
        CampaignAuthority authority = new CampaignAuthority(registry, persistence);
        
        AcoServer server = new AcoServer(6569, migrationManager, authority, registry, globalState, txManager);
        server.start();
        Thread.sleep(200);
        
        try {
            MigrationRequestPayload req = new MigrationRequestPayload("camp1", "sess-test", "mig-integration", "sectorA", "sectorB");
            AcoFrame frame = new AcoFrame((byte)1, AcoMessageType.SECTOR_MIGRATION_REQUEST.id, 1, req.toBytes());
            byte[] encoded = frame.encode();
            
            AcoFrame decoded = AcoFrame.decode(java.nio.ByteBuffer.wrap(encoded));
            assert decoded != null : "Migration frame should decode";
            assert decoded.type() == AcoMessageType.SECTOR_MIGRATION_REQUEST.id : "Frame type should be migration request";
            
            MigrationRequestPayload decodedPayload = MigrationRequestPayload.decode(decoded.payload());
            assert decodedPayload.campaignId.equals("camp1") : "Decoded campaignId";
            assert decodedPayload.sessionId.equals("sess-test") : "Decoded sessionId";
        } finally {
            server.stop();
        }
    }
}
package sc.aco;

import arc.util.Log;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.*;

/**
 * ACO Integration Test - Standalone live socket client/server integration test
 */
public class AcoIntegrationTest {

    public static void main(String[] args) throws Exception {
        System.setProperty("arc.headless", "true");
        
        int passed = 0;
        int failed = 0;
        
        System.out.println("[RUN] testPingPong");
        try {
            testPingPong();
            System.out.println("[PASS] testPingPong");
            passed++;
        } catch (AssertionError e) {
            System.out.println("[FAIL] testPingPong: " + e.getMessage());
            failed++;
        } catch (Exception e) {
            System.out.println("[ERROR] testPingPong: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
        
        System.out.println("\n=== SUMMARY ===");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testPingPong() throws Exception {
        int testPort = 6568;
        
        // 1. Set up dependencies for AcoServer
        GlobalCampaignState globalState = new GlobalCampaignState();
        globalState.campaignId = "test-campaign";
        
        SectorRegistry registry = new SectorRegistry();
        Path persistDir = Paths.get("/tmp/aco-pingpong-" + System.currentTimeMillis());
        CampaignPersistence persistence = new CampaignPersistence(persistDir);
        GlobalBank bank = new GlobalBank(globalState, persistence);
        GlobalTransactionManager txManager = new GlobalTransactionManager(globalState, bank);
        CampaignAuthority authority = new CampaignAuthority(registry, persistence);
        
        AcoP2Manager mockP2 = new AcoP2Manager();
        AcoMigrationManager migrationManager = new AcoMigrationManager(mockP2, null);
        
        AcoServer server = new AcoServer(testPort, migrationManager, authority, registry, globalState, txManager);
        server.start();
        Thread.sleep(150); // Wait for server socket bind
        
        Socket clientSocket = null;
        try {
            // 2. Connect client socket
            clientSocket = new Socket("127.0.0.1", testPort);
            clientSocket.setSoTimeout(3000);
            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();
            
            // 3. Send PING frame (version=1, type=PING, msgId=999, empty payload)
            AcoFrame pingFrame = new AcoFrame((byte)1, AcoMessageType.PING.id, 999, new byte[0]);
            out.write(pingFrame.encode());
            out.flush();
            
            // 4. Receive PONG response
            byte[] headerBuf = new byte[AcoFrame.HEADER_SIZE];
            int totalRead = 0;
            while (totalRead < AcoFrame.HEADER_SIZE) {
                int r = in.read(headerBuf, totalRead, AcoFrame.HEADER_SIZE - totalRead);
                if (r == -1) break;
                totalRead += r;
            }
            assert totalRead == AcoFrame.HEADER_SIZE : "Must receive complete AcoFrame header";
            
            ByteBuffer bb = ByteBuffer.wrap(headerBuf);
            AcoFrame responseFrame = AcoFrame.decode(bb);
            assert responseFrame != null : "Response frame must decode cleanly";
            assert responseFrame.type() == AcoMessageType.PONG.id : "Response type must be PONG (got " + responseFrame.type() + ")";
            assert responseFrame.msgId() == 999 : "Response msgId must match request msgId 999";
            assert responseFrame.payload().length == 0 : "PONG payload should be empty";
            
        } finally {
            if (clientSocket != null) {
                try { clientSocket.close(); } catch (IOException ignored) {}
            }
            server.stop();
        }
    }
}
package sc.aco;

import org.junit.Test;
import static org.junit.Assert.*;

public class AcoIntegrationTest {

    @Test
    public void testPingPong() throws Exception {
        AcoServer server = new AcoServer(6568);
        server.start();
        Thread.sleep(100); // Give server time to bind

        AcoClient client = new AcoClient("127.0.0.1", 6568);
        client.connect();
        client.sendPing(123);
        // Note: For full integration, we'd need a way to listen for the PONG
        // This is a minimal test verifying the server handles the frame without crashing.
    }
}

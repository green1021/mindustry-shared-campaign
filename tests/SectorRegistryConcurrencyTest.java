package tests;

import sc.aco.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SectorRegistryConcurrencyTest {
    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("SectorRegistryConcurrencyTest");
        
        SectorRegistry registry = new SectorRegistry();
        String sectorKey = "test-sector";
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final String sessionId = "session-" + i;
            executor.submit(() -> {
                try {
                    latch.await();
                    if (registry.claimSector(sectorKey, sessionId, 1)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assert successCount.get() == 1 : "Exactly one claimant should succeed (got " + successCount.get() + ")";
        System.out.println("[PASS] Atomic claiming check");
    }
}

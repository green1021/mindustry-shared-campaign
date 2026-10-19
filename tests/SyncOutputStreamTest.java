package sc.aco;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import tests.TestGuard;

/**
 * Proves the contract of {@link SyncOutputStream}: one connection's writes are
 * serialized, different connections are not serialized against each other, and a
 * failed or closing write cannot leave the stream in an ambiguous state.
 *
 * <p>Overlap is detected, not timed: each probe counts how many threads are inside
 * its {@code write} at once, and a bounded {@link CyclicBarrier} is used as a
 * witness. If the wrapper holds the monitor, the second writer can never arrive, so
 * the barrier times out and the overlap counter stays zero. If the wrapper were
 * removed, both writers meet at the barrier, it trips, and the counter increments -
 * so the negative control is a real observation rather than a sleep that happens to
 * be long enough.
 */
public final class SyncOutputStreamTest {

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("sc.aco.SyncOutputStreamTest");
        System.out.println("Running SyncOutputStreamTest");

        run("SingleStreamSerializesWriters", SyncOutputStreamTest::singleStreamSerializes);
        run("IndependentStreamsRunConcurrently", SyncOutputStreamTest::independentStreamsOverlap);
        run("FailedWriteDoesNotPoisonLock", SyncOutputStreamTest::failedWriteReleases);
        run("CloseWaitsForInFlightWrite", SyncOutputStreamTest::closeWaitsForWrite);

        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        if (failed != 0) System.exit(1);
    }

    // ------------------------------------------------------------------ scenarios

    private static void singleStreamSerializes() throws Exception {
        MonitorProbe probe = new MonitorProbe();
        OutputStream out = SyncOutputStream.wrap(probe);
        probe.wrapper = out;

        int threads = 6;
        int perThread = 25;
        Thread[] writers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            String payload = "frame-" + i + "-";
            writers[i] = new Thread(() -> {
                for (int n = 0; n < perThread; n++) {
                    try {
                        out.write(payload.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }, "writer-" + i);
        }
        for (Thread t : writers) t.start();
        for (Thread t : writers) t.join(30_000);
        for (Thread t : writers) assert !t.isAlive() : "a writer thread never finished";

        // The decisive check is inside the delegate: every write must execute while
        // holding the wrapper's monitor. The same monitor on every call is what makes
        // mutual exclusion a property rather than a hope; a missing lock shows up as
        // monitorMisses > 0 instead of as an interleaving that may not reproduce.
        assert probe.monitorMisses.get() == 0
            : probe.monitorMisses.get() + " delegate writes ran without holding the "
                + "per-connection monitor; concurrent frames could interleave";
        assert probe.overlap.get() == 0
            : "two writers were inside the delegate at once: " + probe.overlap.get();
        assert probe.writes.get() == threads * perThread
            : "delegate saw " + probe.writes.get() + " writes, expected " + (threads * perThread);

        String text = probe.text();
        assert text.length() == threads * perThread * "frame-0-".length()
            : "bytes were lost or duplicated: " + text.length();
        for (int i = 0; i < threads; i++) {
            String frame = "frame-" + i + "-";
            int seen = 0;
            for (int at = text.indexOf(frame); at >= 0; at = text.indexOf(frame, at + 1)) seen++;
            assert seen == perThread
                : frame + " appears " + seen + " times, expected " + perThread;
        }
    }

    private static void independentStreamsOverlap() throws Exception {
        CyclicBarrier shared = new CyclicBarrier(2);
        GateProbe first = new GateProbe(shared);
        GateProbe second = new GateProbe(shared);

        Thread a = writer(SyncOutputStream.wrap(first), "first");
        Thread b = writer(SyncOutputStream.wrap(second), "second");
        a.start();
        b.start();
        a.join(20_000);
        b.join(20_000);
        assert !a.isAlive() && !b.isAlive() : "a writer thread never finished";

        assert first.trips.get() == 1 && second.trips.get() == 1
            : "the two connections never wrote at the same time (trips=" + first.trips.get()
                + "/" + second.trips.get() + "): a lock is being shared across connections";
        assert first.text().equals("first") && second.text().equals("second")
            : "a write landed on the wrong stream: " + first.text() + "/" + second.text();
    }

    private static void failedWriteReleases() throws Exception {
        FailingProbe probe = new FailingProbe();
        OutputStream out = SyncOutputStream.wrap(probe);

        boolean threw = false;
        try {
            out.write("boom".getBytes(StandardCharsets.UTF_8));
        } catch (IOException expected) {
            threw = true;
        }
        assert threw : "the delegate's IOException was swallowed instead of reaching the caller";
        out.write("ok".getBytes(StandardCharsets.UTF_8));
        assert probe.text().equals("ok")
            : "the monitor stayed held after a failed write; delegate saw: " + probe.text();
    }

    private static void closeWaitsForWrite() throws Exception {
        GatedProbe probe = new GatedProbe();
        OutputStream out = SyncOutputStream.wrap(probe);

        Thread w = writer(out, "DATA");
        w.start();
        assert probe.inWrite.await(10, TimeUnit.SECONDS) : "the write never reached the delegate";
        Thread c = new Thread(() -> {
            try {
                out.close();
            } catch (IOException ignored) {
                // the assertion below is about ordering, not about close() throwing
            }
        }, "closer");
        c.start();
        probe.release.countDown();
        w.join(20_000);
        c.join(20_000);
        assert !w.isAlive() && !c.isAlive() : "write/close threads did not finish";
        assert !probe.closedDuringWrite
            : "close() ran while the write was still inside the delegate; the stream was "
                + "closed under an in-flight frame";
        assert probe.text().equals("DATA") : "the frame was truncated by close(): " + probe.text();
        assert probe.closeCount.get() == 1 : "close() must reach the delegate exactly once";
    }

    // -------------------------------------------------------------------- helpers

    private static Thread writer(OutputStream out, String payload) {
        return new Thread(() -> {
            try {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, "writer-" + payload);
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void run(String name, Check check) {
        try {
            check.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("[FAIL] " + name + ": " + t);
            t.printStackTrace(System.out);
        }
    }

    private static int passed;
    private static int failed;

    // --------------------------------------------------------------------- probes

    /** Asserts, from inside the delegate, that the wrapper's monitor is held. */
    private static final class MonitorProbe extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger overlap = new AtomicInteger();
        final AtomicInteger monitorMisses = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        volatile OutputStream wrapper;

        @Override
        public void write(int b) {
            throw new UnsupportedOperationException("the server always writes whole frames");
        }

        @Override
        public void write(byte[] b, int off, int len) {
            OutputStream w = wrapper;
            if (w == null || !Thread.holdsLock(w)) monitorMisses.incrementAndGet();
            if (inside.incrementAndGet() > 1) overlap.incrementAndGet();
            try {
                bytes.write(b, off, len);
                writes.incrementAndGet();
            } finally {
                inside.decrementAndGet();
            }
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    /** Separate instance per connection; both must be inside their own write at once. */
    private static final class GateProbe extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CyclicBarrier barrier;
        final AtomicInteger trips = new AtomicInteger();

        GateProbe(CyclicBarrier barrier) {
            this.barrier = barrier;
        }

        @Override
        public void write(int b) {
            throw new UnsupportedOperationException("the server always writes whole frames");
        }

        @Override
        public void write(byte[] b, int off, int len) {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                trips.incrementAndGet();
            } catch (Exception e) {
                throw new IllegalStateException("independent connections were serialized: " + e, e);
            }
            bytes.write(b, off, len);
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class FailingProbe extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean failed;

        @Override
        public void write(int b) {
            throw new UnsupportedOperationException("the server always writes whole frames");
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (!failed) {
                failed = true;
                throw new IOException("simulated socket failure");
            }
            bytes.write(b, off, len);
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class GatedProbe extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final CountDownLatch inWrite = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger closeCount = new AtomicInteger();
        volatile boolean writeFinished;
        volatile boolean closedDuringWrite;

        @Override
        public void write(int b) {
            throw new UnsupportedOperationException("the server always writes whole frames");
        }

        @Override
        public void write(byte[] b, int off, int len) {
            inWrite.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            bytes.write(b, off, len);
            writeFinished = true;
        }

        @Override
        public void close() {
            if (!writeFinished) closedDuringWrite = true;
            closeCount.incrementAndGet();
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}

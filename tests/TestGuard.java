package tests;

/**
 * Guards the canonical suite against the false-pass failure mode: these tests use
 * the Java {@code assert} keyword, which the JVM silently ignores unless it is
 * started with {@code -ea}. A suite that reports "all passed" with assertions
 * disabled is worse than a failing suite, because it looks like evidence.
 *
 * Every assert-using entry point must call {@link #assertEnabled(String)} as its
 * first statement, so a runner that forgets {@code -ea} fails loudly instead of
 * passing vacuously.
 */
public final class TestGuard {

    private TestGuard() {
    }

    /**
     * Throws unless the JVM was started with assertions enabled.
     *
     * @param testName entry point being guarded, used in the error message
     */
    public static void assertEnabled(String testName) {
        boolean enabled = false;
        // Only executes when -ea is active; otherwise the flag stays false.
        assert enabled = true;
        if (!enabled) {
            throw new IllegalStateException(
                testName + ": Java assertions are DISABLED. This test would pass "
                + "vacuously. Run the JVM with -ea (see scripts/run_canonical_tests.py).");
        }
    }
}

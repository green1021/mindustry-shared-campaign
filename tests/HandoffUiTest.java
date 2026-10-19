package tests;

import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.graphics.g3d.PlanetGrid;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.type.SectorPreset;
import sc.ActionResult;
import sc.SharedCampaignUI;
import sc.aco.AcoController;
import sc.aco.ActionDispatch;
import sc.aco.SectorKeyResolver;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless verification of the Takeover / Retry Takeover control logic and of
 * the asynchronous action path those buttons use.
 *
 * <p>What this proves: the UI-facing guards, the phase-to-outcome mapping, the
 * canonical sector key for preset and non-preset sectors, and that every async
 * dispatch terminates exactly once off the caller's thread (success, exception,
 * rejection, no executor). What it does not prove: that the widgets render, or
 * that a real graphical client completes a handoff - see the manual two-machine
 * procedure in reports/full-audit-2026-10.md.
 */
public class HandoffUiTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        TestGuard.assertEnabled("HandoffUiTest");
        System.setProperty("arc.headless", "true");

        run("NonPresetSectorKey", HandoffUiTest::testNonPresetSectorKey);
        run("PresetSectorKey", HandoffUiTest::testPresetSectorKey);
        run("GuardRejectsMissingSector", HandoffUiTest::testGuardRejectsMissingSector);
        run("GuardRejectsUnsafeKey", HandoffUiTest::testGuardRejectsUnsafeKey);
        run("GuardRejectsNonHost", HandoffUiTest::testGuardRejectsNonHost);
        run("GuardRetryRequiresFailedHandoff", HandoffUiTest::testGuardRetryRequiresFailedHandoff);
        run("ReadyRequiresHostAndSelection", HandoffUiTest::testReadyRequiresHostAndSelection);
        run("ReadyReflectsBusyAndRetryability", HandoffUiTest::testReadyReflectsBusyAndRetryability);
        run("OutcomeOnlyServingIsSuccess", HandoffUiTest::testOutcomeOnlyServingIsSuccess);
        run("OutcomeTimeoutIsFailure", HandoffUiTest::testOutcomeTimeoutIsFailure);
        run("DispatchRunsOffThreadOnce", HandoffUiTest::testDispatchRunsOffThreadOnce);
        run("DispatchTaskExceptionTerminates", HandoffUiTest::testDispatchTaskExceptionTerminates);
        run("DispatchRejectionTerminates", HandoffUiTest::testDispatchRejectionTerminates);
        run("ControllerActionsAreAsyncTerminal", HandoffUiTest::testControllerActionsAreAsyncTerminal);

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

    // --- fixtures -----------------------------------------------------------

    /** Content registry is required by Planet's constructor; no assets needed. */
    private static void ensureContent() {
        Vars.headless = true;
        if (Vars.content == null) {
            Vars.content = new ContentLoader();
        }
    }

    /** Planet names are unique per content registry, so each fixture gets its own. */
    private static int planetSeq = 0;

    private static String planetName(String prefix) {
        return prefix + "-" + (++planetSeq);
    }

    private static Sector sector(String planetName, int tileIndex) {
        ensureContent();
        Planet planet = new Planet(planetName, null, 1f);
        PlanetGrid grid = PlanetGrid.create(3);
        return new Sector(planet, grid.tiles[tileIndex]);
    }

    /**
     * A preset sector without running SectorPreset's constructor: the ctor
     * resolves the sector from the planet's generated grid, which a synthetic
     * planet does not have. SectorKeyResolver only reads preset.name, so an
     * allocated instance with that one field set exercises the real branch.
     */
    private static Sector presetSector(String planetName, String presetName, int tileIndex) throws Exception {
        Sector sector = sector(planetName, tileIndex);
        sun.misc.Unsafe unsafe;
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        unsafe = (sun.misc.Unsafe) theUnsafe.get(null);

        SectorPreset preset = (SectorPreset) unsafe.allocateInstance(SectorPreset.class);
        Field nameField = null;
        for (Class<?> c = SectorPreset.class; c != null && nameField == null; c = c.getSuperclass()) {
            try {
                nameField = c.getDeclaredField("name");
            } catch (NoSuchFieldException ignored) {
            }
        }
        if (nameField == null) throw new IllegalStateException("Mindustry content 'name' field not found");
        nameField.setAccessible(true);
        nameField.set(preset, presetName);

        sector.preset = preset;
        return sector;
    }

    // --- sector key consistency --------------------------------------------

    private static void testNonPresetSectorKey() {
        Sector sector = sector(planetName("testplanet"), 7);
        String key = SectorKeyResolver.keyOf(sector);
        assert key.equals(sector.planet.name + ":" + sector.id)
                : "non-preset key must be planet:id, got " + key;
        assert key.indexOf('|') < 0 && key.indexOf('\n') < 0
                : "sector key must not contain protocol separators, got " + key;
        assert SharedCampaignUI.validateHandoffTarget(key, true, false, false) == null
                : "canonical non-preset key must be dispatchable";
    }

    private static void testPresetSectorKey() throws Exception {
        Sector sector = presetSector(planetName("presetplanet"), "groundZero", 3);
        String key = SectorKeyResolver.keyOf(sector);
        assert key.equals("groundZero") : "preset key must be the preset name, got " + key;
        assert SharedCampaignUI.validateHandoffTarget(key, true, false, false) == null
                : "canonical preset key must be dispatchable";
        // Both key shapes must survive the same validation path: the UI must not
        // apply a different rule to preset vs non-preset sectors.
        assert SharedCampaignUI.validateHandoffTarget(
                        SectorKeyResolver.keyOf(sector(planetName("plainplanet"), 2)), true, false, false) == null
                : "non-preset key must pass the same guard as a preset key";
    }

    // --- guards -------------------------------------------------------------

    private static void testGuardRejectsMissingSector() {
        assert "NO_SECTOR_SELECTED".equals(SharedCampaignUI.validateHandoffTarget(null, true, false, false))
                : "null sector must be refused";
        assert "NO_SECTOR_SELECTED".equals(SharedCampaignUI.validateHandoffTarget("", true, false, false))
                : "empty sector must be refused";
        assert "NO_SECTOR_SELECTED".equals(SharedCampaignUI.validateHandoffTarget("   ", true, false, false))
                : "blank sector must be refused";
        assert SharedCampaignUI.handoffRefusalMessage("NO_SECTOR_SELECTED", null).contains("Select a sector")
                : "refusal must tell the player to select a sector";
    }

    private static void testGuardRejectsUnsafeKey() {
        assert "INVALID_SECTOR_KEY".equals(SharedCampaignUI.validateHandoffTarget("a|b", true, false, false))
                : "key with a pipe must be refused";
        assert "INVALID_SECTOR_KEY".equals(SharedCampaignUI.validateHandoffTarget("a\nb", true, false, false))
                : "key with a newline must be refused";
        String longKey = "x".repeat(65);
        assert "INVALID_SECTOR_KEY".equals(SharedCampaignUI.validateHandoffTarget(longKey, true, false, false))
                : "over-long key must be refused";
    }

    private static void testGuardRejectsNonHost() {
        assert "NOT_HOSTING".equals(SharedCampaignUI.validateHandoffTarget("serpulo:5", false, false, false))
                : "takeover must be refused when not hosting";
        assert "NOT_HOSTING".equals(SharedCampaignUI.validateHandoffTarget("serpulo:5", false, true, true))
                : "retry must be refused when not hosting";
        assert SharedCampaignUI.handoffRefusalMessage("NOT_HOSTING", "serpulo:5").contains("campaign host")
                : "refusal must explain the host requirement";
    }

    private static void testGuardRetryRequiresFailedHandoff() {
        // Retry must not start an unrelated takeover: it needs a parked failure.
        assert "NOT_RETRYABLE".equals(SharedCampaignUI.validateHandoffTarget("serpulo:5", true, false, true))
                : "retry without a failed handoff must be refused";
        assert SharedCampaignUI.validateHandoffTarget("serpulo:5", true, true, true) == null
                : "retry after a failed handoff must be allowed";
        assert SharedCampaignUI.validateHandoffTarget("serpulo:5", true, false, false) == null
                : "takeover must not require a prior failure";
        assert SharedCampaignUI.validateHandoffTarget("serpulo:5", true, true, false) == null
                : "takeover must not be blocked by a retryable state";
    }

    private static void testReadyRequiresHostAndSelection() {
        // Button disabled-state mirrors the guard, so it cannot look clickable when
        // the action would be refused.
        assert !SharedCampaignUI.handoffReady(null, true, false, false, false)
                : "no selection must leave takeover disabled";
        assert !SharedCampaignUI.handoffReady("serpulo:5", false, false, false, false)
                : "a non-host must see takeover disabled";
        assert SharedCampaignUI.handoffReady("serpulo:5", true, false, false, false)
                : "host with a valid selection enables takeover";
    }

    private static void testReadyReflectsBusyAndRetryability() {
        assert !SharedCampaignUI.handoffReady("serpulo:5", true, true, true, false)
                : "an in-flight control action must disable takeover";
        assert !SharedCampaignUI.handoffReady("serpulo:5", true, false, false, true)
                : "retry must be disabled without a failed handoff";
        assert SharedCampaignUI.handoffReady("serpulo:5", true, true, false, true)
                : "a failed handoff must enable retry";
        assert SharedCampaignUI.handoffReady("serpulo:5", true, false, false, false)
                : "a failed state must not disable takeover";
    }

    // --- outcome mapping ----------------------------------------------------

    private static void testOutcomeOnlyServingIsSuccess() {
        assert SharedCampaignUI.handoffOutcome("SERVING", null, false).success()
                : "SERVING is the only success phase";
        assert !SharedCampaignUI.handoffOutcome("FAILED", "SNAPSHOT_MISSING", false).success()
                : "FAILED must not report success";
        assert SharedCampaignUI.handoffOutcome("FAILED", "SNAPSHOT_MISSING", false).message().contains("SNAPSHOT_MISSING")
                : "FAILED must surface the authority's reason";
        assert !SharedCampaignUI.handoffOutcome("ABORTED", "OWNER_TIMEOUT", false).success()
                : "ABORTED must not report success";
        assert SharedCampaignUI.handoffOutcome("NONE", null, false) == null
                : "NONE without a local timeout is not terminal";
        assert SharedCampaignUI.handoffOutcome("AWAIT_SNAPSHOT", null, false) == null
                : "AWAIT_SNAPSHOT is still in flight";
        assert SharedCampaignUI.handoffOutcome("LOADING", null, false) == null
                : "LOADING is still in flight";
    }

    private static void testOutcomeTimeoutIsFailure() {
        assert !SharedCampaignUI.handoffOutcome("LOADING", null, true).success()
                : "a timed-out LOADING must not report success";
        assert SharedCampaignUI.handoffOutcome("LOADING", null, true).message().contains("timed out")
                : "timeout must say so";
        assert !SharedCampaignUI.handoffOutcome("NONE", null, true).success()
                : "a handoff that never registered must not report success";
    }

    // --- async dispatch -----------------------------------------------------

    private static ActionResult await(CountDownLatch latch, AtomicReference<ActionResult> ref) throws Exception {
        assert latch.await(10, TimeUnit.SECONDS) : "callback was never invoked";
        assert ref.get() != null : "callback received null";
        return ref.get();
    }

    private static void testDispatchRunsOffThreadOnce() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<ActionResult> ref = new AtomicReference<>();
            AtomicReference<Thread> onThread = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            Thread caller = Thread.currentThread();

            ActionDispatch.submit(ex, "test", ActionResult::ok, r -> {
                calls.incrementAndGet();
                onThread.set(Thread.currentThread());
                ref.set(r);
                latch.countDown();
            });

            ActionResult result = await(latch, ref);
            assert result.success() : "ok task must yield success";
            assert onThread.get() != caller : "action must not run on the calling (game/UI) thread";
            Thread.sleep(100);
            assert calls.get() == 1 : "callback must fire exactly once, observed " + calls.get();
        } finally {
            ex.shutdownNow();
        }
    }

    private static void testDispatchTaskExceptionTerminates() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<ActionResult> ref = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();

            ActionDispatch.submit(ex, "boom", () -> {
                throw new IllegalStateException("intentional");
            }, r -> {
                calls.incrementAndGet();
                ref.set(r);
                latch.countDown();
            });

            ActionResult result = await(latch, ref);
            assert !result.success() : "a throwing task must not report success";
            assert result.reason().equals("EXCEPTION:IllegalStateException")
                    : "exception result must name the type, got " + result.reason();
            Thread.sleep(100);
            assert calls.get() == 1 : "exception must still terminate exactly once, observed " + calls.get();
        } finally {
            ex.shutdownNow();
        }
    }

    private static void testDispatchRejectionTerminates() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        ex.shutdownNow();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ActionResult> ref = new AtomicReference<>();
        ActionDispatch.submit(ex, "rejected", ActionResult::ok, r -> {
            ref.set(r);
            latch.countDown();
        });

        ActionResult result = await(latch, ref);
        assert !result.success() : "rejected task must not report success";
        assert "REJECTED".equals(result.reason()) : "rejection reason expected, got " + result.reason();

        // No executor at all (disposed controller) must also terminate.
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicReference<ActionResult> ref2 = new AtomicReference<>();
        ActionDispatch.submit(null, "no-executor", ActionResult::ok, r -> {
            ref2.set(r);
            latch2.countDown();
        });
        ActionResult result2 = await(latch2, ref2);
        assert "NO_EXECUTOR".equals(result2.reason()) : "missing executor reason expected, got " + result2.reason();
    }

    /**
     * The real controller path the buttons call: a dispatched action completes
     * once, off the caller's thread, with a real failure reason instead of
     * hanging or reporting success.
     */
    private static void testControllerActionsAreAsyncTerminal() throws Exception {
        Path dir = Files.createTempDirectory("handoff-ui-");
        AcoController controller = new AcoController(dir.toString());
        try {
            controller.init();
            assert !controller.isHosting() : "inert controller must not be hosting";

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<ActionResult> ref = new AtomicReference<>();
            AtomicReference<Thread> onThread = new AtomicReference<>();
            Thread caller = Thread.currentThread();

            controller.checkpointLocalSectorAction(r -> {
                onThread.set(Thread.currentThread());
                ref.set(r);
                latch.countDown();
            });
            ActionResult checkpoint = await(latch, ref);
            assert !checkpoint.success() : "checkpoint without an active sector must fail";
            assert "NO_SESSION".equals(checkpoint.reason()) : "expected NO_SESSION, got " + checkpoint.reason();
            assert onThread.get() != caller : "checkpoint must run off the game thread";

            CountDownLatch latch2 = new CountDownLatch(1);
            AtomicReference<ActionResult> ref2 = new AtomicReference<>();
            controller.requestHandoffAction("serpulo:5", r -> {
                ref2.set(r);
                latch2.countDown();
            });
            ActionResult takeover = await(latch2, ref2);
            assert !takeover.success() : "takeover while not hosting must fail";
            assert "NOT_HOSTING".equals(takeover.reason()) : "expected NOT_HOSTING, got " + takeover.reason();

            assert !controller.isHandoffRetryable("serpulo:5")
                    : "nothing is retryable before a failed handoff";
        } finally {
            controller.dispose();
            deleteRecursively(dir);
        }

        // After dispose the executor is gone: a late click must still terminate.
        CountDownLatch latch3 = new CountDownLatch(1);
        AtomicReference<ActionResult> ref3 = new AtomicReference<>();
        controller.checkpointLocalSectorAction(r -> {
            ref3.set(r);
            latch3.countDown();
        });
        ActionResult afterDispose = await(latch3, ref3);
        assert !afterDispose.success() : "post-dispose dispatch must not report success";
        assert "NO_EXECUTOR".equals(afterDispose.reason())
                : "post-dispose dispatch must terminate as NO_EXECUTOR, got " + afterDispose.reason();
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

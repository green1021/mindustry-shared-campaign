package sc;

/**
 * Headless-testable seam between the campaign UI and the ACO control plane.
 *
 * <p>The real implementation is {@code sc.aco.AcoController}. Tests substitute a
 * recording stub so UI wiring (which dialog button calls which action, with
 * which parsed parameters) can be verified without a display.
 */
public interface CampaignActions {
    /** Start a local campaign authority plus a P2 sector host. */
    void hostCampaignAction(int syncPort, int sectorPort);

    /** Start a local campaign authority plus a P2 sector host with explicit advertised address. */
    default void hostCampaignAction(int syncPort, int sectorPort, String advertisedHost) {
        hostCampaignAction(syncPort, sectorPort);
    }

    /** Join a remote campaign authority at the given address. */
    void connectAction(String host, int port);

    /** Claim lease on the selected sector and enter/load local gameplay. */
    default void claimSectorAction(String sectorKey, java.util.function.Consumer<ActionResult> callback) {
        callback.accept(claimSectorAction(sectorKey) ? ActionResult.ok() : ActionResult.fail("Failed to claim"));
    }

    /** Claim lease on the selected sector and enter/load local gameplay. */
    default boolean claimSectorAction(String sectorKey) {
        return false;
    }

    /** Enter a sector locally (equivalent to claim & load). */
    default void enterLocalSectorAction(String sectorKey, java.util.function.Consumer<ActionResult> callback) {
        callback.accept(enterLocalSectorAction(sectorKey) ? ActionResult.ok() : ActionResult.fail("Failed to enter"));
    }

    /** Enter a sector locally (equivalent to claim & load). */
    default boolean enterLocalSectorAction(String sectorKey) {
        return claimSectorAction(sectorKey);
    }

    /** Save an authoritative checkpoint snapshot for the currently active sector. */
    default boolean checkpointLocalSectorAction() {
        return false;
    }

    /** Save checkpoint and exit the local sector back to menu / planet map. */
    default boolean exitLocalSectorAction() {
        return false;
    }

    /** Host requests takeover / handoff of a sector. */
    default boolean requestHandoffAction(String sectorKey) {
        return false;
    }

    /** Host retries takeover / handoff of a sector. */
    default boolean retryHandoffAction(String sectorKey) {
        return false;
    }

    // --- Asynchronous control-plane actions ----------------------------------
    //
    // The boolean methods above are dispatch-only: `true` means the request was
    // accepted for sending, never that the handoff/checkpoint actually completed.
    // UI code must use the callback variants below, which run the (potentially
    // blocking) work off the game thread and invoke the callback exactly once.
    // The defaults execute inline so headless stubs stay simple.

    /** True when this process runs the campaign authority (host role). */
    default boolean isHosting() {
        return false;
    }

    /** Handoff phase for a sector: NONE/AWAIT_SNAPSHOT/AWAIT_RELEASE/LOADING/SERVING/FAILED/ABORTED. */
    default String handoffPhase(String sectorKey) {
        return "NONE";
    }

    /** Last recorded failure reason for that sector's handoff, or null. */
    default String handoffFailure(String sectorKey) {
        return null;
    }

    /** True when a failed handoff left the sector parked and safe to retry. */
    default boolean isHandoffRetryable(String sectorKey) {
        return false;
    }

    /** Host: request takeover; callback receives the dispatch result, off the game thread. */
    default void requestHandoffAction(String sectorKey, java.util.function.Consumer<ActionResult> callback) {
        dispatchInline("takeover", () -> requestHandoffAction(sectorKey), callback);
    }

    /** Host: retry a failed takeover; callback receives the real load result. */
    default void retryHandoffAction(String sectorKey, java.util.function.Consumer<ActionResult> callback) {
        dispatchInline("retry-takeover", () -> retryHandoffAction(sectorKey), callback);
    }

    /** Save a checkpoint asynchronously; callback carries the committed/failed result. */
    default void checkpointLocalSectorAction(java.util.function.Consumer<ActionResult> callback) {
        dispatchInline("checkpoint", () -> checkpointLocalSectorAction(), callback);
    }

    /** Save, leave the world and release the lease asynchronously. */
    default void exitLocalSectorAction(java.util.function.Consumer<ActionResult> callback) {
        dispatchInline("exit", () -> exitLocalSectorAction(), callback);
    }

    /** Inline fallback for headless stubs. Always terminates the callback exactly once. */
    static void dispatchInline(String what, java.util.function.BooleanSupplier op,
                               java.util.function.Consumer<ActionResult> callback) {
        if (callback == null) return;
        ActionResult result;
        try {
            result = op.getAsBoolean() ? ActionResult.ok() : ActionResult.fail(what + " refused");
        } catch (RuntimeException | Error e) {
            result = ActionResult.fail("EXCEPTION:" + e.getClass().getSimpleName());
        }
        callback.accept(result);
    }

    /** Returns active controller status string or empty if unbound. */
    default String getStatusText() {
        return "unbound";
    }

    /** Check if connected to an authority or hosting. */
    default boolean isOnline() {
        return false;
    }

    /** Returns currently advertised host address or null if unconfigured / loopback. */
    default String getAdvertisedHost() {
        return null;
    }
}

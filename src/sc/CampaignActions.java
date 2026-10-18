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

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

    /** Join a remote campaign authority at the given address. */
    void connectAction(String host, int port);
}

package sc.aco;

/**
 * Game-runtime operations the campaign authority depends on.
 *
 * <p>{@link SectorRuntimeAdapter} is the production implementation and requires a
 * live Mindustry runtime (world/content/NetServer). Tests substitute a headless
 * stub so the ownership and routing lifecycle can be exercised without a bound
 * game socket — the P2 bind itself is the environment-blocked piece on a
 * display-less host.
 */
public interface SectorRuntime {
    /** Load the sector world and its authoritative state. */
    boolean initializeSector(String sectorKey, String revision) throws Exception;

    /** Start the P2 host on the given port. Returns true when it is listening. */
    boolean startP2(int port);

    /** Stop the P2 host. */
    void stopP2();
}

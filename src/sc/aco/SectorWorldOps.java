package sc.aco;

/**
 * The owner machine's actual sector world operations.
 *
 * <p>Production implementation ({@link GameSectorWorld}) runs its engine work on the
 * Mindustry game thread; test implementations simulate it. Honest failure is
 * required: {@code saveWorld} returning false aborts a handoff before any ownership
 * change, so it must never claim success unless a validated snapshot was committed.
 */
public interface SectorWorldOps {

    /** Load the stored snapshot for the sector as the process's single active world. */
    boolean loadWorld(String sectorKey, boolean allowBootstrap);

    /**
     * Save the currently running sector world as a full validated snapshot at
     * {@code revision} (committed into the local campaign store).
     */
    boolean saveWorld(String sectorKey, long revision);

    /** Leave the local world (back to the planet/menu); the sector is no longer simulated here. */
    boolean leaveWorld();
}

package sc.aco;

import arc.Core;
import arc.files.Fi;
import arc.struct.StringMap;
import arc.util.Log;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.SectorInfo;
import mindustry.io.JsonIO;
import mindustry.io.SaveIO;
import mindustry.io.SaveMeta;
import mindustry.io.SaveOptions;
import mindustry.type.Planet;
import mindustry.type.Sector;

/**
 * Production {@link SectorWorldOps}: runs the sector world on this (owner) machine.
 *
 * <p>All engine work is scheduled onto the game thread via {@code Core.app.post}
 * and the caller blocks (bounded) for the result -- Mindustry's {@code Vars.world},
 * {@code Vars.state} and {@code Vars.logic} are process-global and thread-affine.
 * These methods must NOT be called from the game thread itself.
 *
 * <p>Snapshots use the same on-disk format as {@code sc.SectorStore}: a real
 * {@code SaveIO.write} whose tags carry {@code sc-sector-info}, {@code sc-campaign},
 * {@code sc-sector} and {@code sc-revision}, so the authority's
 * {@link CampaignSectorStore.SaveMetaValidator} can verify campaign+sector+revision
 * on the actual bytes before committing.
 */
public class GameSectorWorld implements SectorWorldOps {

    private static final long GAME_THREAD_TIMEOUT_MS = 60_000;

    private final CampaignSectorStore store;
    private final String campaignId;
    private final Path scratchDir;

    public GameSectorWorld(CampaignSectorStore store, String campaignId, Path scratchDir) {
        this.store = store;
        this.campaignId = campaignId;
        this.scratchDir = scratchDir;
    }

    @Override
    public boolean loadWorld(String sectorKey, boolean allowBootstrap) {
        byte[] bytes;
        try {
            bytes = store.readSnapshot(sectorKey);
        } catch (RuntimeException e) {
            Log.err("SECTOR_LOAD_READ_FAILED sector=@", sectorKey, e);
            return false;
        }
        if (bytes == null) {
            if (!allowBootstrap) {
                Log.warn("SECTOR_LOAD_MISSING_SNAPSHOT_REFUSED sector=@", sectorKey);
                return false;
            }
            Log.info("SECTOR_BOOTSTRAP_FIRST_ENTRY sector=@", sectorKey);
            return runOnGameThread(() -> bootstrapVanillaSector(sectorKey));
        }
        try {
            Files.createDirectories(scratchDir);
            Path tmp = Files.createTempFile(scratchDir, "load-", ".msav");
            Files.write(tmp, bytes);
            boolean ok = runOnGameThread(() -> loadIntoGame(tmp, sectorKey));
            Files.deleteIfExists(tmp);
            return ok;
        } catch (Exception e) {
            Log.err("SECTOR_LOAD_FAILED sector=@", sectorKey, e);
            return false;
        }
    }

    @Override
    public boolean saveWorld(String sectorKey, long revision) {
        return runOnGameThread(() -> {
            if (!Vars.state.isCampaign()) return false;
            Sector s = Vars.state.getSector();
            if (s == null || !sectorMatches(s, sectorKey)) return false;
            if (Vars.state.rules.defaultTeam.core() == null) return false;
            s.info.prepare(s);
            Files.createDirectories(scratchDir);
            Path tmp = Files.createTempFile(scratchDir, "save-", ".msav");
            try {
                SaveOptions options = new SaveOptions();
                options.extraTags = StringMap.of(
                        "sc-sector-info", JsonIO.write(s.info),
                        "sc-campaign", campaignId,
                        "sc-sector", sectorKey,
                        "sc-revision", String.valueOf(revision));
                SaveIO.write(new Fi(tmp.toFile()), options);
                byte[] bytes = Files.readAllBytes(tmp);
                store.commitSnapshot(sectorKey, revision, bytes, null);
                Log.info("SECTOR_SAVE_COMMITTED sector=@ revision=@ bytes=@", sectorKey, revision, bytes.length);
                return true;
            } finally {
                Files.deleteIfExists(tmp);
            }
        });
    }

    @Override
    public boolean leaveWorld() {
        Boolean res = runOnGameThread(() -> {
            if (Vars.state != null && Vars.state.isGame()) Vars.state.set(State.menu);
            return true;
        });
        return res != null && res;
    }

    // ------------------------------------------------------- shared load logic

    /** Load a stored snapshot file into the game (game thread). Shared with host takeover. */
    static boolean loadIntoGame(Path file, String sectorKey) {
        if (Vars.content == null || Vars.state == null) return false;
        if (Vars.state.isGame()) Vars.state.set(State.menu);
        SaveMeta meta = SaveIO.getMeta(new Fi(file.toFile()));
        if (meta == null || meta.tags == null) return false;
        String info = meta.tags.get("sc-sector-info");
        if (info == null || info.length() > 262144) return false;
        Sector s = resolveSector(sectorKey);
        if (s == null || meta.rules == null || meta.rules.sector == null || !sectorMatches(meta.rules.sector, sectorKey)) {
            return false;
        }
        s.info = JsonIO.read(SectorInfo.class, info);
        Vars.logic.reset();
        SaveIO.load(new Fi(file.toFile()));
        boolean ok = Vars.state.isCampaign() && sectorMatches(Vars.state.getSector(), sectorKey);
        if (ok) Vars.state.set(State.playing);
        return ok;
    }

    /** Resolve {@code planet:sectorId} or preset name to a live Sector, or null when unresolvable here. */
    static Sector resolveSector(String sectorKey) {
        if (sectorKey == null || sectorKey.isEmpty()) return null;
        if (Vars.content == null) return null;

        int idx = sectorKey.indexOf(':');
        if (idx > 0 && idx < sectorKey.length() - 1) {
            String planetName = sectorKey.substring(0, idx);
            try {
                long id = Long.parseLong(sectorKey.substring(idx + 1));
                Planet p = null;
                if (Vars.content.planets() != null) {
                    for (Planet candidate : Vars.content.planets()) {
                        if (candidate.name.equals(planetName)) { p = candidate; break; }
                    }
                }
                if (p != null && p.sectors != null) {
                    for (Sector s : p.sectors) {
                        if (s.id == id) return s;
                    }
                }
            } catch (NumberFormatException ignored) {}
        }

        // Fallback: check sector presets by name (e.g. "groundZero", "craters")
        try {
            mindustry.type.SectorPreset preset = Vars.content.getByName(mindustry.ctype.ContentType.sector, sectorKey);
            if (preset != null && preset.sector != null) {
                return preset.sector;
            }
        } catch (Throwable ignored) {}

        if (Vars.content.planets() != null) {
            for (Planet p : Vars.content.planets()) {
                if (p.sectors != null) {
                    for (Sector s : p.sectors) {
                        if (sectorMatches(s, sectorKey)) return s;
                    }
                }
            }
        }
        return null;
    }

    static boolean sectorMatches(Sector s, String sectorKey) {
        if (s == null || sectorKey == null) return false;
        if (s.planet != null && (s.planet.name + ":" + s.id).equals(sectorKey)) return true;
        if (s.preset != null && sectorKey.equals(s.preset.name)) return true;
        return false;
    }

    /**
     * Bootstrap a clean vanilla campaign sector without overwriting any existing snapshot.
     * Called on the game thread when entering an unlocked sector that has no prior snapshot.
     */
    static boolean bootstrapVanillaSector(String sectorKey) {
        if (Vars.content == null || Vars.state == null || Vars.world == null || Vars.logic == null || Vars.control == null) {
            Log.warn("SECTOR_BOOTSTRAP_ENV_INCOMPLETE sector=@", sectorKey);
            return false;
        }
        Sector s = resolveSector(sectorKey);
        if (s == null) {
            Log.warn("SECTOR_BOOTSTRAP_RESOLVE_FAILED sector=@", sectorKey);
            return false;
        }
        try {
            Vars.control.playSector(s);
            boolean ok = Vars.state.isCampaign() && sectorMatches(Vars.state.getSector(), sectorKey);
            Log.info("SECTOR_BOOTSTRAP_SUCCESS sector=@ ok=@", sectorKey, ok);
            return ok;
        } catch (Throwable t) {
            Log.err("SECTOR_BOOTSTRAP_EXCEPTION sector=@", sectorKey, t);
            return false;
        }
    }

    /**
     * Schedule engine work on the game thread and block (bounded) for its result.
     * Returns false when there is no running application to schedule onto, on
     * timeout, or when the task threw.
     */
    static boolean runOnGameThread(Callable<Boolean> op) {
        if (Core.app == null) {
            Log.warn("SECTOR_WORLD_NO_APP");
            return false;
        }
        if (Core.app.isOnMainThread()) {
            try {
                return Boolean.TRUE.equals(op.call());
            } catch (Throwable t) {
                Log.err("SECTOR_WORLD_DIRECT_FAILED", t);
                return false;
            }
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Core.app.post(() -> {
            try {
                result.set(op.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.warn("SECTOR_WORLD_TIMEOUT");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (error.get() != null) {
            Log.err("SECTOR_WORLD_FAILED", error.get());
            return false;
        }
        return Boolean.TRUE.equals(result.get());
    }
}

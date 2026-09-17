package fixture;

import arc.Events;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.content.SectorPresets;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.server.ServerControl;
import mindustry.type.Sector;

/** Disposable test-classpath fixture ONLY. Uses bundled campaign preset through loadSector. */
public final class CampaignLauncher {
    public static void main(String[] args) {
        Events.on(ServerLoadEvent.class, event -> {
            ServerControl.instance.handler.register("sc-fixture-campaign-load", "Load bundled Ground Zero campaign sector.", values -> {
                try {
                    Sector sector = SectorPresets.groundZero.sector;
                    System.out.println("SC_CAMPAIGN_LOADING api=world.loadSector preset=" + sector.preset.name +
                        " source=" + sector.preset.generator.map.file);
                    Vars.logic.reset();
                    Vars.world.loadSector(sector);
                    // Bounded test: no waves/game-over during inventory assertions. Not a normal play session.
                    Vars.state.rules.waves = false;
                    Vars.state.rules.canGameOver = false;
                    Vars.logic.play();
                    System.out.println("SC_CAMPAIGN_LOADED preset=" + sector.preset.name);
                } catch (Throwable ex) {
                    System.out.println("SC_CAMPAIGN_BLOCKER " + ex);
                    ex.printStackTrace();
                }
            });
            ServerControl.instance.handler.register("sc-fixture-frozen-load", "TEST ONLY: load bundled Frozen Forest in this engine.", values -> {
                try {
                    Sector other = SectorPresets.frozenForest.sector;
                    System.out.println("SC_CAMPAIGN_LOADING api=world.loadSector preset=" + other.preset.name +
                        " source=" + other.preset.generator.map.file);
                    Vars.logic.reset();
                    Vars.world.loadSector(other);
                    Vars.state.rules.waves = false;
                    Vars.state.rules.canGameOver = false;
                    Vars.logic.play();
                    System.out.println("SC_CAMPAIGN_LOADED preset=" + other.preset.name);
                } catch (Throwable ex) {
                    System.out.println("SC_CAMPAIGN_BLOCKER " + ex);
                    ex.printStackTrace();
                }
            });
            ServerControl.instance.handler.register("sc-fixture-seed", "TEST ONLY: seed real core copper to 100.", values -> {
                var core = Vars.state.rules.defaultTeam.core();
                if (!Vars.state.isCampaign() || core == null) throw new IllegalStateException("No campaign core");
                int before = core.items.get(Items.copper);
                core.items.set(Items.copper, 100);
                System.out.println("SC_FIXTURE_SEEDED testOnly=true before=" + before + " after=" + core.items.get(Items.copper));
            });
            ServerControl.instance.handler.register("sc-fixture-campaign-state", "Direct engine campaign/core query.", values -> {
                Sector s = Vars.state.getSector();
                var core = Vars.state.rules.defaultTeam.core();
                System.out.println("SC_CAMPAIGN_STATE campaign=" + Vars.state.isCampaign() +
                    " planet=" + (s == null ? "none" : s.planet.name) + " sector=" + (s == null ? -1 : s.id) +
                    " preset=" + (s == null || s.preset == null ? "none" : s.preset.name) +
                    " core=" + (core != null) + " copper=" + (core == null ? -1 : core.items.get(Items.copper)) +
                    " width=" + Vars.world.width() + " height=" + Vars.world.height() +
                    " tick=" + Vars.state.tick + " updateId=" + Vars.state.updateId +
                    " playing=" + Vars.state.isPlaying() + " netActive=" + Vars.net.active());
            });
        });
        SmokeLauncher.main(args);
    }
}

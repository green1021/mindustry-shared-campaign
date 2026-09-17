package fixture;

import arc.Events;
import arc.struct.StringMap;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.game.Team;
import mindustry.maps.Map;
import mindustry.server.ServerControl;
import mindustry.world.Tile;

/** Test-classpath ONLY. Real generated maps, no listening/hosting and no manual tick increments. */
public final class TickingLauncher {
    public static void main(String[] args) {
        Events.on(ServerLoadEvent.class, event -> {
            ServerControl.instance.handler.register("sc-fixture-load", "<id> <width>", "Load generated map fixture.", values -> {
                String id = values[0];
                int width = Integer.parseInt(values[1]);
                if (!id.matches("generated-(host|guest)") || (width != 16 && width != 20))
                    throw new IllegalArgumentException("Fixture arguments");
                Vars.logic.reset();
                Vars.state.rules.waves = false;
                Vars.state.rules.canGameOver = false;
                Vars.world.loadGenerator(width, 16, tiles -> {
                    for (int x=0; x<width; x++) for (int y=0; y<16; y++)
                        tiles.set(x, y, new Tile(x, y, width == 16 ? Blocks.stone : Blocks.sand, Blocks.air, Blocks.air));
                    tiles.getn(5, 5).setBlock(Blocks.coreShard, Team.sharded);
                });
                StringMap tags = new StringMap();
                tags.put("name", id);
                Vars.state.map = new Map(tags);
                Vars.state.map.width = width;
                Vars.state.map.height = 16;
                Vars.logic.play();
                System.out.println("SC_FIXTURE_LOADED id=" + Vars.state.map.name());
            });
            ServerControl.instance.handler.register("sc-fixture-world", "Query actual engine tick and generated map.", values -> {
                System.out.println("SC_FIXTURE_WORLD id=" + Vars.state.map.name() +
                    " width=" + Vars.world.width() + " height=" + Vars.world.height() +
                    " tick=" + Vars.state.tick + " updateId=" + Vars.state.updateId +
                    " playing=" + Vars.state.isPlaying() + " campaign=" + Vars.state.isCampaign() +
                    " netActive=" + Vars.net.active());
            });
        });
        SmokeLauncher.main(args); // unchanged cooperative guard, then actual ServerLauncher
    }
}

package sc;

import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.core.Version;
import mindustry.mod.Mod;

/** PC bootstrap only. No campaign features are implemented. */
public final class SharedCampaignMod extends Mod {
    @Override
    public void init() {
        if (Version.build == 160 && Version.revision == 4) {
            Log.info("SC_PC_INIT_OK engine=160.4");
        } else {
            Log.warn("SC_PC_VERSION_MISMATCH engine=@.@ expected=160.4 features=false",
                Version.build, Version.revision);
        }
    }

    @Override
    public void registerServerCommands(CommandHandler commands) {
        commands.register("sc-status", "Bootstrap status only; no campaign features.", args ->
            Log.info("SC_STATUS version=0.0.1 engine=@.@ features=false",
                Version.build, Version.revision));
    }
}

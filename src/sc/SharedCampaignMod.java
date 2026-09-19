package sc;

import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.core.Version;
import mindustry.mod.Mod;

/** PC mod: explicit local-only sector sessions plus historical inert M2/M3 experiments. */
public final class SharedCampaignMod extends Mod {
    private final StdioLedger ledger = new StdioLedger();
    private final CampaignInventory inventory = new CampaignInventory();
    private final NetworkCampaign network = new NetworkCampaign();
    private final SectorSessions sessions = new SectorSessions();
    private final SharedCampaignUI ui = new SharedCampaignUI();

    @Override
    public void init() {
        inventory.init();
        sessions.init();
        network.init();
        ui.init();
        if (Version.build == 160 && Version.revision == 4) {
            Log.info("SC_PC_INIT_OK engine=160.4");
        } else {
            Log.warn("SC_PC_VERSION_MISMATCH engine=@.@ expected=160.4 features=false",
                Version.build, Version.revision);
        }
    }

    @Override
    public void registerServerCommands(CommandHandler commands) {
        ledger.register(commands);
        inventory.register(commands);
        sessions.register(commands);
        network.register(commands);
        commands.register("sc-status", "PC diagnostic; local sector sessions only, no playable campaign.", args ->
            Log.info("SC_STATUS version=0.0.4 engine=@.@ features=false",
                Version.build, Version.revision));
    }
}

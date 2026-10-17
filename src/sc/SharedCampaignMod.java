package sc;

import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.game.EventType;
import mindustry.mod.Mod;
import mindustry.mod.Mods;

/** PC mod: explicit local-only sector sessions plus historical inert M2/M3 experiments. */
public final class SharedCampaignMod extends Mod {
    private final StdioLedger ledger = new StdioLedger();
    private final CampaignInventory inventory = new CampaignInventory();
    private final NetworkCampaign network = new NetworkCampaign();
    private final SectorSessions sessions = new SectorSessions();
    private final SharedCampaignUI ui = new SharedCampaignUI();
    private final sc.aco.AcoController aco = new sc.aco.AcoController("shared-campaign-data");

    /**
     * Version as declared in mod.json, read from the live mod registry so the
     * banner can never drift from the packaged metadata again. Falls back to
     * "unknown" when no registry is present (unit tests that construct the mod
     * directly), never to a hardcoded literal.
     */
    static String modVersion() {
        try {
            Mods.LoadedMod loaded = Vars.mods == null ? null : Vars.mods.getMod(SharedCampaignMod.class);
            if (loaded != null && loaded.meta != null && loaded.meta.version != null) {
                return loaded.meta.version;
            }
        } catch (Throwable ignored) {
            // Fall through to the explicit unknown marker.
        }
        return "unknown";
    }

    @Override
    public void init() {
        aco.init();
        // Bind the ACO control plane to the UI action seam so the dialogs do real
        // work instead of logging placeholders.
        ui.setActions(aco);
        inventory.init();
        sessions.init();
        network.init();
        ui.init();
        // mindustry.mod.Mod exposes no dispose() hook; the engine signals teardown
        // through the global DisposeEvent, so bind ACO shutdown there.
        Events.on(EventType.DisposeEvent.class, e -> aco.dispose());
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
            Log.info("SC_STATUS version=@ engine=@.@ features=false | @",
                modVersion(), Version.build, Version.revision, aco.getStatus()));
        registerAcoCommands(commands);
    }

    /** Headless-reachable ACO controls, mirroring the UI actions for a live server. */
    private void registerAcoCommands(CommandHandler commands) {
        commands.register("sc-aco", "ACO integration status.", args ->
            Log.info("SC_ACO_STATUS @", aco.getStatus()));
        commands.register("sc-aco-host", "<syncPort> <sectorPort> Start campaign authority + P2 host.", args -> {
            int sync = args.length > 0 ? SharedCampaignUI.parsePort(args[0], SharedCampaignUI.DEFAULT_SYNC_PORT)
                : SharedCampaignUI.DEFAULT_SYNC_PORT;
            int sector = args.length > 1 ? SharedCampaignUI.parsePort(args[1], SharedCampaignUI.DEFAULT_SECTOR_PORT)
                : SharedCampaignUI.DEFAULT_SECTOR_PORT;
            boolean ok = aco.hostCampaign(sync, sector);
            Log.info("SC_ACO_HOST result=@ @", ok ? "OK" : "FAIL", aco.getStatus());
        });
        commands.register("sc-aco-connect", "<host> [port] Join a campaign authority.", args -> {
            if (args.length < 1) {
                Log.info("SC_ACO_CONNECT usage: sc-aco-connect <host> [port]");
                return;
            }
            int port = args.length > 1 ? SharedCampaignUI.parsePort(args[1], SharedCampaignUI.DEFAULT_SYNC_PORT)
                : SharedCampaignUI.DEFAULT_SYNC_PORT;
            boolean ok = aco.connectToAuthority(args[0], port);
            Log.info("SC_ACO_CONNECT result=@ @", ok ? "OK" : "FAIL", aco.getStatus());
        });
    }
}

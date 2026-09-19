package sc;

import arc.Core;
import arc.Events;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.ui.dialogs.BaseDialog;

/**
 * Client UI integration for Mindustry Shared Campaign.
 * Provides in-game dialog on PC/Desktop when not running headless.
 */
public final class SharedCampaignUI {

    public void init() {
        if (Vars.headless) return;

        Events.on(ClientLoadEvent.class, e -> {
            try {
                buildDialog();
                Log.info("SC_UI_INIT_OK");
            } catch (Throwable t) {
                Log.err("SC_UI_INIT_FAIL", t);
            }
        });
    }

    private void buildDialog() {
        BaseDialog dialog = new BaseDialog("Shared Campaign");
        dialog.addCloseButton();

        dialog.cont.add("Mindustry Shared Campaign").row();
        dialog.cont.image().color(mindustry.graphics.Pal.accent).fillX().height(3f).pad(4f).row();
        dialog.cont.add("Connect to Host Campaign or Select Sector:").pad(10f).row();

        dialog.cont.table(t -> {
            t.button("Join Host Campaign", () -> {
                Log.info("SC_UI_ACTION join-host");
            }).size(220f, 50f).pad(6f);

            t.button("Sector Directory", () -> {
                Log.info("SC_UI_ACTION sector-directory");
            }).size(220f, 50f).pad(6f).row();

            t.button("Research Tree Sync", () -> {
                Log.info("SC_UI_ACTION research-sync");
            }).size(220f, 50f).pad(6f);

            t.button("Resource Transfer", () -> {
                Log.info("SC_UI_ACTION transfer");
            }).size(220f, 50f).pad(6f);
        }).pad(10f).row();

        // Register custom button in campaign / pause menu if ui is ready
        if (Vars.ui != null && Vars.ui.paused != null) {
            Vars.ui.paused.shown(() -> {
                // Hook ready for in-game dialog trigger
            });
        }
    }
}

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
                mindustry.Vars.ui.showTextInput("Enter Host IP:Port", "127.0.0.1:6567", 32, "127.0.0.1:6567", s -> {
                    String[] parts = s.split(":");
                    String ip = parts[0];
                    int port = (parts.length > 1) ? Integer.parseInt(parts[1]) : 6567;
                    mindustry.Vars.net.connect(ip, port, () -> Log.info("SC_CONNECTED_SUCCESS"));
                });
            }).size(220f, 50f).pad(6f).row();

            t.button("Request Sector List", () -> {
                mindustry.Vars.net.send(new sc.NetworkCampaign.Frame("SC5|1|session-m5|ui-list|LIST"), true);
                Log.info("SC_UI_ACTION fetch-list");
            }).size(220f, 50f).pad(6f).row();
        }).pad(10f).row();

        // Register custom button in campaign / pause menu if ui is ready
        if (Vars.ui != null && Vars.ui.paused != null) {
            Vars.ui.paused.shown(() -> {
                Vars.ui.paused.cont.row();
                Vars.ui.paused.cont.button("Shared Campaign", dialog::show).size(220f, 50f);
            });
        }
    }
}

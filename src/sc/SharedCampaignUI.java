package sc;

import arc.Core;
import arc.Events;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.graphics.Pal;

public final class SharedCampaignUI {

    public void init() {
        if (Vars.headless) return;

        Events.on(ClientLoadEvent.class, e -> {
            try {
                // 1. Hook Main Menu: Add "Campaign Online"
                if (Vars.ui != null && Vars.ui.menufrag != null) {
                    Vars.ui.menufrag.addButton("Campaign Online", () -> {
                        BaseDialog d = new BaseDialog("Shared Campaign Online");
                        d.cont.add("Shared Campaign Management").pad(10f).row();
                        d.cont.button("Host Campaign", () -> {
                            // Host logic
                            Log.info("SC_UI_HOST_INIT");
                        }).size(200f, 50f).row();
                        d.cont.button("Join Campaign", () -> {
                            Vars.ui.showTextInput("Enter Host IP", "127.0.0.1", 32, "127.0.0.1", s -> {
                                Log.info("SC_UI_JOIN_ATTEMPT target=" + s);
                            });
                        }).size(200f, 50f).row();
                        d.addCloseButton();
                        d.show();
                    });
                    Log.info("SC_UI_MAIN_MENU_HOOK_OK");
                }
                
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

        dialog.cont.add("Atomic Campaign Orchestrator").row();
        dialog.cont.image().color(Pal.accent).fillX().height(3f).pad(4f).row();
        dialog.cont.add("Player Present Status: [green]ONLINE[]").pad(10f).row();

        dialog.cont.table(t -> {
            t.button("Join Host Campaign", () -> {
                Vars.ui.showTextInput("Enter Host IP:Port", "127.0.0.1:6567", 32, "127.0.0.1:6567", s -> {
                    String[] parts = s.split(":");
                    String ip = parts[0];
                    int port = (parts.length > 1) ? Integer.parseInt(parts[1]) : 6567;
                    Vars.net.connect(ip, port, () -> Log.info("SC_CONNECTED_SUCCESS"));
                });
            }).size(220f, 50f).pad(6f).row();

            t.button("Request Sector List", () -> {
                Vars.net.send(new sc.NetworkCampaign.Frame("SC5|1|session-m5|ui-list|LIST"), true);
            }).size(220f, 50f).pad(6f).row();
        }).pad(10f).row();

        if (Vars.ui != null && Vars.ui.paused != null) {
            Vars.ui.paused.shown(() -> {
                Vars.ui.paused.cont.row();
                Vars.ui.paused.cont.button("Shared Campaign", dialog::show).size(220f, 50f);
            });
        }
    }
}

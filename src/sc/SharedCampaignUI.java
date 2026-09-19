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
                // 1. Hook Join Game Dialog: Add "Add Campaign Server" button
                if (Vars.ui != null && Vars.ui.join != null) {
                    Vars.ui.join.buttons.button("Add Campaign", () -> {
                        BaseDialog d = new BaseDialog("Add Campaign Server");
                        d.cont.add("Enter Campaign Server Details").pad(10f).row();
                        
                        var ipField = new arc.scene.ui.TextField("127.0.0.1");
                        var portField = new arc.scene.ui.TextField("6567");
                        
                        d.cont.table(t -> {
                            t.add("IP: ").pad(5f);
                            t.add(ipField).width(200f).pad(5f).row();
                            t.add("Port: ").pad(5f);
                            t.add(portField).width(200f).pad(5f).row();
                        }).pad(10f).row();

                        d.cont.button("Connect", () -> {
                            String ip = ipField.getText();
                            int port = Integer.parseInt(portField.getText());
                            d.hide();
                            Log.info("SC_CAMPAIGN_SERVER_CONNECT target=" + ip + ":" + port);
                            // Custom campaign connection handshake
                            mindustry.Vars.net.connect(ip, port, () -> {
                                Log.info("SC_CAMPAIGN_CONNECTED");
                            });
                        }).size(150f, 45f).pad(5f);

                        d.addCloseButton();
                        d.show();
                    }).size(180f, 50f);
                    Log.info("SC_UI_JOIN_DIALOG_HOOK_OK");
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

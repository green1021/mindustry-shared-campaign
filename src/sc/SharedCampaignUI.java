package sc;

import arc.Core;
import arc.Events;
import arc.util.Log;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.JoinDialog;
import mindustry.graphics.Pal;

public final class SharedCampaignUI {

    public void init() {
        if (Vars.headless) return;

        Timer.schedule(() -> {
            if (Vars.ui != null && Vars.ui.join != null) {
                injectConnectButton();
            }
        }, 1.0f);

        // Fallback: Check for PlanetDialog every 2s
        Timer.schedule(() -> {
            if (Vars.ui != null && Vars.ui.planet != null && Vars.ui.planet.sectorTop != null && Vars.ui.planet.sectorTop.getChildren().size == 0) {
                 injectHostButton();
            }
        }, 2.0f, 2.0f);

        buildDialog();
    }

    private void injectConnectButton() {
        JoinDialog join = Vars.ui.join;
        join.buttons.button("Connect", () -> {
            BaseDialog d = new BaseDialog("Connect to Online Campaign");
            var addressField = new arc.scene.ui.TextField("127.0.0.1:6567");
            d.cont.add("Address (IP:Port):").pad(10f).row();
            d.cont.add(addressField).width(300f).pad(10f).row();
            d.cont.button("Connect", () -> {
                Log.info("SC_CONNECT_PLACEHOLDER addr=" + addressField.getText());
            }).size(120f, 50f);
            d.addCloseButton();
            d.show();
        }).size(180f, 50f);
    }

    private void injectHostButton() {
        Vars.ui.planet.sectorTop.row();
        Vars.ui.planet.sectorTop.button("Host Campaign", () -> {
            BaseDialog d = new BaseDialog("Host Campaign Settings");
            var port1Field = new arc.scene.ui.TextField("6567");
            var port2Field = new arc.scene.ui.TextField("6568");
            d.cont.add("Sync Port:").pad(5f);
            d.cont.add(port1Field).width(100f).row();
            d.cont.add("Sector Port:").pad(5f);
            d.cont.add(port2Field).width(100f).row();
            d.cont.button("Host", () -> {
                d.hide();
                Log.info("SC_HOST_INIT p1=" + port1Field.getText() + " p2=" + port2Field.getText());
            }).size(100f, 50f);
            d.addCloseButton();
            d.show();
        }).size(200f, 50f).pad(10f);
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
    }
}

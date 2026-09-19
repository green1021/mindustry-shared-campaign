package sc;

import arc.Core;
import arc.util.Log;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.JoinDialog;
import mindustry.graphics.Pal;
import java.lang.reflect.Method;

public final class SharedCampaignUI {

    public void init() {
        if (Vars.headless) return;

        // Use timer to ensure JoinDialog is fully initialized, then inject button
        Timer.schedule(() -> {
            if (Vars.ui != null && Vars.ui.join != null) {
                injectCampaignButton();
                Log.info("SC_UI_JOIN_BUTTON_INJECTED");
            }
        }, 1.0f);

        // 2. Add "Host Campaign" to Planet Dialog - use timer since planet UI is lazy-loaded
        Timer.schedule(() -> {
            if (Vars.ui != null && Vars.ui.planet != null) {
                injectHostCampaignButton();
                Log.info("SC_UI_PLANET_HOOK_OK");
            }
        }, 1.5f);

        buildDialog();
    }

    private void injectCampaignButton() {
        JoinDialog join = Vars.ui.join;
        // Add button to the existing buttons table
        join.buttons.button("Connect", () -> {
            // Placeholder - functionality to be added later
            Log.info("SC_CONNECT_PLACEHOLDER");
        }).size(180f, 50f);
    }

    private void injectHostCampaignButton() {
        Vars.ui.planet.cont.row();
        Vars.ui.planet.cont.button("Host Campaign", () -> {
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

        if (Vars.ui != null && Vars.ui.paused != null) {
            Vars.ui.paused.shown(() -> {
                Vars.ui.paused.cont.row();
                Vars.ui.paused.cont.button("Shared Campaign", dialog::show).size(220f, 50f);
            });
        }
    }
}

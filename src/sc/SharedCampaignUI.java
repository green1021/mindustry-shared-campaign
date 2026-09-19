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

        buildDialog();
    }

    private void injectCampaignButton() {
        JoinDialog join = Vars.ui.join;
        // Add button to the existing buttons table
        join.buttons.button("Add Campaign", () -> {
            BaseDialog d = new BaseDialog("Add Campaign");
            var addressField = new arc.scene.ui.TextField("127.0.0.1:6567");
            d.cont.add("Address (IP:Port):").pad(10f).row();
            d.cont.add(addressField).width(300f).pad(10f).row();
            d.cont.button("OK", () -> {
                String addr = addressField.getText();
                d.hide();
                sc.CampaignInventory.savedCampaignServers.add(addr);
                // Refresh JoinDialog using reflection to call private setup()
                try {
                    Method m = JoinDialog.class.getDeclaredMethod("setup");
                    m.setAccessible(true);
                    m.invoke(join);
                } catch (Exception ex) {
                    Log.err("SC_REFRESH_FAIL", ex);
                }
            }).size(100f, 50f);
            d.addCloseButton();
            d.show();
        }).size(180f, 50f);
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
package sc;

import arc.Core;
import arc.util.Log;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.JoinDialog;
import mindustry.ui.dialogs.PlanetDialog;
import mindustry.graphics.Pal;
import arc.scene.ui.Dialog;

public final class SharedCampaignUI {

    public void init() {
        if (Vars.headless) return;

        Timer.schedule(() -> {
            if (Vars.ui != null && Vars.ui.join != null) {
                injectConnectButton();
            }
            if (Vars.ui != null && Vars.ui.planet != null && !(Vars.ui.planet instanceof CampaignPlanetDialog)) {
                replacePlanetDialog();
            }
        }, 1.0f);
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

    private void replacePlanetDialog() {
        try {
            CampaignPlanetDialog custom = new CampaignPlanetDialog();
            java.lang.reflect.Field f = Vars.ui.getClass().getDeclaredField("planet");
            f.setAccessible(true);
            f.set(Vars.ui, custom);
            Log.info("SC_PLANET_DIALOG_REPLACED");
        } catch (Exception e) {
            Log.err("SC_PLANET_REPLACE_FAIL", e);
        }
    }

    public static class CampaignPlanetDialog extends PlanetDialog {
        @Override
        public Dialog show() {
            Dialog d = super.show();
            injectHostButton();
            return d;
        }

        private void injectHostButton() {
            sectorTop.row();
            sectorTop.button("Host Campaign", () -> {
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
    }
}

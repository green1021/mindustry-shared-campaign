package sc;

import arc.Core;
import arc.util.Log;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.JoinDialog;
import mindustry.ui.dialogs.PlanetDialog;
import sc.aco.SectorKeyResolver;
import arc.scene.ui.Dialog;

public final class SharedCampaignUI {

    /** Default campaign-authority sync port. */
    public static final int DEFAULT_SYNC_PORT = 6567;
    /** Default P2 sector-host port. */
    public static final int DEFAULT_SECTOR_PORT = 6568;

    private CampaignActions actions = new CampaignActions() {
        @Override
        public void hostCampaignAction(int syncPort, int sectorPort) {
            Log.info("SC_ACTIONS_UNBOUND host sync=@ sector=@", syncPort, sectorPort);
        }

        @Override
        public void connectAction(String host, int port) {
            Log.info("SC_ACTIONS_UNBOUND connect host=@ port=@", host, port);
        }
    };

    /** Bind the real ACO control plane (or a test stub). */
    public void setActions(CampaignActions actions) {
        if (actions != null) {
            this.actions = actions;
        }
    }

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

    // --- Action routing (headless-testable) ---

    /** Route a "Connect" button press to the ACO seam. Returns true when dispatched. */
    public boolean connectFromUi(String addressText) {
        String[] endpoint = parseAddress(addressText, DEFAULT_SYNC_PORT);
        if (endpoint == null) {
            Log.warn("SC_CONNECT_INVALID addr=@", addressText);
            return false;
        }
        actions.connectAction(endpoint[0], Integer.parseInt(endpoint[1]));
        return true;
    }

    /** Route a "Host Campaign" button press to the ACO seam. Returns true when dispatched. */
    public boolean hostFromUi(String syncPortText, String sectorPortText) {
        int sync = parsePort(syncPortText, DEFAULT_SYNC_PORT);
        int sector = parsePort(sectorPortText, DEFAULT_SECTOR_PORT);
        actions.hostCampaignAction(sync, sector);
        return true;
    }

    /**
     * Parse "host:port" or bare "host" into {host, port}. Returns null when the
     * text has no usable host component. A blank port falls back to {@code defaultPort}.
     */
    public static String[] parseAddress(String text, int defaultPort) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;

        String host = trimmed;
        String port = String.valueOf(defaultPort);

        int colon = trimmed.lastIndexOf(':');
        if (colon >= 0) {
            host = trimmed.substring(0, colon).trim();
            String tail = trimmed.substring(colon + 1).trim();
            if (!tail.isEmpty()) {
                port = String.valueOf(parsePort(tail, defaultPort));
            }
        }
        if (host.isEmpty()) return null;
        return new String[] { host, port };
    }

    /** Parse a port, falling back when the text is blank or not a valid 1..65535 value. */
    public static int parsePort(String text, int fallback) {
        if (text == null) return fallback;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return fallback;
        try {
            int value = Integer.parseInt(trimmed);
            if (value < 1 || value > 65535) return fallback;
            return value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // --- Dialog construction (requires a display) ---

    private void injectConnectButton() {
        JoinDialog join = Vars.ui.join;
        join.buttons.button("Connect", () -> {
            BaseDialog d = new BaseDialog("Connect to Online Campaign");
            var addressField = new arc.scene.ui.TextField("127.0.0.1:6567");
            d.cont.add("Address (IP:Port):").pad(10f).row();
            d.cont.add(addressField).width(300f).pad(10f).row();
            d.cont.button("Connect", () -> {
                if (connectFromUi(addressField.getText())) {
                    d.hide();
                } else {
                    Vars.ui.showErrorMessage("Invalid address. Use host:port.");
                }
            }).size(120f, 50f);
            d.addCloseButton();
            d.show();
        }).size(180f, 50f);
    }

    private void replacePlanetDialog() {
        try {
            CampaignPlanetDialog custom = new CampaignPlanetDialog();
            custom.setActions(actions);
            java.lang.reflect.Field f = Vars.ui.getClass().getDeclaredField("planet");
            f.setAccessible(true);
            f.set(Vars.ui, custom);
            Log.info("SC_PLANET_DIALOG_REPLACED");
        } catch (Exception e) {
            Log.err("SC_PLANET_REPLACE_FAIL", e);
        }
    }

    public static class CampaignPlanetDialog extends PlanetDialog {

        private CampaignActions actions = new CampaignActions() {
            @Override
            public void hostCampaignAction(int syncPort, int sectorPort) {
                Log.info("SC_HOST_INIT p1=@ p2=@", syncPort, sectorPort);
            }

            @Override
            public void connectAction(String host, int port) {
            }
        };

        private boolean buttonsInjected = false;

        public void setActions(CampaignActions actions) {
            if (actions != null) {
                this.actions = actions;
                this.buttonsInjected = false; // Reset injection flag when actions change
            }
        }

        @Override
        public Dialog show() {
            Dialog d = super.show();
            if (!buttonsInjected) {
                injectHostButton();
                buttonsInjected = true;
            }
            return d;
        }

        private void injectHostButton() {
            sectorTop.row();
            // Host/Connect controls
            sectorTop.button("Host Campaign", () -> {
                BaseDialog d = new BaseDialog("Host Campaign Settings");
                var port1Field = new arc.scene.ui.TextField(String.valueOf(DEFAULT_SYNC_PORT));
                var port2Field = new arc.scene.ui.TextField(String.valueOf(DEFAULT_SECTOR_PORT));
                var hostField = new arc.scene.ui.TextField(actions.getAdvertisedHost() == null ? "" : actions.getAdvertisedHost());
                d.cont.add("Sync Port:").pad(5f);
                d.cont.add(port1Field).width(100f).row();
                d.cont.add("Sector Port:").pad(5f);
                d.cont.add(port2Field).width(100f).row();
                d.cont.add("Advertised Host:").pad(5f);
                d.cont.add(hostField).width(200f).row();
                d.cont.button("Host", () -> {
                    d.hide();
                    int sync = parsePort(port1Field.getText(), DEFAULT_SYNC_PORT);
                    int sector = parsePort(port2Field.getText(), DEFAULT_SECTOR_PORT);
                    actions.hostCampaignAction(sync, sector, hostField.getText());
                }).size(100f, 50f);
                d.addCloseButton();
                d.show();
            }).size(150f, 50f).pad(5f);

            // Gameplay controls (Checkpoint/Exit/Release)
            sectorTop.button("Checkpoint", () -> {
                if (actions.checkpointLocalSectorAction()) {
                    Vars.ui.showInfo("Checkpoint saved.");
                } else {
                    Vars.ui.showErrorMessage("Checkpoint failed.");
                }
            }).size(120f, 50f).pad(5f);

            sectorTop.button("Exit/Release", () -> {
                if (actions.exitLocalSectorAction()) {
                    Vars.ui.showInfo("Exited and released sector.");
                } else {
                    Vars.ui.showErrorMessage("Failed to exit/release.");
                }
            }).size(120f, 50f).pad(5f);

            sectorTop.row();

            sectorTop.button("Claim/Enter", () -> {
                if (selected == null) {
                    Vars.ui.showErrorMessage("Select a sector first.");
                    return;
                }
                String key;
                try {
                    key = SectorKeyResolver.keyOf(selected);
                } catch (IllegalArgumentException e) {
                    Vars.ui.showErrorMessage("Cannot identify the selected sector.");
                    return;
                }
                
                Vars.ui.loadfrag.show("Claiming sector...");
                actions.claimSectorAction(key, res -> {
                    Vars.ui.loadfrag.hide();
                    if (!res.success()) {
                        Vars.ui.showErrorMessage("Failed to claim sector " + key + ": " + res.reason());
                    }
                });
            }).size(150f, 50f).pad(5f);
        }
    }
}

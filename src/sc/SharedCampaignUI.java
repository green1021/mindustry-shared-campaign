package sc;

import arc.Core;
import arc.util.Log;
import arc.util.Timer;
import arc.scene.ui.TextButton;
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

    // --- Handoff control logic (headless-testable) ---

    /** How long the UI waits for the authority to report a terminal handoff phase. */
    public static final long HANDOFF_UI_TIMEOUT_MS = 150_000L;

    /** Terminal result of a takeover, derived only from the authority-reported phase. */
    public record HandoffOutcome(boolean success, String message) {}

    /**
     * Validate a handoff target before anything is dispatched.
     *
     * @param sectorKey canonical key from {@code SectorKeyResolver}
     * @param hosting   whether this process is the campaign host
     * @param retryable whether a previous attempt is parked and retryable
     * @param retry     true for the Retry Takeover control
     * @return null when the request may be dispatched, otherwise a refusal code
     */
    public static String validateHandoffTarget(String sectorKey, boolean hosting, boolean retryable, boolean retry) {
        if (sectorKey == null || sectorKey.isBlank()) return "NO_SECTOR_SELECTED";
        if (sectorKey.length() > 64 || sectorKey.indexOf('|') >= 0
                || sectorKey.indexOf('\n') >= 0 || sectorKey.indexOf('\r') >= 0) {
            return "INVALID_SECTOR_KEY";
        }
        if (!hosting) return "NOT_HOSTING";
        // Retry must never start a second, unrelated takeover: it is only valid
        // for a failed attempt whose lease the authority parked as RESERVED.
        if (retry && !retryable) return "NOT_RETRYABLE";
        return null;
    }

    /**
     * Widget readiness for a handoff button: a valid selection, host role, no control
     * action in flight, and (retry only) a parked failure. Kept static so the
     * disabled-state rule is testable without a scene graph; presentation only, the
     * click handler still re-validates.
     */
    public static boolean handoffReady(String sectorKey, boolean hosting, boolean retryable,
                                       boolean busy, boolean retry) {
        return sectorKey != null && !busy && validateHandoffTarget(sectorKey, hosting, retryable, retry) == null;
    }

    /** Player-facing text for a refusal code returned by {@link #validateHandoffTarget}. */
    public static String handoffRefusalMessage(String code, String sectorKey) {
        if (code == null) return "";
        switch (code) {
            case "NO_SECTOR_SELECTED":
                return "Select a sector on the planet map first.";
            case "INVALID_SECTOR_KEY":
                return "The selected sector has no valid campaign key.";
            case "NOT_HOSTING":
                return "Only the campaign host can take over a sector. Start or join a campaign first.";
            case "NOT_RETRYABLE":
                return "No failed takeover to retry for " + sectorKey + ". Retry is only available after a takeover load fails.";
            default:
                return "Takeover refused: " + code;
        }
    }

    /**
     * Map the authority's handoff phase to a terminal outcome.
     *
     * @return null while the handoff is still running, otherwise the outcome.
     *         Success is reported only for SERVING - never on dispatch alone.
     */
    public static HandoffOutcome handoffOutcome(String phase, String failure, boolean timedOut) {
        String p = phase == null ? "NONE" : phase;
        switch (p) {
            case "SERVING":
                return new HandoffOutcome(true, "Takeover complete: this machine is now hosting the sector.");
            case "FAILED":
                return new HandoffOutcome(false, "Takeover failed: " + reason(failure)
                        + ". The sector was not moved; retry is available once the lease is parked.");
            case "ABORTED":
                return new HandoffOutcome(false, "Takeover aborted: " + reason(failure) + ".");
            case "NONE":
                // A handoff that never registered stays NONE; only the local
                // watchdog may declare that a timeout.
                return timedOut
                        ? new HandoffOutcome(false, "Takeover did not start; the authority reports no handoff for this sector.")
                        : null;
            default:
                return timedOut
                        ? new HandoffOutcome(false, "Takeover timed out while " + p + "; no success was confirmed.")
                        : null;
        }
    }

    private static String reason(String failure) {
        return failure == null || failure.isBlank() ? "unknown reason" : failure;
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

        /** Single-flight guard so two control actions cannot run at once. */
        private final java.util.concurrent.atomic.AtomicBoolean controlBusy =
                new java.util.concurrent.atomic.AtomicBoolean();
        /** Invalidates callbacks/polls belonging to a superseded control action. */
        private volatile long controlOp = 0L;

        public void setActions(CampaignActions actions) {
            if (actions != null) {
                this.actions = actions;
                this.buttonsInjected = false; // Reset injection flag when actions change
                this.controlOp++;
                this.controlBusy.set(false);
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

            // Gameplay controls. Every control dispatches through the async
            // action API so the render thread never blocks on a socket, a
            // snapshot transfer, or a world save. The progress indicator is
            // closed on all terminal outcomes: success, refusal, exception,
            // rejection, and timeout.
            sectorTop.button("Checkpoint", () -> {
                if (!beginControl()) return;
                Vars.ui.loadfrag.show("Saving checkpoint…");
                actions.checkpointLocalSectorAction(res -> {
                    controlBusy.set(false);
                    Vars.ui.loadfrag.hide();
                    if (res.success()) {
                        Vars.ui.showInfo("Checkpoint committed to the campaign.");
                    } else {
                        Vars.ui.showErrorMessage("Checkpoint failed: " + res.reason()
                                + ". Your world was not released; try again before exiting.");
                    }
                });
            }).size(120f, 50f).pad(5f);

            sectorTop.button("Exit/Release", () -> {
                if (!beginControl()) return;
                Vars.ui.loadfrag.show("Saving and releasing the sector…");
                actions.exitLocalSectorAction(res -> {
                    controlBusy.set(false);
                    Vars.ui.loadfrag.hide();
                    if (res.success()) {
                        Vars.ui.showInfo("Exited the sector and released its lease.");
                    } else {
                        Vars.ui.showErrorMessage("Exit/release failed: " + res.reason()
                                + ". The sector was not released.");
                    }
                });
            }).size(120f, 50f).pad(5f);

            sectorTop.row();

            sectorTop.button("Claim/Enter", () -> {
                if (!beginControl()) return;
                final String key;
                try {
                    key = selected == null ? null : SectorKeyResolver.keyOf(selected);
                } catch (RuntimeException e) {
                    controlBusy.set(false);
                    Vars.ui.showErrorMessage("Cannot identify the selected sector: " + e.getMessage());
                    return;
                }
                if (key == null) {
                    controlBusy.set(false);
                    Vars.ui.showErrorMessage("Select a sector first.");
                    return;
                }
                Vars.ui.loadfrag.show("Claiming " + key + "…");
                actions.claimSectorAction(key, res -> {
                    controlBusy.set(false);
                    Vars.ui.loadfrag.hide();
                    if (res.success()) {
                        Vars.ui.showInfo("Claimed " + key + ". Loading the sector…");
                    } else {
                        Vars.ui.showErrorMessage("Failed to claim " + key + ": " + res.reason());
                    }
                });
            }).size(150f, 50f).pad(5f);

            TextButton takeover = new TextButton("Takeover");
            takeover.clicked(() -> requestHandoff(false));
            sectorTop.add(takeover).size(130f, 50f).pad(5f);
            takeover.setDisabled(() -> !canHandoff(false));

            TextButton retry = new TextButton("Retry Takeover");
            retry.clicked(() -> requestHandoff(true));
            sectorTop.add(retry).size(130f, 50f).pad(5f);
            retry.setDisabled(() -> !canHandoff(true));
        }

        /**
         * True when the selected sector can start this handoff right now: a sector is
         * selected, this process is the campaign host, no other control action is in
         * flight, and (for retry) the last attempt actually failed. Wired into
         * {@link TextButton#setDisabled(arc.func.Boolp)} so Arc itself renders the
         * disabled style and suppresses the click; the click handler re-validates
         * anyway, so ownership safety never depends on the widget state.
         *
         * <p>Called from the render thread every frame. It only reads lock-free /
         * volatile state ({@code handoffPhase} is a published snapshot), so it cannot
         * stall on a load in flight.
         */
        private boolean canHandoff(boolean retry) {
            try {
                String key = selected == null ? null : SectorKeyResolver.keyOf(selected);
                return actions != null && handoffReady(key, actions.isHosting(),
                        actions.isHandoffRetryable(key), controlBusy.get(), retry);
            } catch (RuntimeException e) {
                return false;
            }
        }

        /**
         * Dispatch a host-side takeover (or retry) for the selected sector.
         *
         * <p>Success is reported only after the authority moves the sector to
         * SERVING, i.e. after the world was loaded from the committed snapshot
         * on this machine. A dispatch that the authority refuses, or a handoff
         * that fails or times out, leaves ownership untouched and says so.
         */
        private void requestHandoff(boolean retry) {
            if (!beginControl()) return;

            final String key;
            try {
                key = selected == null ? null : SectorKeyResolver.keyOf(selected);
            } catch (RuntimeException e) {
                controlBusy.set(false);
                Vars.ui.showErrorMessage("Cannot identify the selected sector: " + e.getMessage());
                return;
            }

            String refusal = validateHandoffTarget(key, actions.isHosting(),
                    actions.isHandoffRetryable(key), retry);
            if (refusal != null) {
                controlBusy.set(false);
                Vars.ui.showErrorMessage(handoffRefusalMessage(refusal, key));
                return;
            }

            final long op = ++controlOp;
            Vars.ui.loadfrag.show((retry ? "Retrying takeover of " : "Requesting takeover of ") + key + "…");

            java.util.function.Consumer<ActionResult> dispatched = res -> {
                if (op != controlOp) return; // superseded by a newer control action
                if (!res.success()) {
                    controlBusy.set(false);
                    Vars.ui.loadfrag.hide();
                    Vars.ui.showErrorMessage((retry ? "Retry refused: " : "Takeover refused: ") + res.reason());
                    return;
                }
                // Dispatch only proves the request left this process. Poll the
                // authority-reported phase until it reaches a terminal state.
                Vars.ui.loadfrag.show("Waiting for the handoff of " + key + "…");
                final long startedAt = System.currentTimeMillis();
                Timer.schedule(new Timer.Task() {
                    @Override
                    public void run() {
                        if (op != controlOp) {
                            cancel();
                            return;
                        }
                        HandoffOutcome outcome = handoffOutcome(actions.handoffPhase(key),
                                actions.handoffFailure(key),
                                System.currentTimeMillis() - startedAt > HANDOFF_UI_TIMEOUT_MS);
                        if (outcome == null) return;
                        cancel();
                        controlBusy.set(false);
                        Runnable ui = () -> {
                            Vars.ui.loadfrag.hide();
                            if (outcome.success()) {
                                Vars.ui.showInfo(outcome.message() + "\nSector: " + key);
                            } else {
                                Vars.ui.showErrorMessage(outcome.message() + "\nSector: " + key);
                            }
                        };
                        if (Core.app == null) ui.run();
                        else Core.app.post(ui);
                    }
                }, 0.25f, 0.25f);
            };

            if (retry) actions.retryHandoffAction(key, dispatched);
            else actions.requestHandoffAction(key, dispatched);
        }

        /** Single-flight guard shared by all five control buttons. */
        private boolean beginControl() {
            if (!controlBusy.compareAndSet(false, true)) {
                Vars.ui.showInfo("Another campaign action is still running. Wait for it to finish.");
                return false;
            }
            return true;
        }
    }
}

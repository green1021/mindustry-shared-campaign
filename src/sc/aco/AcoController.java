package sc.aco;

import arc.Core;
import arc.util.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Owns the ACO control-plane lifecycle for the running mod, on both sides.
 *
 * <p>Construction is inert: {@link #init()} only rebuilds in-memory state,
 * recovers the persisted campaign snapshot and opens the durable lease journal. No
 * socket is bound until {@link #hostCampaign(int, int)} is called.
 *
 * <p>Role split:
 * <ul>
 *   <li><b>Host</b>: {@code hostCampaign} runs {@link AcoServer} (control plane) and
 *       owns the {@link SectorHandoffManager} that loads a taken-over sector world
 *       on <em>this</em> machine and starts its P2 server.</li>
 *   <li><b>Client</b>: {@code connectToAuthority} opens one
 *       {@link AcoControlConnection}; {@code claimLocalSector} claims a lease over
 *       that control channel and runs the world <em>locally</em> through
 *       {@link GameSectorWorld} / {@link LocalSectorSession}. The client is never a
 *       sector runtime of the host.</li>
 * </ul>
 */
public final class AcoController implements sc.CampaignActions {
    private final Path storageDir;

    private CampaignPersistence persistence;
    private GlobalCampaignState globalState;
    private SectorRegistry registry;
    private SectorLeaseJournal leaseJournal;
    private GlobalTransactionManager txManager;
    private CampaignAuthority authority;
    private CampaignSectorStore sectorStore;
    private AcoP2Manager p2Manager;
    private volatile SectorHandoffManager handoffManager;
    private AcoServer server;
    private SectorHandoffManager.HostRuntimeOps hostOpsOverride;

    private boolean initialized;
    private volatile boolean hosting;

    /** True when startup recovery refused; the on-disk dataset is left untouched and write-latched. */
    private volatile boolean recoveryRefused;

    /** True when a refused recovery left the dataset unusable until an operator repairs it. */
    public boolean isRecoveryRefused() {
        return recoveryRefused;
    }

    private int syncPort;
    private int sectorPort;

    /** Advertised reachable address for the host's sector P2 servers (null -> loopback + warning). */
    private String hostAdvertisedAddress;

    // --- client side ---
    private AcoControlConnection control;
    private String authorityHost;
    private int authorityPort;
    private volatile boolean connected;
    private LocalSectorSession localSession;
    private GameSectorWorld localWorld;
    private volatile SectorWorldOps localWorldOpsOverride;
    private java.util.concurrent.ExecutorService pushExecutor;
    /**
     * Single-thread executor for UI-dispatched control actions (claim, takeover,
     * retry, checkpoint, exit). Separate from {@link #pushExecutor} because that
     * one only exists while a control connection is open, and a pure host must
     * still run takeover/load work off the game thread.
     */
    private volatile java.util.concurrent.ExecutorService actionExecutor;
    private volatile String lastHostChanged;

    private java.util.function.BiConsumer<String, Integer> gameplayConnector;

    public void setGameplayConnector(java.util.function.BiConsumer<String, Integer> connector) {
        this.gameplayConnector = connector;
    }

    public AcoController(String dataDirName) {
        this.storageDir = Paths.get(dataDirName);
    }

    /** Test seam: substitute the host takeover world/runtime operations. */
    public void setHostRuntimeOps(SectorHandoffManager.HostRuntimeOps ops) {
        this.hostOpsOverride = ops;
    }

    /** Configure the address friends should reach for host P2 sector servers. */
    public void setHostAdvertisedAddress(String address) {
        String valid = validateAdvertisedHost(address);
        this.hostAdvertisedAddress = valid;
        if (arc.Core.settings != null) {
            try {
                if (valid == null) {
                    arc.Core.settings.remove("sc-advertised-host");
                } else {
                    arc.Core.settings.put("sc-advertised-host", valid);
                }
            } catch (Throwable ignored) {}
        }
    }

    public static String validateAdvertisedHost(String host) {
        if (host == null || host.isBlank()) return null;
        String trimmed = host.trim();
        if (trimmed.contains(":") && !trimmed.contains("::")) {
            int colon = trimmed.lastIndexOf(':');
            trimmed = trimmed.substring(0, colon).trim();
        }
        if (trimmed.isEmpty()) return null;
        if (trimmed.contains(" ") || trimmed.contains("/") || trimmed.contains("\\")) {
            throw new IllegalArgumentException("Invalid host characters in: " + host);
        }
        return trimmed;
    }

    public static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) return true;
        String h = host.trim().toLowerCase();
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1") || h.startsWith("127.");
    }

    public String getHostAdvertisedAddress() {
        return hostAdvertisedAddress;
    }

    /** Test seam: override lease eligibility policy (default: campaign-unlocked sectors). */
    public void setClaimPolicy(Predicate<String> policy) {
        if (authority != null) authority.setClaimPolicy(policy);
    }

    /** The authority's canonical campaign sector store (host side). */
    public CampaignSectorStore getSectorStore() {
        return sectorStore;
    }

    public SectorRegistry getRegistry() {
        return registry;
    }

    public SectorHandoffManager getHandoffManager() {
        return handoffManager;
    }

    public AcoControlConnection control() {
        return control;
    }

    public LocalSectorSession localSession() {
        return localSession;
    }

    public synchronized void init() {
        if (initialized) return;
        initialized = true;

        if (this.actionExecutor == null) {
            this.actionExecutor = newActionExecutor();
        }

        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            Log.err("ACO_STORAGE_DIR_FAILED path=@", storageDir, e);
        }

        this.persistence = new CampaignPersistence(storageDir);
        this.globalState = new GlobalCampaignState();
        this.globalState.campaignId = "default";

        if (this.hostAdvertisedAddress == null && arc.Core.settings != null) {
            try {
                String saved = arc.Core.settings.getString("sc-advertised-host", null);
                if (saved != null && !saved.isBlank()) {
                    this.hostAdvertisedAddress = validateAdvertisedHost(saved);
                }
            } catch (Throwable ignored) {}
        }

        try {
            this.globalState = persistence.recover();
        } catch (CampaignPersistence.RecoveryException e) {
            // Fail closed: potentially committed data is on disk and has NOT been
            // rewritten. The persistence layer refuses save()/appendWAL(), so a
            // state that may omit a committed record can never be persisted.
            this.recoveryRefused = true;
            Log.err("ACO_STATE_RECOVERY_REFUSED: " + e.getMessage());
        } catch (Exception e) {
            Log.err("ACO_STATE_RECOVERY_FAILED", e);
        }

        // A campaign with no progression yet must still offer its starting sector,
        // or the authority would reject the very first completion as LOCKED.
        if (!recoveryRefused && globalState.unlockedSectors.isEmpty() && globalState.completedSectors.isEmpty()) {
            globalState.unlockStartingSectors();
        }

        // Durable lease journal: an acknowledged lease survives an authority restart
        // (recovered as ORPHANED) instead of silently handing the sector out again.
        this.leaseJournal = null;
        try {
            this.leaseJournal = new SectorLeaseJournal(storageDir.resolve("leases"));
        } catch (IOException e) {
            Log.err("ACO_LEASE_JOURNAL_OPEN_FAILED: @ (lease durability disabled)", e.getMessage());
        }
        this.registry = new SectorRegistry(leaseJournal);

        // Canonical per-campaign sector snapshots. In a running game process the
        // validator reads real save metadata; in a purely headless component test
        // there is no game to read saves with, so only structural validation runs
        // (the store still always verifies length + SHA-256).
        boolean gameContext = Core.app != null;
        this.sectorStore = null;
        try {
            CampaignSectorStore.SnapshotMetaValidator validator = gameContext
                    ? new CampaignSectorStore.SaveMetaValidator(globalState.campaignId)
                    : CampaignSectorStore.STRUCTURAL_ONLY;
            this.sectorStore = new CampaignSectorStore(storageDir, globalState.campaignId, validator);
        } catch (IOException e) {
            Log.err("ACO_SECTOR_STORE_OPEN_FAILED: @ (client snapshots cannot be committed)", e.getMessage());
        }

        this.txManager = new GlobalTransactionManager(globalState, new GlobalBank(globalState, persistence));
        this.authority = new CampaignAuthority(registry, persistence);
        // Product rule: only campaign-unlocked sectors are claimable.
        this.authority.setClaimPolicy(key -> globalState.unlockedSectors.contains(key));
        this.p2Manager = new AcoP2Manager();

        Log.info("ACO_CONTROLLER_INIT data=@ revision=@", storageDir, globalState.globalRevision);
    }

    /**
     * Host: start the campaign authority control plane and wire the host takeover
     * runtime. Returns true when the sync server was started.
     */
    public synchronized boolean hostCampaign(int syncPort, int sectorPort) {
        init();
        if (hosting) return true;

        this.syncPort = syncPort;
        this.sectorPort = sectorPort;

        this.handoffManager = new SectorHandoffManager(registry, hostOps(), serverNotifier(), serverBroadcaster());
        final SectorHandoffManager handoffs = this.handoffManager;
        this.server = new AcoServer(syncPort, authority, registry, globalState, txManager);
        this.server.setSectorStore(sectorStore);
        this.server.setHandoffManager(handoffs);
        this.server.start();
        this.hosting = true;

        Thread watchdog = new Thread(() -> {
            while (hosting) {
                try {
                    handoffs.tick();
                } catch (Exception e) {
                    Log.err("HANDOFF_WATCHDOG_ERROR", e);
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "aco-handoff-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        Log.info("ACO_HOST_START sync=@ sector=@", syncPort, sectorPort);
        return true;
    }

    private SectorHandoffManager.HostRuntimeOps hostOps() {
        if (hostOpsOverride != null) return hostOpsOverride;
        if (sectorStore == null) return null; // authority-only deployment: handoff load stage refuses
        Supplier<String> advertised = () -> hostAdvertisedAddress;
        return new GameSectorHostOps(sectorStore, p2Manager, storageDir.resolve("scratch"),
                () -> sectorPort, advertised);
    }

    private SectorHandoffManager.SessionNotifier serverNotifier() {
        return (sessionId, type, payload) -> server != null && server.sendToSession(sessionId, type, payload);
    }

    private SectorHandoffManager.Broadcaster serverBroadcaster() {
        return (type, payload) -> {
            if (server != null) server.broadcast(type, payload);
        };
    }

    // ------------------------------------------------------------- push dispatch

    private static java.util.concurrent.ExecutorService newPushExecutor() {
        return java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aco-push");
            t.setDaemon(true);
            return t;
        });
    }

    private void shutdownPushExecutor() {
        java.util.concurrent.ExecutorService ex = this.pushExecutor;
        this.pushExecutor = null;
        if (ex != null) ex.shutdownNow();
    }

    /**
     * Single worker for control-plane actions started from the game/UI thread.
     *
     * <p>Those actions block: they write to a control socket, wait for a
     * request/response round trip, transfer a snapshot, or park on a
     * game-thread world load. One thread keeps them strictly ordered (a claim
     * of the same sector cannot overlap a takeover), and the daemon flag keeps
     * the JVM from being held open by a stuck worker.
     */
    private static java.util.concurrent.ExecutorService newActionExecutor() {
        return java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aco-action");
            t.setDaemon(true);
            return t;
        });
    }

    private void shutdownActionExecutor() {
        java.util.concurrent.ExecutorService ex = this.actionExecutor;
        this.actionExecutor = null;
        if (ex != null) ex.shutdownNow();
    }

    /**
     * Run a push-handler side effect off the control connection's reader thread.
     *
     * <p>Push handlers are invoked on the socket reader thread. The handoff path
     * blocks on request/response cycles over that same socket, so running it inline
     * starves the response and deadlocks. All push side effects share one
     * single-thread executor, so per-handoff message order (request before abort)
     * is the submission order; task failures are contained and logged; dispose()
     * shuts the executor down.
     */
    private void submitPush(String what, Runnable task) {
        java.util.concurrent.ExecutorService ex = this.pushExecutor;
        if (ex == null) return;
        try {
            ex.execute(() -> {
                try {
                    task.run();
                } catch (Throwable e) {
                    Log.err("ACO_PUSH_TASK_FAILED task=@", what, e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.warn("ACO_PUSH_REJECTED task=@", what);
        }
    }

    /** Test seam: substitute the client-side local sector world operations. */
    public void setLocalWorldOps(SectorWorldOps ops) {
        this.localWorldOpsOverride = ops;
    }

    /** The client-side world ops: test override when present, production otherwise. */
    private SectorWorldOps localWorldOps() {
        SectorWorldOps override = this.localWorldOpsOverride;
        if (override != null) return override;
        if (localWorld != null) return localWorld;
        localWorld = new GameSectorWorld(sectorStore, globalState.campaignId, storageDir.resolve("scratch"));
        return localWorld;
    }

    /**
     * Client side of shared progression: fold an authority GLOBAL_STATE_SYNC into
     * this machine's campaign view when it is not older. Runs on the reader thread
     * and deliberately does not take the controller monitor (request paths hold it
     * while awaiting responses on that same thread).
     */
    public void applyRemoteState(String json) {
        GlobalCampaignState local = this.globalState;
        if (json == null || local == null) return;
        GlobalCampaignState remote;
        try {
            remote = CampaignPersistence.fromJson(json);
        } catch (Exception e) {
            Log.warn("ACO_STATE_SYNC_REJECTED reason=@", e.getMessage());
            return;
        }
        if (remote == null || remote.campaignId == null || remote.campaignId.isEmpty()) return;
        synchronized (local) {
            if (remote.globalRevision < local.globalRevision) return;
            local.globalRevision = remote.globalRevision;
            local.unlockedSectors.clear();
            local.unlockedSectors.addAll(remote.unlockedSectors);
            local.completedSectors.clear();
            local.completedSectors.addAll(remote.completedSectors);
            local.techTree.clear();
            local.techTree.putAll(remote.techTree);
            local.globalBank.clear();
            local.globalBank.putAll(remote.globalBank);
        }
        Log.info("ACO_STATE_SYNC_APPLIED revision=@", remote.globalRevision);
    }

    // ------------------------------------------------------------------ client

    /**
     * Connect to a remote campaign authority: one control connection, a bounded
     * PING/PONG handshake, and push handlers for handoff messages.
     */
    public synchronized boolean connectToAuthority(String host, int port) {
        init();
        try {
            AcoControlConnection conn = new AcoControlConnection();
            conn.connect(host, port, 3000);
            shutdownPushExecutor();
            pushExecutor = newPushExecutor();
            conn.setOnDisconnect(() -> {
                connected = false;
                Log.warn("ACO_CONTROL_DISCONNECTED host=@ port=@", host, port);
            });
            conn.addPushHandler(AcoMessageType.SECTOR_HANDOFF_REQUEST, frame -> {
                String payload = new String(frame.payload(), java.nio.charset.StandardCharsets.UTF_8);
                submitPush("handoff-request", () -> {
                    LocalSectorSession session = this.localSession;
                    if (session != null) {
                        String err = session.onHandoffRequest(payload);
                        Log.info("ACO_PUSH_HANDOFF_REQUEST payload=@ result=@", payload, err == null ? "OK" : err);
                    } else {
                        String key = payload.split("\\|", -1)[0];
                        conn.send(AcoMessageType.SECTOR_HANDOFF_ABORT, key + "|ABORT|NOT_ACTIVE");
                        Log.warn("ACO_PUSH_HANDOFF_NO_SESSION payload=@", payload);
                    }
                });
            });
            conn.addPushHandler(AcoMessageType.SECTOR_HANDOFF_ABORT, frame -> {
                String payload = new String(frame.payload(), java.nio.charset.StandardCharsets.UTF_8);
                submitPush("handoff-abort", () -> {
                    LocalSectorSession session = this.localSession;
                    if (session != null) session.onHandoffAbort(payload);
                });
            });
            conn.addPushHandler(AcoMessageType.SECTOR_HOST_CHANGED, frame -> {
                lastHostChanged = new String(frame.payload(), java.nio.charset.StandardCharsets.UTF_8);
                Log.info("ACO_HOST_CHANGED @", lastHostChanged);
            });
            // Shared progression: fold the authority's campaign view into this machine.
            conn.addPushHandler(AcoMessageType.GLOBAL_STATE_SYNC, frame -> applyRemoteState(
                    new String(frame.payload(), java.nio.charset.StandardCharsets.UTF_8)));

            String pong = conn.request(AcoMessageType.PING, "", 3000, AcoMessageType.PONG.id);
            if (pong == null) {
                conn.close();
                shutdownPushExecutor();
                Log.warn("ACO_CONNECT_NO_RESPONSE host=@ port=@", host, port);
                return false;
            }
            this.control = conn;
            this.authorityHost = host;
            this.authorityPort = port;
            this.connected = true;
            Log.info("ACO_CONNECT_OK host=@ port=@", host, port);
            return true;
        } catch (IOException e) {
            shutdownPushExecutor();
            Log.warn("ACO_CONNECT_FAILED host=@ port=@ reason=@", host, port, e.getMessage());
            return false;
        }
    }

    /**
     * Claim a sector on the connected authority and start playing it as a local
     * single-player world on this machine.
     *
     * @return null on success, otherwise a failure reason
     */
    public synchronized String claimLocalSector(String sectorKey, long revisionHint) {
        if (!connected || control == null) return "NOT_CONNECTED";
        if (sectorStore == null) return "NO_SECTOR_STORE";
        if (localSession != null && localSession.isActive()) return "ALREADY_ACTIVE";
        SectorWorldOps ops = localWorldOps();
        if (ops == null) return "NO_WORLD_OPS";
        localSession = new LocalSectorSession(control, ops,
                key -> sectorStore.readSnapshot(key));
        return localSession.claim(sectorKey, revisionHint);
    }

    /** Save + commit the current local sector world to the campaign. */
    public synchronized String checkpointLocalSector() {
        if (localSession == null) return "NO_SESSION";
        return localSession.checkpoint();
    }

    /** Leave the local sector and release its lease. */
    public synchronized String releaseLocalSector(String reason) {
        if (localSession == null) return "NO_SESSION";
        String err = localSession.release(reason);
        if (err == null) localSession = null;
        return err;
    }

    /** Retry a release that failed after the local world was already left. */
    public synchronized String retryReleaseLocalSector() {
        if (localSession == null) return "NO_SESSION";
        String err = localSession.retryRelease();
        if (err == null) localSession = null;
        return err;
    }

    // ------------------------------------------------------------------- host

    /**
     * Host: request takeover of a client-owned sector. Drives the handoff protocol;
     * the sector world loads and P2 starts on this machine only after the client's
     * final snapshot is committed and its lease released.
     *
     * <p>Deliberately <em>not</em> synchronized on the controller: the manager owns
     * its own state lock, and the UI polls {@link #handoffPhase} from the render
     * thread. A controller monitor here would let any control action stall the poll.
     *
     * @return null when the handoff started (or completed); otherwise a reason
     */
    public String requestHandoff(String sectorKey) {
        SectorHandoffManager hm = handoffManager;
        if (!hosting || hm == null) return "NOT_HOSTING";
        return hm.begin(sectorKey);
    }

    /**
     * Host: retry a failed takeover load (lease parked as RESERVED). Returns null once
     * the retry was accepted; the load itself runs on the manager's loader thread, so
     * poll {@link #handoffPhase} for the outcome.
     */
    public String retryHandoff(String sectorKey) {
        SectorHandoffManager hm = handoffManager;
        if (!hosting || hm == null) return "NOT_HOSTING";
        return hm.retryHost(sectorKey);
    }

    /** UI-facing status read: short manager lock only, never the controller monitor. */
    public String handoffPhase(String sectorKey) {
        SectorHandoffManager hm = handoffManager;
        return hm == null ? "NO_HOST" : hm.phase(sectorKey);
    }

    /** UI-facing status read: short manager lock only, never the controller monitor. */
    public String handoffFailure(String sectorKey) {
        SectorHandoffManager hm = handoffManager;
        return hm == null ? null : hm.failure(sectorKey);
    }

    /** Host/operator: reclaim an ORPHANED or RESERVED sector back to FREE. */
    public synchronized String forceFreeSector(String sectorKey) {
        if (registry == null) return "NOT_INITIALIZED";
        SectorRegistry.Result r;
        try {
            r = registry.forceFree(sectorKey);
        } catch (SectorLeaseJournal.LeaseJournalException e) {
            return "PERSISTENCE_FAILURE";
        }
        if (r == SectorRegistry.Result.OK && server != null) {
            SectorOwnership own = registry.get(sectorKey);
            server.broadcast(AcoMessageType.SECTOR_HOST_CHANGED,
                    sectorKey + "|FREE|" + (own == null ? -1 : own.ownershipVersion));
        }
        return r == SectorRegistry.Result.OK ? null : r.name();
    }

    // --- raw protocol helpers (tests and diagnostics; real flow goes through the above) ---

    /** Raw claim exchange; response is {@code key|OK|ver|rev|grant} or {@code key|FAIL|reason}. */
    public synchronized String requestClaim(String sectorKey, long revision) {
        if (control == null || !connected) return null;
        return control.request(AcoMessageType.SECTOR_CLAIM_REQUEST, sectorKey + "|" + revision,
                5000, AcoMessageType.SECTOR_CLAIM_RESULT.id);
    }

    /** Join resolution: only host-run sectors answer OK with an endpoint. */
    public synchronized String requestJoin(String sectorKey) {
        if (control == null || !connected) return null;
        return control.request(AcoMessageType.SECTOR_JOIN_REQUEST, sectorKey,
                5000, AcoMessageType.SECTOR_JOIN_RESPONSE.id);
    }

    /** Raw release exchange; requires the exact grant. */
    public synchronized String requestRelease(String sectorKey, long ownershipVersion, String grant) {
        if (control == null || !connected) return null;
        return control.request(AcoMessageType.SECTOR_RELEASE, sectorKey + "|" + ownershipVersion + "|" + grant,
                5000, AcoMessageType.SECTOR_RELEASE.id);
    }

    /** Reattach an ORPHANED lease (reconnect after a dropped control connection). */
    public synchronized String requestReattach(String sectorKey, String grant) {
        if (control == null || !connected) return null;
        return control.request(AcoMessageType.SECTOR_REATTACH, sectorKey + "|" + grant,
                5000, AcoMessageType.SECTOR_REATTACH.id);
    }

    public synchronized String requestResource(String operationId, String sectorId,
            long expectedGlobalRevision, String resourceType, long amount,
            GlobalResourceRequest.OperationType operationType) {
        if (control == null || !connected) return null;
        String payload = operationId + "|" + sectorId + "|" + expectedGlobalRevision
                + "|" + resourceType + "|" + amount + "|" + operationType.name();
        return control.request(AcoMessageType.GLOBAL_RESOURCE_REQUEST, payload,
                5000, AcoMessageType.GLOBAL_RESOURCE_RESPONSE.id);
    }

    public synchronized String requestSectorCompletion(String sectorKey, String operationId) {
        if (control == null || !connected) return null;
        return control.request(AcoMessageType.SECTOR_COMPLETION_EVENT, sectorKey + "|" + operationId,
                5000, AcoMessageType.SECTOR_COMPLETION_EVENT.id);
    }

    public synchronized String requestResearch(String tech, String operationId) {
        if (control == null || !connected) return null;
        return control.request(AcoMessageType.RESEARCH_REQUEST, tech + "|" + operationId,
                5000, AcoMessageType.RESEARCH_RESPONSE.id);
    }

    // --- sc.CampaignActions (headless-testable UI seam) ---

    @Override
    public void hostCampaignAction(int syncPort, int sectorPort) {
        hostCampaignAction(syncPort, sectorPort, getHostAdvertisedAddress());
    }

    @Override
    public void hostCampaignAction(int syncPort, int sectorPort, String advertisedHost) {
        if (advertisedHost != null && !advertisedHost.isBlank()) {
            setHostAdvertisedAddress(advertisedHost.trim());
        }
        boolean ok = hostCampaign(syncPort, sectorPort);
        if (ok && !connected) {
            connectToAuthority("127.0.0.1", syncPort);
        }
    }

    @Override
    public void connectAction(String host, int port) {
        if (connectToAuthority(host, port) && gameplayConnector != null) {
            gameplayConnector.accept(host, port);
        }
    }

    @Override
    public void claimSectorAction(String sectorKey, java.util.function.Consumer<sc.ActionResult> callback) {
        ActionDispatch.submit(actionExecutor, "claim-action",
                () -> toResult(claimLocalSector(sectorKey, 0)), callback);
    }

    @Override
    public boolean claimSectorAction(String sectorKey) {
        String err = claimLocalSector(sectorKey, 0);
        if (err != null) {
            Log.warn("ACO_CLAIM_ACTION_FAILED sector=@ reason=@", sectorKey, err);
            return false;
        }
        return true;
    }

    @Override
    public boolean enterLocalSectorAction(String sectorKey) {
        return claimSectorAction(sectorKey);
    }

    @Override
    public boolean checkpointLocalSectorAction() {
        String err = checkpointLocalSector();
        if (err != null) {
            Log.warn("ACO_CHECKPOINT_ACTION_FAILED reason=@", err);
            return false;
        }
        return true;
    }

    @Override
    public boolean exitLocalSectorAction() {
        String err = releaseLocalSector("USER_EXIT");
        if (err != null) {
            Log.warn("ACO_EXIT_ACTION_FAILED reason=@", err);
            return false;
        }
        return true;
    }

    @Override
    public boolean requestHandoffAction(String sectorKey) {
        String err = requestHandoff(sectorKey);
        if (err != null) {
            Log.warn("ACO_TAKEOVER_ACTION_FAILED sector=@ reason=@", sectorKey, err);
            return false;
        }
        return true;
    }

    @Override
    public boolean retryHandoffAction(String sectorKey) {
        String err = retryHandoff(sectorKey);
        if (err != null) {
            Log.warn("ACO_RETRY_TAKEOVER_ACTION_FAILED sector=@ reason=@", sectorKey, err);
            return false;
        }
        return true;
    }

    // --- asynchronous UI entry points ---------------------------------------
    //
    // Everything below moves the blocking part (socket round trip, snapshot
    // transfer, world load) off the caller's thread and delivers exactly one
    // terminal result back through the app thread. ActionDispatch also covers
    // executor rejection and task exceptions, so a UI progress indicator can
    // always be closed.

    @Override
    public void requestHandoffAction(String sectorKey, java.util.function.Consumer<sc.ActionResult> callback) {
        ActionDispatch.submit(actionExecutor, "takeover",
                () -> toResult(requestHandoff(sectorKey)), callback);
    }

    @Override
    public void retryHandoffAction(String sectorKey, java.util.function.Consumer<sc.ActionResult> callback) {
        ActionDispatch.submit(actionExecutor, "retry-takeover",
                () -> toResult(retryHandoff(sectorKey)), callback);
    }

    @Override
    public void checkpointLocalSectorAction(java.util.function.Consumer<sc.ActionResult> callback) {
        ActionDispatch.submit(actionExecutor, "checkpoint",
                () -> toResult(checkpointLocalSector()), callback);
    }

    @Override
    public void exitLocalSectorAction(java.util.function.Consumer<sc.ActionResult> callback) {
        ActionDispatch.submit(actionExecutor, "exit",
                () -> toResult(releaseLocalSector("USER_EXIT")), callback);
    }

    /**
     * Only a handoff parked in FAILED is retryable: that is the state the manager
     * reaches after parking the lease as RESERVED. The authority re-validates the
     * parked lease when the retry actually runs, so this is a UI gate, not a
     * substitute for the ownership check.
     */
    @Override
    public boolean isHandoffRetryable(String sectorKey) {
        return "FAILED".equals(handoffPhase(sectorKey));
    }

    private static sc.ActionResult toResult(String err) {
        return err == null ? sc.ActionResult.ok() : sc.ActionResult.fail(err);
    }

    @Override
    public String getStatusText() {
        return getStatus();
    }

    @Override
    public boolean isOnline() {
        return isHosting() || isConnected();
    }

    @Override
    public String getAdvertisedHost() {
        return getHostAdvertisedAddress();
    }

    public synchronized void dispose() {
        if (localSession != null && localSession.isActive()) {
            Log.warn("ACO_DISPOSE_LOCAL_SECTOR_STILL_ACTIVE sector=@", localSession.sectorKey());
        }
        // Abandon any in-flight takeover before the executors go away: an attempt whose
        // load is still running can no longer write state, and one that was never
        // dispatched must not be left in LOADING forever.
        SectorHandoffManager hm = handoffManager;
        if (hm != null) {
            try {
                hm.close();
            } catch (RuntimeException e) {
                Log.err("ACO_DISPOSE_HANDOFF_CLOSE_FAILED", e);
            }
            handoffManager = null;
        }
        shutdownActionExecutor();
        shutdownPushExecutor();
        if (control != null) {
            control.close();
            control = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        if (p2Manager != null) p2Manager.stopP2();
        connected = false;
        hosting = false;
        Log.info("ACO_CONTROLLER_DISPOSE");
    }

    /**
     * Render-thread readers must never contend with the controller monitor: several
     * control actions ({@code requestClaim}, {@code claimLocalSector}, ...) are
     * synchronized on this controller and block on a socket round trip, so a
     * synchronized getter here would stall the UI for the duration of that call.
     * Both fields are volatile and independently meaningful, so a plain read is
     * consistent enough for status/disable rendering.
     */
    public boolean isHosting() {
        return hosting;
    }

    public boolean isConnected() {
        return connected;
    }

    /** Last SECTOR_HOST_CHANGED payload observed on the control connection (diagnostics). */
    public synchronized String lastHostChanged() {
        return lastHostChanged;
    }

    public synchronized long getGlobalRevision() {
        return globalState == null ? 0 : globalState.globalRevision;
    }

    public synchronized GlobalCampaignState getGlobalState() {
        return globalState;
    }

    public synchronized String getStatus() {
        String local = localSession == null ? "none" : localSession.phase().name();
        return String.format("aco{init=%b,hosting=%b,syncPort=%d,sectorPort=%d,connected=%b,revision=%d,local=%s}",
                initialized, hosting, syncPort, sectorPort, connected, getGlobalRevision(), local);
    }
}

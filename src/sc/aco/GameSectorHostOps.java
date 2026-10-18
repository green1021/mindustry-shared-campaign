package sc.aco;

import arc.util.Log;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Production host-side {@link SectorHandoffManager.HostRuntimeOps}: when the host
 * takes over a client's sector, <em>this</em> process loads the exact committed
 * snapshot (via {@link GameSectorWorld#loadIntoGame}) and starts an ordinary
 * Mindustry P2 server for it with {@link AcoP2Manager}.
 *
 * <p>The advertised endpoint is explicit and configurable: defaulting to a loopback
 * address keeps local testing working but logs a loud warning, because remote
 * friends cannot join a {@code 127.0.0.1} endpoint -- the spec forbids silently
 * hard-coding it.
 */
public class GameSectorHostOps implements SectorHandoffManager.HostRuntimeOps {

    private final CampaignSectorStore store;
    private final AcoP2Manager p2;
    private final Path scratchDir;
    private final IntSupplier portSupplier;
    private final Supplier<String> advertisedHostSupplier;

    public GameSectorHostOps(CampaignSectorStore store, AcoP2Manager p2, Path scratchDir,
            IntSupplier portSupplier, Supplier<String> advertisedHostSupplier) {
        this.store = store;
        this.p2 = p2;
        this.scratchDir = scratchDir;
        this.portSupplier = portSupplier;
        this.advertisedHostSupplier = advertisedHostSupplier;
    }

    @Override
    public boolean loadSnapshot(String sectorKey) throws Exception {
        byte[] bytes = store.readSnapshot(sectorKey);
        if (bytes == null) {
            Log.err("HOST_LOAD_NO_COMMITTED_SNAPSHOT sector=@", sectorKey);
            return false;
        }
        Files.createDirectories(scratchDir);
        Path tmp = Files.createTempFile(scratchDir, "hostload-", ".msav");
        try {
            Files.write(tmp, bytes);
            return GameSectorWorld.runOnGameThread(() -> GameSectorWorld.loadIntoGame(tmp, sectorKey));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public String startServer(String sectorKey) throws Exception {
        int port = portSupplier.getAsInt();
        boolean started = GameSectorWorld.runOnGameThread(() -> {
            p2.startP2(port, sectorKey);
            return p2.isActive();
        });
        if (!started) return null;
        String host = advertisedHostSupplier == null ? null : advertisedHostSupplier.get();
        if (host == null || host.isBlank() || AcoController.isLoopback(host)) {
            String fallback = (host == null || host.isBlank()) ? "127.0.0.1" : host;
            Log.warn("HOST_ENDPOINT_LOOPBACK sector=@ advertised=@ status=LOCAL_ONLY " +
                    "(Remote peers will NOT be able to connect! Configure an advertised public/LAN IP in host settings)",
                    sectorKey, fallback);
            host = fallback;
        } else {
            Log.info("HOST_ENDPOINT_ADVERTISED sector=@ host=@ port=@", sectorKey, host, port);
        }
        return host + ":" + port;
    }

    @Override
    public void stopServer(String sectorKey) {
        GameSectorWorld.runOnGameThread(() -> {
            p2.stopP2();
            return true;
        });
    }
}

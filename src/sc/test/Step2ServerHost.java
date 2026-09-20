package sc.test;

import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.World;
import mindustry.core.NetServer;
import mindustry.type.SectorPreset;
import mindustry.gen.Player;
import mindustry.net.Packets;
import arc.ApplicationListener;
import arc.backend.headless.HeadlessApplication;
import arc.util.Log;

/**
 * Step 2: Real Server (P2) Hosting groundZero
 * EXACT ServerLauncher.init() sequence reproduction
 */
public class Step2ServerHost {
    public static void main(String[] args) {
        new HeadlessApplication(new ApplicationListener() {
            @Override
            public void init() {
                try {
                    // EXACT ServerLauncher sequence:
                    // 1. Platform setup
                    Vars.platform = new mindustry.core.Platform() {
                        @Override public mindustry.net.Net.NetProvider getNet() { return new mindustry.net.ArcNetProvider(); }
                    };
                    Vars.net = new mindustry.net.Net(Vars.platform.getNet());
                    Vars.loadLocales = false;
                    Vars.headless = true;
                    Vars.loadSettings();
                    Vars.init();
                    
                    // 2. Content creation
                    Vars.content.createBaseContent();
                    Vars.mods.loadScripts();
                    Vars.content.createModContent();
                    Vars.content.init();
                    
                    // 3. Base registry
                    Vars.bases.load();
                    
                    // 4. Core systems
                    Vars.logic = new mindustry.core.Logic();
                    Vars.spawner = new mindustry.ai.WaveSpawner();
                    Vars.collisions = new mindustry.entities.EntityCollisions();
                    Vars.world = new World();

                    // Load sector
                    SectorPreset groundZero = Vars.content.sectors().find(s -> s.name.equals("groundZero"));
                    Vars.world.loadSector(groundZero.sector);

                    // 5. CRITICAL: Add NetServer BEFORE mods.eachClass (exact ServerLauncher order)
                    Vars.netServer = new NetServer();
                    Vars.netServer.init();
                    Vars.netServer.openServer();
                    arc.Core.app.addListener(Vars.netServer);
                    
                    // 6. CRITICAL: Register packet handlers
                    // Just manually trigger NetServer's packet registration if mods don't have main
                    Vars.net.handleServer(mindustry.net.Packets.Connect.class, (con, packet) -> {});
                    Vars.net.handleServer(mindustry.net.Packets.ConnectPacket.class, (con, packet) -> {
                        System.out.println("SERVER_EVENT: ConnectPacket received.");
                        con.player = Player.create();
                        con.player.con(con);
                        mindustry.core.NetServer.connectConfirm(con.player);
                    });
                    
                    // 7. Fire ServerLoadEvent
                    arc.Events.fire(new mindustry.game.EventType.ServerLoadEvent());
                    
                    // Add packet logging for debugging
                    Vars.net.handleServer(Object.class, (con, packet) -> {
                        System.out.println("SERVER_EVENT: Received packet: " + packet.getClass().getSimpleName());
                    });
                    
                    // Host real P2 server
                    Vars.net.host(6567);
                    System.out.println("SERVER_STARTED: P2 listening on port 6567 for groundZero");

                    // Add listeners to verify Handshake
                    arc.Events.on(mindustry.game.EventType.PlayerConnectionConfirmed.class, event -> {
                         System.out.println("SERVER_DIAGNOSTIC: Player accepted/Connection confirmed.");
                    });
                    
                    // Hook into NetServer packet handlers to see WorldStream
                    Vars.net.handleServer(Packets.WorldStream.class, (con, p) -> {
                        System.out.println("SERVER_DIAGNOSTIC: WorldStream received by server handler? (Unexpected in standard flow)");
                    });

                    Vars.net.handleServer(Packets.Connect.class, (con, packet) -> {
                        System.out.println("SERVER_DIAGNOSTIC: Received Connect packet.");
                    });

                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }

            @Override
            public void update() {
                try {
                    Thread.sleep(16);
                } catch (InterruptedException ignored) {}
            }
        });
    }
}
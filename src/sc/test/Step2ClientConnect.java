package sc.test;

import mindustry.Vars;
import mindustry.net.Net;
import mindustry.net.ArcNetProvider;
import mindustry.net.Packets;
import mindustry.gen.Player;
import mindustry.core.NetClient;
import mindustry.core.UI;
import arc.ApplicationListener;
import arc.backend.headless.HeadlessApplication;
import arc.assets.AssetManager;
import arc.Core;

/**
 * Step 2: Minimal Headless Client with minimal UI stub
 */
public class Step2ClientConnect {
    public static void main(String[] args) {
        new HeadlessApplication(new ApplicationListener() {
            @Override
            public void init() {
                try {
                    // Set up essential environment
                    Vars.headless = true;
                    Vars.loadLocales = false;
                    Vars.platform = new mindustry.core.Platform() {
                        @Override public mindustry.net.Net.NetProvider getNet() { return new ArcNetProvider(); }
                    };
                    Vars.net = new Net(Vars.platform.getNet());
                    
                    // Mock minimal assets to allow UI init
                    arc.Core.assets = new AssetManager();
                    
                    Vars.loadSettings();
                    Vars.init();
                    
                    // Now NetClient should be created without NPE
                    if (Vars.netClient == null) {
                        Vars.netClient = new NetClient();
                    }
                    
                    // Essential for handshake
                    Vars.player = Player.create();
                    Vars.player.name = "TestClient";
                    
                    System.out.println("CLIENT: Attempting connection...");
                    
                    Vars.net.connect("localhost", 6567, () -> {
                        System.out.println("CLIENT: Connected!");
                        
                        Packets.ConnectPacket connect = new Packets.ConnectPacket();
                        connect.name = Vars.player.name;
                        connect.version = mindustry.core.Version.build;
                        connect.versionType = mindustry.core.Version.type;
                        
                        Vars.net.send(connect, true);
                        System.out.println("CLIENT: Sent ConnectPacket");
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
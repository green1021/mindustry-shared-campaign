package sc.test;

import mindustry.Vars;
import mindustry.gen.Player;
import mindustry.net.Packets;
import mindustry.net.Net;
import mindustry.net.ArcNetProvider;
import mindustry.core.NetClient;
import arc.backend.headless.HeadlessApplication;
import arc.ApplicationListener;
import arc.Core;
import arc.assets.AssetManager;

/**
 * Diagnostic Client: Validates P2 server-side handshake and asset/world stream initiation.
 */
public class ProtocolDiagnosticClient {
    public static void main(String[] args) {
        new HeadlessApplication(new ApplicationListener() {
            @Override
            public void init() {
                try {
                    Vars.headless = true;
                    Vars.loadLocales = false;
                    Vars.platform = new mindustry.core.Platform() {
                        @Override public mindustry.net.Net.NetProvider getNet() { return new ArcNetProvider(); }
                    };
                    Vars.net = new Net(Vars.platform.getNet());
                    
                    // Essential for minimal handshake without crashing
                    Core.assets = new AssetManager() {
                        @Override public <T> T get(String fileName, Class<T> type) {
                            return null;
                        }
                        @Override public <T> arc.assets.loaders.AssetLoader<T, ?> getLoader(Class<T> type) {
                            return new arc.assets.loaders.CustomLoader() {
                                @Override public arc.struct.Seq<arc.assets.AssetDescriptor> getDependencies(String fileName, arc.files.Fi file, arc.assets.AssetLoaderParameters params) {
                                    return new arc.struct.Seq<>();
                                }
                                @Override public void loadAsync(arc.assets.AssetManager manager, String fileName, arc.files.Fi file, arc.assets.AssetLoaderParameters params) {}
                                @Override public Object loadSync(arc.assets.AssetManager manager, String fileName, arc.files.Fi file, arc.assets.AssetLoaderParameters params) { return null; }
                            };
                        }
                    };
                    
                    Vars.loadSettings();
                    Vars.init();
                    
                    // Setup minimal client state
                    Vars.netClient = new NetClient();
                    Vars.logic = new mindustry.core.Logic(); 
                    Vars.ui = new mindustry.core.UI(); // Add UI stub
                    Vars.player = Player.create();
                    Vars.player.name = "DiagnosticClient";
                    
                    System.out.println("DIAGNOSTIC: Attempting protocol connection...");
                    
                    Vars.net.connect("localhost", 6567, () -> {
                        System.out.println("DIAGNOSTIC: TCP Connected.");
                        
                        Packets.ConnectPacket p = new Packets.ConnectPacket();
                        p.name = Vars.player.name;
                        p.version = mindustry.core.Version.build;
                        p.versionType = mindustry.core.Version.type;
                        Vars.net.send(p, true);
                        System.out.println("DIAGNOSTIC: ConnectPacket sent.");
                    });

                    Vars.net.handleClient(Packets.WorldStream.class, p -> {
                        System.out.println("DIAGNOSTIC: RECEIVED WorldStream (Transfer stream data).");
                    });
                    
                    Vars.net.handleClient(Packets.Connect.class, p -> {
                        System.out.println("DIAGNOSTIC: RECEIVED Connect (Handshake successful).");
                    });

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
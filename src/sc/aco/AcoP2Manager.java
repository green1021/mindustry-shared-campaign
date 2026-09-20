package sc.aco;

import arc.util.Log;
import mindustry.Vars;
import mindustry.net.ArcNetProvider;
import mindustry.net.Net;

import java.io.IOException;

public class AcoP2Manager {
    private boolean active = false;

    public void startP2(int port, String sectorKey) {
        if (active) return;
        Log.info("ACO_P2_START port=@ sector=@", port, sectorKey);
        
        try {
            Vars.net = new Net(new ArcNetProvider());
            Vars.net.host(port);
            active = true;
        } catch (IOException e) {
            Log.err("ACO_P2_HOST_FAILED", e);
        }
    }

    public void stopP2() {
        if (!active) return;
        Log.info("ACO_P2_STOP");
        Vars.net.dispose();
        active = false;
    }
}

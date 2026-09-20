import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.World;
import mindustry.core.NetServer;
import mindustry.net.Net;
import arc.util.Log;

public class SimpleInitTest {
    public static void main(String[] args) {
        try {
            System.setProperty("arc.headless", "true");
            System.setProperty("arc.backend", "headless");
            
            // Minimal manual bootstrap
            Vars.content = new ContentLoader();
            Vars.content.createBaseContent();
            Vars.world = new World();
            Vars.net = new Net(new mindustry.net.NetworkIO());
            
            Log.info("Content initialized: " + Vars.content.sectors().size);
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}

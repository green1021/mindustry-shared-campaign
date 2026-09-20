package tests;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import arc.util.Log;

public class VanillaAssetVerifier {
    public static void main(String[] args) {
        System.setProperty("arc.headless", "true");
        System.setProperty("arc.backend", "headless");
        
        // Ensure classloader loads content
        ContentLoader loader = new ContentLoader();
        Vars.content = loader;
        loader.createBaseContent();
        
        Log.info("Content sectors count: " + Vars.content.sectors().size);
        if (Vars.content.sectors().size > 0) {
            System.exit(0);
        } else {
            System.exit(1);
        }
    }
}

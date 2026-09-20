import mindustry.Vars;
import mindustry.type.SectorPreset;
import arc.util.Log;

public class HeadlessContentInit {
    public static void main(String[] args) {
        try {
            Log.info("=== ATTEMPTING HEADLESS CONTENT INIT ===");
            
            // Set headless mode before any arc classes are loaded
            System.setProperty("arc.headless", "true");
            System.setProperty("arc.backend", "headless");
            
            // Try to initialize by accessing content
            // The content loads on first access usually
            if (Vars.content != null) {
                Log.info("Vars.content already initialized");
            } else {
                Log.info("Vars.content is null - trying to trigger init...");
            }
            
            // Check content count
            int sectorCount = Vars.content.sectors().size;
            Log.info("Sectors found: " + sectorCount);
            
            if (sectorCount > 0) {
                for (SectorPreset s : Vars.content.sectors()) {
                    Log.info("Sector: " + s.name + " Planet: " + s.planet.name);
                }
                Log.info("SUCCESS: Content loaded");
            } else {
                Log.info("FAIL: No sectors found");
            }
            
        } catch (Exception e) {
            Log.err("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

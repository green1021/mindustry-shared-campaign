import mindustry.core.ContentLoader;
import arc.files.Fi;

public class DesktopContentCheck {
    public static void main(String[] args) {
        // Look for the assets inside the jar
        Fi assets = new Fi("planets/serpulo.json"); // Might not work directly
        System.out.println("Path check: " + assets.exists());
    }
}

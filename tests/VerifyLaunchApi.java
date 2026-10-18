package tests;
import mindustry.Vars;
import mindustry.type.Sector;

public class VerifyLaunchApi {
    public static void check() {
        // Just checking if this compiles
        Sector s = null;
        Vars.control.playSector(s);
    }
}

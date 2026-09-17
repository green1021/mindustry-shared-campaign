package fixture;
import mindustry.Vars;
import mindustry.core.World;
import mindustry.core.GameState;
/** Object isolation probe only; does not initialize or tick two engines. */
public final class EngineIsolationProbe {
    public static void main(String[] args) {
        World a = new World(), b = new World();
        GameState sa = new GameState(), sb = new GameState();
        a.resize(8, 8); b.resize(12, 12);
        if(sa.rules == null || sb.rules == null) throw new AssertionError("Missing rules");
        sa.rules.waves = false; sb.rules.waves = true;
        sa.wave = 3; sb.wave = 9;
        if(sa.rules == sb.rules || sa.rules.waves || !sb.rules.waves)
            throw new AssertionError("Rules are not isolated");
        if(sa.wave != 3 || sb.wave != 9 || a.width() != 8 || b.width() != 12)
            throw new AssertionError("World/state values are not isolated");
        System.out.println("RULES_ISOLATED=true A.waves="+sa.rules.waves+" B.waves="+sb.rules.waves);
        System.out.println("VALUES_ISOLATED=true A.width="+a.width()+" B.width="+b.width()+" A.wave="+sa.wave+" B.wave="+sb.wave);
        Vars.world = a; Vars.state = sa;
        boolean first = Vars.world == a && Vars.state == sa;
        Vars.world = b; Vars.state = sb;
        if(!first || Vars.world == a || Vars.state == sa || Vars.world != b || Vars.state != sb)
            throw new AssertionError("Unexpected global reference behavior");
        System.out.println("GLOBAL_ACTIVE_REFERENCE_REPLACED=true");
        System.out.println("SCOPE=constructors_and_fields_only; no maps, ticking, saves, resources or networking tested");
    }
}

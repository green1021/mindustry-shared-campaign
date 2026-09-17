package fixture;

import mindustry.core.World;
import mindustry.core.GameState;
import java.lang.reflect.*;

/** Two independent engine objects, NOT two ticking engines or a network test. */
public final class SharedStateTest {
    static int checks;
    static void check(boolean result, String label){
        if(!result) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }
    public static void main(String[] args) throws Exception {
        World worldA = new World(), worldB = new World();
        GameState stateA = new GameState(), stateB = new GameState();
        worldA.resize(8, 8); worldB.resize(12, 12);
        stateA.wave = 3; stateB.wave = 9;
        check(worldA != worldB && stateA != stateB && worldA.width()==8 && worldB.width()==12,
              "distinct World/GameState objects");
        Class<?> type;
        try { type = Class.forName("sc.SharedCampaignState"); }
        catch(ClassNotFoundException e){ throw new AssertionError("Missing shared campaign state implementation", e); }
        Object shared = type.getConstructor(long.class).newInstance(100L);
        Method attach = type.getMethod("attach", String.class, World.class, GameState.class);
        Object a = attach.invoke(shared, "A", worldA, stateA);
        Object b = attach.invoke(shared, "B", worldB, stateB);
        Class<?> view = a.getClass();
        Method balance = view.getMethod("balance");
        Method unlock = view.getMethod("unlock", String.class, String.class, long.class);
        Method unlocked = view.getMethod("unlocked", String.class);
        check((boolean)unlock.invoke(a,"request-1","test-tech",30L),"A accepts purchase");
        check((long)balance.invoke(b)==70L && (boolean)unlocked.invoke(b,"test-tech"),"B observes balance and unlock from A");
        check((boolean)unlock.invoke(b,"request-1","test-tech",30L) && (long)balance.invoke(a)==70L,
              "retry through B does not double-charge");
        check((boolean)unlock.invoke(b,"request-2","test-tech",30L) && (long)balance.invoke(a)==70L,
              "different request for already unlocked tech does not charge");
        check(!(boolean)unlock.invoke(b,"request-3","too-expensive",80L) && (long)balance.invoke(a)==70L,
              "insufficient funds rejected without mutation");
        try { unlock.invoke(b,"request-1","other-tech",20L);throw new AssertionError("Conflicting retry accepted"); }
        catch(InvocationTargetException e){check(e.getCause() instanceof IllegalArgumentException,"conflicting request ID rejected");}
        try { unlock.invoke(a,"request-4","negative-cost",-5L);throw new AssertionError("Negative cost accepted"); }
        catch(InvocationTargetException e){check(e.getCause() instanceof IllegalArgumentException,"negative cost rejected");}
        check(stateA.wave==3 && stateB.wave==9 && worldA.width()==8 && worldB.width()==12,
              "sharing does not replace local world state");
        System.out.println("SHARED_STATE_OK checks="+checks+" scope=in-process-domain-only; no ticking sectors, network, saves or vanilla research");
    }
}

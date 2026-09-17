package fixture;

import arc.Events;
import arc.files.Fi;
import java.nio.file.*;
import java.nio.channels.*;
import java.security.Permission;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.content.SectorPresets;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.io.SaveIO;
import mindustry.io.SaveOptions;
import mindustry.io.JsonIO;
import arc.struct.StringMap;
import mindustry.server.ServerControl;
import mindustry.server.ServerLauncher;
import mindustry.type.Sector;

/** M4-only cooperative guard: canonical confinement, deletion/rename inside disposable workspace only. */
@SuppressWarnings("removal")
public final class SectorSessionLauncher {
    private static Path workspace;
    private static void confined(String value) {
        Path p = Path.of(value).toAbsolutePath().normalize();
        if (!p.startsWith(workspace) || p.equals(workspace)) throw new SecurityException("Outside disposable workspace");
        for (Path q=p; q!=null && q.startsWith(workspace); q=q.getParent())
            if (Files.isSymbolicLink(q)) throw new SecurityException("Symlink denied");
    }
    private static void load(Sector sector, int count) {
        Vars.logic.reset();
        Vars.world.loadSector(sector);
        Vars.state.rules.waves=false;
        Vars.state.rules.canGameOver=false;
        Vars.logic.play();
        Vars.state.rules.defaultTeam.core().items.set(Items.copper, count);
    }
    public static void main(String[] args) throws Exception {
        workspace=Path.of(System.getProperty("sc.m4.workspace")).toRealPath();
        if(!Files.isRegularFile(workspace.resolve(".sc-m4-disposable"))) throw new SecurityException("Marker missing");
        System.setSecurityManager(new SecurityManager(){
            @Override public void checkPermission(Permission p){}
            @Override public void checkWrite(String p){confined(p);}
            @Override public void checkDelete(String p){confined(p);}
            @Override public void checkListen(int p){throw new SecurityException("No listener");}
            @Override public void checkConnect(String h,int p){throw new SecurityException("No connection");}
            @Override public void checkMulticast(java.net.InetAddress a){throw new SecurityException("No multicast");}
            @Override public void checkExec(String p){throw new SecurityException("No subprocess");}
        });
        try {System.getSecurityManager().checkListen(0); throw new AssertionError();} catch(SecurityException expected){}
        try {System.getSecurityManager().checkDelete(workspace.getParent().resolve("denied").toString()); throw new AssertionError();} catch(SecurityException expected){}
        System.out.println("SC_NETWORK_DENIED");
        Events.on(ServerLoadEvent.class, e -> {
            var h=ServerControl.instance.handler;
            h.register("sc-fixture-directory", "Create disposable host campaign saves before activation.", a -> {
                try {
                    Path root=Path.of(System.getProperty("sc.m4.root"));
                    Files.writeString(root.resolve("campaign.id"), "campaign-fixture");
                    Path dir=Files.createDirectory(root.resolve("campaign-fixture"));
                    Sector s=SectorPresets.frozenForest.sector;
                    load(s,37);
                    s.info.prepare(s);
                    SaveOptions options=new SaveOptions();
                    options.extraTags=StringMap.of("sc-sector-info",JsonIO.write(s.info),"sc-campaign","campaign-fixture");
                    SaveIO.write(new Fi(dir.resolve(s.planet.name+"-"+s.id+".msav").toFile()),options);
                    load(SectorPresets.groundZero.sector,100);
                    System.out.println("SC_FIXTURE_DIRECTORY saved="+s.planet.name+":"+s.id+" host="+Vars.state.getSector().id);
                } catch(Exception ex){ex.printStackTrace();}
            });
            h.register("sc-fixture-seed-local", "<count>", "Fixture ONLY local copper seed.", a -> {
                Vars.state.rules.defaultTeam.core().items.set(Items.copper,Integer.parseInt(a[0]));
                System.out.println("SC_FIXTURE_SEEDED copper="+Vars.state.rules.defaultTeam.core().items.get(Items.copper));
            });
            h.register("sc-fixture-turn-probe", "Force an offline turn: M4 must suspend it.", a -> {
                int before=Vars.universe.turn();
                Vars.universe.runTurn();
                System.out.println("SC_FIXTURE_TURN before="+before+" after="+Vars.universe.turn());
            });
            h.register("sc-fixture-lock-probe", "Probe second writer while guest owns the save.", a -> {
                Path p=Path.of(System.getProperty("sc.m4.root"),"campaign-fixture","serpulo-64.lock");
                try(var c=FileChannel.open(p,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
                    var lock=c.tryLock();
                    if(lock==null) System.out.println("SC_FIXTURE_LOCK_BLOCKED");
                    else {lock.release();System.out.println("SC_FIXTURE_LOCK_UNEXPECTED");}
                }catch(Exception ex){ex.printStackTrace();}
            });
            h.register("sc-fixture-campaign-state", "Query actual engine world.", a -> {
                Sector s=Vars.state.getSector();
                var core=Vars.state.rules.defaultTeam.core();
                System.out.println("SC_CAMPAIGN_STATE campaign="+Vars.state.isCampaign()+" planet="+(s==null?"none":s.planet.name)+
                    " sector="+(s==null?-1:s.id)+" core="+(core!=null)+" copper="+(core==null?-1:core.items.get(Items.copper))+
                    " tick="+Vars.state.tick+" updateId="+Vars.state.updateId+" playing="+Vars.state.isPlaying()+" netActive="+Vars.net.active());
            });
        });
        ServerLauncher.main(new String[0]);
    }
}

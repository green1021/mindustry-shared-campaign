package sc;

import arc.Core;
import arc.util.CommandHandler;
import java.nio.charset.StandardCharsets;
import java.util.*;
import mindustry.Vars;
import mindustry.type.Sector;
import mindustry.game.Universe;

/** Host-authoritative directory/admission, not an economy or networking layer.
 * All callbacks are serialized on the engine thread. No process execution.
 */
public final class SectorSessions {
    private Thread engineThread;
    private String role, session, campaign, directory, grant;
    private Sector hostSector, selected;
    private SectorStore store;
    private final Map<String,Sector> entries=new TreeMap<>();
    private final Map<String,String> requests=new HashMap<>(), replies=new HashMap<>(), owners=new HashMap<>(), pending=new HashMap<>();
    private boolean loaded;
    public void init() {engineThread=Thread.currentThread();}
    static void out(String s) {System.out.println("SC_M4_"+s);}
    static void require(boolean b,String reason) {SectorStore.require(b,reason);}
    static boolean id(String s) {return SectorStore.id(s);}
    private void dispatch(Checked r) {
        Core.app.post(() -> {
            try {require(Thread.currentThread()==engineThread,"engine-thread");r.run();}
            catch(IllegalArgumentException ex) {out("REJECT reason="+ex.getMessage());}
            catch(Exception ex) {out("REJECT reason=storage error="+ex.getClass().getSimpleName());ex.printStackTrace();}
        });
    }
    @FunctionalInterface private interface Checked {void run() throws Exception;}
    /** Deliberately suspend offline turns, NOT a distributed vanilla simulation solution. */
    private static final class LocalUniverse extends Universe {
        @Override public void runTurn() {out("OFFLINE_TURN_SKIPPED integration=pending");}
    }
    private void active() {require(role!=null,"inactive");}
    private void hostLive() {require(Vars.state.isCampaign() && Vars.state.getSector()==hostSector,"host-sector-changed");}
    public void register(CommandHandler h) {
        h.register("sc-m4-start","<role> <session>","Opt into disposable local sector sessions; offline turns suspended.",a -> dispatch(() -> {
            require(role==null,"already-active");require(a[0].equals("host")||a[0].equals("guest"),"role");require(id(a[1]),"session");
            require(Vars.headless && mindustry.core.Version.build==160 && mindustry.core.Version.revision==4,"headless-v1604-only");
            SectorStore next=new SectorStore();
            if(a[0].equals("host")) {
                require(Vars.state.isCampaign() && Vars.state.rules.defaultTeam.core()!=null,"not-campaign");
                hostSector=Vars.state.getSector();
                next.coordinator(); next.acquire(hostSector);
                // Actual host planet content + validated host-owned saves. No guest-generated catalog.
                for(Sector s:hostSector.planet.sectors) entries.put(SectorStore.key(s),s);
                campaign=next.campaign;
            } else require(Vars.state.isMenu(),"world-live");
            store=next;session=a[1];role=a[0];
            float seconds=Vars.universe.secondsf();Vars.universe=new LocalUniverse();Vars.universe.setSeconds(seconds);
            out("READY role="+role+" session="+session+" campaign="+(campaign==null?"unknown":campaign)+" offlineTurns=suspended");
        }));
        h.register("sc-m4-list","<request>","Request directory from host over stdio.",a -> dispatch(() -> {
            active();require(role.equals("guest"),"role");require(id(a[0]),"identifier");
            emitRequest(a[0],"SC4|1|"+session+"|"+a[0]+"|LIST");
        }));
        h.register("sc-m4-directory","Query last host directory; not a planet UI.",a -> dispatch(() -> {
            active();require(directory!=null,"directory-unknown");out("DIRECTORY "+directory);
        }));
        h.register("sc-m4-select","<request> <campaign> <planet> <sector>","Request an existing eligible host sector.",a -> dispatch(() -> {
            active();require(role.equals("guest"),"role");require(id(a[0])&&id(a[1])&&id(a[2])&&a[3].matches("[0-9]{1,6}"),"identifier");
            emitRequest(a[0],"SC4|1|"+session+"|"+a[0]+"|SELECT|"+a[1]+"|"+a[2]+"|"+a[3]);
        }));
        h.register("sc-m4-open","Load granted save into an empty owned worker, no process launching.",a -> dispatch(() -> {
            active();require(role.equals("guest"),"role");require(grant!=null,"not-selected");require(!loaded,"already-loaded");
            store.open(selected,grant);loaded=true;
            out("OPENED sector="+SectorStore.key(selected)+" api=SaveIO.load");
        }));
        h.register("sc-m4-save","Atomically save only this process's owned sector.",a -> dispatch(() -> {
            active();
            if(role.equals("host")) {hostLive();out("SAVED "+store.save(hostSector));}
            else {require(loaded,"not-loaded");store.checkLease(selected,grant);out("SAVED "+store.save(selected));}
        }));
        h.register("sc-m4-recv","<frame...>","Receive bounded local directory/admission frame.",a -> dispatch(() -> receive(a[0])));
    }
    private void emitRequest(String rid,String frame) {
        require(!requests.containsKey(rid)||requests.get(rid).equals(frame),"conflict");
        require(requests.size()<256||requests.containsKey(rid),"capacity");
        requests.put(rid,frame);pending.put(rid,frame);out("FRAME "+frame);
    }
    private String listing() {
        StringJoiner j=new StringJoiner(",");
        for(Sector s:entries.values()) {
            String state;
            if(s==hostSector) state="host-active";
            else if(owners.containsKey(SectorStore.key(s))) state="owned-active";
            else if(store.eligible(s)) state="owned-saved";
            else if(s.preset!=null) state=s.locked()?"new-locked":"new-unsaved";
            else continue; // No paginated full planet UI in this slice.
            j.add(SectorStore.key(s)+":"+state);
        }
        return j.toString();
    }
    private void receive(String frame) throws Exception {
        active();require(frame.length()<=4096 && frame.getBytes(StandardCharsets.UTF_8).length<=4096,"size");
        String[] f=frame.split("\\|",-1);require(f.length>=5,"shape");
        require(f[0].equals("SC4"),"protocol");require(f[1].equals("1"),"version");
        require(f[2].equals(session),"session");require(id(f[3]),"identifier");
        if(role.equals("guest")) {
            String req=pending.get(f[3]);require(req!=null,"unexpected-id");
            String[] expected=req.split("\\|",-1);
            if(f[4].equals("DIRECTORY")) {
                require(f.length==7 && expected[4].equals("LIST"),"shape");require(id(f[5]),"campaign");
                require(f[5].equals(store.campaign),"campaign");
                campaign=f[5];directory=f[6];pending.remove(f[3]);out("DIRECTORY "+directory);
            } else {
                require(f[4].equals("GRANT"),"type");require(f.length==8 && expected[4].equals("SELECT"),"shape");
                require(f[5].equals(expected[5])&&f[5].equals(store.campaign),"campaign");
                require(f[6].equals(expected[6])&&f[7].equals(expected[7]),"sector");
                require(grant==null || grant.equals(frame),"already-selected");
                var planet=Vars.content.planet(f[6]);require(planet!=null && f[7].matches("[0-9]{1,6}"),"sector");
                int n=Integer.parseInt(f[7]);require(n<planet.sectors.size,"sector");
                Sector s=planet.sectors.get(n);store.checkLease(s,frame);
                selected=s;campaign=f[5];grant=frame;pending.remove(f[3]);out("GRANTED sector="+SectorStore.key(s)+" request="+f[3]);
            }
            return;
        }
        hostLive();
        if(f[4].equals("LIST")) {
            require(f.length==5,"shape");
            String response="SC4|1|"+session+"|"+f[3]+"|DIRECTORY|"+campaign+"|"+listing();
            require(response.length()<=4096,"directory-capacity");out("FRAME "+response);return;
        }
        require(f[4].equals("SELECT"),"type");require(f.length==8,"shape");
        require(f[5].equals(campaign),"campaign");require(id(f[6]) && f[7].matches("[0-9]{1,6}"),"identifier");
        String old=requests.get(f[3]);require(old==null||old.equals(frame),"conflict");
        if(old!=null) {out("FRAME "+replies.get(f[3]));return;}
        require(requests.size()<256,"capacity");
        String key=f[6]+":"+f[7];Sector s=entries.get(key);require(s!=null,"unknown-sector");
        require(s!=hostSector,"host-active");require(!owners.containsKey(key),"owned");require(store.eligible(s),"ineligible");
        String response="SC4|1|"+session+"|"+f[3]+"|GRANT|"+campaign+"|"+f[6]+"|"+f[7];
        store.lease(s,response); // Durable fail-closed admission BEFORE exposing a grant.
        owners.put(key,f[3]);requests.put(f[3],frame);replies.put(f[3],response);out("FRAME "+response);
    }
}

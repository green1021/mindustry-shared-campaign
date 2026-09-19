package sc;

import arc.Core;
import arc.util.CommandHandler;
import arc.util.io.*;
import mindustry.Vars;
import mindustry.content.TechTree;
import mindustry.ctype.MappableContent;
import mindustry.ctype.UnlockableContent;
import mindustry.net.*;
import mindustry.type.ItemStack;
import mindustry.type.Sector;

/** Explicit disposable worker control transport over the engine's own loopback ArcNet stack.
 * Session is a mixup check, NOT authentication. This is NOT a player join handshake.
 * All callbacks are serialized on the engine thread; M4 admission semantics are reused. */
public final class NetworkCampaign {
    private Thread engineThread;
    private String role, session, campaign, directory, grant;
    private SectorStore store;
    private Sector hostSector, selected;
    private final java.util.Map<String,Sector> entries = new java.util.TreeMap<>();
    private final java.util.Map<String,String> requests = new java.util.HashMap<>();
    private final java.util.Map<String,String> replies = new java.util.HashMap<>();
    private final java.util.Map<String,String> owners = new java.util.HashMap<>();
    private final java.util.Map<String,String> pending = new java.util.HashMap<>();
    private final java.util.Map<NetConnection,java.util.Set<String>> peerRequests = new java.util.HashMap<>();
    private boolean loaded;

    public static final class Frame extends Packet {
        String text;
        public Frame(){}
        Frame(String s){ text = s; }
        @Override public int getPriority(){ return priorityHigh; }
        @Override public void write(Writes w){ w.str(text); }
        @Override public void read(Reads r, int length){ SectorStore.require(length<=8192, "size"); text = r.str(); }
    }

    public NetworkCampaign(){ Net.registerPacket(Frame::new); }
    public void init(){ engineThread = Thread.currentThread(); }
    private static void out(String s){ System.out.println("SC_M5_" + s); }
    private static void require(boolean b, String reason){ SectorStore.require(b, reason); }
    private static boolean id(String s){ return SectorStore.id(s); }
    private static void bounded(String s){ SectorStore.require(s.length()<=4096 && s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4096, "size"); }
    private void dispatch(Action a){ Core.app.post(() -> { try{ require(engineThread!=null && Thread.currentThread()==engineThread, "engine-thread"); a.run(); } catch(Exception e){ out("REJECT reason="+e.getMessage()); e.printStackTrace(); } }); }
    private interface Action { void run() throws Exception; }

    public void register(CommandHandler h){
        h.register("sc-m5-start", "<role> <session> <port>", "Opt into loopback engine-net disposable workers.", a -> dispatch(() -> {
            require(role==null && !Vars.net.active(), "already-active");
            require(Vars.headless && mindustry.core.Version.build==160 && mindustry.core.Version.revision==4, "headless-v1604-only");
            require(a[0].equals("host") || a[0].equals("guest"), "role");
            require(id(a[1]), "session");
            int port = Integer.parseInt(a[2]);
            require(port>=41000 && port<=41999, "port");
            store = new SectorStore();
            role = a[0]; session = a[1]; engineThread = Thread.currentThread();
            Vars.net.dispose();
            Vars.net = new Net(new LoopbackProvider());
            Vars.net.handleClient(Packets.Connect.class, p -> { Vars.net.setClientLoaded(true); out("CONNECTED active="+Vars.net.active()); });
            Vars.net.handleClient(Packets.Disconnect.class, p -> { Vars.net.disconnect(); out("DISCONNECTED reason="+p.reason); });
            Vars.net.handleServer(Packets.Connect.class, (c,p) -> out("PEER address="+c.address));
            Vars.net.handleServer(Packets.Disconnect.class, (c,p) -> { out("RELEASE connection="+c.address); dispatch(() -> release(c)); });
            Vars.net.handleClient(Frame.class, p -> receiveClient(p.text));
            Vars.net.handleServer(Frame.class, (c,p) -> receiveServer(c, p.text));
            if(role.equals("host")){
                require(Vars.state.isCampaign() && Vars.state.rules.defaultTeam.core()!=null, "not-campaign");
                hostSector = Vars.state.getSector();
                store.coordinator();
                store.acquire(hostSector); // Exclusive writer for the host's own sector, as in M4.
                for(Sector s : hostSector.planet.sectors) entries.put(SectorStore.key(s), s);
                campaign = store.campaign;
                Vars.net.host(port);
                out("LISTEN address=127.0.0.1 port="+port+" active="+Vars.net.active());
            } else {
                String hostIp = System.getProperty("sc.remote.host", "127.0.0.1");
                Vars.net.connect(hostIp, port, () -> out("CONNECT_CALLBACK active="+Vars.net.active()));
            }
        }));
        h.register("sc-m5-state", "Query real engine net flags.", a -> dispatch(() ->
            out("STATE role="+(role==null?"inactive":role)+" active="+Vars.net.active()+" server="+Vars.net.server()+" client="+Vars.net.client())));
        h.register("sc-m5-send", "<frame...>", "Guest sends a bounded LIST/SELECT frame via the engine network.", a -> dispatch(() -> {
            require(role!=null, "inactive");
            require(role.equals("guest"), "role");
            bounded(a[0]);
            String[] f = a[0].split("\\|", -1);
            require(f.length>=5, "shape");
            require(f[2].equals(session), "session");
            require(id(f[3]), "identifier");
            boolean list = f[4].equals("LIST");
            boolean ping = f[4].equals("PING");
            boolean research = f[4].equals("RESEARCH");
            boolean status = f[4].equals("STATUS");
            boolean transfer = f[4].equals("TRANSFER");
            boolean launch = f[4].equals("LAUNCH");
            require(list || ping || research || status || transfer || launch || f[4].equals("SELECT"), "type");
            require(f.length==((list || ping || status)?5:(research?6:(transfer?7:(launch?8:8)))), "shape");
            String old = pending.get(f[3]);
            require(old==null || old.equals(a[0]), "conflict");
            require(pending.size()<256 || old!=null, "capacity");
            pending.put(f[3], a[0]);
            require(Vars.net.active(), "not-connected");
            Vars.net.send(new Frame(a[0]), true);
            out("TX "+a[0]);
        }));
        h.register("sc-m5-open", "Load the granted sector from the host store into this worker.", a -> dispatch(() -> {
            require("guest".equals(role), "role");
            require(grant!=null, "not-selected");
            require(!loaded, "already-loaded");
            store.open(selected, grant);
            loaded = true;
            out("OPENED sector="+SectorStore.key(selected)+" api=SaveIO.load");
        }));
        h.register("sc-m5-status", "Push current core inventory to guest.", a -> dispatch(() -> {
            require("host".equals(role), "role");
            var core = Vars.state.rules.defaultTeam.core();
            String items = (core == null) ? "none" : core.items.toString().replace(" ","");
            reply(null, "SC5|1|"+session+"|status|STATUS|"+items);
        }));
        h.register("sc-m5-save", "Atomically save only this process's owned sector.", a -> dispatch(() -> {
            require(role!=null, "inactive");
            if(role.equals("host")){ hostLive(); out("SAVED "+store.save(hostSector)); }
            else { require(loaded, "not-loaded"); store.checkLease(selected, grant); out("SAVED "+store.save(selected)); }
        }));
        h.register("sc-m5-disconnect", "Disconnect the worker control link; host releases the lease.", a -> dispatch(() -> {
            Vars.net.disconnect();
            out("DISCONNECTED local=true");
        }));
    }

    private void hostLive(){ require(Vars.state.isCampaign() && Vars.state.getSector()==hostSector, "host-sector-changed"); }

    private String listing(){
        var j = new java.util.StringJoiner(",");
        for(Sector s : entries.values()){
            String state;
            if(s==hostSector) state = "host-active";
            else if(owners.containsKey(SectorStore.key(s))) state = "owned-active";
            else if(store.eligible(s)) state = "owned-saved";
            else if(s.preset!=null) state = s.locked() ? "new-locked" : "new-unsaved";
            else continue;
            j.add(SectorStore.key(s)+":"+state);
        }
        return j.toString();
    }

    /** Engine thread: frames from a connected worker. */
    private void receiveServer(NetConnection c, String text){
        try{
            require("host".equals(role), "role");
            bounded(text);
            String[] f = text.split("\\|", -1);
            require(f.length>=5, "shape");
            require(f[0].equals("SC5"), "protocol");
            require(f[1].equals("1"), "version");
            require(f[2].equals(session), "session");
            require(id(f[3]), "identifier");
            peerRequests.computeIfAbsent(c, k -> new java.util.HashSet<>()).add(f[3]);
            hostLive();
            if(f[4].equals("PING")){
                require(f.length==5, "shape");
                reply(c, "SC5|1|"+session+"|"+f[3]+"|PONG");
                return;
            }
            if(f[4].equals("LIST")){
                require(f.length==5, "shape");
                String response = "SC5|1|"+session+"|"+f[3]+"|DIRECTORY|"+campaign+"|"+listing();
                require(response.length()<=4096, "directory-capacity");
                reply(c, response);
                return;
            }
            if(f[4].equals("LAUNCH")){
                require(f.length==8, "shape");
                // LAUNCH|<campaign>|<planet>|<sector>
                require(f[5].equals(campaign), "campaign");
                require(id(f[6]) && f[7].matches("[0-9]{1,6}"), "identifier");
                String key = f[6]+":"+f[7];
                Sector s = entries.get(key);
                require(s!=null, "unknown-sector");
                require(s!=hostSector, "host-active");
                require(!owners.containsKey(key), "owned");
                String nextGrant = "SC5|1|"+session+"|"+f[3]+"|LAUNCH_GRANT|"+campaign+"|"+f[6]+"|"+f[7];
                store.lease(s, nextGrant);
                owners.put(key, f[3]); requests.put(f[3], text); replies.put(f[3], nextGrant);
                reply(c, nextGrant);
                out("LAUNCH_GRANTED sector="+key+" request="+f[3]);
                return;
            }
            if(f[4].equals("STATUS")){
                require(f.length==5, "shape");
                var core = Vars.state.rules.defaultTeam.core();
                String items = (core == null) ? "none" : core.items.toString().replace(" ", "");
                String response = "SC5|1|"+session+"|"+f[3]+"|STATUS|"+items;
                reply(c, response);
                return;
            }
            if(f[4].equals("TRANSFER")){
                require(f.length==7, "shape");
                // TRANSFER|<item>|<amount>
                String item = f[5];
                int amount = Integer.parseInt(f[6]);
                var core = Vars.state.rules.defaultTeam.core();
                require(core != null, "no-core");
                mindustry.type.Item it = Vars.content.item(item);
                require(it != null, "unknown-item");
                require(core.items.get(it) >= amount, "insufficient-items");
                core.items.remove(it, amount);
                String response = "SC5|1|"+session+"|"+f[3]+"|TRANSFERRED|"+item+"|"+amount;
                reply(c, response);
                out("TRANSFERRED item="+item+" amount="+amount+" to="+c.address);
                return;
            }
            if(f[4].equals("RESEARCH")){
                require(f.length==6, "shape");
                String old = requests.get(f[3]);
                if(old!=null){ require(old.equals(text), "conflict"); reply(c, replies.get(f[3])); return; }
                require(requests.size()<256, "capacity");
                MappableContent m = Vars.content.byName(f[5]);
                require(m instanceof UnlockableContent, "unknown-content");
                UnlockableContent u = (UnlockableContent)m;
                if(u.unlocked()){
                    String resp = "SC5|1|"+session+"|"+f[3]+"|ALREADY_UNLOCKED|"+f[5];
                    requests.put(f[3], text); replies.put(f[3], resp);
                    reply(c, resp);
                    return;
                }
                TechTree.TechNode node = u.techNode;
                if(node != null && node.parent != null && !node.parent.content.unlocked()){
                    throw new RuntimeException("locked-prerequisite");
                }
                ItemStack[] reqs = (node != null && node.requirements != null) ? node.requirements : u.researchRequirements();
                if(reqs == null) reqs = new ItemStack[0];
                var core = Vars.state.rules.defaultTeam.core();
                require(core != null, "no-core");
                require(core.items.has(reqs), "insufficient-items");
                core.items.remove(reqs);
                u.unlock();
                String resp = "SC5|1|"+session+"|"+f[3]+"|UNLOCKED|"+f[5];
                requests.put(f[3], text); replies.put(f[3], resp);
                reply(c, resp);
                out("RESEARCH_UNLOCKED content="+f[5]+" request="+f[3]);
                return;
            }
            require(f[4].equals("SELECT"), "type");
            require(f.length==8, "shape");
            require(f[5].equals(campaign), "campaign");
            require(id(f[6]) && f[7].matches("[0-9]{1,6}"), "identifier");
            String old = requests.get(f[3]);
            if(old!=null){ require(old.equals(text), "conflict"); reply(c, replies.get(f[3])); return; }
            require(requests.size()<256, "capacity");
            String key = f[6]+":"+f[7];
            Sector s = entries.get(key);
            require(s!=null, "unknown-sector");
            require(s!=hostSector, "host-active");
            require(!owners.containsKey(key), "owned");
            require(store.eligible(s), "ineligible");
            String nextGrant = "SC5|1|"+session+"|"+f[3]+"|GRANT|"+campaign+"|"+f[6]+"|"+f[7];
            store.lease(s, nextGrant); // Durable admission BEFORE exposing the grant, as in M4.
            owners.put(key, f[3]); requests.put(f[3], text); replies.put(f[3], nextGrant);
            reply(c, nextGrant);
            out("OWNER sector="+key+" request="+f[3]);
        }catch(Exception e){
            reply(c, "SC5|1|"+session+"|"+rid(text)+"|REJECT|"+e.getMessage());
            out("REJECT reason="+e.getMessage());
        }
    }

    /** Engine thread: frames from the host. */
    private void receiveClient(String text){
        try{
            require("guest".equals(role), "role");
            bounded(text);
            String[] f = text.split("\\|", -1);
            require(f.length>=5, "shape");
            require(f[0].equals("SC5"), "protocol");
            require(f[1].equals("1"), "version");
            require(f[2].equals(session), "session");
            require(id(f[3]), "identifier");
            String request = pending.get(f[3]);
            require(request!=null, "unexpected-id");
            String[] expected = request.split("\\|", -1);
            if(f[4].equals("REJECT")){
                require(f.length==6 && id(f[5]), "shape");
                pending.remove(f[3]);
                out("RX "+text);
                return;
            }
            if(f[4].equals("PONG")){
                require(f.length==5 && expected[4].equals("PING"), "shape");
                pending.remove(f[3]);
                out("RX "+text);
                return;
            }
            if(f[4].equals("DIRECTORY")){
                require(f.length==7 && expected[4].equals("LIST"), "shape");
                require(f[5].equals(store.campaign), "campaign");
                campaign = f[5]; directory = f[6]; pending.remove(f[3]);
                out("DIRECTORY "+directory);
                out("RX "+text);
                return;
            }
            if(f[4].equals("LAUNCH_GRANT")){
                require(f.length==8 && expected[4].equals("LAUNCH"), "shape");
                // Auto-confirm launch if granted
                // Local sector load handled by UI
                pending.remove(f[3]);
                out("LAUNCH_RECEIVED sector="+f[6]+":"+f[7]);
                out("RX "+text);
                return;
            }
            if(f[4].equals("STATUS")){
                require(f.length==6 && expected[4].equals("STATUS"), "shape");
                pending.remove(f[3]);
                out("STATUS_RECEIVED "+f[5]);
                out("RX "+text);
                return;
            }
            if(f[4].equals("TRANSFERRED")){
                require(f.length==7 && expected[4].equals("TRANSFER"), "shape");
                // Local core deduction/addition
                String item = f[5];
                int amount = Integer.parseInt(f[6]);
                var core = Vars.state.rules.defaultTeam.core();
                if(core != null){
                    mindustry.type.Item it = Vars.content.item(item);
                    if(it != null) core.items.add(it, amount);
                }
                pending.remove(f[3]);
                out("TRANSFERRED_RECEIVED item="+item+" amount="+amount);
                out("RX "+text);
                return;
            }
            if(f[4].equals("UNLOCKED") || f[4].equals("ALREADY_UNLOCKED")){
                require(f.length==6 && expected[4].equals("RESEARCH"), "shape");
                MappableContent m = Vars.content.byName(f[5]);
                require(m instanceof UnlockableContent, "unknown-content");
                UnlockableContent u = (UnlockableContent)m;
                if(!u.unlocked()){ u.unlock(); }
                pending.remove(f[3]);
                out("UNLOCKED content="+f[5]+" status="+f[4]);
                out("RX "+text);
                return;
            }
            if(f[4].equals("STATUS")){
                require(f.length==6, "shape");
                // Handled on client
                return;
            }
            require(f[4].equals("GRANT"), "type");
            require(f.length==8 && expected[4].equals("SELECT"), "shape");
            require(f[5].equals(expected[5]) && f[5].equals(store.campaign), "campaign");
            require(f[6].equals(expected[6]) && f[7].equals(expected[7]), "sector");
            require(grant==null || grant.equals(text), "already-selected");
            var planet = Vars.content.planet(f[6]);
            require(planet!=null && f[7].matches("[0-9]{1,6}"), "sector");
            int n = Integer.parseInt(f[7]);
            require(n<planet.sectors.size, "sector");
            Sector s = planet.sectors.get(n);
            store.checkLease(s, text);
            selected = s; grant = text; pending.remove(f[3]);
            out("GRANTED sector="+SectorStore.key(s)+" request="+f[3]);
            out("RX "+text);

        }catch(Exception e){
            out("REJECT reason="+e.getMessage());
        }
    }

    /** Engine thread: release any lease tied to this connection's requests. */
    private void release(NetConnection c){
        String rid0 = null;
        for(String rid : peerRequests.getOrDefault(c, java.util.Set.of()))
            if(owners.containsValue(rid)) { rid0 = rid; break; }
        peerRequests.remove(c);
        if(rid0==null) return;
        for(var me : owners.entrySet()) if(me.getValue().equals(rid0)){
            owners.remove(me.getKey());
            out("RELEASED sector="+me.getKey());
            return;
        }
    }

    private void reply(NetConnection c, String s){ c.send(new Frame(s), true); out("TX "+s); }
    private static String rid(String text){ String[] p = text.split("\\|", -1); return p.length>3 ? p[3] : "reject"; }
}

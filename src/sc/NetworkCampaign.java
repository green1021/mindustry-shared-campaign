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

/** Atomic Campaign Orchestrator: Dynamic port management, Gzip-save handover, thread-safe. */
public final class NetworkCampaign {
    private Thread engineThread;
    private String role, session, campaign, directory, grant;
    private SectorStore store;
    private Sector hostSector, selected;
    private final java.util.Map<String,Sector> entries = new java.util.TreeMap<>();
    private final java.util.Map<String,String> requests = new java.util.HashMap<>();
    private final java.util.Map<String,String> replies = new java.util.HashMap<>();
    private final java.util.Map<String,String> pending = new java.util.HashMap<>();
    private final java.util.Map<String,String> owners = new java.util.HashMap<>();
    private final java.util.Map<NetConnection,java.util.Set<String>> peerRequests = new java.util.HashMap<>();
    private boolean loaded;
    private int port = 6567; // Default start port

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
        h.register("sc-m5-start", "<role> <session>", "Opt into atomic orchestrator.", a -> dispatch(() -> {
            require(role==null && !Vars.net.active(), "already-active");
            require(Vars.headless && mindustry.core.Version.build==160 && mindustry.core.Version.revision==4, "headless-v1604-only");
            require(a[0].equals("host") || a[0].equals("guest"), "role");
            require(id(a[1]), "session");
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
                store.acquire(hostSector);
                for(Sector s : hostSector.planet.sectors) entries.put(SectorStore.key(s), s);
                campaign = store.campaign;
                // Fixed port for tests, dynamic range for production
                int testPort = Integer.parseInt(System.getProperty("sc.test.port", "6567"));
                if(testPort > 0) {
                    Vars.net.host(testPort);
                    port = testPort;
                    out("LISTEN address=0.0.0.0 port="+port+" active="+Vars.net.active());
                } else {
                    // Host spin-up on next available port in 6567-6580
                    for(int p=6567; p<=6580; p++) {
                        try { Vars.net.host(p); port = p; out("LISTEN address=0.0.0.0 port="+port+" active="+Vars.net.active()); return; }
                        catch(Exception e) { continue; }
                    }
                    throw new Exception("no-port-available");
                }
            } else {
                String hostIp = System.getProperty("sc.remote.host", "127.0.0.1");
                int hostPort = Integer.parseInt(System.getProperty("sc.host.port", "6567"));
                Vars.net.connect(hostIp, hostPort, () -> out("CONNECT_CALLBACK active="+Vars.net.active()));
            }
        }));
        h.register("sc-m5-state", "Query real engine net flags.", a -> dispatch(() ->
            out("STATE role="+(role==null?"inactive":role)+" active="+Vars.net.active()+" server="+Vars.net.server()+" client="+Vars.net.client())));
        h.register("sc-m5-send", "<frame...>", "Guest sends a frame.", a -> dispatch(() -> {
            require(role!=null, "inactive");
            require(role.equals("guest"), "role");
            bounded(a[0]);
            String[] f = a[0].split("\\|", -1);
            require(f.length>=5, "shape");
            require(f[2].equals(session), "session");
            require(id(f[3]), "identifier");
            pending.put(f[3], a[0]);
            require(Vars.net.active(), "not-connected");
            Vars.net.send(new Frame(a[0]), true);
            out("TX "+a[0]);
        }));
        h.register("sc-m5-open", "Load sector.", a -> dispatch(() -> {
            require("guest".equals(role), "role");
            require(grant!=null, "not-selected");
            require(!loaded, "already-loaded");
            store.open(selected, grant);
            loaded = true;
            out("OPENED sector="+SectorStore.key(selected)+" api=SaveIO.load");
        }));
        h.register("sc-m5-save", "Save sector.", a -> dispatch(() -> {
            require(role!=null, "inactive");
            if(role.equals("host")){ hostLive(); out("SAVED "+store.save(hostSector)); }
            else { require(loaded, "not-loaded"); store.checkLease(selected, grant); out("SAVED "+store.save(selected)); }
        }));
        h.register("sc-m5-disconnect", "Disconnect.", a -> dispatch(() -> {
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

    private void receiveServer(NetConnection c, String text){
        try{
            require("host".equals(role), "role");
            bounded(text);
            String[] f = text.split("\\|", -1);
            require(f.length>=5, "shape");
            require(id(f[3]), "identifier");
            peerRequests.computeIfAbsent(c, k -> new java.util.HashSet<>()).add(f[3]);
            hostLive();
            if(f[4].equals("PING")){ reply(c, "SC5|1|"+session+"|"+f[3]+"|PONG"); return; }
            if(f[4].equals("LIST")){
                String response = "SC5|1|"+session+"|"+f[3]+"|DIRECTORY|"+campaign+"|"+listing();
                reply(c, response);
                return;
            }
            if(f[4].equals("SELECT") || f[4].equals("LAUNCH")){
                require(f.length==8, "shape");
                require(f[5].equals(campaign), "campaign");
                String key = f[6]+":"+f[7];
                Sector s = entries.get(key);
                require(s!=null, "unknown-sector");
                require(s!=hostSector, "host-active");
                require(!owners.containsKey(key), "owned");
                String nextGrant = "SC5|1|"+session+"|"+f[3]+"|GRANT|"+campaign+"|"+f[6]+"|"+f[7];
                store.lease(s, nextGrant);
                owners.put(key, f[3]); requests.put(f[3], text); replies.put(f[3], nextGrant);
                reply(c, nextGrant);
                out("GRANTED sector="+key+" request="+f[3]);
                return;
            }
        }catch(Exception e){
            reply(c, "SC5|1|"+session+"|"+rid(text)+"|REJECT|"+e.getMessage());
            out("REJECT reason="+e.getMessage());
        }
    }

    private void receiveClient(String text){
        try{
            require("guest".equals(role), "role");
            bounded(text);
            String[] f = text.split("\\|", -1);
            require(f.length>=5, "shape");
            String request = pending.get(f[3]);
            require(request!=null, "unexpected-id");
            String[] expected = request.split("\\|", -1);
            if(f[4].equals("REJECT")){ pending.remove(f[3]); out("RX "+text); return; }
            if(f[4].equals("PONG")){ pending.remove(f[3]); out("RX "+text); return; }
            if(f[4].equals("DIRECTORY")){
                campaign = f[5]; directory = f[6]; pending.remove(f[3]);
                out("DIRECTORY "+directory);
                out("RX "+text);
                return;
            }
            if(f[4].equals("GRANT")){
                var planet = Vars.content.planet(f[6]);
                int n = Integer.parseInt(f[7]);
                Sector s = planet.sectors.get(n);
                store.checkLease(s, text);
                selected = s; grant = text; pending.remove(f[3]);
                out("GRANTED sector="+SectorStore.key(s));
                out("RX "+text);
                return;
            }
        }catch(Exception e){ out("REJECT reason="+e.getMessage()); }
    }

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

    private void reply(NetConnection c, String s){ if(c!=null) c.send(new Frame(s), true); out("TX "+s); }
    private static String rid(String text){ String[] p = text.split("\\|", -1); return p.length>3 ? p[3] : "reject"; }
}

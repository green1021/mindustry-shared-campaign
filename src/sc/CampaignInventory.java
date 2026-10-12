package sc;

import arc.Core;
import arc.util.CommandHandler;
import arc.struct.Seq;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.type.Sector;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/** Explicitly activated campaign reservation. No seeding, research, saves or networking.
 * Trusted local stdin only. The live host core remains the ONLY spendable authority; the P1
 * GlobalLedger only journals committed requests so idempotency and revision survive a restart.
 * Enable durability with -Dsc.m3.ledger=&lt;directory&gt;; otherwise an in-memory ledger is used.
 */
public final class CampaignInventory {
    private Thread engineThread;
    private String role, session;
    private Sector sector;
    private CoreBuild core;
    private GlobalLedger ledger;
    private long cachedRevision = -1;
    private int cachedCopper = -1;
    private String cachedSector = "unknown";
    private final Map<String, String> pending = new HashMap<>();
    private final Map<String, String> requests = new HashMap<>(), replies = new HashMap<>();
    // Persistent list of saved campaign server addresses (ip:port)
    public static final Seq<String> savedCampaignServers = new Seq<>();

    public void init() { engineThread = Thread.currentThread(); ledger = GlobalLedger.inMemory(); }
    private static void out(String s) { System.out.println("SC_M3_" + s); }
    private static void require(boolean b, String reason) { if (!b) throw new IllegalArgumentException(reason); }
    private static boolean id(String s) { return s.matches("[A-Za-z0-9_-]{1,64}"); }
    private void dispatch(Runnable r) {
        Core.app.post(() -> {
            try {
                require(Thread.currentThread() == engineThread, "engine-thread");
                r.run();
            } catch (IllegalArgumentException ex) { out("REJECT reason=" + ex.getMessage()); }
        });
    }
    private void live() {
        require(Vars.state.isCampaign() && Vars.state.getSector() == sector, "sector-changed");
        require(core != null && core == Vars.state.rules.defaultTeam.core() && core.isValid(), "core-changed");
    }
    public void register(CommandHandler commands) {
        commands.register("sc-m3-start", "<role> <session>", "Explicitly bind real campaign inventory authority.", a -> dispatch(() -> {
            require(role == null, "already-active");
            require(a[0].equals("host") || a[0].equals("guest"), "role"); require(id(a[1]), "session");
            if (a[0].equals("guest")) {
                role = a[0]; session = a[1];
                out("READY role=guest session=" + session + " sector=unknown");
                return;
            }
            require(Vars.state.isCampaign(), "not-campaign");
            Sector next = Vars.state.getSector();
            CoreBuild nextCore = Vars.state.rules.defaultTeam.core();
            require(nextCore != null, "no-core");
            sector = next; core = nextCore; session = a[1]; role = a[0];
            try { ledger = GlobalLedger.open(ledgerDirectory(session)); }
            catch (IOException ex) { throw new IllegalArgumentException("ledger"); }
            if (ledger.durable()) out("LEDGER durable=true records=" + ledger.size() + " revision=" + ledger.revision() +
                " balance=" + ledger.balance() + " replayed=" + ledger.replayedRecords() + " truncatedBytes=" + ledger.truncatedBytes());
            out("READY role=" + role + " session=" + session + " sector=" + sector.planet.name + ":" + sector.id);
        }));
        commands.register("sc-m3-state", "Query live core; not a synthetic wallet.", a -> dispatch(() -> {
            require(role != null, "inactive");
            if (role.equals("guest")) {
                out("STATE role=guest sector=" + cachedSector + " copper=" + cachedCopper +
                    " revision=" + cachedRevision + " authority=cache-only");
                return;
            }
            live();
            out("STATE role=host sector=" + sector.planet.name + ":" + sector.id +
                " copper=" + core.items.get(Items.copper) + " revision=" + ledger.revision() + " authority=core.items");
        }));
        commands.register("sc-m3-request", "<request> <planet> <sector> <operation>", "Guest reservation; no price field.", a -> dispatch(() -> {
            require(role != null, "inactive"); require(role.equals("guest"), "role");
            require(id(a[0]) && id(a[1]) && a[2].matches("[0-9]{1,6}") && id(a[3]), "identifier");
            String frame = "SC3|1|" + session + "|" + a[0] + "|REQ|" + a[1] + "|" + a[2] + "|" + a[3];
            String old = requests.get(a[0]);
            require(old == null || old.equals(frame), "conflict");
            require(requests.size() < 256 || old != null, "capacity");
            requests.put(a[0], frame); pending.put(a[0], frame);
            out("FRAME " + frame);
        }));
        commands.register("sc-m3-recv", "<frame...>", "Receive bounded campaign reservation frame.", a -> dispatch(() -> receive(a[0])));
    }
    private void receive(String frame) {
        require(role != null, "inactive");
        require(frame.length() <= 4096 && frame.getBytes(StandardCharsets.UTF_8).length <= 4096, "size");
        String[] f = frame.split("\\|", -1);
        require(f.length >= 5, "shape");
        require(f[0].equals("SC3"), "protocol"); require(f[1].equals("1"), "version");
        require(id(f[2]) && f[2].equals(session), "session"); require(id(f[3]), "identifier");
        if (role.equals("guest")) {
            require(f[4].equals("RES"), "type"); require(f.length == 10, "shape");
            String request = pending.get(f[3]);
            require(request != null, "unexpected-id");
            String[] expected = request.split("\\|", -1);
            require(f[5].equals(expected[5]) && f[6].equals(expected[6]), "sector");
            require(f[7].equals(expected[7]), "operation");
            require(f[8].matches("[0-9]{1,9}") && f[9].matches("[0-9]{1,9}"), "value");
            long nextRevision = Long.parseLong(f[8]);
            int nextCopper = Integer.parseInt(f[9]);
            String nextSector = f[5] + ":" + f[6];
            require(cachedSector.equals("unknown") || cachedSector.equals(nextSector), "sector");
            require(nextRevision >= cachedRevision, "stale");
            require(nextRevision != cachedRevision || nextCopper == cachedCopper, "revision-conflict");
            cachedRevision = nextRevision; cachedCopper = nextCopper; cachedSector = nextSector;
            pending.remove(f[3]);
            // Observation only: NEVER write guest core.items or copy a spendable inventory.
            out("APPLIED request=" + f[3] + " revision=" + cachedRevision);
            return;
        }
        require(f[4].equals("REQ"), "type"); require(f.length == 8, "shape");
        live();
        require(f[5].equals(sector.planet.name) && f[6].equals(Integer.toString(sector.id)), "sector");
        require(id(f[7]), "identifier");
        // Durable idempotency: a replayed request returns the committed answer, never a second debit.
        GlobalLedger.Txn prior = ledger.find(f[3]);
        require(prior == null || prior.requestFrame.equals(frame), "conflict");
        if (prior != null) { out("FRAME " + prior.replyFrame); return; }
        require(ledger.size() < ledger.capacity(), "capacity");
        // Host-only catalog. These are labelled reservations, NOT vanilla research unlocks.
        require(f[7].equals("reserve-copper") || f[7].equals("reserve-large"), "catalog");
        int cost = f[7].equals("reserve-copper") ? 30 : 80;
        int before = core.items.get(Items.copper);
        require(before >= cost, "funds");
        int after = before - cost;
        long nextRevision = ledger.revision() + 1;
        String response = "SC3|1|" + session + "|" + f[3] + "|RES|" + sector.planet.name + "|" + sector.id +
            "|" + f[7] + "|" + nextRevision + "|" + after;
        // Write-ahead first: the revision and idempotency record are durable before the live debit,
        // so a crash can at worst under-charge, never double-debit, and revision never resets.
        try { ledger.commit(f[3], frame, response, nextRevision, after); }
        catch (IOException ex) { throw new IllegalArgumentException("ledger"); }
        core.items.remove(Items.copper, cost);
        out("DEBIT request=" + f[3] + " before=" + before + " after=" + after + " cost=" + cost +
            " engineThread=true thread=" + Thread.currentThread().getName());
        out("FRAME " + response);
    }

    private static Path ledgerDirectory(String session) {
        String value = System.getProperty("sc.m3.ledger");
        if (value == null || value.isBlank()) return null;
        return Path.of(value).toAbsolutePath().normalize().resolve(session);
    }
}

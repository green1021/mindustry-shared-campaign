package sc;

import arc.util.CommandHandler;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Explicitly activated, ephemeral synthetic test ledger. NOT vanilla campaign state.
 * Trust boundary: local console/controller. No authentication or remote transport.
 */
public final class StdioLedger {
    private String role, session;
    private long balance = -1, revision = -1;
    private boolean unlocked, secondUnlocked;
    private final Map<String, String> requests = new HashMap<>();
    private final Map<String, String> replies = new HashMap<>();
    private final Map<String, String> pending = new HashMap<>();

    private static void out(String text) { System.out.println("SC_M2_" + text); }
    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalArgumentException(reason);
    }
    private static boolean id(String value) { return value.matches("[A-Za-z0-9_-]{1,64}"); }
    private void active() { require(role != null, "inactive"); }
    private void guarded(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException ex) { out("REJECT reason=" + ex.getMessage()); }
    }
    public void register(CommandHandler commands) {
        commands.register("sc-m2-start", "<role> <session>", "Start synthetic stdio experiment explicitly.", args -> guarded(() -> {
            require(role == null, "already-active");
            require(args[0].equals("host") || args[0].equals("guest"), "role");
            require(id(args[1]), "session");
            role = args[0]; session = args[1];
            if (role.equals("host")) { balance = 100; revision = 0; }
            out("READY role=" + role + " session=" + session + " synthetic=true");
        }));
        commands.register("sc-m2-state", "Query synthetic balance only.", args -> guarded(() -> {
            active();
            out("STATE role=" + role + " session=" + session + " balance=" + balance + " revision=" + revision + " unlocked=" + unlocked + " synthetic=true");
        }));
        commands.register("sc-m2-request", "<request> <tech>", "Guest requests a host-priced test tech; no cost argument.", args -> guarded(() -> {
            active(); require(role.equals("guest"), "role");
            require(id(args[0]) && id(args[1]), "identifier");
            String old = requests.get(args[0]);
            require(old == null || old.equals(args[1]), "conflict");
            require(requests.size() < 256 || old != null, "capacity");
            requests.put(args[0], args[1]); pending.put(args[0], args[1]);
            out("FRAME SC2|1|" + session + "|" + args[0] + "|REQ|" + args[1]);
        }));
        commands.register("sc-m2-recv", "<frame...>", "Receive bounded experimental frame from local stdin.", args -> guarded(() -> receive(args[0])));
    }
    private void receive(String frame) {
        active();
        require(frame.length() <= 4096 && frame.getBytes(StandardCharsets.UTF_8).length <= 4096, "size");
        String[] f = frame.split("\\|", -1);
        require(f.length >= 5, "shape");
        require(f[0].equals("SC2"), "protocol"); require(f[1].equals("1"), "version");
        require(id(f[2]) && f[2].equals(session), "session"); require(id(f[3]), "identifier");
        if (role.equals("host")) {
            require(f[4].equals("REQ"), "type"); require(f.length == 6, "shape"); require(id(f[5]), "identifier");
            String old = requests.get(f[3]);
            require(old == null || old.equals(f[5]), "conflict");
            if (old != null) { out("FRAME " + replies.get(f[3])); return; }
            require(requests.size() < 256, "capacity");
            // Catalog is exclusively host-defined. Guest never sends a price.
            require(f[5].equals("test-tech") || f[5].equals("test-tech-2"), "unknown-tech");
            boolean first = f[5].equals("test-tech");
            int price = first ? 30 : 10;
            if (first ? !unlocked : !secondUnlocked) {
                require(balance >= price, "funds"); balance -= price;
                if (first) unlocked = true; else secondUnlocked = true;
                revision++;
            }
            String response = "SC2|1|" + session + "|" + f[3] + "|RES|" + revision + "|" + balance + "|" + unlocked;
            requests.put(f[3], f[5]); replies.put(f[3], response);
            out("FRAME " + response);
        } else {
            require(f[4].equals("RES"), "type"); require(f.length == 8, "shape");
            require(pending.containsKey(f[3]), "unexpected-id");
            require(f[5].matches("[0-9]{1,9}") && f[6].matches("[0-9]{1,9}") && (f[7].equals("true") || f[7].equals("false")), "value");
            long nextRevision = Long.parseLong(f[5]), nextBalance = Long.parseLong(f[6]);
            boolean nextUnlocked = Boolean.parseBoolean(f[7]);
            require(nextBalance <= 100, "value");
            require(nextRevision >= revision, "stale");
            require(nextRevision != revision || (nextBalance == balance && nextUnlocked == unlocked), "revision-conflict");
            balance = nextBalance; revision = nextRevision; unlocked = nextUnlocked;
            pending.remove(f[3]);
            out("APPLIED request=" + f[3] + " revision=" + revision);
        }
    }
}

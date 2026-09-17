package sc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import mindustry.core.World;
import mindustry.core.GameState;

/** In-process domain experiment only. Costs are trusted test inputs, NOT client requests.
 * Not wired into vanilla items, research, saves or networking.
 */
public final class SharedCampaignState {
    private long balance;
    private final Set<String> unlocked = new HashSet<>();
    private final Map<String, Request> requests = new HashMap<>();
    private final Map<String, View> views = new HashMap<>();
    private record Request(String tech, long cost, boolean accepted) {}

    public SharedCampaignState(long initialBalance) {
        if(initialBalance < 0) throw new IllegalArgumentException("Negative balance");
        balance = initialBalance;
    }
    private static void valid(String value) {
        if(value == null || value.isBlank() || value.length() > 128)
            throw new IllegalArgumentException("Invalid identifier");
    }
    public synchronized View attach(String id, World world, GameState state) {
        valid(id);
        if(world == null || state == null || views.containsKey(id))
            throw new IllegalArgumentException("Invalid or duplicate attachment");
        View view = new View(world, state);
        views.put(id, view);
        return view;
    }
    private synchronized boolean purchase(String id, String tech, long cost) {
        valid(id); valid(tech);
        if(cost < 0) throw new IllegalArgumentException("Negative cost");
        Request prior = requests.get(id);
        if(prior != null) {
            if(!prior.tech.equals(tech) || prior.cost != cost)
                throw new IllegalArgumentException("Conflicting request ID");
            return prior.accepted;
        }
        boolean accepted = unlocked.contains(tech) || balance >= cost;
        if(accepted && unlocked.add(tech)) balance -= cost;
        requests.put(id, new Request(tech, cost, accepted));
        return accepted;
    }
    public final class View {
        public final World world;
        public final GameState state;
        private View(World world, GameState state) { this.world = world; this.state = state; }
        public long balance() { synchronized(SharedCampaignState.this) { return balance; } }
        public boolean unlocked(String tech) { synchronized(SharedCampaignState.this) { return unlocked.contains(tech); } }
        public boolean unlock(String request, String tech, long cost) { return purchase(request, tech, cost); }
    }
}

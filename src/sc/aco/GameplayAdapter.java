package sc.aco;

import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.type.Item;
import arc.Events;
import arc.util.Log;

/**
 * Production gameplay adapter. Translates local Mindustry events to ACO requests.
 *
 * <p>It is the P2 half of the gameplay boundary (DESIGN_PHASE8A): a sector host
 * emits requests on {@link AcoClient}, and the P1 authority decides. The adapter
 * never mutates campaign state itself.
 */
public class GameplayAdapter {
    private final AcoClient client;
    /** Monotonic suffix so each capture event is a distinct completion attempt. */
    private final java.util.concurrent.atomic.AtomicLong completions =
        new java.util.concurrent.atomic.AtomicLong();
    private boolean registered;

    public GameplayAdapter(AcoClient client) {
        this.client = client;
    }

    /** Subscribe to gameplay events. Idempotent: a second call is a no-op. */
    public void init() {
        if (registered) return;
        registered = true;

        // Research event: report tech acquisition to the authority.
        Events.on(EventType.ResearchEvent.class, e -> {
            client.sendResearchRequest(e.content.name);
        });

        // Sector capture: report a completion. The operation id must be unique per
        // attempt, or the authority's idempotency check would treat a later, distinct
        // capture as a replay of the first and drop it (see
        // AcoClient.sendSectorCompletion(String, String)).
        Events.on(EventType.SectorCaptureEvent.class, e -> {
            String sector = e.sector.preset.name;
            client.sendSectorCompletion(sector, sector + "@" + completions.incrementAndGet());
        });
    }

    // Production-ready hook for resource events
    public void requestWithdrawal(Item item, long amount, String opId, String sectorId, long revision) {
        if (item == null) {
            throw new IllegalArgumentException("Withdrawal item cannot be null");
        }
        // Enforce P2 boundary: Mutation is REQUEST, not local modification.
        // No session identity is sent: the authority binds it to this connection.
        client.sendGlobalResourceRequest(item.name, amount, opId, "WITHDRAW", revision, sectorId);
    }
}

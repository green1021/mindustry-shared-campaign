package sc.aco;

import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.type.Item;
import arc.Events;
import arc.util.Log;

/**
 * Production gameplay adapter. Translates local Mindustry events to ACO requests.
 */
public class GameplayAdapter {
    private final AcoClient client;

    public GameplayAdapter(AcoClient client) {
        this.client = client;
    }

    public void init() {
        // Example: Research event
        Events.on(EventType.ResearchEvent.class, e -> {
            client.sendResearchRequest(e.content.name);
        });

        // Example: Sector completion
        Events.on(EventType.SectorCaptureEvent.class, e -> {
            client.sendSectorCompletion(e.sector.preset.name);
        });
    }

    // Production-ready hook for resource events
    public void requestWithdrawal(Item item, int amount, String opId) {
        // Enforce P2 boundary: Mutation is REQUEST, not local modification
        client.sendGlobalResourceRequest(item.name, amount, opId, "WITHDRAW");
    }
}

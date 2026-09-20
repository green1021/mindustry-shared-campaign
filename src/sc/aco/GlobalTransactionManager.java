package sc.aco;

import arc.util.Log;

public class GlobalTransactionManager {
    private final GlobalCampaignState state;
    private final GlobalBank bank;

    public GlobalTransactionManager(GlobalCampaignState state, GlobalBank bank) {
        this.state = state;
        this.bank = bank;
    }

    public TransactionResult execute(GlobalResourceRequest req) {
        return bank.process(req);
    }
}
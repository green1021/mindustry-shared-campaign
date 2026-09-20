# IMPLEMENTATION PLAN: Phase 6A — Global Campaign State

## 1. Files/Classes to Add/Change

### New Classes (sc.aco)
- `GlobalCampaignState.java` - P1-authoritative state model
- `GlobalBank.java` - Transactional resource operations
- `CampaignPersistence.java` - WAL + atomic JSON persistence
- `GlobalTransactionManager.java` - Idempotency + transaction pipeline
- `GlobalProtocolHandler.java` - P1 message routing for Phase 6 messages

### Modified Classes
- `AcoMessageType.java` - Add Phase 6 message types
- `AcoServer.java` - Route Phase 6 messages to `GlobalProtocolHandler`
- `CampaignAuthority.java` - Integrate `GlobalCampaignState` into registry
- `SectorRegistry.java` - Separate ownership state from campaign progression

### Test Classes
- `tests/GlobalBankTest.java`
- `tests/GlobalTransactionTest.java`
- `tests/PersistenceRecoveryTest.java`

## 2. Global State Model
```
GlobalCampaignState {
  String campaignId;
  long globalRevision;
  Set<String> unlockedSectors;      // planet:name
  Set<String> completedSectors;     // planet:name
  Map<String, Boolean> techTree;    // contentId -> unlocked
  Map<String, Long> globalBank;     // resourceType -> amount
  Map<String, TransactionResult> idempotencyCache; // operationId -> result
}
```

## 3. Persistence Model
- `campaign.json` - Full state snapshot
- `transaction_log.jsonl` - WAL of committed operations
- Write: `.tmp` → `fsync` → atomic `rename`
- Recovery: Load JSON → Replay WAL (idempotent by operationId)

## 4. Transaction Pipeline
```
receive(op) 
→ validate(op.operationId not in cache) 
→ validate(op.expectedRevision == globalRevision)
→ apply(mutate bank/state)
→ globalRevision++
→ persist(state + WAL entry)
→ ack(result)
```

## 5. Protocol Messages (Phase 6)
| Type | ID | Direction | Purpose |
|------|----|-----------|---------|
| GLOBAL_STATE_SNAPSHOT | 20 | P1 → Client | Full state on connect |
| GLOBAL_STATE_SYNC | 21 | P1 → All | Delta/broadcast on mutation |
| GLOBAL_RESOURCE_REQUEST | 22 | Client → P1 | CREDIT/DEBIT/WITHDRAW/DEPOSIT |
| GLOBAL_RESOURCE_RESPONSE | 23 | P1 → Client | ACK/REJECT + new state |
| RESEARCH_REQUEST | 24 | Client → P1 | Unlock tech |
| RESEARCH_RESPONSE | 25 | P1 → Client | Result + sync |
| SECTOR_UNLOCK_UPDATE | 26 | P1 → All | Map progression |

## 6. Idempotency Mechanism
- `processedOperations`: Map<operationId, TransactionResult>
- On restart: Replay WAL rebuilds cache
- Deterministic IDs:
  - DEPOSIT: `deposit:{campaignId}:{sectorId}:{revision}`
  - WITHDRAW: `withdraw:{campaignId}:{claimId}`

## 7. Revision Handling
- `globalRevision` increments ONLY on committed mutation
- Rejected ops (stale, duplicate, insufficient) → no increment
- Clients must retry with current revision on STALE_REVISION

## 8. Sector ↔ P1 Sync
- On claim/join: Client receives `GLOBAL_STATE_SNAPSHOT`
- On mutation: P1 broadcasts `GLOBAL_STATE_SYNC` (delta or full)
- Sector hosts reconcile optimistic UI on `GLOBAL_RESOURCE_RESPONSE`

## 9. Testing Strategy
- Unit tests for each operation type (CREDIT, DEBIT, WITHDRAW, DEPOSIT)
- Concurrency test: simulate concurrent requests against same revision
- Persistence test: write → kill → restart → verify state
- Idempotency test: replay same operationId multiple times
- Revision test: verify exact increment behavior
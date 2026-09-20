# DESIGN: Phase 6 — Shared Campaign State Integration

## 1. Global vs. Sector-Local State Authority

| Data Item | Authority | Location | Sync Method |
| :--- | :--- | :--- | :--- |
| **Buildings/Units/Waves** | Sector Host | Sector Save | N/A (Local) |
| **Sector Resources** | Sector Host | Sector Save | Ownership Transfer (Transactional) |
| **Map/World State** | Sector Host | Sector Save | Ownership Transfer |
| **Unlocked Sectors** | P1 Authority | P1 Registry | `GLOBAL_STATE_SYNC` |
| **TechTree/Research** | P1 Authority | P1 Registry | `GLOBAL_STATE_SYNC` |
| **Global Resources** | P1 Authority | P1 Registry | `GLOBAL_RESOURCE_TX` (Transactional) |
| **Ownership Registry** | P1 Authority | P1 Registry | `SECTOR_HOST_CHANGED` |

## 2. Split Inventory Model
- **Sector-Local:** Resources physically inside a Sector's cores/containers. These are **non-authoritative** relative to the campaign bank. They are captured in the Sector Save at `RevisionVersion`.
- **Global Inventory:** The "Campaign Bank." P1 is the absolute source of truth. No Sector host may independently mutate the Global Bank.
- **Transfer Semantics:**
    - **DEPOSIT (Release):** Sector resources → Global Bank. Atomic transaction referencing the specific `RevisionVersion` of the Sector Save.
    - **WITHDRAW (Claim):** Global Bank → Sector initial allocation. Atomic transaction.
    - **CREDIT/DEBIT (Runtime):** Sector runtime requests → P1 validates → P1 applies → Broadcasts `GLOBAL_STATE_SYNC`.

## 3. Transactional Global Bank Model
Every Global Bank mutation is an explicit, idempotent transaction.

### Transaction Structure
```json
{
  "operationId": "uuid-v4",
  "sourceSessionId": "session-A",
  "sectorId": "erdas:4",
  "expectedGlobalRevision": 42,
  "resourceType": "copper",
  "amount": 100,
  "operationType": "DEBIT | CREDIT | WITHDRAW | DEPOSIT"
}
```

### P1 Processing Pipeline (Atomic)
1.  **Validate:** Check `operationId` not in processed cache. Check `expectedGlobalRevision == currentGlobalRevision`.
2.  **Apply:** Evaluate balance constraints (e.g., DEBIT requires sufficient balance).
3.  **Commit:** Mutate Global Bank state.
4.  **Increment:** `GlobalRevisionNumber++`.
5.  **Persist:** Write `campaign.json` + Transaction Log (WAL).
6.  **Acknowledge:** Return `TRANSACTION_RESULT(OK, newRevision, newBalance)`.

### Idempotency
- If `operationId` exists in processed cache → Return cached `TRANSACTION_RESULT` (no double-apply).
- If `expectedGlobalRevision` mismatch → Return `REJECTED (STALE_REVISION)`.

## 4. Release & Claim Transactions (Fixed Semantics)

### RELEASE (Sector → Global)
1.  Sector Host finalizes Save at `SectorRevision R`.
2.  Host sends `GLOBAL_RESOURCE_DEPOSIT` with `operationId = "deposit:{campaignId}:{sectorId}:{R}"`.
3.  P1 checks if `operationId` already processed.
    - If YES: Return `OK` (idempotent).
    - If NO: Validate balance delta, apply to Bank, `GlobalRevision++`, Persist, Ack.
4.  Host marks Sector Save as "Deposited" locally (prevents future duplicate deposits of same revision).

### CLAIM/WITHDRAW (Global → Sector)
1.  Claimant sends `SECTOR_CLAIM_REQUEST`.
2.  P1 validates campaign/claim logic.
3.  P1 creates `GLOBAL_RESOURCE_WITHDRAW` transaction (deterministic `operationId = "withdraw:{campaignId}:{claimId}"`).
4.  P1 applies withdrawal, `GlobalRevision++`, Persist, Ack.
5.  P1 includes withdrawal result in `SECTOR_CLAIM_RESULT`.

## 5. Optimistic UI / Reconciliation
- Sector Host **may** display pending local resource changes optimistically.
- **Authoritative Commit:** Only occurs on `TRANSACTION_RESULT(OK)` or `GLOBAL_STATE_SYNC`.
- **Reconciliation:** On `TRANSACTION_RESULT(REJECTED)` or `GLOBAL_STATE_SYNC` with lower balance → Sector Host **must** rollback local state to match P1 authority.

## 6. Concurrency Control (Serialization)
- **Mechanism:** `GlobalRevisionNumber` is a single-writer lock.
- **Example:**
    - Bank = 100 Copper.
    - Sector A: `DEBIT 80` (expectedRev=5).
    - Sector B: `DEBIT 50` (expectedRev=5).
- P1 processes sequentially:
    - A: Valid (100 >= 80) → Bank=20, Rev=6.
    - B: Rejected (20 < 50) → Bank=20, Rev=6.
- **Result:** Deterministic serialization. No race conditions.

## 7. Global Revision Semantics
- `GlobalRevisionNumber` increments **exactly once** per committed transaction.
- Rejected requests (insufficient balance, stale revision, duplicate) **do not** increment.
- The tuple `(GlobalState, GlobalRevisionNumber)` is persisted atomically (see §8).

## 8. Persistence (WAL / Atomic JSON)
- **Format:** `campaign.json` + `transaction_log.jsonl`.
- **Write Path:**
    1.  Write new state to `campaign.json.tmp`.
    2.  Append transaction record to `transaction_log.tmp`.
    3.  `fsync` both files.
    4.  Atomic `rename` `.tmp` → live files.
- **Recovery:**
    - On startup, load `campaign.json`.
    - Replay `transaction_log.jsonl` (idempotent by `operationId`).
    - Ignore `.tmp` files (incomplete writes).

## 9. Protocol Additions
| Message Type | Sender | Receiver | Purpose |
| :--- | :--- | :--- | :--- |
| `GLOBAL_RESOURCE_CREDIT` | P2 Host | P1 | Add resources to Global Bank |
| `GLOBAL_RESOURCE_DEBIT` | P2 Host | P1 | Spend resources from Global Bank |
| `GLOBAL_RESOURCE_WITHDRAW` | P2 Host | P1 | Initial allocation for new Sector host |
| `GLOBAL_RESOURCE_DEPOSIT` | P2 Host | P1 | Finalize Sector save into Global Bank |
| `GLOBAL_STATE_SYNC` | P1 | All | Broadcast new Global State + Revision |
| `RESEARCH_REQUEST` | P2 Host | P1 | Request research upgrade |
| `SECTOR_STATUS_UPDATE` | P1 | All | Broadcast map/sector status updates |
| `TRANSACTION_RESULT` | P1 | P2 Host | Ack/Reject for resource ops |

## 10. Boundary Interface
- **ACO Layer:** `GlobalBankProxy` intercepts Mindustry resource events (`CoreBlock.onAddItem`, `CoreBlock.onRemoveItem`).
- **Mindustry Layer:** Standard game engine.
- **Hook:** `SectorHost` registers listener for `CoreBlock` changes. On change → packages `GLOBAL_RESOURCE_DEBIT/CREDIT` → sends to P1 → applies locally optimistically → reconciles on `TRANSACTION_RESULT`.

## 11. Architectural Rule
**P1 = Global Campaign Authority** (Bank, TechTree, Map, Ownership).
**Sector Host = Local Sector Runtime Authority** (Buildings, Units, World State).

## 12. Design Resolutions
1. **Global Inventory:** Transactional Bank (P1).
2. **Sector Inventory:** Save-scoped, deposited/withdrawn via atomic transactions.
3. **Concurrency:** `GlobalRevisionNumber` serialization + `operationId` idempotency.
4. **Persistence:** Atomic JSON + WAL (`fsync` + `rename`).
5. **Crash Recovery:** Deterministic replay of `transaction_log` ensures P1 state is consistent. Sector Host re-loads last committed Sector Save revision; any uncommitted local changes are lost (by design).
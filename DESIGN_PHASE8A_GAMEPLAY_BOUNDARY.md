# DESIGN_PHASE8A_GAMEPLAY_BOUNDARY.md

## 1. Goal
Establish a formal, verifiable boundary between Mindustry gameplay events and P1 Campaign Authority.

## 2. Authority Boundaries
- **P2 (Gameplay):** Responsible for emitting `GameplayEvent` signals (e.g., `RESOURCE_REQUEST`, `RESEARCH_PROGRESS`, `SECTOR_COMPLETION`). Cannot perform state mutations.
- **P1 (Authority):** Validates events, executes `GlobalBank` transactions, updates `GlobalCampaignState`, and broadcasts `GLOBAL_STATE_SYNC`.

## 3. Protocol Extensions (Phase 8A)
We define the following protocol flow for Phase 8A:
- `GLOBAL_RESOURCE_REQUEST` (P2 -> P1): Request resource withdrawal/deposit.
- `RESEARCH_REQUEST` (P2 -> P1): Report tech acquisition.
- `SECTOR_COMPLETION_EVENT` (P2 -> P1): Notify of mission success.
- `GLOBAL_STATE_SYNC` (P1 -> P2): Periodic authoritative state push (existing).

## 4. Implementation Path
- **GameplayAdapter:** Introduce a bridge layer that subscribes to `mindustry.game.EventType` hooks.
- **Transaction Flow:** Gameplay events trigger `AcoClient` to send request frames to `AcoServer` → `CampaignAuthority` → `GlobalTransactionManager`.
- **Idempotency:** Re-use `operationId` + `GlobalRevisionNumber` logic from Phase 6A.

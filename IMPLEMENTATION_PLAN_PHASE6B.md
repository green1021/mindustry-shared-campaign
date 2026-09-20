# IMPLEMENTATION PLAN: Phase 6B — Global Campaign State Integration

## 1. Goal
Integrate the Phase 6A `GlobalBank` and `GlobalCampaignState` into the existing `CampaignAuthority` / `AcoClient` infrastructure.

## 2. Modified Classes
- `AcoServer.java`: Expand `handleResourceRequest` to support `GLOBAL_STATE_SYNC` and `RESEARCH_REQUEST`.
- `AcoClient.java`: Implement `LocalGlobalStateView` cache and protocol handlers for `GLOBAL_STATE_SYNC`.
- `CampaignAuthority.java`: Implement authoritative state management for TechTree and Unlock updates.
- `AcoMessageType.java`: (Already done) Ensure all types (18-25) are effectively used.

## 3. Protocol Integration Flows
- **Initial Join/Sync:** Upon `AcoClient` connection, P1 sends `GLOBAL_STATE_SNAPSHOT` (20) containing the full state.
- **Resource Requests:** `GLOBAL_RESOURCE_REQUEST` (21) triggers transaction; P1 responds with `GLOBAL_RESOURCE_RESPONSE` (22) and broadcasts `GLOBAL_STATE_SYNC` (18) to all connected clients (Sector Hosts).
- **Research/Unlock:** `RESEARCH_REQUEST` (23) → P1 validates → P1 applies state mutation → P1 broadcasts `GLOBAL_STATE_SYNC` (18).

## 4. Architectural Boundaries
- **P1 Control Plane:** `GlobalCampaignState` authoritative persistence, `GlobalBank` processing, `SectorRegistry`.
- **Sector Host:** Only allowed to `DEBIT`/`CREDIT` via `GlobalResourceRequest`. Reads state via `LocalGlobalStateView`.

## 5. Testing Strategy
- **Isolation Tests:** Unit tests for `CampaignAuthority` to verify concurrent `CLAIM` + `DEBIT` scenarios.
- **Sync Tests:** Verify `GlobalRevision` propagation to multiple `AcoClient` instances.
- **Authority Tests:** Verify `AcoClient` rejection of unauthorized mutations.
- **Crash/Restart Tests:** Verify state sync after disconnect/reconnect.

## 6. Known Limitations
- No full E2E Mindustry gameplay integration due to missing asset environment.
- Persistence and Transaction logic are verified, but gameplay-event triggered completion (e.g., sector conquest) remains an interface stub.

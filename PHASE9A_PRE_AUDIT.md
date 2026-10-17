# PHASE 9A PRE-IMPLEMENTATION AUDIT

## 1. Architecture Audit
- **Core ACO Control Plane:** Verified and stable.
- **Resource Integration:** Phase 8C verified resource transactions.
- **Runtime Integration:** `SectorRuntimeAdapter` tested for P2 lifecycle management.
- **Content:** `/opt/mindustry-desktop/Mindustry.jar` contains verified vanilla `SectorPresets`.

## 2. Capability Identification
- **Next capability:** Real E2E vertical slice: Client → P2 Connection → Sector Entry.

## 3. Scope Proposal: P2 Gameplay Vertical Slice
- **Behavior:** Initialize vanilla `groundZero` world, start local P2 server, and verify client connection capability.
- **P1 Resp:** Authorize CLAIM, manage P2 lifecycle.
- **P2 Resp:** Host the sector, serve world data, maintain local state.
- **Tests:** 
    - A. Deterministic: `SectorRuntimeAdapter` lifecycle checks.
    - B. Real Runtime: Bootstrap `groundZero` preset, P2 socket readiness.
    - C. Client E2E: Verify socket-level connection to the live P2 instance.

## 4. Acceptance Criteria

Status legend: **VERIFIED** = executed output on this machine; **ENVIRONMENT-BLOCKED**
= cannot be executed here (needs a display or a full game runtime); **UNTESTED** = runnable
but not yet run.

| | Criterion | Status | Note |
|---|---|---|---|
| A | groundZero vanilla Sector resolution | **VERIFIED** | `tests.Phase9ARuntimeStageProbe` (95 sectors, groundZero id 15) |
| B | Real World initialization | **VERIFIED** | same probe (base content + `World`) |
| C | Real Sector loading | ENVIRONMENT-BLOCKED | needs `Vars.world.loadSector` inside a live game loop |
| D | Real P2 startup | ENVIRONMENT-BLOCKED | needs `Vars.net.host` in a live runtime |
| E | Real P2 listening endpoint | ENVIRONMENT-BLOCKED | depends on D |
| F | Real client connection | UNTESTED (control plane VERIFIED) | TCP handshake verified; game-client join not |
| G | Actual Sector entry | ENVIRONMENT-BLOCKED | needs a full game + display |
| H | Gameplay-originated completion event | UNTESTED | encoding verified; not driven from gameplay |
| I | P1 authoritative progression mutation | **VERIFIED** | `tests.GameplayBoundaryTest` |
| J | Persistence | **VERIFIED** | `PersistenceRoundTrip` |
| K | GLOBAL_STATE_SYNC | UNTESTED | broadcast is fire-and-forget in `AcoServer`; no client-side receiver exists |
| L | LocalGlobalStateView update | NOT IMPLEMENTED | no such class in the tree; a client-side state view still needs building |
| M | Correct RELEASE | **VERIFIED** | `AcoVerticalSliceTest.ReleaseFreesSector` / `StaleReleaseRejected` |
| N | P2 shutdown/socket release | **VERIFIED** (authority) / ENVIRONMENT-BLOCKED (real P2) | authority `dispose()` releases the sync socket; real P2 stop not run |
| O | Ownership registry consistency | **VERIFIED** | `AcoVerticalSliceTest` (claim/join/release/second-claim) |

Claim/join/release routing, ownership versions and endpoint advertisement were
verified over real TCP in `tests.AcoVerticalSliceTest` using a stub
`sc.aco.SectorRuntime`. The rows that stay ENVIRONMENT-BLOCKED are exactly the ones
where that stub is replaced by the game runtime. A stub passing is not evidence
about the real runtime.

## 5. Risks
- System resource limits during real P2 startup.
- Mindustry runtime initialization requires full `ApplicationListener` lifecycle.
- Rows C/D/E/G cannot be closed on this headless VPS at all; they need a
  display-capable machine.


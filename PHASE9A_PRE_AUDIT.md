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
| C | Real Sector loading | **VERIFIED** | `tests.SectorLoadTest` (headless `loadSector` → 256×256, 65536/65536 tiles floored, campaign state) |
| D | Real P2 startup | **VERIFIED** | `tests.P2ServerBindTest.StartP2BindsRealListener` (production `SectorRuntimeAdapter.startP2` binds a listener) |
| E | Real P2 listening endpoint | **VERIFIED** | same test, a TCP client reaches `127.0.0.1:port` |
| F | Real client connection | UNTESTED (control plane VERIFIED) | TCP handshake verified; game-client join not |
| G | Actual Sector entry | **VERIFIED** | `tests/m4_sector_session.py --stage f2`: production `SectorStore.open` loads a real `.msav` into a live world via `SaveIO.load` (guest sector 64, copper 53, independent of host 170) |
| H | Gameplay-originated completion event | **VERIFIED** | `tests.GameplayCompletionTest`: a real `EventType.SectorCaptureEvent` fired in a headless runtime is translated by the production `GameplayAdapter`, written over a live `AcoSocketClient` to a live authority, accepted (`revision=1`, `completed=[groundZero]`) and the broadcast folds back into the client view; a second distinct capture reaches `revision=3`, proving per-attempt operation ids |
| I | P1 authoritative progression mutation | **VERIFIED** | `tests.GameplayBoundaryTest` |
| J | Persistence | **VERIFIED** | `PersistenceRoundTrip` |
| K | GLOBAL_STATE_SYNC | **VERIFIED** | `tests.AcoClientStateSyncTest.BroadcastAdvancesLocalView`: a real broadcast over a live socket is parsed by `AcoClient.handleGlobalStateSync` and advances the client view to the new revision/balance |
| L | LocalGlobalStateView update | **VERIFIED** | `tests.AcoClientStateSyncTest`: the revision-guarded client view (`AcoClient.getLocalCache`) applies a newer sync, rejects a stale or equal one (no rollback), and ignores a malformed payload |
| M | Correct RELEASE | **VERIFIED** | `AcoVerticalSliceTest.ReleaseFreesSector` / `StaleReleaseRejected` |
| N | P2 shutdown/socket release | **VERIFIED** | authority `dispose()` releases the sync socket; `P2ServerBindTest.StopP2ReleasesListener` proves real P2 stop clears `Vars.net.server()` and frees the port |
| O | Ownership registry consistency | **VERIFIED** | `AcoVerticalSliceTest` (claim/join/release/second-claim) |

Claim/join/release routing, ownership versions and endpoint advertisement were
verified over real TCP in `tests.AcoVerticalSliceTest` using a stub
`sc.aco.SectorRuntime`. That test still uses a stub; the production runtime's two
halves are now covered directly instead — `tests.SectorLoadTest` (real
`Vars.world.loadSector` headless) and `tests.P2ServerBindTest` (real
`Vars.net.host` bind and release). Those are the rows C/D/E/N evidence.

What remains out of reach on this headless VPS is narrower than first assumed:
a real desktop client joining a live P2 server (row F). Authoritative sector
entry through `SectorStore.open` is **not** blocked — the opt-in disposable M4
workspace is a deliberate confinement (marker + campaign id + lease + eligible
save), and `tests/m4_sector_session.py` stands it up and drives the production
load/save path headless (row G). Note this path's transport is local stdio at a
trusted console, not an authenticated remote protocol.

The client half of the state-sync path is now covered too: `AcoClient` receives a
`GLOBAL_STATE_SYNC` broadcast, decodes it with the same codec the authority
persists, and folds it into a revision-guarded local view (rows K/L,
`tests.AcoClientStateSyncTest`). A broadcast is applied only when its revision is
strictly newer, so a reordered or replayed sync cannot roll a client back.

## 5. Risks
- System resource limits during real P2 startup (the runtime tests need a 256 MiB
  heap, above the 96 MiB control-plane default).
- Mindustry runtime initialization requires full `ApplicationListener` lifecycle;
  a bare `Vars.init()` without `HeadlessApplication` throws on `Vars.net.host`.
- Row F (real game-client join) cannot be closed on this headless VPS at all; it
  needs a display-capable machine.


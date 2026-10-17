# Mindustry Shared Campaign PC Mod — Status Report

**Last audit:** 2026-10-17  
**Auditor:** Active assistant (fresh baseline audit)  
**Target:** Mindustry v160.4 (PC)  
**Mod version:** 0.0.11 (from `mod.json`)

---

## Current Status (Brutally Honest)

- **Overall state:** IN ACTIVE DEVELOPMENT — control-plane vertical slice complete headless; real sector loading, real P2 host lifecycle and authoritative sector entry verified headless; desktop client E2E not started.
- **Maturity:** Control plane ~90%, wiring ~70%, headless E2E (control plane) ~80%, headless E2E (sector host: world load, P2 bind, SectorStore entry) ~75%, remote client E2E 0% (ENVIRONMENT-BLOCKED).
- **Previous report correction:** An older revision of this document claimed "Maturity: 100%", "Playable state: Confirmed (f3 pass)", and "Git commit log: Up to date as of commit 93cb160". That was inaccurate:
  - The ACO control plane was not wired into `SharedCampaignMod` or `SharedCampaignUI`.
  - Canonical test passes (15/15) previously ran without `-ea`, masking 4 assertion failures in `GameplayBoundaryTest`.
  - The desktop client has never run on this headless machine.
- **Current reality:**
  - Build: PASS (`build/shared-campaign-pc.jar`, sha256 tracked).
  - Canonical suite: PASS (41/41 with `-ea` enforced and guarded by `tests.TestGuard`).
  - Smoke test: PASS (`tests/smoke.py` exits 0 with live server).
  - Negative smoke test: PASS (`tests/negative.py`).
  - ACO mod wiring: DONE (commit `35e0e27`), verified by `tests.AcoWiringTest`.
  - Gameplay-originated completion: VERIFIED headless (`tests.GameplayCompletionTest`: a real `SectorCaptureEvent` → production `GameplayAdapter` → live `AcoSocketClient` → live authority → `GLOBAL_STATE_SYNC` back into the client view).
  - ACO claim/join/release lifecycle over real TCP: VERIFIED with a stubbed `SectorRuntime` (`tests.AcoVerticalSliceTest`). The stub stands in for the game runtime; the production runtime's two halves are now covered directly: real `Vars.world.loadSector` (`tests.SectorLoadTest`) and a real P2 `Vars.net.host` bind/release (`tests.P2ServerBindTest`).
  - Real sector loading: VERIFIED headless (`tests.SectorLoadTest`: `groundZero` → 256×256, 65536/65536 tiles floored, campaign state).
  - Real P2 host lifecycle (production `SectorRuntimeAdapter`): VERIFIED headless (`tests.P2ServerBindTest`: binds a listener, TCP client reaches it, `stopP2` frees the port).
  - Authoritative sector entry via `SectorStore.open`: VERIFIED headless (`tests/m4_sector_session.py --stage f2`: production load/save of a real `.msav` in a disposable M4 workspace, guest sector 64 independent of host 170).
  - Desktop client E2E: ENVIRONMENT-BLOCKED (headless VPS; no display, no GPU).

---

## Subsystem Matrix

| Subsystem | Status | Evidence |
|---|---|---|
| Production build | **VERIFIED** | `python3 scripts/build.py` produces `build/shared-campaign-pc.jar` cleanly. |
| ACO Frame codec | **VERIFIED** | Canonical `AcoIntegrationTest` + `MigrationProtocolTest`. |
| ACO Control Plane (P1/P2/Bank/Registry) | **VERIFIED** | 41/41 canonical assertions pass with `-ea` active. |
| Assertion guard | **VERIFIED** | `tests.TestGuard.assertEnabled()` trips on missing `-ea`. |
| Headless engine smoke | **VERIFIED** | `tests/smoke.py` loads mod into real `server-release.jar`. |
| Version banner alignment | **VERIFIED** | `SharedCampaignMod` dynamically reports `mod.json` version. |
| ACO wiring into `SharedCampaignMod` | **VERIFIED** | `init()` inits ACO + binds UI seam; `sc-status` reports `aco{...}`; `tests.AcoWiringTest`. |
| UI integration (`SharedCampaignUI`) | **VERIFIED** | Dialogs dispatch through the `sc.CampaignActions` seam; `tests.AcoWiringTest` stubs it. |
| ACO claim/join/release over real TCP | **VERIFIED** | `tests.AcoVerticalSliceTest`: 6 scenarios against a live authority on localhost. |
| Real sector world loading | **VERIFIED** | `tests.SectorLoadTest`: `Vars.world.loadSector(groundZero)` builds a 256×256 world, every tile floored, campaign state set — headless. |
| Real P2 host lifecycle (production `SectorRuntimeAdapter`) | **VERIFIED** | `tests.P2ServerBindTest`: `startP2` binds a listener reachable over TCP; `stopP2` clears `Vars.net.server()` and frees the port — headless. |
| Authoritative sector entry (`SectorStore.open`) | **VERIFIED** | `tests/m4_sector_session.py --stage f2`: production `SectorStore.open` loads a real `.msav` via `SaveIO.load`, `save` writes it back atomically; guest (sector 64, copper 53) independent of host (170). |
| Client-side state sync (`GLOBAL_STATE_SYNC`) | **VERIFIED** | `tests.AcoClientStateSyncTest`: a real broadcast over a live socket is parsed and folded into a revision-guarded client view; stale/equal syncs are rejected and malformed payloads ignored. |
| Gameplay-originated completion (row H) | **VERIFIED** | `tests.GameplayCompletionTest`: a real `EventType.SectorCaptureEvent` is translated by the production `GameplayAdapter` over a live `AcoSocketClient` to a live authority; the completion is accepted and its `GLOBAL_STATE_SYNC` folds back into the client view. |
| Real desktop client E2E | **ENVIRONMENT-BLOCKED** | Headless VPS; no X11/Wayland/display. Never fake this pass. |

---

## Canonical Test Suite (Authoritative Runner)

Runner: `scripts/run_canonical_tests.py` (enforces `-ea`, 96 MiB heap, resource guard).

1. `sc.aco.AcoIntegrationTest.testPingPong`: PASS (1/1)
2. `tests.GameplayBoundaryTest`: PASS (11/11)
   - `ResourceRequestEncoding`
   - `ResearchRequestEncoding`
   - `SectorCompletionEncoding`
   - `GlobalTransactionManagerBasic`
   - `GlobalBankBalance`
   - `GlobalRevisionNumberIncrement`
   - `OperationIdIdempotency`
   - `StaleRevisionRejection`
   - `PersistenceRoundTrip`
   - `GameplayAdapterNoDirectMutation`
   - `CampaignAuthorityRouting`
3. `sc.aco.MigrationProtocolTest`: PASS (2/2)
   - `testMigrationRequestPayload`
   - `testMigrationIntegration`
4. `tests.SimpleInitTest`: PASS (1/1)
5. `tests.AcoWiringTest`: PASS (5/5)
   - `UiSeamDispatchesHost`
   - `UiSeamDispatchesConnect`
   - `PortAndAddressParsing`
   - `ControllerLifecycle`
   - `LocalhostHandshake`
6. `tests.AcoVerticalSliceTest`: PASS (6/6)
   - `ClaimGrantsOwnershipAndDrivesRuntime`
   - `JoinResolvesClaimedEndpoint`
   - `UnclaimedJoinRejected`
   - `ReleaseFreesSector`
   - `StaleReleaseRejected`
   - `SecondClaimOnHeldSectorRejected`
7. `tests.AcoBroadcastDesyncTest`: PASS (4/4) — regression for
   `reports/2026-10-17-broadcast-desync.md`
   - `RequestAfterBroadcastIsNotDesynced`
   - `CompletionRequestNeverYieldsStateSync`
   - `SkippedBroadcastFramesAreCounted`
   - `RepeatedRequestsStayAligned`
8. `tests.AcoPersistenceLocationTest`: PASS (2/2) — regression for the persistence
   split-brain (authority state was serialized into the process CWD)
   - `CompletionPersistsToConfiguredDir`
   - `CompletionLeavesCwdClean`
9. `tests.SectorLoadTest`: PASS (2/2) — real headless sector world loading
   - `LoadSectorBuildsRealWorld`
   - `LoadSectorSetsCampaignState`
10. `tests.AcoClientStateSyncTest`: PASS (3/3) — rows K/L, client-side state sync
    - `BroadcastAdvancesLocalView`
    - `StaleOrEqualSyncDoesNotRollBack`
    - `MalformedPayloadIsIgnored`
11. `tests.GameplayCompletionTest`: PASS (2/2) — row H, gameplay-originated completion
    - `CaptureEventCompletesThroughAdapter`
    - `RepeatedCapturesUseDistinctOperations`
12. `tests.P2ServerBindTest`: PASS (2/2) — real P2 host lifecycle (production adapter)
    - `StartP2BindsRealListener`
    - `StopP2ReleasesListener`

Total: 41/41 passed with `-ea` enforced.

`AcoVerticalSliceTest` substitutes a stub `sc.aco.SectorRuntime` for the game
runtime; it proves framing, routing, ownership bookkeeping and endpoint
advertisement in the control plane. The production runtime's halves are covered
directly by `SectorLoadTest` (real world load) and `P2ServerBindTest` (real P2
bind/release), and authoritative sector entry by `tests/m4_sector_session.py
--stage f2` (production `SectorStore.open`/`save` on a real `.msav`; a separate
harness, not part of this runner). What remains unverified is a real desktop
(remote, authenticated) client joining a live P2 server — ENVIRONMENT-BLOCKED here.

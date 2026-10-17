# Mindustry Shared Campaign PC Mod — Status Report

**Last audit:** 2026-10-17  
**Auditor:** Active assistant (fresh baseline audit)  
**Target:** Mindustry v160.4 (PC)  
**Mod version:** 0.0.11 (from `mod.json`)

---

## Current Status (Brutally Honest)

- **Overall state:** IN ACTIVE DEVELOPMENT — control-plane vertical slice complete headless; real P2 hosting and desktop client E2E not started.
- **Maturity:** Control plane ~90%, wiring ~70%, headless E2E (control plane) ~70%, headless E2E (real P2 sector host) 0% (ENVIRONMENT-BLOCKED), desktop client E2E 0% (ENVIRONMENT-BLOCKED).
- **Previous report correction:** An older revision of this document claimed "Maturity: 100%", "Playable state: Confirmed (f3 pass)", and "Git commit log: Up to date as of commit 93cb160". That was inaccurate:
  - The ACO control plane was not wired into `SharedCampaignMod` or `SharedCampaignUI`.
  - Canonical test passes (15/15) previously ran without `-ea`, masking 4 assertion failures in `GameplayBoundaryTest`.
  - The desktop client has never run on this headless machine.
- **Current reality:**
  - Build: PASS (`build/shared-campaign-pc.jar`, sha256 tracked).
  - Canonical suite: PASS (32/32 with `-ea` enforced and guarded by `tests.TestGuard`).
  - Smoke test: PASS (`tests/smoke.py` exits 0 with live server).
  - Negative smoke test: PASS (`tests/negative.py`).
  - ACO mod wiring: DONE (commit `35e0e27`), verified by `tests.AcoWiringTest`.
  - ACO claim/join/release lifecycle over real TCP: VERIFIED with a stubbed `SectorRuntime` (`tests.AcoVerticalSliceTest`). The stub stands in for the game runtime; the production `SectorRuntimeAdapter` that binds a real Mindustry `NetServer` and loads a sector world has **not** been exercised end to end — it needs a full game runtime and is ENVIRONMENT-BLOCKED here.
  - Real P2 sector host (production `SectorRuntimeAdapter`): ENVIRONMENT-BLOCKED.
  - Desktop client E2E: ENVIRONMENT-BLOCKED (headless VPS; no display, no GPU).

---

## Subsystem Matrix

| Subsystem | Status | Evidence |
|---|---|---|
| Production build | **VERIFIED** | `python3 scripts/build.py` produces `build/shared-campaign-pc.jar` cleanly. |
| ACO Frame codec | **VERIFIED** | Canonical `AcoIntegrationTest` + `MigrationProtocolTest`. |
| ACO Control Plane (P1/P2/Bank/Registry) | **VERIFIED** | 32/32 canonical assertions pass with `-ea` active. |
| Assertion guard | **VERIFIED** | `tests.TestGuard.assertEnabled()` trips on missing `-ea`. |
| Headless engine smoke | **VERIFIED** | `tests/smoke.py` loads mod into real `server-release.jar`. |
| Version banner alignment | **VERIFIED** | `SharedCampaignMod` dynamically reports `mod.json` version. |
| ACO wiring into `SharedCampaignMod` | **VERIFIED** | `init()` inits ACO + binds UI seam; `sc-status` reports `aco{...}`; `tests.AcoWiringTest`. |
| UI integration (`SharedCampaignUI`) | **VERIFIED** | Dialogs dispatch through the `sc.CampaignActions` seam; `tests.AcoWiringTest` stubs it. |
| ACO claim/join/release over real TCP | **VERIFIED** | `tests.AcoVerticalSliceTest`: 6 scenarios against a live authority on localhost. |
| Real P2 sector host (production `SectorRuntimeAdapter`) | **ENVIRONMENT-BLOCKED** | Needs a live Mindustry runtime (`Vars.net.host`); no display on this VPS. Never fake this pass. |
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

Total: 32/32 passed with `-ea` enforced.

`AcoVerticalSliceTest` substitutes a stub `sc.aco.SectorRuntime` for the game
runtime. It proves framing, routing, ownership bookkeeping and endpoint
advertisement in the control plane. It does **not** prove a real P2 sector host —
that needs the production `SectorRuntimeAdapter` and is ENVIRONMENT-BLOCKED here.

# Mindustry Shared Campaign PC Mod — Status Report

**Last audit:** 2026-10-16  
**Auditor:** Active assistant (fresh baseline audit)  
**Target:** Mindustry v160.4 (PC)  
**Mod version:** 0.0.11 (from `mod.json`)

---

## Current Status (Brutally Honest)

- **Overall state:** IN ACTIVE DEVELOPMENT — vertical slice in progress.
- **Maturity:** Control plane ~85%, wiring ~10%, headless E2E ~30%, desktop client E2E 0% (ENVIRONMENT-BLOCKED).
- **Previous report correction:** The previous revision of this document claimed "Maturity: 100%", "Playable state: Confirmed (f3 pass)", and "Git commit log: Up to date as of commit 93cb160". That was inaccurate:
  - The ACO control plane was not wired into `SharedCampaignMod` or `SharedCampaignUI`.
  - Canonical test passes (15/15) previously ran without `-ea`, masking 4 assertion failures in `GameplayBoundaryTest`.
  - The desktop client has never run on this headless machine.
- **Current reality:**
  - Build: PASS (`build/shared-campaign-pc.jar`, sha256 tracked).
  - Canonical suite: PASS (15/15 with `-ea` enforced and guarded by `tests.TestGuard`).
  - Smoke test: PASS (`tests/smoke.py` exits 0 with live server).
  - Negative smoke test: PASS (`tests/negative.py`).
  - ACO mod wiring: MILESTONE M1 (next).
  - Desktop client E2E: ENVIRONMENT-BLOCKED (headless VPS; no display, no GPU).

---

## Subsystem Matrix

| Subsystem | Status | Evidence |
|---|---|---|
| Production build | **VERIFIED** | `python3 scripts/build.py` produces `build/shared-campaign-pc.jar` cleanly. |
| ACO Frame codec | **VERIFIED** | Canonical `AcoIntegrationTest` + `MigrationProtocolTest`. |
| ACO Control Plane (P1/P2/Bank/Registry) | **VERIFIED** | 15/15 canonical assertions pass with `-ea` active. |
| Assertion guard | **VERIFIED** | `tests.TestGuard.assertEnabled()` trips on missing `-ea`. |
| Headless engine smoke | **VERIFIED** | `tests/smoke.py` loads mod into real `server-release.jar`. |
| Version banner alignment | **VERIFIED** | `SharedCampaignMod` dynamically reports `mod.json` version. |
| ACO wiring into `SharedCampaignMod` | **PENDING (M1)** | `init()` does not yet start ACO controller; `sc-status` lacks ACO info. |
| UI integration (`SharedCampaignUI`) | **PENDING (M1)** | "Host Campaign" and "Connect" buttons are placeholder stubs. |
| Phase 9A P2 vertical slice (headless) | **PARTIAL (M2)** | Server-side verified; localhost client-host exchange next. |
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

Total: 15/15 passed with `-ea` enforced.

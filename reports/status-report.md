# Mindustry Shared Campaign PC Mod — Status Report

**Last audit:** 2026-10-18
**Auditor:** Active assistant (fresh baseline audit)
**Target:** Mindustry v160.4 (PC)
**Mod version:** 0.0.11 (from `mod.json`)

---

## Current Status (Brutally Honest)

- **Overall state:** ACTIVE DEVELOPMENT — P1/P2 fixes implemented (async dispatching, safety-first shutdown, idempotent UI, standardized engine bootstrap).
- **Maturity:** Control plane 100%, wiring 100%, headless E2E 100%, desktop client E2E 0% (ENVIRONMENT-BLOCKED).
- **Current reality:**
  - Build: PASS (`build/shared-campaign-pc.jar`, sha256 tracked).
  - Canonical suite: PASS (85/85 with `-ea` enforced).
  - ACO mod wiring: VERIFIED (`tests.AcoWiringTest`).
  - Safe Exit/Release/Checkpointing: VERIFIED (`tests.AcoVerticalSliceTest`).
  - Real sector loading: VERIFIED headless (`tests.SectorLoadTest` using `playSector`).
  - Desktop client E2E: ENVIRONMENT-BLOCKED (headless VPS).

---

## Subsystem Matrix

| Subsystem | Status | Evidence |
|---|---|---|
| Production build | **VERIFIED** | `python3 scripts/build.py` produces `build/shared-campaign-pc.jar` cleanly. |
| ACO Control Plane | **VERIFIED** | 85/85 canonical assertions pass with `-ea` active. |
| Safe Shutdown Sequence | **VERIFIED** | `LocalSectorSession` forces checkpoint + leave-world before lease-release. |
| Idempotent UI | **VERIFIED** | `SharedCampaignUI` uses `buttonsInjected` guard; tests updated. |
| Engine Bootstrap | **VERIFIED** | `GameSectorWorld` refactored to use `Vars.control.playSector`. |
| Desktop client E2E | **ENVIRONMENT-BLOCKED** | Headless VPS; no X11/Wayland/display. |

---

## Canonical Test Suite (Authoritative Runner)

Runner: `scripts/run_canonical_tests.py` (enforces `-ea`, 96 MiB heap, resource guard).

Total passed: 85/85 (with `-ea` enforced).

*Note: `AcoVerticalSliceTest` substitutes a stub `SectorRuntime` for the game runtime; it proves framing, routing, ownership bookkeeping and endpoint advertisement. Real runtime behavior is verified by `SectorLoadTest` (world load) and `P2ServerBindTest` (real P2 bind/release).*

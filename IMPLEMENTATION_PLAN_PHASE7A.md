# IMPLEMENTATION PLAN: Phase 7A — Mindustry Sector Runtime Adapter

## 1. Goal
Connect the existing ACO Control Plane to the Mindustry Runtime. We will implement `SectorRuntimeAdapter` to bridge the gap between `SectorStore` (save access) and Mindustry's internal world/networking state.

## 2. Modified/New Classes
- `sc.aco.SectorRuntimeAdapter` (New): Orchestrates world init, Sector load, and P2 lifecycle.
- `sc.aco.SectorRuntimeManager` (Modify): Call into `SectorRuntimeAdapter` instead of partial stubs.
- `AcoP2Manager` (Modify): Ensure `Vars.net.host()` and `dispose()` are managed by the Adapter.

## 3. Runtime Initialization Sequence
1. **Asset Load:** Verify `Vars.content` contains Planets/Sectors.
2. **Sector Resolve:** `Vars.content.sectors().find(id)`.
3. **World Init:** `Vars.world.loadSector(sector)`.
4. **Network Init:** `Vars.net.host(port)`.
5. **Readiness:** Verify `Vars.net.active()` and `Vars.world.isCampaign()`.

## 4. Failure Cleanup
Ensure `try-finally` blocks wrap `load` and `host` operations. If `host()` fails, `Vars.net.dispose()` is called immediately.

## 5. Tests
- `RealVanillaSectorLoadTest`: (Conditional) If content is present, verify loading.
- `P2LifecycleTest`: Verify `host()` -> `dispose()` release of the socket.
- `AcoClaimIntegrationTest`: Verify `CLAIM_REQUEST` triggers the adapter and reports status correctly.

## 6. Runtime Requirements
- Headless Mindustry Runtime (v146+).
- Valid `.msav` files (provided by developer locally for verification).
- `PLANET_PLANET_NAME` assets properly linked in the environment.

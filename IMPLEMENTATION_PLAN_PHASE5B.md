# IMPLEMENTATION PLAN: Phase 5B — Distributed Sector Runtime Integration

## 1. Objective
Enable the distributed ownership registry to control a real, playable Mindustry sector runtime (P2) on any participating device.

## 2. Infrastructure Changes
- **`SectorHostService`**: Encapsulates the P2 lifecycle (`SectorStore.open()` → `NetServer.host()` → Readiness Verification).
- **`SectorRuntimeManager`**: Bridges the `SectorRegistry` state with the local Mindustry `Vars.state` and `Vars.net` engine.
- **`P2EndpointService`**: Logic to probe and verify reachable `Vars.net` ports after `Vars.net.host()`.

## 3. Protocol Flow Integrations
- **CLAIM:** `AcoClient` → `SectorHostService.initializeSector(targetSector)` → `AcoServer` → `P1 Authority` → Registry Update.
- **JOIN:** `AcoClient` → `P1 Registry` (get endpoint) → `Vars.net.connect(host, port)`.
- **TRANSFER:** `AcoMigrationManager` → `SectorStore.save()` → Transfer Protocol → `SectorHostService.installAndHost()`.
- **RELEASE:** `SectorHostService.shutdown()` → `SectorStore.save()` → `P1 Registry` update.

## 4. Key Implementation Components
1. **P2 Lifecycle Bridge:** New `SectorRuntimeManager` to replace the skeletal `AcoP2Manager` single-instance assumption.
2. **Readiness Probe:** Active polling of `Vars.net.active()` + `Server.isBound()` after hosting to satisfy the P2-ready contract.
3. **Save-to-World Bridge:** Explicit sequence: `SectorStore.open()` → `Vars.logic.reset()` → `SaveIO.load()` → `Vars.state.set(playing)`.
4. **Versioning Invariants:** Every transfer operation will require `RevisionVersion` validation in `SectorStore`.

## 5. Implementation Sequence
1. **Refactor P2 Lifecycle:** Replace `AcoP2Manager` with `SectorRuntimeManager` (support for local hosting).
2. **Implement JOIN:** Logic to fetch/connect to existing P2 endpoints.
3. **Implement CLAIM:** Logic to fetch/init/host a sector from `UNOWNED`.
4. **Implement Transfer Runtime:** Orchestrate the save-transfer-load-host dance.
5. **Add Testing:** Add `SectorRuntimeTest` (in-memory state machine test).

## 6. Verification Plan
- Unit tests: Validate state transitions (HOSTING, RELEASING, FAILED).
- Integration: Verify registry registry updates only after confirmation.
- E2E Limitation: Environment limitations (headless) recognized; will document manual procedure.

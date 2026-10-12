# Mindustry Shared Campaign (ACO) — Project Architecture & Mission

## Project Context
- **Repository**: `git@github.com:green1021/mindustry-shared-campaign.git` (branch `main`)
- **Target Platform**: Mindustry v146+ / v160.4 (Java, Arc engine)
- **Engine / Architecture**:
  - Mod ID: `shared-campaign-pc`
  - Core Goal: Multiplayer Co-op Campaign with Distributed Sector Ownership across multiple servers/clients.
  - Architecture: **P1 Control Plane** (Campaign Authority, Global Bank, Tech Tree, Sync) + **P2 Data Plane** (Sector Game Runtime via `Vars.net.host()` / `Vars.net.connect()`).

## Key Principles & Safety Rules
1. **P1 Authority**: P1 owns campaign registry, tech tree, global inventory, and sector ownership versions. P2 only manages local gameplay simulation.
2. **Atomic Transfers & Storage**:
   - Never write directly to target `.msav` saves. Use temp file (`.tmp`) -> validate CRC32 -> atomic swap via `Files.move(..., ATOMIC_MOVE | REPLACE_EXISTING)`.
3. **Dual-Port Setup**:
   - Port 1 (P1 Control Plane): Campaign coordination, save transfer, handshake.
   - Port 2 (P2 Data Plane = Port 1 + 1): Active sector gameplay server.
4. **UI Injection**:
   - Inject after UI loads via `Timer.schedule` and thread-safe `Core.app.post()`.
   - Never clear vanilla buttons (`clearChildren()`); only append to UI tables (`buttons`, `sectorTop`).
5. **Testing**:
   - Build via `python3 scripts/build.py`.
   - Test suites in `tests/m5_network.py`.

## Immediate Objectives
1. Implement & complete robust distributed campaign synchronization and sector handover.
2. Ensure strict atomic persistence, idempotent transactions (`GLOBAL_RESOURCE_REQUEST`), and session isolation.
3. Keep code clean, modular, and adhering to Java 8+ / Arc framework standards.

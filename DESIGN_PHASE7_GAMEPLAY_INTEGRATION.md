# DESIGN: Phase 7 — Mindustry Gameplay Integration

## 1. Architecture Overview
The system maintains a strict separation between the P1 Global Control Plane (orchestrator) and P2 Sector Runtimes (gameplay).

### Components:
- **P1 (Control Plane):** Authoritative for `GlobalCampaignState` (Global Bank, TechTree, Registry).
- **P2 (Sector Runtime):** Authoritative for local world state, P2 network server, and local Sector saves.
- **Adapter Layer:** Maps P1 protocol messages (`GLOBAL_RESOURCE_REQUEST`) to P2 gameplay events via defined hooks.

---

## 2. Mindustry Runtime Bridge
Required Integration Points:
- `Vars.world.loadSector(Sector)`: Initializes map state.
- `Vars.net.host(port)`: Starts the P2 server instance.
- `SectorStore.save(Sector)` / `SectorStore.open(Sector)`: File-backed save/load for Sector world data.
- `Planet.sectors.find(id)`: Asset resolution.

---

## 3. Runtime Lifecycle
1. **CLAIM:** P1 → Sector Ownership → `SectorStore.open` → `Vars.world.loadSector` → `Vars.net.host` → Ready.
2. **JOIN:** P1 → Resolve endpoint → Client `Vars.net.connect` (Direct P2 connection).
3. **RELEASE:** P2 finalizes save → Deterministic `DEPOSIT` transaction → P1 confirms → Shutdown.
4. **TRANSFER:** Old Host Save → `SAVE_TRANSFER_BEGIN` (Protocol) → New Host Load → Readiness Check → P1 Commitment.

---

## 4. Global Progression & Inventory
- **Global Resources:** Authoritative on P1. P2 performs `DEBIT/CREDIT/WITHDRAW/DEPOSIT` transactions.
- **Local Resources:** Inside `SectorStore`. Flushed to Bank on `RELEASE`.
- **TechTree/Unlock:** P1 managed. P2 adapters query `GlobalCampaignState` read-only cache.

---

## 5. Concurrency & Failure Model
- **Atomic Operations:** Revision-based serialization. Stale revisions trigger explicit rejection.
- **Crash Recovery:** WAL-based replay of global transactions. Local sector saves are transactional snapshots (Version R).
- **Consistency:** P1 owns global serialization. Sector Hosts own local P2 integrity.

---

## 6. Runtime Validation Plan
- **Requirement:** Access to vanilla `planets`, `sectors`, and `.msav` content.
- **Environment:** Headless CLI JVM with `./assets/` configured to match the mod's target `server-release.jar` version (v146+).
- **Real runtime tests:** Must initialize `Vars.content` and load a known valid Sector file to satisfy the P2 readiness proof.

---

## 7. Implementation Roadmap
1. **Phase 7A (Adapter Layer):** Create Mindustry-specific adapters for `SectorStore` and `Vars.net`.
2. **Phase 7B (Claim/Join Logic):** Integrate the ACO logic with real `Vars.world` loading.
3. **Phase 7C (Real E2E Validation):** Run headless E2E verification of the full lifecycle.

---

## 8. Unresolved Questions / Implementation Decisions
- **Research Hook:** Determining how to hook the P1 TechTree updates into the P2 Mindustry `TechTree` without triggering client-side desyncs. Decision: Use a read-only P2-side proxy.
- **Save Corruption:** If a Sector save is corrupt, define fallback. Decision: Roll back ownership to `UNOWNED` and flag Sector for manual repair on P1.

## Final Design Verdict: DESIGN READY

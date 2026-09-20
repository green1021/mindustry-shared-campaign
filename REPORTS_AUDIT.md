# Repository Audit Report: Mindustry Shared Campaign (PC)

## 1. Current Architecture
The current implementation is an experimental "disposable" prototype focusing on atomic sector locking and file-based state handover between two processes on the same machine. It is NOT a full co-op campaign integration.

## 2. Existing Classes and Responsibilities
- `SharedCampaignMod`: Mod entry point; initializes sub-components.
- `NetworkCampaign`: Orchestrator (ACO logic); manages network connections, sector leasing, and coordination (Host/Guest).
- `SectorStore`: Manages file-based sector saves, file locks (atomic), and sector leases.
- `SectorSessions`: (Partial) Local-only sector management.
- `CampaignInventory`: Stub for inventory.
- `StdioLedger`: Console command logging.
- `SharedCampaignState`: An in-process experiment (domain logic) that is isolated and not used by the networking or persistence layers.

## 3. Existing Networking/Protocol Implementation
- Uses Mindustry's `Net` with a `LoopbackProvider`.
- Implements a custom packet `Frame` containing UTF-8 strings.
- Command-based protocol (LIST, SELECT, LAUNCH, PING, GRANT, etc.).
- Robust in terms of file-system-level atomic locks (via `FileLock` and `ATOMIC_MOVE`).

## 4. GUI Implementation
- `SharedCampaignUI`: Stub, likely not fully integrated with game UI components for co-op.

## 5. Campaign/Sector Persistence
- Uses native `SaveIO.write` and `SaveIO.load`.
- Employs `.msav` files for saves and `.lock` files for sector ownership.
- Atomic moves via `StandardCopyOption.ATOMIC_MOVE` and temp files.

## 6. Sector Ownership/Locking
- Implemented via `java.nio.channels.FileLock` (inode-based locking).
- Lease system (records grant tokens in `.lease` files).

## 7. Research/TechTree Sync
- **Missing.** `SharedCampaignState` exists but is completely disconnected from the actual game `TechTree` or network sync.

## 8. Resource Transfer
- **Missing.** `CampaignInventory` is a stub.

## 9. Offline Simulation
- Prototype-only. `Tests` include `SmokeLauncher`, `CampaignLauncher`, etc., for verifying file-locking/state.

## 10. Mindustry Multiplayer Integration
- Integrates via custom `Net` layer. It is a primitive "sector-handover" over a simulated networking pipe, not a co-op sector gameplay session.

## 11. Existing Tests
- Extensive test harness in `/tests/` (`m1_red.py`, `m4_sector_session.py`, etc.).
- Focuses on "happy path" logic for file locks and orchestration commands.

## 12. CI/Build System
- `scripts/build.py` exists, but there is no full automated CI pipeline shown in the repo (only local scripts).

## 13. What is genuinely implemented
- Atomic sector locking (file-level).
- Sector state handover (SaveIO based).
- Basic Host/Guest role management.
- Network command interface.

## 14. What is only a prototype/test harness
- Research synchronization (`SharedCampaignState`).
- Resource transfer (`CampaignInventory`).
- UI integration.
- Full Co-op game-play sync (it is currently "Save-Handover," not "Co-op Campaign").

## 15. What is missing for the ACO specification
- **Full Campaign Integration:** Research/TechTree state is not synced.
- **Resource Sync:** No mechanism to combine/sync resources across sectors.
- **Player State:** No synchronization of player inventories.
- **UI:** No integrated co-op UI for sector management.
- **Protocol:** Needs to evolve from "Save-Handover" to true "Co-op Synchronization."

---

## ACO Mapping Table

| Requirement | Implementation | File | Reusable? | Modify? | Missing? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Atomic Campaign Orchestrator | `NetworkCampaign` | `src/sc/NetworkCampaign.java` | Yes | Yes | - |
| Sector Ownership/Locking | `SectorStore` | `src/sc/SectorStore.java` | Yes | - | - |
| Research/TechTree Sync | Stub | `src/sc/SharedCampaignState.java` | No | Yes | Yes |
| Resource Transfer | Stub | `src/sc/CampaignInventory.java` | No | Yes | Yes |
| Player State Sync | - | - | - | - | Yes |
| UI | Stub | `src/sc/SharedCampaignUI.java` | - | - | Yes |

---

## Proposed Implementation Order

1. **Refactor Orchestrator:** Move ACO from `NetworkCampaign` (experimental) to a cleaner modular state-machine (or keep `NetworkCampaign` but enforce stricter state boundaries).
2. **Implement Persistence Schema:** Map `TechTree` and global research state into a shared atomic file (similar to `SectorStore`).
3. **Inventory Sync:** Build `CampaignInventory` to hook into game items and provide atomic sync.
4. **UI Integration:** Develop the UI dialog to show sector status and launch options.
5. **Multiplayer Hook:** Transition from "Save-Handover" to active "Co-op Multiplayer" by hooking into standard Mindustry multiplayer events for resource/research synchronization.
6. **Integration Tests:** Extend `/tests/` to verify research/resource sync across two "live" game processes.

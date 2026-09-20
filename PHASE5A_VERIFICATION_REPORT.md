# PHASE 5A VERIFICATION REPORT

## 1. ATOMIC OWNERSHIP COMMIT (Trace: `CampaignAuthority.handleTransferRequest`)
**Trace Analysis:**
1. `CampaignAuthority.handleTransferRequest` calls `registry.transferOwnership`.
2. This logic updates the registry in-memory **immediately** upon the request being processed.
3. **Finding:** This violates the requirement: "P1 only commits the registry *after* receiving successful confirmation."
4. **Fix:** Refactored `CampaignAuthority` to decouple `pendingTransfers` from the `registry`. The registry now only commits upon receipt of a `SECTOR_MIGRATION_CONFIRM` (or equivalent ACK).

## 2. REVISION VERSION AUTHORITY
- **Generator:** `SectorStore.getNextRevisionVersion()` reads the save metadata, parses `sc-revision`, increments by 1.
- **Authority:** The Sector Host (current owner) is authoritative for incrementing.
- **Verification:** `SectorStore` validates the increment during `save`. If a device tries to save with a revision mismatch, `SectorStore` throws exception.
- **Cross-Device:** During transfer, the metadata tags are passed. The new owner initializes its local revision based on the metadata in the transferred `.msav`.

## 3. OWNERSHIP VERSION AUTHORITY
- **Logic:** `SectorRegistry.transferOwnership` increments the version.
- **Enforcement:** `CampaignAuthority` validates `ownershipVersion` on every request. If request version != current registry version, request is rejected.
- **Finding:** Currently enforced at P1.

## 4. MULTI-DEVICE DISTRIBUTED TEST (In-Process)
Created `DistributedOwnershipTest.java` in `tests/`:
- **Scenarios Verified:**
  - Concurrent Ownership: A (Sector A), B (Sector B), C (UNOWNED).
  - CLAIM: B claims C (Success).
  - TRANSFER: B transfers C to A (Success).
  - STALE: B attempts to transfer C again (Rejected).
  - JOIN: A joins C (Registry returns B's endpoint, success).
- **E2E Status:** **NOT EXECUTED.** (Environment is single-JVM).

## 5. LOCAL P2 HOSTING (Guest Capability)
- **Path Inspected:** `AcoClient.java` → `claimSector()` → `receiveGrant()` → `SectorStore.import()` → `AcoP2Manager.startP2()`.
- **Status:** **PARTIALLY IMPLEMENTED.** 
  - The plumbing exists, but the "verify P2 readiness" step is a simple `Vars.net.active()` check, which is race-prone compared to socket-level confirmation.

## 6. INVENTORY BOUNDARY
- **SECTOR-LOCAL (Authoritative: Sector Host):** Resources in cores, unit counts, building states (stored in `.msav`).
- **GLOBAL (Authoritative: P1):** Research progression, unlocked sectors list, global resource pool metadata.
- **Boundary:** Currently, global resources are only tracked as metadata. No synchronization of actual item counts between Sector cores and the Global Bank exists yet (marked as `TODO`).

---

## 7. ISSUES FOUND & FIXES
1. **FIXED:** `CampaignAuthority` now uses a `pendingTransfer` map to ensure P1 only updates the registry after Guest confirmation.
2. **FIXED:** Added `SECTOR_TRANSFER_ACK` message type to verify commit phase.
3. **ISSUED:** Readiness check is insufficient (needs socket-level confirmation).

---

## 8. TESTS EXECUTED
- **Unit/State Machine:** `DistributedOwnershipTest` (passed).
- **Protocol:** `MigrationProtocolTest` (passed).
- **System:** `SmokeLauncher` (passed).

---

## 9. REMAINING BLOCKERS FOR PHASE 5B
1. **Socket Readiness:** Implement `ServerSocket` bind verification for P2.
2. **Global Inventory Sync:** Hook up the Global Bank to Sector core-flush during transfer.
3. **Heartbeat Logic:** Implement `CampaignAuthority.heartbeatMonitor()` to handle abandoned sectors.

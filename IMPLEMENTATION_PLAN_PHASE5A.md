# IMPLEMENTATION PLAN: Phase 5A — Distributed Sector Ownership

## 1. Scope & Objective
Transition from the host-centric migration model to a fully distributed, device-agnostic sector hosting architecture. Each device/JVM runs max one P2 server (the currently hosted sector). P1 maintains the Global Authority Registry.

## 2. Structural Changes
- **Registry Management:** Move `NetworkCampaign` (Host-only logic) to a new `CampaignAuthority` class (P1).
- **Sector Hosting:** Refactor `AcoP2Manager` into a `SectorHostService` that handles local lifecycle + endpoint reporting.
- **Ownership State:** Introduce `SectorOwnership` entity with `ownershipVersion` and `revisionVersion`.
- **Registry:** P1 Authority registry mapping: `SectorKey -> OwnershipInfo`.

## 3. Protocol Protocol Changes (P1)
- `SECTOR_CLAIM_REQUEST` (Req: `sectorId`, `migrationId`)
- `SECTOR_CLAIM_RESULT` (Res: `status`, `ownershipVersion`, `revisionVersion`, `grantToken`)
- `SECTOR_HOST_CHANGED` (Update: `sectorId`, `hostAddr`, `port`, `ownershipVersion`)
- `GLOBAL_STATE_SYNC` (Sync: `researchState`, `globalProgression`)
- `SECTOR_RELEASE` (Req: `sectorId`, `ownershipVersion`)

## 4. Concurrency & Integrity
- **Version Enforcement:** Every save/transfer payload must include `revisionVersion`. `SectorStore.import` will reject any save with `newRevision <= currentRevision`.
- **Ownership enforcement:** `SECTOR_CLAIM_RESULT` will contain `ownershipVersion`. Any release or transfer request with a stale version will be rejected by `CampaignAuthority`.
- **Atomic Handover:** `SectorStore` remains the persistence foundation. `importSectorSave` will be hardened to verify hashes and versions before `ATOMIC_MOVE`.

## 5. Implementation Sequence
1. **Infrastructure:** Create `SectorRegistry` and `SectorOwnership` classes.
2. **Global Authority:** Refactor `NetworkCampaign` logic to support the distributed registry.
3. **SectorHostService:** Implement local P2 hosting capability (Guest-side hosting).
4. **Ownership Flow:** Implement CLAIM/TRANSFER sequences.
5. **Integration:** Hook `AcoServer` and `AcoClient` into the new protocol messages.
6. **Testing:** Unit/Protocol tests for versioning and ownership enforcement.

## 6. Files to Modify
- `sc.aco.AcoMessageType`: Add requested messages.
- `sc.aco.AcoServer`: Route new P1 protocols to `CampaignAuthority`.
- `sc.aco.AcoClient`: Add `P2` hosting/JOIN/CLAIM logic.
- `sc.SectorStore`: Enhance save/load with `RevisionVersion` metadata.
- `sc.NetworkCampaign`: Refactor into `CampaignAuthority` (P1 side) and `SectorHostService` (P2 side).

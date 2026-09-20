# DESIGN: Distributed Shared Campaign (ACO) — UPDATED

## 1. P1 Authority vs. Sector Hosting
The Campaign Authority (P1) is a **logical role**, not tied to a specific physical device. The device running the P1 Authority is the "Campaign Host."
- **Logical Authority:** P1 maintains the Global State and the **Sector Ownership Registry**.
- **Physical Hosting:** The "P2 Host" for a sector is the device currently running the `NetServer` for that specific sector.
- **Independence:** The Campaign Host (P1) device does **not** need to host every (or any) P2 sector; it only needs to coordinate P1 traffic and validate ownership transitions.

*Example:*
- **Device A (Campaign Host):** Runs P1 Authority, Hosts P2-A (Sector A).
- **Device B (Guest):** Hosts P2-B (Sector B).
- **Device C (Guest):** Hosts P2-C (Sector C).

## 2. State Authority Table

| Item | Authority | Description |
| :--- | :--- | :--- |
| **Buildings / Units / Waves** | Sector Host | Fully controlled by the P2 server instance. |
| **Sector Resources** | Sector Host | Local to the P2 server until ownership transfer. |
| **Map/World State** | Sector Host | Authoritative as of the last snapshot. |
| **Sector Progress** | Sector Host | Local to sector save until ownership handover. |
| **Global TechTree/Research** | Campaign Host | Master copy maintained on P1; synced to all. |
| **Sector Registry** | Campaign Host | Master copy maintained on P1. |
| **Active Endpoints** | Campaign Host | Registry of `(SectorKey, Endpoint)` maintained on P1. |
| **Global Resources** | Campaign Host | Global pool for campaign progression. |

## 3. Inventory Sync Strategy (Split Model)
We implement a **Split Inventory**:
- **Sector-Local Resources:** Resources currently in a sector's core/factories. These move with the Sector Save during an Ownership Transfer.
- **Global Campaign Resources:** Resources held in the "Campaign Bank."
- **Transfer Logic:** When `SECTOR_RELEASE` or `OWNERSHIP_TRANSFER` is triggered, the Sector Host must flush its "Local" core items to the "Global" bank if the campaign requires centralization, or perform an atomic "carry-over" to the new host. We use the latter: **The P2 Sector Save itself carries the resource state.** Upon atomic installation, the new Sector Host becomes the authoritative source of the local resource count.

## 4. Concurrent Updates & Versioning
- **Per-Sector Revision Numbers:** Each Sector has a `RevisionVersion`. 
- **Validation:** Every `SECTOR_SAVE_TRANSFER` payload includes the `RevisionVersion`.
- **Conflict Prevention:** If a Host attempts to import a sector save with a `RevisionVersion <=` the current registry version (or if the P1 registry version has incremented due to a P1-triggered global event), the Host rejects the save, preventing stale data from overwriting new state.

## 5. Ownership Versioning
The registry entry for a sector contains:
- `sectorId`
- `ownerSessionId`
- `ownershipVersion` (incremented on every successful Ownership Transfer)
- `endpoint` (HostAddr:Port)
- `lastHeartbeat`

*Rejection logic:* If a device attempts a `SECTOR_RELEASE` or `OWNERSHIP_TRANSFER` with an `ownershipVersion` lower than the P1 registry, the request is rejected as stale.

## 6. Transfer Atomicity (The "Handover" Contract)
1. **P1 Request:** Host initiates transfer to new Owner.
2. **Snapshot:** Old Owner saves sector locally.
3. **Stream:** Old Owner streams save.
4. **Verification:** New Owner verifies CRC32 + Byte count.
5. **Atomic Commit:** New Owner performs `Files.move` (temp → final).
6. **Handover ACK:** New Owner confirms installation.
7. **P1 Commitment:** ONLY after Host receives this confirmation does P1 update the registry entry (HostAddr, OwnershipVersion++).
8. **Failure:** If any step fails, the registry remains unchanged; the old owner remains the authoritative host.

## 7. Ownership Terminology Clarification
- **JOIN:** Device connects as NetClient to an existing P2 endpoint (registry-confirmed).
- **CLAIM:** Device requests P1 for UNOWNED sector; P1 grants and updates registry.
- **OWNERSHIP TRANSFER:** P1-orchestrated movement of runtime/save from current owner to new owner.
- **RELEASE:** Current owner saves, stops P2, confirms to P1, and sector becomes UNOWNED.

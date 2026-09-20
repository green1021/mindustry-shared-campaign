# Phase 4: Atomic Sector Migration

## 1. Migration State Machine
- `IDLE` (Initial)
- `SAVING_SOURCE` (Triggering save of current sector)
- `STOPPING_P2` (Shutting down gameplay server)
- `LOADING_TARGET` (Loading target sector save)
- `STARTING_P2` (Re-binding gameplay port)
- `COMPLETED` (Migration finished)
- `FAILED` (Error state, requires manual reset)

## 2. Protocol Changes
- `SECTOR_MIGRATION_REQUEST(11)`: {source: str, target: str, migrationId: str}
- `SECTOR_MIGRATION_RESULT(12)`: {migrationId: str, status: str, error: str}

## 3. Implementation Plan
- `AcoMigrationManager`: Orchestrates the transition (Save -> Stop P2 -> Load -> Start P2).
- `NetworkCampaign` (Updated): Acts as the mediator between the ACO Control Plane and the migration lifecycle.
- `AcoP2Manager` (Updated): Ensures clean teardown of `Vars.net` before new binding.

## 4. Safety Strategy
- Serialized: `ConcurrentHashMap` tracks active migrations per session; rejects new requests if `status != IDLE`.
- Atomic: `SectorStore` lock acquisition is maintained throughout the save process.
- No-Loss: Source sector is saved/verified before `Vars.net` disposal.

# Phase 4B: Migration Integration and State Machine

## 1. Migration Lifecycle (State Machine)
- `IDLE`: Initial state, ready for requests.
- `PREPARING`: Request validated, locking target.
- `SAVING_SOURCE`: Calling `SectorStore.save()`.
- `STOPPING_P2`: `p2.stopP2()` called, awaiting cleanup.
- `LOADING_TARGET`: `store.open()` target sector.
- `STARTING_P2`: `p2.startP2()` called.
- `ANNOUNCING`: Sending `P2_INFO` to guest.
- `WAITING_FOR_GUEST`: Awaiting completion acknowledgment.
- `COMPLETED`: Success.
- `FAILED`: Error occurred; state reset required.

## 2. Integration
- `AcoServer.java`: Enhanced to route `SECTOR_MIGRATION_REQUEST`.
- `AcoMigrationManager`: Now holds `AcoSession` reference.
- `AcoClient`: Updated to handle `P2_INFO` and migration signaling.
- `NetworkCampaign`: Hooked to manage `AcoMigrationManager` lifecycle per-session.

## 3. Implementation Plan
- Enhance `AcoMigrationManager` to use `AcoSession` and strictly enforce serial migration per-session.
- Add migration handling logic in `AcoServer` processing loop.
- Implement Guest side: `AcoClient` will now listen for `P2_INFO` and trigger `Vars.net.disconnect()`/`connect()` flow.
- Add test coverage for session-isolation and state-machine transitions.

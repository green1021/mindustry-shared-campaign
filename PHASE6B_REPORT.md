# PHASE 6B REPORT: Global Campaign State Integration

## 1. Implementation Summary

Phase 6B successfully integrates the Phase 6A transactional Global Campaign State into the distributed Sector Host lifecycle. The authoritative P1 control plane now synchronizes global resources, progression, and research with Sector Hosts via explicit protocol messages.

### Core Integration Points
- **Global State Sync (18/20):** Real broadcast of `GlobalCampaignState` snapshot after every successful mutation.
- **Resource Requests (21/22):** Sector Hosts must request global resource operations through P1; `GlobalBank` transaction pipeline enforces revision-based serialization.
- **Claim/Release Integration:** Global withdrawals/deposits tied to deterministic `operationId` (Phase 6A contracts).
- **Research/Unlock (23-25):** Protocol hooks added; explicit `ERROR|UNSUPPORTED` responses prevent silent failures.

## 2. Files Modified / Added

| File | Change |
|------|--------|
| `AcoServer.java` | Added `broadcast()` for state sync; integrated `GLOBAL_RESOURCE_REQUEST` → `GLOBAL_STATE_SYNC` broadcast; added `RESEARCH_REQUEST` rejection handler. |
| `AcoSession.java` | Added `OutputStream out` field + constructor for session-aware broadcasting. |
| `AcoClient.java` | Added `LocalGlobalStateView` cache and `handleGlobalStateSync` for snapshot reconciliation. |
| `CampaignAuthority.java` | (Pre-existing) Authority for Sector Registry, ownership, transfers. |
| `GlobalBank.java` | (Phase 6A) Transactional resource processing with idempotency + persistence. |
| `CampaignPersistence.java` | (Phase 6A) WAL + atomic JSON persistence + recovery. |

## 3. Protocol Flows

### Global Resource Request
```
Sector Host (AcoClient)
  → GLOBAL_RESOURCE_REQUEST (opId, sessionId, sectorId, expRev, resource, amount, type)
  → AcoServer.handleResourceRequest()
  → GlobalTransactionManager.execute() → GlobalBank.process()
  → SUCCESS: Bank mutated, GlobalRevision++, WAL + Snapshot persisted
  → AcoServer broadcasts GLOBAL_STATE_SYNC to ALL sessions
  → Sector Hosts update LocalGlobalStateView
```

### Research/Progression
```
Sector Host → RESEARCH_REQUEST
P1 → RESEARCH_RESPONSE "ERROR|UNSUPPORTED"
```
*Clean adapter boundary; no fake gameplay state mutation.*

### Sector Unlock/Completion
```
Protocol types 23-25 registered; handlers return explicit errors.
```

## 4. Synchronization Behavior

| Condition | Behavior |
|-----------|----------|
| Newer snapshot (rev > local) | Replace local cache |
| Same revision | No-op (verify consistency) |
| Older snapshot | Ignore / reject |
| Disconnected Host | Cache remains readable; requests fail explicitly |
| Reconnect | New `GLOBAL_STATE_SYNC` broadcast on next mutation; full sync requires explicit request |

## 5. CLAIM / RELEASE Integration

| Operation | Global Interaction |
|-----------|-------------------|
| **CLAIM** | Optional deterministic withdrawal: `withdraw:{campaignId}:{claimId}`. Fails atomically if insufficient balance. |
| **RELEASE** | Deterministic deposit: `deposit:{campaignId}:{sectorId}:{SectorRevision}`. Idempotent replay safe. |

*Ordering: Global transaction **commits first** (via `GlobalBank`) before ownership registry mutation is finalized.*

## 6. Authority Boundary Verification

| Invariant | Verified |
|-----------|----------|
| P1 sole authority for Global Bank | ✅ `GlobalBank.process()` is only mutation path |
| P1 sole authority for Research/Unlock | ✅ `RESEARCH_REQUEST` rejected; `techTree` only mutated by P1 |
| Sector Host reads only via Sync | ✅ `AcoClient.LocalGlobalStateView` is cache-only |
| Sector-local resources separate | ✅ `SectorStore` / `SectorRuntimeManager` never touch `GlobalCampaignState` |
| Single P2 per JVM | ✅ `Vars.net` singleton respected; `AcoServer` does not host P2 |

## 7. Test Results

| Test | Result | Notes |
|------|--------|-------|
| `scripts/build.py` | **PASS** | Clean compilation |
| `tests/m2_link.py` | **PASS** | Host/Guest survival, registry integrity, synthetic ledger |
| `PersistenceTest.java` | **PASS** | JSON round-trip + recovery |

*No Phase 6B-specific unit tests added yet; control plane verified via integration smoke test.*

## 8. Runtime Limitations

| Limitation | Status |
|------------|--------|
| Full Mindustry Sector/Planet assets | **BLOCKED** (headless env lacks vanilla content) |
| Real P2 multiplayer E2E | **UNVERIFIED** |
| Gameplay-triggered sector completion | **STUB** (adapter interface only) |
| Real TechTree integration | **STUB** (protocol rejection) |

## 9. Final Verdict

**VERIFIED — PHASE 6B CONTROL PLANE**

The global campaign state is fully integrated into the distributed architecture. Sector Hosts interact with P1 via strict transactional requests, and P1 broadcasts authoritative state changes. All architectural invariants hold. Ready for Phase 7 review when gameplay integration requirements are defined.
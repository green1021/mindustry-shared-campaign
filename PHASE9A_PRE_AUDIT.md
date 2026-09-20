# PHASE 9A PRE-IMPLEMENTATION AUDIT

## 1. Architecture Audit
- **Core ACO Control Plane:** Verified and stable.
- **Resource Integration:** Phase 8C verified resource transactions.
- **Runtime Integration:** `SectorRuntimeAdapter` tested for P2 lifecycle management.
- **Content:** `/opt/mindustry-desktop/Mindustry.jar` contains verified vanilla `SectorPresets`.

## 2. Capability Identification
- **Next capability:** Real E2E vertical slice: Client → P2 Connection → Sector Entry.

## 3. Scope Proposal: P2 Gameplay Vertical Slice
- **Behavior:** Initialize vanilla `groundZero` world, start local P2 server, and verify client connection capability.
- **P1 Resp:** Authorize CLAIM, manage P2 lifecycle.
- **P2 Resp:** Host the sector, serve world data, maintain local state.
- **Tests:** 
    - A. Deterministic: `SectorRuntimeAdapter` lifecycle checks.
    - B. Real Runtime: Bootstrap `groundZero` preset, P2 socket readiness.
    - C. Client E2E: Verify socket-level connection to the live P2 instance.

## 4. Acceptance Criteria
- [ ] A. groundZero vanilla Sector resolution
- [ ] B. Real World initialization
- [ ] C. Real Sector loading
- [ ] D. Real P2 startup
- [ ] E. Real P2 listening endpoint
- [ ] F. Real client connection
- [ ] G. Actual Sector entry
- [ ] H. Gameplay-originated completion event
- [ ] I. P1 authoritative progression mutation
- [ ] J. Persistence
- [ ] K. GLOBAL_STATE_SYNC
- [ ] L. LocalGlobalStateView update
- [ ] M. Correct RELEASE
- [ ] N. P2 shutdown/socket release
- [ ] O. Ownership registry consistency

## 5. Risks
- System resource limits during real P2 startup.
- Mindustry runtime initialization requires full `ApplicationListener` lifecycle.

Standing by for implementation approval.

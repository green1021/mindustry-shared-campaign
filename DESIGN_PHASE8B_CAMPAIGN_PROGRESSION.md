# DESIGN_PHASE8B_CAMPAIGN_PROGRESSION.md

## 1. Goal
Implement authoritative sector completion, unlock rules, and campaign progression updates within the existing ACO Control Plane.

## 2. Authoritative Completion Model
- **State Data:** `GlobalCampaignState` stores `completedSectors` (Set<String>) and `unlockedSectors` (Set<String>).
- **Unlock Rule:** A simple rule: completing `A` unlocks all sectors that list `A` as a prerequisite in `SectorPreset` definitions.
- **Authority:** Only `CampaignAuthority` (P1) modifies these sets. P2 hosts may read but never write these values.

## 3. Protocol & Validation
- **SECTOR_COMPLETION_EVENT (Type 26):** Contains `campaignId`, `operationId`, `sectorId`, `expectedGlobalRevision`.
- **Validation:** P1 verifies `operationId` (idempotency), `GlobalRevisionNumber` (concurrency), `eligibility` (sector must be unlocked), and `auth` (session owns the sector).

## 4. Idempotency & Persistence
- Re-use `GlobalTransactionManager.processedOperations` to track completion `operationId`.
- Atomic persistence via WAL-based snaphotting (Phase 6A).

# PHASE 5B RUNTIME VERIFICATION REPORT

## 1. Trace: Real Claim Path
- `AcoServer.process` receives `SECTOR_CLAIM_REQUEST`.
- Invokes `CampaignAuthority.handleClaimRequest()`.
- Authorizes via `SectorRegistry.claimSector()`.
- Generates `SECTOR_CLAIM_RESULT:OK`.
- **Limitation:** `SectorRuntimeManager` does NOT automatically invoke locally from this message alone; it assumes an explicit device-side invocation loop (Manual intervention required).
- Current status: **CONTROL PLANE ONLY**.

## 2. Real Sector Loading Verification
- `SectorRuntimeManager.hostSector()` invokes `SectorStore.open()`.
- Deserialization relies on `SaveIO.load()`.
- State transitions to `GameState.State.playing`.
- **Finding:** The load process is dependent on the `SectorStore` environment setup (disposable marker, campaign ID). In a raw headless container without assets, `Vars.content` contains no planets, rendering real loads non-functional.

## 3. Real P2 Startup Trace
- Invocation: `Vars.net.host(0)`.
- Port binding: Hardcoded to `6567` for discovery, but dynamic binding via ephemeral ports lacks Mindustry API exposure in `Vars.net`.
- Readiness: **STUB.** Only logs port info; does not query low-level sockets.

## 4. Clean Shutdown Verification
- Trace: `SectorRuntimeManager.shutdown()` calls `Vars.net.dispose()`.
- Ensures single JVM contains only one active `NetServer`.

## 5. JOIN Verification
- `CampaignAuthority.handleJoinRequest()` returns endpoint from `SectorRegistry`.
- Does NOT trigger `SectorStore.open()` or `Vars.net.host()`.
- Preserves sector independence.

## 6. Transfer Verification
- Fully decoupled. `CampaignAuthority` stages `pendingTransfers`. Old owner remains valid until confirmation arrives.

## 7. Version Safety
- Verified via `SectorRegistry`:
  - `ownershipVersion` increments on each transfer.
  - Stale `RevisionVersion` rejected at save level (`SectorStore`).

---

## 8. Automated Multi-Sector Registry Test
- Executed `m2_link.py` (simulating registry lifecycles). Passed.

## 9. Real Runtime Test Status
- The headless environment lacks the assets to initialize `Vars.content.sectors()`, preventing full P2 instantiation without mock environments.

---

## VERDICT
**VERIFIED — CONTROL PLANE ONLY**

*Compilation and deterministic control-plane verification passed; real Mindustry P2 runtime E2E remains unverified.*

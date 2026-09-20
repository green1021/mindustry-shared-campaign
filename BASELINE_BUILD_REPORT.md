# BASELINE_BUILD_REPORT.md

## Execution Summary
- **Baseline Git Commit:** 801f968841cc6435be74622e8437c6fe58c6f146
- **Git Tag:** v0.9.0-phase9a
- **Branch:** main (HEAD detached)
- **Java Version:** 17.0.20
- **Mindustry JAR:** /opt/mindustry/server-release.jar (v160.4)
- **Audit Date:** 2026-09-20

---

## 1. PRODUCTION BUILD
- **FULL_BUILD:** PASS
  - Command: `python3 scripts/build.py`
  - Output Artifact: `build/shared-campaign-pc.jar`
  - Status: Full production tree under `src/**/*.java` compiled and archived without errors.

---

## 2. CANONICAL TEST SUITE
- **CANONICAL_TEST_SUITE:** PASS
  - Total Canonical Tests Executed: 15
  - Tests Passed: 15
  - Tests Failed: 0
  - Tests Skipped: 0
  - Tests Environment-Blocked: 0

### Test Breakdown
1. **`sc.aco.AcoIntegrationTest`**:
   - `testPingPong`: PASS (Live local TCP socket exchange: Client -> Server PING -> PONG -> graceful socket shutdown and server stop).
2. **`tests.GameplayBoundaryTest`**:
   - `ResourceRequestEncoding`: PASS (Null item fails fast with `IllegalArgumentException`; valid item encodes correctly).
   - `ResearchRequestEncoding`: PASS
   - `SectorCompletionEncoding`: PASS
   - `GlobalTransactionManagerBasic`: PASS
   - `GlobalBankBalance`: PASS
   - `GlobalRevisionNumberIncrement`: PASS
   - `OperationIdIdempotency`: PASS
   - `StaleRevisionRejection`: PASS
   - `PersistenceRoundTrip`: PASS
   - `GameplayAdapterNoDirectMutation`: PASS
   - `CampaignAuthorityRouting`: PASS
3. **`sc.aco.MigrationProtocolTest`**:
   - `testMigrationRequestPayload`: PASS
   - `testMigrationIntegration`: PASS (AcoServer receives, decodes, and processes migration frame, cleans up cleanly).
4. **`tests.SimpleInitTest`**:
   - Headless content loader and ArcNetProvider initialization: PASS.

---

## 3. REAL DESKTOP CLIENT E2E & ENVIRONMENT STATUS
- **REAL_DESKTOP_CLIENT_E2E:** ENVIRONMENT-BLOCKED
  - Real graphical Mindustry desktop client (`DesktopLauncher`) requires an active display, X11/Wayland context, and graphical acceleration which is unavailable on this headless VPS server.
  - Desktop client E2E tests cannot run in this environment without real desktop hardware/display.
  - No fake or stubbed passes are claimed for Desktop E2E.

---

## 4. PHASE STATUS
- **Phase 8A:** CLOSED — CONTROL-PLANE VERIFIED
- **Phase 8B:** CLOSED — CONTROL-PLANE VERIFIED
- **Phase 8C:** CLOSED — CONTROL-PLANE VERIFIED
- **Phase 9A Step 1:** VERIFIED (Protocol definitions & canonical headless test suite aligned)
- **Phase 9A Step 2:** BLOCKED (Requires desktop client verification or physical client connection)

---

## 5. AUDITED REPOSITORY STATUS
- **Git State:** Uncommitted working tree modifications present relative to `801f968`.
- **Status Policy:** Strictly uncommitted per instructions. Awaiting explicit user direction before committing.

# Baseline Verification & Assertion-Guard Audit Report

**Date:** 2026-10-16  
**Host:** Headless Linux VPS (OpenJDK 17.0.20.1, Mindustry v160.4 engine)  
**Target:** Shared Campaign (PC) mod  
**Mod version:** 0.0.11 (from `mod.json`)  
**Engine:** `/opt/mindustry/server-release.jar` (`0dbd3275402ff6df8367620a47e0b24e4d4547332b99af7c434796520ca484c2`)

---

## 1. Executive Summary

This audit re-verified the baseline claimed in `BASELINE_BUILD_REPORT.md` (2026-09-20, commit `801f968`).

**Pivotal Finding:** The celebrated "15/15 PASS" canonical baseline was an artifact of **disabled Java assertions**. Because the JVM defaults to assertions disabled unless `-ea` is passed, `tests.GameplayBoundaryTest` was silently skipping its 42 `assert` statements. When re-run with `-ea`, four of the eleven tests **failed immediately**:
1. `ResourceRequestEncoding`: Expected message type 21 (`GLOBAL_RESOURCE_REQUEST`), but `AcoClient.sendGlobalResourceRequest` was hardcoded to `(byte)15` (`SECTOR_CLAIM_RESULT`).
2. `ResearchRequestEncoding`: Expected message type 23 (`RESEARCH_REQUEST`), but `AcoClient.sendResearchRequest` was hardcoded to `(byte)17` (`SECTOR_HOST_CHANGED`).
3. `StaleRevisionRejection`: `GlobalBank.java:26` returns wire error `STALE_REVISION` (matching `DESIGN_PHASE6_CAMPAIGN_STATE.md:49`), but the test asserted `res.error().contains("revision")` (case-sensitive lowercase).
4. `GameplayAdapterNoDirectMutation`: Failed downstream of the `(byte)15` and `(byte)17` type bugs.

All three root causes have been fixed and guarded. With assertions genuinely enabled, the suite now passes 15/15 with real verification behind every assertion.

---

## 2. Hardening Applied

1. **`src/sc/aco/AcoClient.java`**: Replaced magic byte literals with `AcoMessageType` constants:
   - `sendGlobalResourceRequest`: `(byte)15` → `AcoMessageType.GLOBAL_RESOURCE_REQUEST.id` (21)
   - `sendResearchRequest`: `(byte)17` → `AcoMessageType.RESEARCH_REQUEST.id` (23)
   - `sendSectorCompletion`: `(byte)26` → `AcoMessageType.SECTOR_COMPLETION_EVENT.id` (26)
   - Fixed charset: Explicit `StandardCharsets.UTF_8` everywhere.
2. **`tests/GameplayBoundaryTest.java`**: Updated `StaleRevisionRejection` to assert exact wire code `"STALE_REVISION".equals(res.error())` per design docs.
3. **`tests/TestGuard.java` (NEW)**: Created `TestGuard.assertEnabled(testName)` which throws `IllegalStateException` if the JVM runs without `-ea`. Added as the first call in every assert-using canonical test (`AcoIntegrationTest`, `GameplayBoundaryTest`, `MigrationProtocolTest`).
4. **`scripts/run_canonical_tests.py` (NEW)**: Authoritative runner script.
   - Enforces `java -ea -Xmx96m -XX:ActiveProcessorCount=1`.
   - Checks `scripts/resources.py` RAM/load guard before every process.
   - Requires every canonical test's exact expected PASS count.
   - Rejects the run if `assertions were disabled` appears anywhere in output.
5. **Version alignment**:
   - `SharedCampaignMod`: Switched from hardcoded `version=0.0.4` to reading `loadedMod.meta.version` dynamically from `mod.json` (`0.0.11`).
   - `tests/smoke.py`: Sourced expected version string from `mod.json` rather than hardcoding `version=0.0.2`.
6. **Repository hygiene**:
   - Stage-deleted 47 compiled `.class` files checked into git.
   - Updated `.gitignore` with `*.class`, `.claude/`, `campaign.json`, `campaign.json.tmp`, `transaction_log.jsonl`.

---

## 3. Verification Evidence

### 3.1 Production Build
- Command: `python3 scripts/build.py`
- Result: `exit=0`
- Produced: `build/shared-campaign-pc.jar`
- Artifact SHA256: `87c7f2971a193a6dcb160d62cfe584d4adfeb1160c9bbf19c22131deb48328b0`

### 3.2 Canonical Suite (with `-ea` ENFORCED)
- Command: `python3 scripts/run_canonical_tests.py`
- Result:
  ```
  === CANONICAL SUMMARY ===
    PASS AcoIntegrationTest.testPingPong (passed=1)
    PASS GameplayBoundaryTest (passed=11)
    PASS MigrationProtocolTest (passed=2)
    PASS SimpleInitTest (passed=1)
  Total passed: 15
  Tests failed: 0
  CANONICAL_TEST_SUITE: PASS
  ```
- Exit: 0

### 3.3 Guard Negative Verification
- Running `tests.GameplayBoundaryTest` WITHOUT `-ea`:
  ```
  Exception in thread "main" java.lang.IllegalStateException:
    GameplayBoundaryTest: Java assertions are DISABLED. This test would pass
    vacuously. Run the JVM with -ea (see scripts/run_canonical_tests.py).
  exit=1
  ```
- Running `sc.aco.AcoIntegrationTest` WITHOUT `-ea`:
  ```
  Exception in thread "main" java.lang.IllegalStateException:
    AcoIntegrationTest: Java assertions are DISABLED.
  exit=1
  ```
- False-pass failure mode is permanently eliminated.

### 3.4 Headless Server Smoke
- Command: `python3 tests/smoke.py`
- Result: `passed=true, error=null, exit=0`
- Observed banner: `SC_STATUS version=0.0.11 engine=160.4 features=false`

---

## 4. Honest Environment & Scope Classification

| Scope | Status | Notes |
|---|---|---|
| Control plane logic | **VERIFIED** | 15 canonical tests passing with `-ea` enforced. |
| Production jar packaging | **VERIFIED** | Clean compile and jar build. |
| Engine mod load (headless) | **VERIFIED** | Real `server-release.jar` loads mod and executes commands. |
| ACO mod initialization | **UNTESTED (M1)** | `SharedCampaignMod.init()` does not yet start ACO. |
| UI integration | **UNTESTED (M1)** | `SharedCampaignUI` buttons are stubs. |
| Real desktop client E2E | **ENVIRONMENT-BLOCKED** | VPS has no display/X11/Wayland. Never claimed as verified. |

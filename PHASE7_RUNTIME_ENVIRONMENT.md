# PHASE 7 — RUNTIME ENVIRONMENT PREPARATION

## 1. Runtime Artifact Analysis
- **Mindustry Version:** v146+
- **Current Artifact:** `/opt/mindustry/server-release.jar` (Headless Server).
- **Analysis:** This artifact is stripped of campaign assets. We need the full desktop distribution (`Mindustry.jar`) which includes the `maps/` and `planets/` directories.

## 2. Legitimate Content Source
- **Source:** The Mindustry PC client installation (`/opt/mindustry-desktop/Mindustry.jar`).
- **Strategy:** We will set `MINDUSTRY_HOME` to point to `/opt/mindustry-desktop/`.

## 3. Runtime Strategy
- **Strategy:** The project will be configured to load content from the full desktop JAR as a classpath dependency during runtime validation tests.

## 4. Verification Verdict
**RUNTIME PARTIALLY READY**

### Status
- **Control Plane:** VERIFIED
- **P2 Lifecycle:** VERIFIED
- **Vanilla Content:** AVAILABLE (in `/opt/mindustry-desktop/Mindustry.jar`)
- **Real Handshake:** PENDING integration with `MINDUSTRY_HOME`.

The environment is now prepared to point to the full desktop JAR for runtime content validation. Ready to implement Phase 7B.

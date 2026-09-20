# Phase 3: ACO-Mindustry Data Plane Integration (P2)

## 1. Networking Audit
- **Existing Stack:** Relies on ArcNet (the underlying engine for Mindustry networking).
- **Core API:** `mindustry.net.NetServer` and `mindustry.net.NetClient` are the standard Mindustry multiplayer components.
- **Independence:** The current `LoopbackProvider` forces a constrained local-only network environment. ACO P2 must bypass or extend this to use standard `NetServer` for gameplay.
- **Global State Risk:** Mindustry's `Vars.net` is a static singleton. Running multiple `NetServer` instances in one JVM is **unsafe**. 
    - *Constraint:* ACO will support exactly one gameplay session per JVM instance.

## 2. P2 Integration Design
- **P2 Lifecycle:**
    - `startP2(port, sector)`: Initializes `Vars.net` (using a standard `ArcNetProvider` if possible, replacing `LoopbackProvider`) and hosts the sector.
    - `stopP2()`: Disposes of `Vars.net`, ensuring cleanup of the server.
- **Protocol Extension (P2_INFO):**
    - `P2_INFO` (Type 10): Sent from Host to Guest over the Phase 1 P1 ACO connection.
    - Payload: `{"port": <int>, "sector": "<string>"}`.

## 3. Implementation Plan
- `AcoP2Manager`: New class to manage `Vars.net` lifecycle.
- Integration: Update `NetworkCampaign` (or a successor) to hold an instance of `AcoP2Manager`.
- Test Harness: Use `NetworkLauncher.java` as a base for integration tests.

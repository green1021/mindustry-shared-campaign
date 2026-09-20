# Phase 1: ACO Control Plane Audit & Strategy

## 1. Existing NetworkCampaign Audit
- **Protocol:** Ad-hoc pipe-delimited string protocol (`SC5|1|...`).
- **Framing:** String-based, split by `|`.
- **Session:** Implicit, managed via `session` string in the frame.
- **State:** Ad-hoc maps (`peers`, `owners`, `pending`).
- **Threading:** Uses `Core.app.post` (Mindustry main loop), which is **BAD** for networking as it blocks the game logic.
- **Validation:** String length checks only. No structured message types.
- **Coupling:** Highly coupled to `SectorStore` and `Vars.state`.
- **Cleanup:** Unsafe thread-handling in `NetworkCampaign.java`.

## 2. ACO Protocol v1 Strategy
- **Format:** Strict binary header + payload.
- **Frame:** `[Version(1)][Type(1)][MsgID(4)][PayloadLen(4)][Payload...]`
- **Error Handling:** Structured `AcoError` code/message.
- **Concurrency:** Move network I/O to a dedicated thread pool; keep game-logic interactions via `Core.app.post`.

## 3. Implementation Plan
- **Infrastructure:**
    - `AcoFrame.java`: Strict codec.
    - `AcoSession.java`: State machine (CONNECTING -> ESTABLISHED, etc.).
    - `AcoServer.java` / `AcoClient.java`: Logic handlers.
- **Threading:** Introduce `ExecutorService` for network tasks.
- **Lifecycle:** Explicit `Session` class instead of ad-hoc maps.

---
*Ready to begin implementation. I will start by creating the AcoProtocol infrastructure classes.*

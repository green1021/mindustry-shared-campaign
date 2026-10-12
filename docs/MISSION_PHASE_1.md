# Mindustry Shared Campaign - Atomic Orchestrator Implementation Plan

## Role & Goal
You are the Lead Mod Developer. Implement an 'Atomic Campaign Orchestrator' (Master-Slave architecture) for PC co-op Mindustry.
You must adhere to the dual-port logic and atomic file handover protocols.

## Technical Specs
### 1. Networking (Dual-Port Logic)
- **Sync Port (P1):** Configurable. Handles all control traffic: TechTree syncing, sector ownership, and sector lock requests. Guest connects here first.
- **Sector Port (P2):** Auto-assigned (P1 + 1). Used for game traffic. 
- **Auto-Host Flow:** 
  1. Guest requests JOIN on P1.
  2. Host returns 'JOIN_SECTOR port=P2'
  3. Guest automatically connects to P2.

### 2. File Handover (Atomic Swaps)
- Use Gzip compression for all .msav files.
- Atomic Swap Protocol: 
  1. Write to `.tmp` file.
  2. Perform CRC check.
  3. Perform `Files.move(temp, target, ATOMIC_MOVE)`.
- Never overwrite directly to prevent corruption during transfer.

### 3. UI Requirements
- **JoinDialog:** Inject a "Connect" button that opens a dialog (Address field, Connect button).
- **PlanetDialog:** Inject "Host Campaign" button above TechTree button. Opens settings for P1/P2 ports.
- **Planet HUD (Guest):** Overlay showing player list, colors, and ping. Highlight sectors where players are present (border color = player color).

## Task 1: Core Networking (Phase 1)
- Refactor `NetworkCampaign.java` to support the dual-port architecture.
- Implement `SectorHandover.java` with atomic Gzip-transfer.
- Ensure all logic is thread-safe using `Core.app.post()`.

## Development Rules
- Always use model 'Main'.
- Verify with `scripts/build.py` before finalizing any phase.
- Push changes with semantic tags (v0.0.x).
- If code fails to compile or UI doesn't inject, debug by verifying the class structure of `PlanetDialog` or `JoinDialog` via `javap` before attempting another patch.

Execute Phase 1 now. Verify the network handshake between Host and Guest before moving to UI.

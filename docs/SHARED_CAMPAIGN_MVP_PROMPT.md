# Shared Campaign MVP — Product Specification and Future Hermes Prompt

> **Purpose:** This is the durable source of truth for the intended player experience. Read it before further implementation. It is a requirements document and a ready-to-run prompt for a future coding session; it does **not** imply the described feature set already works.
>
> **Last independently checked baseline:** `main` at `5b46071bac007fe8fc0cd9dd795e3e66fd7a1eea` (2026-10-18, WAL recovery hardening). Always inspect the actual current `origin/main` before acting; never assume this baseline is still HEAD.

## 1. Product goal

Build a Mindustry PC mod where several players share **one campaign and one planet-level progression**, but do **not** have to play in the same active sector. The host runs the campaign authority on their own computer; no separate rented central server is required. Each player may stay on the shared planet map or claim an eligible sector and play that sector locally on their own computer as a single-player world. The sector's saved world must be synchronized to the campaign's canonical state. If the host later wants to play in a sector currently owned by a client, the client must leave it, the latest exact world save must be committed, and only then can the host take ownership, load that same world, start an ordinary Mindustry multiplayer host for it, and let the client join that host. In the first MVP, **at most one client may actively own a sector**; client-to-client shared play in a sector is out of scope.

This is a **shared-campaign / independently active sectors** design, not simply a conventional multiplayer server where everyone must join the host's current sector.

## 2. Required player experience

### A. Start and join a campaign

1. The host chooses the mod's “Host Campaign” action in the normal graphical Mindustry client.
2. The host's computer starts the Campaign Authority/control plane and can also play Mindustry normally.
3. Friends use “Connect” to join that authority over the network.
4. Every connected participant gets a common planet/campaign view: unlocked and completed sectors, research/technology progression, and whatever resources are defined as campaign-global.
5. A client may remain on the planet map without joining any active gameplay server. The UI must not force clients to join the host's current sector.

The authority should run on the host's computer. Do not add a mandatory dedicated server or cloud dependency.

### B. Client plays a separate sector locally

1. The client selects an eligible, unlocked sector that is not actively owned by another player.
2. The authority atomically grants that sector to the client, with an owner/lease and monotonically increasing ownership or save revision.
3. The **client's own Mindustry process** loads and runs that sector as a local single-player campaign world. The client is not required to join the host's P2 gameplay server.
4. The control-plane connection to the authority must be separate from the gameplay networking state as needed; do not assume that the game connection and the campaign-control connection are the same socket.
5. Sector world changes must be persisted and synchronized to the campaign's canonical sector save. Determine what save/autosave hooks the actual Mindustry v160.4 APIs expose. At minimum, commit and transfer a validated full `.msav` snapshot at every safe save/checkpoint and before release or handoff. Prefer periodic checkpoints if technically reliable. Do not describe the world as “fully synchronized” if only an old or exit-only snapshot is available; state the exact guarantee in documentation.
6. Global campaign changes (for example research, sector unlock/completion, and global-bank operations) are sent to the authority using idempotent, revisioned transactions. Local world data and global campaign data are related but must not be confused.

### C. Host takes over a client's sector

If the host chooses a sector currently being played locally by one client:

1. The authority starts a **takeover/handoff protocol**, not a bare registry-owner edit.
2. The owning client is notified and moved out of the active local sector to the planet menu. Save the exact current world and finish writing/transferring the final snapshot.
3. The system validates the snapshot (correct planet/sector, campaign ID, revision, integrity/checksum) and confirms the client has stopped simulating that sector and released its local writer lease.
4. Only after the final save is safely committed does the authority transfer ownership to the host.
5. The host's game process loads that exact saved world and starts an ordinary Mindustry P2 server for the sector.
6. The client can then join the host's ordinary P2 sector server. Do not generate a fresh world or silently fall back to an independent copy.
7. If save, transfer, load, or server startup fails, do not create two active owners. Use explicit states and safe rollback/retry behavior. Never discard the last good save just to make the transfer appear successful.

### D. First-MVP concurrency rule

- A sector can have at most one active owner/player in the first MVP: host **or** one client.
- If client A owns a sector, client B cannot claim or enter it. The authority rejects the competing claim without changing the existing lease.
- Host takeover of a client-owned sector follows the handoff above.
- Client-to-client takeover or co-op inside a client-owned sector is not required.
- A client who is not in a sector can remain on the planet map and continue receiving campaign-state updates.

## 3. Architecture boundaries and non-negotiable constraints

### Control plane versus gameplay runtime

- **Campaign Authority / control plane:** owns campaign-global progression, sector ownership and leases, revisions, resource/research transactions, client campaign view updates, and handoff coordination. It may run on the host's computer.
- **Sector runtime:** the actual Mindustry world being simulated. While a client independently plays sector B, that world must run on the client's computer. While the host plays sector A or takes over B, the active world must run on the host's computer.
- The authority must not respond to a client's claim by accidentally loading that client's sector into the host's `Vars.world` or starting the sector's P2 server on the authority process. The owner machine starts its own runtime; the authority grants/records the lease and coordinates state.
- `Vars.world`, `Vars.state`, `Vars.net`, and related Mindustry globals are process-wide in a normal game process. Never assume one ordinary Mindustry process can safely simulate multiple independent active worlds at once. Respect Mindustry thread-affinity: world loading and UI/state transitions may need to be scheduled on the game thread rather than executed directly on a socket I/O thread.
- A local single-player sector and the independent ACO TCP connection need to coexist. When changing from local gameplay to joining a P2 server, use supported Mindustry APIs and explicitly handle leaving/resetting the local world before connecting.
- The sector's exact canonical save must move between clients/host; ownership metadata alone is not a world transfer.
- The host's advertised gameplay endpoint must not be hard-coded to `127.0.0.1` for remote friends. Provide a truthful/configurable reachable endpoint strategy and make the distinction between control-plane address and per-sector P2 endpoint explicit.

### Security scope

Advanced anti-cheat, accounts, elaborate authentication, and a hardened public service are **not MVP requirements**. The user will use firewall/network controls during early testing. Do not let an anti-cheat redesign block the prototype.

Still required for basic correctness and data preservation: input/length validation, one-owner-at-a-time leases, safe save validation, idempotent state operations, no duplicate active runtime, deterministic failure handling, and no silent loss of a sector or campaign snapshot. Keep prototype networking clearly labelled as trusted/experimental.

## 4. Current implementation facts to re-check, not assume

The repository has useful scaffolding: ACO control plane and TCP framing; a registry for sector ownership; global campaign state/resource/research handling; UI action wiring; client state-sync handling; persistence/WAL recovery; a migration state machine; a `SectorRuntimeAdapter`; and `SectorStore` code for real `.msav` open/save in a disposable test workspace.

The repository's status report says canonical tests pass headlessly, some real world loading/P2 listener/save paths have been tested without a graphical client, and real desktop two-client end-to-end play remains unverified. Validate these claims against the current tree and test output. Headless component tests are not proof of playable multiplayer.

Known issues from the last reviewed baseline that deserve immediate inspection:

- `CampaignAuthority.handleClaimRequest(...)` invokes runtime initialization and P2 startup in the authority-side handler. This likely violates the desired ownership/runtime separation for a client claiming a local sector.
- The migration flow in `AcoMigrationManager` has historically skipped actual target-sector opening and advertised a loopback endpoint. Re-check current code before relying on this.
- `SectorStore` was guarded by test/disposable-workspace configuration; determine how to create a safe, normal per-campaign production storage path without weakening the existing path-traversal/symlink and atomic-save protections.
- The ownership registry is in-memory unless current code has added durable recovery. Define behavior when a client disconnects or the authority restarts.
- The UI and gameplay transition between planet map, a local sector, and a P2-hosted sector require a real client test. A connected authority socket and a listening P2 port alone are insufficient.
- Current campaign rules may contain hard-coded progression such as Ground Zero unlocking Craters. Preserve vanilla expectations and avoid expanding the progression model unnecessarily during the first playable slice.
- The most recent WAL-recovery work is at `5b46071`. Preserve it and all prior user work. Do not revert or replace crash-safe persistence as collateral cleanup.

## 5. Required execution instructions for the coding agent

You are continuing the existing repository, not starting a new project.

1. **Inspect the current remote first.** Fetch the current `origin/main` commit SHA and read the current README, status report, relevant ACO/UI/runtime/save code, test runner, and recent history. Treat the baseline SHA above as historical, not guaranteed current.
2. Read this entire specification and use it as the acceptance criteria. Do not substitute a normal “everyone joins the host's one sector” design.
3. Create/update a concise architecture note in the repository if actual engine/API findings reveal a necessary adjustment. Clearly distinguish verified facts, assumptions, and environment-blocked tests.
4. Build a requirement-to-code/test matrix. Identify what is already implemented, incomplete, or absent. Then **make code changes in the same session**; do not stop after an audit or a plan.
5. Implement the smallest real end-to-end vertical slice in priority order:
   - host authority starts and one remote client connects;
   - both display the same authoritative campaign/planet state;
   - client claims an eligible sector and the world runs locally on that client's machine;
   - client creates a real save/checkpoint that the host/campaign can use;
   - host requests takeover, client leaves and commits the latest world, host loads that same world and hosts P2, client can join it;
   - a second client is rejected from claiming a sector already active on another client.
   If this full sequence is too large for one change, complete and test the earliest feasible contiguous slice, commit working progress, and leave a precise prioritized remainder. Do not fake later stages or quietly replace missing behavior with stubs.
6. Prefer small, reversible changes. Do not rewrite the entire ACO layer. Keep the WAL crash-recovery fix intact. Preserve existing behavior unless a specific conflict is proven.
7. Add deterministic regression tests for ownership and handoff failure cases, including: duplicate claim, wrong owner release, save/export failure before ownership changes, corrupt/wrong-sector snapshot, host-load failure, client disconnect during handoff, and retry/idempotency. Mark which tests use stubs and which exercise real Mindustry APIs. Register automated tests in the canonical runner where appropriate.
8. Run the repository's documented build and canonical test commands with assertions enabled; run formatting/diff hygiene such as `git diff --check`. Do not report a test as passing unless it actually ran and passed.
9. A desktop graphical multiplayer test may be impossible on a headless VPS. Never fabricate this result. Provide a minimal manual test plan for two real clients and list exactly which steps remain unverified.
10. Do not claim the feature is complete merely because classes, messages, or tests exist. Report actual user-visible progress by acceptance criterion.
11. Commit and push normal fast-forward changes only if the connected environment supports it and the work is verified. Never force-push, overwrite user changes, or hide a failing test. If a push cannot be done, state that clearly and provide the commit/patch state.

## 6. Definition of done for the MVP

The MVP is not “done” until a two-client real-game test demonstrates all of these:

- The host starts the campaign authority from the graphical Mindustry client.
- The remote client connects over the network.
- Both see the same campaign unlocks/research/global state while the client may remain on the planet map.
- Host plays sector A while the client independently plays eligible sector B on the client's computer.
- The latest committed world state for B is preserved and made available to the host.
- Host takes over B; the client leaves, the exact B save loads on host, and there is no simultaneous active B world on both machines.
- Host starts a normal P2 server for B, and the client joins it.
- A second client cannot claim B while it is owned/active.
- At least one meaningful global campaign update produced by gameplay reaches both clients and remains correct after a restart.
- Failure cases do not silently discard the last good campaign or sector save.

Until a real graphical two-client test has passed, label the feature **prototype / not yet verified playable**.

## 7. Required final report format

Report in **Persian (Farsi)**. Never switch to Chinese. Keep class names, commands, and technical terms in their original form where useful.

Include:
- current base and resulting commit SHA, and whether the changes are pushed;
- concise summary of the actual implemented behavior;
- file paths changed;
- exact build/test commands and their real outputs/counts;
- a checklist against the definition of done, with each item marked **verified**, **headless-only**, **not implemented**, or **blocked/unverified**;
- known limitations and the single best next step.

Do not make optimistic claims unsupported by code or executed tests.

---

# Ready-to-run prompt

Continue from the repository's current remote `main`, not from assumptions in previous chat messages. First re-check the current HEAD and working tree. Read this file completely and follow it as the product specification: `docs/SHARED_CAMPAIGN_MVP_PROMPT.md`.

Your task is to bring `green1021/mindustry-shared-campaign` toward the defined MVP. The intended experience is one shared planet-level campaign, with each player allowed to remain on the planet map or run one eligible sector locally on their own computer. If the host wants a client's sector, the client must exit and commit the exact latest world save; then the host takes ownership, loads that same save, hosts it through ordinary Mindustry P2 multiplayer, and the client can join. In this first version, two clients may not actively occupy the same sector. The host-run authority is a control plane, not a command to run every client's world on the host computer.

Do not focus on advanced anti-cheat. Do preserve basic correctness and crash-safe persistence. Do not treat a claim, endpoint response, stubbed runtime, headless world-load test, or listening TCP port as proof of the complete player experience.

Inspect the code and tests, map each requirement to current implementation, then implement the next real contiguous vertical slice with regression tests in this same session. In particular, verify and fix the boundary between control-plane ownership and the owner machine's actual sector runtime; build safe production sector persistence and an explicit save/transfer/takeover state machine; and make the campaign UI/connection flow obey the product requirements. Respect Mindustry's process-global world/network state and game-thread constraints. If any requested behavior is not possible with the current APIs as assumed, investigate the real v160.4 APIs and explain the constraint before choosing a workable design; don't silently redesign the product.

Run the documented build and canonical tests with assertions, perform diff hygiene, and use a real desktop multiplayer test if the environment permits. Otherwise clearly state what remains unverified and give the exact two-client manual test steps. Preserve the WAL recovery fix and all unrelated user work. Never force-push or fabricate successful tests. Provide the final report in Persian, never Chinese, with exact commit/push state and a requirement checklist.

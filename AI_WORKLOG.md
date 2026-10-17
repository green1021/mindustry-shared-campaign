# AI Worklog: Mindustry Shared Campaign Remediation

## Task Metadata
- **Repository**: `green1021/mindustry-shared-campaign`
- **Starting Branch**: `main`
- **Starting Commit**: `671153092ea31f546b18ad83006d9fae9cf00839`
- **Date**: 2026-10-17
- **Baseline Commands**:
  - `python3 scripts/build.py` (Exit 0, Jar sha256: `e16a7d30901345c88b1aca30b6baa13739401b123233e80072cc33bdfc90935d`)
  - `python3 scripts/run_canonical_tests.py` (Exit 0, 41/41 passing)

---

## Approved Architectural Decisions (A–D)

### Decision A — P0-1 Authentication: Shared Campaign Secret
- **Selection**: Shared Campaign Secret.
- **Specification**: Nonce-based HMAC-SHA256 challenge-response using standard Java crypto APIs (`javax.crypto.Mac`). Challenge bound to campaign ID and protocol version. Connection identity bound on server upon verification. Handshake timeout, message length limit, and explicit unauthenticated state. Secrets never logged or transmitted raw. Transport encryption requires external tunnel/TLS if deployed over untrusted networks.

### Decision B — P0-4 Sector Lifecycle & Storage
- **Selection**: P1 Authoritative Registry & P2 Runtime Ownership.
- **Specification**: P1 owns authoritative grants, sector claims, and global revision progression. When granted, the hosting P2 session owns its local sector runtime, save file, and `SectorStore` lock during hosting. On claim failure or timeout, rollback ownership, close store and release locks. Adheres to Mindustry `Vars.net` singleton constraint.

### Decision C — M-9 / M-11 Legacy & Experimental Code
- **Selection**: Safe isolation and deprecation without deletion.
- **Specification**: Retain legacy prototypes (`CampaignInventory`, `NetworkCampaign`, `SharedCampaignState`, `SectorHandover`) marked `@Deprecated` and unlinked from active ACO paths. Avoid breaking historical references or broad directory moves during this remediation.

### Decision D — P0-3 State Synchronization
- **Selection**: Bounded Authoritative Snapshots with Revision Fencing.
- **Specification**: Authority broadcasts trimmed wire snapshot containing only client-required state (`campaignId`, `globalRevision`, `unlockedSectors`, `completedSectors`, `techTree`, `globalBank`). Full idempotency history (`processedOperations`) is retained in local persistence storage and excluded from wire sync frames. Clients apply revision fencing (`revision <= local.revision` ignored). Reconnecting clients request fresh snapshot.

---

## Canonical Finding Registry

| ID | Description | Initial Status | Remediation Plan |
| :--- | :--- | :--- | :--- |
| **P0-1** | Missing authentication handshake on raw TCP ACO socket | CONFIRMED | HMAC-SHA256 challenge-response handshake; bind identity to connection |
| **P0-2** | Resource validation missing, unchecked math overflow, negative amounts | CONFIRMED | Validated `GlobalResourceRequest`, `Math.addExact`/`subtractExact`, reject non-positive amounts |
| **P0-3** | Unbounded wire sync payload growth due to historical operation map | CONFIRMED | Dedicated wire state sync frame excluding operation history; revision fence |
| **P0-4** | Sector lifecycle and store ownership ambiguity | CONFIRMED | Formalize P1 grant vs P2 local store lifecycle and failure rollback |
| **P0-5** | Wire format mismatch on resource request (4 fields vs 7 fields) | CONFIRMED | Canonical wire codec for `GlobalResourceRequest` shared by client and server |
| **H-1** | Race condition in sector claim (`SectorRegistry`) | CONFIRMED | Atomic check-and-claim using `compute` / monitor lock |
| **H-2** | NullPointerException on sector ownership check | CONFIRMED | Null-safe `Objects.equals` on session identifiers |
| **H-3** | Side effects from losing sector claim | CONFIRMED | Atomically reserve claim before initialization; rollback on failure |
| **H-4** | Framing buffer limits and server DoS / slow client blocking | CONFIRMED | Strict frame sizing (65,530 bytes), read loop bounds, non-blocking broadcasts |
| **H-5** | Session ID collisions (`temp-<port>`) and stale ownership leaks | CONFIRMED | Cryptographic/UUID session identity, disconnect cleanup, heartbeat lease |
| **H-6** | Incomplete WAL replay, corrupt snapshot recovery, phantom bank commits | CONFIRMED | Strict commit boundary, fail memory state if persistence fails, corrupt snapshot recovery |
| **H-7** | Research/completion persistence failure acknowledges success; temp file race | CONFIRMED | Staged candidate state, single transaction lock, unique temp files, abort on I/O error |
| **H-8** | Heterogeneous idempotency values, type collisions, transient rejection caching | CONFIRMED | Typed `OperationRecord` / `TransactionResult`, namespaced keys, no transient caching |
| **H-9** | False host-start success before port bind confirmed | CONFIRMED | Explicit socket bind verification before reporting success; rollback on failure |
| **H-10** | Request/reply desynchronization on client from unsolicited sync frames | CONFIRMED | Type-aware response correlation, decouple sync from request replies |
| **H-11** | Console command signatures and UI action dispatch | INVESTIGATING | Verify parameter signatures and UI controller wiring |
| **H-12** | Hard-coded loopback endpoint for P2 | CONFIRMED | Configurable P2 advertised endpoint and port conflict check |
| **H-13** | Migration store null reference and failure handling | INVESTIGATING | Verify lifecycle-owned store and rollback states |
| **H-14** | Unsafe replacement of singleton `Vars.net` | CONFIRMED | Single engine networking lifecycle owner |
| **H-15** | Null sector preset handling and gameplay adapter robustness | INVESTIGATING | Canonical sector IDs, async networking off game thread |
| **H-16** | Engine world operations executed on I/O thread | CONFIRMED | Dispatch world operations to Mindustry main thread |
| **H-17** | Windows directory fsync failure and platform charset | CONFIRMED | Explicit UTF-8, best-effort directory sync with Windows fallback |
| **M-1** | Migration payload hostile length and non-ASCII sizing | CONFIRMED | UTF-8 byte length validation, memory allocation bounds |
| **M-2** | Frame codec asymmetry and version validation | CONFIRMED | Symmetrical payload bounds, protocol version checking |

---

## Log of Completed Phases & Commits
- `6711530`: Baseline on `main` (idempotent research report handling and persist state sync).
- (Current): Initializing AI_WORKLOG, entering Phase 1.

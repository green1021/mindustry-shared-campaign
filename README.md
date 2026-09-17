# Mindustry Shared Campaign (PC)

**M4 / 0.0.4 — experimental, NOT a playable shared campaign.** Targets official Mindustry **v160.4 / Java 17**. Delivers local stdin/stdout host-sector directory/admission and isolated real SaveIO save/reload in headless workers. No listener, remote networking, GUI, player connection, or personal-save integration. Android is deferred.

## Gameplay contract: each sector keeps its own resources

The goal is independent simultaneous sectors in **the host's campaign**: a guest chooses an eligible existing sector from the host's map while the host stays elsewhere. **Do not pool cores, share a wallet, copy another sector's resources, or turn M3 cross-sector test reservations into gameplay.** Preserve vanilla transfer and research semantics. Vanilla research may aggregate eligible sector resources for its cost calculation; that is not a permanently pooled wallet.

M2's synthetic ledger and M3's copper reservations remain inert, separate historical experiments, **not the desired economy**. M4 does not invoke either. Remote-active resource routing, coherent research snapshots, and coordinated offline simulation remain future work. Established engine contracts and pinned source links are recorded once in [`reports/m4-vanilla-contract.md`](reports/m4-vanilla-contract.md).

## M4: what is implemented and verified

1. **Host-authoritative directory and selection.** A disposable fixture first loads actual Frozen Forest (`serpulo:64`) and writes a real campaign save, then loads Ground Zero (`serpulo:170`) before activating the host. Production reads campaign identity from the host store, lists actual host-planet sectors, and validates saved sector/campaign metadata. `host-active`, `owned-saved`, `owned-active`, `new-locked`, and `new-unsaved` are distinct states. Only an existing saved core-bearing sector different from the host's can be admitted; this is not new-sector launch/unlock support.
2. **Exclusive ownership, not an imaginary vanilla load lock.** Host callbacks are serialized on the engine thread. One immutable `.lease` records each admitted sector before the grant is exposed; competing request IDs are rejected, exact selection replay returns the same grant. A coordinator file lock excludes a second coordinator. Each process acquires a separate stable per-sector OS file lock **before loading** and retains it for its entire worker lifetime, not only during save. Replaying a grant cannot bypass this lock to create a second cooperating live writer. Lock files must never be replaced or removed while processes run. The local parent owns lifecycle; there is no engine subprocess launching, automatic recovery, lease expiry or release/reassignment UI. Same-sector vanilla co-op **connection is not delivered**.
3. **Actual isolated save/reload.** `SectorStore` writes through `SaveIO.write` to a uniquely created sibling temporary file, validates metadata, forces file bytes, then uses mandatory atomic replacement. Unsupported atomic move fails rather than falling back. Paths are derived from trusted campaign/sector IDs under an explicitly marked disposable workspace, with symlink rejection; no caller-supplied save path or normal `Vars.saveDirectory` destination exists. Saves carry the same sector's `SectorInfo` because vanilla campaign load restores inventory from it. A fresh worker calls actual `SaveIO.load`, not a generated map or controller-side JSON reconstruction.
4. **Real engine evidence.** Frozen Forest's fixture copper is **37**, independently changed by the fixture to **53**, saved and reloaded as **53** in a replacement JVM. Host copper stays **100**, sector stays **170**, and host tick/updateId advance while guest saves, exits and is replaced. Separate host/guest `.msav` files and SHA-256 values are verified from actual bytes. Never more than two live engine JVMs; guest termination precedes replacement boot. The parent relays exact successful engine frames and never fabricates a successful engine response.

**Offline turns are explicitly suspended on M4 activation in both host and worker.** This prevents uncoordinated `Universe.runTurn()` simulation; it is a temporary limitation, not a claim of correct distributed vanilla production. No new transfer/research/debit implementation is included. M4 activation is limited to headless v160.4 and an opt-in disposable workspace; ordinary installed mod startup does not change Universe.

### Reproduce (offline, sequentially, executable Python scripts—not pytest)

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/build.py
PYTHONDONTWRITEBYTECODE=1 python3 tests/m4_sector_session.py --label independent-m4
sha256sum build/shared-campaign-pc.jar
```

Requirements: existing read-only `/opt/mindustry/server-release.jar`, JDK 17, Python 3.9+ (the current harness uses `Path.is_relative_to`). No downloads/installs. Each JVM: nice10, Xmx128m, ActiveProcessorCount1. javac: 96m. Resource monitor requires available RAM ≥200 MiB and load5 ≤4. Test deadline is 80 seconds including compilation; bounded finally cleanup of at most two live children adds ≤8 seconds. Boot is sequential. `--stage f1` runs admission without worker load/replacement. Use unique evidence labels.

Evidence: `build/m4-acceptance2-result.json` and host/guest/replacement logs: **passed, 26.451s**, final 0.0.4 JAR. Raw saved files retained as `build/m4-acceptance2-serpulo-{64,170}.msav`; all runtime directories are disposable and cleaned. RED, actual failed attempts and exact commands are described in [`reports/final-m4-fa.md`](reports/final-m4-fa.md). One M3 regression passed as `build/m4-m3-regression-result.json`; it verifies preservation, not the desired economy.

### Local stdio API

The runner prepares JVM properties `sc.m4.workspace` and `sc.m4.root=<workspace>/campaigns`, a `.sc-m4-disposable` marker, `campaigns/campaign.id`, and the host fixture's per-campaign directory. These are **not instructions to point the mod at personal saves**. Launchers/fixtures are test-classpath only, not packaged.

```text
sc-m4-start host session-m4            # host already in campaign, binds active sector
sc-m4-start guest session-m4           # different JVM, still in menu
sc-m4-list list1                      # guest emits LIST; relay to host using sc-m4-recv
sc-m4-directory                       # guest shows last host directory after response relay
sc-m4-select choose1 campaign-fixture serpulo 64
sc-m4-open                           # guest only, after relayed validated host GRANT
sc-m4-save                           # saves only this process's owned live sector

SC_M4_FRAME SC4|1|session-m4|choose1|SELECT|campaign-fixture|serpulo|64
SC_M4_FRAME SC4|1|session-m4|choose1|GRANT|campaign-fixture|serpulo|64
```

Relay only strips `SC_M4_FRAME ` and sends unchanged payload prefixed by `sc-m4-recv `. Frames ≤4096 UTF-8 bytes; exact shapes, bounded IDs and ≤256 successful selection IDs. Session is a mixup check, **not authentication**. The console, saves and local filesystem participants are trusted/cooperative. Guest directory is a snapshot, not continuous synchronization. The bounded directory covers the host's active planet and fails on capacity rather than silently truncating. A saved base's eligibility is based on save metadata, not a fabricated desktop `SaveSlot` or complete vanilla launch UI.

### Safety and scope

M4 uses a separate `SectorSessionLauncher` cooperative Java 17 guard: deny network/listen/connect/multicast and subprocesses; permit writes, delete and rename only inside its canonical marked disposable workspace; reject symlink traversal. Legacy M2/M3 guards are unchanged. This is not a hostile-code sandbox. Python only terminates owned children and deletes its own temporary directories. Expected process exit 143 is owned SIGTERM, not clean server shutdown. Settings are per-engine and **not** a shared wallet or synchronized research database.

Lifetime locks plus immutable admission prevent cooperating duplicate writers; they do not secure an untrusted console or a process ignoring locks. Host restart recovery/replay is not implemented: stale lease files fail closed. Atomic save-file replacement is not a multi-file/directory-fsync crash-recovery guarantee; research/global settings are not part of its transaction. The store accepts M4-tagged saves, not arbitrary vanilla save imports. Runtime worlds can only be coordinated if all workers use this API; do not invoke unrelated console load/save commands while owned.

All production classes—including nested session helpers—are packaged; fixtures and seeds are excluded. Metadata and `sc-status` now say **0.0.4**, while `features=false` still means no playable campaign. No GUI, LAN/WAN, co-op player connection, personal campaign import, or full vanilla research/transfer integration is claimed.

## فارسی — خلاصهٔ M4

هر سکتور منابع خودش را نگه می‌دارد؛ کیف پول مشترک و رزروهای M3 هدف بازی نیستند. M4 فهرست مجاز میزبان، انتخاب انحصاری سکتور ذخیره‌شده و ذخیره/بارگذاری واقعی همان سکتور را در stdio محلی ثابت می‌کند. میزبان در Ground Zero با ۱۰۰ مس زنده می‌ماند؛ Frozen Forest پس از ذخیره و جایگزینی worker با ۵۳ مس خودش بارگذاری می‌شود. شبکه، GUI، اتصال co-op و اقتصاد توزیع‌شدهٔ vanilla هنوز تحویل نشده‌اند. گزارش دقیق: `reports/final-m4-fa.md`.

---

The sections below preserve M2/M3 history. Their reservation/debit commands are experiments, not M4 gameplay; historical milestone limitations and artifact hashes refer to those earlier builds.

## Historical M3: actual campaign/core reservation experiment
Three bounded features, tested RED before GREEN (see `reports/final-m3-fa.md`):

1. **Real campaign fixture:** `tests/CampaignLauncher.java`, test-classpath only, calls `Vars.world.loadSector(SectorPresets.groundZero.sector)` with bundled `maps/serpulo/groundZero.msav`. In v160.4 this is **serpulo:170**, **256×256**, with a real core, `state.isCampaign()==true` and advancing tick/updateId. No generic map is relabelled and no campaign flag is assigned. `Vars.logic.reset()` / `play()` work headlessly; the fixture disables waves/game-over for bounded assertions. No desktop Control or external save is needed.
2. **Real host debit:** inert until `sc-m3-start host <session>` binds the current sector and core. `CampaignInventory` posts all operations to the captured engine thread; reads/removes `Items.copper` directly in `core.items`. Only the host catalog determines costs: `reserve-copper=30`, `reserve-large=80`. Each new successful request ID buys one labelled **test reservation**, not a tech unlock; different IDs can reserve again. No production seeding exists. The fixture alone sets host copper **0→100**; direct engine queries prove **100→70**, retry **70→70**, and insufficient funds **70→70**. Session, ID, sector, protocol/type, catalog, shape/price injection, replay conflict and UTF-8 bounds are checked before mutation.
3. **Two actual engines, byte-only relay:** guest requests a fresh reservation; only host response bytes are relayed. Direct host core query becomes **40** before guest response delivery, while guest cache stays **70**. After delivery cache becomes **40**; retry does not debit again. Historical host replies are rejected if stale. Optional two-sector test loads bundled **Frozen Forest, serpulo:64, 200×200** in the guest: both sectors tick, host identity remains 170, and guest real copper stays **0**. Guest cache is observation only, **never copied to a spendable core**. This does not unify all vanilla inventory flows across sectors.

Reproduce from this project root, sequentially (executable scripts, **not pytest**):
```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/build.py
PYTHONDONTWRITEBYTECODE=1 python3 tests/m3_campaign.py --two-sectors --label independent-m3
# Lower-memory variant: guest stays in menu
PYTHONDONTWRITEBYTECODE=1 python3 tests/m3_campaign.py --label independent-m3-menu
```
Use `--stage f1` for one-engine campaign-only testing or `--stage f2` for one-engine real-inventory tests. Each run uses the unchanged cooperative guard, sequential boots, nice 10 / Xmx128m / ActiveProcessorCount=1 and javac96m. An 80-second deadline includes compilation/exchange; cleanup adds at most 4 seconds per owned child (two maximum), below 90 seconds total. RAM available ≥200 MiB / load5 ≤4 are monitored throughout. JSON records commands, hashes, PIDs/exits, direct world/core observations, relayed frames, and sampled per-child RSS.

Evidence: `build/m3-f1-red-*`, `m3-f1-green-*`, `m3-f2-red-*`, `m3-f2-green-*`, `m3-f3-red-*`, `m3-f3-green-*`, `m3-f3-sectors-red-*`, `m3-f3-sectors-green-*`, `m3-acceptance-*`. The first F1 attempt actually loaded Ground Zero but exposed an incorrect test expectation (15 vs actual 170), retained as `m3-f1-attempt1-*`; it is not a load blocker. Old JARs `build/m3-pre-f2.jar` and `build/m3-pre-f3.jar` permit expected failures using `--jar` and `--stage f2` / `f3`. For F1 RED use `--baseline --stage f1`. Do not overwrite evidence labels.

### M3 local console protocol
```text
sc-m3-start host session-m3          # on loaded campaign host; no balance argument
sc-m3-start guest session-m3         # separate JVM, cache initially unknown
sc-m3-request r1 serpulo 170 reserve-copper
sc-m3-recv SC3|1|session-m3|r1|REQ|serpulo|170|reserve-copper
sc-m3-state

SC_M3_FRAME SC3|1|session-m3|r1|REQ|serpulo|170|reserve-copper
SC_M3_FRAME SC3|1|session-m3|r1|RES|serpulo|170|reserve-copper|1|70
```
Same M2 stdin-command / stdout-marker / exact-frame relay structure, but **SC3 is deliberately separate from synthetic SC2**, not wire-compatible. Exactly 8 request fields / 10 response fields, ≤4096 UTF-8 bytes, ≤256 request IDs, no price field. Guest response must match pending session/ID/sector/operation and nondecreasing revision; equal revision requires matching cached count. Host detects replaced core or changed sector and rejects rather than rebinding silently. `sc-m3-state` host reads live items; guest reports `authority=cache-only`.

**Limits:** trusted local console, no authentication or remote transport. Successful retries return the original historical reply, not a current snapshot. Revisions cover these reservations only, **not vanilla mining/spending/imports**; cache can be stale. Rejected requests are not retained as durable transactions. No persistence, crash atomicity or host-restart replay guarantee. No real vanilla research, tech/planet UI, campaign saves, player networking, launch flow, deployment or playable shared economy is claimed. M2 remains explicitly synthetic and is not used to implement M3 balances. One unchanged M2 `--maps` regression passed as `build/m3-m2-regression-result.json`.

## What M2 actually proves
- Same production JAR digest in two distinct, simultaneously alive `ServerLauncher` JVMs, booted sequentially in disposable directories.
- An explicitly activated **synthetic** host ledger starts at 100. A guest starts with an unknown cached balance (`-1`), emits a request for `test-tech`; Python relays the exact engine frame to host stdin. The host's catalog fixes the cost at **30**; there is no guest price field. Only the actual host response is relayed back. Both engine queries then report **70, revision 1, unlocked=true** (the flag refers to `test-tech`, not vanilla research).
- Before response delivery the guest stays unknown; before request delivery the host stays at 100. The controller is not a ledger and never generates successful replies.
- Request retry does not charge twice. A second host-defined synthetic item, `test-tech-2` (10), advances the host revision to 2 and balance to 60, enabling a test with a genuinely old engine response.
- Invalid/unknown/overlong frames, wrong session/version/type/ID, supplied prices, conflicting retries, unexpected replies, revision conflicts and stale replies are rejected without state mutation or engine crash. Queries act as barriers after each negative input.
- Terminating the guest leaves the host responsive. A fresh guest can explicitly replay the two original requests and recover their historical responses without charging the host again. **This is not automatic reconnect or a latest-state snapshot**: replaying an old ID returns that ID's original response, which may be obsolete. Host restart loses all state.
- Optional separate fixture loads distinct **generated maps**, 16×16 stone and 20×16 sand with a core each. Both actual `Vars.state.tick` and `updateId` advance during the exchange with `playing=true`, `campaign=false`, `netActive=false`. These are **not campaign sectors**. No fixture class or world-manipulation code is packaged in the mod.

M1 bootstrap/in-memory domain tests are historical evidence only; they are not the basis for these cross-process claims. See `reports/independent-check.md` and `reports/final-m2-fa.md`.

## Offline build and independent reproduction
Requirements: Python 3.9+, Java/JDK 17 and the existing read-only `/opt/mindustry/server-release.jar`. No installs/downloads. From this project root, run **sequentially**:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/build.py
PYTHONDONTWRITEBYTECODE=1 python3 tests/m2_link.py
PYTHONDONTWRITEBYTECODE=1 python3 tests/m2_link.py --maps --label m2-maps
sha256sum build/shared-campaign-pc.jar
```

These are executable Python test scripts, **not pytest tests**. A successful test requires assertions plus JSON/log artifacts, not merely JVM exit 0. Compiler heap is 96 MiB; each engine uses maximum heap 128 MiB, one active processor and nice 10. The test's deadline starts before compilation: 80 seconds for compilation/startup/exchange, with bounded owned-child cleanup (up to 4 seconds per still-live child; normally at most two). Observed final runs took 26–28 seconds. RAM available ≥200 MiB and load5 ≤4 are checked throughout, with the live PIDs sampled together. Low resources fail the test rather than bypassing guards.

The historical M3 JAR included `SharedCampaignMod`, `StdioLedger`, `CampaignInventory`, and `mod.json`; M4 adds `SectorSessions` (including nested helpers) and `SectorStore`. It is a Mindustry-loadable mod, not a standalone `java -jar` application. M1's `SharedCampaignState` and all fixtures are excluded.

### Evidence
- `build/m2-result.json`, `build/m2-host.log`, `build/m2-guest.log`, `build/m2-guest-restarted.log`: latest default run.
- `build/m2-acceptance-result.json` and matching logs: unchanged final JAR rerun.
- `build/m2-maps-result.json` and matching logs: generated-map run, actual before/after ticks.
- JSON includes exact commands, copied JAR hashes, engine hash, child PIDs/exits, resource samples, input/output events and exact relayed frames.
- `build/m2-red-result.json` and `build/m2-red-*.log`: preserved pre-implementation failure with two real engines and old JAR, **not a missing-artifact failure**. `build/m2-old.jar` preserves that old build. Initial ANSI assertion error is separately labelled `m2-harness-red-*`, not counted as feature RED.
- `build/m2-replay-red-*`: second catalog/revision test failed before its implementation; `build/m2-pre-replay.jar` preserves that build.
- `build/m2-maps-red-*`: map commands absent before the separate fixture existed.
- `build/build-result.json`: current production JAR/engine digests.

Optional RED recheck on this workspace (expected exit **1**, missing M2 command marker; use a new label to preserve original evidence):
```sh
PYTHONDONTWRITEBYTECODE=1 python3 tests/m2_link.py --jar build/m2-old.jar --stage core --label m2-red-recheck
```
Build artifacts are gitignored; repeatable sources and the report remain in the repository. Legacy `tests/smoke.py` and `tests/negative.py` remain bootstrap regression scripts, not new M2 evidence.

## Experimental console protocol
The M2 ledger stays **inert until explicit `sc-m2-start`** (M3 has its separate activation); `sc-status` still says `features=false` for playable campaign features. `init()` checks actual engine build/revision before its v160.4 success marker.

Commands:
```text
sc-m2-start host session-m2      # OR guest, once per engine lifetime
sc-m2-request r1 test-tech      # guest only; no price accepted
sc-m2-recv SC2|1|session-m2|r1|REQ|test-tech
sc-m2-state
```

Frames are compact delimiter-based ASCII (not JSON), one stdout line:
```text
SC_M2_FRAME SC2|1|session-m2|r1|REQ|test-tech
SC_M2_FRAME SC2|1|session-m2|r1|RES|1|70|true
```
Relay strips only `SC_M2_FRAME `, prefixes `sc-m2-recv ` and sends the **unchanged payload** to the other engine. The payload has protocol, version, session, request ID, message type and typed fields. Frame limit is **4096 UTF-8 bytes** (excluding console command/marker/newline); overlong input is rejected before splitting. IDs are `[A-Za-z0-9_-]{1,64}`. Exact field counts are required. Request history is capped at 256 IDs per engine. Guest updates require a pending expected ID, matching session and nondecreasing revision; equal revisions are accepted only if cached values match. A duplicate unsolicited response is rejected, whereas an explicitly re-requested identical response may be applied idempotently. No deserialization, reflection, evaluation, process execution or network code is used in this protocol.

`SC_M2_APPLIED` means the guest actually applied a validated response. `SC_M2_REJECT` never counts as receipt. Negative tests deliberately send invalid/mutated input as fault injection, never a fabricated successful response. The synthetic `unlocked` field tracks `test-tech` only; this protocol is not a general tech-tree replication format.

**Trust boundary:** trusted local console/controller. Session identifiers prevent mixups, not forgery; this transport has no authentication, encryption, remote peer discovery, durable storage, timeout/retry scheduling or generic resynchronization. Do not expose the console as a remote service.

## Guard and remaining work
Historical M1–M3 fixture launchers use the existing unchanged `tests/SmokeLauncher.java` cooperative Java 17 guard. It denies listen/connect/multicast, subprocesses, deletion and writes outside each disposable directory. This is **not a hostile-code sandbox**. No normal `host` command is invoked and no network port is opened. Python only terminates children it owns, then removes its disposable directories; raw stdout/stderr and verdicts remain. Expected engine exit is usually **143 (owned SIGTERM)**, not an asserted clean server shutdown. JLine/`infocmp` and SecurityManager warnings remain visible; PATH is removed only from engine-child environments. No original saves, services or credentials are touched.

Still absent after M4: playable PC campaign, comprehensive live inventory/research synchronization, selection UI, general save migration and crash recovery, real networking, desktop GUI testing. M4 adds only the local disposable admission/save slice above. Mindustry has process-global `Vars.world/state/net`; two isolated processes with generated maps do not solve gameplay integration automatically. No Android or deployment work is included.

## فارسی
M3 اکنون بارگذاری واقعی Ground Zero و کسر مس از core میزبان را ثابت می‌کند؛ Frozen Forest مهمان هم جداگانه tick می‌زند و مس واقعی مهمان صفر می‌ماند. رزرو آزمایشی research نیست و کش مهمان کیف پول نیست. گزارش جدید: `reports/final-m3-fa.md`. توضیح زیر سابقهٔ M2 است.

نسخهٔ ۰.۰.۲ آزمایش محلی stdio میان دو موتور واقعی است، نه کمپین مشترک قابل‌بازی. موجودی ۱۰۰ و فناوری‌های آزمایشی ساختگی‌اند. درخواست مهمان واقعاً به میزبان می‌رسد و پاسخ میزبان کش مهمان را تغییر می‌دهد؛ قیمت فقط در میزبان تعیین می‌شود. تست جداگانه پیشروی tick دو نقشهٔ تولیدشده را ثبت می‌کند، نه سکتورهای کمپین. شبکهٔ راه‌دور، همگام‌سازی research/save واقعی و GUI هنوز وجود ندارند. دستورهای بالا برای بازآزمایی مستقل و `reports/final-m2-fa.md` برای شواهد و محدودیت‌ها هستند.

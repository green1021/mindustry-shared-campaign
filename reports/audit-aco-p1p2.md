# ACO P1/P2 Architecture Audit

Date: 2026-10-06
Scope: `src/sc/SectorHandover.java`, `src/sc/CampaignInventory.java`, `src/sc/SectorStore.java`
(plus the call sites in `SectorSessions.java` / `NetworkCampaign.java` that use them).
Method: static review + a harness driving the **real compiled classes** (`build/audit/src/sc/Audit.java`)
+ the project's own suites (`tests/m3_campaign.py`, `tests/m5_network.py`).

## Verdict

| Area | Status |
|------|--------|
| Atomic `.msav` swap in `SectorStore.save` | **Correct** (unique temp → validate → `force(true)` → `ATOMIC_MOVE`) |
| Atomic transfer in `SectorHandover` | **Broken / not implemented** (not compiled, not called, load path non-atomic, no CRC32) |
| Idempotent resource reservation (`CampaignInventory`) | **Correct within one host process**, but non-durable and unbounded |
| `SectorStore` locking / lease | **TOCTOU race**; asymmetric exception handling |
| Session isolation (network P1) | **Not enforced host-side** in `NetworkCampaign` |
| P1/P2 dual-port architecture | **Stub only** (single port; P2 never opened; UI is a placeholder) |

## Severity summary

### Critical
1. **`SectorHandover.loadCompressed` writes directly to the target** — violates "Never write directly to target `.msav`".
2. **`SectorStore.lease()` is a lock-free, check-free overwrite** — a non-owner can invalidate a live grant (TOCTOU).
3. **Host-side session check missing in `NetworkCampaign.receiveServer`** — session isolation is client-only there.
4. **`SectorHandover` is dead code and excluded from the build** — the "Gzip + CRC32 + atomic transfer" requirement is effectively unimplemented; no `GLOBAL_RESOURCE_REQUEST` op exists.

### High
5. Idempotency cache + `revision` are **in-memory only** → double-debit across a host restart.
6. `SectorHandover.saveCompressed` uses a **fixed `.tmp` name**, **no CRC32**, **no fsync**, **no temp cleanup**.
7. `tests/m5_network.py` **cannot run** (port 6567 vs. guard 41000–41999) and stage `f3` asserts ops that don't exist.

### Medium
8. `coordinator()` propagates unchecked `OverlappingFileLockException`; `acquire()` maps it to `writer-live`.
9. Idempotency maps are capped at 256 with **no eviction/TTL** → permanent lockout.
10. No lock release path; `.lease` persists across restarts with no owner check.
11. `release()` frees at most one grant; `peerRequests` grows unbounded (populated before validation).
12. Durability/atomicity gaps: no directory `fsync`; lease uses non-atomic `Files.writeString/readString`.
13. **Zero `CRC32` usage** anywhere in `src/` (only mentioned in `AGENTS.md`).

### Low / informational
14. `CampaignInventory.savedCampaignServers` is a `public static` mutable `Seq`, populated and read nowhere.
15. `SharedCampaignUI` dual-port fields are placeholders; `replacePlanetDialog` reflectively overwrites `Vars.ui.planet`.
16. `NetworkCampaign.receiveClient` indexes `f[6]/f[7]` without a shape check (AIOOBE instead of a clean reject).

---

## 1. `SectorHandover` — atomic transfers (Gzip + CRC32 + Atomic Move)

```java
public static void saveCompressed(Path source, Path target) throws IOException {
    Path temp = target.resolveSibling(target.getFileName() + ".tmp");   // (a) fixed name
    try (GZIPOutputStream out = new GZIPOutputStream(new BufferedOutputStream(
            Files.newOutputStream(temp)))) {
        Files.copy(source, out);                                        // (b) no temp cleanup on failure
    }
    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                               StandardCopyOption.REPLACE_EXISTING);    // (c) no CRC32 / no fsync
}
public static void loadCompressed(Path source, Path target) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new BufferedInputStream(
            Files.newInputStream(source)))) {
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);    // (d) DIRECT non-atomic write!
    }
}
```

Confirmed by inspection and harness:

- **(a) Fixed temp path.** `target + ".tmp"` is not unique. `Files.newOutputStream` truncates it by default,
  so two concurrent writers to the same target share one temp file. In the harness, 8 concurrent
  `saveCompressed` calls to the same target produced **7/8 `NoSuchFileException`** (one thread moves the temp
  out from under the others). With different sources/timings a partially written temp can be moved into place.
  Compare `SectorStore.save`, which correctly uses `Files.createTempFile(directory, …)`.
- **(b) No `finally { Files.deleteIfExists(temp); }`.** A failed gzip write leaks the temp file
  (`SectorStore.save` cleans up).
- **(c) No CRC32 and no fsync.** `AGENTS.md` requires "write `.tmp` → validate CRC32 → atomic swap"; the
  method performs none. GZIP embeds a CRC32 trailer, but it is only verified on *read*, and only if the
  whole stream is read. There is also no `FileChannel.force(true)` before the move.
- **(d) The receive path is the worst case.** `loadCompressed` writes straight into the live target with
  `REPLACE_EXISTING`. Harness: a valid 32-byte target was reduced to **15 bytes** by a mid-stream
  `EOFException`, i.e. a truncated/!transferred save silently replaced a good one.

**Evidence (real class):**
```
target before load: size=32 bytes
loadCompressed -> THREW java.io.EOFException: Unexpected end of ZLIB input stream
target after failed load: size=15 bytes, content=PRE-EXISTING-VA
=> target opened with REPLACE_EXISTING and clobbered (NOT atomic)
```

**Dead code + not built.** `grep` finds no reference to `SectorHandover` outside itself; `scripts/build.py`
does not compile it. `jar tf build/shared-campaign-pc.jar | grep -c SectorHandover == 0`.
So the actual handover path (`NetworkCampaign`/`SectorSessions`) never compresses, never validates CRC32,
and never performs the atomic receive swap.

**Recommendation:** make `loadCompressed` decompress to a `Files.createTempFile` sibling, validate the
decompressed artifact (CRC32 of the `.msav`, and/or `SaveIO.getMeta` as `SectorStore.save` does),
`force(true)`, then `ATOMIC_MOVE` onto the target in a `try/finally` that deletes the temp. Do the same
(unique temp + CRC32 + fsync) in `saveCompressed`. Add the class to `build.py`.

## 2. `CampaignInventory` — idempotent resource reservation

The named `GLOBAL_RESOURCE_REQUEST` does not exist. The implemented analog is `sc-m3-request` with a
host-owned catalog (`reserve-copper` = 30, `reserve-large` = 80). The core idempotency is **correct**:

- Host keys on `f[3]` (request id) and stores the **full request frame**; a retry with an identical frame
  is required (`old == null || old.equals(frame)`) and returns the cached `replies.get(f[3])` **before**
  any debit. Same id + different frame → `conflict`. No double debit.
- `live()` re-validates the campaign sector and core identity at debit time; the debit is against the real
  host `CoreBuild`.
- Guest is read-only: it updates `cachedRevision/cachedCopper/cachedSector` and never writes `core.items`
  ("Observation only").
- Verified end-to-end by `tests/m3_campaign.py --stage f2` (**passed**): `before=100 → after=70`, retry
  returns the identical response with no second debit, plus 14 negative cases.

Gaps:

- **Non-durable idempotency (High).** `requests`/`replies` are `HashMap`s and `revision` is a field. If the
  host debits `core.items` (persisted in the atomic `.msav`) and then restarts before the guest receives the
  reply, the guest's retry finds no record and is debited **again**. `revision` also resets to 0, so any
  guest holding a higher cached revision will reject every reply as `stale` until the host catches up
  (liveness bug). Idempotency must be persisted (e.g. a `sc-request-log` tag / journal in the same atomic
  save) or reconstructed from the save on load.
- **Unbounded-in-practice cache (Medium).** `require(requests.size() < 256, "capacity")` with no eviction or
  TTL: after 256 distinct ids no new reservation can ever be admitted (retries of existing ids still work).
- **Debit/record not jointly atomic (Low).** The debit, `revision++`, `requests.put`, `replies.put`, and the
  `out(...)` writes are separate steps; a failure between debit and `requests.put` re-opens double-debit for
  that id.
- **Naming/traceability.** `AGENTS.md` names `GLOBAL_RESOURCE_REQUEST`; nothing in the code or tests uses it.
- `savedCampaignServers` (new, uncommitted) is a `public static final Seq<String>` that is never written or
  read; if it is ever used from more than one thread it is an unsynchronised shared structure.

## 3. `SectorStore` race conditions

### 3.1 Lease TOCTOU (Critical) — verified against the real class

```java
void lease(Sector s, String grant) throws IOException {          // never requires/checks writerLock
    Files.writeString(file(s, ".lease"), grant, CREATE, TRUNCATE_EXISTING, WRITE);
}
void checkLease(Sector s, String grant) throws IOException {
    Path p = file(s, ".lease");
    require(Files.isRegularFile(p) && Files.size(p) <= 4096 && Files.readString(p).equals(grant), "lease");
}
```

`lease()` does not acquire the sector's writer lock, does not check whether a live lease already exists, and
is a plain non-atomic `Files.writeString` (truncate + write). Harness on the compiled class:

```
stored lease=A ; owner checks A -> ACCEPTED
rewrote lease=B WITHOUT holding writer lock
owner's original grant A now valid? -> REJECTED (lease)
imposter's grant B now valid?       -> ACCEPTED
```

Consequence in `NetworkCampaign.receiveServer` / `SectorSessions.receive`: admission is guarded only by the
in-memory `owners` map. After a host restart (or a dropped/reconnected peer) `owners` is empty while the
on-disk `.lease` still names a guest; the host re-grants the same sector and `lease()` silently overwrites
the live guest's grant. The OS writer lock (`acquire`) still prevents two *simultaneous* `open()` loads, so
this is not instant file corruption — but the running guest's next `sc-m5-save`/`sc-m4-save` fails
`checkLease`, and the "owner" identity on disk no longer matches reality. The lease must be the lock-guarded
source of truth: take the target's writer lock (or a dedicated admission lock) around read-check-write, and
refuse to overwrite an existing non-expired lease.

### 3.2 Asymmetric lock-exception handling (Medium) — verified

```java
void coordinator() throws IOException {
    coordinatorChannel = FileChannel.open(p, CREATE, WRITE);
    coordinatorLock = coordinatorChannel.tryLock();          // no OverlappingFileLockException catch
    if (coordinatorLock == null) { … throw new IllegalArgumentException("coordinator-owned"); }
}
void acquire(Sector s) throws IOException {
    try { writerLock = writerChannel.tryLock(); }
    catch (OverlappingFileLockException ex) { writerChannel.close(); throw new IllegalArgumentException("writer-live"); }
    …
}
```

`FileLock` is JVM-wide, and `FileChannel.tryLock()` throws `OverlappingFileLockException` when the lock is
held by the same JVM. Harness:

```
first  coordinator() -> OK (lock held)
second coordinator() -> THREW java.nio.channels.OverlappingFileLockException  (unchecked=true)
```

So a second coordinator in the same JVM becomes a raw runtime exception instead of the advertised
`coordinator-owned` reject (in `NetworkCampaign.dispatch` it surfaces as `REJECT reason=null` + stack trace).
This is reachable in one process because **both `SectorSessions` and `NetworkCampaign` construct their own
`new SectorStore()`**: running `sc-m4-start host` then `sc-m5-start host` collides on `coordinator.lock`.

### 3.3 Other SectorStore observations

- **No release path.** `coordinatorLock`/`writerLock` and their channels are never closed; `SectorStore` has
  no `close()`. This is the documented fail-closed intent ("keep lock until process termination"), but it
  means a JVM can never re-activate a session and there is no cooperative cross-process release.
- **`eligible()`/`lease()`/`open()` are not one atomic admission.** `open()` runs `checkLease →
  eligible → acquire`; the `.msav` can change between `eligible` and `acquire`, and `lease()` is outside the
  lock entirely. Correctness currently leans on the OS writer lock, not on the store's own protocol.
- **Durability.** `save()` fsyncs the temp file (`c.force(true)`) but never fsyncs the parent directory, so
  the atomic rename is not power-loss durable. `meta/eligible/listing` read `.msav` without a lock — safe
  because writes are atomic renames, so readers see old-or-new, not torn.
- **`save()` is otherwise sound**: `Files.createTempFile` (unique), `SaveIO.getMeta` validation, `force(true)`,
  `ATOMIC_MOVE | REPLACE_EXISTING`, and `finally { Files.deleteIfExists(temp); }`. The m4 symlink-negative
  test also exercises `noLinks(destination)` immediately before the move.

## 4. Session isolation & P1/P2 architecture

- **`NetworkCampaign` does not validate the session on receive.** The only `equals(session)` in
  `NetworkCampaign` is on the guest **send** path (`sc-m5-send`). `receiveServer` splits the frame and checks
  `id(f[3])`, `hostLive()`, and `f[5].equals(campaign)` — but never `f[2].equals(session)`. `receiveClient`
  keys off `pending` only. A raw peer can therefore act under any session string. (`StdioLedger`,
  `CampaignInventory`, and `SectorSessions` *do* check `f[2]` on receive; `NetworkCampaign` is the outlier.)
- **Unimplemented network transactions.** `NetworkCampaign.receiveServer` handles only `PING`, `LIST`,
  `SELECT`/`LAUNCH`. `tests/m5_network.py` stage `f3` sends `STATUS`, `TRANSFER|copper|15`, and
  `RESEARCH|…` and expects `TRANSFERRED`/`UNLOCKED`/`REJECT|locked-prerequisite` — none of which exist, so
  the frame is silently ignored and the test times out.
- **The m5 suite cannot run at all.** Both the host bind and `NetworkLauncher`'s `SecurityManager` reject
  any port outside 41000–41999, while the command uses `System.getProperty("sc.test.port","6567")` and the
  test now sets `-Dsc.test.port=6567`. Observed:
  `SC_M5_REJECT reason=port`, `passed=false`, `error="host: missing 'SC_M5_LISTEN '"`.
- **Dual-port is a stub.** `NetworkCampaign` opens a single port; P2 (= P1+1) is never hosted. `SharedCampaignUI`
  collects P1/P2 into text fields but only `Log.info`s them; `replacePlanetDialog` reflectively overwrites
  `Vars.ui.planet`. It never calls `clearChildren()`, which complies with the UI rule.
- **`release()` correctness.** It adds every received request id to `peerRequests` **before** validating the
  frame type, so it grows without bound per connection; on disconnect it releases only the **first** id that
  appears in `owners`, so a connection owning more than one sector leaks the rest. `requests`/`replies` in
  `NetworkCampaign` also have no capacity guard (unlike `SectorSessions`).

## 5. Positive findings

- `SectorStore.save` genuinely implements temp → validate → `force(true)` → `ATOMIC_MOVE` → cleanup.
- Host debit is idempotent per request id with full-frame equality; guest is strictly read-only.
- `m3` negatives (14) pass: protocol/version/session/identifier/sector/shape/catalog/conflict/funds are all
  rejected without mutating host copper.

## 6. Verification log

```
# Build (SectorHandover NOT included)
python3 scripts/build.py                      -> exit 0
jar tf build/shared-campaign-pc.jar | grep -c SectorHandover   -> 0

# Real-class harness (package sc, reaches package-private members)
javac --release 17 -cp $ENGINE:build/classes -d build/audit/classes \
      build/audit/src/sc/Audit.java src/sc/SectorHandover.java
cd build/audit/ws && java -Dsc.m4.workspace=… -Dsc.m4.root=…/campaigns \
      -cp build/audit/classes:build/classes:$ENGINE sc.Audit
  -> coordinator() 2nd call: OverlappingFileLockException (unchecked)
  -> acquire()   2nd call: IllegalArgumentException "writer-live"
  -> lease() overwrite by non-owner: owner's grant REJECTED, imposter's ACCEPTED
  -> loadCompressed mid-stream failure: target 32 -> 15 bytes (clobbered)
  -> 8 concurrent saveCompressed: 7 errors (NoSuchFileException), fixed ".tmp" name

# Project suites
python3 tests/m3_campaign.py --stage f2 --label audit-m3-f2   -> exit 0 (idempotency verified, 14 negatives)
python3 tests/m5_network.py  --stage f1 --label audit-m5-f1   -> exit 1 (SC_M5_REJECT reason=port)
```

Harness source (git-ignored `build/` tree): `build/audit/src/sc/Audit.java`.

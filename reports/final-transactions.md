# P1 Transactional Persistence & Atomic Handover

Date: 2026-10-06
Scope: `src/sc/SectorHandover.java` (rewritten), `src/sc/GlobalLedger.java` (new),
`src/sc/CampaignInventory.java` (host path wired to the ledger), `scripts/build.py`.
Follows `AGENTS.md` (P1 owns the Global Bank; atomic temp → CRC32 → swap; idempotent transactions).

## 1. Atomic handover — `SectorHandover`

Old behaviour: a fixed `target + ".tmp"` name, no CRC32, no fsync, no cleanup, and `loadCompressed`
streamed straight into the live target with `REPLACE_EXISTING` (a mid-stream failure clobbered a good save).

Now both directions follow one protocol:

1. Stream into a **uniquely created** sibling `Files.createTempFile(dir, name, ".tmp")`.
2. Frame the uncompressed payload with `[int MAGIC][int VERSION][long length][int crc32][gzip]`.
3. `FileChannel.force(true)` the temp, re-read it and verify length + CRC32
   (`saveCompressed` self-validates the artifact before it can become the target), then
   `Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)`, then fsync the parent directory.
4. `finally { if (!committed) Files.deleteIfExists(temp); }` — the live target is untouched on any failure.

The class is now compiled into the production jar (previously excluded from `build.py`).

## 2. Durable idempotency — `GlobalLedger` (WAL + Snapshot)

An append-only journal per session directory (`<sc.m3.ledger>/<session>/`):

- `global.wal` — each committed transaction is a `[int len][int crc32][payload]` frame, `force(true)`-ed
  **before** the caller mutates live state. Payload = `sequence`, `requestId`, `requestFrame`, `replyFrame`,
  `revision`, `balance`.
- `global.snapshot` — CRC-framed full state, written to a temp file, fsynced and swapped with `ATOMIC_MOVE`.
  The WAL is truncated only after the snapshot is durable; the snapshot carries its own high-water sequence
  so a crash between the two re-applies nothing.
- Recovery loads the snapshot, replays the WAL, and **truncates a torn tail** (partial or CRC-bad final
  record) instead of failing the store. A corrupt snapshot fails closed.

`CampaignInventory` opens the ledger on `sc-m3-start host` (in-memory when `-Dsc.m3.ledger` is unset, so the
existing stdio path is unchanged). A host request now:

```
find(requestId)  -> committed?  return stored reply  (no second debit)
validate catalog/funds against the live core
commit(requestId, frame, reply, revision+1, balance-after)   // WAL + fsync
core.items.remove(...)
```

Because the durable record precedes the live debit, a restart can at worst under-charge in a microscopic
crash window, but can **never double-debit**, and the revision continues from the persisted value instead of
resetting to 0 (which previously made every cached guest reject replies as `stale`).

## 3. Verification

```
python3 scripts/build.py                                  -> exit 0
  jar_sha256 c914c65b26c20fa36d01d317d1228d7d11a0bf4a86a438e2d63a1d63774a68f0
  jar contains sc/GlobalLedger.class + sc/SectorHandover.class
python3 tests/m6_transactions.py --label m6-v2             -> exit 0, 27 checks
python3 tests/m3_campaign.py --stage f4 --label m3-f4-v2   -> exit 0  (f1 + f2 + durable restart)
python3 tests/m3_campaign.py --stage f3 --label m3-f3-check -> exit 0  (host+guest path)
```

Real-engine restart evidence (`m3 --stage f4`: host committed r1, was killed, restarted, retried):

```
SC_M3_LEDGER durable=true records=1 revision=1 balance=70 replayed=1 truncatedBytes=0
retry r1  -> SC3|...|reserve-copper|1|70  (copper stays 70, no SC_M3_DEBIT emitted)
new   r2  -> SC3|...|reserve-copper|2|40  (revision did not reset)
```

Harness coverage (`tests/TransactionTest.java`): exact roundtrip; corrupted/truncated/bad-magic transfers
rejected with the live target intact; 8 concurrent `saveCompressed` writers to one target; no `.tmp` leaks;
WAL replay after an un-closed (crashed) writer; idempotent re-commit; conflict rejection; monotonic revision;
snapshot-only recovery; post-snapshot WAL replay; torn-tail truncation; CRC-bad tail drop; corrupt-snapshot
fail-closed.

## 4. Limitations

- The network transfer operation that would call `SectorHandover` (`NetworkCampaign`/`SectorSessions` save
  handover) is still unimplemented; the component is built and tested but not yet invoked.
- The live core remains the spendable authority, so the ledger journals idempotency/revision rather than
  owning the balance. Making the Global Bank itself ledger-authoritative is a larger M3 semantics change.
- `tests/m5_network.py` still cannot run (port 6567 vs. the 41000–41999 loopback guard) and asserts
  `STATUS`/`TRANSFER`/`RESEARCH` ops that do not exist — unchanged by this work.
- `tests/smoke.py` asserts `SC_STATUS version=0.0.2` while `HEAD` prints `0.0.4`; this pre-existing stale
  assertion is unrelated to these changes and was left untouched.

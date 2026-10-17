# Defect: unsolicited GLOBAL_STATE_SYNC desyncs the controller's one-frame round trip

**Date:** 2026-10-17
**Status:** FIXED and covered by `tests.AcoBroadcastDesyncTest` (4/4 green)
**Severity:** High — any client that issues a request after a broadcast-generating
request reads the wrong frame and misinterprets it as its own response.

## What happens

Several `AcoServer` handlers push an unsolicited `GLOBAL_STATE_SYNC` frame to
*every* connected session, including the one that just made the request
(`AcoServer.handleResourceRequest`, `handleResearchRequest`,
`handleSectorCompletion`). The requester therefore receives **two** frames per
request: its own response, then the broadcast.

`AcoController.roundTrip` reads exactly one frame and returns it. After a
broadcast-generating request, the next call to `roundTrip` consumes the *queued
broadcast* as if it were that request's response. Every subsequent response is
off by one.

## Reproduction

`build/probe/SyncDesyncProbe.java`, against a live `AcoController` host on
localhost with a real TCP client:

```
connect=true
R1 (expect RESPONSE op-1|true|...): op-1|true|null|1|100
R2 (expect LOCKED|FAIL response, but broadcast may be queued): {"campaignId":"default","globalRevision":1,...}
R3: LOCKED|FAIL|0|
```

R1 is correct. Its handler then broadcast `GLOBAL_STATE_SYNC`. R2 asked for a new
sector-completion response (`SECTOR_COMPLETION_EVENT`) but was handed the queued
JSON snapshot of the campaign state instead. The real R2 response only arrived at
R3. A client parsing R2 as a completion response reads a JSON blob where it
expects `sector|status|...`.

## Why it matters

This is exactly the path the UI/controller seam uses. A player who transfers
resources and then claims a sector would act on a misparsed frame. The failure is
silent: both frame types decode cleanly, so nothing throws.

## Fix

`AcoController.roundTrip` now takes the set of response types it will accept and
skips unsolicited broadcast frames while waiting for the response to its own
request, bounded by `MAX_SKIPPED_FRAMES` so a misbehaving peer cannot hang the
client. Skipped sync frames are surfaced via `getSkippedSyncFrames()` rather than
silently dropped.

Two details the first attempt got wrong, both caught by re-running the probe:

- The response id is **not** always the request id. `SECTOR_CLAIM_REQUEST` (14) is
  answered with `SECTOR_CLAIM_RESULT` (15), and a join is answered with either
  `SECTOR_HOST_CHANGED` (17) on success or `SECTOR_MIGRATION_RESULT` (12) on
  refusal. The first version compared against the request id and rejected every
  legitimate response as "unexpected".
- `SECTOR_COMPLETION_EVENT` (26) is both request and response, so a completion
  round trip legitimately sees its own type back.

Fixed probe output (R2 now gets its own response, not the queued broadcast):

```
connect=true
R1 (expect RESPONSE op-1|true|...): op-1|true|null|1|100
R2 (expect LOCKED|FAIL response, but broadcast may be queued): LOCKED|FAIL|0|
R3: LOCKED|FAIL|0|
```

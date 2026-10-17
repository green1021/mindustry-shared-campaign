# Defect: sector completion persisted to the process CWD, not the campaign storage dir

**Date:** 2026-10-17
**Status:** FIXED and covered by `tests.AcoPersistenceLocationTest` (2/2 green)
**Severity:** High (latent) — the authority would write campaign state to one
directory and recover it from another, so a completed sector could vanish on
restart.

## What happens

`AcoServer` built its own persistence object rooted at the process working
directory:

```java
new CampaignPersistence(java.nio.file.Paths.get(".")).save(globalState);   // AcoServer.java:171
```

at four sites (the completion save plus three `GLOBAL_STATE_SYNC` serializations).
The controller, meanwhile, constructs the authority with
`new CampaignPersistence(storageDir)` and recovers from there. On a real deployment
the two paths are unrelated: the game process CWD is not the campaign data
directory. A successful completion was written to `<CWD>/campaign.json` while the
authority reloaded `<storageDir>/campaign.json`.

## Reproduction

`build/probe/CwdPersistProbe.java`, run from a scratch (empty) working directory so
the leak is visible. A fresh campaign unlocks nothing, so `craters` is seeded as
unlocked to reach `handleSectorCompletion`'s persist branch:

```
connect=true
storageDir=/tmp/cwd-probe-host-9386704481328515946
cwd=/tmp/cwd-probe-run
cwd/campaign.json (before)=false
completion=craters|SUCCESS|1|
cwd/campaign.json (after)=true      <- leaked into the CWD
storageDir/campaign.json (after)=true
```

Before the fix the CWD gained a fresh `campaign.json` written from the in-memory
state; the configured storage directory kept its pre-seed contents
(`globalRevision:0`). The regression test was confirmed to fail against the
pre-fix code for both reasons: the seeded file still read `"globalRevision":0`, and
a new file appeared in the CWD.

## Why it matters

The authority's whole value is being the single source of truth for progression.
If the completion write lands somewhere the recovery path never reads, then a
sector a player completed comes back as incomplete after a restart — silently, with
a `SUCCESS` response at the time. It is latent today only because a fresh campaign
unlocks no sectors, so the persist branch is unreachable until a campaign has
progressed.

## Fix

`CampaignAuthority` exposes its `CampaignPersistence`; `AcoServer` serializes and
saves through it (`broadcastStateSync()`, `authority.getPersistence().save(...)`),
so every write and every recovery use the same configured directory. The
completion save is now wrapped so a disk failure is logged (`ACO_COMPLETION_PERSIST_FAILED`)
rather than thrown out of the handler.

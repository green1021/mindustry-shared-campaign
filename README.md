# Mindustry Shared Campaign PC

Simultaneous multi-sector co-op for Mindustry v160.4 (PC), coordinated by an ACO
control plane (`src/sc/aco/`).

## Status

**In active development. Not playable yet.** The ACO control plane is implemented
and wired into the mod, and its claim/join/release lifecycle is verified headless
against a live localhost authority. The production `SectorRuntimeAdapter`'s two
halves — real sector world loading and a real P2 `NetServer` bind — are verified
headless; real desktop-client multiplayer alone remains **ENVIRONMENT-BLOCKED** on
this build host (no display). See `reports/status-report.md` for the honest
subsystem matrix.

Verified headless today (36/36 canonical assertions, `-ea` enforced):

- ACO frame codec, ownership registry, global bank, transaction manager.
- ACO wired into mod init and the UI action seam (`tests.AcoWiringTest`).
- Claim → join → release over real TCP against a live authority
  (`tests.AcoVerticalSliceTest`), with a stubbed game runtime.
- Real sector loading: `Vars.world.loadSector` builds a 256×256 generated world in
  a headless runtime (`tests.SectorLoadTest`).
- Real P2 host lifecycle: the production `SectorRuntimeAdapter` binds a listening
  socket and releases it on stop (`tests.P2ServerBindTest`).
- Authoritative sector entry: production `SectorStore.open`/`save` load and write
  real `.msav` worlds (`tests/m4_sector_session.py --stage f2`; separate harness,
  not part of the canonical runner).
- Response framing under unsolicited broadcasts, and campaign persistence landing
  in the configured storage directory (`tests.AcoBroadcastDesyncTest`,
  `tests.AcoPersistenceLocationTest`).

Not verified (never claim otherwise):

- Any real desktop-client multiplayer session.
- Remote multiplayer over an authenticated protocol (the M4 sector-entry path is
  local stdio at a trusted console).

## Implemented

- ACO control plane: ownership registry, global bank + transaction manager,
  campaign persistence and recovery, migration protocol.
- Mod wiring: `SharedCampaignMod.init()` initializes ACO and binds the UI seam;
  `sc-status`, `sc-aco`, `sc-aco-host`, `sc-aco-connect` server commands.
- UI actions ("Host Campaign", "Connect") dispatch through `sc.CampaignActions`.

## Control-plane commands

| Command | Purpose |
|---|---|
| `sc-status` | Mod + ACO status banner |
| `sc-aco` | ACO status only |
| `sc-aco-host <syncPort> <sectorPort>` | Start campaign authority + sector host |
| `sc-aco-connect <host> [port]` | Join a campaign authority |

## Build & test

```sh
python3 scripts/build.py            # build/shared-campaign-pc.jar + sha256
python3 scripts/run_canonical_tests.py   # canonical suite (enforces -ea)
python3 tests/smoke.py              # load the mod into a real server JAR
python3 tests/negative.py           # fixture must fail: proves the smoke check bites
```

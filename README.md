# Mindustry Shared Campaign PC

Simultaneous multi-sector co-op for Mindustry v160.4 (PC), coordinated by an ACO
control plane (`src/sc/aco/`).

## Status

**In active development. Not playable yet.** The ACO control plane is implemented
and wired into the mod, and its claim/join/release lifecycle is verified headless
against a live localhost authority. A real P2 sector host and real desktop-client
multiplayer are **ENVIRONMENT-BLOCKED** on the current headless build host (no
display). See `reports/status-report.md` for the honest subsystem matrix.

Verified headless today (26/26 canonical assertions, `-ea` enforced):

- ACO frame codec, ownership registry, global bank, transaction manager.
- ACO wired into mod init and the UI action seam (`tests.AcoWiringTest`).
- Claim → join → release over real TCP against a live authority
  (`tests.AcoVerticalSliceTest`), with a stubbed game runtime.

Not verified (never claim otherwise):

- Real P2 sector hosting — needs a live Mindustry runtime (`Vars.net.host`).
- Any real desktop-client multiplayer session.

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

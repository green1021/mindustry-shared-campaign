# Shared Campaign (PC) — working notes for AI assistants

Distributed co-op campaign mod for **Mindustry v160.4 (PC)**: several players, each hosting their own sector with a real Mindustry `NetServer`, sharing one logical campaign (tech tree, global bank, sector ownership, completion/progression) coordinated by a single **Campaign Authority** (P1). Design docs: `DESIGN_DISTRIBUTED_CAMPAIGN.md`, `DESIGN_PHASE*.md`.

## State of play (as of 2026-10-16)
- The ACO control plane (`src/sc/aco/`) is implemented and the 15-test canonical suite passed as of `BASELINE_BUILD_REPORT.md` (commit `801f968`, tag `v0.9.0-phase9a`).
- **Critical gap:** `sc.aco` is not yet wired into the mod entry point. `src/sc/SharedCampaignMod.java` does not initialize ACO, and the UI actions in `src/sc/SharedCampaignUI.java` are placeholder stubs. Wiring this is the current milestone.
- Phase 9A: server-side vertical slice verified; real desktop-client E2E is ENVIRONMENT-BLOCKED on headless machines (no display). Never claim it as verified.
- Repo culture: evidence-based, brutally honest reports. VERIFIED vs ENVIRONMENT-BLOCKED vs UNTESTED must never be blurred.

## This machine (VPS)
- OpenJDK 17.0.20.1 (`javac 17.0.20.1`), Python 3.14. Headless — never attempt to launch the desktop client.
- Engines: `/opt/mindustry/server-release.jar` (v160.4, sha256 `0dbd3275402ff6df8367620a47e0b24e4d4547332b99af7c434796520ca484c2`), `/opt/mindustry-desktop/Mindustry.jar` (full desktop jar with vanilla assets).
- Shared production VPS, ~2 GB RAM total. Follow the repo's resource discipline: `nice -n 10`, `-J-Xmx96m -J-XX:ActiveProcessorCount=1` for javac/jar, one engine JVM at a time, respect `scripts/resources.py` RAM guard (fails under 200 MiB free).
- Credentials: never read `/root/.hermes`, `/root/.ssh`, `/root/.git-credentials`, `~/.claude`. Never print secrets.

## Build & test
- Build: `python3 scripts/build.py` → `build/shared-campaign-pc.jar` + `build/build-result.json` (prints engine + jar sha256).
- Tests: plain Java `main()` runners under `tests/` (compile against `build/classes` + engine, run one at a time) plus Python harnesses `tests/m1_red.py` … `tests/m5_network.py`, `smoke.py`, `negative.py`. The canonical suite is the 15 tests listed in `BASELINE_BUILD_REPORT.md`.
- If no canonical runner script exists yet, create `scripts/run_canonical_tests.py` (compile + run + PASS/FAIL summary, low-resource conventions) and keep it authoritative.

## Rules
1. Evidence over claims. No PASS without executed command output. No fake or assumed results — ever.
2. Work only inside this repository. No sudo, no systemd, no apt, no touching other services on this box.
3. Keep it green: run build + affected tests before every commit.
4. Commit style: `feat(aco):`, `fix(...)`, `test(...)`, `docs(...)`, `chore(...)`. Small commits. Push `origin main` only when green. Never force-push.
5. Docs must match reality after each milestone (README / status / reports).

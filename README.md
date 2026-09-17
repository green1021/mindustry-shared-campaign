# Mindustry Shared Campaign (PC)

**Experimental, not yet a working shared-campaign mod.** Targets official Mindustry v160.4 / Java 17. Android work is deferred.

## Intended gameplay
Two desktop players install the mod. The guest can browse the host campaign and select an eligible sector independently while the host stays in another sector. Campaign progress and research remain host-authoritative; resources retain campaign/sector semantics rather than being duplicated into independent wallets. Returning to the same sector should allow normal co-op.

## Current state
Version **0.0.1 is a bootstrap-only PC Java mod**, executed successfully by the actual headless Mindustry v160.4 engine. This verifies mod loading, not desktop rendering or gameplay. There is no playable shared-campaign build and no verified independent-sector multiplayer.

Implemented scope (three items):
1. Native `sc.SharedCampaignMod` bootstrap and server-only `sc-status` diagnostic command.
2. Bounded real-engine smoke test, including an obsolete-marker negative fixture.
3. Build/test documentation and execution report.

`init()` logs `SC_PC_INIT_OK engine=160.4` only when the actual `Version.build == 160` and `Version.revision == 4`. Otherwise it emits `SC_PC_VERSION_MISMATCH` without the success marker; metadata also requires at least 160.4. `sc-status` reports mod version, actual engine version, and **`features=false`**. No GUI is added.

## Offline build and test (PC / Linux)
Requirements: Python 3.9+, Java/JDK 17, and the existing read-only `/opt/mindustry/server-release.jar` (v160.4). No downloads or installs.

From the project root, run sequentially:
```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/build.py
PYTHONDONTWRITEBYTECODE=1 python3 tests/smoke.py
PYTHONDONTWRITEBYTECODE=1 python3 tests/negative.py
sha256sum build/shared-campaign-pc.jar
```

Output: `build/shared-campaign-pc.jar`, containing only `sc/SharedCampaignMod.class` (Java 17) and `mod.json`; no engine assets, dependencies, tests, or fixtures. The build uses `javac --release 17` then `jar`.

The smoke test runs the actual engine through `tests/SmokeLauncher.java`, with disposable `build/smoke-*` config/home/temp directories. It denies network listening/connections/multicast, subprocesses, all deletion, and writes outside that disposable directory. This is a cooperative Java 17 test guard, **not a hostile-code sandbox**. No hosted game or listening port is started. Python removes the disposable directory afterward; logs/results remain in `build/`.

JVMs run sequentially at nice 10 with one active processor: javac/jar heap 96 MiB, engine heap 160 MiB. Resource checks require available RAM ≥200 MiB and five-minute load ≤4, and monitor children while running. Engine timeout is 30 seconds; compiler timeout is 20 seconds in smoke. If checks fail, stop and retry later rather than bypassing them.

The test removes PATH only from the engine child's environment and uses absolute launcher paths plus JLine's dumb terminal settings. This avoids executable discovery failing under the unchanged subprocess guard. A denied `infocmp` attempt and Java SecurityManager deprecation warnings may still appear; neither is suppressed. ANSI color codes are stripped only for assertions. The exact real init marker, one-mod loader evidence, network-guard marker, command response, and clean exit are all required.

The negative test compiles a separate obsolete-marker fixture without changing the production JAR. It succeeds only when the engine exits 0 but smoke exits 1 specifically with `Real mod init/version marker missing`. A missing artifact alone is not sufficient negative evidence.

Evidence: `build/build-result.json`, `build/smoke-result.json`, `build/smoke.log`, `build/negative-result.json`, `build/negative.log`, and `reports/final-fa.md`.

## Not implemented / not validated
Independent simultaneous sectors, host-authoritative campaign/research/resource synchronization, sector selection UI, persistence, reconnect, two-player networking, and desktop GUI/rendering are **not implemented or validated**. Android is deferred. No personal save is used or modified. This milestone proves bootstrap only, not the intended gameplay.

## Engineering constraints
Mindustry v160.4 has process-global `Vars.world`, `Vars.state` and `Vars.net`. Its normal `PlanetDialog.show()` refuses clients. Simply bypassing that UI restriction does not enable multiple simultaneous worlds or shared persistent progression. A future gameplay milestone must test world independence and host-authoritative synchronization without modifying a player's original campaign saves; the current bootstrap milestone does not test either.

Save files, credentials, runtime state and engine binaries must not be committed. All experiments use disposable data directories. No production server deployment is part of this repository initialization.

## References
- https://github.com/Anuken/Mindustry/releases/tag/v160.4
- https://github.com/Anuken/Mindustry/blob/v160.4/core/src/mindustry/Vars.java
- https://github.com/Anuken/Mindustry/blob/v160.4/core/src/mindustry/ui/dialogs/PlanetDialog.java

## فارسی
هدف: بازی هم‌زمان دو نفر روی PC در سکتورهای مستقل، با کمپین و فناوری مشترک میزبان. این پروژه هنوز نسخهٔ قابل‌بازی ندارد و موفقیت آن با تست واقعی سنجیده می‌شود؛ نه صرفاً ساخت JAR. اندروید فعلاً خارج از دامنه است.

وضعیت کنونی: بوت‌استرپ PC نسخهٔ 0.0.1 فقط بوت‌استرپ است. JAR توسط موتور واقعی v160.4 بدون شبکه بارگذاری و اجرا شد و دستور `sc-status` با `features=false` کار می‌کند. هیچ قابلیت کمپین مشترک پیاده‌سازی یا اعتبارسنجی نشده است.

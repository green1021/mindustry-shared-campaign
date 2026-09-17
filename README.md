# Mindustry Shared Campaign (PC)

**Experimental, not yet a working shared-campaign mod.** Targets official Mindustry v160.4 / Java 17. Android work is deferred.

## Intended gameplay
Two desktop players install the mod. The guest can browse the host campaign and select an eligible sector independently while the host stays in another sector. Campaign progress and research remain host-authoritative; resources retain campaign/sector semantics rather than being duplicated into independent wallets. Returning to the same sector should allow normal co-op.

## Current state
Architecture and first executable feasibility slice are in development. There is no released playable build and no verified independent-sector multiplayer yet. A diagnostics screen, two arbitrary maps, or an offline ledger test alone does not satisfy the gameplay goal.

## Engineering constraints
Mindustry v160.4 has process-global `Vars.world`, `Vars.state` and `Vars.net`. Its normal `PlanetDialog.show()` refuses clients. Simply bypassing that UI restriction does not enable multiple simultaneous worlds or shared persistent progression. The first milestone must test world independence and host-authoritative synchronization without modifying a player's original campaign saves.

Save files, credentials, runtime state and engine binaries must not be committed. All experiments use disposable data directories. No production server deployment is part of this repository initialization.

## References
- https://github.com/Anuken/Mindustry/releases/tag/v160.4
- https://github.com/Anuken/Mindustry/blob/v160.4/core/src/mindustry/Vars.java
- https://github.com/Anuken/Mindustry/blob/v160.4/core/src/mindustry/ui/dialogs/PlanetDialog.java

## فارسی
هدف: بازی هم‌زمان دو نفر روی PC در سکتورهای مستقل، با کمپین و فناوری مشترک میزبان. این پروژه هنوز نسخهٔ قابل‌بازی ندارد و موفقیت آن با تست واقعی سنجیده می‌شود؛ نه صرفاً ساخت JAR. اندروید فعلاً خارج از دامنه است.

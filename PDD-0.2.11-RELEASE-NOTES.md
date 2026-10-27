# PDD 0.2.11-debug — Rebuilt collision observation source

**Status:** ZIP source prepared; Sable 2.0.6 Java bytecode analyzed, pure-Java parser/queue checks performed. NeoForge/Gradle full build and **in-game collision reception not yet verified**. Do not present this as game-tested.

## Changes from 0.2.1
- Fix nested `Boolean.getBoolean(System.getProperty(...))` debug flag bug from latest GitHub 0.2.1.
- Observe Sable native `Rapier3D.clearCollisions()` result (record width 15 doubles) via required `@ModifyExpressionValue` hook; never alter original array.
- **Also** observe Sable existing concrete Fragile/Belt `sable$onCollision` callbacks via required `@Inject(RETURN)` hook; preserve original response.
- Default ON, bounded queues, per-impact sample lines and every 100-tick native/callback summaries.
- No extra dependency, no world damage or changes. No synthetic collision data mislabeled as live input.

## Source basis
- Latest GitHub `main` observed at `85c7ca305396f95fb855c23aa00b79ce1ef54da9` (2026-10-27).
- Uploaded Sable 2.0.6, Create 6.0.10, Aeronautics 1.3.2 JARs and draft 3.3.1.
- Archived gameplay logs showed 0.2.1 `debugCollisions=false`, Sable loaded, and no proven Sable→PDD live collision sample.
- Prior 0.2.11 ZIP used only the native feed; rebuilt version retains **both** native and existing callback observation for two independent signals.

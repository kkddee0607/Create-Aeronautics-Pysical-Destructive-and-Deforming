# PDD 0.2.11 — Actual Sable collision observation test (mobile first)

## Objective and evidence basis
Minecraft Java 1.21.1, NeoForge 21.1.265 (observed in old gameplay logs), Create 6.0.10, Aeronautics bundled 1.3.2, Sable 2.0.6. Previous `latestlog(1).txt` showed `PDD 0.2.1-debug debugCollisions=false` while Sable loaded and the server started. The other `latestlog-1(1).txt` was 0.2.0-debug and explicitly marked Sable callback NOT_IMPLEMENTED. No pre-existing real collision sample was confirmed in those logs.

## Actual wired data paths
1. Sable `RapierPhysicsPipeline.postPhysicsTicks()` -> `processCollisionEffects()` -> `Rapier3D.clearCollisions(sceneHandle)` -> native `double[]` -> PDD `RapierCollisionTelemetryMixin` -> `NativeCollisionTelemetry.capture()` -> main server tick -> `[PDD-COLLISION]`. This is a **native collision effects feed**, not automatically proof that a `BlockSubLevelCollisionCallback` fired. It can include callback-free collider pairs if Sable exports them in this feed. The Sable 2.0.6 Java bytecode reads records in 15-double units; field meanings remain raw.
2. Sable concrete `FragileBlockCallback` or `BeltBlockCallback.sable$onCollision()` RETURN -> PDD `SableCollisionBridgeMixin` -> `SableContactQueue.offer()` -> main server tick -> `[PDD-SABLE-CALLBACK]`. This path proves *only those callback methods actually invoked*, not all ordinary blocks.
3. Both hooks are declared required in `pdd.mixins.json` to make a failed injection fail visibly. They preserve the original array and original return values; damage and mutation remain disabled. `pdd.debugCollisions` defaults ON without any launcher argument. Optional `-Dpdd.debugCollisions=false` disables telemetry.

## Mobile GitHub steps
1. Upload **one** ZIP under GitHub repo `incoming/`. The Apply PDD ZIP action detects exactly one archive and removes it after applying. ZIP has one `PDD-0.2.11/` root and includes `src`, `build.gradle` and `settings.gradle`. Existing `.github/`, `gradlew` and Gradle wrapper are protected and remain in the repository.
2. Wait for **Apply PDD ZIP** job to finish, then start **PDD Manual Build** from Actions. Confirm `Run JUnit tests` and `Build JAR` both succeeded. Artifact is still labelled `PDD-0.2.1-debug` by the **protected workflow**, but select `physical-destroy-deform-0.2.11-debug.jar` inside it.
3. Place the built JAR in `mods/` after removing older `physical-destroy-deform-0.2.1-debug.jar` and other PDD JARs. Do not install this source ZIP directly.
4. Start a singleplayer world with Sable physics and an actual moving Aeronautics structure made of ordinary blocks (e.g., stone). Run tests: ship vs stationary stone terrain; ship vs ship; separately TNT / fragile callback-specific test. Slow then fast collision. A mere visual overlap without physics contact is insufficient.
5. Collect `logs/latest.log` (Android launchers may locate it beneath their instance folder). Search `PDD-COLLISION`, `PDD-SABLE-CALLBACK`, `Mixin`, `PDD-DIAG`.

## Runtime decision matrix
| Evidence | Meaning |
|---|---|
| `[PDD-COLLISION] source=SABLE_RAPIER_NATIVE phase=LIVE_DATA` | Native Sable contact data reached PDD (true runtime feed, not self-test) |
| `nativeCalls>0` and `nativeContacts=0` | Hook executed but Sable's effects feed produced no records in that time window; do not treat as successful capture |
| `nativeCalls>0` and `nativeContacts>0` | Sable native feed received real records from Rapier; correlate their timestamp with the physical impact |
| `[PDD-SABLE-CALLBACK] source=SABLE_BLOCK_CALLBACK` | Existing Fragile/Belt callback method returned and PDD observed it |
| `HOOK_NOT_OBSERVED` | Hook did not execute during the last 100 server ticks; scene may be inactive or Mixin not applied |
| Mixin apply exception | Injection failed; copy full stack trace. Failure is intentionally not hidden |
| `samplesDropped>0` / `dropped>0` | Bounded sampling overflow; total count is still tracked |

**Crucial limitation:** Native `clearCollisions` is a Sable collision-*effects* feed, not an independent guaranteed all-contact event stream. A real crash test MUST check whether ordinary block hits and terrain hits enter this native feed. Without that proof this version is an instrumented candidate, not a verified full collision callback implementation.

## Report template
Copy `[PDD-DIAG] INIT`, `[PDD-DIAG] SABLE_BRIDGE`, 3 successive `[PDD-COLLISION] summary` lines around a collision, actual `phase=LIVE_DATA` samples, and callback summary/records. If no samples, report the test scenario, whether a native physics collision occurred, and the exact time, mod list, and mixin error lines.

# PDD 0.2.0-debug — Diagnostic Source Package

**Target:** Minecraft Java 1.21.1 · Java 21 · NeoForge 21.1.x · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6  
**Design authority:** PDD development draft 3.3.1  
**Purpose:** Log observable checks for the core, API-independent PDD models that actually exist in 0.2.0.

> This is a diagnostic build, not a gameplay release. Startup self-tests use synthetic inputs. They do not prove that a live collision occurred or that blocks are damaged in-game.

## What this build records

`latest.log` receives `[PDD-DIAG]` entries for mod startup and synthetic smoke checks of:

- material property validation (hardness 0–255, ductility/elasticity percent 0–100)
- local damage and neighbor stress model calculations
- `ImpactSample` validation and energy-to-base-damage conversion using a synthetic calibration
- `BlockDamageState` byte-range storage and 24-bit pack/unpack round-trip
- bounded `ObservationBuffer` behavior and signed collision-velocity preservation
- a summary status plus explicit runtime integration limitations

## What is not implemented or claimed

- Sable native collision callback registration and live callback counter
- block/material property registry or automatic property inference
- conversion of Sable callback values to calibrated impact energy
- damage accumulation driven by a real collision
- world mutation, destruction, deformation, neighbor stress propagation, persistence, crack rendering/sync, or collider refresh

The `RUNTIME_INTEGRATION` log deliberately records these gates as `NOT_IMPLEMENTED` / `DISABLED`; synthetic smoke checks must not be confused with gameplay evidence.

## Build

Requires JDK 21, Gradle 8.10.2, and internet access to Gradle Plugin Portal, Maven Central, and NeoForge Maven:

```bash
gradle test --no-daemon
gradle build --no-daemon
```

The Gradle wrapper scripts and wrapper JAR are not included in this ZIP. If you restore `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties` from the repository, use `./gradlew` instead.

This ZIP contains the Java project source, model tests, design/API reference, and Gradle project configuration. The binary Gradle wrapper JAR is not included, so use a locally installed Gradle 8.10.2 (or restore `gradle/wrapper/gradle-wrapper.jar` from the repository) to build. Build and in-game execution were not possible in this packaging environment; confirm a successful build in GitHub Actions before use.

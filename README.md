# PDD — Physical Destruction and Deforming

**PDD 0.2.0** is a development-stage Minecraft Java addon scaffold for Create Aeronautics, intended to use Sable physics to drive physical block damage, deformation and destruction.

> **Current status:** API-verification and diagnostics scaffold, not yet a playable destruction/deformation addon. Runtime damage is intentionally disabled.

## Target environment

- Minecraft Java 1.21.1
- Java 21
- NeoForge 21.1.172
- Create 6.0.10
- Create Aeronautics 1.3.2
- Sable 2.0.6

## What is included

- Validated, API-independent impact/material/state calculation models from the 0.1.x prototype.
- Hardness, ductility and elasticity role separation consistent with design draft 3.3.1.
- `CollisionObservation`, preserving the coordinate-frame label and signed callback velocity without interpreting it as energy.
- `ObservationBuffer`, which is bounded and counts dropped diagnostic observations.
- Unit tests for observation validation and bounded buffering.
- API verification and runtime experiment plans under `docs/`.

## Not implemented

- Sable native collision callback registration/adapter.
- In-game callback counters and collision diagnostics.
- Runtime event deduplication.
- Server-world mutation queue.
- Sparse persistent damage state, block destruction, deformation, stress propagation, explosions/projectiles, crack visuals/sync, or collider refresh.

These are intentionally gated while API-01 through API-08 remain unverified in the target game. Sable's block-scoped callback selection is not a global collision feed; the documented impact-position frame can vary; `impactVelocity` is normal velocity, not energy, and its units/sign/timing still need runtime confirmation.

## Build and tests

With JDK 21 and network access to Gradle Plugin Portal, Maven Central and NeoForge Maven:

```bash
./gradlew test --no-daemon
./gradlew build --no-daemon
```

The repository's latest inspected CI run for 0.1.3 succeeded, but this 0.2.0 source package must pass CI on its own before it can be described as a verified build.

## Design invariants

1. Hardness affects direct damage.
2. Ductility does not reduce direct damage.
3. PDD elasticity controls stress transfer; it is not Sable restitution.
4. Destruction threshold is separate from hardness.
5. Raw Sable `impactVelocity` is never treated as impact energy without verified units and calibration.
6. Physics callbacks must not mutate the world at an unverified processing phase.
7. Existing Sable/Create/Aeronautics collision responses must be preserved.
8. This addon must not implement the separate lightweight conversion/reassembly optimization addon.

See `docs/PDD-0.2.0-API-VERIFICATION-AND-DIAGNOSTICS.md` and `docs/PDD-0.2.0-ROADMAP-REVIEW.md`.


# PDD — Physical Destruction and Deforming

**PDD** is a Minecraft Java addon project for **Create Aeronautics**, designed to implement physical block damage, deformation, and destruction using the Sable physics system.

> **Current status:** Development prototype. PDD is not yet a playable destruction and deformation addon.

## Version 0.1.3

**Target environment:** Minecraft Java 1.21.1 · Java 21 · NeoForge 21.1.172 · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6

Version 0.1.3 aligns the calculation model with **PDD Design Draft 3.3.1** and documents the unresolved API integration requirements.

## Implemented Features

- Minecraft 1.21.1 and NeoForge development project structure.
- Compact material properties:
  - Hardness: `0–255`
  - Ductility: `0–100%`
  - Elasticity: `0–100%`
- Damage, deformation, and crack values constrained to `0–255`.
- A 24-bit packing helper for compact block-state data.
- Hardness-based direct damage attenuation with explicit curve parameters.
- Separation of direct damage and deformation calculations.
- Elasticity-based stress transfer to neighboring blocks.
- API-independent `ImpactSample` data model.
- Explicit impact-energy calibration helper.
- Unit tests for value boundaries, hardness response, and the independence of direct damage from ductility.
- API and implementation status documentation.

## Not Yet Implemented

The following gameplay and runtime integration features remain unimplemented:

- Sable collision callback registration and verified collision-event handling.
- Collision-to-damage processing pipeline.
- Global collision observation for ordinary Minecraft blocks.
- Sparse per-world damage-state storage and persistence.
- Actual block destruction and deformation.
- Neighbor stress propagation.
- Explosion and projectile integration.
- Crack visualization and damage recovery.
- Collider refresh after block changes.
- In-game compatibility and performance validation.

These features depend on resolving the API and runtime integration requirements documented in the project.

## Design Invariants

The following rules must be preserved during development:

1. **Hardness** affects direct damage.
2. **Ductility** controls deformation behavior and must not directly reduce damage.
3. **Elasticity** controls PDD's stress transfer to neighboring blocks; it is not Sable's collision restitution.
4. The destruction threshold remains separate from hardness.
5. `ImpactSample` is a PDD data model, not a native Sable event type.
6. Raw Sable `impactVelocity` must not be treated as impact energy without verifying its semantics and calibrating the conversion.
7. Physics callbacks must not modify the world until a safe server-side update path has been established.
8. Existing Sable, Create, and Create Aeronautics collision responses must be preserved.
9. `CollisionResult.NONE` must not be used as a shortcut to replace existing collision behavior.
10. PDD must not implement the separate optimization addon's lightweight conversion and reassembly behavior.

## Build and Test

The project uses the Gradle Wrapper.

### Linux / macOS

```bash
chmod +x gradlew
./gradlew test --no-daemon
./gradlew build --no-daemon
# PDD — Physical Destruction and Deforming

PDD is a planned Minecraft Java addon for Create Aeronautics, using Sable's physics system as the collision source. It is not yet a complete gameplay addon. 
PDD made by only CHAT GPT

## Version 0.1.3

**Status: development source prototype; not a playable destruction/deformation release.** This version aligns the calculation responsibilities with design draft 3.3.1 and includes the API/argument reference report used to determine the integration boundary.

## Implemented in 0.1.3

- Minecraft 1.21.1 / Java 21 / NeoForge project scaffold, version metadata updated to 0.1.3
- Compact material properties: hardness `0..255`, ductility and elasticity `0..100%`
- Per-block damage, deformation and crack values constrained to `0..255`, with a 24-bit packing helper
- Hardness-only direct damage attenuation with explicit curve parameters
- Ductility no longer reduces direct damage; it is reserved for the separate deformation model
- Elasticity controls only the PDD stress share passed to neighboring blocks
- API-independent `ImpactSample` and explicit energy calibration helper
- Unit tests for boundaries, hardness response, and the invariant that ductility does not change direct damage
- `docs/PDD-0.1.3-API-Arguments-Reference.md`: uploaded API report retained in the source archive
- `docs/PDD-0.1.3-IMPLEMENTATION-STATUS.md`: implementation scope and unresolved integration gates

## Deliberately not implemented

The API report identifies unresolved blockers around native callback frequency, the exact semantics of `impactVelocity`, coordinate spaces, generic-block collision coverage, preservation of existing callback responses, mass/effective-mass interpretation, safe world mutation timing, and collider refresh after block changes. Therefore this version does **not** register a guessed Sable callback or convert callback velocity directly into energy.

Also not implemented: runtime collision-to-damage pipeline, global collision observation for ordinary blocks, sparse per-world state storage and save/load, actual block destruction/deformation, stress traversal, explosion/projectile integration, crack rendering/recovery, and in-game compatibility/performance validation.

## Target environment

- Minecraft Java Edition 1.21.1
- Java 21
- NeoForge `21.1.172` development target
- Create `6.0.10`
- Create Aeronautics `1.3.2`
- Sable `2.0.6`

The exact external runtime integration remains pending the documented verification gates. The PDD source project does not yet compile against or register a Sable API callback.

## Build and test

The repository does not include a Gradle wrapper. With JDK 21 and network access to Gradle Plugin Portal, Maven Central and NeoForge Maven:

```bash
gradle test
gradle build
```

Do not interpret the existence of tests as proof that Gradle tests or a game launch have passed. See the delivery notes for the checks actually performed for this archive.

## Design invariants

- Hardness affects direct damage; ductility affects deformation allowance/response, not direct damage.
- Elasticity controls PDD neighbor stress transfer; it is not Sable restitution.
- Destruction threshold remains separate from hardness.
- `ImpactSample` is a PDD value object, not a Sable event type.
- A raw Sable `impactVelocity` is not energy and must not be converted without validated semantics and calibration.
- Physics callbacks must not mutate the world until the safe server-side update path is established.
- Preserve existing Sable/Create/Aeronautics collision response; do not replace it with `CollisionResult.NONE` as a shortcut.
- PDD does not implement the separate optimization addon's lightweight conversion/reassembly behavior.

## License

See `LICENSE`. Confirm compatibility with upstream mod licenses before redistributing third-party binaries or assets.

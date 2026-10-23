# PDD 0.2.0 Implementation Status

## Baseline and validation evidence

- Base source: PDD 0.1.3 main branch.
- The latest inspected GitHub Actions run (2026-10-23) succeeded for commit `5cad9efa598c8e7b202a7fd2a73c2ed45dbeb7a6`.
- Its single job completed `./gradlew test --no-daemon` and `./gradlew build --no-daemon` successfully.
- This validates that commit's build/tests, not in-game physics behavior.

## Included in this 0.2.0 package

- Version metadata advanced to 0.2.0.
- API-independent `CollisionObservation` record preserves the coordinate-frame label and signed `impactVelocity`; validates coordinate and velocity finiteness and nullable-other-block consistency.
- `ObservationBuffer` provides bounded diagnostic buffering with explicit overflow counts.
- Unit tests cover input invariants, signed velocity preservation, bounded buffering, immutable snapshots and drain semantics.
- API call-path and runtime experiment gates are documented.

## Explicitly not implemented

- Sable collision adapter or native callback registration.
- Runtime callback counters/diagnostics wired into Sable.
- Native callback cadence and deduplication policy.
- Server world-mutation queue integration.
- Material registry/property inference, persistent sparse damage storage, block destruction, deformation, neighbor stress propagation, explosions/projectiles, rendering/synchronization, collider refresh integration.

These omissions are intentional: runtime integration would be speculative while PDD-030–036 remain unresolved.

## Design invariants retained

1. Hardness affects direct damage.
2. Ductility does not directly attenuate direct damage.
3. PDD elasticity controls PDD stress transfer; it is not Sable restitution.
4. Destruction threshold remains separate from hardness.
5. `ImpactSample` is a PDD type, not a native Sable event.
6. Raw `impactVelocity` is not impact energy without unit/calibration evidence.
7. World state is not mutated from an unverified callback phase.
8. Existing Sable/Create/Aeronautics collision responses are preserved.
9. `CollisionResult.NONE` is not used to override existing behavior.
10. This addon does not implement the separate lightweight conversion/reassembly optimization addon.

## Build status

A prior commit has a successful CI run: https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/actions/runs/39922897915

The new 0.2.0 package still needs `./gradlew test --no-daemon` and `./gradlew build --no-daemon` executed against its own exact source before being called a verified build artifact.

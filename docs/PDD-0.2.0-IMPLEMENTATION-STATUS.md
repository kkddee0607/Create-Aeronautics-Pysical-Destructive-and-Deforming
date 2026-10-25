# PDD 0.2.0 — Implementation Status

## Implemented model-only components

- `MaterialProperties`: validated hardness 0–255 and ductility/elasticity integer percentages.
- `ImpactSample`: validated, API-independent impact values; it is not a Sable event.
- `DamageModel`: pure prototype damage/stress math, not SI-unit material physics and not connected to Sable collision events.
- `BlockDamageState`: unsigned-byte-compatible damage/deformation/crack values and a 24-bit packing helper.
- `CollisionObservation`, `ObservationKey`, `ObservationBuffer`: API-independent diagnostic primitives, not registered to a live callback.
- `PddDiagnostics`: debug-only synthetic startup self-test with `[PDD-DIAG]` log lines.

## Explicitly not implemented

Sable callback adapter/registration, live collision counters, material registry/property inference, event deduplication, calibrated collision energy, server mutation queue, persistent damage storage, destruction, deformation, stress propagation, explosions/projectiles, crack rendering/sync and collider refresh.

## Design invariants

1. Hardness affects direct damage.
2. Ductility does not directly attenuate direct damage in the design; current prototype equation must be reviewed against design draft 3.3.1 before gameplay activation.
3. PDD elasticity controls PDD stress transfer, not Sable restitution.
4. Raw Sable `impactVelocity` is not impact energy without unit/calibration evidence.
5. Never mutate the world from an unverified physics callback phase.
6. Never override Sable/Create/Aeronautics collision responses as an observation shortcut.

# PDD 0.2.1 — Sable Collision Integration Algorithm Sheet

**Target:** Minecraft Java 1.21.1 · NeoForge 21.1.x · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6  
**Baseline:** `main` @ `b19f5acbfb3767c6af763616869d7b6ea3769a65` (PDD 0.2.0-debug)  
**Design authority:** PDD development draft 3.3.1  
**State:** Source-grounded algorithm design. Runtime collision integration is not yet implemented or verified.

## 1. Decision

PDD must not replace Sable collision behavior merely to observe impacts. Sable’s callback is a block-specific response callback and returns `CollisionResult(tangentMotion, removeCollision)`. Built-in callbacks can break fragile blocks, ignite TNT, and handle beehives. PDD observation must preserve the existing callback result and must not mutate the world during an unverified native physics callback phase.

The checked API does not expose a confirmed global collision listener for all block pairs. `BlockWithSubLevelCollisionCallback.sable$getCallback(BlockState)` selects a callback only when the block implements the callback interface or Sable’s FRAGILE property applies; otherwise it returns `null`. `RapierVoxelColliderBakery` passes that result into `Rapier3D.createVoxelColliderEntry(..., callback)`. Attaching callbacks to all blocks is not a safe shortcut: Sable’s voxel-neighborhood optimization treats callback-bearing blocks specially.

## 2. Verified Java call path

```text
BlockState
  -> RapierVoxelColliderBakery.buildPhysicsDataForBlock(state)
      -> BlockWithSubLevelCollisionCallback.sable$getCallback(state)
          -> block callback | FragileBlockCallback | null
      -> Rapier3D.createVoxelColliderEntry(friction, volume, restitution, liquid, callback)
          -> native newVoxelCollider(..., callback)
              -> [native Rapier collision pipeline]
                  -> BlockSubLevelCollisionCallback.onCollision(...)
                      -> sable$onCollision(...)
                          -> CollisionResult(tangentMotion, removeCollision)
```

The Java-side selector and callback bridge are confirmed in the Sable source/API reviewed for this task. Native invocation frequency, exact runtime sampling time, and units still require binary/runtime evidence.

## 3. Input contract

| Input | Meaning | Constraint |
|---|---|---|
| `hitBlockPos` | Block associated with the selected callback | Resolve in the correct static/sub-level frame; do not assume global coordinates |
| `otherHitBlockPos` | Optional other block | Nullable; never fabricate a counterpart |
| `impactPosition` | Hit position | Sable documents sub-level plot coordinates for a hit sub-level, or global coordinates if no sub-level contains the block; explicit conversion is required |
| `impactVelocity` | Velocity along collision normal | Not energy or impulse. Unit, sign convention, sampling instant, and callback frequency need evidence |
| `CollisionResult` | Tangential movement and collision-removal decision | Preserve the original callback result exactly |

Do not calculate impact energy from `impactVelocity` until coordinate frame, unit, effective-mass meaning, and sampling instant are validated. Current `ImpactSample`/`DamageModel` are API-independent prototypes, not calibrated SI physics.

## 4. Planned runtime algorithm

### A. Callback boundary — minimal, no world mutation

1. Observe only on the supported server-side physics context.
2. When an original callback exists, invoke it once, retain its exact `CollisionResult`, enqueue a compact immutable observation, and return the original result unchanged.
3. When no original callback exists, do not blindly return `CollisionResult.NONE`; that may alter collision semantics. First prove a composable hook that preserves baseline behavior and Sable’s callback-bearing classification. If no such API exists, investigate a Sable integration mixin/hook instead of faking coverage.
4. Do not mutate world state, query recipes, perform I/O/network sends, propagate stress, or log each impact inside the callback.

### B. Observation record

Store only minimum immutable fields: dimension/level key, physics-system identity if safely available, hit block, nullable other block, impact position, signed velocity, server tick and physics substep if known. Do not retain live `Level`, `BlockState`, `SubLevelPhysicsSystem`, or native/body handles in a long-lived queue.

### C. Deduplication and bounded queue

1. After coordinate semantics are known, key by physics system + ordered block pair (or a one-block key when the counterpart is absent) + quantized impact region.
2. Coalesce repeated samples within a calibrated time window. Retain a documented representative such as peak valid normal speed; do not sum repeats as independent impacts by default.
3. Use a fixed-capacity per-level ring/queue. On overflow, drop and increment a counter; never let a collision storm create unbounded memory growth.
4. Never dedupe across distinct physics systems or dimensions. Runtime IDs are not persistent save identifiers.

### D. Server-tick processing

1. Drain observations after the callback returns, at a verified safe server-thread/world-mutation phase.
2. Resolve fresh block states in the correct world/sub-level coordinate mapping. Skip stale, unloaded, removed, or unsupported contexts safely.
3. Convert callback inputs to calibrated energy only after API gates G3–G5 pass. Until then, telemetry only; no damage mutation.
4. Resolve material properties using a block-identity cache. Explicit user settings outrank inferred values; recipes are supporting evidence, not a complete material specification.
5. Follow draft 3.3.1 mechanics: hardness governs direct damage; ductility governs permanent deformation; PDD elasticity governs neighbor stress transfer, not Sable restitution.
6. Propagate stress iteratively with a visited set, deterministic neighbor order, maximum depth, maximum affected-block budget, and minimum-transfer threshold.
7. Apply server-authoritative state changes only at a verified mutation phase. Batch writes per level and coalesce redundant writes.
8. Verify and invoke the correct Sable collider-refresh path. `setBlock` alone does not prove static/sub-level collider refresh.
9. Persist only changed blocks using sparse damage state. Send crack updates only when quantized visual state changes.

## 5. Performance constraints

- No persistent object for untouched blocks.
- Material cache keyed by block identity; never scan all blocks every tick.
- Fixed-capacity observation queue and low-allocation immutable records.
- No disk/network I/O, recipe lookup, per-impact logging, world mutation, or heavy propagation in the callback.
- Aggregate per block pair per server tick; use saturating byte state values 0–255.
- Bound stress propagation by depth, visited set, node budget, and minimum stress.
- Summarize diagnostic counters periodically.
- Keep observation, calculation, world mutation, persistence, and rendering separate.

## 6. API and source map

| Component | Verified role | Status in PDD main |
|---|---|---|
| `BlockSubLevelCollisionCallback.sable$onCollision(BlockPos, @Nullable BlockPos, Vector3d, double)` | Collision response callback | No PDD adapter/registration |
| `BlockSubLevelCollisionCallback.onCollision(...)` | Primitive bridge; converts args, calls `sable$onCollision`, returns 4 doubles (tangent XYZ + remove flag) | Not connected |
| `BlockWithSubLevelCollisionCallback.sable$getCallback(BlockState)` | Selects block callback / FRAGILE / null | External Sable API |
| `RapierVoxelColliderBakery.buildPhysicsDataForBlock` | Selects callback and passes it to collider creation | External Sable implementation |
| `SablePrePhysicsTickEvent` / `SablePostPhysicsTickEvent` | Physics phase markers (`system`, `dt`), not collision records | Not a collision feed |
| `ImpactSample`, `DamageModel`, `BlockDamageState`, `CollisionObservation`, `ObservationBuffer` | Existing model/data primitives | Not connected to live callback |
| `ServerLevel.destroyBlock`, `Level.setBlock` | World mutation | Disabled; collider refresh unresolved |

Sable sources reviewed:
- https://github.com/ryanhcode/sable/blob/227077f7f528c9beab1336a9fad2e27b754e4e70/common/src/main/java/dev/ryanhcode/sable/api/physics/callback/BlockSubLevelCollisionCallback.java
- https://github.com/ryanhcode/sable/blob/227077f7f528c9beab1336a9fad2e27b754e4e70/common/src/main/java/dev/ryanhcode/sable/api/block/BlockWithSubLevelCollisionCallback.java
- https://github.com/ryanhcode/sable/blob/227077f7f528c9beab1336a9fad2e27b754e4e70/sable_rapier/src/main/java/dev/ryanhcode/sable/physics/impl/rapier/collider/RapierVoxelColliderBakery.java

PDD sources:
- https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/blob/main/src/main/java/com/physicaldestroydeform/PhysicalDestroyDeform.java
- https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/blob/main/docs/PDD-0.1.3-API-Arguments-Reference-Draft-3.3.1.md

## 7. Blocking validation gates

| Gate | Required proof before enabling |
|---|---|
| G1 — callback composition | Existing Sable callback responses and callback-bearing classification remain unchanged |
| G2 — ordinary block coverage | A verified hook covers blocks with no Sable callback; no global-listener assumptions |
| G3 — callback frequency/dedupe | Live test establishes when and how often contacts are reported |
| G4 — velocity/coordinates | Unit, sign, sample instant, and static/sub-level coordinate transforms validated |
| G5 — effective mass | `MassData` fields and units traced; do not infer from the type name |
| G6 — mutation phase | Server thread and callback/physics phase proven safe |
| G7 — collider refresh | Static-world and sub-level block changes refresh collision geometry |
| G8 — Create/Aeronautics overlap | Same impact cannot be counted twice across pathways |

Any unpassed gate keeps its related gameplay feature disabled. Synthetic startup tests must never be presented as live integration.

## 8. Build and release criteria

- `main` currently targets NeoForge `21.1.172`; align it with the actual Sable 2.0.6 dependency requirements after confirming uploaded JAR metadata.
- Run `./gradlew test build --no-daemon` in GitHub Actions and inspect logs/artifact.
- Test slow/fast impacts, sustained contact, TNT, beehive, fragile blocks, sub-level/static collision, sub-level/sub-level collision, and immediate re-collision after break.
- Release only after a game log proves a live callback invocation and existing Sable responses remain unchanged.

## 9. Baseline status

PDD `main` at `b19f5acbfb3767c6af763616869d7b6ea3769a65` is `0.2.0-debug`. Startup tests use synthetic inputs. No Sable callback adapter/registration or collision-driven world mutation exists yet. This sheet defines the next implementation direction; it does not claim runtime integration is already done.

# PDD 0.2.0 — API Verification and Diagnostics

**Target:** Minecraft Java 1.21.1 · NeoForge 21.1.x · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6  
**Design authority:** PDD development draft 3.3.1  
**Base code:** PDD 0.1.3  
**Status:** API-independent diagnostics scaffold; gameplay damage remains disabled.

## Why this release does not mutate the world

The PDD 0.1.3 API report and roadmap identify native Rapier callback frequency, callback velocity semantics, coordinate handling, preservation of existing callback responses, safe world-mutation timing, and collider refresh as gates for runtime collision damage, destruction, and deformation. Do not infer missing behavior from method names.

## Confirmed Java API facts

### Collision callback

Sable's `BlockSubLevelCollisionCallback` bridge receives integer block coordinates, an impact-position vector, `impactVelocity`, and `hasOtherBlock`; it constructs `BlockPos` values and calls `sable$onCollision(...)`. When `hasOtherBlock` is false, the second position is null.

The source Javadoc states that `impactPosition` is in the plot of the sublevel hit, or in global coordinates if that sublevel does not contain the block. It describes `impactVelocity` as impact velocity along the collision normal. These comments do **not** establish native callback frequency, units, sign convention, or exact sampling point.

The callback result is `CollisionResult(tangentMotion, removeCollision)`, which the bridge serializes to a four-element array. An observer must preserve the existing result; returning `CollisionResult.NONE` as a blanket replacement is prohibited.

Sources:
- https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/physics/callback/BlockSubLevelCollisionCallback.java
- https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/block/BlockWithSubLevelCollisionCallback.java

### Callback selection scope

`BlockWithSubLevelCollisionCallback.sable$getCallback(BlockState)` selects callbacks for blocks implementing the callback interface or states marked `FRAGILE`; otherwise it returns null. It is not a global collision feed for every block.

Source:
- https://github.com/ryanhcode/sable/blob/main/sable_rapier/src/main/java/dev/ryanhcode/sable/physics/impl/rapier/collider/RapierVoxelColliderBakery.java

### Mass and velocity

Sable's Java API documents pipeline linear velocity in m/s and angular velocity in rad/s. `MassData` documents mass with the unit label `kpg`, inverse mass as `1/kpg`, and inertia as `kpg*m^2`. `getInverseNormalMass(position, direction)` combines inverse mass and a rotational term around the center of mass. This may help estimate effective mass, but position and direction must be in the documented plot/local frame. Check the exact Sable 2.0.6 binary and runtime behavior before combining values.

Sources:
- https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/physics/PhysicsPipeline.java
- https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/physics/mass/MassData.java

### Block changes and collider state

`PhysicsPipeline.handleBlockChange(...)` is documented as a server-side notification for a chunk block change, with chunk-relative x/y/z. It updates the physics pipeline, not the Minecraft world state itself. Calling `ServerLevel.destroyBlock` or `Level.setBlock` is not proof that every static-world/sublevel collider refreshes correctly.

## Required runtime observations

Run these against the exact Sable 2.0.6 JAR and Create Aeronautics 1.3.2 bundle in a disposable world. Java signatures alone cannot certify these results.

| Gate | Experiment | Record | Exit criterion |
|---|---|---|---|
| API-01 | Static block vs moving sublevel; single, multi, and sustained contacts | callback count, positions, tick/substep | triggering and frequency characterized |
| API-02 | Normal approach at multiple speeds, oblique approach, separating contact if observable | raw velocity, geometry, frame/time | sign, unit, and measurement timing independently confirmed |
| API-03 | Move and rotate a sublevel against a static block; sublevel-sublevel if supported | block positions, impact position, transformed world coordinates | every coordinate frame/transform verified |
| API-04 | Callback present/absent and missing second block | callback type, nullable second position, object type | supported/unsupported collision pairings classified |
| API-05 | Sable fragile block, TNT, beehive, and normal callback block | original result and in-world behavior | no response alteration or duplicated Sable/vanilla actions |
| API-06 | Compare body mass, center of mass, inverse normal mass and a known case | mass fields and directions | mass semantics agree with binary/runtime |
| API-07 | Record thread, physics phase, event phase and queue drain phase | thread name, level, substep, tick | safe server mutation point established |
| API-08 | Change static-world and sublevel blocks independently | changed state and next collider response | no ghost collision; collider refresh proven |
| API-09 | Aeronautics contraption/Sable sublevel overlap | hook and callback occurrence | duplicate accounting paths understood |

## 0.2.0 implementation boundary

Allowed: version metadata, documentation, API-independent observation models, finite-value/consistency validation, bounded diagnostic buffers, and unit tests. Diagnostics must not mutate the world or rewrite Sable responses.

Prohibited until evidence is attached: speculative global collision hooks; treating raw `impactVelocity` as energy; assuming a single coordinate frame; replacing results with `CollisionResult.NONE`; world mutation from an unverified physics callback phase; claiming collider refresh based solely on Minecraft state change.

This document supports PDD-030 through PDD-037 and prepares PDD-040 through PDD-045. It does not close those issues. Runtime logs/tests must be attached before the corresponding issues can be marked complete.

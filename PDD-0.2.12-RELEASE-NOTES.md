# PDD 0.2.12 — Universal Sable callback intake (source candidate)

## Reference
- GitHub main `d02a915b397bf9e3988645da76b8a56f10283d6e` (2026-10-27)
- User's `PDD-1.0.0-Integrated-Design-Specification`, §14.2, target 0.2.12
- MC JE 1.21.1, NeoForge 21.1.228+, Create 6.0.10, Create Aeronautics bundled 1.3.2, Sable 2.0.6

## Implemented (source; NOT yet game-tested)
1. Instrument Sable `RapierVoxelColliderBakery.buildPhysicsDataForBlock(BlockState)` at its actual `sable$getCallback` call using a fail-fast `@ModifyExpressionValue` Mixin. Wrap *non-air* blocks even when the Sable callback was `null`.
2. Delegate to the exact original callback once, preserving the `double[]` object returned by `onCollision`, or the exact `CollisionResult` returned by `sable$onCollision`. Callback-free branches use a neutral result. No direct physics/world changes.
3. Always capture immutable `RawObservation` with capture time, source, block identifier, original callback metadata, callback arguments and raw return flag; owner/frame explicitly unknown. This is an explicit data handoff for the future normalizer, not just a log print.
4. Bound the raw queue to 2048 records, keep lifetime counts for invocations, drops, capture errors, original/null callback registrations. Print up to 8 samples per tick and per-100-tick summaries by default.
5. Retain the 0.2.11 native `Rapier3D.clearCollisions` effects-feed hook as **diagnostic only**; do not combine the two feeds as independent physical impacts.
6. Keep existing material-type cache, packed byte state and independent prototype damage calculations; **no actual damage, deformation, persistence, or world mutation**.

## Critical limitations
- Wrapping a Sable `null` callback registers a non-null Java callback in the native collider. It is a deliberate behavior-path change. Whether an ordinary collision runs the callback, and whether native physics handling remains unchanged, must be checked in a real game; pure Java tests cannot prove this.
- Callback coordinates, time units, owner physics space and Sable native-effect scalar are **UNVERIFIED**. Do not use these records as world coordinates, joules or m/s until 0.2.13+.
- Other mods that modify the *same* `sable$getCallback` expression may have mixin-order compatibility issues. Test separately with True Impact / Sable Collision Damage / Sable Impact / Sable Optimizer; they are **not required dependencies**.
- The hooked method may only execute for voxel entries Sable actually bakes; excluded or non-collider blocks may produce no records.
- External network for Maven/Gradle is unavailable in this execution environment. A real NeoForge compile, JUnit run and game test have **not** been performed. See `docs/verification/0.2.12.md`.

## Scope decisions (Ponytail)
Reuse existing bounded queue, materials and native diagnostic feed; add no physics engine, world mutation adapter, new background service, duplicated collision classifier or speculative config system. No separate state storage for every block.

## Safe installation into GitHub
**DO NOT upload this ZIP to `incoming/` unchanged.** The repository's `Apply PDD ZIP` workflow recursively deletes files missing from the archive; this ZIP does not contain all historical repository documentation and root files. Use `scripts/merge-into-repo.sh` in a Codespace to preserve the historical files; then trigger the existing Manual Build Action. The standalone source ZIP can also be opened independently as a Gradle project.

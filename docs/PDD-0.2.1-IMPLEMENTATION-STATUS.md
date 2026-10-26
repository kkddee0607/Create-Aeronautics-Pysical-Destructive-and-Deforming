# PDD 0.2.1-debug — implementation status and decisions

Baseline: `main` @ `b19f5acbfb3767c6af763616869d7b6ea3769a65` (inspected 2026-10-26).
Authority: development draft 3.3.1 and PDD 0.2.1 Sable collision algorithm sheet.

## Issue traceability

| GitHub issue | Implementation | Qualification |
|---|---|---|
| #30 PDD-045 | `SableCollisionBridgeMixin`, `SableContactQueue`, `PhysicalDestroyDeform.onServerTick` | Existing Sable fragile/TNT/beehive/bell and belt callback implementations only. Needs in-game Mixin confirmation; no global collision coverage. |
| #31 PDD-050 | `MaterialRegistry` map indexed by registered block ID | One entry per registered block type, never one entry per placed block. |
| #32 PDD-051 | Default attributes loaded for all registered blocks at server start | User override JSON has highest priority. Does not change third-party block definitions. |
| #33 PDD-052 | One deterministic recipe pass at startup or reload | A conservative heuristic: 75% base attributes, 25% block ingredient mean; recipe cannot prove material physics. |
| #34 PDD-053 | `FALLBACK` = (128, 20, 30) and neutral values for unsupported mining lookup | Missing recipes keep intrinsic estimate; unregistered IDs return fallback. |
| #35 PDD-054 | `OnDatapackSyncEvent` reload and atomic immutable map swap | Only on full datapack sync, not per login. Manual override file changes require `/reload` or server restart. |
| #36 PDD-055 | `HardnessScale` plus boundary, monotonicity tests | Heuristic normalized mining/explosion resistance; hardness is not destruction threshold. |

## Validated external binary facts

`Sable 2.0.6` has an interface default `BlockSubLevelCollisionCallback.onCollision(int×6,double×4,boolean)` that delegates to `sable$onCollision(BlockPos,@Nullable BlockPos,Vector3d,double)`, then returns a four-double array (tangent X/Y/Z, remove flag). Instead of injecting into the interface default method (whose Mixin compatibility remains unproven), PDD injects at `RETURN` into **concrete** `FragileBlockCallback.sable$onCollision` and `BeltBlockCallback.sable$onCollision`. The former covers its inherited TNT, beehive, and bell callbacks. PDD copies the returned record's `toString()` for debug output but never replaces the record or callback selection. All signatures were inspected using `javap` on the attached Sable JAR; this is **not** proof that the Mixin is correctly applied at game launch.

NeoForge requirement was raised from `21.1.172` to `21.1.228` based on uploaded Sable 2.0.6 / bundled Aeronautics 1.3.2 JAR metadata; Create 6.0.10 requires `21.1.219`.

## Non-negotiable runtime limitations

- No evidence yet for a universal collision listener; ordinary callback-free blocks are **not** claimed as observed.
- Callback arguments are raw Sable values. Dimension, correct coordinate frame, physics-system ID, tick/substep, and units have not been established. Never label raw hit coordinates as world coordinates.
- The bridge records only optional telemetry when `-Dpdd.debugCollisions=true` is specified. Its queue contains only value types, has capacity 2048, drops on overflow and records a counter. The server tick drains it and emits up to eight sample lines per tick and a summary each 100 ticks.
- Mixin/callback invocation, server-vs-client context, callback preservation for TNT/beehive/fragile blocks, frequency/deduplication and physics substep timing all require **live logs**. Mixed-side sampling cannot yet be attributed to a dimension.
- Calibrated impact energy, mass lookup, dedupe across physical systems, world/sublevel mutation, collider refresh, damage, permanent deformation, stress propagation, persistence and rendering are disabled.
- Existing `ImpactSample` and `DamageModel` are synthetic models, not validated physical collision data. `DamageModel` now follows draft 3.3.1 by excluding ductility from direct damage attenuation.

## Hardness conversion policy

Let `d` be Minecraft block destroy speed and `b` the block explosion resistance. For `d < 0` PDD returns 255. Otherwise define `S(x,r)= clamp(log(1+max(0,x))/log(1+r), 0, 1)` with NaN -> 0 and +Infinity -> 1. Hardness byte is `round(255*(0.65*S(d,50)+0.35*S(b,1200)))`. Limits 50 and 1200, weights 65/35, and fallback values are **engineering defaults**, not empirical material constants. Round happens once. The score is monotone and bounded 0..255. Changes must be tested because existing cached scores are regenerated each server startup/reload.

## Recipe precedence

1. Intrinsic (mining / blast) baseline for all registered blocks.
2. First eligible (lexicographically smallest recipe ID) recipe with registered BlockItem inputs: blend 25% its mean with 75% intrinsic attributes.
3. Explicit JSON override at `config/pdd-materials.json`. Missing fields inherit current values; invalid entries are skipped and logged.

The registry is rebuilt as a whole and swapped atomically; collisions never invoke recipe scanning. 0.2.1 does not attempt automatic inference from Java block classes or untrusted biome/world positions.

## Build status

Source package assembled and pure Java checks can be executed offline. Full Gradle/NeoForge compilation, Minecraft launch, callback and `latest.log` confirmation **not verified in this environment** because Gradle distribution and dependency downloads are unavailable. The GitHub Apply Action synchronizes the ZIP but does **not** compile it; run the separate Gradle Build Action after Apply.

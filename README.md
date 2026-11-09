# PDD 0.4.1-spreadFix — Physical Destruction and Deforming

**Create Aeronautics: Physical Destruction and Deforming (PDD)** is a Minecraft Java 1.21.1 / NeoForge add-on that consumes Sable collision evidence to calculate block-level impact propagation and damage. The **0.4.1-spreadFix** release retains the 0.4.1 lineage and extends the read-only 0.4.0finalFix impact pipeline with **server-side damage accumulation and conditional destruction of eligible static World blocks**. Physical Plot block destruction, deformation, and stress mutation are **not enabled** in this release.

> **moreFix (prior release) status:** This source passed 39 Java21 independent stub-based test methods, but the 0.4.1-moreFix Gradle/JUnit build, GitHub Actions, and in-game destruction/contact tests were **not performed** when this ZIP was assembled. Treat it as an **unverified development release**. Back up the world and test on a disposable server first. See [0.4.1 verification report](docs/PDD-0.4.1-RELEASE-VERIFICATION.md).

## Supported environment

| Component | Target |
| --- | --- |
| Minecraft Java Edition | 1.21.1 |
| NeoForge | 21.1.228 or compatible 21.1.x |
| Java | 21 |
| Create | 6.0.10 (declared range `[6.0.10,6.1)`) |
| Create Aeronautics (bundled) | 1.3.2 (declared range `[1.3.2,1.4)`) |
| Sable | 2.0.6 (declared range `[2.0.6,2.1)`) |

PDD does not replace Sable's physics solver or bundle its runtime. Install the required dependencies and the **built PDD JAR** in the server's `mods/` directory. The source ZIP is for development / GitHub Apply and **must not** be installed as a mod. A matching client mod configuration may be required by the mod loader; complete client-server compatibility remains unverified for this build.

## 0.4.1-spreadFix — direction-biased lateral F propagation (2026-11-09)

- On an approved collision, verified inward children receive **70%** and lateral children **30%** of the parent's outgoing half (when both groups exist). Missing groups release their reservation to present verified children; backward transfers are forbidden. Unknown normal retains equal weighting.
- Removes the old geometric cone veto which forbade *all* lateral propagation on the initial native contact plane outside sparse contact seeds. Graph still uses six-face owner-local verified neighbors, parent-level half transfer, elasticity, F/Q safety budgets and one source envelope.
- A single native solver witness can now authorize only a **one-cell local candidate neighborhood** when anchored by an independently verified R2 target. Each candidate needs its owner's real collision shape; this is explicitly a post-effect game approximation, **not** proof that all candidates touched the opposite collider.
- Never treats a secondary child as a new Sable native contact; no new collision source F/Q is generated.
- **Validation:** see `docs/verification/0.4.1-spreadFix.md`. Full NeoForge Gradle/JUnit, GitHub Actions and in-game validation are separate from independent Java21 tests.

## 0.4.1-moreFix (historical) — bounded multi-surface runtime correction (2026-11-08)

- **General contact surfaces, not a 5×5 special case.** The owner-local native contact witnesses are grouped by compatible planes; the actual 2D convex contact hull (or witnessed segment) of each group is rasterized and every candidate block is independently rechecked against the real owner-local collision shape. Multiple perpendicular / oblique source planes can contribute seeds to the **same original F/Q event**, without multiplying force or inventing extra solver contacts. No four-corner predicate or one-rectangle restriction remains. Shape/audit, source evidence, region and graph budgets remain enforced.
- **Late first-impact authorization.** A pair first observed in contact is no longer permanently disqualified before the first approved credit. The server can re-audit an ACTIVE zero-credit pair when a previous stable-world-block and Plot AABB snapshot independently prove new approach. The credit FSM permits one first authorization on that independently proved approach. High F **alone never overrides** the duplicate-contact gate; further credits still require verified separation and rearm.
- **Finite F saturation:** `MAX_GAME_F=40,000,000` and default `maxGroupQ=0.161×(MAX_GAME_F−2)` are derived from the same cap, with no arbitrary 5×5 or force-exception threshold. Look for `[PDD-HIGH-F]` to distinguish `approval` rejections from `cappedF` and `[PDD-CONTACT-PATCH]` for individual recovered surface counts.
- **Honest constraint:** The Sable 2.0.6 post-effect records lack a complete synchronized native manifold, per-contact pose and collider-pair snapshot. This patch reconstructs shape-verified **gameplay damage candidates**, not exact native contact forces or a mathematical guarantee of all actual contacting blocks. Plot block mutation/deformation remains preview-only; material, World write and callback safety gates are not removed.

> moreFix is **not a verified game release**: independent Java 21 source/test compilation is separate from Gradle/JUnit, Actions and in-game testing. See `docs/verification/0.4.1-moreFix.md` for acceptance tests and remaining risks.

## 0.4.1-Fix3 reconstructed contact face (2026-11-08)

- A **5×5×4 fully colliding cube face** with just four native corner contact neighborhoods can now recover all **25 owner-local collision-shape-verified face blocks** as distinct F propagation seeds. The previous geometry required four already verified targets on the same four corners; this was rejected in actual logs even when four native corner witnesses were present.
- Native near-axis-aligned, same-plane witnesses define the proposed rectangle; four independently spaced quadrant witnesses and actual per-cell collision-shape rechecks are required. Mixed oblique contacts do not invalidate an otherwise valid planar group. No cross-owner guessed target, unverified occupied voxel, duplicate F/Q credit or volumetric scan is permitted. Faces are limited to 100×100 and bounded by the existing unique-cell cap.
- **F ceiling:** `MAX_GAME_F=40,000,000`; a larger finite native collision is **saturated, not interpreted as no collision**. `maxGroupQ` corresponds to this F ceiling; the original credit/owner/episode gates still apply. An accepted oversized hit emits `[PDD-F-CAP] action=SATURATE_NOT_REJECT`.
- The **broad-base, inward-tapering cone** stays in the existing directional `ForcePropagation` implementation. Its effect requires a validated native inward normal and actually recovered face seeds; no Sable physics callback is replaced.
- No hardcoded block allowlist; ordinary slime/netherite/registered mod World blocks are eligible. Intrinsic negative destroy speed, `MaterialProperties.unbreakable`, block entities, beds/doors, claims, loaded-chunk and owner/state checks remain protective.
- The JAR's NeoForge metadata, startup and World safety policy logs now correctly report **Fix3**, instead of the earlier misleading Fix2 metadata. Look for `[PDD-FACE-POLICY] revision=Fix3-FACE25` to verify the installed build.
- Plot physical destruction/deformation remains **preview-only**, unrelated to this Fix3 contact/force correction. This release does not claim Plot mutation support.

## 0.4.1-Fix: post-test collision corrections

This fix preserves the **World-only mutation / Plot-preview** version boundary and the Sable original collision callback. Fixes:

- Reconstructs **axis-aligned, four-corner-witnessed contact faces** by independently auditing each added candidate against the real owner-local block collision shape; the same approved collision issues one F/Q credit, not 25 credits. This is deliberately a bounded, evidence-based planar repair, **not** proof that arbitrary rotated or partially observed faces are recoverable.
- Saturates the game-scale event F at `40,000,000` (100×100×100 blocks × 4 mass units per block × 10 speed units). This is a **gameplay F proxy, not a measured physical force in newtons**. With default `forceFloor=2`, `forceGain=0.161`, the default group-Q limit is `6,439,999.678`; larger valid forces are clamped rather than rejected.
- Extends R2 contact-audit, seed, graph and World-queue and preview-state limits for large faces. An independently invalid owner/region/chunk or unverified collision remains ineligible.
- Reverses verified-direction propagation geometry: a **broad proven contact face narrows inward**; maximum geometric reach grows monotonically with F and is bounded to 32 block steps. Actual damage still depends on per-block F, material attenuation and caps.
- Removes the hardcoded list of breakable World block IDs. Single-cell vanilla and modded blocks are eligible subject to the unchanged **block entity / linked multiblock / unbreakable / owner / loaded chunk / claim / BreakEvent / stale state** vetoes. This is **not** unrestricted destruction of every installed machine or multi-block.

> **Verification:** Isolated Java compilation and local tests were run; the current full NeoForge/Gradle/JUnit build and in-game behavior remain **unverified**. Always test on a backed-up disposable world. See [the fix verification record](docs/verification/0.4.1-Fix.md) and [integrated specification 1.0.7](docs/PDD-1.0.7-Integrated-Design-Specification.md).

## What's new in 0.4.1

### Static World block damage and break

- Takes **already authorized and sealed** Sable-derived F/Q collision credits from the existing 0.4.0finalFix pipeline; it does not recalculate or redistribute the original F/Q ledger.
- Projects eligible **static World** shares to individual damage intents. Plot shares remain separate and are not committed to Plot blocks.
- Accumulates sparse block damage using an unsigned-byte damage value and a **1/256 fixed-point remainder**. The existing hardness/threshold/ductility/elasticity damage model remains the calculation source.
- When damage reaches the block's destruction threshold, applies a bounded server-thread commit, calls Minecraft's `destroyBlock(pos, true)` **once**, and checks the block state again afterward. No separate `dropResources` call is made.
- Limits **break attempts to 64 per server tick by default**, configured using `-Dpdd.worldBreakCapPerTick=64` (bounded to 1–4096). Pending work has an explicit bounded queue and deferral timeout; rejected or uncertain operations are not silently classified as successful.

**World safety policy (0.4.1-Fix3 corrected).** There is **no hard-coded block-ID allow-list**. Ordinary breakable blocks such as `minecraft:slime_block`, `minecraft:netherite_block` and registered mod blocks can receive damage and break. **Intrinsic negative destroy speed and the material definition's `unbreakable` flag remain absolute vetoes**: this release does **not** break bedrock or change `UNBREAKABLE` behavior. Block entities and linked multi-cell blocks (e.g. doors/beds) remain conservatively protected until their lifecycle/atomicity can be verified. Loaded chunk, verified World ownership, claim/`BreakEvent`, fingerprint and WAL protections still apply. A block in `blocks.csv` is not automatically permitted to break.

### Safe server commits

The intended execution sequence is:

`Issued Credit → World Damage Intent → CAS preflight → permission/event veto → recheck → World commit → World re-read → durable receipt`.

The implementation checks server thread, dimension and owner, loaded chunk, block fingerprint and incarnation token, entity/multiblock safety, and permission. A NeoForge `BlockEvent.BreakEvent` is posted using a fake player for an actual break; canceling it vetoes that break. Damage-only accumulation does not synthesize a `BreakEvent`. **Claim mods which do not honor that event may not be compatible**. This is not a guarantee that every external protection system is enforced. A block-replacement Mixin is included; its injection target must be verified against the actual mapped runtime before production use.

### Minimal restart persistence

The World damage / receipt journal is stored at:

`<world-folder>/data/pdd-world-0.4.1.wal`

It uses a versioned, CRC-checked, append-only write-ahead log and synchronous flushes. It stores relevant World damage/remainder and per-ApplyId receipts. The possible receipt states include `PREPARED`, `DEFERRED`, `APPLIED`, `ABORTED` and `PARTIAL_QUARANTINED`. Interrupted work is **reconciled/quarantined**, not blindly replayed as another break. Corrupt or oversized journal data is not silently accepted; unsafe journal initialization disables World mutation. This is **not** the full chunk-sharded storage/migration system planned for 0.5.0, and it does **not** guarantee exactly-once drops through abrupt power loss.

Important operational limits in source: 200,000 stored damage states; 60,000 receipts; a 96 MiB journal cap; and 16,384 queued intents. The synchronous journal can affect TPS under heavy collision loads. Monitor logs and test before enabling PDD on an existing world. **Do not delete the WAL to resolve startup errors without making a complete world backup.**

### Bundled CSV material data and overrides

Both catalogs are **already bundled** in the mod source:

```text
src/main/resources/pdd/materials/blocks.csv
src/main/resources/pdd/materials/items.csv
```

A successful Gradle build packages these resources into the JAR. **You do not need to put separate CSV files in `mods/` for the built-in dataset.** The supplied dataset includes 1,844 block entries and 2,159 item entries. Values are resolved per registered definition/snapshot, not recomputed for every placed block.

For operator edits, optional external files use these exact paths **relative to the game/server directory**:

```text
mods/pdd/materials/blocks.csv
mods/pdd/materials/items.csv
```

Do **not** place bare `.csv` files directly in `mods/`. Files under `mods/pdd/materials/` are data sources, not mod JARs. The existing JSON override location is:

```text
config/pdd-materials.json
```

The implemented block material precedence (lowest to highest) is: **intrinsic / recipe inference → built-in profiles → bundled JAR CSV → JSON override → external user CSV**. External CSV values therefore supersede JSON on overlapping fields. CSVs are checked for format, ranges, duplicate IDs and registration; invalid user entries are logged/filtered or rejected according to the parser and source-loading policy. The material snapshot is published atomically at server startup and during supported data reload handling. The material registry preserves immutable per-generation snapshots for a collision episode.

For blocks the schema has `H` (hardness), `T` (damage threshold), `D` (ductility) and `E` (elasticity); item records include `H`, `D`, `E`. The existing material computation uses the project's `MaterialProperties` and `DamageModel`; CSV `eligibility` is **not an override of the World gateway's safe-block restrictions**. Existing Minecraft intrinsically unbreakable status cannot be overridden by a CSV.

## Preserved 0.4.0finalFix behavior

- Sable original collision callback order, count, return values and native physics handling.
- Verified collision ownership and coordinate transforms, multiple contact normals, available-sample treatment at the upstream native 100-record cap, and diagonal averaged-normal use for F propagation without inventing a deformation axis.
- Episode/separation-based deduplication and rearm; F-first propagation, branch/merge limits, terminal recovery, elastic attenuation, Q sealing and F/Q invariants.
- Prior diagnostic preview storage and existing JUnit test sources. The preview checkpoint is **not** the authoritative 0.4.1 World WAL.

Historical implementation details and previous verification are preserved in [0.4.0finalFix notes](docs/verification/0.4.0finalFix.md), the [F-first propagation design](docs/PDD-0.4.0-F-First-Impact-Propagation-Design-v0.2.md) and the repository's `docs/verification/` history. Historical `worldMutation=OFF` log examples refer to earlier releases or specific diagnostic phases, **not** to the intended static World commit capability in 0.4.1.

## Build and GitHub Apply

Source repository: [Create-Aeronautics-Pysical-Destructive-and-Deforming](https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming).

For an existing GitHub setup, upload **one complete project source ZIP** into `incoming/` and use the existing **Apply PDD ZIP** workflow. It recognizes a Gradle project root containing `build.gradle`, `settings.gradle`, and `src/main/java`. The workflow preserves repository-side `.github/`, `incoming/`, `gradle/`, `gradlew`, and `gradlew.bat`. Never upload a partial source ZIP to this workflow: it mirrors unprotected project paths and can delete files omitted from the archive.

After Apply, run **PDD Manual Build** and **PDD Callback Contract Test**. For local builds, use the repository's existing Gradle wrapper:

```bash
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

Install the successfully built mod JAR only after the full build succeeds. The project requires its configured NeoForge/Maven dependencies; do not assume local Sable/Create/Aeronautics JARs can replace Gradle's dependency resolution without inspecting the existing build configuration.

## Diagnostics and performance

Useful log prefixes:

- `[PDD-COMMIT]`: queued work, applied damage, block breaks, rejection and uncertain outcomes.
- `[PDD-SAVE]`: WAL initialization, writes, recovery, quarantine, storage limits and I/O faults.
- `[PDD-MATERIAL]`: snapshot generation, CSV sources, overrides and input issues.
- `[PDD-F-INVARIANT]`, `[PDD-Q-SEAL]`, `[PDD-F-PERF]`: unchanged F/Q accounting and performance evidence.

Optional JVM properties:

| Property | Default / behavior |
| --- | --- |
| `pdd.worldBreakCapPerTick` | `64` attempted World breaks per tick (clamped 1–4096) |
| `pdd.world.verbose` | `false`: additional World offer diagnostics |
| `pdd.f.trace.enabled` | `false`: verbose F-edge and node tracing |
| `pdd.normal.samples.maxPerCredit` | `8`: bounded native-normal sample diagnostics |
| `pdd.material.trace.maxPerTick` | `16`: bounded material trace lines |

Logging, synchronous WAL writes, safety rechecks and the legacy collision path can all affect tick time. **20 TPS is not guaranteed**.

## In-game acceptance testing (maximum two sessions)

1. **World commit and protection:** On a disposable world with supported mod versions, repeatedly collide a Sable/Aeronautics physical structure with loaded static vanilla stone. Verify damage accumulation followed by one block break/drop, and inspect `[PDD-COMMIT]` / `[PDD-SAVE]`. Test block entities, unsuitable multiblocks and claimed or unloaded locations: these must not be changed. Confirm the F/Q ledger and Sable callback behavior still look consistent.
2. **Restart and stale damage:** Stop the server normally, restart and strike a previously damaged block to verify progression resumes. Replace a damaged block (including same-state replacement) and confirm old damage is not reused. On a separate backup, assess interrupted PREPARED/DEFERRED reconciliation and quarantine without assuming exactly-once drops after a crash.

A failed Gradle build, failed Mixin application, unexpected break/drop, claim bypass, state loss or receipt mismatch is a **failed acceptance criterion**. Preserve the full Actions/Gradle output, `latest.log`, crash reports, loaded mod versions, and relevant `[PDD-*]` log lines.

## Roadmap and exclusions

- **0.4.1:** static World damage/conditional destruction, basic durable receipts and material sources (implemented in source; full runtime validation pending).
- **0.5.0:** comprehensive chunk-scoped persistent state and migration, **not implemented** here.
- **0.5.1:** actual physical Plot block mutation/destruction, **not implemented** here.
- Plastic deformation and stress propagation do not automatically become additional Direct damage in 0.4.1. No Plot voxel movement, splitting or visual deformation is claimed by this release.

## License

See [LICENSE](LICENSE) for the repository's licensing terms. External mods and upstream datasets retain their respective licenses and attribution requirements.


## 0.4.1-Fix3 corrected (2026-11-08)

- **Corrected an over-broad Fix2 policy:** previously this source attempted to bypass intrinsic and material `UNBREAKABLE` and allowed block entities and linked multi-cell structures to break. That was **not** the requested behavior and is now reverted.
- **Removed only the temporary block-ID allow-list.** Slime, netherite, ordinary modded single-cell blocks and other non-immune blocks are now eligible; all existing ownership, physics phase, claim, cancellation, BE, linked-structure and immutable material safety checks remain active.
- World preview and actual World commit both preserve definition-level `unbreakable` without converting material profiles. Plot remains preview-only.
- Retains Fix1/Fix2 collision-face reconstruction, F=40,000,000 saturation, reverse cone geometry, and their tests. Gradle 8.10.2 bootstrap adjustments from the previous Fix3 are also retained, but **`gradle/wrapper` is protected by Apply**; the included wrapper properties must be manually applied on GitHub if not already done.
- See `docs/verification/0.4.1-Fix3-unbreakable-correction.md` for exact changed files, validation, limits and test handoff.

## PDD 0.5.0 — Chunk-sharded World persistence

0.5.0 preserves the 0.4.1-spreadFix F-first 70:30 collision and World CAS contracts.
It migrates schema-2 `data/pdd-world-0.4.1.wal` read-only into a CRC-validated,
checkpointed store at `data/pdd-world-0.5.0/`: per-dimension/chunk sparse damage,
256 durable receipt buckets, fsynced recovery WAL, and atomic generation manifest.

The original WAL is kept for forensic recovery. Do **not** run a downgraded PDD
version against the same world: divergent legacy WAL writes fail closed on the
next 0.5.0 restart. World block damage still requires an already loaded chunk
and validated live block fingerprint; Plot mutation remains disabled.

Debug: set `-Dpdd.persistence.verbose=true` (or `-Dpdd.world.verbose=true`) in
server JVM arguments, then inspect `[PDD-PERSIST]`, `[PDD-COMMIT]`, and
`[PDD-SAVE]`. Full implementation limits, migration details, regressions, and
two-session in-game test procedure are in
`docs/verification/0.5.0-CAP-SPARSE-PERSIST.md`.

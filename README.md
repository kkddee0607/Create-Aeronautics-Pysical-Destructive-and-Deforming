# PDD 0.4.1-Fix — Physical Destruction and Deforming

**Create Aeronautics: Physical Destruction and Deforming (PDD)** is a Minecraft Java 1.21.1 / NeoForge add-on that consumes Sable collision evidence to calculate block-level impact propagation and damage. Version **0.4.1-Fix** builds on 0.4.1 and extends the read-only 0.4.0finalFix impact pipeline with **server-side damage accumulation and conditional destruction of eligible static World blocks**. Physical Plot block destruction, deformation, and stress mutation are **not enabled** in this release.

> **Release status:** The 0.4.1 implementation is present in source, but a complete 0.4.1 Gradle/JUnit build, GitHub Actions verification, and in-game destruction/drop/restart tests were **not performed** when this source ZIP was assembled. Treat it as an **unverified development release**. Back up the world and test on a disposable server first. See [0.4.1 verification report](docs/PDD-0.4.1-RELEASE-VERIFICATION.md).

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

**Safety scope is deliberately narrow.** The World gateway permits only certain simple, full-cube vanilla blocks (including stone, cobblestone, deepslate, dirt, planks, concrete and related basic materials). It rejects block entities, suspicious/multiblock states, unbreakable blocks and uncertain ownership. It never intentionally loads missing chunks or treats Sable Plot storage as ordinary World blocks. A block's presence in `blocks.csv` **does not** mean PDD can destroy that block.

### Safe server commits

The intended execution sequence is:

`Issued Credit → World Damage Intent → CAS preflight → permission/event veto → recheck → World commit → World re-read → durable receipt`.

The implementation checks server thread, dimension and owner, loaded chunk, block fingerprint and incarnation token, entity/multiblock safety, and permission. A NeoForge `BlockEvent.BreakEvent` is posted using a fake player; canceling the event vetoes damage/break. **Claim mods which do not honor that event may not be compatible**. This is not a guarantee that every external protection system is enforced. A block-replacement Mixin is included; its injection target must be verified against the actual mapped runtime before production use.

### Minimal restart persistence

The World damage / receipt journal is stored at:

`<world-folder>/data/pdd-world-0.4.1.wal`

It uses a versioned, CRC-checked, append-only write-ahead log and synchronous flushes. It stores relevant World damage/remainder and per-ApplyId receipts. The possible receipt states include `PREPARED`, `DEFERRED`, `APPLIED`, `ABORTED` and `PARTIAL_QUARANTINED`. Interrupted work is **reconciled/quarantined**, not blindly replayed as another break. Corrupt or oversized journal data is not silently accepted; unsafe journal initialization disables World mutation. This is **not** the full chunk-sharded storage/migration system planned for 0.5.0, and it does **not** guarantee exactly-once drops through abrupt power loss.

Important operational limits in source: 200,000 stored damage states; 60,000 receipts; a 96 MiB journal cap; and 8,192 queued intents. The synchronous journal can affect TPS under heavy collision loads. Monitor logs and test before enabling PDD on an existing world. **Do not delete the WAL to resolve startup errors without making a complete world backup.**

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

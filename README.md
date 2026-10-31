# PDD 0.3.0 moreRog — Create Aeronautics Physical Destruction and Deforming

Target: Minecraft Java 1.21.1 · NeoForge 21.1.x (build contract: 21.1.228) · Java 21 · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6.

**Normative design:** `docs/PDD-1.0.3-Integrated-Design-Specification.md`, §5.3–§6.4 and §14.2 (`G-MATH-01`). Base GitHub main commit: `e97cd7c61f4fa890b6efe607d5a9bda31b201d9c`.

This is a **source ZIP**, not a JAR or a confirmed Gradle build. The actual test/verification record is in `docs/verification/0.3.0-whyReject.md` (earlier reports are historical).



## moreRog — rejection evidence patch (read-only)

Extends the previous whyReject diagnostics without changing any approved impact
budget, callback return, native array, collision policy, damage or mutation. To
activate the additional bounded diagnostic output, append:

```text
-Dpdd.moreRog.enabled=true
```

Existing parameters (`-Dpdd.debugCollisions=true`,
`-Dpdd.whyReject.enabled=true`, and optional explicit P-16 proxy settings)
remain unchanged. `moreRog` defaults **off** to avoid expensive diagnostics in
normal play. Each logged owner-pair episode gets a separate
`[PDD-MORE-ROG] phase=EPISODE_EVIDENCE` with:

- Native body-pair post-effect record counts, first four raw local-normal
  vectors and uninterpreted point fields, converted world normals,
  opposing-side cosine, first incompatible canonical-normal cosine and the
  *actual rejecting record ordinal*. This ordinal is **not a manifold ID**;
  manifold ID and native-effect pre-solver substep index are unavailable.
- For every retained pre-solver frame of a **validated** native pair,
  the normal, computed translational approach, radius to the candidate voxel
  center plus voxel corner, each `|omega|*radius` bound, and the first failing
  substep. Up to four frames are shown, but all are counted. An invalid pair
  normal stays invalid: rotation is not inferred from a rejected direction.
- For World–Plot events, an identical rotation upper-bound check whenever the
  static cube face normal is independently verified.
- A voxel-center `omega x r` projection is marked **diagnostic-only**, not the
  solver's contact-point rotational speed. `worldMutation=OFF` always.

These entries are emitted once per **owner-pair episode** rather than once per
callback. A subsequently observed chain/stone or reinforced-deepslate/stone
pair replaces an unrelated diagnostic example in the same episode. Existing
whyReject full target-pair list and category counts remain unchanged.

See `docs/verification/0.3.0-moreRog.md` for build status, explicit source
contracts, two-session in-game procedures and PASS/FAIL/UNVERIFIED criteria.

## whyReject: exhaustive target-type counts and bounded episode evidence

The patch keeps every collision result unchanged while adding the read-only
`[PDD-WHY-REJECT]` stream. By default `pdd.whyReject.enabled=true` and it can
be disabled for non-debug production use with `-Dpdd.whyReject.enabled=false`.

- `phase=EPISODE`: one record per *owner-pair physics epoch* with original
  callback-mapped target blocks, Plot UUIDs, allocated local BlockPos, runtime
  body IDs, source tick, candidate refusal reasons, native **body-pair** gate,
  and the **final** post-cap/post-continuity decision. A bounded first-instance snapshot also includes mass, velocity, angular velocity, inferred normal, and quality provenance. It does not imply a
  native per-block contact manifold.
- `phase=SUMMARY`: cumulative verified callback pair and owner episode counts,
  never conflating `NO_OTHER` / unverified raw callbacks with verified pairs.
  `chainPlotStone` and `reinforcedPlotStone` count ONLY callbacks that verified
  **two different SABLE_PLOT owners** with those block types. Counts of zero
  mean *not observed and verified by PDD*, not proof that a Rapier contact was absent.
- `phase=FOCUS_PAIR_TYPE`/`TOP_PAIR_TYPE`: all non-discarded tracked types
  distinguished by STATIC_WORLD versus SABLE_PLOT; `approvedOwnerEpisodesAssociated`
  is **not** a block-specific impact or damage attribution.
- Logs are rate-limited to 128 regular plus 32 additional physical chain/stone
  or reinforced/stone episodes per server tick. Every omitted episode line and
  target detail has an explicit cumulative count. The verified category table
  retains up to 256 block-type combinations and reports any overflow. These
  caps do not affect the physics pipeline or budget decisions.

The primary regression test is a separately assembled physical stone structure
against the balloon's bottom chain and reinforced-deepslate weight: compare
`phase=EPISODE`, `phase=FOCUS_PAIR_TYPE`, native reasons and `chainPlotStone` /
`reinforcedPlotStone`. The exact contacted block pair cannot be inferred from
**body-pair-only** native normals; this limitation is intentional.


## What this release implements

- Preserves Sable's callback result and existing `G-COL-02` owner/block audit. `NO_OTHER` is counted separately from failed live block lookups. Raw callback impact frames are explicitly **UNVERIFIED**, not assumed Plot-local from the owner.
- Captures immutable pre-solver Sable `MassData` mass, inverse mass, inverse inertia tensor, center of mass, pose and linear/angular velocity. The mass adapter rejects missing or non-unit-scale data rather than fabricating mass/inertia.
- `ImpactMath.exact`: validated contact point, world B-to-A normal, synchronized kinematics, `v_linear + omega×r`, and effective inverse normal mass. Results are intensity indicators, **not** claims about actual Sable solver impulse or SI joules.
- `ImpactMath.proxy`: separate translational game-budget calculation, independent validated approach direction, explicit P-16 factor and step cap. No inferred block-center normal or unverified `signedVelocity`.
- `ImpactEpisodeLedger`: one approved budget per dimension + pre-solver epoch + canonical body pair, with callback count independent target deduplication. This is a provisional read-only grouping for 0.3.0; 0.3.1 implements final budget allocation.
- `ImpactLiveAudit` FIX: on a **live-audited full-collision-cube STATIC_WORLD block**, one Sable callback contact point may identify a single axis-aligned block face. A cheap six-face ambiguity/out-of-block gate infers a **proxy-only** B→A direction without any reciprocal point matching or exact contact location requirement. This source is `BLOCK_FACE_ESTIMATE`, **not** a validated Rapier normal. For Plot–Plot, a separate native-pair-normal bridge is available under the strict conditions below. Ambiguous world faces, unresolved rotations, and insufficient approaches still remain unresolved.
- Plot–Plot proxy: Sable Rapier 2.0.6 `event_handler.rs` stores opposite local manifold normals and pair runtime IDs in a 15-double effect record. `NativePlotDirection` checks **both normal sides**, actual runtime IDs against verified stable Plot IDs, and the orientation/approach of **every pre-solver substep for the same dimension/tick**. It rejects conflicting normals, any non-approaching or rotation-dominated substep, missing input, untracked bodies or malformed/dropped native effect records. If all pass, it takes the **minimum** independently computed game budget across the substeps. There is no block-center proxy, spatial contact-point matching, or exact-SI-energy claim. Force thresholding means some real contacts have no native record and must be rejected. This is *tick-conservative evidence*, not a claim that a post-tick effect can be assigned to one exact solver substep.
- Sable 2.0.6 source-proven limitation: Rust `modify_solver_contacts` computes `impactVelocity` as `abs(normal relative velocity)`, so that field does **not** distinguish approach from separation and is never used to create a budget. Native effect **force amounts and point vectors** remain diagnostic only; local normals can contribute solely to the separately gated tick-conservative game proxy.
- `ImpactContinuityGate` blocks repeat budget credit from resting/continuous owner-pair contacts, including multiple physics substeps; a contact-free full tick is required for a new credit. Multiple owner-pair proxies share a step cap. **No world mutation.**
- Structured `PDD-G-MATH-01` summary, bounded live pre-solver mass/velocity input samples, rejection reason counts, input quality and `approvedEpisodes`; older `G-COL-01/02` diagnostic logs remain. **No blocks are mutated and damage is disabled.**

## Proxy policy

P-16 is **off** by default. Optional explicit Java properties:

```text
-Dpdd.proxy.enabled=true
-Dpdd.proxy.factor=0.5
-Dpdd.proxy.stepCap=100.0
```

The above are **illustrative test-only numbers**, not endorsed physical units or balance settings. Merely enabling policy does not authorize all live impacts: World–Plot uses a verified world full-cube face. Plot–Plot requires two opposite native normals for the exact runtime body pair, stable orientation and positive translational approach in *all* pre-solver substeps of that tick, and acceptable rotation bound. Both paths require verified target blocks. Collision-face estimate is an independently gated **proxy** direction, never an exact Rapier contact normal. Invalid/missing policy numbers do not crash initialization; they reject proxy approval.

## Existing Apply ZIP contract

Upload this entire ZIP as **the sole file** in GitHub repository `incoming/`. The existing `Apply PDD ZIP` action preserves `.github/`, `.git/`, `incoming/`, `gradlew`, `gradlew.bat`, and `gradle/` from the GitHub checkout. The ZIP excludes those protected paths and includes all other project source, tests and documentation. Before upload, compare GitHub `main` SHA with the base SHA above; Apply overwrites unprotected repository files.

After Apply, run the existing `PDD Manual Build` and `PDD Callback Contract Test`. The protected build workflow uploads an artifact named `PDD-0.3.0`. The expected JAR inside is `physical-destroy-deform-0.3.0-moreRog.jar` (inspect the JAR manifest and mod metadata; do not mistake the artifact name for the JAR version).

## Validation

Run in a full GitHub checkout:

```sh
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

Local environment did not contain the protected Gradle wrapper or Gradle distribution; both commands were attempted and exited **127**. The Java 21 auxiliary compiler and JUnit-style assertion runner **do not** establish a successful official Gradle/JUnit build. See `docs/verification/0.3.0-moreRog.md` for the current validation matrix and two in-game test sessions.

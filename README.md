# PDD 0.4.0duperFix — Physical Destruction and Deforming

## duperFix (0.4.0) — final diagnostic candidate

This build is **read-only** (`worldMutation=OFF`) and keeps the existing Sable original callback path, material definitions, F/Q gain, and previous test sources. It is not a verified real-world solver impulse or a world-destruction build. Baseline: GitHub main `bc0267464f9ebd3f63f76a124979df2c549b642b`, from 0.4.0superFix.

1. **Mean manifold normal:** per record corroborate opposite world-frame normals; convert each inward normal to its owner's stable local frame; normalize the vector sum of valid unit normals. Record count, resultant magnitude, weakest alignment and sampled raw normals; refuse an undefined near-zero resultant (<0.10) instead of choosing a fabricated direction. UNKNOWN direction falls back to equal-weight F sharing without a shallow fixed depth cap. Pre-solver/effect-pose and owner-generation checks remain.
2. **Bounded exhaustive propagation:** no arbitrary 2/9-layer depth termination; follow current verified six-face neighbors once per owner-local block, with the existing 30-degree direction cone and 0.1 weighted sharing for corroborated normals. Work cap increases 128→2,048 nodes per side. Unknown/unloaded neighbor or work cap is *not* a verified terminal: residual is reported as `unresolvedF` rather than turned into fake Q. Fully verified boundaries recover their unused half-F into the terminal node's own Q calculation. Attenuation is calculated once from the actual parent's elasticity and child payment path.
3. **Hard F/Q floor:** no F node or edge below 1 is propagated, no Q below 1 is issued. `thresholdDiscardF` and `thresholdDiscardQ` explicitly reconcile subunit cutoffs so they are not misreported as unresolved work. Source events whose total Q is below 1 do not consume the armed episode. The source Q envelope, per-side caps, and preview persistence remain authoritative.
4. **Detailed forensic log:** `[PDD-NATIVE-NORMAL]`, `[PDD-NORMAL-SAMPLE]`, `[PDD-F-EDGE]`, `[PDD-F-MERGE]`, `[PDD-F-NODE]`, `[PDD-F-CUTOFF]`, `[PDD-F-LEDGER]`, `[PDD-F-INVARIANT]`, `[PDD-F-PERF]`, `[PDD-MATERIAL-PLAN]`, `[PDD-F-TRACE]` with suppression counts. Diagnostics are emitted for authorized credits only, never persisted as gameplay state. Default per-credit F trace 4,096 lines and per-tick 8,192; overridable via `pdd.f.trace.maxPerCredit` and `pdd.f.trace.maxPerTick`; use `-Dpdd.f.trace.enabled=false` to disable for ordinary server play.

**Explicitly NOT changed:** reinforced deepslate material values, actual world edits, NeoForge/Sable/Create dependency versions, Sable callbacks or GitHub Actions / Apply ZIP contract. The historical documentation follows unchanged.

**Verification and two-pass in-game protocol:** [`docs/verification/0.4.0duperFix.md`](docs/verification/0.4.0duperFix.md). Actual Gradle/JUnit, Actions, and game results are not implied by stand-alone Java checks.

## 0.3.1QFix changes

1. **Independent Plot/World clearance:** `WorldClearanceVerifier` no longer applies the R4 32-cell target-search limit to the entire Plot AABB. Up to **8,192** expanded footprint cells can be inspected; larger scans fail closed explicitly as `TOO_BROAD`. A dedicated global scan budget of 1,024 cell reads/tick and a maximum of 256 scans/pair/tick bound the incremental work.
2. **Atomic final proof:** A scan spanning multiple server ticks is `IN_PROGRESS`, not proof. At completion, the entire current footprint is rechecked **on one server tick** with a separate global 8,192-read confirmation budget. A new obstruction, unloaded chunk, unavailable Sable plot container, changed footprint, or unverified status cannot promote a pair to `ARMED`.
3. **Rearm without duplicate damage:** The existing `GameImpactCredit` FSM still needs two consecutive, independently completed positive separation observations before `ARMED`; Q calculation is unchanged. An intervening unproved World frame resets the separation streak, and an actual Native contact discards pending clearance progress. The original `ContactCreditLedger` remains unchanged and issues one Q per new episode generation only. Owner generation changes invalidate stale evidence.
4. **Live diagnosis:** `[PDD-WORLD-CLEARANCE]` reports `IN_PROGRESS`, `CLEAR`, `BLOCKED`, `INVALIDATED`, `UNAVAILABLE` or `TOO_BROAD` with scanned/total cells and the exact gate reason. Idle pending scans are pruned; initial precontact work rotates between plots to avoid persistent scan starvation.
5. **0.3.1realFix retained:** Native 100-record saturation flags, R2 duplicate-audit skip, independent R4 budget, up to 32 verified target blocks per side, Q group limit 64, unchanged Sable original callbacks and no world mutation.

**Limit:** Large Plot bounding boxes exceeding 8,192 conservatively expanded World cells cannot be certified by QFix. The atomic verification budget is intentionally finite to protect server tick time. A future Sable-supported spatial index/air-section acceleration can extend coverage without weakening separation proof.

## Preserved contracts

- **World mutation OFF:** this version does not damage, deform, destroy, split or move Minecraft blocks.
- Sable native 15-double record, physics API, original collision callback return and ordering, COM/Plot Body-local coordinate conversion, independent witness R2, R3 cross-substep quarantine, R4 diagnostics only, frame-epoch checks.
- `GameImpactCredit` separation/episode FSM; Q only after positive separation + verified approach; no duplicate credit; force peak, not summed witness forces. Per-group Q maximum 64.
- Existing JUnit sources, prior design/verification documents, Gradle and GitHub Actions Apply contracts.

## Verification and installation

Code and tests changed; see [0.3.1duperFix verification](docs/verification/0.3.1duperFix.md) for actual execution labels, limits, and test procedure. Previous superFix tests are retained; historical 0.25 gain comparisons use explicit legacy Settings.

**Apply:** upload exactly one whole-project Apply-eligible root ZIP to GitHub `incoming/`. Existing Apply workflow preserves `.github`, `incoming`, `gradle`, `gradlew`, and `gradlew.bat` from GitHub. Run existing PDD Manual Build and Callback Contract Test Actions, then install only the resulting JAR in `mods/`. Report any failed Actions logs and full `latest.log`.

## 0.4.0moreFix — bounded R2 shape-audit budget separation

* The existing `collect()` policy and its 32-check unit-test contract remain unchanged. Actual `audit()` now permits **up to 256 distinct inspected owner/cells per side per authorized collision event**; the separate **32 verified damage targets per side** cap is unchanged. Same-owner-cell checks remain cached while each native witness point still undergoes its shape test.
* This addresses the observed `SIDE_A_INCOMPLETE` at the former 32 unique-cell ceiling without lifting server-thread work limits or falsely crediting uncertain targets. If more than 256 distinct shape cells are necessary or more than 32 targets are identified, that side is still quarantined and receives no Q; the other independently proven side may be PARTIAL. This release is not a guarantee against all large-manifold cap exhaustion.
* Checkpoint filename intentionally **remains `pdd-0.4.0Fix-preview-only.bin`** for backwards-compatible preview restoration; it is still shadow-only (`mutation=OFF`).
* B3 balloon item drops are a separate, cause-unknown issue. No other mod's block-breaking behavior was changed.
* Static and actual game/Gradle verification states are reported in `docs/verification/0.4.0moreFix.md`.


### Experimental collision-scale calibration (0.4.0scaleFix)

The source default uses `forceGain=0.161`, `maxGroupQ=65536`, and
`damageGain=2.6` to avoid saturating the total game-Q budget at F=258.
The game-Q formula, callback contract, and `mutation=OFF` remain unchanged.
Vanilla grass and reinforced deepslate receive the material-sheet H/T/D/E
anchors. At hypothetical Sable F=400 (mass 1, 10m/s, 0.025s stop) and
50% allocation to one grass block, the predicted damage reaches T=32.
This is **not** a verified Sable runtime force or actual world destruction.
`-Dpdd.realfix.forceGain` and `-Dpdd.realfix.maxGroupQ` override the
force defaults. Large Q may saturate per-block stored damage and alter
balance; test on a separate world before any future mutation-enabled build.

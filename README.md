# PDD 0.4.0finalFix — Physical Destruction and Deforming

## finalFix (0.4.0) — evidence-safe fast path

**Scope:** production collision selection / F-first per-block distribution / H-T-D-E material and Q sealing are unchanged; `worldMutation=OFF` is still a shadow preview, NOT Minecraft block destruction. Based on GitHub `main` `786c536feab4244ab110a14615c070c0e63c83f5` and IDS 1.0.7.

1. **Sable native 100-record saturation:** Sable's upstream Rapier post-effect feed truncates each poll to 100 native records. PDD cannot restore omitted records. Instead of rejecting all available opposing normals just because the feed is full, finalFix verifies every *available* contact, averages inward vectors in each owner's fixed local basis, and labels the result `NATIVE_100_CAP_SAMPLED` (not complete-contact evidence). Owner identity, opposite-normal, frame round-trip, and every pre-solver substep orientation gate still apply. An indeterminate mean falls back to non-directional F distribution; target selection and one-credit accounting are untouched.
2. **Separate F vector from future deformation axes:** A diagonal, corroborated averaged normal is a valid F propagation direction. `PDD-DIRECTION` separately reports `fVectorStatus` and `gridAxisStatus`; failure to obtain a signed single-block axis does **not** invalidate the valid F vector. No fake axis, no exact solver contact claim, no enabled world mutation.
3. **Per-credit neighbor snapshot:** Reuses resolved server dimension/container, Plot center/UUID and **already audited same-owner block coordinates** only within a single synchronous approved-impact plan. The first read still runs the original Sable ownership, chunk, shape and fingerprint checks. No across-tick cache, no forced chunk load, no owner mixing, no reduced target-validation checks.
4. **Remove redundant cone parsing and frame scans:** Parsed integer seed positions are reused across graph traversal; identical effect owner frames share one all-substep orientation proof within each direction verification. The original 2,048-node safety bound, per-parent half-F and elasticity, F>=1 / Q>=1 cutoff, terminal F recovery, merge-once, Q caps and ledger remain.
5. **Production-safe logging defaults:** Aggregate F ledger, Q seal, invariant and performance summaries remain. Full per-edge/per-node trace is opt-in with `-Dpdd.f.trace.enabled=true`; its previous default emitted thousands of INFO lines on the server tick and was included in `planNanos`. Normal samples default to 8 per credit, material target lines to 16 per tick; increase the existing properties for detailed field diagnostics. When trace is enabled, `planNanos` explicitly marks that it includes diagnostic output.

For development evidence use `-Dpdd.f.trace.enabled=true -Dpdd.normal.samples.maxPerCredit=100 -Dpdd.material.trace.maxPerTick=512` on a brief reproducible test. For routine server use, leave per-edge trace disabled. **Neither standalone checks nor speculative speed estimates are a guarantee of 20 TPS**; use `PDD-F-PERF` in the actual server to decide whether this optimization is sufficient.

**Detailed verification, API notes, and two in-game scenarios:** [`docs/verification/0.4.0finalFix.md`](docs/verification/0.4.0finalFix.md).

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

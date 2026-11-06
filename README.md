# PDD 0.4.0superFix — Physical Destruction and Deforming

## Release 0.4.0superFix — 0.1-quantized oblique F sharing and receipt diagnostics

- Verified owner-local Sable inward normal stays full precision and is **never** rounded. Six-face graph adjacency, existing 30-degree expanding envelope and strict no-backward BFS are preserved.
- Only eligible children share each parent's already approved, elastically attenuated outgoing F. Verified normal projections are quantized to **0.1 weight increments**, with an equal-or-greater 0.1 share per reachable child and deterministic largest-remainder assignment. The sum of child weights is exactly 1.0. UNKNOWN direction continues using the legacy unquantized equal split.
- New per-credit `[PDD-F-EDGE]` records parent/child/owner/layer, input/local/outgoing F, directional fraction and actual sent F; `[PDD-F-MERGE]` records the unique child's parent count and summed F, and `[PDD-F-TRACE]` reports truncated diagnostic lines. Only accepted ledger credits emit these traces; no trace data is retained in the credit ledger.
- Per-tick trace limit 128 lines; per-credited-event limit 96 lines. Use JVM `-Dpdd.f.trace.enabled=false` to disable. Truncation counts are reported for every credited event while enabled.
- Keeps Sable callbacks, F/Q global envelopes, Elasticity/H/T/D material coefficients, Plot/World frame validation, owner IDs, preview-only world mutation OFF and all previous JUnit tests unchanged.
- **Explicitly excluded:** reinforced-deepslate material/threshold edits, actual block destruction/deformation, new Sable API dependencies, per-layer moving Plot world-pose resampling. Checkpoint name changed to avoid treating old policy's preview estimates as directly comparable.

**Verification and in-game log protocol:** `docs/verification/0.4.0superFix.md`.

---

## Historical release — PDD 0.4.0colFix3

## Release 0.4.0colFix3 — multi-contact normal-directed F propagation (design priority: confirmed conversation contract)

- Preserves Sable's native collision handling, owner/episode authorization, F-first Q envelope, material coefficients, elastic parent-Q/child-F attenuation and `worldMutation=OFF`.
- Direction is corroborated from Sable manifold **owner-local vectors** using the captured effect pose (inverse-transpose for non-uniform scale). All pre-solver snapshot rotations **and scales** must agree; otherwise fallback is `UNKNOWN`.
- Normal-based forwarding now uses a 30° expanding forward field with half-block initial width in each owner-local lattice. A permitted grid edge cannot point backward along the inward normal. **No nearest-axis rounding** and no world-coordinate reprojection between BFS layers.
- Multi-source seeds each receive an equal fraction of source F. A later cell can accumulate valid contributions from several distinct parents, yet its Q is computed only once. Earlier or same-depth cells cannot receive F again.
- During one grant, the Plot storage center is frozen as an identity check; movement/reallocation during traversal yields an `UNKNOWN` neighbor, not an invented position. This does not change the contact-time rotation or the local cell indices.
- Normal-free bounded fallback and prior tests remain. New regression tests cover tilted normals, four roots, joining paths, nonuniform scale, epoch mismatch and unknown boundaries.
- Preview checkpoint names are version-namespaced (`colFix3`). Old preview files remain intact; no previous game state is replayed across changed policies.

**Source review and rollout:** `docs/verification/0.4.0colFix3.md`. The 30°/0.5-cell directional envelope is a *provisional gameplay policy* rather than a measured Rapier contact cone. No exact native impulse is claimed.

---

## Previous release — PDD 0.4.0colFix2

### Release 0.4.0colFix2 — native-direction-gated and branch-accounted F-first preview (IDS 1.0.6 + F propagation v0.2)

- New **server-side F-first** impact preview: Sable observed peak F -> World/Plot 50% side budgets -> owner-local verified first-contact seeds -> six-face connected propagation; each layer keeps half the surviving F, except the PROVED terminal receives everything. Target Q is computed **after** its F is assigned. Equal-depth simultaneous contact targets share that layer's F equally (provisional branch resolution, not measured contact timing).
- **Elasticity:** branch-local elastic descendants attenuate each actual predecessor's provisional Q, and each node attenuates its OWN outgoing F. After F-to-Q issuance, a node's own E absorbs its local Q once in `DamageModel`; this is separate from its effect on a different node. Same-episode prior persisted damage is never retroactively changed. Sable motion/solver is not modified.
- Per-owner six-neighbor World and Plot verification uses `OwnerResolver` loaded block-state and Sable Plot access; same credit never visits one cell twice, and the 128-node owner budget is fail-closed. Unknown/unloaded/bounded branches must not masquerade as a terminal. Logs: `[PDD-F-LEDGER]`, `[PDD-F-PROPAGATION]`.
- **Still 0.4.0 preview-only.** No World or Plot block destruction, structural splits or permanent physical deformation (`worldMutation=OFF`), per the IDS 1.0.6 version gates. Distinct 0.4.0colFix2 preview checkpoint, preserving older file names.
- **Native direction:** actual 15-double `clearCollisions` manifold normals are retained. WORLD (-1) is in the fixed-collider/world frame; Plot uses Sable's effect owner pose and owner-local frame; opposite normals, owner identity, multiple records and all captured pre-step pose orientations must agree. Native effect direction is valid for F-path planning, NOT proof of an exact solver impulse/contact substep or authorization to mutate blocks. Missing evidence uses a three-layer maximum (depth 0..2) bounded isotropic fallback, never a fabricated normal.
- **Finite branching:** each parent sends F only to its own child targets; joins aggregate F once, siblings cannot damp each other, unknown/chunk/cap/depth limit cannot receive a manufactured terminal remainder. Verified path endpoints receive all remaining F. The affected owner-local graph is limited to 128 nodes, an inward 0.6-slope native normal cone, a native depth of 9, plus Q-floor early retirement. These are gameplay/CPU policy caps, not Newtonian propagation laws.
- **Elastic block survivability:** the explicit slime profile has `T >= 64` while retaining its existing H/D/E (0/80/100 at default hardness). Its own elastic local Q absorption is calculated exactly once; high elasticity does not grant invulnerability. Material overrides remain authoritative.
- Existing 0.4.0scaleFix2 calibration, source-gate and original Sable callback contract are preserved. See `docs/PDD-0.4.0-F-First-Impact-Propagation-Design-v0.1.md`, `docs/PDD-0.4.0-F-First-Impact-Propagation-Design-v0.2.md`, and `docs/verification/0.4.0colFix2.md`.

## Previous release: 0.4.0scaleFix2 (PDD IDS 1.0.6, experimental calibration)

Based on GitHub main `a7b3c1b3854bb6a2d6e7bad1a6d0b5d600b26094` (latest verified at packaging). Source tree matches the main `src` / `docs` / `scripts` Git trees. **World/Plot mutation remains OFF.** This release does not make real blocks break.

- Retains `Q = min(maxGroupQ, forceGain * max(0, F - forceFloor))`, 0.161 force gain, 65,536 group cap, and 2.6 default damage gain. This is **not measured Sable force**.
- Rejects conflicting block fingerprints for a single owner-local cell in an impact Credit; duplicates with identical fingerprints remain one target.
- Snapshot `-Dpdd.material.damageGain=<positive finite number>` once per bridge instance, invalid property falls back to 2.6; original `-Dpdd.realfix.forceGain`, `-Dpdd.realfix.maxGroupQ` knobs remain. Force gain is limited to [0, 1] by IDS P-16.
- Previous scaleFix2 isolated non-authoritative preview checkpoints by release and a stable policy/material-cache identity (file name begins `pdd-0.4.0scaleFix2-preview-only-`), leaving old checkpoints untouched. A datapack/policy change during the session skips the old-policy checkpoint save. Current calibration is reported under `[PDD-POLICY]`.
- Corrects stale JUnit expectations from preceding calibration changes. The earlier moreFix implementation and reports remain as historical evidence.
- Does not alter the Sable callback, collision episode/owner proofs, per-side 32 target cap, runtime R2 256 shape-read bound, or Apply/Actions contracts.

Details: `docs/verification/0.4.0scaleFix2.md`.

## Historical release 0.4.0moreFix (IDS 1.0.6)

This is the **read-only R2 audit improvement** based on the verified 0.4.0Fix ZIP; the newest GitHub main SHA could not be verified from this environment. The 0.4.0Fix basis was on the actual GitHub `main` SHA `34739bb665d7c0ce5da2d2b0e6f9aae0abae1bb5` (2026-11-03T14:45:13Z). It preserves the full 0.4.0 codebase and unchanged Sable collision/FSM/Q behavior. See [0.4.0Fix verification, source, and two-session in-game protocol](docs/verification/0.4.0Fix.md).

**New**: Read-only cumulative 1/256 fixed-damage shadow state per stable owner/coordinate/fingerprint; bounded credit-ID dedup; runtime SparseStateCodec CRC roundtrip and deliberate corruption rejection; optional restart checkpoint at `pdd-0.4.0Fix-preview-only.bin` in the world folder (diagnostic only, enabled by default, disable via JVM `-Dpdd.preview.checkpoint=false`); per-credit Plot/Plot owner-local native normal evidence gated by pre-substep agreement, owner epochs and grid axis. Unknown World normal remains UNKNOWN. Checkpoint contents are **not authoritative world damage** and are never applied to blocks. No actual damage, stress, plastic deformation, or world mutation in this release.

0.4.0moreFix changes neither the native 100-record cap nor Q/ledger/rearm safety policies. Gradle/JUnit/Actions and in-game testing still require user execution.

---

## Previous release: PDD 0.4.0 (historical notes, IDS 1.0.6)

**GitHub baseline:** `main` `bb064c58381bac8bafae3da36fd591567acc3fb2` (2026-11-03). Original 134 Apply-eligible source files verified against their Git blob SHA values. Read the [0.4.0 implementation and test report](docs/verification/0.4.0.md) for exact feature gates.

**Complete 0.4.0 planning core:** H/T/D/E material profiles and immutable generation snapshots; explicit vanilla anchors and priority overrides, registered-block/recipe and relevant BlockState inference; hardness/elasticity curves, first-pass Direct/Plastic/Stress budget accounting, fixed-point 1/256 damage remainder; verified Credit-to-target material planning; optional verified directional evidence; sparse owner-local key, value and CRC-checked versioned serializer. The original duperFix Q/owner/rearm FSM and Sable post-effect observer stay unchanged.

**No block mutation:** 0.4.0 calculates and logs `PREVIEW_ONLY` plans, never actual damage, stress propagation, deformation, destruction, client visuals, drops or storage writes. These are explicitly later-version features under IDS 1.0.6. The repo's existing protected Apply/Actions files remain in GitHub and are not included in the Apply ZIP.

**Example overrides:** [pdd-materials.example.json](pdd-materials.example.json) defines H, T, D, E, `yieldThreshold`, exact per-BlockState selectors, and optional `unbreakable=true`. Place the user copy under `config/pdd-materials.json`. Disabled Plastic/Stress branches are accounted as unused, not added to Direct.

---

## Historical release 0.3.1duperFix (IDS 1.0.6)

**GitHub source:** `main` at `9563c2c16052a8b42e29b62bc5067bfacb09e085`, November 3, 2026. Source is the existing superFix full Apply-eligible ZIP, with Java/Gradle and IDS blob hashes matched to GitHub main. Do not infer source from a JAR.

**Root fixes:** pre-contact Plot/Plot tracking expiry now uses `lastProcessed` instead of `lastSeen=-1`, preserving proven separation evidence for contact after 600+ ticks. Runtime developer-provisional `forceGain` is now `0.001` instead of `0.25`, preserving the Q=64 cap and linear first-approved force model but avoiding early saturation for observed forces ~3,849–52,421. Original `GameImpactCredit` FSM and `ContactCreditLedger` are unchanged except revision identifiers. No new physics API call or dependency.

**Runtime:** MC Java 1.21.1, NeoForge 21.1.x, Create 6.0.10, Create Aeronautics 1.3.2, Sable 2.0.6. Read-only: `worldMutation=OFF`.

**Design:** [IDS 1.0.6](docs/PDD-1.0.6-Integrated-Design-Specification.md). The gain change is a version-specific `DEV_PROVISIONAL` policy revision under §23; previous defaults remain visible in historical docs and explicit legacy tests. [DuperFix report and two-session game test](docs/verification/0.3.1duperFix.md); [superFix history](docs/verification/0.3.1superFix.md).

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

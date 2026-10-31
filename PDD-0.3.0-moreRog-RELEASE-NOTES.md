# PDD 0.3.0 moreRog — IDS 1.0.3 G-MATH-01 evidence patch

**Source baseline:** GitHub `main` commit `e97cd7c61f4fa890b6efe607d5a9bda31b201d9c` (whyReject).

## Purpose and confirmed prior findings

The whyReject in-game log reported verified `SABLE_PLOT` chain vs stone (1,014
callback pairs), reinforced-deepslate vs stone (2,326), and some positive Plot–Plot
proxy budgets. This is target-pair observation, **not** proof of exact Rapier
manifold block-to-block contact. Missing post-effect entries and rejected
canonical directions were observed. The rotation guard often rejected
otherwise usable Plot–Plot normals. The previous log does not prove that
these refusals are incorrect.

## Implementation

- `ImpactLiveAudit`: diagnostic-only `RotationEvidence` reproduces the existing
  rotation decision inputs for both bodies; logs signed translational velocity,
  `closing`, angular magnitudes, candidate voxel-center distance, radius upper
  bound, `|omega|*radius`, voxel-center `omega x r` normal projection, and
  separate side vetoes. It **does not** replace/relax the actual veto.
- `NativePlotDirection`: optional bounded per-body-pair native-effect evidence,
  raw per-side local normals, raw point fields with unverified coordinate frame,
  first success, first rejecting effect ordinal, world side-normal cosine,
  canonical cosine and thresholds. Logs the all-pre-solver-substeps rotation
  replay only for pairs that pass original native normal validation.
- `WhyRejectDiagnostics` and `PhysicalDestroyDeform`: one additional optional
  evidence sample per owner episode; physical chain/stone or reinforced/stone
  target pair takes priority. Report retains source tick, Plot UUIDs, both
  block targets, existing final reason and complete category counters.
- Explicit **absence** of Sable manifold ID / per-native-record substep ID.
  A Rapier post-effect record ordinal is NOT a manifold ID or exact solver
  contact and is never promoted into exact damage input.
- Tests added for diagnostic/guard parity, opposing body normals, conflicting
  records and every-pre-step rejection, laziness and targeted block preference.

## Runtime flags

```text
-Dpdd.proxy.enabled=true
-Dpdd.proxy.factor=0.5
-Dpdd.proxy.stepCap=100
-Dpdd.debugCollisions=true
-Dpdd.whyReject.enabled=true
-Dpdd.moreRog.enabled=true
```

All proxy values are explicitly configured test settings, NOT physical units.
`pdd.moreRog.enabled` defaults false. `worldMutation=OFF` regardless.

## Boundaries

No changes to Sable/NeoForge/Gradle Wrapper/JUnit/Actions, original Sable
callbacks, P-16 approval/duplication/continuity rules, native 15-double field
layout, exact synthetic math or any destruction system. This is not an exact
rotational contact implementation: that requires reliably same-epoch native
contacts and is a separate 0.9.1 in-game acceptance criterion in IDS 1.0.3.

Validation, failure modes, and at most two reproducible in-game sessions:
`docs/verification/0.3.0-moreRog.md`.

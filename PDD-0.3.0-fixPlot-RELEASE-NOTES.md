# PDD 0.3.0 fixPlot — source delivery notes

Base GitHub `main`: `5e9b63172d29521ebdbf4185bef0a9a423c6498f`.
Normative spec: `docs/PDD-1.0.3-Integrated-Design-Specification.md` §5.2.2, §6.1 and §14.2 (`G-MATH-01`).

## Change relative to 0.3.0 FIX

The prior 0.3.0 FIX could approve `CONSERVATIVE_PROXY` budgets only from a VERIFIED full-cube static world face. Real Plot–Plot collisions had mass and global pre-solver velocity but no independent approach direction. This release adds a separately gated Plot–Plot normal path from **Sable 2.0.6's own Rapier manifold normals**, not from relative center positions.

- Native post-tick effect records are tagged with source dimension and game time; runtime Plot IDs are captured on *each pre-solver substep*. No new external dependency or alternative callback implementation.
- Both native manifold local normals are transformed independently through each body's pose, checked opposing and required stable across all matching substeps and native contacts. Arbitrarily swapped native collider order is normalized.
- Every pre-solver substep in the tick must have complete owner/pose/mass/velocity evidence, show positive translational approach and satisfy rotation guard at the audited target block. The **lowest** calculated proxy budget is chosen. No favorable-sample selection, midpoint normal, guesswork, re-used post-solver velocity or absolute callback scalar.
- Budget has `budgetKind=CONSERVATIVE_PROXY` and `source=NATIVE_PLOT_PAIR_OPPOSING_NORMAL_ALL_PRE_SUBSTEPS_MIN_GAME_PROXY`. Exact contact point and force are never claimed; missing or conflicting native data produces zero game budget.
- Original Sable callbacks, vanilla World–Plot face fallback, `G-COL-02` owner audit, budget ledger and anti-repeat guard stay intact. All world mutations remain OFF.

## Known restrictions

- Sable's `clearCollisions` is a post-solver/force-thresholded feed with no per-substep ID. It can miss ordinary contacts. `0.3.0` cannot claim an exact solver impulse or validate a unique substep from this source; all-substeps acceptance is deliberately restrictive.
- Native runtime body-pair normals are not validated individual **block** contact normals. They are only evidence for a body-pair/tick **game proxy**. Downstream block-level damage target distribution remains for milestone `0.3.1` and later.
- Real gameplay, full ModDevGradle/JUnit and Github Actions for this new release are **not verified** in the source authoring environment. The full original build contract and protected Apply ZIP workflow are preserved.
- The `MIN_OPPOSITE_COS` and `MIN_COMMON_COS` constants are input-evidence angular tolerances (minimum cosine 0.98), not damage-balancing coefficients. P-16 proxy `factor` and `stepCap` still require explicit user configuration.

Detailed validation and <=2 in-game sessions: `docs/verification/0.3.0-fixPlot.md`.

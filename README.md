# PDD 0.3.0 FIX — Create Aeronautics Physical Destruction and Deforming

Target: Minecraft Java 1.21.1 · NeoForge 21.1.x (build contract: 21.1.228) · Java 21 · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6.

**Normative design:** `docs/PDD-1.0.3-Integrated-Design-Specification.md`, §5.3–§6.4 and §14.2 (`G-MATH-01`). Base GitHub main commit: `1adf31dc33b19a7d7cb9bcef2b9b83718475165a`.

This is a **source ZIP**, not a JAR or a confirmed Gradle build. The actual test/verification record is in `docs/verification/0.3.0-fix.md` (the earlier `0.3.0.md` is historical).

## What this release implements

- Preserves Sable's callback result and existing `G-COL-02` owner/block audit. `NO_OTHER` is counted separately from failed live block lookups. Raw callback impact frames are explicitly **UNVERIFIED**, not assumed Plot-local from the owner.
- Captures immutable pre-solver Sable `MassData` mass, inverse mass, inverse inertia tensor, center of mass, pose and linear/angular velocity. The mass adapter rejects missing or non-unit-scale data rather than fabricating mass/inertia.
- `ImpactMath.exact`: validated contact point, world B-to-A normal, synchronized kinematics, `v_linear + omega×r`, and effective inverse normal mass. Results are intensity indicators, **not** claims about actual Sable solver impulse or SI joules.
- `ImpactMath.proxy`: separate translational game-budget calculation, independent validated approach direction, explicit P-16 factor and step cap. No inferred block-center normal or unverified `signedVelocity`.
- `ImpactEpisodeLedger`: one approved budget per dimension + pre-solver epoch + canonical body pair, with callback count independent target deduplication. This is a provisional read-only grouping for 0.3.0; 0.3.1 implements final budget allocation.
- `ImpactLiveAudit` FIX: on a **live-audited full-collision-cube STATIC_WORLD block**, one Sable callback contact point may identify a single axis-aligned block face. A cheap six-face ambiguity/out-of-block gate infers a **proxy-only** B→A direction without any reciprocal point matching or exact contact location requirement. This source is `BLOCK_FACE_ESTIMATE`, **not** a validated Rapier normal. Plot-Plot, ambiguous corners, moving-only rotations or insufficient approaches remain unresolved.
- Sable 2.0.6 source-proven limitation: Rust `modify_solver_contacts` computes `impactVelocity` as `abs(normal relative velocity)`, so that field does **not** distinguish approach from separation and is never used to create a budget. Post-solver native effect contacts remain diagnostic-only.
- `ImpactContinuityGate` blocks repeat budget credit from resting/continuous owner-pair contacts, including multiple physics substeps; a contact-free full tick is required for a new credit. Multiple owner-pair proxies share a step cap. **No world mutation.**
- Structured `PDD-G-MATH-01` summary, bounded live pre-solver mass/velocity input samples, rejection reason counts, input quality and `approvedEpisodes`; older `G-COL-01/02` diagnostic logs remain. **No blocks are mutated and damage is disabled.**

## Proxy policy

P-16 is **off** by default. Optional explicit Java properties:

```text
-Dpdd.proxy.enabled=true
-Dpdd.proxy.factor=0.5
-Dpdd.proxy.stepCap=100.0
```

The above are **illustrative test-only numbers**, not endorsed physical units or balance settings. Merely enabling policy does not authorize all live impacts: a verified world full-cube face and same-step mass/velocity data remain mandatory. Collision-face estimate is an independently gated **proxy** direction, never an exact Rapier contact normal. Invalid/missing policy numbers do not crash initialization; they reject proxy approval.

## Existing Apply ZIP contract

Upload this entire ZIP as **the sole file** in GitHub repository `incoming/`. The existing `Apply PDD ZIP` action preserves `.github/`, `.git/`, `incoming/`, `gradlew`, `gradlew.bat`, and `gradle/` from the GitHub checkout. The ZIP excludes those protected paths and includes all other project source, tests and documentation. Before upload, compare GitHub `main` SHA with the base SHA above; Apply overwrites unprotected repository files.

After Apply, run the existing `PDD Manual Build` and `PDD Callback Contract Test`. The protected build workflow uploads an artifact named `PDD-0.3.0`. The expected JAR inside is `physical-destroy-deform-0.3.0-fix.jar` (inspect the JAR manifest and mod metadata; do not mistake the artifact name for the JAR version).

## Validation

Run in a full GitHub checkout:

```sh
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

Local environment did not contain the protected Gradle wrapper or Gradle distribution; both commands were attempted and exited **127**. The Java 21 auxiliary compiler and JUnit-style assertion runner **do not** establish a successful official Gradle/JUnit build. See `docs/verification/0.3.0-fix.md` for the full matrix and two in-game test sessions.

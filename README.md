# PDD 0.3.0 — Create Aeronautics Physical Destruction and Deforming

Target: Minecraft Java 1.21.1 · NeoForge 21.1.x (build contract: 21.1.228) · Java 21 · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6.

**Normative design:** `docs/PDD-1.0.3-Integrated-Design-Specification.md`, §5.3–§6.4 and §14.2 (`G-MATH-01`). Base GitHub main commit: `32d80d3cd4fe3e3f027b3b6d0c2b649a8a794731`.

This is a **source ZIP**, not a JAR or a confirmed Gradle build. The actual test/verification record is in `docs/verification/0.3.0.md`.

## What this release implements

- Preserves Sable's callback result and existing `G-COL-02` owner/block audit. `NO_OTHER` is counted separately from failed live block lookups. Raw callback impact frames are explicitly **UNVERIFIED**, not assumed Plot-local from the owner.
- Captures immutable pre-solver Sable `MassData` mass, inverse mass, inverse inertia tensor, center of mass, pose and linear/angular velocity. The mass adapter rejects missing or non-unit-scale data rather than fabricating mass/inertia.
- `ImpactMath.exact`: validated contact point, world B-to-A normal, synchronized kinematics, `v_linear + omega×r`, and effective inverse normal mass. Results are intensity indicators, **not** claims about actual Sable solver impulse or SI joules.
- `ImpactMath.proxy`: separate translational game-budget calculation, independent validated approach direction, explicit P-16 factor and step cap. No inferred block-center normal or unverified `signedVelocity`.
- `ImpactEpisodeLedger`: one approved budget per dimension + pre-solver epoch + canonical body pair, with callback count independent target deduplication. This is a provisional read-only grouping for 0.3.0; 0.3.1 implements final budget allocation.
- `ImpactLiveAudit`: a real collision with a verified owner but *unverified native/callback geometry* is rejected with a reason. In this release **live contact normals and post-solver effect rows are not silently promoted to approved pre-solver input**. The exact/proxy positive paths are exercisable in deterministic JUnit tests, not yet proven in the game.
- Structured `PDD-G-MATH-01` summary, bounded live pre-solver mass/velocity input samples, rejection reason counts, input quality and `approvedEpisodes`; older `G-COL-01/02` diagnostic logs remain. **No blocks are mutated and damage is disabled.**

## Proxy policy

P-16 is **off** by default. Optional explicit Java properties:

```text
-Dpdd.proxy.enabled=true
-Dpdd.proxy.factor=0.5
-Dpdd.proxy.stepCap=100.0
```

The above are **illustrative test-only numbers**, not endorsed physical units or balance settings. Merely enabling policy does not authorize any live proxy impact: a verified, independent approach normal and same-step mass/velocity data remain mandatory. Invalid/missing policy numbers do not crash initialization; they reject proxy approval.

## Existing Apply ZIP contract

Upload this entire ZIP as **the sole file** in GitHub repository `incoming/`. The existing `Apply PDD ZIP` action preserves `.github/`, `.git/`, `incoming/`, `gradlew`, `gradlew.bat`, and `gradle/` from the GitHub checkout. The ZIP excludes those protected paths and includes all other project source, tests and documentation. Before upload, compare GitHub `main` SHA with the base SHA above; Apply overwrites unprotected repository files.

After Apply, run the existing `PDD Manual Build` and `PDD Callback Contract Test`. The existing manually-named artifact is still `PDD-0.2.13` **because its protected workflow is unchanged**; inspect `build/libs/physical-destroy-deform-0.3.0.jar` inside the resulting artifact to determine the actual version.

## Validation

Run in a full GitHub checkout:

```sh
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

Local environment did not contain protected Gradle wrapper or Gradle distribution; these commands were attempted and exited **127**. Independent Java compilation and auxiliary tests do **not** establish a successful Gradle build. See `docs/verification/0.3.0.md` for the full verification matrix and two in-game test procedures.

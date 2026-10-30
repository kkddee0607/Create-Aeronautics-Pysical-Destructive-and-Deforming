# PDD 0.2.13 — Create Aeronautics: Physical Destruction and Deforming

**Target:** Minecraft Java 1.21.1 · NeoForge 21.1.228 · Create 6.0.10 · Create Aeronautics bundled 1.3.2 · Sable 2.0.6 · Java 21.

**Normative design:** `docs/PDD-1.0.1-Integrated-Design-Specification.md`, §§ 3.2, 5.1–5.4, 14.2, 15.1 (`UT-FRAME-01/02`), `G-COL-02`.

**Base GitHub HEAD:** `b39f9c388f8c2050fdd13c8cf958d418834d68b8` (2026-10-30). This delivery is a complete **source archive**, not a tested binary or a GitHub commit.

## 0.2.13 implementation scope

- Sable physics **pre-solver** and **post-solver** hook points are bound to the confirmed Sable 2.0.6 call sites using `SableStepSnapshotMixin`. The pre hook copies immutable plot UUID/bounds, pose, and raw linear/angular velocity/mass center into a physics-step epoch before solver work. No physics result or Sable callback is replaced.
- `CollisionFrame` resolves each callback `hit` and `other` independently into **candidate** `STATIC_WORLD`, `SABLE_PLOT`, or `UNKNOWN` owner spaces. The raw callback coordinates may still use an unverified frame. Both hit/other transform hypotheses are preserved with their source body snapshots and provenance.
- `FrameMath` uses Sable `Pose3dc` position/rotationPoint/orientation/scale conventions. Position, vector and inverse-transpose normal transforms have separate explicit operations and deterministic tests.
- Missing Sable step, invalid/missing plot, ambiguous plot ownership and non-finite inputs produce `INVALID` diagnostics; unverified-but-numerically-valid frame candidates are `DIAGNOSTIC_ONLY`. **No candidate authorizes Minecraft world mutation.** The Sable `signedVelocity` remains uncalibrated.
- `[PDD-G-COL-02]` logs include per-sample epoch, dimension, time phase, both target owners, raw contact and two candidate world contact points, source body states and validity. Every 100 server ticks the summary reports pre/post events, unscoped callbacks and invalid bodies.
- Existing original-preserving collision decorator and callback-contract tests are maintained.

## Import the entire source ZIP through the existing Apply workflow

Upload the provided archive as the **only** ZIP under `incoming/` in the GitHub repository, provided `main` has not moved from the stated base SHA. The existing `Apply PDD ZIP` workflow syncs the entire *unprotected* project tree from the archive; it intentionally retains `.github`, `gradlew`, `gradlew.bat`, `gradle/`, `incoming/`, `.git`. This source ZIP deliberately excludes those protected GitHub-side paths, just like the supplied 0.2.12 full-source ZIP. Do not extract/upload only changed Java files.

If `main` has changed, examine the diff before invoking the destructive sync. The ZIP does not contain a Gradle wrapper distribution: the protected GitHub checkout provides it.

## CI (the existing workflow contracts remain unchanged)

In the actual GitHub checkout:

```sh
chmod +x gradlew
./gradlew test --no-daemon
./gradlew build --no-daemon
```

The existing `PDD Callback Contract Test` action still invokes `gradle test --tests "com.physicaldestroydeform.diagnostics.ObservingCollisionCallbackTest" --stacktrace` using Gradle 8.14.3. The `PDD Manual Build` workflow already uses the `PDD-0.2.13` JAR artifact name. All GitHub Actions YAML files are unchanged by this archive.

## G-COL-02 in-game evidence collection

Enable the existing `-Dpdd.debugCollisions=true` Java property for detailed collision observations; the property defaults to `true` in the 0.2.12 baseline. Search the server's `logs/latest.log` for `[PDD-G-COL-02]` and `[PDD-G-COL-01]` while performing all of these scenarios:

1. Ordinary ship/terrain collision: identify static world and Sable plot owners and compare world impact *hypotheses* with the visible contact.
2. Plot/plot collision: verify both stable plot UUIDs and independently transformed candidates.
3. Rotating boundary contact: compare raw angular velocity, pose, contact coordinates and epoch against observed contact.
4. Absent/invalid plot, nonfinite or wrong-epoch cases: check `INVALID` and zero world changes.

**Do not mark `G-COL-02` passed merely because INIT, PRE or raw contact logging appears.** Actual in-game scene correspondence is required and still not run. Once validated, record exact source/build hash, dependent JAR hashes and log evidence in `docs/verification/`.

This version does **not** implement 0.3.0 impact energy, 0.4.x damage, deformation, plot/world mutation, or damage persistence. Those remain on the fixed roadmap.


### 0.2.13 G-COL-02 coordinate correction

The hit callback point belongs to the hit block coordinate frame. `worldFromHit` is transformed once; `otherLocalFromWorld` is the same world point expressed in the other owner's native frame. Live diagnostics report independent reciprocal same-epoch pair agreement and emit a maximum of two callback and one native detail sample per 100 server ticks. World mutation remains disabled; see `docs/verification/0.2.13-g-col-02-hotfix.md`.

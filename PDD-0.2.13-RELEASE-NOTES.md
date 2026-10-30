# PDD 0.2.13 — Owner / coordinate / physics-epoch normalization

**Parent GitHub source:** `b39f9c388f8c2050fdd13c8cf958d418834d68b8` (2026-10-30). ZIP provided separately; no commit or official JAR release claimed.

**Scope:** PDD integrated design 1.0.1 §14.2, release `0.2.13` / G-COL-02 / UT-FRAME-01/02.

Implemented together:

- Reused and preserved the original Sable `BlockSubLevelCollisionCallback` decorator; added an immutable epoch/owner evidence attachment to `RawObservation` while preserving its original constructor, callback return/throw behavior and existing queue.
- Added the Sable 2.0.6-specific pre/post solver call-site hooks with `require=1` (fail-fast on ABI drift), using common Sable API types; NeoForge-only event class is not needed as a compile dependency.
- Captured per-epoch dimension, server tick, stable plot UUID and chunk bounds, Sable pose including rotation point and scale, raw linear/angular velocities and mass center. Kept pre and post snapshots separately.
- Resolved both `hit` and `other` owners independently into static world, plot or invalid/unknown, retaining the raw callback inputs. Computed **separately labelled hypotheses**, never asserted a contact frame not established by the in-game test.
- Added position/vector/normal/inverse transformations; rejected absent/malformed/non-finite source data. Validity remains `DIAGNOSTIC_ONLY` until G-COL-02.
- Added per-sample and summary `[PDD-G-COL-02]` server logs, with explicit zero-world-mutation guard.
- Updated Gradle build metadata, NeoForge metadata and README to 0.2.13. Build script task/dependency configuration and original GitHub Actions files are unchanged.
- Added 7 unit scenarios in `FrameMathTest` and `CollisionFrameTest`; original 6 callback contract scenarios are unchanged.

**Verification achieved:** pure Java compile, ABI descriptor checks using uploaded Sable 2.0.6, and independent Java compatibility-harness execution of 13 scenarios. The harness is not the official JUnit engine. **Not executed:** Gradle/JUnit CI, game loading, real collision acceptance, Apply ZIP GitHub action. These are marked NOT_RUN in the ledger; none is inferred PASS.

**Safety boundary:** No world mutation, no damage, no physical impulses, no guessed m/s conversion. The `G-COL-02` acceptance gate is pending real gameplay evidence. `0.3.0` remains the next stage after that acceptance.

## 0.2.13 G-COL-02 coordinate-frame correction (2026-10-30)

- Baseline: GitHub `main` `b09c1a3041f7a2fba69ca83201e9a3e525f371f4`.
- The 2026-10-30 live log showed reciprocal callback points converging near `(78.973, 63, -51.110)` when the hit owner's local/world frame is used, but the old `worldFromOther` mistakenly transformed the **hit-side** raw point using the other body's pose, producing millions-of-block coordinates. The incorrect hypothesis was diagnostic only; it did not mutate blocks.
- Compute the world point strictly in the **hit owner's** frame. Convert that result into the other owner's local space via the inverse pose (or leave unchanged for static world); never apply the other pose to the original raw point. Log `otherLocalFromWorld`, not a spurious independent world point.
- Verify independently reported reciprocal hit-side world points when two adjacent opposite-side callbacks share the same solver epoch and exact reversed block owners. Record `reciprocalPairs`, `matchingPairs`, and `mismatchedPairs` (0.25-block comparison radius) in periodic summaries. A mismatch is a diagnostic, not a reason to tamper with Sable's collision handling.
- Keep geometric plausibility as `DIAGNOSTIC_ONLY`; do not claim the raw Sable ABI is fully validated or enable world mutation until the in-game acceptance gate is passed.
- Throttle detailed raw/native samples to at most two callback samples and one native sample per 100 server ticks while preserving all existing full capture counters, consumer dispatch and Sable callback semantics. The counts and `[PDD-G-COL-02]` summaries are emitted even when detailed logging is disabled.
- No Gradle, Java, NeoForge, JUnit, GitHub Actions, or Apply ZIP contract changes. No new dependencies.
- Added reciprocal-frame unit tests including the large plot coordinate regime; run `./gradlew clean test --no-daemon` and `./gradlew build --no-daemon` on GitHub. Builds and CI for **this corrected archive** remain pending until actually executed.

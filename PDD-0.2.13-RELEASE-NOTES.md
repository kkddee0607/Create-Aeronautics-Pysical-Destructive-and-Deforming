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

## G-COL-02 final functional implementation — second 2026-10-30 correction

**Source baseline:** GitHub `main` `348f6a44d863381d86188f2e5763f01f7154bbe4` (the applied previous coordinate ZIP). **This is a source ZIP for Apply; it is not a verified published JAR.**

- The earlier hotfix left **every collision** as `DIAGNOSTIC_ONLY` and treated geometrically impossible callback points as mere diagnostic candidates. That was not a functional resolution of the reported defect.
- `CollisionFrame.Snapshot.observe` now rejects hit-side and other-side contact points that lie outside the respective block collision neighborhood (`CONTACT_OUTSIDE_OWNER_BOUNDS`), and non-pre-solver events (`NOT_PRE_SOLVER`). Never re-interpret a rejected plot-local point as a world point to force a match.
- New `ContactPairMatcher` joins only **independent** opposite-owner callback samples with the same dimension, tick and physics epoch and world-space contact distance <=0.25 blocks. Spatial buckets avoid an all-to-all scan. Arbitrary adjacent events do not count as reciprocal contacts.
- Only those independently matched samples become `PRE_SOLVER_VALIDATED` and are dispatched downstream, including `HIT_PLOT_LOCAL_VALIDATED` or `HIT_STATIC_WORLD_VALIDATED` frame labels; unmatched samples remain `DIAGNOSTIC_ONLY` and malformed ones become `INVALID`.
- Summary reports `validatedPairs`, breakdown `worldPlotPairs`, `plotPlotPairs`, `worldWorldPairs`, `unpairedContacts`, `invalidContacts`. The old `mismatchedPairs` count was misleading (adjacent samples were not necessarily the same contact).
- Logs preserve raw signed velocity units, unaltered Sable callback results and zero world mutation. The native effects feed remains separate and `UNVERIFIED` by design. Actual damage/energy accounting belong to later design versions.
- `ContactPairMatcherTest` adds five direct regression scenarios, including **the exact type of plot→plot false coordinate observed in the game log**, rotating independent plots, interleaved callbacks, different epochs, and valid world↔plot pairs. Previous callback-contract tests remain intact.
- Official `./gradlew clean test` and `./gradlew build`, GitHub Actions on this new ZIP, and in-game G-COL-02 are **NOT_RUN** here. Java 21 auxiliary source-stub and frame tests are *not* Gradle/JUnit substitutes.

---

## IDS 1.0.2 ownership-normalization revision (2026-10-30)

- Source baseline: GitHub `main` `18ca022e6e4deb0b1280361b7a29c457a62d3982`.
- Owner-local targets are now audited against Sable's actual plot mapping and live block storage on the server tick. TargetKey identifies `(dimension, STATIC_WORLD|SABLE_PLOT, stablePlotId, owner-local BlockPos, blockstate fingerprint)` for future world/plot damage adapters; no mutation or damage occurs in 0.2.13.
- Bad native contact coordinates no longer invalidate an otherwise valid owner target. 0.25-block reciprocal proximity never promotes owner or contact geometry quality. Multiple same-owner contacts without manifold IDs are `PAIR_AMBIGUOUS` instead of arbitrarily paired.
- Original callback invocation, exact return object, native feed and safe no-damage behavior unchanged. All 1.0.1 design features outside the 0.2.13 scope remain future milestones unchanged.
- **Build status: NOT VERIFIED.** This execution environment lacks GitHub-protected `gradlew`/wrapper and local Gradle; `clean test`, `build`, GitHub Actions and in-game G-COL-02 were NOT completed. See `docs/verification/0.2.13-ids-1.0.2-owner-target.md` for complete evidence and ≤2 in-game test sessions.

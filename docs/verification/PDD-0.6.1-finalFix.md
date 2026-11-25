# PDD 0.6.1-finalFix — contact surface component certification

- Base GitHub repository: `kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming`
- Base `main`: `1b0e03cccb7fc48752e98918f8ab7849cd05ebf6` (393 original blobs, no truncated tree).
- Reference: `.github/workflows/PDD-1.0.9-Integrated-Design-Specification.md` and user-approved post-originFix remediation plan.
- Existing source interfaces, F/Q formulas, stress, material constants, native Sable callback, vanilla/Plot setter ordering, persistence schema and WAL fsync unchanged.

## Root cause and precise corrections

1. In `SableContactRegions.audit`, `completeSurface` previously required **no saturated Native record**, **no rejected frame**, and **no geometry-budget rejection** before promoting any complete bilateral shape matches. This was a global veto: a capped Sable sample (100 effects) or an unrelated imperfect frame could erase otherwise certified individual contact faces. The new `certifiedSurface` is true when `mergeBilateralPairs` actually incorporates all **fully verified component matches** into the original evidence ledger; it is **not** a claim that the complete native manifold was observed. `completeSurface` remains a diagnostic whole-event status and is false on capped records. Cap saturation still denies unverified directional movement via unchanged TargetDirections / VerifiedContactGraph rules.
2. `BilateralContactSurface.reconstructAll` formerly treated all anchors in an identical pose group as one all-or-nothing flood fill; any budget failure discarded all discovered matches. `Search` now fully closes and commits a component before trying the next Native anchor. Earlier closed components remain valid when later components exhaust shape/geometry or pair limits, whereas **no matches from the incomplete component** enter the ledger. Already completed pair keys short-circuit repeated anchor traversals. Verified contact pairs still require positive physical area, both owner-local VoxelShapes, both exterior-face checks, valid owner epoch and same record's Pose.
3. Preliminary Native normal/witness gates now generate *candidates* within broader numerical limits (world-normal dot ≤ −0.80, dominant local axis ≥ 0.75, source witness delta norm by axis ≤ 1/16 block). These are **not** move-direction authorization. Actual face approval still requires verified opposite geometry with face opposition dot ≤ −0.98, positive clipped area, own-face exposure, no >1/32 block penetration and no positive gap greater than `EPS=1e-5`. A geometry-unknown cell makes the active component incomplete rather than an apparent air boundary. These revised candidate limits are new experimental policy, not measured Sable solver error bounds; benchmark and tune only after DEBUG evidence.
4. For mixed valid/invalid frames, good geometry can now issue F only from **independently closed** components. Native unpaired seeds and not-yet-proved components remain in the existing unresolved-F logic. The old `mergeBilateralPairs` limit/owner/material-compatibility checks and ledger's one-credit F/Q accounting remain untouched; no force is minted or redistributed to other sides.
5. Existing `MAX_CANDIDATE_CELLS=64`, `RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE=32768`, `MAX_TARGETS_PER_SIDE=16384`, WAL/Stress budgets and schema are unchanged. In particular **Sable's 100 native-effect limit cannot be raised from this Java mod**. This change makes its saturation less destructive to independently proven PDD surface components; it cannot reconstruct an unobserved independent collision event or claim exact per-block Rapier solver forces.

## Files

Modified: `src/main/java/com/physicaldestroydeform/collision/BilateralContactSurface.java`, `src/main/java/com/physicaldestroydeform/collision/SableContactRegions.java`, `src/main/java/com/physicaldestroydeform/PhysicalDestroyDeform.java`, `src/main/resources/META-INF/neoforge.mods.toml`, `build.gradle`, `README.md`, `scripts/check-duperfix-contract.py`.

Added: `src/test/java/com/physicaldestroydeform/collision/FinalFixContactSurfaceTest.java`, this document. No pre-existing file deleted. Original 800 JUnit @Test declarations retained; **7 new declarations, total 807**. Test expectations in the previous 800 are unchanged.

## Static verification vs real verification

- AI performs Java 21 parser validation (not full Java linkage), independent Java 21 stub-backed compilation of modified `BilateralContactSurface` and the new test class, and independent Java execution of geometry/budget/component scenarios. The standalone JUnit-free simulation is not JUnit or Gradle, and it does not verify runtime NeoForge classes or a real Sable server.
- User must run `python3 scripts/check-duperfix-contract.py`, `python3 scripts/check-superfix-assertions.py`, `./gradlew clean test --no-daemon`, then `./gradlew build --no-daemon` on Java 21. Verify 807 JUnit tests and JAR `physical-destroy-deform-0.6.1-finalFix.jar`, then existing Build, Callback Contract Test, Apply workflows per repository policy.
- Deployment: create exactly one root-level full project ZIP under `incoming/` and use existing Apply. Do not overwrite protected `.github`, `incoming`, `gradlew`, `gradlew.bat`, or `gradle/`. Keep the prior world backup and install just one PDD JAR.

## In-game tests, maximum two sessions

**Test 1 — certified damage surface:** One 5×5 or 10×10 flat A/B face, a face with a central gap, and two disconnected Native-anchored faces. Observe actual Layer 0 F per block, `CONTACT_UNPROVED`, `unresolvedF`, `[PDD-CONTACT-PATCH] completeSurface / certifiedComponentsPresent / nativeCapSaturated`, `bilateralCertifiedA/B`, F/Q conservation and 100-record saturation. A complete independent certified component must still be used even when the **event** is telemetry-saturated; an incomplete component must not be promoted. Noncontact cells must never receive F.

**Test 2 — regression and performance:** Rotated Plot/World, partial/copycat shapes, multi-Plot collisions, save/restart, rejected unknown/unloaded cells. With diagnostics disabled on an identically staged benchmark, compare R2, F propagation, Bridge, Stress, World/Plot Commit P50/P95/P99, TPS, `Can't keep up!`, heap, and `PDD-JOURNAL-IO`. F/Q conservation, receipt/CAS/drops and clean storage are mandatory; do not assume fewer solver records means a performance win.

## Known limits and explicitly unverified objectives

- Exact per-block native Rapier F and the *missing* independent collisions beyond Sable's native cap are not obtainable from the existing Java ABI.
- Contact scanning and durable WAL fsync are still synchronous; no safe asynchronous WAL writer, checkpoint architecture, or tick-resumable immutable physical snapshot was implemented. Consequently zero lag is **not promised**.
- Only real user Gradle/JUnit, Actions, and in-game logs can establish build success and behavioral/performance correctness. Do not change failing JUnit expectation numbers just to force green status.

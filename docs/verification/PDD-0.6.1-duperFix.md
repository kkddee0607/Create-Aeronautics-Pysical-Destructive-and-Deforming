# PDD 0.6.1 duperFix — surface recovery and verification

## Baseline / status

- GitHub main: `e611793adc98042c34149516f1b8eb5adbb2bb6b` (2026-11-25).
- Original source: `PDD-0.6.1-superFix-buildFix2-FULL-SOURCE.zip`, 384 tracked files; byte-for-byte Git Blob/mode manifest matches GitHub main.
- Applicable spec: `.github/workflows/PDD-1.0.9-Integrated-Design-Specification.md` (same SHA256 as provided user design document).
- Sable: `sable-neoforge-1.21.1-2.0.6.jar`, nested Rapier collider JAR inspected. Additional dependencies or build workflow edits: **none**.
- This is AI static/audited-source work only. `compileJava`, `compileTestJava`, Gradle/JUnit, Actions and in-game behavior have **not** been executed by the AI.

## Root cause and remedy

Prior `ContactFacePatch` requires 3 coherent native points, its strict `TargetDirections.patchFrames()` cross-record pose/delta coherence commonly rejects moving source records. Those functions remain available (with their strict direction contract), but they are no longer the only avenue for verifying game damage surface material correspondence.

`BilateralContactSurface` takes **one real native effect record as the seed**, retaining the existing episode/credit authority. It validates that record's two IDs, owner identity, effect-phase poses, approximately opposing normals, relative depth and position. It then reads only valid owner-local voxels via `OwnerResolver.NativeAuditBatch`, reconstructs the actual opposing Sable collision faces, projects them to a common world plane and clips their convex face polygons. A **strict positive intersection area, opposing face normal, bounded penetration, exterior face and connected tangent seam** are required per new cell pair. A failure, missing chunk, stale owner or exhausted event budget creates **no new paid target**. It never changes Sable's solver/callbacks or mints a new F/Q event.

The read-only `SableEffectShapeReader` reproduces verified `RapierVoxelColliderBakery` source behavior with the Sable collision context and isolated `BlockGetter` (without referring to nested Rapier classes on the compile classpath). State-liquid/kelp uses full-cube geometry as observed in `VoxelNeighborhoodState.isLiquid`. Material snapshot remains the original PDD block/Copycats+ resolver, not the collision shape.

`SableContactRegions.mergeBilateralPairs` keeps existing exact native Regions intact and upgrades or adds *singleton* Region `opposingCandidates` only if both independently checked targets exist. Ambiguous (>8) candidates on either side are not truncated. Original per-side target limits, immutable credit F/Q split, Q mapping, World/Plot mutation, WAL/fsync, stress, damage, movement direction, callback order and dedup contracts remain unchanged. New surface correspondences **never authorize** deformation direction or Cross-owner propagation edges.

Potential limitations: Sable's 15-double post-effect ABI omits per-contact solver substep/collider identity. Records with inconsistent effect poses, unknown owner, large anchor drift, non-unit scale or oblique/noncoplanar face contact remain safely unresolved. This release does not provide guaranteed exact native manifold recovery for arbitrary high-speed rotating bodies. Event-scoped geometry scans may still be CPU expensive at pathological sizes; each scan uses the existing audited-cell/region budgets and an independent bounded pair-work budget.

## Source and tests

Modified originals: `OwnerResolver.java`, `SableContactRegions.java`, `PhysicalDestroyDeform.java`, `SuperFixBilateralSurfaceTest.java` (clarification only), `build.gradle`, `scripts/check-superfix-assertions.py`.

Added: `BilateralContactSurface.java`, `SableEffectShapeReader.java`, `DuperFixBilateralSurfaceTest.java`, this document.

Preserved: all pre-existing Java test declarations (781), resource fixtures, Wrapper, settings, materials, `.github/workflows` including Build / Callback Contract / Apply.

New `DuperFixBilateralSurfaceTest` contains 11 `@Test` methods covering: a single Native anchor to 25-cell bilateral face; 24 cells with a hole; 9 real cells on a smaller opposite plane; internal seam rejection; both bodies rotated 45 degrees; stale Native time; small positive gap exclusion; valid shallow penetration; discontinuous partial-box face; single original Credit and 25/25 layer-0 F owners; truncated-budget quarantine. Total expected static `@Test` declaration count: **792**. This is NOT a claim about actual passing tests.

Existing superFix hull-based tests remain **strict** for their legacy proof and do not assert that a single witness must fail the newly introduced independent proof. Physical constants and historical test expectations were not silently rebalanced.

### AI static audit / failure lessons

- Java 21 parse-only compiler API: all main/test source units parsed; no syntax errors.
- Independently compiled `BilateralContactSurface`/`FrameMath` with verified-type-compatible minimal stubs; independently compiled `SableEffectShapeReader` against checked Sable API signatures; independently type-checked `mergeBilateralPairs`. These checks are **not** the real NeoForge compileClasspath.
- Found and corrected a Java illegal `var` compound declaration during that audit. Lambda-captured loop variables and original `migrated()` API are preserved.
- Existing static assertion contradiction guard now checks at least 791 tests; missing/invalid tests still require real `compileTestJava`/JUnit.
- Original file path, byte, Blob SHA, mode and Apply protected paths checked before packaging.

## User Gradle / Actions

1. Upload exactly one full project ZIP under GitHub `incoming/` and run the existing Apply workflow. Never change `.github` or Wrapper from the ZIP.
2. Run `./gradlew clean test --no-daemon` and `./gradlew build --no-daemon` on Java 21; examine `compileJava`, `compileTestJava`, test reports, processResources and jar.
3. Inspect Manual Build and Callback Contract Test Actions. JAR classifier should be `physical-destroy-deform-0.6.1-duperFix.jar`.
4. Send the **first failing error**, compilation stack or `build/test-results/test` XML if anything fails; do not adjust expected physical constants to hide defects.

## In-game test A — area recovery (one session)

Back up the world. Run `-Dpdd.diagnostics.mode=DEBUG` for one or more 5x5 and 10x10 facing Plot/World or Plot/Plot full-cube impacts, preferably with limited Native contact points. Verify actual Layer-0 F receipt targets across **internal and edge cells**; not all contacted cells must break because F is split across more blocks. Verify 1 Credit/episode, no duplicate World/Plot receipt, and F/Q ledger PASS. In the same Credit compare `[PDD-CONTACT-PATCH] contactPatch=... bilateralPairs=... bilateralCertifiedA=... bilateralCertifiedB=... surfaceChecks=...` with `CONTACT_UNPROVED`, layer-0 distribution and Commit result. `bilateralCertifiedA/B` measures shape-evidenced targets, not successful damage.

## In-game test B — false contact / persistence (one session)

Impact partly empty slabs/stairs/Copycat shape, rotated Plot, two nearby non-touching faces and truly corner-only shapes. Empty/internally obscured cells, unknown owner generations and unloaded chunks must receive **no invented game F**. Save, exit and reload: previous damage, target fingerprints, ownership, receipt counts and WAL recovery must stay consistent, with no duplicate drops or callback execution. Capture PDD debug contact logs, F/Q ledger, World/Plot commit outcomes and first exception if any.

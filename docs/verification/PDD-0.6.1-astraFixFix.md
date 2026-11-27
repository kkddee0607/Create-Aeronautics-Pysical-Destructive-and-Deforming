# PDD 0.6.1-astraFixFix — development and verification report

## Scope / baseline
- main `e92f9f56e19a4d9b69a494eff3365ecf20f46145`; tree `4e24a13bcc68f130e849c97890ccfd428f16f4c7`.
- Source restored from prior full ZIP; SHA-256 `0bad7314143d98512b59c898ebee48102831526c37bbe757f451138220512b1a`; all 420 original blobs, total 5,555,869 bytes, verified via Git tree hash. No remote writes or workflow triggers.
- Target JAR/API remains Sable 2.0.6, Create 6.0.10, Aeronautics 1.3.2, Copycats+ 3.0.11. Read-only inspection of installed JAR entries and javap LevelPlot allocation/accessor methods and Copycat material getters. No new ABI, dependencies, callback or save schema changes. Actual NeoForge compile/link not established.

## Implemented in this candidate
- JVM opt-in categories: `-Dpdd.logs=contact,force,stress` (case insensitive comma-separated); `off` or no flag defaults to no routine collision/damage/break INFO traces. Channels: native,contact,force,damage,stress,deform,commit,copycat,lineage,storage,network,perf; `all` explicitly enables all. Old explicit `-Dpdd.rog.enabled=true` enables all when pdd.logs is absent. `pdd.rog.enabled=false` suppresses the ROG stream separately. Safety errors/quarantine and startup/shutdown notices remain. External Sable/Minecraft logs unaffected.
- Routine ROG samples counted without detail by default; `-Dpdd.diagnostics.mode=TRACE` enables sampled detail, or select `-Dpdd.rog.traceEpisode=45`. Raw Native records require native channel plus TRACE/selected episode. Contact/force seed coordinates and effective F available without raw Native spam, up to configured bounds. Defaults 64 ROG lines/tick, 32768 chars/tick, seedLimit 128; separate legacy INFO cap normally 16 lines/tick and five/channel, where server ROG state exists. Periodic summaries and errors have their own behavior; not a total process byte cap. Category `damage` covers R07 and material logs; `commit` covers actual damage/break plans and receipts (R09/R10). Explicit trace can still be large. Legacy flags are supported only as documented above; explicit `pdd.logs` takes precedence over category selection.
- `ContactFacePatch`: preserves original successful fit, attempts at most 64 deterministic leave-one-out models on failed full fit. At least four distinct matching anchors in both charts and strict majority required, competing equally supported distinct sets rejected. Rebuilds both hulls only from inliers and reuses original exterior/connected component checks, no broader tolerance. Unsupported anchors remain unresolved. This is deliberately a single-outlier fallback, not arbitrary multi-model RANSAC. Patch outcome counters appear in CONTACT_COVERAGE. Exact path has a protected 25% remaining cell allowance for patch if a poll contains >=3 records, shared hard per-side limit unchanged; the fraction is a new tuning decision, not measured optimum. Existing final F/Q allocation and same-source refinement retained.
- `StressRuntime`: validation cursor scans remaining cells even after a change, accumulates invalidated set, then retires/replays once per completed validation pass. Temporarily unavailable observations cannot authorize a seal; retry/backoff under original TTL. Existing DENIED sink, prefix and partition verification, original ApplyId, reservation and residual parking retained. Later mutations may still trigger another replay; full incremental dependency graph not introduced.
- Legacy INFO and ordinary contact-budget/hold logs gated before formatting. Quarantine result warnings and F/Q invariant failures remain. Perf channel adds direct/mapped JVM buffer metrics (not total native RSS). Existing bounded Receipt index/cache, HeatMap denial cache and journal/lineage safety contracts retained; avoid speculative data deletion or external packet edits.

## Tests / static checks
- 856 -> 864 @Test declarations, 136 test files; 8 added, 0 removed/disabled. Original assertions retained. New tests: two contact outlier cases, 129-cell retirement and Q, three log-selection cases, one legacy budget case, one terminal receipt no-reissue on older clock. Gradle test JVM explicitly selects contact,force,commit so existing trace/no-trace comparison fixtures exercise enabled paths; production defaults unchanged. JUnit XML/summary retained, Java 21/NeoForge 21.1.228/JUnit 5.11.4 unchanged. Fixed source count guard and contact routing static guard updated.
- JavacTask.parse Java 21: 268 source files, zero syntax errors. Limited javac type check of real LogSelection, DiagnosticMode, RogTrace, RuntimeDiagnostics using SLF4J signature-only stubs (not shipped). Static scripts `check-duperfix-contract.py`, `check-superfix-assertions.py` passed. Actual Gradle, JUnit, Actions and game tests were NOT run. Full production/test type resolution not performed.
- Virtual checks: four corners + one displaced correspondence leaves four matching independent anchors, rebuilds original hull; 4-point inconsistent set cannot qualify; DENIED retains original directional edge weight; unknown scan cannot reach seal; stale published prefix still rejects; cap=64 yields legacy max16 and per-channel5.

## Remaining limitations / no completion claim
- No proven exact native substep; multi-outlier/multiple competing chart recovery still unresolved. Existing F reservation formula unchanged except existing source-specific refinement benefits from added valid patches. No full independent-path incremental Stress engine; batching reduces repeated validation-driven replay but persistent concurrent changes can still trigger replays. No new owner-wide scheduling beyond existing HeatMap denial cache, no new receipt compression, no atomic Minecraft world/PDD WAL checkpoint or retrospective terminal receipt scan. Existing safe recovery rules and new regression preserve terminal receipt no-reissue; exact rollback consistency not established. No Android logcat or native RSS; crash cause remains unconfirmed. Real performance and log-size reduction unmeasured. This is a full-source **verification candidate**, not a claim all previous improvement requirements or 0.6.1 exit gates are complete.

## User test
`./gradlew clean test --no-daemon` and `./gradlew build --no-daemon`; then existing Actions Build/Callback Contract. Apply via incoming single ZIP, protected paths unchanged.
Session A: `-Dpdd.logs=contact,force,stress,perf -Dpdd.rog.maxPerTick=128 -Dpdd.rog.maxCharsPerTick=65536 -Dpdd.rog.seedLimit=128` ; 3x3/5x5 three repeats, 9x9, ring, actual corner, tilted, Plot/Plot and reimpact; verify CONTACT_COVERAGE patchOutcomes, CONTACT_SEED_COORDS, CONTACT_SEED_EFFECTIVE, F_CREDIT_SUMMARY, STRESS_PARTIAL_REPLAY changedCount and originalDeadline, no invariant FAIL. Let TTL expire. If needed select an episode for raw detail with native channel. Session B: `-Dpdd.logs=commit,damage,deform,copycat,lineage,storage,stress,perf`; movement/break/drop, BE/glue, Copycat revisions, split/merge, save/restart; compare original deadline/ApplyId and readback. In both sessions retain startup selected channel list and full logs. Normal operation remove JVM log options or use `-Dpdd.logs=off`. If crash, preserve Android logcat, launcher exit code, JVM hs_err. Never run old and new PDD JAR together.

## File inventory changes
- modified: `README.md`
- modified: `build.gradle`
- modified: `scripts/check-duperfix-contract.py`
- modified: `scripts/check-superfix-assertions.py`
- modified: `src/main/java/com/physicaldestroydeform/PhysicalDestroyDeform.java`
- modified: `src/main/java/com/physicaldestroydeform/collision/ContactCreditLedger.java`
- modified: `src/main/java/com/physicaldestroydeform/collision/ContactFacePatch.java`
- modified: `src/main/java/com/physicaldestroydeform/collision/ContactPoseDiagnostics.java`
- modified: `src/main/java/com/physicaldestroydeform/collision/OwnerResolver.java`
- modified: `src/main/java/com/physicaldestroydeform/collision/RealFixRuntimeBridge.java`
- modified: `src/main/java/com/physicaldestroydeform/collision/SableContactRegions.java`
- modified: `src/main/java/com/physicaldestroydeform/collision/SableFrameCapture.java`
- modified: `src/main/java/com/physicaldestroydeform/deform/MoveRuntime.java`
- modified: `src/main/java/com/physicaldestroydeform/deform/MoveTransactionExecutor.java`
- modified: `src/main/java/com/physicaldestroydeform/diagnostics/AcceptanceReport.java`
- modified: `src/main/java/com/physicaldestroydeform/diagnostics/DiagnosticMode.java`
- modified: `src/main/java/com/physicaldestroydeform/diagnostics/PddDiagnostics.java`
- modified: `src/main/java/com/physicaldestroydeform/diagnostics/RogTrace.java`
- modified: `src/main/java/com/physicaldestroydeform/diagnostics/RuntimeDiagnostics.java`
- modified: `src/main/java/com/physicaldestroydeform/material/MaterialRegistry.java`
- modified: `src/main/java/com/physicaldestroydeform/material/TargetMaterials.java`
- modified: `src/main/java/com/physicaldestroydeform/plot/NeoForgePlotGateway.java`
- modified: `src/main/java/com/physicaldestroydeform/plot/PlotCommitService.java`
- modified: `src/main/java/com/physicaldestroydeform/plot/PlotPhysicsProbe.java`
- modified: `src/main/java/com/physicaldestroydeform/stress/StressRuntime.java`
- modified: `src/main/java/com/physicaldestroydeform/world/LineageRecovery.java`
- modified: `src/main/java/com/physicaldestroydeform/world/NativeLineageTransfer.java`
- modified: `src/main/java/com/physicaldestroydeform/world/NeoForgeWorldGateway.java`
- modified: `src/main/java/com/physicaldestroydeform/world/WorldCommitService.java`
- modified: `src/main/resources/META-INF/neoforge.mods.toml`
- modified: `src/test/java/com/physicaldestroydeform/collision/AstraFixContactPatchTest.java`
- modified: `src/test/java/com/physicaldestroydeform/diagnostics/RogTraceTest.java`
- modified: `src/test/java/com/physicaldestroydeform/stress/AstraFixStressRepairTest.java`
- modified: `src/test/java/com/physicaldestroydeform/stress/StressRuntimeProtocolTest.java`
- added: `src/main/java/com/physicaldestroydeform/diagnostics/LogSelection.java`
- added: `src/test/java/com/physicaldestroydeform/diagnostics/LogSelectionTest.java`
- added: `docs/verification/PDD-0.6.1-astraFixFix.md`
- deleted: none

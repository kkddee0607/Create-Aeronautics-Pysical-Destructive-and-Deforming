# PDD 0.6.0 Fix — evidence and verification

Baseline: main `6a4e289603d3c4eb2e00a6fa32a1a200ceeaf32b` (321 files, all baseline Git blob hashes matched). Design: integrated specification 1.0.9, especially target-specific deformation evidence and native contact saturation safety. Mod version remains 0.6.0; no save schema, callback or material table changes.

## Observed failure

The supplied latestlog.txt contains 19 `NO_MOVE_DIRECTION_UNKNOWN` decisions. Example: tick 1822, World dirt, `K=0.8125`, `normalFixed=17555`, `priorFixed=0`, `reservedQ=7.419600277700354`, `F=0.0`, `steps=[]`. For T=77 the fixed-point damage threshold is 19712 and the K threshold is 16016; 17555 falls between them, so a direction veto is material to this case. The logged zero F on that branch is unavailable evidence, not proof that the physical collision had zero force. The individual native witnesses cannot be reconstructed from the old log alone.

## Correction

- The original R2 shape audit also collects target-specific direction witnesses; no second native shape query. Each record independently validates opposing normals, source epoch and pre-solver approach. A uniquely facing target is not contaminated by a non-facing neighbour from the same contact.
- Conflicting, incomplete or ambiguous evidence remains unknown. Native saturation/incomplete events cannot authorize movement. The existing F/Q damage envelope is unchanged.
- Derived patch movement requires a strictly interior polygon cell, coplanar witnesses within the existing shape epsilon (1/32 block), unanimous signed single axes, nearly aligned normals, and that target's actual exterior collision face. Sparse halos, lines and oblique or discrepant planes remain damage-only.
- Propagated F children inherit consistent paid parent directions; unknown original seeds cannot be upgraded by propagation. No extra propagation solely to infer direction. When no seed is proved, the direction trace is skipped (optional diagnostic F tracing still works).
- F and delayed reserved Q retain separate accounting and lifetimes. Shared contact geometry is reused; no cross-tick graph caching or change to persistent stress reservations.
- `[PDD-DEFORM-EVIDENCE]` reports gate, direct and patch counts, ambiguous/missing faces, per-record normal reasons and authorized paid targets. `FSource=UNAVAILABLE` identifies missing direction/force evidence. Startup shows `revision=Fix`.

## External mod diagnostics

Sable 2.0.6's `ClientboundSableSnapshotDualPacket.handleClient` skips an entry whose target sublevel no longer exists. PDD does not cancel, modify or retry that packet; the existing read-only diagnostic is limited to 32 notices and disabled with `-Dpdd.traceSublevelPackets=false`. The log alone cannot identify whether a particular missing sublevel was removed, not tracked or delivered out of order.

Copycats+ 3.0.11 registers `copycat_catwalk` as an item referencing `COPYCAT_BOARD`; it is not a block registry entry. Its bundled `data/copycats/physics_block_properties/copycat_catwalk.json` targets that nonexistent block. `copycat_board.json` already contains the corresponding mass/volume (0.25/0.25). The upstream invalid resource must be corrected in Copycats+, not by registering a fake PDD block, suppressing the Sable error, or changing PDD materials. PDD's existing Copycats composition and NBT compatibility remain intact.

## Static checks

- 200 Java compilation units parsed using syntax-only Java parsing; zero syntax errors. No type-resolution compilation or actual Gradle/JUnit was executed.
- 536 existing `@Test` declarations retained; 6 new declarations (542 total). Existing patch coverage and force/damage expectations retained. New expectations: unique real face does not poison unrelated evidence; conflict/incomplete remains unknown; patch does not override unknown seed; only non-seed child inherits; pre-solver closing is required and native-cap/incomplete veto remains; non-coplanar damage patches cannot authorize movement. Additional assertions cover polygon interior and sparse/line exclusions.
- No Gradle dependency, build configuration or established numeric damage thresholds changed. Test expectations reflect the new evidence rules rather than weakening existing failure assertions.
- Actual compileJava, compileTestJava, JUnit, processResources, jar, clean test, build, Actions and game checks: NOT_RUN.

## User checks (two game sessions maximum)

1. Apply the full ZIP via the existing incoming workflow. Run `./gradlew clean test --no-daemon` and `./gradlew build --no-daemon` under Java 21, or the equivalent existing Build Test Action. Preserve the first failure and complete test report if any.
2. In a backed-up test world, collide a small Plot with a static wall/floor and with another Plot. Use a ductile material and a collision above K but below destruction, with an empty safe destination and connected remaining Plot; repeat with a solid blocker. Check `[PDD-DEFORM-EVIDENCE]` for `gate=AUDIT` and `authorized>0`; compare `[PDD-DEFORM]`, `[PDD-MOVE]` (result=APPLIED or explicit veto), `[PDD-DEFORM-Q]` and Plot `PLOT_POSTSTATE` after HeatMap. In the blocker case expect a verified blocked reserve settlement, not movement through a solid block. A native-cap event must still report `gate=NATIVE_CAP`, no authorized movement. Damage and Q remain available under the existing rules.
3. Second session: save/reopen the test world and repeat with a Copycats decorative block, then reproduce a Plot split/untrack/reload. Check persisted stress/Q recovery, Copycat snapshot material/revision, movement journal and absence of duplicate spend/mutation; correlate `[PDD-SUBLEVEL-NET]` with Plot lifecycle and Sable errors. No new packet suppression is expected. Keep complete latest.log plus crash report if any. Avoid a performance conclusion from mobile TPS alone.

Success requires actual build/Test Action success and confirmed in-game effects with no unsafe movement or double spend. Static source package alone is not that confirmation.

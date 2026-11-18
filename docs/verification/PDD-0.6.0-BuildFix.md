# PDD 0.6.0 BuildFix — World break cap regression

2026-11-18 · IDS 1.0.9 · version remains 0.6.0.

## Observed CI failure

- Main `5b52ab638575138019198bc75a8ea8d297683a24`, tree `f7a44a397172ac6d85574f36f8b16afb39f0602f`, all 320 baseline files matched the previous ZIP by Git blob SHA and size.
- Apply #98 succeeded; Manual Build #94 ([run](https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/actions/runs/44204631718)) failed at `:test`, job 134467301777. `createMinecraftArtifacts`, `compileJava`, `processResources`, `compileTestJava` and `testClasses` completed. 535 tests completed, 2 failed. JAR build/upload skipped.
- Failed: `WorldCommitIntegrationTest.perTickBreakCapDefersAndLaterProcesses` (line 174), `baselineStandaloneRegressionsRemainApplicable` (line 202, call to `WorldCommitCoreSelfTest.main`). The job log does not print the wrapped assertion message for the second test.

## Root-cause trace and correction

1. The old fake `breakVanillaOnce(A)` assigned a world-wide `air=true`. Reading B then returned air too, so the production preflight correctly terminalized B as `EMPTY_OR_UNLOADED` rather than deferring a real occupied block. Queue size 0, expected 1. Both fakes now track broken `SparseStateKey` cells independently; the explicit forced-air toggle remains for recovery cases. The same fake instance is reused between ticks to retain physical state.
2. `WorldCommitCore` shares a break budget across calls with the same server tick (including `settleBlockedMove`). The standalone allow-list loop previously called tick 108 three times with cap 1, expecting all three blocks to break immediately. The first uses the budget; the second must defer, even though it is an eligible block. The allow-list test now advances ticks 108/109/110 and subsequent unload/replay uses 111/112. The separate cap regression explicitly checks repeated calls at tick 114, then progression at 115.
3. New JUnit regression `repeatedCallsInSameTickCannotResetBreakCapOrLoseDurableQ`: persistent journal, two distinct occupied cells, cap 1; first call at tick 201 breaks A, next at the same tick yields `BREAK_CAP` for B, durable B receipt and sealed intent unchanged. Reopen journal; B remains queued exactly once, duplicate submit rejected, tick 202 breaks B once, terminal receipt APPLIED and duplicate submit rejected. No new Q issuance.

No production Java, runtime resources, Gradle, workflows or design constants changed. Tests were not removed, skipped or made less strict. Existing numeric assertions and limit are unchanged. No external mod API changes.

## Static numeric trace (not a JUnit run)

Stone profile H=59, T=91, D=10%, E=9%. Defaults give hardness factor ~0.699957, elasticity retention ~0.887872, direct share .60, gain 2.6. Q=.5 yields 124 fixed-point units; Q=.75 yields 186. Q=500 yields 484 whole damage (>91); Q=1500 saturates the 1024 damage ceiling (>91). Thus both cap scenarios genuinely request breaks. After A, B must remain present, so its threshold plan is reached and the existing cap branch retains it. The next tick resets the counter, not the pending Q. No calibration edit is needed.

## Verification status

- Baseline actual CI: 533 non-failing of 535 reported tests; 2 failed. No claim that skipped JAR, Actions for this package or in-game tests passed.
- This package: 536 JUnit declarations, 1 new, 0 removed; AI reviewed control flow, test inputs/expectations, persistent pending round-trip and same-tick budget. Actual Gradle/JUnit/Actions/game execution remains user-owned and NOT_RUN for the modified source.
- Run Apply using only this ZIP in `incoming/`, then PDD Manual Build. Expected regression outcomes: both previously failed JUnit methods and new durable cap test pass; actual total/failures must be verified from the new Actions log. In case of failure, provide full stack trace and test name. No in-game verification is replaced by this test correction.

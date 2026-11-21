# PDD 0.6.0 moreFix — verification and deployment

## Baseline and purpose

- Repository: `kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming`.
- Fix4 main: `8a72c2f7e60525af07ec9331a13d67dd5bab3496`. All 336 repository blobs and file modes matched the recovered baseline. Integrated design specification 1.0.9 matched the project attachment.
- Minecraft 1.21.1, Java 21, NeoForge 21.1.228, Sable 2.0.6, Create 6.0.10, Aeronautics 1.3.2; optional Copycats+ 3.0.11. No new external API or dependency.
- Preserve original damage/elasticity/deformation equations, fixed-point scale, Sable solver and callbacks, owner/instance/HeatMap/material CAS, reservation identity, shared 512 work limit, 128 derived nodes, depth 4, initial 1200 game-tick deadline, and 32768 F-side limit. No Q reissue or deadline extension.

## Observed Fix4 evidence

- A restored reservation called the normal stress handoff with `gameTick < createdGameTick`; `stress game clock rollback` propagated to the global stress runtime HALT. 62 subsequent offers were rejected. A subsequent world startup processed stress normally, so a permanently defective collision formula is not established.
- 474 visible TTL rejection log records (407 Plot, 67 Stress), not necessarily all receipts. 473 belong to one owner. HeatMap waits are documented, but carry-over work and a second-session backlog also occurred; the unique reason for every expiry cannot be recovered from sampled logs. No reason to relax original deadlines, ownership or work limits.
- Preview reached 16379/16384 states, with 44 capacity rejections and recurring full codec round trips of about 2.14 MiB. This is a non-authoritative diagnostic ledger, not actual damage or the durable commit journal.
- Sable emitted seven UDP unknown-sublevel errors; PDD's paired read-only packet diagnostic did not cancel the handler. Sable's tracking/snapshot arrival ordering is a possible cause, not proven. Copycats' physics JSON refers to `copycats:copycat_catwalk` (registered as item rather than block); its eight warnings are external compatibility evidence. No broad exception suppression or asset override is included.
- No definitive fatal stack trace in the supplied latest log proves the later process exit cause. Phone/launcher resource limitations are outside this fix; capture crash reports and launcher exit status if the process disappears without a Java exception.

## Stress clock recovery contract

- On restored startup, identify active reservations whose immutable creation tick is later than the world game tick. Persist a rollback reason in the existing reservation record before the first game tick; retain the original sources, plans, settings, deadline and stable ApplyIds. A marked reservation cannot return through normal handoff on a later restart even if time has caught up.
- Unplanned residual Q becomes explicitly unused. A previously sealed output retains its original partition; receipt cancellation does not pretend its damage was applied or change its Q. Missing and exact matching DEFERRED receipts become terminal ABORTED, with no read, mutation, queue admission or new deadline. PREPARED and existing terminal results remain governed by the original receipt recovery protocol. A mismatched target/payload/settings/deadline or actual I/O failure stays fail-closed.
- Cancel marked active jobs in bounded batches under the existing shared work budget, and retire only after all known source/output IDs are durably accounted for. Rotation of their held commit entries prevents them from blocking unrelated work. No new Q is issued. Source-owner and derived-ID checks isolate the canceled work.
- Legacy compact archives do not store the creation tick. For restored production stress receipts, a saved deadline later than startup game time plus immutable production TTL (1200) proves future creation. The startup-only scan cancels such archived direct or derived DEFERRED receipts before normal commits begin. This may entail multiple fsyncs at startup; it is not an in-game tick-budget measurement. Saturated deadlines that cannot prove the creation tick are not guessed.
- The existing in-session clock rollback HALT remains fail-closed, including holds for stress commits. This fix addresses persisted startup mismatch, not general restoration of arbitrarily mismatched world files. A journal I/O failure at startup disables mutations; do not continue to save or overwrite the affected world, and restore a consistent backup before retrying.
- Movement transaction rollback/expiry guards and existing physical/owner CAS recovery are untouched. No global corruption or native-process-exit guarantee is inferred from this correction.

## Preview diagnostic policy

- `-Dpdd.preview.mode=SUMMARY` (default): keep isolated preview accumulation and checkpoint compatibility; cached fixed total uses constant-time reads rather than scanning all stored states; periodic full-state binary round trips and target-level log bursts are disabled.
- `-Dpdd.preview.mode=FULL`: explicit target/performance trace subject to the existing per-tick line cap, plus a periodic codec check only for a changed snapshot (including the initial restored snapshot). Independent of `pdd.verf.enabled`; the other diagnostic switches retain their existing meaning.
- `-Dpdd.preview.mode=OFF`: skip the diagnostic planner and preview I/O, preserving existing preview files. Authoritative World/Plot commit calls stay in the original order in all modes. `-Dpdd.preview.checkpoint=false` also remains available in SUMMARY/FULL.
- Both the 16384 state and 8192 credit caps remain. A rejected preview operation changes neither the prior shadow state nor real damage. A valid unchanged checkpoint is not rewritten; corrupt input is preserved rather than silently overwritten. The preview codec schema and calibration identity are unchanged.

## Validation procedure (user)

1. Back up the entire world, including level data and all PDD journals. Apply this ZIP as the only `.zip` in `incoming/`. Keep the original protected workflow and wrapper. Build on Java 21:

   ```sh
   ./gradlew clean test --no-daemon
   ./gradlew build --no-daemon
   ```

2. Confirm `compileJava`, `compileTestJava`, JUnit, `processResources`, `jar`, build and callback-contract workflow success. Replace the old PDD JAR; never install both revisions. The new artifact is `physical-destroy-deform-0.6.0-moreFix.jar`. Confirm startup `revision=moreFix`.
3. In-game run A (copied clean test world): use identical small and large rigid plots, collide with world and another plot, include an existing Copycat, damage, deformation and break case. Reopen normally. Confirm the same Q/material/deformation bounds, no repeated ApplyId, solver callback unchanged, original deadline preserved, queue eventually drains, and preview diagnostics do not gate real commits. Check `[PDD-STRESS-LEDGER]`, `[PDD-STRESS-APPLY]`, `[PDD-MOVE]`, `[PDD-DEFORM]`, `[PDD-COPYCAT]`, `[PDD-PERSIST]`.
4. In-game run B (disposable copy of the affected world): reopen a copy that actually contains receipts/reservations newer than restored world game time. Keep all journals intact. Confirm the identified future-clock work is terminalized without replay, current work accepts and progresses, a subsequent restart does not reissue Q, and no global HALT is caused merely by recovered clock mismatch. If no such copy exists, the scenario remains an unperformed in-game regression; do not manipulate the production world's time or delete journals to manufacture it.
5. Submit full new `latest.log`, any `crash-reports`, launcher exit status and Actions/Gradle test summary. The real-game checks are independent of hardware TPS.

## Validation status

Static acceptance: 336 original files preserved in path and mode; 13 changed, 3 added, no deleted files. All 582 existing JUnit method names retained, 8 added, expected total 590 `@Test` methods. No tests disabled. Existing Gradle and callback-contract workflows retain their original invocation and report collection; no artificial pass threshold or test count gate.

Java 21 syntax-only parse: 211 compilation units, 0 syntax errors. Independent static review covered durable identity, receipt transitions, restart/catch-up, normal-job isolation, preview checkpoint and real offer order. This is not a dependency-resolved compilation or test execution.

Regression expectation matrix (derived from existing formulas, not execution results):

| Test scope | Fixture | Expected |
|---|---|---|
| Unplanned rollback | reservation 100, startup earlier | unused Q 100, pending Q 0, sticky marker |
| Sealed rollback | Q 100, depth 1, original game tick 100 | direct Q 60, plastic unused Q 15, unused Q 25; 100 accounted, exact deadline 1300; ABORTED sources/outputs, no new damage |
| Receipt integrity | prior PREPARED/APPLIED/ABORTED/PARTIAL; conflicting deadline 1301 | original terminal/ambiguous receipt unchanged, mismatch rejected |
| Legacy archived receipt | startup 99, TTL 1200 | 1300 is future, 1299 equality is valid; only the relevant two future receipts aborted, other three preserved |
| Preview capacity | 16384 records, each fixed damage `600*256+1` | 2,516,598,784 units unchanged on overflow, +347 on valid existing-key update, then 2,516,599,131 |
| Preview dirtiness | old snapshot, duplicate, later changed snapshot | one round trip for each changed snapshot when requested, 0 for duplicate/unchanged; restored sum correct |
| Preview policy | default/invalid, explicit FULL and OFF | SUMMARY fallback; no authoritative commit policy change |

For in-game rollback verification look for `[PDD-STRESS-LEDGER] phase=ROLLBACK_RECOVERY` with `markedReservations`, `cancelledArchivedPending`, `QReissue=false`, then the aggregate `rollbackJobsReconciled`. Any `JOURNAL_INIT_FAILED` or `SAVE_OR_PLAN_FAILURE` is an actual failure, not a success condition. A clean current-time session must not mark normal jobs.

Actual Gradle/JUnit, Actions and in-game verification: **NOT_RUN** (user-owned). The pre-delivery review is static and hypothetical; it does not establish stable gameplay on every launcher.

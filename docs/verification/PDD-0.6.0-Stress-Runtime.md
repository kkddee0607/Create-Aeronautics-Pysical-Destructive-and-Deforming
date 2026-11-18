# 0.6.0 Stress runtime review and implementation handoff

Status: actual server event → stress reservation → partial sealed outputs → existing World/Plot commit path is implemented in source. Gradle, JUnit, standalone Java tests, Actions, and game execution were NOT run. This is a source/static review, not a successful build/runtime claim. No remote writes, git commits, or source API inventions.

## Actual call path

- Existing accepted `ContactCreditLedger.Grant` still feeds `WorldCommitService.offer` and `PlotCommitService.offer` through their unchanged target mappers. Each service calls `StressRuntime.reserve` before enqueueing any direct origin.
- Reservation schema2 saves original full-Q intents, source settings/material generation, immutable stress seed partitions, creation game time, policy, frozen observations and append-only plans. A source's already self-damped stress partition is obtained from the existing DamageModel; plastic and direct shares are never used to fund stress. `plan` and `planPreDamped` currently use the same own-E calculation, as verified in the actual source.
- `PhysicalDestroyDeform.onServerTick` calls stress after native observation/Grant processing and before both actual commit services. Runtime holds only direct intents whose source reservation is RESERVED or ROOTS_PENDING. Legacy pending direct IDs absent from the source index are not retroactively stressed.
- A root and its six edges are frozen before that root's original direct mutation can proceed. Completed minimum-depth nodes are published before unavailable/capped residuals consume the original TTL. Saved graph replay can still reference a root removed by its own direct break.
- Runtime probes existing loaded-only gateways in the same canonical owner-local space. Existing BE/multiblock/owner-generation/claims/physics guards are reused. World and Plot mutation trackers invalidate changed unpublished observations, including tracked same-state replacement.
- `StressCommitHandoff.offer` validates membership, stable ApplyId, owner/position/fingerprint/incarnation, Q, settings, material and material generation, then uses the existing `WorldCommitCore.submitSealed`. Deadline remains original creation+TTL; pending origin eventTick is unchanged. The core performs real damage/break through existing safe gateways and receipt journal; the stress subsystem itself does not call destroyBlock, mint Q/Credit, recalculate G(F), or issue native impulses.
- Stress journal CLOSED means every output has a durable queued/terminal receipt; it does not mean every output damaged the world. Stress result logs report the core's actual outcome.

## Changes completed in this resumed review

1. Added `StressProbeCursor`: one expansion can resume through a shared budget yield without repeatedly charging already frozen reads. This fixes otherwise perpetual starvation for configured budgets1..7. Fresh reads and planning operation are charged once; live validation, origin recovery, sealing and handoff remain charged under the same server-wide cap≤512.
2. Finished capped planners release their `activeSessions` slot while reserved residual remains pending until original TTL. A capped session no longer monopolizes one of128 planning slots for1200 ticks.
3. Changed snapshot invalidation to durable `DEFERRED_BOUNDED` residual state. It preserves published partitions/plans, stops planning replacement blocks, releases direct intents for their own CAS, and closes only unspent continuation at original TTL. This avoids falsely declaring failure residual immediately UNUSED.
4. Durable source ApplyId uniqueness now spans reservations, including after CLOSED/restart. Changed projection IDs cannot reserve an overlapping source origin a second time. Reusing the same runtime reservation ID with changed origins/settings/material generation now fails closed instead of silently returning.
5. Added `Entry.holdsOrigins` as the common persisted hold predicate.
6. Structured trace logs are bounded to8 per runtime tick; `-Dpdd.stress.trace=false` disables detailed seal/handoff logs while startup/summary/fault logs remain.
7. Updated stale comments to reflect append-only partial publication.

## Files

New in this review:
- src/main/java/com/physicaldestroydeform/stress/StressProbeCursor.java
- src/test/java/com/physicaldestroydeform/stress/StressRuntimeBudgetTest.java

Modified in this review:
- src/main/java/com/physicaldestroydeform/stress/StressRuntime.java
- src/main/java/com/physicaldestroydeform/stress/StressReservationJournal.java
- src/main/java/com/physicaldestroydeform/stress/StressPropagation.java (documentation only in this resumed segment)
- src/test/java/com/physicaldestroydeform/stress/StressRuntimeProtocolTest.java

Existing interrupted implementation reviewed, retained:
- StressRuntime / StressPropagation bounded partial mode / schema2 StressReservationJournal
- StressCommitHandoff; bounded validation changes in StressWorkCoordinator
- WorldCommitService, PlotCommitService, PhysicalDestroyDeform runtime wiring and watchers
- Existing NeoForgeWorldGateway/NeoForgePlotGateway loaded-only reads, permission and mutation contracts

No deform/*, DamageModel, WorldPersistentJournal, WorldReceiptJournal or WorldCommitCore files edited by this resumed task. Movement storage and integration status is tracked in the main checkpoint document.

## New JUnit source/static expectations

RuntimeProtocolTest now has9 cases (6 previously present +3 added); RuntimeBudgetTest has2. None executed.

- cap depth1, seed100, E0 line: one planned neighbor with direct60/plastic15, loop unused12.5, residual12.5 deferred until creation100+TTL1200=1300; after closure total unused25 and conserved100.
- unloaded later frontier retains first output durably; loaded retry yields total direct67.5/plastic16.875/unused15.625 for the line and never duplicates stable output IDs.
- originalQ400 gives direct240/plastic60/stress100; all original fields round-trip. A mismatched160 stress seed is rejected.
- frozen graph replay yields same plan prefix, identities, ledger and output IDs; changed published prefix is rejected.
- changed graph retains12.5 pendingQ and previous plans under original deadline; cannot acknowledge handoff before residual close.
- reusing source ApplyId through a different reservation fails before and after restart/CLOSED.
- actual pure core integration: originalQ1000 root remains held with zero break calls until root expansion is sealed; then exactly one direct break; frozen graph continues to two neighbors receiving damage390 and48+192/256. Reoffering their stable ApplyIds creates no queue entries and no second root break. This exercises existing WorldCommitCore/WorldPersistentJournal with a fake loaded gateway, not Minecraft execution.
- per-tick budget1: three expansions complete with21 charged fresh graph reads (root+two nodes ×7), Q stays100, final plans/ledger equal an unconstrained plan. Replay with a changed requested cell fails.

## Remaining practical limits / risks

- Real dependency resolution, compilation, JUnit execution, World/Plot lifecycle and split effects remain entirely user verification. The runtime reuses existing verified API call sites; no new native dependency was introduced here.
- MaterialRegistry generation is a process-local counter. Saved material snapshots/properties are immutable, but live validation currently rejects changed numeric generation. If startup rebuild counts differ after restart, continuation can conservatively defer/expire despite equal properties. This is a conservative false rejection, not Q recreation; a content-based persistent material generation is not implemented by this task.
- A completely unavailable/faulted World or Plot service keeps its origins recoverable in the stress journal; runtime cannot claim those direct payloads reached a commit journal. No fallback nondurable mutation occurs.
- Max8192 permanent stress reservations/64MiB intentionally fails closed; tombstones are not evicted to create apparent capacity. Operator retention/compaction beyond the current bounded store is not supplied.
- Source roots limit8192 and frozen snapshot record limit4MiB/65536 cells remain explicit hard caps. Oversized source reservations fail closed rather than silently omitting roots. A production large-source chunking protocol would need a separately durable cause-level partition contract.
- Snapshot invalidation abandons unspent continuation of the whole job conservatively (retaining prior published outputs); it does not reassign Q to the replacement block.
- Older pure StressWorkCoordinator is not the Minecraft runtime; actual runtime uses shared charged probe/validation/handoff budget and partial publication. Do not describe that older Backend abstraction as the live integration.

## User validation handoff (for the checkpoint)

A: loaded World adjacent line/diamond, duplicate callbacks and restart before completion. Enable default stress trace; retain `[PDD-STRESS-LEDGER]`, `[PDD-STRESS-APPLY]`, `[PDD-COMMIT]`, `[PDD-PERSIST]`. Confirm direct root mutations do not erase propagation, each stable ApplyId has at most one terminal application, total accountedQ never exceeds source stress reservation, and stress work summary≤configured cap≤512.

B: two different Plot UUID owners, boundaries/unloaded cells, Plot split/restart. Confirm no coordinate-only crossing, no forced chunk load, deferred residual keeps original deadline, output owners/incarnations remain exact, existing Sable heatmap/tick witness/pacing guards defer destructive commits. Provide complete latest.log, settings, SHA, owner UUIDs, world game ticks and outcome screenshots. Actual mass/collider/drop correctness needs game evidence.

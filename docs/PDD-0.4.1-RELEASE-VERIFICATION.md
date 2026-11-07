# PDD 0.4.1 source integration and verification status

Baseline: PDD 0.4.0finalFix source ZIP, GitHub main SHA `92b95b77c5a41009b425682079f940aa0d2e1607` at source review. Integrated design: PDD 1.0.7.

## Implementation

- Existing Sable callback, F-first distribution and Q ledger retained; already sealed grants are projected into World-only intents. Plot physical mutation remains deferred.
- Server tick commits with loaded-chunk and owner/fingerprint/incarnation CAS checks, conservative vanilla single-block safety restrictions, NeoForge block-break event veto, one Vanilla `destroyBlock(pos, true)` call and post-operation re-read.
- Bounded synchronous CRC-checked append-only WAL records World damage (U8 plus 1/256 remainder), apply IDs and receipts. Interrupted PREPARED and DEFERRED receipts are quarantined on startup and never blindly replayed. Save failures fail closed. 0.5.0 sharded storage/migration is not implemented.
- CSV material sources loaded once per snapshot, with bundled and external layers, item inheritance and recipe inference; state snapshots published atomically. No per-placed-block recipe inference.
- Existing Java sources and tests retained. Added World and material JUnit and standalone tests.

## Verification performed

- Original source ZIP CRC verified; original 177 files all preserved, 6 modified and 15 new implementation/test/resource files plus this report.
- Java 21 independent compilation: WorldCommitCore, WorldReceiptJournal, WorldIntentMapper, MaterialCsvCatalog against existing 0.4.0 artifact succeeded.
- Standalone self-tests: 32 World, 10 mapper, 19 CSV checks passed. Supplied material files parsed (1,844 blocks, 2,159 items).
- No actual 0.4.1 Gradle build, existing JUnit execution, GitHub Actions, or Minecraft in-game validation was performed. NeoForge WorldGateway API and real claim protection require build and in-game verification.

## Known limitations / risks

- Vanilla block break events are emitted using a fake player for compatibility; third-party claim implementations that do not honor this event may not veto. The safe-block allowlist excludes most complex blocks and block entities.
- World block replacement Mixin injection descriptor must be verified against the actual mapped NeoForge runtime. A missing injection is a blocking safety failure.
- World journal fsync per transition is bounded but potentially TPS-expensive; no chunk-sharded storage or guaranteed exactly-once drops across power loss.
- Material CSV slime threshold and legacy elasticity policy require in-game regression verification.
- WAL receipts have finite hard limits; exceeding them rejects new work rather than silently evicting entries. Server operator must archive/repair under controlled conditions.

## In-game verification (at most two sessions)

1. With the correct Sable/Create/Aeronautics versions, collide a movable structure with static stone, repeat impacts to accumulate damage, and check single block break and single drop. Repeat on unloaded chunk, protected claim, BE and unsafe multiblock: these must not be destroyed. Capture `[PDD-COMMIT]`, `[PDD-MATERIAL]`, `[PDD-SAVE]` and any Mixin errors.
2. Shut down cleanly, restart and apply a new collision to the same damaged stone; verify damage resumes without duplicate application. Replace a previously damaged block with an identical block state, verify stale damage is rejected. Interrupt/restart on a test copy to inspect PREPARED/PARTIAL_QUARANTINED reconciliation, without expecting exactly-once drops under crashes.

If build or gameplay fails, provide complete Gradle/Actions log, latest.log and crash report, including source commit SHA and loaded mod versions.

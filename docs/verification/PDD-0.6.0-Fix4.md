# PDD 0.6.0 Fix4 — log regression / bounded large reservations

## Baseline and scope

- Main: `kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming`, SHA `a4b411b186e3da038609e4feb572ddc1da883192`. Original 331/331 files matched Git Tree blob SHA, byte size and executable bit. IDS 1.0.9 unchanged. Java 21, Minecraft 1.21.1, NeoForge 21.1.228, Sable 2.0.6, Create 6.0.10, Aeronautics 1.3.2, optional Copycats+ 3.0.11 unchanged.
- Fix3 runtime log episode 35 emitted 9,216 Plot / 9,744 World targets before `invalid stress reservation`. Both exceed the independent 8,192 root limit in reservation/session/decoders. F/Q conservation passed before that failure. The F side limit is 32,768; both sides may belong to the same domain. Do not partition the same root set into independent stress sessions, because that would allow original roots to re-receive Q or duplicate the derived-node budget.
- Log pressure near 12 MiB active bytes was not definitively diagnosed as a specific quota; old per-record cap was 4 MiB. Whole `aTargets` and `bTargets` lists accounted for a substantial share of the log (62 PDD-LEDGER lines ≈48% of bytes). Device-only TPS, frame drop and crash symptoms are excluded. Sable network/HeatMap messages and Copycats selector warnings alone do not establish a PDD defect; external JARs unchanged.

## Changes and invariants

1. Reservation/session/active and closed decode root limit = `2*ForcePropagation.MAX_NODES_PER_SIDE = 65,536`. Frozen graph count bound = max(old 65,536, `7*(roots+policy.maxNodes)`), global decoder cap 487,424. All roots, including zero-Q, are excluded from derived outputs; zeros need no root expansion reads. One minimum-depth merge, original Q, stable ApplyIds and one derived budget per domain grant. No new source Q, no changed damage/H/T/D/E/geometry equation.
2. Large active records (canonical bytes >=256 KiB) use a bounded DEFLATE BEST_SPEED outer frame (`-5`, decoded length, compressed data). Canonical v4 inner bytes, CRC32C and archive hash are unchanged. Small v4 records remain raw; old schemas remain readable. Active on-disk limits 16 MiB per record/64 MiB total; decoded 64 MiB per record/128 MiB total, with closure headroom. Size and corruption checks fail closed. Closed archive origin bound matches source. Do not downgrade after Fix4 writes a compressed record; retain full backup.
3. Original direct intents remain in the already durable reservation until the root barrier is durable or residual is safely closed. Shared work cap includes their handoff. At most 64 origin handoffs per stress tick, each call at most 32; domain FIFO and per-grant reverse-layer order retained. Existing core receipt queue 16,384 unchanged. Temporary queue pressure defers a sealed handoff without producing a fictitious receipt; original TTL expires into a terminal receipt. No renewal or reissue. Derived handoff also uses this safe admission path. Closure requires durable source/derived receipts, not claims of successful damage.
4. Persist partial frozen graph at bounded batch boundaries, avoid whole-graph revalidation until all roots are expanded, keep full live validation before new durable publication. Already-published non-Copycat cells need no redundant re-scan; actual mutation CAS remains. Positional material revision, owner/fingerprint/incarnation, protection, split and HeatMap safety remain. On restart before direct receipt exists, bind Plot through the existing exact saved root incarnation and live owner gate, never by guessing an owner; then read live. Clock/expiry, invalidation and receipt semantics remain fail-closed.
5. Session verification now once per batch rather than per single graph operation; cached immutable published set, no repeated source-owner index population at every checkpoint; CRC verification reads the original buffer without copying the full body. Diagnostic target count and first 4 shares (32 in fullTrace) replace unbounded lists. Admission/pressure/expiry and aggregate Q kept; suppression counters retain log totals. These are work/volume reductions, not measured MSPT claims. Large frozen snapshots still require bounded synchronous durable writes; real disk speed and simultaneous grants can affect latency.

Unchanged defaults: depth 4, derived 128, shared work cap 512/tick, minQ 1/256, original TTL 1200 ticks; direct break caps, native solver/callback contract, storage IDs, fixed damage and deformation thresholds. 65,536 is an admission ceiling, not a claim that all extreme loads will finish within the fixed TTL; unfinished Q follows existing bounded expiration/unused policy. Full disk, corrupt receipts, owner replacement, unknown direction and unsafe physics remain fail-closed.

## Static test review

574 existing `@Test` methods preserved unchanged. 8 new methods (582 source-declared, not actual test executions):

| Case | Expected |
|---|---|
| Roots 8192, 8193, 9216, 9744 | accepted, reopen exact payload; one reservation and same source owner |
| Roots 65536 / 65537 | accepted / rejected, no new allocation beyond F limit; reserved Q 6,553,600 at 100/root |
| 9216 linear roots, one external neighbor | 1 derived node, Q 50, direct 30, plastic-unused 7.5, unused 921562.5, total 921600 |
| 9744 roots + 68000 frozen observations | graph restored; canceled unused 974400; compact/active twin reconciled, retry denied without issuing Q |
| Large framing / old v4 | exact decoded entry, canonical CRC preserved; trailing bytes and CRC corruption rejected |
| Zero-Q root | remains excluded; 7 graph probes only for one positive root; no derived targets |
| Real 16,384-slot queue | full ⇒ DEFERRED with no receipt/write; duplicate recognized; game tick 1301 / deadline 1300 ⇒ exactly one terminal expired receipt |
| Target sample | exact ordered prefix, 2/5 items; no mutation of source list |

Static parser: Java 21, 210 compilation units, 0 syntax errors. This is not type resolution, runtime or Gradle verification. JAR inspection confirms Sable `SubLevelContainer.getContainer(ServerLevel)`, `getSubLevel(UUID)`, `getPlot(ChunkPos)` and `ServerSubLevel.getPlot()/getSplitFromSubLevel()` descriptors; original gateway APIs reused. Create CopycatBlockEntity.getMaterial and Copycats+ IMultiStateCopycatBlockEntity.getMaterialItemStorage remain unchanged. Aeronautics bundled jars inspected; no new compile/runtime dependency. Standard JDK compression streams only. No existing Gradle, Wrapper, CI, resources or workflow modifications. Existing JUnit assertions unchanged.

**Actual verification:** compileJava, compileTestJava, JUnit pass count, processResources, jar, clean test, build, Actions, in-game and MSPT: NOT_RUN (user-owned verification). No green result inferred from static parsing.

## User verification (two game sessions)

1. Back up the entire world; upload this complete root ZIP into existing `incoming/` then run Apply and Build. Local equivalent `./gradlew clean test --no-daemon` and `./gradlew build --no-daemon`. Keep full test report if any failure; no changed test expectations to mask failures.
2. Session A: on copied world reproduce previous large Plot/World collision (same structure/material/speed), then allow jobs to drain. Compare `LARGE_RESERVED` roots (9216/9744 in old episode), `SEALED` Q accounting, `ORIGIN_WAIT` if any, `SUMMARY` work<=512, active/decode bytes and queue jobs. No `invalid stress reservation`; no duplicate IDs or Q; no unsafe moves/breaks. Original collision, stress, damage, deformation and Copycat material/owner behavior should remain observable. If pressure persists, retain the reason, roots/snapshot/bytes and game tick/deadline. No hardware-only lag/crash diagnosis from raw TPS.
3. Session B: save/exit while a bounded job is still pending, reopen the same copy, allow completion; verify same stable IDs, original deadline, no reissued Q, archived receipt count preserved, independent collision works. On copy only, preserve all files if `ARCHIVE_WAIT` or `HALT` occurs. Submit latestlog plus crash report if applicable, Actions/JUnit report, and optional same-scene MSPT profile for a real performance comparison. Do not delete active stress/receipt journals or downgrade.

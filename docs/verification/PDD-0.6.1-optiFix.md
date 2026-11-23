# PDD 0.6.1 optiFix — release verification

## Baseline and invariants

- GitHub main `96a4ccf7c56025d211ac4626e73f76392dcb9b39` (spreadFix); IDS 1.0.9.
- Java 21, Minecraft 1.21.1, NeoForge 21.1.228, Sable 2.0.6, Create 6.0.10, Aeronautics 1.3.2 and optional Copycats+ 3.0.11. No dependency or Mixin ABI changes. Mod version stays 0.6.1; classifier and startup revision are `optiFix`.
- F/Q formulas, direct contact alpha 0.4, material mixing, propagation limits, fixed-point remainder, eligibility, owner-local stress, cross-owner evidence and duplicate credit rules remain unchanged. Algorithm revision is intentionally unchanged: no new physical algorithm or saved receipt identity.
- Existing WAL, manifest schema 6, stress `.psc` schema, lineage, rollback and pending semantics remain. Back up the complete world before upgrading. Never delete old PDD save files or run two PDD JARs together.

## Changes and bounds

| Area | Contract |
|---|---|
| Copycats | Cache only exact pure material calculation; live block/BE/slot/material evidence is still read. 2,048 entries, signature/parts limits; material reload and server stop invalidate. Trace history also bounded to 2,048. |
| Contact | Reuse exact validated endpoint evidence within a single synchronous credit plan; no carry-over to later credits. 4,096 endpoint cache limit, fallback to live audit at capacity. Direction and actual contact proof unchanged. |
| Sable snapshots | PRE remains authoritative. NORMAL avoids duplicate POST owner walks; DEBUG/TRACE retains POST diagnostic capture. Original callback still executes once and its return is unmodified. Completed PRE and session state released. BODY_ERRORS in NORMAL no longer includes skipped POST audit errors. |
| Owner lifetime | Use server-session tick for absent age; retire only absent ephemeral generation after more than 600 ticks, invalidate old evidence on runtime change. Durable Plot ownership remains authoritative and is not retired by this cache. |
| World/Plot receipts | 16 MiB estimated decoded-completed cache per journal; primitive offset/CRC/candidate index (~16 bytes per indexed record, plus arrays). Full ApplyId verification on candidate hit; pending/quarantine and dirty WAL overlay remain authoritative, released only after manifest publication. The original 2,000,000 receipt limit and 96 MiB WAL cap remain. |
| Stress | Cold archive `.psc` exact lookup, bounded decoded cache (8 MiB combined budget). Closed source ownership uses a derived append-only exact disk index with compact hash candidates; a candidate match must verify the original full ID. Active reservation/hold semantics and old archives are preserved; derived index rebuilt from authoritative archive records at startup. |
| Lifecycle | Rebind acceptance consumer and reset counters on each server session, clear session resources independently on stop. A receipt lookup failure or unexpected commit exception halts both mutation services while preserving stress holds and WAL until shutdown. |
| Telemetry | Fixed 512 timing samples, 10-second report cadence; no heap dump, forced GC, new background workers or native solver changes. Heap estimates do not measure native memory. |

Receipt and archive caches affect retention, not identity. Compact historical indices grow with durable identities; the active/pending data, dirty checkpoints and decoded shard work buffers are not included in cache budget estimates. No claim of zero heap growth or measured TPS is made without the in-game test. Existing per-batch checkpoint time budgets are soft bounds; a single fsync/shard can exceed them. The previous reported crash does not establish a Java OOM, native failure or mod conflict by itself.

## Diagnostic modes

- Default `-Dpdd.diagnostics.mode=NORMAL`: essential decision, F/Q conservation, errors, persistence and sampled metrics remain; avoid detailed diagnostic-only work.
- `-Dpdd.diagnostics.mode=DEBUG`: detailed contact/owner and diagnostic POST audit.
- `-Dpdd.diagnostics.mode=TRACE`: detailed F node trace under existing caps, with DEBUG evidence.
- Legacy `-Dpdd.diagnostics.fullTrace=true` and existing explicit F/verification settings remain supported. Never change physical policy to collect logs.
- `[PDD-MEMORY]`: Java heap used/committed/max and cumulative GC counters; native memory is unmeasured. `[PDD-PERFORMANCE]`: rolling PDD tick handler and bridge p50/p95/p99 in nanoseconds, not whole server MSPT. `[PDD-COPYCAT-PERF]`, `[PDD-CACHE-PERF]`, `[PDD-STORAGE-PERF]`, `[PDD-STRESS-LEDGER]`: cache/retention counters. `[PDD-SESSION]`: lifecycle/fault cleanup. `cleanup=CLOSED` means cleanup actions returned; inspect individual storage STOP logs for save success.

## Tests and delivery gates

All existing 687 `@Test` methods and workflows are retained. An additional 57 tests bring the source count to 744. The AI performs static inspection/Java parsing and arithmetic reasoning only; the user executes the actual Gradle tests and Actions. The new tests cover Copycat calculations/trace (16), contact memo (7), owner lifetime (2), PRE frame release (2), diagnostic/session behavior (5), cold World/Plot receipt restart/CRC/identity/dirty checkpoint (16) and stress archive/ownership/restart/corruption/cache pressure (9). See test source for exact assertions.

Run `./gradlew clean test --no-daemon`, then `./gradlew build --no-daemon`. Inspect compileJava, compileTestJava, JUnit, processResources, jar, callback contract and build Actions. The existing Apply workflow protects its own configuration and the wrapper; use the full root-structured source ZIP as provided.

## In-game verification — at most two sessions

1. NORMAL mode, backed-up copy of the same world and mod set. Use the same vehicle/road, rigid vs elastic contact, P1–P2–S1 with and without a gap, rotated Plot, stationary persistent contact, dense Copycats and a split/reassembly. Repeat for at least 15 minutes with comparable workload. Record `[PDD-F-LEDGER]`/chain, damage/commit, owner generation, memory, GC, PDD timing and cache summaries. Confirm conserved F/Q, correct final damage, no repeated issuance while stationary, no fake contact, no stale ownership, bounded cache entries and no persistent increase in retained heap after comparable natural GC cycles. Expected optiFix difference is work/retention/diagnostic cost, not damage numbers. Compare actual MSPT and OS process RSS separately; use equal JVM settings and hardware.
2. Save and exit with damaged plots, outstanding stress/transfer work and Copycats; inspect checkpoint and shutdown logs. Re-enter the same world in the same client JVM, check damage/deformation/remainder, Copycat NBT, owner generation, exact retained transfer/receipt state, and repeat the first collision once. Confirm fresh acceptance counters, no duplicate old credit and normal subsequent damage. DEBUG only for a focused reproduction if required; avoid comparing TRACE performance to NORMAL. Submit full latest.log, crash-report and `hs_err_pid*.log` if present, JVM heap flags, OS process memory and mod list. A Java OOM requires actual error evidence; native/driver/mod interactions require separate evidence.

Required gates: G-SPLIT-01, G-CC-LINEAGE, G-CONTACT-ELASTIC, G-CROSS-OWNER, G-COL-01, all 0.6.0/0.6.1 regressions, and repeat-session/receipt/cold archive persistence. Actual build, Actions and gameplay are **unverified** until user runs them.

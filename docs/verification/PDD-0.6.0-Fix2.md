# PDD 0.6.0 Fix2 — runtime correction and verification

## Baseline and scope

- Repository: `kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming` (repository ID 1423843296), main `a26ca70883b5f664f1993919bd8e2a4e9ea486d3`, tree `c8ca6a853f54d8a3d9ebc5f96b1262775d8585a2`.
- GitHub recursive non-truncated Tree: 322 blobs. All 322 local Fix files matched path, size, Git blob SHA, executable flag. Previous ZIP SHA-256: `196965fb737a0ff4e4392378d229eb9583d2e6384aa71c4dca5ece368a8f13d1`.
- Attached 1.0.9 design matches the GitHub specification byte-for-byte. Sections 24.5, 24.6 and 37.3 control the fix; section 37.4 split/merge state transfer remains outside 0.6.0.
- Version, material table, save schema, numerical policies and original Sable callback invocation/return contracts unchanged. The runtime startup revision is `Fix2`.

## Evidence and changes

Latest Fix log: 60,868 lines. F/Q conservation was reported for 196 events; 2,269 stress seals were logged, including repeated visible accounting. 16 origin reservation exceptions were observed (15 already belonging to another reservation, one changed payload). 894 repetitive owner epoch mismatch notices for one owner were observed. There were 68 blocked movement settlements but no confirmed successful move. These are log observations, not a CPU profile or proof of every root cause.

1. Credit identities: an episode sequence is allocated from one monotonically increasing counter, independent of bounded per-pair state eviction. A new physical authorization after abort also receives a new sequence. Existing burst, separation, force and authorization rules remain; no new Q because of an ID alone. Persistent source IDs remain deterministic for the same accepted grant. Never delete old durable receipts to bypass identity checks.
2. Plot lifecycle: distinguish observed Sable `UNLOADED`/add from true removal and split lineage. Rebind only a proved same Plot identity; never migrate sparse state by XYZ or blindly reassign an unknown epoch. On lifecycle change invalidate transient collision authorization. Orphan decisions remain durable; repeated mismatch notices are not repeated for the same fenced owner. Sable observer callbacks perform identity bookkeeping, not Minecraft block mutation.
3. Target direction: exact independently verified unique-face evidence can agree with a tentative multi-face candidate. A candidate alone cannot authorize a move. Conflicting signed axes, unknown approach, incomplete geometry, native 100-cap and unknown original seeds retain veto. Reuse exact event-local normal/pose proof; repeated identical normals do not repeat all pairwise comparisons. No cache across events or material changes.
4. Stress: skip only byte-equivalent persistent snapshot/seal updates, eliminate repeated scans of already-published cells via an index of the exact durable Entry, reuse immutable probes and remove redundant adapter watch registrations. Ordinary immutable material definitions reuse same-tick, same registry snapshot results; Copycats positional material and live owner/state/CAS checks remain live. Work charges, order, TTL, and changed journal writes remain intact. No new storage format. F-first and reserved Q-stress are not pooled or double-counted; they are distinct accounting stages under §24.5/37.3.
5. Logging: `[PDD-DEFORM-Q]` now has the correct `spentQ` and `unusedQ` fields. Routine World/Plot commit detail sampled to 4 rows/domain/tick; breaks and quarantines retained, cumulative suppressed count in summaries. Routine no-move detail sampled to 2 rows/domain/tick, with suppressed statuses summarized; actual move transaction results retained. `-Dpdd.diagnostics.fullTrace=true` raises routine caps to 20/16/16 respectively. Stress trace cap separately configurable by `-Dpdd.stress.trace.maxPerTick=8` (0..128). Summary contains actual seal/handoff counts and unchanged writes avoided. No behavior depends on log limits.

## Unchanged numerical and safety contracts

- F-first force cap 40,000,000, default `G(F)=0.1*max(0,min(F,Fmax)-2)`; one source credit; default duplicate/separation/lifetime policies remain.
- E applied in the correct stage, H/T/D/E and fixed-point scale unchanged. Stress depth 4, unique nodes 128, work per tick 512, minimum Q 1/256 and original TTL 1200 game ticks unchanged. Node-once minimum depth merge, owner boundaries, conservation and journal ordering remain.
- D/K threshold and verified direction, max one block / one or two signed axes, reverse order, blocked reserve capped, no reapplication of E. No unverified destinations, new drop path or functional BE support.
- Copycats+ 3.0.11 material resolution, shape T correction, material revision CAS and decorative BE remain. Upstream catwalk and Sable packet behavior not patched by PDD.

## User verification (two game sessions maximum)

1. Back up the world (including PDD journals). Apply the full source ZIP via existing workflow. Run `./gradlew clean test --no-daemon` and `./gradlew build --no-daemon` with Java 21 or the existing Build Test Action; save the first failure and full test report if any. Do not disable failed tests.
2. Session A: start an existing backup test world and verify startup `revision=Fix2`. Repeat same pair impacts separated by over 600 ticks and repeated independent reimpact; IDs must not repeat, `origin already belongs` or `changed payload` must not appear for new impacts. Test a small Plot/World and Plot/Plot collision on hard/ductile materials and a valid open move destination, then an obstructed destination; confirm `[PDD-IMPACT-DECISION]`, `[PDD-DEFORM-Q] result=APPLIED` (when all preflight gates pass), or explicit veto/blocked settlement and `reservedQ=spentQ+unusedQ`. Compare `[PDD-STRESS-LEDGER]`, F/Q conservation, `[PDD-STRESS-APPLY]`, and `[PDD-DEFORM]` with previous run. The trace summary distinguishes suppressed details from failed operations. For detailed diagnostic, enable the above JVM properties before session launch.
3. Session B: test normal unload/reload of an existing Plot (same UUID and center); confirm `phase=REBOUND ... proof=UNLOAD_ADD_SAME_CENTER` only for proved lifecycle. Test a true split separately: parent quarantine, independent child, no state transfer; save/reopen and repeat Copycats material substitution and collision. Watch receipts, material revision, stress handoff and no repeat mutation/drop. No old packet suppression is expected. Attach complete `latest.log` with first error/crash report if present.

For performance comparisons use the same world, hardware, mods, collision scenario and logging settings; compare average/P95 tick length and `[PDD-STRESS-LEDGER]` work/queue/write-savings counters. No absolute TPS improvement is claimed without the game benchmark. In-game checks and actual Gradle/JUnit/Actions are user-executed only; AI checks are syntax/static and manual numerical reasoning.

## AI preflight result

- 201 Java compilation units parsed with zero syntax errors (syntax-only; not type attribution, `compileJava` or `compileTestJava`).
- 542 original `@Test` methods retained; 13 new methods (2 credit, 4 direction, 4 lifecycle/native evidence, 3 stress), 555 total. Actual JUnit execution and pass count unverified.
- Manual checks: F=42 gives Q=4 with default scale/threshold. At tick 602 a pair last seen at tick 1 exceeds its 600-tick retention; a newly created grant uses a new sequence. Stress regression 100Q accounts for 60 direct + 15 plastic + 25 unused. Changed reception values are not used to weaken prior assertions.
- 322 original project files retained, 19 modified and 2 added (324 total). Build script, Gradle wrapper and GitHub workflows unchanged. Apply expects one project root with `build.gradle`, `settings.gradle`, `src/main/java`; protected workflow/wrapper paths unchanged.
- Actual compile, resources, JAR, clean test, build, Actions, and in-game results remain unverified by AI. No remote changes.

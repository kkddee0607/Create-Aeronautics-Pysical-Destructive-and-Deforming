# Physical Destruction and Deforming (PDD) — 0.5.1

**Minecraft Java 1.21.1 · NeoForge 21.1.228+ · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6**

PDD is a server-authoritative Create Aeronautics addon that consumes **Sable's original collision output**, calculates bounded, owner-aware damage, and selectively breaks eligible world or physical-structure blocks. It does **not** replace Sable's physics solver or the original collision callbacks. This README documents the current release only; historical verification reports are retained under `docs/` for provenance, not cumulative release notes.

## 0.5.1 release scope (IDS 1.0.8)

| Capability | 0.5.1 behavior |
|---|---|
| World static blocks | Existing 0.5.0 `WorldCommitService` retained: material, fixed-point T1024, protection, WAL/receipt, restart recovery and vanilla drops. |
| Physical Plot blocks | **New real server-side damage and destruction** via Sable's `EmbeddedPlotLevelAccessor.destroyBlock` using center-relative owner-local coordinates; committed in `ServerTickEvent.Post`, not inside physics callbacks. |
| One source F/Q | The same immutable ledger `Grant` is projected separately to World and Plot. Plot does not mint or multiply Q. Owner UUID, epoch, dimension, local position and BlockState fingerprint are bound to receipts. |
| Safe block group | Ordinary loaded, independently owner-verified blocks only. Block entities, linked double blocks, doors, beds, unbreakable blocks, unknown claims/permissions and uncertain physics owners **veto** mutation. |
| Sable mass / collider | Uses Sable's **existing LevelChunk change notification path**; never constructs a second physics body, calls `removeSubLevel`, forces a rebuild or rewrites native callbacks. Correct mass, collider and split runtime behavior **requires the user's in-game validation**. |
| Split and owner lifecycle | Tracks physical object identity, runtime ID, UUID, generation and Plot center. Sable `getSplitFromSubLevel` and owner mismatches trigger **durable ORPHAN_QUARANTINE** before bounded cleanup of old-owner damage. It never copies ghost damage to another Plot. |
| Plot storage | Independent `data/pdd-plot-0.5.1/` schema-2 snapshot/WAL with per-Plot virtual namespace, CRC and durable receipts. It cannot alias the existing `data/pdd-world-0.5.0/` or two Plot UUIDs at the same local coordinates. |
| Per-tick protection | `pdd.plotMutationCapPerTick` defaults to **32** (caps total Plot commit operations, including damage-only); no forced chunk generation or loading. |
| Deformation preparation | An immutable owner-local **MovePlan candidate** contract with D/K yield, break-first precedence, signed-axis validation and reserved-Q accounting exists and is testable. **0.5.1 does not move blocks**, infer a direction from unverified normals, or claim a planned move as applied. |

The later roadmap, including actual physical movement, full split lineage migration, stress-graph mutation, cracks/UI, and explosion/projectile/entity causes, **is not claimed as implemented in 0.5.1**. See [`docs/PDD-1.0.8-Integrated-Design-Specification.md`](docs/PDD-1.0.8-Integrated-Design-Specification.md) for version boundaries.

## Numerical and persistence contracts

The release retains baseline F/Q source policy (`pdd.realfix.forceGain=0.1`, game F maximum 40,000,000, Q maximum 3,999,999.8), one source episode / credited event, owner-local F-first propagation, damage retention and definition-level H/T/D/E profiles. **No numerical model values or existing JUnit tests were intentionally deleted.** A Plot Plan shares the `DamageModel` arithmetic already used by World.

Each eligible Plot change observes current owner and block fingerprint, checks permissions and BE state, and rereads the block after the operation. Damage receipts and sparse state are saved atomically in the new WAL. For block destruction, a durable `PREPARED` receipt precedes the actual Sable/vanilla destruction, and `APPLIED` is only recorded after an AIR reread. A crash after `PREPARED` is **quarantined and never replayed speculatively**. Full cross-mod exactly-once world transactionality is not claimed.

**Before installing, back up the complete world**, especially `data/pdd-world-0.5.0/` and `data/pdd-plot-0.5.1/`. Do not downgrade a persisted schema without restoring a matching world backup. This is an unvalidated physical-mutation release; test on a disposable world first.

## Build and deployment

Source ZIPs are for **GitHub Apply PDD ZIP**, not Minecraft `mods/`. Upload exactly **one** full Apply source ZIP to repository `incoming/`; GitHub Actions `Apply PDD ZIP` discovers the Gradle project root and synchronizes all non-protected files, deleting missing non-protected paths. GitHub preserves `.github`, `incoming`, `gradlew`, `gradlew.bat`, `gradle/` and `.git` regardless of ZIP contents. **Do not upload multiple ZIPs at once.** The source archive must include all non-protected source/resource/Gradle files and preserved tests.

After Apply succeeds, run GitHub Actions **PDD Manual Build** and **PDD Callback Contract Test**. The former runs the repository's Java 21 wrapper `./gradlew test --no-daemon` followed by `./gradlew build --no-daemon`; the latter runs the original callback contract test. Download the resulting JAR artifact from the completed Build workflow and install that JAR together with the matching dependencies.

Local commands, when a full NeoForge Gradle environment is available:

```sh
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

**Verification status for the submitted source:** Java 21 isolated compilation and an in-process test-assertion harness may be used for the pure source subset. They are **not official Gradle/JUnit, NeoForge/Minecraft runtime, GitHub Actions or in-game passes**. Consult the response accompanying the ZIP for exact executed checks.

## Server settings

| Java system property | Default | Effect |
|---|---:|---|
| `-Dpdd.plotMutationCapPerTick=32` | `32` | Bounded Plot commit operations per server tick; clamped to `1..4096`. |
| `-Dpdd.worldBreakCapPerTick=64` | `64` | Existing World break attempts per tick. |
| `-Dpdd.realfix.forceGain=0.1` | `0.1` | Existing source F→Q coefficient. |
| `-Dpdd.material.damageGain=2.6` | `2.6` | Existing damage gain. |

`plotBEHandling=VETO_UNSAFE` is the **fixed current safety policy**, not a configurable bypass. Avoid editing the world files in `data/` while the server is running.

## In-game handoff: two sessions

**Session A — source and material regression.** On a backed-up disposable server, collide World↔Plot and Plot↔Plot blocks at one verified contact, separate them physically, then repeat. Include a rotated Plot. Collect `[PDD-NATIVE-NORMAL]`, `[PDD-F-LEDGER]`, `[PDD-F-PROPAGATION]`, `[PDD-Q-ISSUE]`, `[PDD-PLOT] phase=OFFER`, and `[PDD-COMMIT]` lines. Check that the Sable callback contract remains intact and that the source Q budget is not multiplied when both sides receive damage.

**Session B — actual Plot commit, BE/claim veto, restart and split.** On separate uniquely identified Plot UUIDs, impact breakable non-BE blocks until accumulated damage or destruction, then attempt a protected BE, a linked door/bed and an intrinsically unbreakable block; confirm none are modified. Split one damaged Plot, collide the resulting fragments, save and restart the server. Verify `[PDD-PLOT] phase=COMMIT` with `APPLIED_DAMAGE` or `APPLIED_BREAK` only where the block actually changed; verify `OWNER_ORPHAN`, `ABORT_SPLIT`, and no old damage copied to a new owner. Recheck drops and Sable physical mass/collider behavior. **A MovePlan log must not be mistaken for actual movement.** A failed mass/collider recheck is a release blocker, not a test pass.

If a check fails, collect **the complete `latest.log`**, the complete failed Actions job log (if relevant), the repository commit SHA, world dimension, Plot UUIDs, event order and approximate coordinates, server settings, and whether the error is reproducible after restart. Distinguish `APPLIED`, `ABORTED`, `QUARANTINED`, and `DEFERRED`; unavailable chunks or claim veto are not valid proof of a successful physical break.

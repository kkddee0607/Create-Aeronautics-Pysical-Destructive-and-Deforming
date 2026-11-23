# PDD 0.6.1 optiFix Implementation Plan

**Baseline:** 96a4ccf7c56025d211ac4626e73f76392dcb9b39, IDS 1.0.9, spreadFix.

**Goal:** bounded runtime cache and storage overhead; unchanged F/Q, block ownership, material, durability and callback semantics.

**Constraints:** Java 21, NeoForge 21.1.228, Sable 2.0.6, existing optional Copycats+ 3.0.11. No new dependencies; retain Alpha 0.4, 70/30, half-forward, existing F/Q and owner limits. No remote writes, commits, Gradle/JUnit runs by AI. Tests are authored first and statically reasoned; user executes them.

1. Diagnostic modes, bounded timing statistics, repeat-session observer registration, cleanup even on failure.
2. Copycat live signatures and bounded pure calculation cache, deduplicated trace, reload/stop invalidation.
3. Per-plan verified contact evidence reuse without changing live generation, pose, compression, or commit checks.
4. Owner generation retention audit: preserve no-ABA semantics, clear obsolete transient evidence, retain authoritative lifecycle proof.
5. Exact disk-indexed receipt lookup with bounded decoded cache, schema-6 and WAL/transfer compatibility, dirty overlay retained until atomic checkpoint.
6. Exact cold stress archive and origin ownership lookup, bounded cache; preserve active reservations and old .psc files.
7. Verify all existing source paths, JUnit source and expectations, actual JAR signatures, Apply and workflow invariants. Package whole project without .git or build artifacts.

**Review focus:** pending/prepared/quarantined across restart; duplicate cold applyId; write/rename failure; material change within same tick; owner ABA/rotation; session lifecycle exceptions; log mode must not change results. Device/native failure remains unproven by previous log.

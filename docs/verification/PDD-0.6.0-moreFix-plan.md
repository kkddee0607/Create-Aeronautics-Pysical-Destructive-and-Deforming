# moreFix Implementation Plan

**Goal:** Recover from a persisted stress reservation dated later than the restored world game clock without halting independent jobs or replaying ambiguous damage. Reduce repeated preview-only work.

**Architecture:** Keep the saved source identity, original deadline, immutable material and Q, and existing durable receipt transitions. Route future-clock records through bounded recovery instead of letting the handoff exception stop all jobs. Separate diagnostic preview work from authoritative mutation.

**Tech stack:** Java 21 / NeoForge 21.1.228 / Sable 2.0.6 / Create 6.0.10 / Aeronautics 1.3.2 / optional Copycats+ 3.0.11.

**Spec:** IDS 1.0.9 §§24.4–24.6, 36, 37.3, 37.9. Baseline main 8a72c2f7e60525af07ec9331a13d67dd5bab3496.

## Global constraints

- No new Q, duplicate mutation, changed material or damage formula, new dependency, or weakened owner/HeatMap/BE/CAS gate.
- Keep original TTL 1200, shared stress cap 512, derived cap 128, depth 4, F-side cap 32768; no automatic renewal.
- Existing saves, Apply, callbacks, Gradle Wrapper and JUnit setup retained.
- User executes real Gradle/JUnit/Actions and game tests; AI uses static review and hypothetical execution only.

## Review focus

- Future-created active work and already-sealed derived handoff; unrelated current job must progress.
- Durable DEFERRED vs PREPARED/APPLIED; no repeat side effect across crash/restart or archive.
- World and Plot direct origins, derived receipts, bounded queue and truthful unused/rejected accounting.
- Old preview snapshot near capacity; diagnostics must not affect live damage, fail-open or clear authoritative data.
- Real I/O and corrupted receipt failures stay fail-closed; boundary equality and original expiry remain consistent.

## Tasks

1. Add targeted regression tests first, trace restore/offer/commit boundaries, implement safe clock recovery; review both ownership domains.
2. Add preview regressions and reduce diagnostic full-map work without changing authoritative commit or saved-format compatibility.
3. Distinguish the moreFix JAR filename and startup revision; preserve mod ID/version, workflow and classpaths. Review all test inputs and existing assertions.
4. Inspect independent review findings, verify syntax and source manifest, package the complete project exactly once at root, check CRC and Apply rules. User runs actual build and game verification.

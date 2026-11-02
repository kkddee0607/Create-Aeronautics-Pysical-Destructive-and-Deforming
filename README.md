# PDD 0.3.1QFix — Physical Destruction and Deforming

**Runtime:** Minecraft Java 1.21.1 · NeoForge 21.1.x · Java 21 · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6.

**Development baseline:** GitHub `main` commit `d698802106e29587d769b673de1727e2d55c9f0a` (`0.3.1realFix`), full 126 Apply-eligible paths verified byte-for-byte via Git blob SHA against the source ZIP. This distribution preserves the full non-protected repository source. The Apply Action retains `.github`, `incoming`, `gradle`, `gradlew`, and `gradlew.bat` in GitHub without replacing them.

**Design:** [IDS 1.0.5-A](docs/PDD-1.0.5-Integrated-Design-Specification.md), [Sable 2.0.6 Plot-coordinate reverse engineering](docs/research/PDD-Sable-2.0.6-Plot-Coordinate-Forensics.md), [plotRog resolution decision](docs/research/PDD-0.3.1plotRog-Resolution-Decision-Report.md). This build changes provisional development multi-target policy 4→32 per side and region cap 16→64; documents version-specific deviation, not a change to earlier historical specifications.

## 0.3.1QFix changes

1. **Independent Plot/World clearance:** `WorldClearanceVerifier` no longer applies the R4 32-cell target-search limit to the entire Plot AABB. Up to **8,192** expanded footprint cells can be inspected; larger scans fail closed explicitly as `TOO_BROAD`. A dedicated global scan budget of 1,024 cell reads/tick and a maximum of 256 scans/pair/tick bound the incremental work.
2. **Atomic final proof:** A scan spanning multiple server ticks is `IN_PROGRESS`, not proof. At completion, the entire current footprint is rechecked **on one server tick** with a separate global 8,192-read confirmation budget. A new obstruction, unloaded chunk, unavailable Sable plot container, changed footprint, or unverified status cannot promote a pair to `ARMED`.
3. **Rearm without duplicate damage:** The existing `GameImpactCredit` FSM still needs two consecutive, independently completed positive separation observations before `ARMED`; Q calculation is unchanged. An intervening unproved World frame resets the separation streak, and an actual Native contact discards pending clearance progress. The original `ContactCreditLedger` remains unchanged and issues one Q per new episode generation only. Owner generation changes invalidate stale evidence.
4. **Live diagnosis:** `[PDD-WORLD-CLEARANCE]` reports `IN_PROGRESS`, `CLEAR`, `BLOCKED`, `INVALIDATED`, `UNAVAILABLE` or `TOO_BROAD` with scanned/total cells and the exact gate reason. Idle pending scans are pruned; initial precontact work rotates between plots to avoid persistent scan starvation.
5. **0.3.1realFix retained:** Native 100-record saturation flags, R2 duplicate-audit skip, independent R4 budget, up to 32 verified target blocks per side, Q group limit 64, unchanged Sable original callbacks and no world mutation.

**Limit:** Large Plot bounding boxes exceeding 8,192 conservatively expanded World cells cannot be certified by QFix. The atomic verification budget is intentionally finite to protect server tick time. A future Sable-supported spatial index/air-section acceleration can extend coverage without weakening separation proof.

## Preserved contracts

- **World mutation OFF:** this version does not damage, deform, destroy, split or move Minecraft blocks.
- Sable native 15-double record, physics API, original collision callback return and ordering, COM/Plot Body-local coordinate conversion, independent witness R2, R3 cross-substep quarantine, R4 diagnostics only, frame-epoch checks.
- `GameImpactCredit` separation/episode FSM; Q only after positive separation + verified approach; no duplicate credit; force peak, not summed witness forces. Per-group Q maximum 64.
- Existing JUnit sources, prior design/verification documents, Gradle and GitHub Actions Apply contracts.

## Verification and installation

**AI verification:** Java 21 independent compilation of pure Java core, plus selected regression checks under a *test-only* JUnit-style runner. This is *not* a NeoForge Gradle compilation, official JUnit result, Actions success or Minecraft test. Evidence and detailed two-session in-game procedure: [QFix verification report](docs/verification/0.3.1QFix.md).

**Apply:** upload exactly one **complete source ZIP** into GitHub `incoming/`, run/observe existing **Apply PDD ZIP**, then Gradle Build and JUnit/Callback Contract Actions. Install **only the resulting compiled JAR**, not this ZIP, in the game `mods/` directory. Retest B1 against A1/A2/B2/B3 with the same conditions; provide `latest.log` and failed Actions job logs for any regressions. All source ZIP files live directly at the project root (not under an extra containing directory).

# PDD 0.3.1superFix — Physical Destruction and Deforming

## 0.3.1superFix notes (IDS 1.0.6)

Built from GitHub main `473dbdd58286cda5a84941b3bcc5eb60ec711c81`. The source keeps the QFix per-side **32 unique cell audit bound**, 32 verified targets, 64 total regions, Q=64 maximum, single-episode credit, and two independent full-world clearance proofs. These safety limits are not changed on unverified performance assumptions.

Changes: native R2 target auditing now reuses the *same authoritative BlockState/shape read* for geometry evidence instead of re-querying it; caches each cell's collision AABBs and rechecks each independent witness point. Telemetry reports unique cells, reused cells, witness tests and candidate AABBs; force calibration reports approved first-frame peaks, raw Q and saturation distribution without retuning the original Q; clearance profiling reports read counts and elapsed time without changing the atomic proof contract. All world mutation remains OFF.

The original sections below describe the inherited QFix behavior. See [superFix verification](docs/verification/0.3.1superFix.md) and [IDS 1.0.6](docs/PDD-1.0.6-Integrated-Design-Specification.md).


**Runtime:** Minecraft Java 1.21.1 · NeoForge 21.1.x · Java 21 · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6.

**Development baseline:** GitHub `main` commit `473dbdd58286cda5a84941b3bcc5eb60ec711c81` (`0.3.1QFix`). The source ZIP's Git blob SHA `22bb5a8e83191d7bd2348aedef54f62d1225d51a` matches the actual uploaded ZIP. The 129 Apply-eligible original files are preserved. Apply Action retains `.github`, `incoming`, `gradle`, `gradlew`, and `gradlew.bat` at GitHub and does not overwrite them.

**Design:** [IDS 1.0.6](docs/PDD-1.0.6-Integrated-Design-Specification.md), the earlier [IDS 1.0.5-A](docs/PDD-1.0.5-Integrated-Design-Specification.md) (historical), [Sable 2.0.6 Plot-coordinate reverse engineering](docs/research/PDD-Sable-2.0.6-Plot-Coordinate-Forensics.md), [plotRog resolution decision](docs/research/PDD-0.3.1plotRog-Resolution-Decision-Report.md). This build changes provisional development multi-target policy 4→32 per side and region cap 16→64; documents version-specific deviation, not a change to earlier historical specifications.

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

**superFix AI verification:** Java 21 independent compilation of `GameImpactCredit`, `ContactCreditLedger`, `ForceCalibration` succeeded. A separate `assert` runner passed preserved Q/ledger/rearm checks; the three new calibration JUnit methods passed with a **test-only** JUnit-style stub runner. Neither is an official JUnit/Gradle test. A superFix NeoForge Gradle build, GitHub Actions and gameplay are **NOT RUN**. Handoff: [superFix verification report](docs/verification/0.3.1superFix.md); historical [QFix report](docs/verification/0.3.1QFix.md).

**Apply:** upload exactly one **complete source ZIP** into GitHub `incoming/`, run/observe existing **Apply PDD ZIP**, then Gradle Build and JUnit/Callback Contract Actions. Install **only the resulting compiled JAR**, not this ZIP, in the game `mods/` directory. Retest B1 against A1/A2/B2/B3 with the same conditions; provide `latest.log` and failed Actions job logs for any regressions. All source ZIP files live directly at the project root (not under an extra containing directory).

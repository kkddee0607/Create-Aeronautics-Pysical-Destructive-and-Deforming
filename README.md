# PDD 0.3.1duperFix — Physical Destruction and Deforming

## Release 0.3.1duperFix (IDS 1.0.6)

**GitHub source:** `main` at `9563c2c16052a8b42e29b62bc5067bfacb09e085`, November 3, 2026. Source is the existing superFix full Apply-eligible ZIP, with Java/Gradle and IDS blob hashes matched to GitHub main. Do not infer source from a JAR.

**Root fixes:** pre-contact Plot/Plot tracking expiry now uses `lastProcessed` instead of `lastSeen=-1`, preserving proven separation evidence for contact after 600+ ticks. Runtime developer-provisional `forceGain` is now `0.001` instead of `0.25`, preserving the Q=64 cap and linear first-approved force model but avoiding early saturation for observed forces ~3,849–52,421. Original `GameImpactCredit` FSM and `ContactCreditLedger` are unchanged except revision identifiers. No new physics API call or dependency.

**Runtime:** MC Java 1.21.1, NeoForge 21.1.x, Create 6.0.10, Create Aeronautics 1.3.2, Sable 2.0.6. Read-only: `worldMutation=OFF`.

**Design:** [IDS 1.0.6](docs/PDD-1.0.6-Integrated-Design-Specification.md). The gain change is a version-specific `DEV_PROVISIONAL` policy revision under §23; previous defaults remain visible in historical docs and explicit legacy tests. [DuperFix report and two-session game test](docs/verification/0.3.1duperFix.md); [superFix history](docs/verification/0.3.1superFix.md).

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

Code and tests changed; see [0.3.1duperFix verification](docs/verification/0.3.1duperFix.md) for actual execution labels, limits, and test procedure. Previous superFix tests are retained; historical 0.25 gain comparisons use explicit legacy Settings.

**Apply:** upload exactly one whole-project Apply-eligible root ZIP to GitHub `incoming/`. Existing Apply workflow preserves `.github`, `incoming`, `gradle`, `gradlew`, and `gradlew.bat` from GitHub. Run existing PDD Manual Build and Callback Contract Test Actions, then install only the resulting JAR in `mods/`. Report any failed Actions logs and full `latest.log`.

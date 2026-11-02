# PDD 0.3.1realFix — Physical Destruction and Deforming

**Runtime:** Minecraft Java 1.21.1 · NeoForge 21.1.x · Java 21 · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6.

**Development baseline:** GitHub `main` commit `feabf5e4af0fe49ab4e33f1517d925977f0958e1` (`0.3.1plotRog`). All 124 Apply-eligible source paths verified against Git blob SHA metadata. This ZIP includes the full non-protected project source; the GitHub Apply workflow keeps `.github`, `incoming`, `gradle`, `gradlew`, and `gradlew.bat` unchanged in the repository.

**Design:** [IDS 1.0.5-A](docs/PDD-1.0.5-Integrated-Design-Specification.md), [Sable 2.0.6 Plot-coordinate reverse engineering](docs/research/PDD-Sable-2.0.6-Plot-Coordinate-Forensics.md), [plotRog resolution decision](docs/research/PDD-0.3.1plotRog-Resolution-Decision-Report.md). This build changes provisional development multi-target policy 4→32 per side and region cap 16→64; documents version-specific deviation, not a change to earlier historical specifications.

## 0.3.1realFix changes

1. **Correct Plot/World separation:** Previously remembered single World block bounds never prove separation from the whole World. Re-arm only after a complete, loaded, bounded world-collider-clearance check. Keep Plot/Plot full structural AABB separation as before. When clearance is unknown or budget-exhausted, fail closed.
2. **Avoid redundant contact-shape audits:** For an already-exhausted `ACTIVE` impact episode, skip costly R2 voxel-shape verification while retaining native record intake, owner and generation checks, FSM advancement, and all original callback/credit rules. Preserve full audit for `ARMED` and `ACTIVE` with unspent authorization. Separate `r2Audits`, `r2SkippedDuplicate`, `r2ActualShapeChecks` counters from rejected targets.
3. **Bounded multi-block Q ledger:** Preserve verified 5–32 unique blocks per owner side using existing R2 and ledger structures, within the unchanged maximum 32 new block-cell inspections per side. Up to 64 Region entries total. One global episode credit (`Q≤64`), existing 50/50 side shares, deterministic equal split between verified blocks. Incomplete side gets no Q; valid opposite side stays available, other share unused. No invented manifold IDs or speculative target subsets.
4. **Native poll-saturation signal:** Exactly 100 contacts in a Sable `clearCollisions` poll flags `capSaturated`, carried per contact through COM-epoch masking and through logs. This is potential incomplete coverage, not proof records were dropped. No JNI / native mutation.
5. **Separate diagnostic budgets:** World clearance uses a bounded dedicated budget. R4 provisional world candidate diagnostics have an independent smaller budget and cannot mint Q.

## Preserved contracts

- **World mutation OFF:** this version does not damage, deform, destroy, split or move Minecraft blocks.
- Sable native 15-double record, physics API, original collision callback return and ordering, COM/Plot Body-local coordinate conversion, independent witness R2, R3 cross-substep quarantine, R4 diagnostics only, frame-epoch checks.
- `GameImpactCredit` separation/episode FSM; Q only after positive separation + verified approach; no duplicate credit; force peak, not summed witness forces. Per-group Q maximum 64.
- Existing JUnit sources, prior design/verification documents, Gradle and GitHub Actions Apply contracts.

## Verification and installation

**AI verification:** Java 21 independent compilation with *test-only* external API stubs and **selected** JUnit-style regression checks. This is *not* a NeoForge Gradle compilation, official JUnit result, Actions success or Minecraft test. Evidence and detailed two-session in-game procedure: [realFix verification report](docs/verification/0.3.1realFix.md).

**Apply:** upload exactly one **complete source ZIP** into GitHub `incoming/`, run/observe existing **Apply PDD ZIP**, then Gradle Build and JUnit/Callback Contract Actions. Install **only the resulting compiled JAR**, not this ZIP, in the game `mods/` directory. Retest B1 against A1/A2/B2/B3 with the same conditions; provide `latest.log` and failed Actions job logs for any regressions. All source ZIP files live directly at the project root (not under an extra containing directory).

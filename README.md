# PDD 0.3.1plotRog — Physical Destruction and Deforming

**Runtime:** Minecraft Java 1.21.1 · NeoForge 21.1.x · Java 21 · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6.

**Normative design:** [`docs/PDD-1.0.5-Integrated-Design-Specification.md`](docs/PDD-1.0.5-Integrated-Design-Specification.md), 1.0.5-A §§21–23 and 26–28. **Research:** [`docs/research/PDD-Sable-2.0.6-Plot-Coordinate-Forensics.md`](docs/research/PDD-Sable-2.0.6-Plot-Coordinate-Forensics.md), based on Sable 2.0.6 release Rust/Java and supplied JARs. **Baseline:** GitHub `main` commit `5a87ab16c848f4a111055f0b74464a33024718cc` (`0.3.1fix`); all 120 Apply-eligible files in the source archive matched its Git blob SHA set.

## What changed in 0.3.1plotRog

1. **Independent native witness points (R2).** Sable's `local_p1` and `local_p2` are separate contact points on separate bodies. **Their post-effect world separation is no longer a global credit-denial gate.** Dynamic Plot points use `bodyLocal + Sable rotationPoint` to find allocated Plot blocks, then the existing embedded Plot accessor and actual live `VoxelShape` confirm each block. Static `-1` points use the world voxel grid directly. A missing/wrong Plot UUID, invalid frame, unloaded block, or shape miss still cannot authorize that side.
2. **Substep-origin proof.** Sable's `clearCollisions` merges multiple physics substeps and omits the individual substep index. Every observed pre-solver substep must confirm the same Plot UUID/center of mass and the effect frame must match that mass origin; otherwise **that side's frame is masked before R2**. The other side may still be audited and receive only its own Q share. Non-unit scale is not silently trusted.
3. **R3 quarantined (credit disabled).** Projecting one native witness through the last post-solver pose into the other body mixes physics epochs without a per-contact snapshot. This unsupported fallback returns `CROSS_PHASE_UNCALIBRATED`, never a guessed credited block. R1 callback hints still cannot mint native-event Q; R4 remains provisional diagnostics only.
4. **New `[PDD-PLOTROG-WITNESS]` and `[PDD-PLOTROG-EPOCH]` diagnostics.** Witness distance, normal-axis and transverse components, effect-phase owner frames and side status are recorded at a bounded sample frequency. They are **not** interpreted as signed native penetration depth. The native `clearCollisions` **100-record saturation counter** is diagnostic, not proof of dropped records.
5. **Accurate one-sided quality.** A source group with validated targets on only one side reports `PARTIAL`, never `PROVED`.

## Preserved contracts

- **World mutation OFF:** this version never damages, deforms, destroys or splits blocks.
- Sable's original callbacks, return values and the `clearCollisions` array remain untouched. No new native ABI or synthetic null callback.
- `GameImpactCredit` event authorization and separation FSM; native force **maximum**, never per-point sum; Q≤64 once per authorized group; existing ledger with 50/50 side allocation and unused Q.
- Maximum four verified target blocks per side, 32 new block-cell inspections per side per group, 16 regions or compacted eight one-sided regions, no unbounded fallback search.
- Existing and new JUnit test source files remain in `src/test/java`; old verification notes remain in `docs/verification/`.

## Verification

**Status: implemented; official Gradle/JUnit/Actions/Minecraft not executed by the AI.** Independent Java 21 compiler with API-shaped test stubs and a reflective JUnit-style runner passed **62 selected tests**. This does not establish NeoForge compile, actual runtime compatibility or game target accuracy. Detailed evidence and two-session in-game acceptance procedure: [`docs/verification/0.3.1plotRog.md`](docs/verification/0.3.1plotRog.md).

## Apply ZIP workflow

Upload **one** complete source ZIP to GitHub's `incoming/` directory, with no other ZIP present. The verified `Apply PDD ZIP` workflow syncs every **non-protected** project file from the archive. Protected repository files `.github`, `gradle`, `gradlew`, `gradlew.bat`, `.git`, `incoming` remain in GitHub, exactly as prescribed by the existing Apply workflow. Then run existing Gradle Build and Callback Contract Actions, install their compiled JAR in `mods/`, and run B1 against A1/A2/B2/B3. **Do not place this source ZIP in `mods/`.**

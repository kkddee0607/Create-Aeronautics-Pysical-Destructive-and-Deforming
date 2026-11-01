# PDD 0.3.1fix — Physical Destruction and Deforming

**Target:** Minecraft Java 1.21.1 · NeoForge 21.1.x · Java 21 · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6.

**Normative design:** `docs/PDD-1.0.5-Integrated-Design-Specification.md` (1.0.5-A); release 0.3.1fix inherits 0.3.1 and is governed by §§14.1, 21–23 and 26–28. **Source baseline:** GitHub `main` at `191185a61faf25ab8d45ffd9fafd2426f969aa44` (`0.3.1verf`); repository renamed without trailing hyphen. Historic versions and their evidence are preserved in `docs/verification/`.

## What 0.3.1fix fixes and measures (read-only)

This version preserves the **0.3.1 episode, group-Q and damage policies** but modifies target inspection: a group-scoped cache reuses actual owner/block/shape lookups for the same allocated cell, while **every distinct contact point still undergoes shape-distance verification**. The 32 inspections per side count unique cell lookups, not duplicate point observations. R3 consumes only its actual remaining bounded checks. A side exceeding four verified targets is quarantined (its Q remains unused); the other side can retain its independently complete, verified target set. If verified region pair combinations alone exceed 16, they compact into at most eight verified one-side regions without fabricating pairs or multiplying Q. An actual mismatch is never forced to match by expanding tolerances. `PDD-VERF-FSM` records pre/post state, evidence generation and consumption, and both native/Java server tick clocks. `PDD-FIX-FRAME` records transformed world-space point residuals, world A/B, delta, frame poses and source indices for mismatched samples. `PDD-VERF-REGION` records the exact record and budget/region/owner reason for a target rejection; `PDD-VERF-R4` explains provisional world-side candidate rejection; `PDD-VERF-SEPARATION` shows evidence that armed the pair. Identical stable failures are sampled (first/change/every 100 server ticks by default; `-Dpdd.verf.enabled=false` disables new telemetry and `-Dpdd.verf.sampleTicks=100` configures sampling (minimum 20)). Q and world mutation are unchanged. See `docs/verification/0.3.1fix.md`.

## Inherited 0.3.1 implementation

- Original Sable `clearCollisions(J)[D` is observed without changing the original source array or Sable callback/return contract. No synthetic null callback is installed.
- Native effect force is a **game force proxy**, neither impulse nor joules; its group maximum, **not the sum of native points**, feeds the capped `Q_game = min(64, 0.25 * max(0, F_peak - 2))` policy.
- Server-thread contact regions use owner-specific, non-air, loaded block/shape checks on corroborated native per-record contact frames. Duplicate reports of a block deduplicate; distinct verified contact blocks remain distinct. Up to four targets per verified side, 32 unique owner/cell inspections per side per group, at most 16 correlated or eight compacted one-sided regions, and bounded R4 diagnostic exploration.
- `GameImpactCredit` remains the only pair-episode **grant authority**. Validated approach/separation, owner generation and server run delimit episodes. The new `ContactCreditLedger` reserves and splits that **one** group Q across verified target blocks (default 50/50 A/B); unverified portions are recorded as *unused*, never allocated to guessed blocks. Native point ordinals do not become synthetic solver IDs.
- `PDD-LEDGER`, `PDD-EPISODE`, `PDD-TARGET` and `PDD-0.3.1fix` logs report generation, evidence and reservation. Ledgers have bounded capacity and TTL; owner disappearance/replacement invalidates their histories.
- **`worldMutation=OFF`:** 0.3.1 does **not** apply world damage, deform, split, or destroy blocks. R4 is diagnostic-only. Legacy source and tests are preserved; the project does not claim solver-grade manifold/impulse access.

## Build & validation status

This ZIP is an **Apply-compatible complete non-protected source candidate**. Java 21 stub-assisted compiler and selected checks passed; the official NeoForge Gradle compilation/JUnit, GitHub Actions and Minecraft validation have **not** been executed here. See `docs/verification/0.3.1fix.md` for this verification build; `docs/verification/0.3.1.md` preserves prior evidence. Actual compilation or in-game success must not be inferred from local stubs.

## Apply ZIP workflow

1. Remove old `incoming/*.zip` from the GitHub repository and upload this ZIP as its single incoming archive.
2. The existing `Apply PDD ZIP` workflow will sync all non-protected project files from the ZIP root. It preserves `.github`, `gradle`, `gradlew`, `gradlew.bat`, `incoming` and `.git` from GitHub.
3. Run the preserved Gradle Build and JUnit/Callback Contract workflows. Only after successful build, install the built JAR in the Minecraft `mods/` folder; **do not** copy the source ZIP into `mods/`.
4. Test B1 against A1/A2/B2/B3 using the acceptance criteria in the verification report; submit the full latest.log for any failure.

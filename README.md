# PDD 0.3.0realFix — Physical Destruction and Deforming

Target: Minecraft Java 1.21.1, NeoForge 21.1.228, Create 6.0.10, Create Aeronautics 1.3.2, Sable 2.0.6, Java 21.

Normative specification: `docs/PDD-1.0.5-Integrated-Design-Specification.md` (revision 1.0.5-A, especially §§14, 21–23, 26–28). Baseline GitHub `main` SHA: `db4fc84564de39b60ef5d150cca3313e245853c2`.

## Development scope

- Sable `clearCollisions(J)[D` post-effect array observed once without changing its reference, contents or original consumer.
- Original *non-null* special Sable callbacks remain delegated once with the identical return or thrown exception. `null` callbacks remain `null`.
- Native 15-double samples are validated, bounded and grouped by dimension, observed tick, owner pair and maximum `F_raw` **without pointwise sums**. `F_raw` is not joules/impulse.
- Owner UUID/runtime snapshot and existing callback-owned R1 target are verified before any game-budget credit. R2/R3 uncalibrated points cannot be treated as verified; R4 boundary candidates are non-authoritative shadow evidence.
- Minimal four-phase one-credit game episode and strictly positive separation/closing evidence; no automatic rearm on missing callbacks or time alone.
- Provisional Q formula: `min(64, 0.25 * max(0, F_peak - 2))`; uncertain side Q is reserved, not fabricated. Normal world mutation remains OFF in this version.
- Structured `PDD-OBS`, `PDD-TARGET`, `PDD-EPISODE`, and `PDD-REALFIX` server logs, bounded hot-path diagnostics.

## Validation status

This is a **source package** and is not evidence of Gradle, GitHub Actions or in-game success. See `docs/verification/0.3.0realFix.md` for the exact outcome matrix and game test protocol. A validation-blocked candidate must not be misrepresented as a verified release.

## How to use the full-source Apply ZIP

Upload the single full-source ZIP to `incoming/` of the GitHub repository after removing the pre-existing `incoming/*.zip`; the existing Apply action will synchronize all **non-protected** project files. The protected `.github/`, `gradle/`, and `gradlew*` files remain from the repository. Inspect the Apply commit and then run the preserved Build and Callback Contract workflows.

Do not directly extract this ZIP into `mods/`. After Build success, use the generated JAR in the Minecraft `mods/` folder and test it with matching dependency versions.

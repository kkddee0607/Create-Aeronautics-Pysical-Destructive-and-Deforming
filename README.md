# PDD 0.3.0duperFix — Physical Destruction and Deforming

Target: Minecraft Java 1.21.1, NeoForge 21.1.228, Create 6.0.10, Create Aeronautics 1.3.2, Sable 2.0.6, Java 21.

Normative specification: `docs/PDD-1.0.5-Integrated-Design-Specification.md` (revision 1.0.5-A, especially §§14, 21–23, 26–28). Baseline GitHub `main` SHA: `ed5f206ed06883ce90de85ae847f3211489a31b0`.

## Development scope

- Sable `clearCollisions(J)[D` post-effect array observed once without changing its reference, contents or original consumer.
- Original *non-null* special Sable callbacks remain delegated once with the identical return or thrown exception. `null` callbacks remain `null`.
- Native 15-double samples are validated, bounded and grouped by dimension, observed tick, owner pair and maximum `F_raw` **without pointwise sums**. `F_raw` is not joules/impulse.
- Post-effect Sable `activeSubLevels` mapping snapshots the exact owner UUID and logical pose at source time. Native R2 consumer-grid candidates and R3 cross-side projections use owner-specific loaded-block/shape checks, never static-world coordinates for Plot storage. R4 is bounded, provisional diagnostics only. Sable-consumer compatibility is not equivalent to a fully calibrated Rapier collider pose; runtime acceptance still needs in-game proof.
- Minimal four-phase one-credit game episode and strictly positive separation/closing evidence; no automatic rearm on missing callbacks or time alone.
- Provisional Q formula: `min(64, 0.25 * max(0, F_peak - 2))`; uncertain side Q is reserved, not fabricated. Normal world mutation remains OFF in this version.
- Structured `PDD-OBS`, `PDD-TARGET`, `PDD-EPISODE`, and `PDD-DUPERFIX` server logs, bounded hot-path diagnostics.

## Validation status

This is a **source package** and is not evidence of Gradle, GitHub Actions or in-game success. See `docs/verification/0.3.0duperFix.md` for the exact outcome matrix and game test protocol. A validation-blocked candidate must not be misrepresented as a verified release.

## How to use the full-source Apply ZIP

Upload the single full-source ZIP to `incoming/` of the GitHub repository after removing the pre-existing `incoming/*.zip`; the existing Apply action will synchronize all **non-protected** project files. The protected `.github/`, `gradle/`, and `gradlew*` files remain from the repository. Inspect the Apply commit and then run the preserved Build and Callback Contract workflows.

Do not directly extract this ZIP into `mods/`. After Build success, use the generated JAR in the Minecraft `mods/` folder and test it with matching dependency versions.

## 0.3.0duperFix independent recheck (IDS 1.0.5-A)

The original source was independently reviewed against the full 1.0.5-A contract. The R2 consumer candidate now requires corroboration between both native effect world points; pre-contact world/Plot bounding boxes are aligned to canonical owner order; R2/R3 counters are separated. This version is **not officially Gradle-built or in-game verified**. See `docs/verification/0.3.0duperFix-recheck.md` for exact test grades, binary evidence, and the two-session verification protocol. Existing Apply protected files and workflow files remain untouched.

## 0.3.0duperFix — IDS 1.0.5-A incident-ledger and contact-target refinement

Updated after the B1→A1/A2 and B1→B2 in-game tests. R2/R3 audit each corroborated native contact record in its own owner-local frame, deduplicating exact point checks without multiplying Q; distinct verified blocks remain ambiguous. R4 tracks a bounded union of contact-local swept-boundary cells but never upgrades provisional geometry to damage authority. FSM diagnostics now distinguish older positively observed separation in an ARMED pair from recent gap and current AABB closing. Group Q remains capped at 64, callback behavior and worldMutation=OFF remain unchanged.

**Build status:** source-only candidate. Standalone Java 21 type checking and 26 stub-assisted assertion scenarios passed, NOT Gradle/JUnit or in-game validation. See `docs/verification/0.3.0duperFix.md`.

# PDD 0.2.12-fix — 1.0.1 design-aligned source patch

**Base GitHub SHA**: `3ab7c2685c62021797ed3b56cdf125474d96ab33` (2026-10-29; 0.2.12 source).
**Baseline successful CI**: [GitHub Actions run 40856866629](https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/actions/runs/40856866629) — `./gradlew test --no-daemon` and `./gradlew build --no-daemon` passed, JAR artifact uploaded.
**Binding design**: `docs/PDD-1.0.1-Integrated-Design-Specification.md` §3.2, §14.1, §14.2 and §15.2.

## Fix scope

- Preserve the already-CI-proven 0.2.12 Java implementation: callback-decorating Mixin, original-once response preservation, null-callback observation, bounded queue, raw DTO, diagnostic effects feed. No damage or deformation mutations are enabled.
- Keep **all** GitHub-CI-proven Gradle dependency and classpath entries untouched, including both main runtime and compile classpaths. Retain minimal Sable API and Companion JUnit runtime dependencies with `transitive = false`; do not bundle any Sable classes in PDD.
- Set Gradle project version, manifest `Specification-Version`, mod TOML version, and runtime log build version to `0.2.12-fix`.
- Include the actual integrated design 1.0.1; record the successful parent CI and outstanding in-game acceptance evidence explicitly.

## Evidence and acceptance

- **PASS (upstream build, identical Java behavior and dependencies; new version labels only):** `Run JUnit tests`, `Build JAR`, `Upload JAR artifact` for baseline SHA `3ab7c268`.
- **NOT RUN (fix ZIP itself):** new `0.2.12-fix` GitHub CI. Version stamp changes require confirmation by a fresh Actions run.
- **NOT RUN:** `G-COL-01` gameplay: ordinary stone and wood vs stationary terrain, and Fragile/Belt/TNT behavior comparison against PDD-absent control, including JNI null-callback physical regression.
- **NOT RUN:** live NeoForge Mixin injection and the original Sable native response parity.

Do **not** promote this patch to `G-COL-01 VERIFIED` or release as gameplay-proven until the above tests pass. The source fixes a stable baseline; it cannot guarantee success in every external runtime.

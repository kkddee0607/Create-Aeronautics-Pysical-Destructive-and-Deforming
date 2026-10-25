# PDD 0.2.0-debug — Release Notes

Diagnostic-only fork from the 0.2.0 API-verification scaffold.

### Added
- `[PDD-DIAG]` startup/version record.
- Synthetic startup smoke checks for existing material, damage, impact-sample, damage-state and bounded-observation models.
- Explicit limitation record so logs cannot imply that live Sable collision, world damage or destruction is implemented.

### Not enabled
- No Sable callback or Mixin was added.
- No collision-derived damage, block destruction, deformation, stress propagation, persistence or world mutation was enabled.
- Synthetic observations are not runtime collisions.

### Verification status
- Source structure was checked against the repository's `main` 0.2.0 model/API-boundary files.
- GitHub branch creation was denied to the connected integration (HTTP 403), and the local build environment cannot reach GitHub or resolve Gradle distribution downloads.
- Therefore this ZIP is a **source package, not a verified build artifact**. Run `./gradlew test --no-daemon` and `./gradlew build --no-daemon` in GitHub Actions before producing/using the debug JAR.

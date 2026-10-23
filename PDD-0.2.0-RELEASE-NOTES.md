# PDD 0.2.0 — Release Notes

This is a development package, not a gameplay release.

### Changed
- Version metadata and project documentation updated to 0.2.0.
- Added API-independent `CollisionObservation`, `ObservationKey`, and bounded `ObservationBuffer` diagnostic primitives.
- Added unit tests for input validation, preservation of signed velocity, bounded recording, dropped observation accounting, immutable snapshots and identity-key validation.
- Documented verified Sable Java contracts and remaining runtime API gates.

### Not enabled
- No Sable callback registration or Mixin was added.
- No collision-derived damage, block destruction, deformation, stress propagation or world mutation was enabled.

### Verification
- Java 21 compiler successfully compiled all main Java source files except the NeoForge-annotated mod entry point (which requires the NeoForge development dependencies).
- Full Gradle/JUnit execution was attempted, but the environment could not resolve `services.gradle.org`; the Gradle distribution is not locally available.
- Do not call this package a fully verified Gradle build until CI runs against this exact source.

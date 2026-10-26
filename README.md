# PDD 0.2.1-debug — Apply Action source ZIP

Physical Destruction and Deforming for Minecraft Java 1.21.1, NeoForge >=21.1.228,
Create 6.0.10, Create Aeronautics bundled 1.3.2 and Sable 2.0.6.

**Source package only. Not a built `.jar`, a validated gameplay release, or proof of live collisions.**

## Changes

- Optional Sable callback-return instrumentation: `-Dpdd.debugCollisions=true` enables raw bounded `[PDD-COLLISION]` logs. Only the verified concrete Sable callback implementations can be seen. No callback response is overwritten.
- Server startup and recipe reload build a registered block ID → 3-byte-compatible material table; look for `[PDD-MATERIAL]` logs.
- Optional `config/pdd-materials.json` overrides with precedence above deterministic recipe heuristics. See `pdd-materials.example.json`.
- Hardness conversion with boundary and monotonicity unit tests.
- Corrected the prototype damage model to stop treating ductility as direct damage attenuation, as required by design draft 3.3.1.
- No world mutation, physical destruction, mass/energy conversion, collider refresh or verified ordinary-block collision sampling.

## Apply workflow

The repository's `Apply PDD ZIP` action expects exactly one ZIP at `incoming/*.zip`.
This archive has one project root containing `build.gradle`, `settings.gradle`, and `src/main/java/`.
It intentionally **does not** include `.github/`, `incoming/`, or Gradle wrapper binaries because the Apply workflow protects those repository paths.

After the Apply action, trigger the separate Gradle Build workflow. The artifact produced by
Gradle (`build/libs/physical-destroy-deform-0.2.1-debug.jar`) is the file to install,
not this ZIP. The supplied source has not been fully compiled or executed in Minecraft here.

See `docs/PDD-0.2.1-IMPLEMENTATION-STATUS.md` for issue-by-issue evidence and limitations
and `docs/PDD-0.2.1-TEST-PLAN.md` for test procedures.

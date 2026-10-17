# Create Aeronautics: Physical Destroy and Deform — v0.1.0

## Status

**Source prototype only. This is not a completed gameplay addon and has not been compiled or tested in Minecraft.** The current environment had Java 21 but no Gradle installation, and the Gradle distribution could not be downloaded because network DNS was unavailable. No JAR is included.

## Target

- Minecraft Java 1.21.1
- NeoForge 21.1.172
- Java 21
- Intended integration: Create Aeronautics + Sable (exact runtime API still to be verified)

## Included in 0.1

- NeoForge mod project scaffold
- Compact material defaults: hardness `0..255`, ductility `0..100%`, elasticity `0..100%`
- Compact per-block state: damage, deformation, crack visualization, each `0..255`
- 24-bit pack/unpack helper for those three per-instance state values
- Pure damage math prototype: hardness and ductility reduce local damage; elasticity controls the neighbor-stress share

## Not implemented yet

- Sable collision callbacks and physical-structure coordinate conversion
- Actual block state persistence / sparse per-world state store
- Applying block damage to physical structures or static terrain
- Real block movement/deformation and collision-shape refresh
- Explosion extension and projectile integration
- Crack rendering/recovery
- Item drops and block-entity preservation
- Automatic structure-splitting verification
- Performance/load testing

These omissions are intentional rather than simulated. The addon does not claim to destroy or deform blocks in-game yet.

## Build

With JDK 21 and network access to Gradle Plugin Portal and NeoForge Maven:

```bash
gradle build
```

The project uses Gradle 8.x with NeoForge ModDevGradle. Resolve any plugin/API compatibility errors against the exact target environment before treating the build as verified.

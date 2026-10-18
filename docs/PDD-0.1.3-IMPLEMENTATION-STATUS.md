# PDD 0.1.3 implementation status

## Governing inputs

- Design: PDD development draft 3.3.1
- API reference: `PDD-0.1.3-API-Arguments-Reference.md`
- Previous source: PDD 0.1.2 (reference only; not the design authority)
- Target binaries: Sable 2.0.6, Create 6.0.10, Create Aeronautics 1.3.2

## Included changes

1. Version/build/mod metadata updated to `0.1.3`.
2. Direct damage depends on hardness only. The hardness curve's minimum factor and exponent are explicit inputs rather than invented defaults.
3. Ductility is excluded from direct damage and remains reserved for deformation calculations.
4. Elasticity continues to control PDD's neighbor-stress transfer only.
5. Existing API-independent impact model and byte-packed state helpers are retained as reusable prototype components.
6. The supplied API report is included verbatim as a design/development reference.

## Runtime integration decision

No Sable callback is registered in this release. The supplied report marks the relevant evidence as incomplete, and specifically prohibits approving collision damage before API-01 through API-08 are resolved. A speculative hook could duplicate impacts, corrupt collision response, misinterpret coordinates/velocity, or mutate the world at an unsafe time.

## Blocking gates before runtime damage implementation

- API-01: establish native callback trigger conditions and call frequency.
- API-02: establish `impactVelocity` unit, sign, and measurement timing.
- API-03: establish coordinate spaces for impact position and both block positions.
- API-04: establish coverage for ordinary blocks without callbacks.
- API-05: prove preservation of existing Sable callback responses.
- API-06: verify mass/effective-mass semantics and units.
- API-07: verify safe server-world mutation timing and thread context.
- API-08: verify collider refresh for static-world and sublevel block changes.
- API-09: prevent duplicate accounting across Create contraption and Sable paths before enabling that integration.
- API-10: finish property inference and fallback policy before completing material auto-registration.

## Not included in 0.1.3

No runtime collision adapter, collision deduplication, world mutation queue, material registry, sparse world-state persistence, neighbor traversal, deformation placement, destruction, explosion/projectile hooks, rendering, or game compatibility claims. These remain later work once the API gates are satisfied.

## Validation status

This archive must not be described as a successful Gradle build, a built mod JAR, or a tested in-game release unless those checks are separately executed. The calculation changes have unit-test source coverage; runtime integration is intentionally absent.

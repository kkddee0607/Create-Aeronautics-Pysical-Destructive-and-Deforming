# PDD 0.2.0 Roadmap Review

Reviewed on 2026-10-23 against `issues.json`, the PDD 0.1.3 API reference and current GitHub issues.

## Version alignment

The roadmap defines 0.2.0 as **Physics API call-path verification**, not runtime damage implementation. PDD-040 through PDD-045 (adapter, input validation, deduplication, collision-pair scope and diagnostics) are currently assigned to 0.3.0. The 0.2.0 work therefore establishes evidence and safe interfaces; it must not silently skip unresolved prerequisites.

## Issue audit

| GitHub issue | Roadmap ID | Evidence now available | Remaining work |
|---|---|---|---|
| #20 | PDD-033 | Callback bridge makes other position null when `hasOtherBlock=false` | Runtime collision classes and safe target policy |
| #21 | PDD-034 | `CollisionResult` contains tangent motion and collision-removal flag | Instrumented tests preserving original results for special callbacks |
| #22 | PDD-035 | Sable pre/post physics events and pipeline phases are documented | Verify thread, callback phase and safe queue-drain point in the target game |
| #23 | PDD-036 | `handleBlockChange` exists as a server-side physics-pipeline notification | Static-world and sublevel collider regression tests |
| #24 | PDD-037 | Existing report contains Java call chain and known unknowns | Attach experimental evidence for PDD-030–036; prose alone is insufficient |
| #25 | PDD-040 | Block-scoped callback API is confirmed | Implement only in 0.3.0 after gates pass; do not activate speculative integration |
| #26 | PDD-041 | No runtime adapter currently exists | Adapter target/level validation tests |
| #27 | PDD-042 | `ImpactSample` rejects invalid finite/range values | Adapter-specific normalization and rejection-reason metrics |
| #28 | PDD-043 | Native call cadence is not confirmed | Establish cadence then test deduplication policy |
| #29 | PDD-044 | Callback selector is not global across all blocks | Runtime coordinate/collision-pair coverage |
| #30 | PDD-045 | Bounded API-independent observation buffer added here | Wire debug-only metrics after the callback route is verified |

## Issue closure rule

PDD-030 through PDD-036 require native/runtime experiments. Source inspection confirms contracts and method shapes but not callback frequency or live-world collider behavior. Keep the issues open until evidence is attached.

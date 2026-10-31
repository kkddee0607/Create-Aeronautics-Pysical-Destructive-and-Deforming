# PDD 0.3.0 FIX — IDS 1.0.3

**Status: source implementation ready for build verification. Not a verified release.**

## Change

- Verified Sable 2.0.6 callback `impactPosition` is hit-space (global for static world); callback `impactVelocity` is unsigned (`abs`) and cannot prove approach.
- Keep existing real `TargetKey` identity and Sable original collision callbacks unchanged.
- New cheap **BLOCK_FACE_ESTIMATE** for audited full-collision-cube world blocks. A unique single Sable contact face supplies approximate, signed direction to the existing conservative **translation-only** proxy budget. No exact point matching, no block-center normal, no fabricated unit/impulse claims.
- Explicit `P-16` factor and shared step cap, per-step owner-pair grouping and cross-tick continuous-contact duplicate guard. Rotation-dominated, separating, ambiguous or unverified contacts reject with named reasons.
- New diagnostic `PDD-G-MATH-01 phase=APPROVED` includes actual target identities, direction source, speed, mass and budget; no damage/world mutation.

## Build and game verification

Only local Java 21 auxiliary test runner (71 passing tests) and auxiliary type-check completed. Official Gradle commands attempted but wrapper absent in Apply ZIP container (exit 127). Source JUnit declares 85 test cases; actual JUnit engine not executed here. GitHub Actions and game tests pending. See `docs/verification/0.3.0-fix.md` for all checks, deployment process and two-session acceptance.

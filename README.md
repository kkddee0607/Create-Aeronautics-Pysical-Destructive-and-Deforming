# PDD 0.2.12-fix — Create Aeronautics Physical Destruction and Deforming

**Target:** Minecraft Java 1.21.1 · NeoForge 21.1.228 · Create 6.0.10 · Create Aeronautics bundled 1.3.2 · Sable 2.0.6.

**Specification:** `docs/PDD-1.0.1-Integrated-Design-Specification.md`, §3.2 / §14.2 / `G-COL-01`.

0.2.12-fix is a minimal stability patch on top of the latest **CI-passing** GitHub 0.2.12 commit `3ab7c2685c62021797ed3b56cdf125474d96ab33`. All collision-capture Java source and existing JUnit assertions are preserved. This version observes Sable callback results; it **does not implement block damage, deformation or world mutation**.

## GitHub import (current repository only)

Upload this **full source ZIP** under `incoming/` while the GitHub repository remains at the reviewed base SHA. The existing `.github/workflows/apply-pdd-zip.yml` will import source files; it preserves `.github`, `gradle/`, `gradlew`, `gradlew.bat`, `incoming/`, and `.git`. This ZIP contains every other existing tracked project file as of the reviewed base SHA (plus the 1.0.1 spec and fix notes), so the import should not delete historical files.

If the repository has advanced beyond the reviewed SHA, **do not** use the destructive ZIP-sync workflow; instead manually apply the changed files in a Git checkout and compare `git diff` first.

## Build in the GitHub checkout

```bash
chmod +x gradlew
./gradlew clean test build --no-daemon
```

The ZIP itself does not embed the Gradle Wrapper, because that is protected and already present in the connected GitHub checkout. A standalone extracted ZIP needs the original GitHub `gradlew`, `gradle/wrapper/*`, and a network-enabled dependency resolver.

**Previous baseline succeeded in GitHub Actions:** https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/actions/runs/40856866629
**0.2.12-fix binary remains unverified until a new CI run.**

## G-COL-01 in-game verification

See `docs/verification/0.2.12-fix.md` and `docs/verification/0.2.12.md`. No build test can prove an actual ordinary-block JNI callback was invoked or guarantee original Sable physical response parity.

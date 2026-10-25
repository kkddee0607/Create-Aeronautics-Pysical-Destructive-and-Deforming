# PDD 0.2.0-debug — Test Plan

## A. Confirm diagnostic class is loaded
1. Install only this debug JAR in the target 1.21.1 NeoForge instance with the expected dependencies.
2. Start Minecraft and search `latest.log` for `[PDD-DIAG]`.
3. Expect `INIT ... status=LOADED`, five `SELFTEST ... status=PASS` records, `SELFTEST_SUMMARY status=PASS`, a `RUNTIME_INTEGRATION` limitation record, and `INIT_DIAGNOSTIC status=PASS`.

## B. Interpret results correctly
- `SELFTEST ... PASS`: only confirms the current API-independent model passed its synthetic startup checks.
- `RUNTIME_INTEGRATION ... NOT_IMPLEMENTED/DISABLED`: expected for this version and must remain explicit.
- Missing all `[PDD-DIAG]` messages: likely indicates the PDD debug JAR was not loaded, the wrong JAR was installed, or startup failed before the constructor ran; compare mod list and earlier exceptions.
- `SELFTEST_SUMMARY status=FAIL`: attach the complete `latest.log`; the summary includes the exception trace.

## C. What this version cannot test
A block colliding with a Create Aeronautics structure cannot trigger a PDD live-collision log in this build. The 0.2.0 source does not register a Sable callback. Do not report a failed collision-damage feature based on absence of live events: the runtime feature is not implemented yet. The next implementation stage still needs API-01–API-09 runtime evidence from the design/API report before enabling world mutation.

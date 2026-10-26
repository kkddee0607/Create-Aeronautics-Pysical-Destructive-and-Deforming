# PDD 0.2.1-debug — log test plan

1. Upload this source ZIP to `incoming/` in GitHub and let `Apply PDD ZIP` finish. This action copies project files; it is **not** a compilation or test task.
2. Trigger the repository Gradle Build workflow. Require `./gradlew test build --no-daemon` to pass. Do **not** install the source ZIP as a Minecraft mod.
3. Install the resulting debug JAR on Minecraft 1.21.1 with NeoForge >=21.1.228, Create 6.0.10, Sable 2.0.6 and bundled Aeronautics 1.3.2, plus their dependency mods.
4. For callback telemetry, add JVM flag `-Dpdd.debugCollisions=true` at the **Java process** startup (not in the chat/console). The default is disabled.
5. In `latest.log`, inspect `[PDD-DIAG] INIT`, the six `SELFTEST` checks, `[PDD-MATERIAL] rebuild`, and `[PDD-DIAG] SABLE_BRIDGE`.
6. Test a Sable fragile block, TNT, beehive, static-vs-sublevel and sublevel-vs-sublevel impacts, plus slow vs fast and sustained contact. Expect `[PDD-COLLISION]` only for supported callback-bearing blocks; absent ordinary-block logs are a known limitation, not proof of no physical collision.
7. If a collision record is present, compare raw hit pair, signed normal velocity, tangent output and removeCollision with the existing Sable behavior. Collect log across server and client (if applicable), mark coordinate frame unverified and do not infer energy or world damage.
8. On `/reload`, look for `[PDD-MATERIAL] rebuild generation=`. Edit `config/pdd-materials.json`, reload, ensure `overrides=` and generation update without old/new mixed cache entries.
9. Verify malformed or unknown ID overrides log a warning without killing the server.
10. Disable telemetry to confirm that no `[PDD-COLLISION]` lines are produced; damage, deformation and destruction must remain disabled.

Do not mark gates G1/G2/G3/G4/G5/G6/G7/G8 as runtime passed until evidence appears in actual logs. A passing JUnit test or `SELFTEST` is synthetic and insufficient.

# PDD 0.2.11-debug — Sable collision telemetry

Source ZIP for GitHub **Apply PDD ZIP** (not a Minecraft mod JAR). The Java side hooks into **Sable 2.0.6's actual native collision effects array** and, independently, two existing Sable block-specific callbacks. The hook only observes; it does not change Sable physics, collision return values, world state or destruction. **Built but NOT runtime-confirmed** is not a release claim; this is a source candidate requiring GitHub build and actual gameplay validation.

**All collision telemetry ON by default.** No JVM option required. Use `-Dpdd.debugCollisions=false` only if explicitly desired. Record `[PDD-COLLISION]` (native) and `[PDD-SABLE-CALLBACK]` (specific callbacks) in `logs/latest.log`; every 100 server ticks the counters explain absent samples.

See `docs/PDD-0.2.11-RUNTIME-COLLISION-TEST.md` for mobile installation and test matrix. The GitHub `Apply PDD ZIP` workflow preserves its own `.github/` and Gradle wrapper; `PDD Manual Build` artifact may still be labelled **PDD-0.2.1-debug** because the protected workflow was not modified. Verify JAR filename version instead.

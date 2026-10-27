# PDD 0.2.1-debug — historical implementation status

The 0.2.1 implementation provided a type-level material cache and attempted Fragile/Belt callback telemetry. The Android log from 2026-10-26 shows `debugCollisions=false` and did not prove live Sable collision reception. A later GitHub 0.2.1 change used `Boolean.getBoolean(System.getProperty("pdd.debugCollisions", "true"))`, which also evaluates false in the ordinary default environment. See the 0.2.11 release notes and runtime test document for the corrected behavior.

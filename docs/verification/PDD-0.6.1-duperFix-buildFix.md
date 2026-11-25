# PDD 0.6.1-duperFix-buildFix — compileTestJava and release-metadata repair

## Verified GitHub baseline and first failure

- Repo: `kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming`
- `main` SHA: `c2466405bc18b7bbfa69042ea18c8dfb2304dd26` (2026-11-25)
- Existing complete source: 388 tracked files, restored from `PDD-0.6.1-duperFix-FULL-SOURCE.zip`. Verified Git Blob SHA, file path, size and executable modes against the latest main tree.
- GitHub Actions failure: [Manual Build #45583354353](https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/actions/runs/45583354353).
- **First failure:** `src/test/java/com/physicaldestroydeform/collision/DuperFixBilateralSurfaceTest.java:148`, `error: package DamageModel does not exist` in `DamageModel.Settings.defaults()`.
- Actual `compileJava` and `processResources` passed. Actual `compileTestJava` failed (one diagnostic). `test`, `build`, `jar` were **NOT RUN**, and the new eleven JUnit tests were not executed.
- Last known full successful baseline: [Manual Build #45524414340](https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/actions/runs/45524414340) at `e611793adc98042c34149516f1b8eb5adbb2bb6b`, before adding the duperFix bilateral source path. Its 781 declared JUnit tests are preserved.

## Implemented correction

1. `DuperFixBilateralSurfaceTest.java`: add the actual `import com.physicaldestroydeform.DamageModel;` used by the new bilateral F/Q integration fixture. No bypass of its `issuePropagatedTargets()` path.
2. `README.md`: display the current release, correct JAR installation target, link this verification, explicitly label historical release advice as historical.
3. `PhysicalDestroyDeform.java`: update `BUILD_VERSION` and server-start `revision=` to `0.6.1-duperFix-buildFix`, update face-recovery log descriptor to the implemented bilateral voxel path.
4. `src/main/resources/META-INF/neoforge.mods.toml`: update mod's **NeoForge in-game displayed version**, without modifying modId, mixin, dependency ranges or save schema.
5. `build.gradle`: use the same release string as Gradle project version and JAR Manifest `PDD-Revision`, clear the redundant classifier so the single JAR name is `physical-destroy-deform-0.6.1-duperFix-buildFix.jar`. Java 21, NeoForge 21.1.228, Maven and runtime/test dependencies, and existing JUnit execution settings remain the same.
6. `scripts/check-duperfix-contract.py`: read-only, standard-library static guard for release metadata consistency, missing `DamageModel` imports in test classes (supports direct/wildcard imports), and the unchanged number of JUnit declarations. No external dependency or workflow modification.

**No changes** to `BilateralContactSurface`, `SableEffectShapeReader`, `SableContactRegions`, `ContactCreditLedger`, `ForcePropagation`, `DamageModel`, F/Q/elasticity/material constants, World/Plot commit, WAL/fdatasync, callbacks, existing tests or existing assertion values. Known failing issue is a missing Java import, not a failing physical calculation.

## Original and new JUnit contract review

The existing `@Test` inventory is **792**: 781 prior declarations and eleven in `DuperFixBilateralSurfaceTest`. Prior 781 have an Actions success baseline. The eleven new test expected values must be checked against source logic, *not* guessed or modified merely to make assertions pass:

| New test / setup | Source-derived expected behavior |
| --- | --- |
| One native record, 5×5 aligned whole cubes | 25 positive-area pairs, `BILATERAL_SURFACE` |
| Center hole on opposite side | 24 pairs, no target at (2,2) on side B |
| Opposite 3×3 inside 5×5 | 9 real pairs, no unsupported 5×5 extrapolation |
| Same-owner covering block above A | 0 exterior face pairs |
| Identically rotated poses | 25 pairs with equal body-frame rotation |
| Wrong native tick or bodies separated | stale evidence rejected; separated bodies have 0 area pairs |
| Positive gap of 0.01 voxel | 0 pairs (`depth > EPS`) |
| Shallow penetration of 0.02 voxel | 25 pairs (`depth >= -1/32`) and independently checked exterior faces |
| Disconnected partial AABB face | one pair; no Flood Fill across disconnected tangent seam |
| One exact native pair plus full 25×25 unique cells | 1 exact Region + 48 singleton candidates = **49 Regions**; **25 Layer-0 shares per side**; issued Q accounted without duplicate Credit |
| Work budget of 4 checks | incomplete result, no partially paid reconstructed face |

For the F/Q test `force=50,000`, `Settings.provisional().candidate(50,000) = (50,000-2)×0.1 = 4,999.8`. At E=0 and 25 distinct seeds per side, per-side F is 25,000, seed F 1,000, candidate Q per seed `(1000-2)×0.1 = 99.8`, resulting in 2,495 Q per side and 9.8 unused budget. This is *why* the existing `grant.accounted()==grant.issuedQ()` assertion should be retained. It is not acceptable to change this to an arbitrary expected value or inflate the total Q.

Potential remaining runtime risks are unrelated to this missing import: `Sable` contact substeps are not all observable through its 15-double JNI ABI; the new geometry may legitimately return incomplete or unknown proof; copies of state could change before commit; performance impact of large collision surfaces requires in-game profiling. These cannot be called resolved by an import fix.

## Static verification and CI contracts

- New scripts: `python3 scripts/check-duperfix-contract.py` and existing `python3 scripts/check-superfix-assertions.py`. These checks are **not** substitutes for actual Gradle Java compilation or executing JUnit.
- Inspect all test files for cross-package `DamageModel` references; `DuperFixBilateralSurfaceTest` now uses the same verified public API import already present in `DuperFixPropagationTest`, `SuperFixBilateralSurfaceTest` and other tests. The existing earlier tests remain unchanged.
- Preserve `.github/workflows/apply-pdd-zip.yml`: `incoming/*.zip`, one archive, recognisable root containing `build.gradle`, `settings.gradle`, `src/main/java`; protected `.github`, `gradle`, `gradlew*`, `incoming` remain byte-for-byte unchanged. Build, Callback Contract and Apply workflows also remain unchanged.
- ZIP is the complete project; `src/main`, `src/test`, resources, build scripts, Wrapper and old verification docs included. ZIP CRC, all entries, SHA and file mode checks must pass before sharing.
- **Actual tests:** `compileJava` new release NOT RUN, `compileTestJava` new release NOT RUN, JUnit new release NOT RUN, `processResources`, `jar`, `clean test`, `build`, Actions, in-game all NOT RUN. The preceding failure's `compileJava` result is evidence for the pre-fix revision only.

## User verification

After backing up the world, upload the **one full ZIP** under `incoming/`, Apply using the existing workflow, then run:

```sh
python3 scripts/check-duperfix-contract.py
python3 scripts/check-superfix-assertions.py
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

Check `compileJava`, `compileTestJava`, all JUnit results, `processResources`, `jar`, Manual Build, Callback Contract Test and the in-game version. Expected JAR: `build/libs/physical-destroy-deform-0.6.1-duperFix-buildFix.jar`. Check mods list version **exactly** `0.6.1-duperFix-buildFix`; do not install two PDD versions concurrently.

**In-game A:** one 5×5 or 10×10 aligned World/Plot collision; check `[PDD-CONTACT-PATCH]` bilateral pairs and certified cells, `[PDD-F-LEDGER]` unresolved F, Layer-0 shares (internal and edge cells), F/Q invariant, actual Commit results. Actual faces, not only corner particles, must receive game F when truly certified. Not every contacted block must break if its individual F is below threshold.

**In-game B:** a rotated Plot, a partially empty or Copycat face, shallow penetration versus positive gap, save and reload; check no false contact, stale owner, double drop or receipt replay, preserve existing stress, movement and journal semantics. Submit full logs and first failure if any. Test device-induced TPS lag alone is not treated as algorithmic correctness failure.

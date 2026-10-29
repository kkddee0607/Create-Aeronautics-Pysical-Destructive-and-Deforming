# Create Aeronautics: Physical Destruction and Deforming (PDD) 0.2.12

**State:** source candidate; in-game / NeoForge / Gradle build not yet verified. Based on the existing GitHub 0.2.11 collision telemetry and the integrated 1.0.0 specification §14.2.

This version instruments universal Sable block callback registration and returns unchanged Sable responses while collecting bounded `RawObservation` records. The previous native effects telemetry is preserved for diagnostics; prototype material calculations remain disconnected from gameplay. **This build does not damage any world blocks.**

The documented test procedure is at `docs/verification/0.2.12.md`. Release limitations and reference revision are in `PDD-0.2.12-RELEASE-NOTES.md`.

## Build

From a merged, complete GitHub checkout (which already contains Gradle wrapper): `./gradlew test build --no-daemon`. This ZIP omits wrapper files because the existing GitHub wrapper is protected from the ZIP-sync action; a standalone build requires a compatible Gradle wrapper or installed Gradle.

## Safe update of your existing repository

**Do not put this ZIP in `incoming/`:** the repository's current ZIP-sync workflow *deletes* old docs and root files not in a submitted archive. Open the GitHub repository in Codespaces and upload/extract this ZIP into a temporary directory, then run:

```bash
bash PDD-0.2.12/scripts/merge-into-repo.sh .
git diff --stat
git add -A && git commit -m 'Implement PDD 0.2.12 universal Sable raw callback intake' && git push
```

The example assumes the shell is in the GitHub repository directory and the extracted folder `PDD-0.2.12` is under that directory. The script only updates the source/build files it owns, removes the obsolete active old callback mixin and adds its reports. Historical docs and `.github/` are untouched. Then start GitHub Actions → `PDD Manual Build` and download the `physical-destroy-deform-0.2.12.jar` inside the artifact (the artifact's label may be stale).

## Debug data

- `[PDD-DIAG]`: startup and bridge hook configuration
- `[PDD-RAW]`: raw Sable callback data (only `phase=LIVE_DATA` means callback executed)
- `[PDD-COLLISION]`: old native effects-feed samples, **not** automatically unique physical impacts
- `-Dpdd.debugCollisions=false`: optional *logging* suppression; callback capture remains enabled

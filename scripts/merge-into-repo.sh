#!/usr/bin/env bash
# Apply selected 0.2.12 source updates WITHOUT deleting unrelated GitHub files.
set -euo pipefail
SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="${1:-$PWD}"
if [[ ! -d "$REPO_DIR/.git" || ! -f "$REPO_DIR/.github/workflows/apply-pdd-zip.yml" ]]; then
  echo "ERROR: argument must be the existing PDD GitHub checkout (including .git and .github)" >&2
  exit 1
fi
for file in build.gradle settings.gradle gradle.properties pdd-materials.example.json README.md PDD-0.2.12-fix-RELEASE-NOTES.md; do
  cp "$SOURCE_DIR/$file" "$REPO_DIR/$file"
done
mkdir -p "$REPO_DIR/src/main/java" "$REPO_DIR/src/test/java" "$REPO_DIR/src/main/resources" "$REPO_DIR/docs/verification"
cp -a "$SOURCE_DIR/src/main/java/." "$REPO_DIR/src/main/java/"
cp -a "$SOURCE_DIR/src/test/java/." "$REPO_DIR/src/test/java/"
cp -a "$SOURCE_DIR/src/main/resources/." "$REPO_DIR/src/main/resources/"
cp "$SOURCE_DIR/PDD-0.2.12-RELEASE-NOTES.md" "$REPO_DIR/"
cp "$SOURCE_DIR/docs/verification/0.2.12.md" "$REPO_DIR/docs/verification/0.2.12.md"
cp "$SOURCE_DIR/docs/verification/0.2.12-fix.md" "$REPO_DIR/docs/verification/0.2.12-fix.md"
cp "$SOURCE_DIR/docs/verification/0.2.12-build-audit.md" "$REPO_DIR/docs/verification/0.2.12-build-audit.md"
cp "$SOURCE_DIR/docs/PDD-1.0.1-Integrated-Design-Specification.md" "$REPO_DIR/docs/PDD-1.0.1-Integrated-Design-Specification.md"
rm -f "$REPO_DIR/src/main/java/com/physicaldestroydeform/mixin/SableCollisionBridgeMixin.java"
echo "PDD 0.2.12-fix source merged. Existing docs, workflows, Gradle wrapper and history were preserved."
echo "Review with: git diff --stat && git status --short"

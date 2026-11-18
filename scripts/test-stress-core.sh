#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
# Uses the real Java compiler module, even where the javac launcher is absent.
java com.sun.tools.javac.Main -Xlint:all -d "$classes" \
  src/main/java/com/physicaldestroydeform/BlockDamageState.java \
  src/main/java/com/physicaldestroydeform/DamageModel.java \
  src/main/java/com/physicaldestroydeform/MaterialProperties.java \
  src/main/java/com/physicaldestroydeform/ImpactSample.java \
  src/main/java/com/physicaldestroydeform/collision/GameImpactCredit.java \
  src/main/java/com/physicaldestroydeform/state/SparseStateKey.java \
  src/main/java/com/physicaldestroydeform/world/WorldJournal.java \
  src/main/java/com/physicaldestroydeform/world/WorldReceiptJournal.java \
  src/main/java/com/physicaldestroydeform/world/WorldCommitCore.java \
  src/main/java/com/physicaldestroydeform/stress/StressCommitHandoff.java \
  src/main/java/com/physicaldestroydeform/stress/StressPropagation.java \
  src/main/java/com/physicaldestroydeform/stress/StressReservationJournal.java \
  src/main/java/com/physicaldestroydeform/stress/StressScheduler.java \
  src/main/java/com/physicaldestroydeform/stress/StressWorkCoordinator.java \
  src/test/java/com/physicaldestroydeform/stress/StressCoordinatorSelfTest.java \
  src/test/java/com/physicaldestroydeform/stress/StressDurabilitySelfTest.java \
  src/test/java/com/physicaldestroydeform/stress/StressPropagationSelfTest.java
java -cp "$classes" com.physicaldestroydeform.stress.StressPropagationSelfTest

java -cp "$classes" com.physicaldestroydeform.stress.StressDurabilitySelfTest

java -cp "$classes" com.physicaldestroydeform.stress.StressCoordinatorSelfTest

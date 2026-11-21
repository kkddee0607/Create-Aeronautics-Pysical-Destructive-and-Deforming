# deformFix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Restore safe owner-local Plot deformation on glued thin walls without changing the collision or damage equations.

**Architecture:** Retain verified glue regions in their original allocation coordinates, distinguish them from real physical occupancy, and use Sable's 18-neighbor topology only for the movement proof. All existing transaction, permission, material and post-HeatMap gates remain.

**Tech Stack:** Java 21, Minecraft 1.21.1, NeoForge 21.1.228, Sable 2.0.6, Create 6.0.10, Aeronautics 1.3.2.

**Spec:** IDS 1.0.9, sections covering deformation preflight, owner-local commits and Sable lifecycle (lines 773, 1902–1903, 2498). Full cross-owner glue migration, bilateral elastic attenuation and pendulum collisions remain out of scope.

## Global constraints

- Preserve original damage, stress, reservation, journal and callback semantics; no new libraries.
- Do not run Gradle/JUnit/Actions or write to GitHub. User owns actual test execution.
- Retain all existing tests, source and protected Apply files.

## Review focus

- Edge-adjacent thin wall, true three-axis corner, isolated destination, unknown cell, severed branch.
- Exact adhesive class/type and same-Plot allocation, including negative and boundary coordinates.
- Real entity, foreign glue, unavailable owner, World safety, physical overlap.
- Glue region stays intact: no entity deletion, resizing, spawning or cross-owner transfer by PDD. Native region membership may change when blocks enter or leave existing bounds.
- No relaxation of kinetic/BE/claims/physical route and transaction/HeatMap checks.

## Tasks

- [x] Write regressions in PlotMoveTopologyTest and MoveSafetyTest before production changes; trace expected failures against old six-neighbor and glue-veto logic.
- [x] Change PlotMoveTopology to the 18 offset set from Sable 2.0.6; retain local bounded proof, cache loaded-cell results.
- [x] Add exact glue identity and allocation-bound checks to MoveSafety, wire the Plot-only allowance through both entity queries and existing Create connection checks. Keep World default veto.
- [x] Separate diagnostic gate reasons, update release classifier, startup revision and README; document exact expected regression outcomes and two in-game trials.
- [x] Review all deltas and syntax, preserve existing tests, prepare complete root ZIP for final archive integrity check. Actual tests remain user-owned.

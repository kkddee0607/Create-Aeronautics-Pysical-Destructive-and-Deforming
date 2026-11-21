# Fix4 Implementation Plan

**Goal:** Admit the full bounded F-first projection without splitting the Q graph or repeating source credit. Preserve all prior capabilities.

**Architecture:** One durable reservation per existing projection (including both Plot sides). Bounded cursor processing, one shared root exclusion set, deterministic level merge, same ApplyIds. Compress large snapshots atomically, but keep canonical legacy archive hashes and bound both disk and decoded bytes. Stage original direct receipts only when the root barrier is durable; FIFO per existing World/Plot domain, reverse layer order within a grant.

**Tech stack:** Java 21 / NeoForge 21.1.228 / Sable 2.0.6 / Create 6.0.10 / Aeronautics 1.3.2 / optional Copycats+ 3.0.11.

**Spec:** IDS 1.0.9 §§8,24.5,24.6,36,37.3; Fix3 log episode 35. Baseline a4b411b186e3da038609e4feb572ddc1da883192.

## Constraints and review focus

- No F/Q, damage, E, direction, material, owner, callback, depth 4, derived 128, work 512, minQ 1/256, TTL 1200 or mutation safety change.
- All roots (including zero Q) exclude derived targets; the two-side upper bound is 65536. No independent child Credit or per-page node cap.
- Crash before direct handoff, oversized/corrupt storage, old v4 archive and active record overlap, root barrier changes, full commit queue, owner replacement and reload.
- User runs Gradle/JUnit and Actions; no remote edits.

## Tasks

- [x] Add large reservation and restart regression tests (8192/8193/9216/9744/65536; roundtrip, merge and closed retry).
- [x] Align reservation/session/archive root and snapshot bounds; preserve canonical archive hashes and legacy framing; add bounded large-record framing and decompressed quota.
- [x] Avoid repeated whole-graph root validation and per-operation full ledger scan; retain frozen reads, root barrier, immutable prefix and live validation before publication.
- [x] Stage direct origin receipts using existing durable reservation, shared tick budget and per-domain FIFO; avoid queue-cap terminalization on temporary pressure.
- [x] Replace unbounded diagnostic target lists with counts and bounded samples; identify pressure and version.
- [x] Static source/API/expected-value review; retain existing tests, Gradle, wrappers and Apply contract. Verify whole ZIP and report user-run commands (actual test execution user-owned).

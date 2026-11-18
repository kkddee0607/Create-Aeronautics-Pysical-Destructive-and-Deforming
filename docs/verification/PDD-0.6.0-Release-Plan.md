# PDD 0.6.0 implementation plan

Goal: complete the 1.0.9 release scope without deferring required movement, blocked reserve or Copycats support.
Architecture: seal direct damage once, then execute verified owner-local movement using the two-cell journal; keep Q-stress separately reserved. Both services use one common movement runtime and their existing native gateways.
Spec: .github/workflows/PDD-1.0.9-Integrated-Design-Specification.md §§7.7,24,35–37.

- Reverse F layer/key order and preserve same-credit pending order; verify destination and path at the actual commit boundary.
- Integrate durable MoveTransactionExecutor; hold ordinary writes while a move is in progress; quarantine ambiguous before-images on restart, never repeat a setter or drop.
- Settle only bounded, already E-damped plastic reserve through a distinct durable ApplyId with the existing protected break contract.
- Resolve actual Copycats active materials/shape, preserve approved BE payload and consumed items on movement, revalidate revision.
- Verify Java API signatures against supplied JARs, schema, fixed-point expectations, regressions, structured logs and Apply archive.

Verification: new JUnit assertions precede implementation where practical; actual Gradle/JUnit/Actions/game are user-run by project instruction (no claim of RED/GREEN here). GitHub read-only. No remote commits.

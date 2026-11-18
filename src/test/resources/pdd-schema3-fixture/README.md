# Schema 3 migration fixture

This is a constructed binary-format fixture, not the result of executing the old
Java implementation. It copies the existing schema 2 fixture, updates each header
and CRC, adds schema 3's `hasPending=false` byte to legacy DEFERRED receipts, and
appends one schema 3 DEFERRED payload to its WAL. State encoding remains U16 damage
plus U8 remainder, without the schema 4 deformation byte.

The appended `schema3-pending` has Q=1.25, H=80, T=1024, D=20, E=30,
material generation=7, event tick=123, pre-damped=true, default damage settings,
order=9 and deadline=2345. Migration must preserve all fields and initialize old
deformation history to zero. No Gradle or JUnit was executed while constructing it.

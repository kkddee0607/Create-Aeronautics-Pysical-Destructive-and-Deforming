#!/usr/bin/env python3
"""Read-only originlastFix contact routing guard; not compilation or JUnit."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
main=root/'src/main/java/com/physicaldestroydeform/collision'
java=list((root/'src/main/java').rglob('*.java'))+list((root/'src/test/java').rglob('*.java'))
removed=re.compile(r'\b(?:ContactFacePatch|pendingPatchTargets|needsFallback|pairVerifiedSurfaces|uniformPatchAxis|patchFrames)\b')
errors=[f'{p.relative_to(root)}: obsolete unilateral path' for p in java if removed.search(p.read_text())]
bridge=(main/'RealFixRuntimeBridge.java').read_text()
for term in ('SableContactRegions.prepare(', 'prepared.expand()', 'regionLedger.issueContactSeeds(', 'damageRegions.reservedA()', 'damageRegions.reservedB()', 'OwnerResolver.matchesSnapshot('):
    if term not in bridge:errors.append(f'missing live direct-seed wiring: {term}')
if bridge.find('var damageRegions=')>bridge.find('regionLedger.issueContactSeeds('):errors.append('expansion must precede sealing')
if errors:raise SystemExit('\n'.join(errors))
print('OriginlastFix static contact routing guard PASS: obsolete paths absent, direct/expansion/sealing wiring present')

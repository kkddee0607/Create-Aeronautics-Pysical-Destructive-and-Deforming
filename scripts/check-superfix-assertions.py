#!/usr/bin/env python3
"""Read-only astraFix contact routing guard; not compilation or JUnit."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
main=root/'src/main/java/com/physicaldestroydeform/collision'
java=list((root/'src/main/java').rglob('*.java'))+list((root/'src/test/java').rglob('*.java'))
removed=re.compile(r'\b(?:pendingPatchTargets|needsFallback|pairVerifiedSurfaces|uniformPatchAxis|patchFrames)\b')
errors=[f'{p.relative_to(root)}: obsolete unilateral path' for p in java if removed.search(p.read_text())]
bridge=(main/'RealFixRuntimeBridge.java').read_text()
for term in ('SableContactRegions.prepare(', 'prepared.exact()', 'prepared.expand()', 'regionLedger.issueContactSeeds(', 'damageRegions.reservedA()', 'damageRegions.reservedB()', 'OwnerResolver.matchesSnapshot('):
    if term not in bridge:errors.append(f'missing live direct-seed wiring: {term}')
if bridge.find('var damageRegions=')>bridge.find('regionLedger.issueContactSeeds('):errors.append('expansion must precede sealing')
surface=(main/'BilateralContactSurface.java').read_text()
stress=(main.parent/'stress/StressRuntime.java').read_text()
for term in ('clipHalfPlane(', 'exteriorFaces(', 'Overlap.UNKNOWN'):
    if term not in surface:errors.append(f'missing bilateral geometry: {term}')
for term in ('wakeIndex.wake(', 'StressResidualWait.readyForResidualWait(', 'releasePlanning(job)', '"RESIDUAL"'):
    if term not in stress:errors.append(f'missing stress residual scheduling: {term}')
regions=(main/'SableContactRegions.java').read_text()
ledger=(main/'ContactCreditLedger.java').read_text()
for term in ('ContactFacePatch.reconstruct(', 'mergePatch(', 'refineGroups(', 'Result exact()'):
    if term not in regions:errors.append(f'missing integrated patch routing: {term}')
for term in ('ownerLocalPatch()', 'CONTACT_PATCH_CONSENSUS'):
    if term not in ledger:errors.append(f'missing distinct patch evidence: {term}')
for term in ('StressSnapshotRepair.retire(', 'StressSnapshotRepair.verifyPrefix('):
    if term not in stress:errors.append(f'missing bounded stress repair: {term}')
if 'BILATERAL_CLOSED_ONLY' in bridge or 'speculativeDamageSeeds=0' in bridge:errors.append('stale face policy')
if errors:raise SystemExit('\n'.join(errors))
print('AstraFix static contact routing guard PASS: exact/patch/ledger and stress repair wiring present')

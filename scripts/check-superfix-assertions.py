#!/usr/bin/env python3
"""Read-only duoFix contact routing guard; not compilation or JUnit."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
main=root/'src/main/java/com/physicaldestroydeform/collision'
java=list((root/'src/main/java').rglob('*.java'))+list((root/'src/test/java').rglob('*.java'))
removed=re.compile(r'\b(?:pendingPatchTargets|needsFallback|pairVerifiedSurfaces|uniformPatchAxis|patchFrames)\b')
errors=[f'{p.relative_to(root)}: obsolete unilateral path' for p in java if removed.search(p.read_text())]
bridge=(main/'RealFixRuntimeBridge.java').read_text()
for term in ('DcrtContactBatch.select(', 'credit.stepRegional(frame)', 'selection.forces()', 'selection.expand(pair)', 'selection.seedDirections()', 'regionLedger.issueContactSeeds(', 'damageRegions.reservedA()', 'damageRegions.reservedB()', 'OwnerResolver.matchesSnapshot('):
    if term not in bridge:errors.append(f'missing live direct-seed wiring: {term}')
if bridge.find('var damageRegions=')>bridge.find('regionLedger.issueContactSeeds('):errors.append('expansion must precede sealing')
dcrt=(main/'DcrtContactBatch.java').read_text()
for term in ('SableContactRegions.prepare(', 'prepared.exact()', 'part.prepared.expand()', 'positivePreGap(', 'history.eligible(', 'mapToDouble(Contact::rawImpactScalar).max()'):
    if term not in dcrt:errors.append(f'missing DCRT authority or shared budget: {term}')
for term in ('probeReimpact', 'lastCreditedRegions'):
    if term in bridge:errors.append(f'retired pair-wide authorization: {term}')
force=(main/'ForcePropagation.java').read_text()
for term in ('regionalDirections', 'DCRT_CONFLICT', 'child.incoming+=part'):
    if term not in force:errors.append(f'missing shared regional F merge: {term}')
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
patch=(main/'ContactFacePatch.java').read_text()
logs=(main.parent/'diagnostics/LogSelection.java').read_text()
for term in ('partialFit(', 'inliers.size()<4', 'patchReserve'):
    if term not in patch+regions:errors.append(f'missing bounded partial patch: {term}')
if 'job.validationIndex>=job.validation.size()' not in stress or 'invalidate(job,probe.getKey());return;' in stress:errors.append('stress must gather a full validation batch before replay')
if 'System.getProperty("pdd.logs")' not in logs:errors.append('missing startup log selection')
if errors:raise SystemExit('\n'.join(errors))
print('DuoFix static contact routing guard PASS: exact/patch/ledger and stress repair wiring present')

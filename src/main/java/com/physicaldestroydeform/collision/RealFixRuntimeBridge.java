package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.diagnostics.RawObservation;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.physicaldestroydeform.collision.GameImpactCredit.*;
import static com.physicaldestroydeform.collision.RealFixBoundsEvidence.*;

/**
 * Read-only 1.0.5-A game-impact adapter. Does not reinterpret the native force
 * as impulse and does not require a hypothetical Rapier JNI contact API.
 *
 * Source groups: original clearCollisions array observed by existing Mixin.
 * Source owners: Sable effect-phase activeSubLevels snapshots, cross-audited by pre-physics history.
 * Source targets: independent native Body-local owner R2 shape audits.
 * No cross-substep world-point R3 credits without a native per-contact pose.
 * Source separation: positive gap in actual Sable world-space sublevel AABBs.
 *
 * The exact Sable consumer convention is not a general collider-pose calibration;
 * R4 is never promoted to verified target by guessing. Incomplete evidence yields Q=0.
 */
public final class RealFixRuntimeBridge {
    private static final int MAX_TRACKED=4096;
    private static final int MAX_LOG_PER_TICK=64;
    // Verification-build-only instrumentation; never changes credit, owner or Q logic.
    private static final boolean VERF_ENABLED=Boolean.parseBoolean(
            System.getProperty("pdd.verf.enabled","true"));
    private static final long VERF_SAMPLE_TICKS=Math.max(20L,
            Long.getLong("pdd.verf.sampleTicks",100L));
    private String runId=UUID.randomUUID().toString();
    private final GameImpactCredit credit=new GameImpactCredit(Settings.fromSystemProperties());
    private final ContactCreditLedger regionLedger=new ContactCreditLedger();
    private final Map<Pair,Tracked> tracked=new HashMap<>();
    private final Map<String,Generation> generations=new HashMap<>();
    private long credited,observed,zero,missingOwner,ambiguousOwners,missingTarget;
    private long r2Verified, r2Rejected, r3Verified, prearmed, r1Fallback;
    private long multiGroupCredits, multiRegions, ledgerRejected, multiTooBroad;
    private long lastDropped,lastMalformed,lastCallbackDropped,lastPreHistoryDropped;
    private static final class Tracked {
        Target a,b;
        Box previousA,previousB;
        long lastSeen=-1;
        long lastProcessed=-1;
        boolean lastPositiveSeparation;
        long lastSeparatedTick=-1;
        double lastPeak=Double.NaN;
        String lastVerfRegion="";
        long lastPlotRogWitnessTick=-1;
        long lastPlotRogEpochTick=-1;
        long lastVerfRegionTick=-1;
        String lastVerfFsm="";
        long lastVerfFsmTick=-1;
        String lastVerfR4="";
        long lastVerfR4Tick=-1;
    }
    private static final class Generation {
        int runtimeId;
        long number=1;
        boolean present=true;
        long lastSeen=-1;
        Generation(int runtimeId){this.runtimeId=runtimeId;}
    }
    private record Stamp(String dimension,long tick) { }
    private record SceneKey(String dimension,long tick,String lowOwner,String highOwner) { }
    private static final class ObservedTargets {
        final Map<String,Set<Target>> sides=new HashMap<>();
        void add(String owner,Target target) {if(target!=null)sides.computeIfAbsent(owner,k->new HashSet<>()).add(target);}
        Target unique(String owner) {var s=sides.get(owner);return s!=null&&s.size()==1?s.iterator().next():null;}
        boolean ambiguous(String owner) {var s=sides.get(owner);return s!=null&&s.size()>1;}
    }
    private record RuntimeOwners(Pair pair) { }
    private static final String AMBIGUOUS_OWNER = "\u0000";
    private static final int MAX_INDEX_BODIES_PER_TICK = 16384;
    private static final class OwnerSnapshot {
        final Map<Integer,String> bodies=new HashMap<>();
        boolean complete=true;
        int scanned;
    }

    /** Build owner->runtime evidence once per server tick, not once per native record. */
    private static Map<Stamp,OwnerSnapshot> ownerSnapshots(List<CollisionFrame.Snapshot> pre) {
        Map<Stamp,OwnerSnapshot> result=new HashMap<>();
        for(var snap:pre) {
            if(snap.phase()!=CollisionFrame.Phase.PRE_SOLVER || snap.serverTick()<0
                    || snap.dimension()==null)continue;
            Stamp stamp=new Stamp(snap.dimension(),snap.serverTick());
            OwnerSnapshot index=result.computeIfAbsent(stamp,k->new OwnerSnapshot());
            if(!snap.containerAvailable()) {index.complete=false;continue;}
            if(!index.complete)continue;
            for(var b:snap.bodies()) {
                if(++index.scanned>MAX_INDEX_BODIES_PER_TICK) {
                    index.complete=false;index.bodies.clear();break;
                }
                if(b==null || !b.ownerCandidateValid() || b.runtimeId()<0 || b.plotId()==null)continue;
                String stable="PLOT:"+b.plotId();
                index.bodies.merge(b.runtimeId(),stable,(oldValue,newValue)->
                        oldValue.equals(newValue)?oldValue:AMBIGUOUS_OWNER);
            }
        }
        return result;
    }

    public void clear() {
        credit.clear();regionLedger.clear();tracked.clear();generations.clear();
        runId=UUID.randomUUID().toString();
        lastDropped=lastMalformed=lastCallbackDropped=lastPreHistoryDropped=0;
    }

    /** Called only from ServerTickEvent.Post. Never mutate any world or native buffer. */
    public void tick(MinecraftServer server,List<RawObservation> callbacks,
                     List<NativeCollisionTelemetry.Contact> nativeEvents,
                     List<CollisionFrame.Snapshot> pre,
                     Logger log) {
        Objects.requireNonNull(server);Objects.requireNonNull(callbacks);
        Objects.requireNonNull(nativeEvents);Objects.requireNonNull(pre);Objects.requireNonNull(log);
        final long now=server.getTickCount();
        final boolean complete=NativeCollisionTelemetry.dropped()==lastDropped
                && NativeCollisionTelemetry.malformed()==lastMalformed
                && com.physicaldestroydeform.diagnostics.SableContactQueue.dropped()==lastCallbackDropped
                && SableFrameCapture.preHistoryDropped()==lastPreHistoryDropped;
        lastDropped=NativeCollisionTelemetry.dropped();
        lastMalformed=NativeCollisionTelemetry.malformed();
        lastCallbackDropped=com.physicaldestroydeform.diagnostics.SableContactQueue.dropped();
        lastPreHistoryDropped=SableFrameCapture.preHistoryDropped();
        final Map<Stamp,List<NativeCollisionTelemetry.Contact>> raw=new HashMap<>();
        for(var c:nativeEvents){
            if(c.dimension()!=null&&c.serverTick()>=0)
                raw.computeIfAbsent(new Stamp(c.dimension(),c.serverTick()),k->new ArrayList<>()).add(c);
        }
        final Map<SceneKey,ObservedTargets> targetEvidence=callbackTargets(server,callbacks);
        final Map<Stamp,OwnerSnapshot> ownerIndex=ownerSnapshots(pre);
        final var massEpochs=new NativeContactMassEpoch(pre);
        final Map<String,Map<String,Box>> worldBounds=readServerBounds(server);
        invalidateUnloadedOwners(worldBounds);
        final Map<String,Box> knownWorldBlocks=new HashMap<>();
        // Pre-solver snapshots may be from multiple substeps. A runtime pair is
        // accepted only when every matching snapshot maps to the same stable pair.
        int lines=0;
        int[] remainingR4Checks={RealFixBoundaryCandidates.MAX_PER_TICK};
        final Set<Pair> contacted=new HashSet<>();
        for(var stampEntry:raw.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Stamp,List<NativeCollisionTelemetry.Contact>> x)->x.getKey().dimension)
                        .thenComparingLong(x->x.getKey().tick)).toList()) {
            Stamp stamp=stampEntry.getKey();
            List<NativeCollisionTelemetry.Contact> values=stampEntry.getValue();
            double[] copied=new double[values.size()*15];
            for(int i=0;i<values.size();i++) {
                var c=values.get(i);int o=i*15;
                copied[o]=c.idA();copied[o+1]=c.idB();copied[o+2]=c.rawImpactScalar();
                copied[o+3]=c.ax();copied[o+4]=c.ay();copied[o+5]=c.az();
                copied[o+6]=c.bx();copied[o+7]=c.by();copied[o+8]=c.bz();
                copied[o+9]=c.contactAX();copied[o+10]=c.contactAY();copied[o+11]=c.contactAZ();
                copied[o+12]=c.contactBX();copied[o+13]=c.contactBY();copied[o+14]=c.contactBZ();
            }
            NativeEffectGroups.Batch batch=NativeEffectGroups.aggregate(stamp.dimension,stamp.tick,copied);
            var contactsByPair=new HashMap<NativeEffectGroups.RuntimePair,List<NativeCollisionTelemetry.Contact>>();
            for(var c:values) {
                if(c.idA()< -1 || c.idB()< -1 || c.idA()==c.idB())continue;
                contactsByPair.computeIfAbsent(NativeEffectGroups.RuntimePair.of(c.idA(),c.idB()),
                        ignored->new ArrayList<>()).add(c);
            }
            for(var group:batch.groups()) {
                observed++;
                if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-OBS] source=POST_EFFECT dimension={} tick={} bodyIds={}/{} rawRecords={} peakForce={} discarded={} invalid={} mutation=OFF",
                        stamp.dimension,stamp.tick,group.runtimePair().lowId(),group.runtimePair().highId(),
                        group.originalRecordCount(),group.forcePeak(),batch.discardedRecords(),batch.invalidRecords());
                List<NativeCollisionTelemetry.Contact> records=contactsByPair.getOrDefault(group.runtimePair(),List.of());
                RuntimeOwners owners=resolveOwners(ownerIndex,stamp,group,records);
                if(owners==null) {
                    missingOwner++;zero++;
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-OBS] source=POST_EFFECT dimension={} tick={} ids={}/{} peak={} result=OWNER_UNVERIFIED mutation=OFF",
                            stamp.dimension,stamp.tick,group.runtimePair().lowId(),group.runtimePair().highId(),group.forcePeak());
                    continue;
                }
                Pair pair=owners.pair();
                SceneKey scene=new SceneKey(stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey());
                ObservedTargets candidate=targetEvidence.get(scene);
                // §21.4: inspect each native contact point once, in its own
                // Sable consumer pose; aggregate only source-proven block keys.
                // The whole owner/pair/tick has a strict 32-check/side budget.
                var massSafe=massEpochs.validate(stamp.dimension,stamp.tick,records);
                var regions=SableContactRegions.audit(server,stamp.dimension,pair,massSafe.contacts());
                Target a=regions.verified()?regions.firstA():null;
                Target b=regions.verified()?regions.firstB():null;
                if(regions.status()==SableContactRegions.Status.TOO_BROAD)multiTooBroad++;
                String methodA=a==null?"UNVERIFIED":regions.methodA();
                String methodB=b==null?"UNVERIFIED":regions.methodB();
                if(methodA.contains("R2"))r2Verified++;else r2Rejected++;
                if(methodB.contains("R2"))r2Verified++;else r2Rejected++;
                if(methodA.contains("R3"))r3Verified++;
                if(methodB.contains("R3"))r3Verified++;
                // An existing non-null callback may identify a block owner, but
                // it is NOT asserted to be the same native effect/manifold.
                // Restrict impact credit to Sable's native source-correlated sides.
                Target r1a=candidate==null?null:candidate.unique(pair.a().stableKey());
                Target r1b=candidate==null?null:candidate.unique(pair.b().stableKey());
                if(candidate!=null && (candidate.ambiguous(pair.a().stableKey())||candidate.ambiguous(pair.b().stableKey()))) {
                    ambiguousOwners++;r1a=null;r1b=null;
                }
                if(a==null && r1a!=null)r1Fallback++;
                if(b==null && r1b!=null)r1Fallback++;
                Tracked t=tracked.get(pair);
                if(t==null && tracked.size()<MAX_TRACKED) {
                    t=new Tracked();tracked.put(pair,t);
                }
                if(t==null){zero++;continue;}
                if(a!=null)t.a=a;
                if(b!=null)t.b=b;
                Box boxA=box(server,pair.a().stableKey(),a==null?t.a:a,stamp.dimension,worldBounds,knownWorldBlocks);
                Box boxB=box(server,pair.b().stableKey(),b==null?t.b:b,stamp.dimension,worldBounds,knownWorldBlocks);
                long previousContactTick=t.lastSeen;
                var gapBefore=credit.evidence(pair);
                boolean previousSeparated=t.lastPositiveSeparation
                        && t.lastSeparatedTick>=0 && now-t.lastSeparatedTick<=2;
                Phase phaseBefore=credit.phase(pair);
                // An ARMED episode can have older positive gap evidence: that
                // evidence does not expire merely because it was >2 ticks ago.
                // A previous contact AFTER the gap invalidates this shortcut.
                boolean armedFromGap=phaseBefore==Phase.ARMED
                        && t.lastSeparatedTick>t.lastSeen;
                boolean aabbClosing=RealFixBoundsEvidence.approaching(t.previousA,t.previousB,boxA,boxB);
                boolean approach=aabbClosing || previousSeparated || armedFromGap;
                long separationEvidenceTick=t.lastSeparatedTick;
                double rise=Double.isFinite(t.lastPeak)?group.forcePeak()-t.lastPeak:Double.NaN;
                t.lastPeak=group.forcePeak();
                t.lastPositiveSeparation=false;
                Box priorBoxA=t.previousA, priorBoxB=t.previousB;
                Box sweptA=RealFixBoundsEvidence.swept(t.previousA,boxA);
                Box sweptB=RealFixBoundsEvidence.swept(t.previousB,boxB);
                t.previousA=boxA;t.previousB=boxB;
                t.lastSeen=now;t.lastProcessed=now;
                contacted.add(pair);
                if(a==null&&b==null)missingTarget++;
                // Diagnostic-only, sampled per pair/rejection cause so resting
                // contact cannot flood a small server's log every physics tick.
                var trace=regions.trace();
                String regionSignature=regions.status()+":"+trace.failure();
                if(VERF_ENABLED && regions.status()!=SableContactRegions.Status.PROVED
                        && ( !regionSignature.equals(t.lastVerfRegion)
                        || now-t.lastVerfRegionTick>=VERF_SAMPLE_TICKS)) {
                    log.info("[PDD-VERF-REGION] nativeTick={} serverTick={} pair={}/{} bodyIds={}/{} " +
                            "status={} failure={} failedRecordIndex={} inspectedRecords={} sourceRecords={} " +
                            "checksA={} checksB={} remainingA={} remainingB={} requestedA={} requestedB={} " +
                            "verifiedA={} verifiedB={} regionsBeforeFailure={} rejectedContacts={} " +
                            "detail={} sampleRawContact={} sampleConsumerA={} sampleConsumerB={} frameA={} frameB={} mutation=OFF",
                            stamp.tick,now,pair.a().stableKey(),pair.b().stableKey(),
                            group.runtimePair().lowId(),group.runtimePair().highId(),
                            regions.status(),trace.failure(),trace.sourceIndex(),trace.processedRecords(),records.size(),
                            regions.checksA(),regions.checksB(),trace.remainingA(),trace.remainingB(),
                            trace.requestedA(),trace.requestedB(),trace.verifiedTargetsA(),trace.verifiedTargetsB(),
                            trace.regionCount(),trace.rejectedContacts(),trace.detail(),
                            sampleContact(records),sampleConsumerPoint(records,true),
                            sampleConsumerPoint(records,false),
                            sampleFrame(records,true),sampleFrame(records,false));
                    t.lastVerfRegion=regionSignature;t.lastVerfRegionTick=now;
                }
                if(VERF_ENABLED && massSafe.maskedFrames()>0 &&
                        (t.lastPlotRogEpochTick<0 || now-t.lastPlotRogEpochTick>=VERF_SAMPLE_TICKS)) {
                    log.info("[PDD-PLOTROG-EPOCH] nativeTick={} serverTick={} pair={}/{} " +
                                    "maskedOwnerFrames={} sourceRecords={} policy=UNSTABLE_COM_SIDE_Q_ZERO mutation=OFF",
                            stamp.tick,now,pair.a().stableKey(),pair.b().stableKey(),
                            massSafe.maskedFrames(),records.size());
                    t.lastPlotRogEpochTick=now;
                }
                // Sample witness separation independent of whether R2 approved.
                // Effect-phase projected points may span different substeps;
                // never turn this diagnostic into a hard contact gate.
                if(VERF_ENABLED && (t.lastPlotRogWitnessTick<0 || now-t.lastPlotRogWitnessTick>=VERF_SAMPLE_TICKS)) {
                    for(int i=0;i<records.size();i++) {
                        var contact=records.get(i);
                        var wd=SableConsumerTargets.witnessDiagnostic(contact);
                        if("WITNESS_SEPARATED_GT_0.25".equals(wd.residual().reason())) {
                            log.info("[PDD-PLOTROG-WITNESS] nativeTick={} serverTick={} bodyIds={}/{} " +
                                    "sourceIndex={} sourceRecords={} ownerA={} ownerB={} " +
                                    "worldA={} worldB={} delta={} distance={} alongNormal={} " +
                                    "transverse={} normalAlignment={} diagnosis={} r2Status={} " +
                                    "policy=INDEPENDENT_OWNER_LOCAL_R2_NO_DISTANCE_GATE mutation=OFF",
                                    stamp.tick,now,contact.idA(),contact.idB(),i,records.size(),
                                    pair.a().stableKey(),pair.b().stableKey(),
                                    wd.residual().a(),wd.residual().b(),wd.residual().delta(),
                                    wd.residual().distance(),wd.alongNormal(),wd.transverse(),
                                    wd.normalAlignment(),wd.reason(),regions.status());
                            t.lastPlotRogWitnessTick=now;
                            break;
                        }
                    }
                }
                if(lines++<MAX_LOG_PER_TICK) log.info("[PDD-NATIVE-TARGET] dim={} tick={} ownerA={} ownerB={} methodA={} reasonA={} checkedA={} methodB={} reasonB={} checkedB={} regionCount={} rejectedPoints={} callbackHintA={} callbackHintB={} worldMutation=OFF",
                        stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey(),
                        methodA,regions.status(),regions.checksA(),methodB,regions.status(),regions.checksB(),
                        regions.regions().size(),regions.rejected(),r1a!=null,r1b!=null);
                // R4 shadow audit. This only describes plausible loaded world
                // blocks; WITHOUT in-game ground truth it can never mint credit.
                if(pair.a().stableKey().startsWith("WORLD:") && a==null) {
                    int before=remainingR4Checks[0];
                    R4Probe probe=probeWorldR4(server,stamp.dimension,sweptB,records,remainingR4Checks);
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-TARGET] source=R4_SWEPT_PROVISIONAL dimension={} tick={} worldCandidate={} credited=false",stamp.dimension,stamp.tick,probe.status());
                    if(!probe.status().startsWith("PROVISIONAL_ONLY"))
                        logVerfR4(log,t,now,stamp.tick,pair,probe,records.size(),before,remainingR4Checks[0],sweptB);
                }
                if(pair.b().stableKey().startsWith("WORLD:") && b==null) {
                    int before=remainingR4Checks[0];
                    R4Probe probe=probeWorldR4(server,stamp.dimension,sweptA,records,remainingR4Checks);
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-TARGET] source=R4_SWEPT_PROVISIONAL dimension={} tick={} worldCandidate={} credited=false",stamp.dimension,stamp.tick,probe.status());
                    if(!probe.status().startsWith("PROVISIONAL_ONLY"))
                        logVerfR4(log,t,now,stamp.tick,pair,probe,records.size(),before,remainingR4Checks[0],sweptA);
                }
                if(!complete || batch.invalidRecords()>0 || batch.discardedRecords()>0 || batch.malformedArray()) {
                    zero++;
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-EPISODE] dimension={} tick={} ownerA={} ownerB={} reason=INCOMPLETE_INPUT Q=0 worldMutation=OFF",
                            stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey());
                    continue;
                }
                try {
                    // AABB overlap is UNKNOWN, never 'contact proved'. New Q
                    // requires independently observed prior clearance and a source-
                    // mapped, verified owner-local block. Force is a GAME proxy.
                    var frame=new Frame(pair,now,group.samples(),false,approach,a,b,group.forcePeak());
                    var result=credit.step(frame);
                    var gapAfter=credit.evidence(pair);
                    String fsmSignature=result.reason()+":"+gapBefore.phase()+":"+gapAfter.phase();
                    if(VERF_ENABLED && (result.credited() || gapBefore.phase()!=gapAfter.phase()
                            || !fsmSignature.equals(t.lastVerfFsm) || now-t.lastVerfFsmTick>=VERF_SAMPLE_TICKS)) {
                        log.info("[PDD-VERF-FSM] nativeTick={} serverTick={} bodyIds={}/{} pair={}/{} " +
                                "stateBefore={} stateAfter={} stateTickBefore={} stateTickAfter={} " +
                                "pendingBefore={} pendingAfter={} burstBefore={} burstAfter={} " +
                                "separatedSamplesBefore={} separatedSamplesAfter={} " +
                                "authorizationBefore={} authorizationAfter={} evidenceAge={} " +
                                "episodeBefore={} episodeAfter={} generationBefore={} generationAfter={} " +
                                "separationEvidenceServerTick={} lastContactServerTick={} recentGap={} armedFromGap={} " +
                                "aabbClosing={} verifiedApproach={} prevAabbGap={} currentAabbGap={} " +
                                "targetA={} targetB={} targetStatus={} cause={} issuedQ={} " +
                                "reason={} authConsumed={} source=POST_EFFECT_GAME_POLICY mutation=OFF",
                                stamp.tick,now,group.runtimePair().lowId(),group.runtimePair().highId(),
                                pair.a().stableKey(),pair.b().stableKey(),
                                gapBefore.phase(),gapAfter.phase(),gapBefore.stateTick(),gapAfter.stateTick(),
                                gapBefore.pendingApproach(),gapAfter.pendingApproach(),
                                gapBefore.burstFrames(),gapAfter.burstFrames(),
                                gapBefore.separatedSamples(),gapAfter.separatedSamples(),
                                gapBefore.authorizationTick(),gapAfter.authorizationTick(),
                                gapAfter.authorizationTick()>=0?now-gapAfter.authorizationTick():-1,
                                gapBefore.episode(),gapAfter.episode(),
                                gapBefore.episodeGeneration(),gapAfter.episodeGeneration(),
                                separationEvidenceTick,previousContactTick,previousSeparated,armedFromGap,
                                aabbClosing,approach,verifiedGap(priorBoxA,priorBoxB),verifiedGap(boxA,boxB),
                                a==null?"UNVERIFIED":a.localBlock(),b==null?"UNVERIFIED":b.localBlock(),
                                regions.status(),"PAIR_EPISODE_"+result.episodeGeneration(),result.issuedQ(),
                                result.reason(),gapBefore.pendingApproach()&&!gapAfter.pendingApproach());
                        t.lastVerfFsm=fsmSignature;t.lastVerfFsmTick=now;
                    }
                    ContactCreditLedger.Grant grant=result.credited()?regionLedger.issue(pair,result,
                            ContactCreditLedger.Source.SABLE_POST_EFFECT,regions.regions()):null;
                    boolean granted=grant!=null && grant.credited();
                    if(granted) {
                        credited++;
                        multiGroupCredits++;
                        multiRegions+=grant.observedRegions();
                    } else {
                        zero++;
                        if(grant!=null)ledgerRejected++;
                    }
                    if(grant!=null && lines++<MAX_LOG_PER_TICK)
                        log.info("[PDD-LEDGER] dimension={} tick={} episodeGeneration={} source={} status={} regions={} checksA={} checksB={} rejectedPoints={} Q={} allocatedA={} allocatedB={} unused={} aTargets={} bTargets={} ownerEpochA={} ownerEpochB={} mutation=OFF",
                                stamp.dimension,stamp.tick,grant.id().episodeGeneration(),grant.source(),grant.status(),
                                grant.observedRegions(),regions.checksA(),regions.checksB(),regions.rejected(),
                                grant.issuedQ(),grant.allocatedA(),grant.allocatedB(),grant.unused(),
                                grant.a(),grant.b(),pair.a().generation(),pair.b().generation());
                    if(lines++<MAX_LOG_PER_TICK) log.info("[PDD-TARGET] dimension={} tick={} ownerA={} ownerAGeneration={} ownerB={} ownerBGeneration={} targetA={} fingerprintA={} targetB={} fingerprintB={} frameMethodA={} frameMethodB={} shapeAudit=OWNER_SHAPE_CHECKED quality={} reason={} approachByAabb={} armedFromGap={} recentGap={} worldMutation=OFF",
                            stamp.dimension,stamp.tick,pair.a().stableKey(),pair.a().generation(),pair.b().stableKey(),pair.b().generation(),
                            a==null?"UNVERIFIED":a.localBlock(),a==null?"UNVERIFIED":a.fingerprint(),
                            b==null?"UNVERIFIED":b.localBlock(),b==null?"UNVERIFIED":b.fingerprint(),
                            methodA,methodB,
                            a==null&&b==null?"SOURCE_UNVERIFIED":"POST_EFFECT_CORROBORATED_GAME_PROXY",
                            a==null&&b==null?"TARGET_UNVERIFIED":"SABLE_NATIVE_CONSUMER_MAPPED",
                            aabbClosing,armedFromGap,previousSeparated);
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-EPISODE] dimension={} tick={} bodyIds={}/{} records={} peak={} previousSeparated={} separationEvidenceTick={} phaseBefore={} armedFromGap={} aabbClosing={} forceRise={} state={} reason={} creditCount={} episodeGeneration={} authorizationTick={} Q={} shareA={} shareB={} unused={} regionQuality={} groupCause={} source=POST_EFFECT_GAME_POLICY worldMutation=OFF",
                            stamp.dimension,stamp.tick,group.runtimePair().lowId(),group.runtimePair().highId(),
                            group.originalRecordCount(),group.forcePeak(),previousSeparated,separationEvidenceTick,
                            phaseBefore,armedFromGap,aabbClosing,
                            Double.isFinite(rise)?Double.toString(rise):"UNKNOWN",result.phase(),
                            grant!=null&&!granted?"LEDGER_"+grant.status():result.reason().name(),result.episode(),
                            result.episodeGeneration(),result.authorizationTick(),
                            granted?grant.issuedQ():0.0,granted?grant.allocatedA():0.0,
                            granted?grant.allocatedB():0.0,granted?grant.unused():0.0,
                            regions.status(),"PAIR_EPISODE_"+result.episodeGeneration());
                } catch(RuntimeException unsupported) {
                    zero++;
                    if(lines++<MAX_LOG_PER_TICK)log.warn("[PDD-EPISODE] dimension={} tick={} Q=0 reason=INVALID_BRIDGE_INPUT type={} worldMutation=OFF",
                            stamp.dimension,stamp.tick,unsupported.getClass().getSimpleName());
                }
            }
        }
        // Bounded state update: missing native effects NEVER imply separation.
        // Only actual disjoint 3D world AABBs may rearm an already known pair.
        for(var entry:tracked.entrySet()) {
            Pair p=entry.getKey(); Tracked t=entry.getValue();
            if(contacted.contains(p) || t.lastProcessed==now) continue;
            Box a=box(server,p.a().stableKey(),t.a,p.dimension(),worldBounds,knownWorldBlocks);
            Box b=box(server,p.b().stableKey(),t.b,p.dimension(),worldBounds,knownWorldBlocks);
            boolean definitelySeparate=a!=null&&b!=null&&a.separatedFrom(b);
            t.previousA=a;t.previousB=b;t.lastProcessed=now;
            if(definitelySeparate) {
                t.lastPositiveSeparation=true;
                t.lastSeparatedTick=now;
                try {
                    var before=credit.evidence(p);
                    var d=credit.step(new Frame(p,now,List.of(),true,false,t.a,t.b));
                    if(VERF_ENABLED && before.phase()!=d.phase())
                        log.info("[PDD-VERF-SEPARATION] serverTick={} pair={}/{} source=OBSERVED_AABB_GAP " +
                                "stateBefore={} stateAfter={} separatedBefore={} separatedAfter={} " +
                                "aabbGap={} sourceVerified=true mutation=OFF",
                                now,p.a().stableKey(),p.b().stableKey(),before.phase(),d.phase(),
                                before.separatedSamples(),credit.evidence(p).separatedSamples(),verifiedGap(a,b));
                }
                catch(RuntimeException ignored) { /* invalid snapshot: Q=0 */ }
            }
        }
        // Pre-contact state construction (§22). Silence alone never proves
        // separation: plot-plot uses real Sable world AABBs; plot-world uses a
        // complete, loaded, bounded world-collider clearance inspection.
        prearmIndependentPairs(server,now,worldBounds,contacted,remainingR4Checks,log);
        if(now%100==0) {
            credit.prune(now);
            regionLedger.prune(now);
            generations.entrySet().removeIf(e->!e.getValue().present
                    && e.getValue().lastSeen>=0 && now-e.getValue().lastSeen>600);
            tracked.entrySet().removeIf(e->now>e.getValue().lastSeen && now-e.getValue().lastSeen>600);
            log.info("[PDD-0.3.1plotRog] phase=SUMMARY observed={} credited={} zero={} ownerMissing={} targetMissing={} ambiguous={} trackedPairs={} r2Verified={} r2Rejected={} r3Verified={} prearmed={} r1Fallback={} regionGroups={} regionCount={} regionTooBroad={} ledgerRejected={} activeCredits={} observerErrors={} nativeDropped={} nativeMalformed={} nativeCapSaturatedPolls={} callbackDropped={} preHistoryDropped={} generation=IDS-1.0.5-A mutation=OFF",
                    observed,credited,zero,missingOwner,missingTarget,ambiguousOwners,tracked.size(),
                    r2Verified,r2Rejected,r3Verified,prearmed,r1Fallback,
                    multiGroupCredits,multiRegions,multiTooBroad,ledgerRejected,regionLedger.size(),
                    NativeCollisionTelemetry.observerErrors(),
                    NativeCollisionTelemetry.dropped(),NativeCollisionTelemetry.malformed(),
                    NativeCollisionTelemetry.nativeCapSaturatedPolls(),
                    com.physicaldestroydeform.diagnostics.SableContactQueue.dropped(),SableFrameCapture.preHistoryDropped());
        }
    }

    private Map<SceneKey,ObservedTargets> callbackTargets(MinecraftServer server,List<RawObservation> callbacks) {
        Map<SceneKey,ObservedTargets> mapped=new HashMap<>();
        var cache=new HashMap<OwnerResolver.CacheKey,OwnerResolver.Check>();
        for(var c:callbacks) {
            if(!c.hasOther() || c.frameEvidence()==null)continue;
            var e=c.frameEvidence();
            if(e.hit()==null||e.other()==null||e.epoch()<0)continue;
            var result=OwnerResolver.audit(server,e,c.blockId(),cache);
            String oa=stable(e.hit()),ob=stable(e.other());
            if(oa==null||ob==null||oa.equals(ob))continue;
            SceneKey key=oa.compareTo(ob)<0?new SceneKey(e.dimension(),e.serverTick(),oa,ob)
                    :new SceneKey(e.dimension(),e.serverTick(),ob,oa);
            ObservedTargets set=mapped.computeIfAbsent(key,ignored->new ObservedTargets());
            if(result.hit().verified())set.add(oa,target(oa,result.hit().key()));
            if(result.other().verified())set.add(ob,target(ob,result.other().key()));
        }
        return mapped;
    }
    private static Target target(String owner,OwnerResolver.TargetKey k) {
        return new Target(owner,k.x()+","+k.y()+","+k.z(),k.fingerprint());
    }
    private static String stable(CollisionFrame.BlockTarget t) {
        if(t==null||!t.valid())return null;
        if(t.space()==CollisionFrame.Space.STATIC_WORLD)return "WORLD:"+t.dimension();
        if(t.space()==CollisionFrame.Space.SABLE_PLOT&&t.stablePlotId()!=null)return "PLOT:"+t.stablePlotId();
        return null;
    }
    /** Ownership comes from exact-phase Sable activeSubLevels, not approximate
     * AABB proximity. Pre-history is a cross-check when complete, not a substitute. */
    private RuntimeOwners resolveOwners(Map<Stamp,OwnerSnapshot> ownerIndex,Stamp stamp,
                                         NativeEffectGroups.Group group,
                                         List<NativeCollisionTelemetry.Contact> contacts) {
        if(contacts.isEmpty())return null;
        String low=null,high=null;
        int idLo=group.runtimePair().lowId(),idHi=group.runtimePair().highId();
        for(var contact:contacts) {
            String la=sourceOwner(stamp.dimension(),contact.idA(),contact.frameA());
            String lb=sourceOwner(stamp.dimension(),contact.idB(),contact.frameB());
            if(la==null || lb==null || la.equals(lb))return null;
            String currentLow=contact.idA()==idLo?la:lb;
            String currentHigh=contact.idA()==idHi?la:lb;
            if(low!=null && !low.equals(currentLow))return null;
            if(high!=null && !high.equals(currentHigh))return null;
            low=currentLow;high=currentHigh;
        }
        OwnerSnapshot pre=ownerIndex.get(stamp);
        if(pre!=null && pre.complete) {
            String preLow=owner(pre,stamp.dimension(),idLo);
            String preHigh=owner(pre,stamp.dimension(),idHi);
            if(preLow!=null && !preLow.equals(low) || preHigh!=null && !preHigh.equals(high))return null;
        }
        Owner a=new Owner(low,generation(stamp.dimension(),low,idLo,stamp.tick()));
        Owner b=new Owner(high,generation(stamp.dimension(),high,idHi,stamp.tick()));
        return new RuntimeOwners(Pair.canonical(runId,stamp.dimension(),a,b));
    }
    private static String sourceOwner(String dimension,int id,
                                      NativeCollisionTelemetry.OwnerFrame frame) {
        if(id==-1)return "WORLD:"+dimension;
        return frame==null?null:"PLOT:"+frame.uuid();
    }
    private static String owner(OwnerSnapshot index,String dimension,int runtimeId) {
        if(runtimeId==-1)return "WORLD:"+dimension;
        String stable=index.bodies.get(runtimeId);
        return AMBIGUOUS_OWNER.equals(stable)?null:stable;
    }
    private long generation(String dimension,String owner,int runtimeId,long observedTick) {
        if(owner.startsWith("WORLD:"))return 1;
        String key=dimension+"|"+owner;
        Generation g=generations.computeIfAbsent(key,k->new Generation(runtimeId));
        if(g.runtimeId!=runtimeId || !g.present){
            g.runtimeId=runtimeId;
            // Missing owners were incremented when they disappeared; don't
            // double-increment on reappearance with the same runtime id.
            if(g.present)g.number++;
            invalidateOwner(owner);
        }
        g.present=true;
        g.lastSeen=observedTick;
        return g.number;
    }
    /** Unload/split invalidates every old target/episode, even if the native
     * runtime integer happens to be reused when the plot returns. */
    private void invalidateUnloadedOwners(Map<String,Map<String,Box>> bounds) {
        for(var entry:generations.entrySet()) {
            String key=entry.getKey();int separator=key.indexOf('|');
            if(separator<0)continue;
            String dim=key.substring(0,separator),owner=key.substring(separator+1);
            Generation g=entry.getValue();
            if(bounds.getOrDefault(dim,Map.of()).containsKey(owner)) {
                g.present=true;
                continue;
            }
            if(g.present){
                g.present=false;
                g.number++;
                invalidateOwner(owner);
            }
        }
    }
    private void invalidateOwner(String owner) {
        // Old owner-generation credits must never authorize a recycled body ID.
        tracked.keySet().removeIf(pair->pair.a().stableKey().equals(owner)
                || pair.b().stableKey().equals(owner));
        credit.invalidateOwner(owner);
        regionLedger.invalidateOwner(owner);
    }
    private static Map<String,Map<String,Box>> readServerBounds(MinecraftServer server) {
        Map<String,Map<String,Box>> result=new HashMap<>();
        for(ServerLevel level:server.getAllLevels()) {
            var container=SubLevelContainer.getContainer(level);
            if(container==null)continue;
            Map<String,Box> boxes=new HashMap<>();
            for(ServerSubLevel sub:container.getAllSubLevels()){
                if(sub==null||sub.isRemoved()||sub.getUniqueId()==null)continue;
                try {
                    var b=sub.boundingBox();
                    if(b!=null) boxes.put("PLOT:"+sub.getUniqueId(),new Box(
                            b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()));
                } catch(RuntimeException invalid) { /* fail closed */ }
            }
            result.put(level.dimension().location().toString(),boxes);
        }
        return result;
    }
    /** Bounded verified precontact clearance. Will not load a chunk or scan a big ship. */
    private void prearmIndependentPairs(MinecraftServer server,long now,
                                       Map<String,Map<String,Box>> bounds,Set<Pair> contacted,
                                       int[] remaining,Logger log) {
        int attempted=0;
        for(var dimEntry:bounds.entrySet()) {
            String dim=dimEntry.getKey();
            var entries=new ArrayList<>(dimEntry.getValue().entrySet());
            entries.sort(Map.Entry.comparingByKey());
            for(int i=0;i<entries.size() && attempted<128;i++) {
                var first=entries.get(i);Box firstBox=first.getValue();
                if(firstBox==null)continue;
                for(int j=i+1;j<entries.size() && attempted<128;j++) {
                    var second=entries.get(j);Box secondBox=second.getValue();
                    if(secondBox==null)continue;
                    attempted++;
                    if(firstBox.separatedFrom(secondBox)) {
                        Pair pair=Pair.canonical(runId,dim,
                                new Owner(first.getKey(),currentGeneration(dim,first.getKey())),
                                new Owner(second.getKey(),currentGeneration(dim,second.getKey())));
                        seedSeparation(pair,now,contacted,first.getKey(),firstBox,secondBox,log);
                    }
                }
                if(attempted>=128)break;
                attempted++;
                if(confirmedWorldClearance(server,dim,firstBox,remaining)) {
                    Pair pair=Pair.canonical(runId,dim,new Owner("WORLD:"+dim,1),
                            new Owner(first.getKey(),currentGeneration(dim,first.getKey())));
                    seedSeparation(pair,now,contacted,"WORLD:"+dim,null,firstBox,log);
                }
            }
        }
    }
    private long currentGeneration(String dimension,String owner) {
        Generation g=generations.get(dimension+"|"+owner);
        return g==null?1:g.number;
    }
    /** Pair.canonical orders by stable owner, NOT by native ID or iteration order. */
    static Box[] alignBounds(Pair pair,String firstOwner,Box first,Box second) {
        if(pair==null || firstOwner==null)return null;
        if(pair.a().stableKey().equals(firstOwner))return new Box[]{first,second};
        if(pair.b().stableKey().equals(firstOwner))return new Box[]{second,first};
        return null;
    }
    private void seedSeparation(Pair pair,long now,Set<Pair> contacted,
                                String firstOwner,Box first,Box second,Logger log) {
        if(contacted.contains(pair) || tracked.size()>=MAX_TRACKED && !tracked.containsKey(pair))return;
        Box[] ordered=alignBounds(pair,firstOwner,first,second);
        if(ordered==null)return;
        Tracked t=tracked.computeIfAbsent(pair,k->new Tracked());
        try {
            var prior=credit.evidence(pair);
            var d=credit.step(new Frame(pair,now,List.of(),true,false,null,null));
            if(d.reason()==GameImpactCredit.Reason.STALE_FRAME)return;
            if(VERF_ENABLED && prior.phase()!=d.phase())
                log.info("[PDD-VERF-SEPARATION] serverTick={} pair={}/{} source=PRECONTACT_CLEARANCE " +
                        "stateBefore={} stateAfter={} separatedBefore={} separatedAfter={} " +
                        "aabbGap={} sourceVerified=true mutation=OFF",
                        now,pair.a().stableKey(),pair.b().stableKey(),prior.phase(),d.phase(),
                        prior.separatedSamples(),credit.evidence(pair).separatedSamples(),
                        verifiedGap(ordered[0],ordered[1]));
            t.lastPositiveSeparation=true;
            t.lastSeparatedTick=now;
            t.lastProcessed=now;
            t.previousA=ordered[0];t.previousB=ordered[1];
            prearmed++;
        } catch(RuntimeException invalid){ /* fail closed */ }
    }
    private static boolean confirmedWorldClearance(MinecraftServer server,String dim,Box box,
                                                   int[] remaining) {
        // This is a finite empty-region proof only; returning false proves nothing.
        var candidates=RealFixBoundaryCandidates.nearOverlap(new Box(
                box.minX()-0.25,box.minY()-0.25,box.minZ()-0.25,
                box.maxX()+0.25,box.maxY()+0.25,box.maxZ()+0.25),remaining[0]);
        if(candidates.reason()!=RealFixBoundaryCandidates.Reason.CANDIDATES)return false;
        for(ServerLevel level:server.getAllLevels()) {
            if(!level.dimension().location().toString().equals(dim))continue;
            var container=SubLevelContainer.getContainer(level);
            if(container==null)return false;
            for(var block:candidates.blocks()) {
                if(--remaining[0]<0)return false;
                BlockPos pos=new BlockPos(block.x(),block.y(),block.z());
                if(!level.hasChunkAt(pos))return false;
                try {
                    var chunk=new ChunkPos(pos);
                    if(container.inBounds(chunk)||container.getPlot(chunk)!=null)return false;
                    var state=level.getBlockState(pos);
                    if(!state.isAir() && !state.getCollisionShape(level,pos).isEmpty())return false;
                } catch(RuntimeException invalid) {return false;}
            }
            return true;
        }
        return false;
    }
    private record R4Probe(String status,int points,int candidateCells,int failedPoint,String detail) { }
    private static String sampleContact(List<NativeCollisionTelemetry.Contact> records) {
        if(records.isEmpty())return "NONE";
        var c=records.get(0);
        return "("+c.contactAX()+","+c.contactAY()+","+c.contactAZ()+")/("+
                c.contactBX()+","+c.contactBY()+","+c.contactBZ()+")";
    }
    private static String sampleConsumerPoint(List<NativeCollisionTelemetry.Contact> records,boolean first) {
        if(records.isEmpty())return "NONE";
        var local=SableConsumerTargets.candidate(records.get(0),first);
        return local==null?"UNKNOWN":local.toString();
    }
    private static String sampleFrame(List<NativeCollisionTelemetry.Contact> records,boolean first) {
        if(records.isEmpty())return "NONE";
        var f=first?records.get(0).frameA():records.get(0).frameB();
        return f==null?"NO_FRAME":f.uuid().toString();
    }
    private static String verifiedGap(Box a,Box b) {
        return a==null||b==null?"UNKNOWN":Double.toString(a.gap(b));
    }
    private static void logVerfR4(Logger log,Tracked t,long now,long nativeTick,
                                  Pair pair,R4Probe probe,int records,int before,int after,Box swept) {
        if(!VERF_ENABLED)return;
        String signature="R4:"+probe.status()+":"+probe.detail();
        if(signature.equals(t.lastVerfR4) && now-t.lastVerfR4Tick<VERF_SAMPLE_TICKS)return;
        log.info("[PDD-VERF-R4] nativeTick={} serverTick={} pair={}/{} reason={} detail={} " +
                "sourceRecords={} sourcePoints={} candidateCells={} failedPointIndex={} " +
                "tickBudgetBefore={} tickBudgetAfter={} sweptBounds={} credited=false mutation=OFF",
                nativeTick,now,pair.a().stableKey(),pair.b().stableKey(),probe.status(),probe.detail(),
                records,probe.points(),probe.candidateCells(),probe.failedPoint(),before,after,swept);
        t.lastVerfR4=signature;t.lastVerfR4Tick=now;
    }
    private static R4Probe probeWorldR4(MinecraftServer server,String dimension,Box moving,
                                       List<NativeCollisionTelemetry.Contact> records,
                                       int[] remainingChecks) {
        // World-side native point is in the coordinate convention Sable itself
        // uses for static BlockPos. Do not sample arbitrary ship AABB faces.
        if(records.isEmpty())return new R4Probe("NATIVE_WORLD_POINT_MISSING",0,0,-1,"NONE");
        // Do not fail merely because different native points occupy different
        // contact regions. This is a bounded R4 diagnostic, NEVER Q authority.
        var points=new ArrayList<FrameMath.Vec>(records.size());
        for(var c:records) {
            if(c.idA()==-1)
                points.add(new FrameMath.Vec(c.contactAX(),c.contactAY(),c.contactAZ()));
            else if(c.idB()==-1)
                points.add(new FrameMath.Vec(c.contactBX(),c.contactBY(),c.contactBZ()));
            else return new R4Probe("NOT_WORLD_COLLIDER",points.size(),0,-1,"NATIVE_SIDE_MISMATCH");
        }
        var candidates=RealFixBoundaryCandidates.nearContactPoints(moving,points,remainingChecks[0]);
        if(candidates.reason()!=RealFixBoundaryCandidates.Reason.CANDIDATES)
            return new R4Probe(candidates.reason().name(),points.size(),
                    candidates.uniqueCells(),candidates.failedPointIndex(),candidates.detail());
        int found=0;String sole="NONE";
        for(ServerLevel level:server.getAllLevels()) {
            if(!level.dimension().location().toString().equals(dimension))continue;
            var c=SubLevelContainer.getContainer(level);
            if(c==null)return new R4Probe("CONTAINER_MISSING",points.size(),candidates.uniqueCells(),-1,"NONE");
            for(var block:candidates.blocks()) {
                if(--remainingChecks[0]<0)return new R4Probe("TICK_CANDIDATE_LIMIT",points.size(),candidates.uniqueCells(),-1,"R4_TICK_BUDGET_EXHAUSTED");
                BlockPos pos=new BlockPos(block.x(),block.y(),block.z());
                try {
                    if(!level.hasChunkAt(pos))continue;
                    var chunk=new ChunkPos(pos);
                    if(c.inBounds(chunk)||c.getPlot(chunk)!=null)continue;
                    var state=level.getBlockState(pos);
                    if(state.isAir()||state.getCollisionShape(level,pos).isEmpty())continue;
                    found++;
                    if(found>1)return new R4Probe("TARGET_MULTI_UNRESOLVED",points.size(),candidates.uniqueCells(),-1,"MULTIPLE_SOLID_BLOCKS");
                    sole=block.x()+","+block.y()+","+block.z()+":"+
                            BuiltInRegistries.BLOCK.getKey(state.getBlock());
                }catch(RuntimeException skipped){return new R4Probe("R4_READ_ERROR",points.size(),candidates.uniqueCells(),-1,"BLOCK_READ_EXCEPTION");}
            }
            return new R4Probe(found==1?"PROVISIONAL_ONLY:"+sole:"TARGET_UNRESOLVED",
                    points.size(),candidates.uniqueCells(),-1,"NONE");
        }
        return new R4Probe("DIMENSION_MISSING",points.size(),candidates.uniqueCells(),-1,"NONE");
    }

    private static Box box(MinecraftServer server,String owner,Target target,String dimension,
                           Map<String,Map<String,Box>> plotBounds,Map<String,Box> worldBlocks) {
        if(owner.startsWith("PLOT:"))return plotBounds.getOrDefault(dimension,Map.of()).get(owner);
        if(target==null)return null;
        String cacheKey=owner+"|"+target.localBlock()+"|"+target.fingerprint();
        if(worldBlocks.containsKey(cacheKey))return worldBlocks.get(cacheKey);
        try {
            String[] coords=target.localBlock().split(",",-1);
            if(coords.length!=3)return null;
            BlockPos pos=new BlockPos(Integer.parseInt(coords[0]),Integer.parseInt(coords[1]),Integer.parseInt(coords[2]));
            for(ServerLevel level:server.getAllLevels()) {
                if(!level.dimension().location().toString().equals(dimension))continue;
                if(!level.hasChunkAt(pos))return null; // never force load
                var sublevels=SubLevelContainer.getContainer(level);
                if(sublevels==null || sublevels.inBounds(new ChunkPos(pos))
                        || sublevels.getPlot(new ChunkPos(pos))!=null)return null;
                var state=level.getBlockState(pos);
                String fingerprint=BuiltInRegistries.BLOCK.getKey(state.getBlock())+"|"+state;
                if(!fingerprint.equals(target.fingerprint()))return null;
                Box b=Box.block(pos.getX(),pos.getY(),pos.getZ());
                worldBlocks.put(cacheKey,b);
                return b;
            }
        }catch(RuntimeException invalid){return null;}
        return null;
    }
}

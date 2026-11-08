package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.material.MaterialPlanner;
import com.physicaldestroydeform.material.MaterialRegistry;
import com.physicaldestroydeform.material.PreviewDamageLedger;
import com.physicaldestroydeform.world.WorldCommitService;
import com.physicaldestroydeform.state.PreviewCheckpoint;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.diagnostics.RawObservation;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    private static final int MAX_LOG_PER_TICK=Math.max(64,Math.min(4096,
            Integer.getInteger("pdd.verf.maxLogPerTick",1024)));
    // Diagnostic-only trace, capped independently from collision evidence logs.
    private static final boolean F_TRACE_ENABLED=Boolean.parseBoolean(
            System.getProperty("pdd.f.trace.enabled","false"));
    // Verification release: a radius-3.5 Plot previously lost 193/289 F
    // receipts. Keep explicit operator caps for high-traffic production use.
    private static final int MAX_F_TRACE_PER_TICK=Math.max(256,Math.min(16384,
            Integer.getInteger("pdd.f.trace.maxPerTick",8192)));
    private static final int MAX_F_TRACE_PER_CREDIT=Math.max(256,Math.min(16384,
            Integer.getInteger("pdd.f.trace.maxPerCredit",4096)));
    private static final int MAX_NATIVE_NORMAL_SAMPLES=Math.max(0,Math.min(100,
            Integer.getInteger("pdd.normal.samples.maxPerCredit",8)));
    private static final int MAX_MATERIAL_LOGS_PER_TICK=Math.max(16,Math.min(4096,
            Integer.getInteger("pdd.material.trace.maxPerTick",16)));
    // Verification-build-only instrumentation; never changes credit, owner or Q logic.
    private static final boolean VERF_ENABLED=Boolean.parseBoolean(
            System.getProperty("pdd.verf.enabled","true"));
    private static final long VERF_SAMPLE_TICKS=Math.max(20L,
            Long.getLong("pdd.verf.sampleTicks",100L));
    private String runId=UUID.randomUUID().toString();
    private final GameImpactCredit credit=new GameImpactCredit(Settings.fromSystemProperties());
    private final DamageModel.Settings damageSettings=DamageModel.Settings.fromSystemProperties();
    private final ContactCreditLedger regionLedger=new ContactCreditLedger();
    private final WorldClearanceVerifier worldClearance=new WorldClearanceVerifier();
    private final ForceCalibration forceCalibration=new ForceCalibration();
    private final PreviewDamageLedger preview=new PreviewDamageLedger();
    private WorldCommitService worldCommit;
    /** Side-effect commit is intentionally outside physics/native callback. */
    public void setWorldCommitService(WorldCommitService next){this.worldCommit=next;}
    private long directionApproved,directionUnknown,previewFailures;
    private boolean previewCheckpointWritable=true;
    private String previewPolicyId;
    private final Map<Pair,Tracked> tracked=new HashMap<>();
    private final Map<String,Generation> generations=new HashMap<>();
    private long credited,observed,zero,missingOwner,ambiguousOwners,missingTarget;
    private long r2Verified, r2Rejected, r3Verified, prearmed, r1Fallback;
    private long multiGroupCredits, multiRegions, ledgerRejected, multiTooBroad;
    private long materialPlanned, materialMissing, materialRejected;
    private long r2Audits,r2SkippedDuplicate,r2ShapeChecks;
    private long clearanceCalls,clearanceCellReads,clearanceElapsedNanos,clearanceProved;
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
        String lastClearanceStatus="";
        long lastClearanceStatusTick=-1;
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
        credit.clear();regionLedger.clear();worldClearance.clear();tracked.clear();generations.clear();
        runId=UUID.randomUUID().toString();
        lastDropped=lastMalformed=lastCallbackDropped=lastPreHistoryDropped=0;
        r2Audits=r2SkippedDuplicate=r2ShapeChecks=0;
        clearanceCalls=clearanceCellReads=clearanceElapsedNanos=clearanceProved=0;
        forceCalibration.clear();
        materialPlanned=materialMissing=materialRejected=0;
        directionApproved=directionUnknown=previewFailures=0;
        preview.clear();
        worldCommit=null;
        previewCheckpointWritable=true;
        previewPolicyId=null;
    }

    /** Diagnostic snapshot ONLY. No connection to real World/Plot damage persistence. */
    public void loadPreview(MinecraftServer server,Logger log) {
        var q=credit.settings();
        log.info("[PDD-POLICY] revision={} forceEnter={} forceFloor={} forceGain={} maxGroupQ={} maxGameF={} " +
                        "damageGain={} directShare={} plasticShare={} stressShare={} fixedPointScale={} " +
                        "F_FIRST=TRUE halfTransfer=0.5 maxNodesPerSide={} minRemainingF={} " +
                        "source=POST_EFFECT_GAME_PROXY measured=false mutation=OFF",
                GameImpactCredit.POLICY_REVISION,q.forceEnter(),q.forceFloor(),q.forceGain(),
                q.maxGroupQ(),GameImpactCredit.MAX_GAME_F,damageSettings.damageGain(),DamageModel.DIRECT_SHARE,
                DamageModel.PLASTIC_SHARE,DamageModel.STRESS_SHARE,
                com.physicaldestroydeform.BlockDamageState.FIXED_SCALE,
                ForcePropagation.MAX_NODES_PER_SIDE,ForcePropagation.MIN_PROPAGATED_F);
        // A changed Q, damage, material override or datapack cache must not replay
        // the previous calibration's diagnostic damage as this policy's result.
        previewPolicyId=calibrationIdentity();
        if(!PreviewCheckpoint.enabled()) {
            log.info("[PDD-PREVIEW-SAVE] phase=START status=DISABLED persisted=false mutation=OFF");
            return;
        }
        try {
            var path=previewPath(server);
            var bytes=PreviewCheckpoint.load(path);
            if(bytes==null) {
                log.info("[PDD-PREVIEW-SAVE] phase=START status=EMPTY file={} restored=0 mutation=OFF",path);
                return;
            }
            preview.restore(bytes);
            log.info("[PDD-PREVIEW-SAVE] phase=START status=RESTORED file={} entries={} fixedTotal={} " +
                    "bytes={} authoritative=false replay=false mutation=OFF",
                    path,preview.size(),preview.fixedTotal(),bytes.length);
        }catch(Exception invalid) {
            // An invalid checkpoint is never partially used or treated as legitimate game damage.
            preview.clear();
            previewCheckpointWritable=false;
            log.warn("[PDD-PREVIEW-SAVE] phase=START status=REJECTED reason={} " +
                    "authoritative=false mutation=OFF",invalid.getClass().getSimpleName(),invalid);
        }
    }
    public void savePreview(MinecraftServer server,Logger log) {
        if(!PreviewCheckpoint.enabled())return;
        if(previewPolicyId==null || !previewPolicyId.equals(calibrationIdentity())) {
            log.warn("[PDD-PREVIEW-SAVE] phase=STOP status=SKIPPED_POLICY_CHANGED " +
                    "originalCheckpointPreserved=true mutation=OFF");
            return;
        }
        if(!previewCheckpointWritable) {
            log.warn("[PDD-PREVIEW-SAVE] phase=STOP status=SKIPPED_BAD_INPUT " +
                    "originalCheckpointPreserved=true mutation=OFF");
            return;
        }
        try {
            byte[] bytes=preview.verifyRoundTrip();
            var path=previewPath(server);
            PreviewCheckpoint.save(path,bytes);
            log.info("[PDD-PREVIEW-SAVE] phase=STOP status=WRITTEN file={} entries={} fixedTotal={} " +
                    "bytes={} authoritative=false mutation=OFF",
                    path,preview.size(),preview.fixedTotal(),bytes.length);
        }catch(Exception error) {
            log.warn("[PDD-PREVIEW-SAVE] phase=STOP status=FAILED reason={} mutation=OFF",
                    error.getClass().getSimpleName(),error);
        }
    }

    private String calibrationIdentity() {
        // Name-based UUID is a stable, bounded checkpoint namespace, not a security checksum.
        var material=MaterialRegistry.snapshot();
        String policy=GameImpactCredit.POLICY_REVISION+"|"+credit.settings()+"|"+
                DamageModel.ALGORITHM_REVISION+"|"+damageSettings+"|"+
                ContactCreditLedger.ALGORITHM_REVISION+"|F-FIRST-0.4.0finalFix|"+
                material.algorithmRevision()+"|"+material.definitions().hashCode()+"|"+
                material.stateSpecific().hashCode();
        return UUID.nameUUIDFromBytes(policy.getBytes(StandardCharsets.UTF_8)).toString();
    }
    private Path previewPath(MinecraftServer server) {
        return PreviewCheckpoint.file(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
                previewPolicyId);
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
        int materialLines=0;
        final int[] fTraceLines={0};
        // Clearance is safety-critical. R4 is diagnostic-only: separate
        // bounded budgets prevent R4 from starving positive separation proof.
        int[] remainingClearanceChecks={RealFixBoundaryCandidates.MAX_PER_TICK};
        int[] remainingAtomicConfirmChecks={WorldClearanceVerifier.MAX_CONFIRM_PER_TICK};
        int[] remainingR4Checks={Math.min(128,RealFixBoundaryCandidates.MAX_PER_TICK)};
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
                // Observed native contact invalidates any incomplete clearance scan.
                worldClearance.contact(pair);
                SceneKey scene=new SceneKey(stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey());
                ObservedTargets candidate=targetEvidence.get(scene);
                // §21.4: inspect each native contact point once, in its own
                // Sable consumer pose; aggregate only source-proven block keys.
                // The owner/pair/tick has a separate bounded R2 shape-check budget (256 unique cells/side); target Q allocations stay capped at 32 targets/side.
                var beforeAudit=credit.evidence(pair);
                // An exhausted ACTIVE episode cannot mint Q. Still observe
                // native records and advance FSM, but skip expensive shape I/O.
                boolean auditEligible=beforeAudit.phase()!=Phase.ACTIVE
                        || beforeAudit.hasUnspentAuthorization();
                long r2Start=auditEligible?System.nanoTime():0;
                var massSafe=auditEligible?massEpochs.validate(stamp.dimension,stamp.tick,records):null;
                var regions=auditEligible?SableContactRegions.audit(server,stamp.dimension,pair,massSafe.contacts())
                        :SableContactRegions.skippedDuplicate();
                long r2Nanos=auditEligible?System.nanoTime()-r2Start:0;
                if(auditEligible){
                    r2Audits++;r2ShapeChecks+=regions.checksA()+regions.checksB();
                    log.info("[PDD-R2-PERF] pair={}/{} phase=AUTHORIZED_AUDIT nanos={} records={} " +
                                    "checkedA={} checkedB={} regions={} status={} mutation=OFF",
                            pair.a().stableKey(),pair.b().stableKey(),r2Nanos,records.size(),
                            regions.checksA(),regions.checksB(),regions.regions().size(),regions.status());
                    if(regions.trace().detail().contains("facePatchAddedA="))
                        log.info("[PDD-CONTACT-PATCH] pair={}/{} nativeTick={} " +
                                        "originalRecords={} verifiedRegions={} audit={} mutation=OFF",
                                pair.a().stableKey(),pair.b().stableKey(),stamp.tick,
                                records.size(),regions.regions().size(),regions.trace().detail());
                }
                else r2SkippedDuplicate++;
                boolean saturatedPoll=records.stream().anyMatch(NativeCollisionTelemetry.Contact::capSaturated);
                Target a=regions.verified()?regions.firstA():null;
                Target b=regions.verified()?regions.firstB():null;
                if(regions.status()==SableContactRegions.Status.TOO_BROAD)multiTooBroad++;
                String methodA=a==null?"UNVERIFIED":regions.methodA();
                String methodB=b==null?"UNVERIFIED":regions.methodB();
                if(auditEligible) {
                    if(methodA.contains("R2"))r2Verified++;else r2Rejected++;
                    if(methodB.contains("R2"))r2Verified++;else r2Rejected++;
                }
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
                if(auditEligible && a==null&&b==null)missingTarget++;
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
                if(VERF_ENABLED && massSafe!=null && massSafe.maskedFrames()>0 &&
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
                if(VERF_ENABLED && auditEligible && (t.lastPlotRogWitnessTick<0 || now-t.lastPlotRogWitnessTick>=VERF_SAMPLE_TICKS)) {
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
                if(lines++<MAX_LOG_PER_TICK) log.info("[PDD-NATIVE-TARGET] dim={} tick={} ownerA={} ownerB={} methodA={} reasonA={} checkedA={} methodB={} reasonB={} checkedB={} regionCount={} rejectedPoints={} callbackHintA={} callbackHintB={} sourceCapSaturated={} worldMutation=OFF",
                        stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey(),
                        methodA,regions.status(),regions.checksA(),methodB,regions.status(),regions.checksB(),
                        regions.regions().size(),regions.rejected(),r1a!=null,r1b!=null,saturatedPoll);
                // R4 shadow audit. This only describes plausible loaded world
                // blocks; WITHOUT in-game ground truth it can never mint credit.
                if(auditEligible && pair.a().stableKey().startsWith("WORLD:") && a==null) {
                    int before=remainingR4Checks[0];
                    R4Probe probe=probeWorldR4(server,stamp.dimension,sweptB,records,remainingR4Checks);
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-TARGET] source=R4_SWEPT_PROVISIONAL dimension={} tick={} worldCandidate={} credited=false",stamp.dimension,stamp.tick,probe.status());
                    if(!probe.status().startsWith("PROVISIONAL_ONLY"))
                        logVerfR4(log,t,now,stamp.tick,pair,probe,records.size(),before,remainingR4Checks[0],sweptB);
                }
                if(auditEligible && pair.b().stableKey().startsWith("WORLD:") && b==null) {
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
                    var materialSnapshot=MaterialRegistry.snapshot();
                    // Sable 2.0.6 exports opposing local manifold normals per
                    // native record. Validate each pair, then average all
                    // witnesses in the SAME owner-local effect frame.
                    long normalStart=System.nanoTime();
                    var nativeNormal=(!result.credited() || massSafe==null
                            || massSafe.maskedFrames()>0)
                            ?NativeEffectDirection.unknown("NATIVE_FRAME_OR_EFFECT_UNVERIFIED")
                            :NativeEffectDirection.verify(pair,massSafe.contacts(),pre);
                    long normalNanos=System.nanoTime()-normalStart;
                    // Trace only accepted credits. The ledger buffers edge receipts
                    // transiently, emits after validation and never persists them.
                    final int[] traceCounts={0,0,0,0,0}; // edges, receipts, emitted, nodes, cutoffs
                    java.util.function.Consumer<ForcePropagation.Trace> fTrace=F_TRACE_ENABLED
                            ?traceEvent->{
                                if(traceEvent instanceof ForcePropagation.Edge)traceCounts[0]++;
                                else if(traceEvent instanceof ForcePropagation.Receipt)traceCounts[1]++;
                                else if(traceEvent instanceof ForcePropagation.Node)traceCounts[3]++;
                                else if(traceEvent instanceof ForcePropagation.Cutoff)traceCounts[4]++;
                                if(fTraceLines[0]>=MAX_F_TRACE_PER_TICK
                                        ||traceCounts[2]>=MAX_F_TRACE_PER_CREDIT)return;
                                fTraceLines[0]++;traceCounts[2]++;
                                if(traceEvent instanceof ForcePropagation.Edge edge) {
                                    log.info("[PDD-F-EDGE] credit={}/{} generation={} owner={} " +
                                            "layer={} parent={} child={} parentReceivedF={} parentLocalF={} " +
                                            "parentOutgoingF={} directionWeight={} sentF={} " +
                                            "normalFrame=OWNER_LOCAL normal={} normalEvidence={} mutation=OFF",
                                            pair.a().stableKey(),pair.b().stableKey(),result.episodeGeneration(),
                                            edge.parent().ownerKey(),edge.layer(),edge.parent().localBlock(),
                                            edge.child().localBlock(),edge.parentReceivedF(),edge.parentLocalF(),
                                            edge.parentOutgoingF(),edge.directionWeight(),edge.sentF(),
                                            nativeNormal.forOwner(edge.parent().ownerKey()).inward(),
                                            nativeNormal.forOwner(edge.parent().ownerKey()).evidence());
                                } else if(traceEvent instanceof ForcePropagation.Receipt receipt) {
                                    log.info("[PDD-F-MERGE] credit={}/{} generation={} owner={} " +
                                            "layer={} child={} parentCount={} totalReceivedF={} " +
                                            "processedOnce={} mutation=OFF",
                                            pair.a().stableKey(),pair.b().stableKey(),result.episodeGeneration(),
                                            receipt.child().ownerKey(),receipt.layer(),receipt.child().localBlock(),
                                            receipt.parentCount(),receipt.totalReceivedF(),receipt.processedOnce());
                                } else if(traceEvent instanceof ForcePropagation.Node node) {
                                    log.info("[PDD-F-NODE] credit={}/{} generation={} owner={} layer={} " +
                                                    "local={} receivedF={} localF={} outgoingSentF={} " +
                                                    "dampedF={} recoveredTerminalF={} thresholdDiscardF={} " +
                                                    "candidateQ={} finalQ={} candidates={} paidChildren={} " +
                                                    "decision={} mutation=OFF",
                                            pair.a().stableKey(),pair.b().stableKey(),result.episodeGeneration(),
                                            node.target().ownerKey(),node.layer(),node.target().localBlock(),
                                            node.receivedF(),node.localF(),node.forwardedF(),node.dampedF(),
                                            node.terminalRecoveredF(),node.discardedF(),node.rawQ(),
                                            node.finalQ(),node.eligibleChildren(),node.paidChildren(),node.decision());
                                } else if(traceEvent instanceof ForcePropagation.Cutoff cutoff) {
                                    log.info("[PDD-F-CUTOFF] credit={}/{} generation={} owner={} " +
                                                    "layer={} parent={} child={} F={} reason={} mutation=OFF",
                                            pair.a().stableKey(),pair.b().stableKey(),result.episodeGeneration(),
                                            cutoff.from().ownerKey(),cutoff.layer(),cutoff.from().localBlock(),
                                            cutoff.to()==null?"NONE":cutoff.to().localBlock(),
                                            cutoff.rejectedF(),cutoff.reason());
                                }
                            }:null;
                    long propagationStart=System.nanoTime();
                    ContactCreditLedger.Grant grant=result.credited()?regionLedger.issuePropagated(
                            pair,result,ContactCreditLedger.Source.SABLE_POST_EFFECT,regions.regions(),
                            OwnerResolver.propagationSnapshot(server,stamp.dimension),
                            materialSnapshot::resolveFingerprint,credit.settings(),damageSettings,
                            nativeNormal.owners(),fTrace):null;
                    long propagationNanos=System.nanoTime()-propagationStart;
                    boolean granted=grant!=null && grant.credited();
                    if(granted) {
                        log.info("[PDD-F-PERF] credit={} planNanos={} ownerCount=2 " +
                                        "maxNodesPerSide={} eventF={} eventQCap={} rawPeak={} fSaturated={} " +
                                        "normalMode={} traceEnabled={} includesTraceOutput={} mutation=OFF",
                                grant.id(),propagationNanos,ForcePropagation.MAX_NODES_PER_SIDE,
                                result.peakForce(),result.issuedQ(),group.forcePeak(),
                                group.forcePeak()>GameImpactCredit.MAX_GAME_F,nativeNormal.reason(),
                                F_TRACE_ENABLED,F_TRACE_ENABLED);
                        log.info("[PDD-NORMAL-PERF] credit={} verifyNanos={} availableRecords={} " +
                                        "capSampled={} verified={} mutation=OFF",
                                grant.id(),normalNanos,massSafe==null?0:massSafe.contacts().size(),
                                saturatedPoll,nativeNormal.owners().size()==2);
                        if(F_TRACE_ENABLED)log.info("[PDD-F-TRACE] credit={} edges={} receipts={} " +
                                        "nodes={} cutoffs={} " +
                                        "emitted={} suppressed={} maxPerTick={} maxPerCredit={} " +
                                        "quantization=0.1 directionUnknownEqualSplit=true mutation=OFF",
                                grant.id(),traceCounts[0],traceCounts[1],traceCounts[3],traceCounts[4],
                                traceCounts[2],traceCounts[0]+traceCounts[1]+traceCounts[3]+traceCounts[4]-traceCounts[2],
                                MAX_F_TRACE_PER_TICK,MAX_F_TRACE_PER_CREDIT);
                        if(VERF_ENABLED)log.info("[PDD-NATIVE-NORMAL] credit={} status={} " +
                                        "reason={} records={} meanResultant={} minimumAlignment={} " +
                                        "meanPolicy=UNIT_NORMAL_EQUAL_RECORD_WEIGHT ownerVectors={} nativeCapSampled={} effectPhase=true " +
                                        "exactSolverContact=false deformationAuthorized=false mutation=OFF",
                                grant.id(),nativeNormal.owners().size()==2?"CORROBORATED":"UNKNOWN",
                                nativeNormal.reason(),nativeNormal.corroboratedRecords(),
                                nativeNormal.meanResultant(),nativeNormal.minimumAlignment(),nativeNormal.owners(),
                                saturatedPoll);
                        if(VERF_ENABLED&&massSafe!=null) {
                            int shown=0;
                            for(var sample:massSafe.contacts()) {
                                if(shown++>=MAX_NATIVE_NORMAL_SAMPLES)break;
                                log.info("[PDD-NORMAL-SAMPLE] credit={} ordinal={} idA={} idB={} " +
                                                "normalA_local=({},{},{}) normalB_local=({},{},{}) " +
                                                "frameA={} frameB={} capSaturated={} mutation=OFF",
                                        grant.id(),shown-1,sample.idA(),sample.idB(),
                                        sample.ax(),sample.ay(),sample.az(),sample.bx(),sample.by(),sample.bz(),
                                        sample.frameA()==null?"WORLD":sample.frameA().uuid(),
                                        sample.frameB()==null?"WORLD":sample.frameB().uuid(),sample.capSaturated());
                            }
                            if(massSafe.contacts().size()>MAX_NATIVE_NORMAL_SAMPLES)
                                log.info("[PDD-NORMAL-SAMPLES] credit={} logged={} suppressed={} mutation=OFF",
                                        grant.id(),MAX_NATIVE_NORMAL_SAMPLES,
                                        massSafe.contacts().size()-MAX_NATIVE_NORMAL_SAMPLES);
                        }
                        credited++;
                        if(grant.propagated()) {
                            double fInput=grant.inputF(),fAccounted=grant.propagationA().assignedF()+
                                    grant.propagationA().dampedF()+grant.propagationA().unresolvedF()+
                                    grant.propagationA().audit().thresholdDiscardF()+
                                    grant.propagationB().assignedF()+grant.propagationB().dampedF()+
                                    grant.propagationB().unresolvedF()+
                                    grant.propagationB().audit().thresholdDiscardF();
                            double qError=grant.issuedQ()-grant.accounted();
                            double fError=fInput-fAccounted;
                            log.info("[PDD-F-INVARIANT] credit={} FInput={} FAccounted={} FError={} " +
                                            "issuedQ={} reservedA={} reservedB={} unusedQ={} QError={} " +
                                            "FStatus={} QStatus={} mutation=OFF",
                                    grant.id(),fInput,fAccounted,fError,grant.issuedQ(),
                                    grant.allocatedA(),grant.allocatedB(),grant.unused(),qError,
                                    Math.abs(fError)<=Math.max(1e-7,fInput*1e-7)?"PASS":"FAIL",
                                    Math.abs(qError)<=Math.max(1e-7,grant.issuedQ()*1e-7)?"PASS":"FAIL");
                            log.info("[PDD-Q-SEAL] credit={} ownerA={} ownerB={} " +
                                            "preCapA={} preCapB={} capA={} capB={} " +
                                            "sealedA={} sealedB={} " +
                                            "preCapDiscardQA={} preCapDiscardQB={} " +
                                            "postCapAndFloorLostA={} postCapAndFloorLostB={} " +
                                            "unusedQ={} mutation=OFF",
                                    grant.id(),pair.a().stableKey(),pair.b().stableKey(),
                                    grant.propagationA().finalQ(),grant.propagationB().finalQ(),
                                    result.allocation().sideA(),result.allocation().sideB(),
                                    grant.allocatedA(),grant.allocatedB(),
                                    grant.propagationA().audit().thresholdDiscardQ(),
                                    grant.propagationB().audit().thresholdDiscardQ(),
                                    Math.max(0,grant.propagationA().finalQ()-grant.allocatedA()),
                                    Math.max(0,grant.propagationB().finalQ()-grant.allocatedB()),
                                    grant.unused());
                            for(var side:List.of(grant.propagationA(),grant.propagationB())) {
                                String owner=side==grant.propagationA()?pair.a().stableKey():pair.b().stableKey();
                                if(lines++<MAX_LOG_PER_TICK)log.info(
                                        "[PDD-F-LEDGER] credit={} owner={} inputF={} allocatedF={} dampedF={} " +
                                        "unresolvedF={} thresholdDiscardF={} terminalRecoveredF={} " +
                                        "candidateQ={} finalQ={} dampedQ={} thresholdDiscardQ={} " +
                                        "terminalNodes={} paidEdges={} mergedNodes={} blockedNodes={} " +
                                        "maxDepth={} end={} nodes={} mutation=OFF",
                                        grant.id(),owner,side.inputF(),side.assignedF(),side.dampedF(),
                                        side.unresolvedF(),side.audit().thresholdDiscardF(),
                                        side.audit().terminalRecoveredF(),side.candidateQ(),
                                        side.finalQ(),side.dampedQ(),side.audit().thresholdDiscardQ(),
                                        side.audit().terminalNodes(),side.audit().paidEdges(),
                                        side.audit().mergedNodes(),side.audit().safetyBlockedNodes(),
                                        side.audit().maxDepth(),side.endReason(),side.shares().size());
                                int shownNodes=0;
                                for(var node:side.shares())if(shownNodes++<4 && lines++<MAX_LOG_PER_TICK)log.info(
                                        "[PDD-F-PROPAGATION] credit={} owner={} layer={} local={} F={} " +
                                        "candidateQ={} finalQ={} E={} mutation=OFF",
                                        grant.id(),owner,node.layer(),node.target().localBlock(),
                                        node.allocatedF(),node.rawQ(),node.finalQ(),node.elasticityPercent());
                            }
                        }
                        // Only the first approved frame of each actually issued grant.
                        forceCalibration.record(result.peakForce(),credit.settings());
                        multiGroupCredits++;
                        multiRegions+=grant.observedRegions();
                        // Native n1/n2 are only directional candidates. The existing
                        // Plot/Plot all-substep gate must prove orientation consistency;
                        // Plot/World is intentionally UNKNOWN until World normal frame is verified.
                        NativePlotDirection.Prepared.DirectionResult witness;
                        if(massSafe==null || massSafe.maskedFrames()>0
                                || massSafe.contacts().stream().anyMatch(c->
                                (c.idA()>=0&&c.frameA()==null) || (c.idB()>=0&&c.frameB()==null))) {
                            witness=new NativePlotDirection.Prepared.DirectionResult(
                                    Map.of(),"OWNER_MASS_EPOCH_FRAME_UNVERIFIED",0);
                        } else if(pair.a().stableKey().startsWith("PLOT:")
                                &&pair.b().stableKey().startsWith("PLOT:")) {
                            witness=NativePlotDirection.prepare(massSafe.contacts(),pre).ownerLocalDirection(
                                    stamp.dimension,stamp.tick,group.runtimePair().lowId(),group.runtimePair().highId());
                        } else if(!nativeNormal.owners().isEmpty()) {
                            long earliest=pre.stream()
                                    .filter(p->p.phase()==CollisionFrame.Phase.PRE_SOLVER
                                            && p.serverTick()==stamp.tick
                                            && stamp.dimension.equals(p.dimension()))
                                    .mapToLong(CollisionFrame.Snapshot::epoch)
                                    .min().orElse(-1);
                            var axes=NativeEffectDirection.gridAxes(nativeNormal,stamp.tick,earliest);
                            witness=new NativePlotDirection.Prepared.DirectionResult(axes,
                                    axes.isEmpty()?"F_VECTOR_VALID_GRID_AXIS_NOT_APPLICABLE":nativeNormal.reason(),
                                    nativeNormal.corroboratedRecords());
                        } else witness=new NativePlotDirection.Prepared.DirectionResult(
                                Map.of(),nativeNormal.reason(),0);
                        // An oblique averaged vector is VALID for F; absence of
                        // a signed grid axis is not a failure of that evidence.
                        // Future voxel movement still requires separate exact
                        // axis/contact proof; do NOT round a diagonal normal.
                        boolean fVectorValid=nativeNormal.owners().size()==2;
                        if(fVectorValid)directionApproved++;
                        else directionUnknown++;
                        if(VERF_ENABLED)log.info("[PDD-DIRECTION] credit={} status={} reason={} " +
                                "fVectorStatus={} fVectorReason={} gridAxisStatus={} " +
                                "auditedPreFrames={} witnesses={} exactContact=false " +
                                "deformationAuthorized=false mutation=OFF",
                                grant.id(),fVectorValid?"F_VECTOR_CORROBORATED":"UNKNOWN",
                                witness.reason(),fVectorValid?"CORROBORATED":"UNKNOWN",nativeNormal.reason(),
                                witness.owners().isEmpty()?"NONAXIAL_OR_UNVERIFIED":"AXIS_CORROBORATED",
                                witness.auditedFrames(),witness.owners());
                        try {
                            var materials=materialSnapshot;
                            long materialStart=System.nanoTime();
                            var previewResult=preview.record(grant,materials.generation(),now,
                                    materials::resolveFingerprint,damageSettings,
                                    key->java.util.Optional.ofNullable(witness.owners().get(
                                            key.ownerSpace()+":"+key.ownerId())));
                            long materialNanos=System.nanoTime()-materialStart;
                            log.info("[PDD-MATERIAL-PERF] credit={} previewNanos={} targetsA={} " +
                                            "targetsB={} status={} mutation=OFF",
                                    grant.id(),materialNanos,grant.a().size(),grant.b().size(),
                                    previewResult.status());
                            if(previewResult.status()!=PreviewDamageLedger.ResultStatus.ACCEPTED) {
                                previewFailures++;
                                log.warn("[PDD-MATERIAL-PLAN] credit={} status={} " +
                                        "reason=PREVIEW_DEDUP_OR_BOUNDED_CAP mutation=OFF",
                                        grant.id(),previewResult.status());
                            } else {
                                var planReport=previewResult.report();
                                materialPlanned+=planReport.plans().size();
                                if(planReport.missingMaterialQ()>0)materialMissing++;
                                double difference=Math.abs(planReport.accountedQ()-grant.issuedQ());
                                if(difference>1.0e-7)
                                    throw new IllegalStateException("material Q reconciliation failed " + difference);
                                if(materialLines++<MAX_MATERIAL_LOGS_PER_TICK)log.info("[PDD-MATERIAL-PLAN] credit={} " +
                                        "revision={} materialGeneration={} verifiedTargets={} missingMaterialQ={} " +
                                        "reservedQ={} absorbedQ={} directQ={} unusedPlannedQ={} " +
                                        "fixedIncrement={} directionWitnesses={} stress=INACTIVE plastic=INACTIVE " +
                                        "state=PREVIEW_ONLY committedDamage=0 previewEntries={} previewFixedBefore={} " +
                                        "previewFixedAfter={} previewUpdated={} mutation=OFF",
                                        planReport.creditId(),DamageModel.ALGORITHM_REVISION,
                                        planReport.materialGeneration(),planReport.plans().size(),
                                        planReport.missingMaterialQ(),grant.issuedQ(),planReport.absorbedQ(),
                                        planReport.directQ(),planReport.unusedPlannedQ(),
                                        planReport.fixedIncrementTotal(),witness.owners().size(),
                                        previewResult.entries(),previewResult.fixedBefore(),
                                        previewResult.fixedAfter(),previewResult.updatedTargets());
                                int targetsLogged=0;
                                for(int i=0;i<planReport.plans().size() && materialLines<MAX_MATERIAL_LOGS_PER_TICK;i++) {
                                    var targetPlan=planReport.plans().get(i);
                                    var m=targetPlan.material();var planned=targetPlan.plan();
                                    materialLines++;targetsLogged++;
                                    log.info("[PDD-MATERIAL-TARGET] key={} H={} T={} D={} E={} unbreakable={} " +
                                                    "Q={} absorbed={} direct={} plasticUnused={} stressUnused={} " +
                                                    "rawDamage={} incrementFixed={} predictedDamage={} remainder={} " +
                                                    "thresholdReached={} plasticPreview={} direction={} " +
                                                    "applied=false reason=SHADOW_PREVIEW_ONLY mutation=OFF",
                                            targetPlan.key(),m.hardness(),m.threshold(),m.ductilityPercent(),
                                            m.elasticityPercent(),m.unbreakable(),planned.incomingQ(),
                                            planned.absorbedQ(),planned.directQ(),planned.plasticQ(),planned.stressQ(),
                                            planned.rawDamage(),planned.fixedIncrement(),planned.plannedDamageU8(),
                                            planned.plannedRemainderU8(),planned.thresholdReached(),
                                            planned.plannedPlasticIncrement(),
                                            targetPlan.direction().map(Object::toString).orElse("UNKNOWN"));
                                }
                                log.info("[PDD-MATERIAL-TRACE] credit={} plannedTargets={} " +
                                                "logged={} suppressed={} maxPerTick={} mutation=OFF",
                                        grant.id(),planReport.plans().size(),targetsLogged,
                                        planReport.plans().size()-targetsLogged,MAX_MATERIAL_LOGS_PER_TICK);
                            }
                        } catch (RuntimeException invalidMaterialPlan) {
                            materialRejected++;
                            log.warn("[PDD-MATERIAL-PLAN] reason=PLAN_REJECTED " +
                                    "credit={} Q={} applied=false mutation=OFF",grant.id(),grant.issuedQ(),invalidMaterialPlan);
                        }
                        if(worldCommit!=null) {
                            try {worldCommit.offer(grant,materialSnapshot);}
                            catch(RuntimeException commitError) {
                                log.error("[PDD-COMMIT] phase=OFFER status=ABORTED " +
                                        "credit={} reason=UNEXPECTED_ADAPTER_ERROR",grant.id(),commitError);
                            }
                        }
                    } else {
                        zero++;
                        if(grant!=null) {
                            ledgerRejected++;
                            if(grant.status()==ContactCreditLedger.Status.INVALID_TARGET_SET)
                                log.warn("[PDD-F-PLAN-REJECT] pair={}/{} generation={} " +
                                                "reason={} sourceStatus={} mutation=OFF",
                                        pair.a().stableKey(),pair.b().stableKey(),
                                        result.episodeGeneration(),
                                        regionLedger.lastPropagationFailure(),grant.status());
                        }
                    }
                    if(grant!=null && lines++<MAX_LOG_PER_TICK)
                        log.info("[PDD-LEDGER] dimension={} tick={} episodeGeneration={} source={} status={} regions={} checksA={} checksB={} rejectedPoints={} Q={} allocatedA={} allocatedB={} unused={} aTargets={} bTargets={} ownerEpochA={} ownerEpochB={} sourceCapSaturated={} mutation=OFF",
                                stamp.dimension,stamp.tick,grant.id().episodeGeneration(),grant.source(),grant.status(),
                                grant.observedRegions(),regions.checksA(),regions.checksB(),regions.rejected(),
                                grant.issuedQ(),grant.allocatedA(),grant.allocatedB(),grant.unused(),
                                grant.a(),grant.b(),pair.a().generation(),pair.b().generation(),saturatedPoll);
                    if(lines++<MAX_LOG_PER_TICK) log.info("[PDD-TARGET] dimension={} tick={} ownerA={} ownerAGeneration={} ownerB={} ownerBGeneration={} targetA={} fingerprintA={} targetB={} fingerprintB={} frameMethodA={} frameMethodB={} shapeAudit={} quality={} reason={} approachByAabb={} armedFromGap={} recentGap={} worldMutation=OFF",
                            stamp.dimension,stamp.tick,pair.a().stableKey(),pair.a().generation(),pair.b().stableKey(),pair.b().generation(),
                            a==null?"UNVERIFIED":a.localBlock(),a==null?"UNVERIFIED":a.fingerprint(),
                            b==null?"UNVERIFIED":b.localBlock(),b==null?"UNVERIFIED":b.fingerprint(),
                            methodA,methodB,
                            auditEligible?"OWNER_SHAPE_CHECKED":"SKIPPED_DUPLICATE",
                            !auditEligible?"NOT_AUDITED_DUPLICATE":a==null&&b==null?"SOURCE_UNVERIFIED":"POST_EFFECT_CORROBORATED_GAME_PROXY",
                            !auditEligible?"NO_Q_AUTHORIZATION":a==null&&b==null?"TARGET_UNVERIFIED":"SABLE_NATIVE_CONSUMER_MAPPED",
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
        // Plot/Plot uses disjoint AABBs; Plot/World requires an atomic verified empty region.
        for(var entry:tracked.entrySet()) {
            Pair p=entry.getKey(); Tracked t=entry.getValue();
            if(contacted.contains(p) || t.lastProcessed==now) continue;
            // Avoid repeated full World proofs once ARMED; Plot/Plot behavior remains intact.
            if(credit.phase(p)==Phase.ARMED &&
                    (p.a().stableKey().startsWith("WORLD:")||p.b().stableKey().startsWith("WORLD:")))
                continue;
            Box a=box(server,p.a().stableKey(),t.a,p.dimension(),worldBounds,knownWorldBlocks);
            Box b=box(server,p.b().stableKey(),t.b,p.dimension(),worldBounds,knownWorldBlocks);
            boolean worldSide=p.a().stableKey().startsWith("WORLD:")
                    || p.b().stableKey().startsWith("WORLD:");
            Box plotBox=p.a().stableKey().startsWith("PLOT:")?a:b;
            WorldClearanceVerifier.Result clearance=worldSide
                    ? inspectWorldClearance(server,p,plotBox,now,remainingClearanceChecks,remainingAtomicConfirmChecks)
                    : null;
            logClearance(log,t,now,p,clearance);
            if(worldSide && (clearance==null || !clearance.proved()))
                credit.invalidateSeparationStreak(p);
            boolean definitelySeparate=RealFixBoundsEvidence.separatedForRearm(
                    worldSide,a,b,clearance!=null && clearance.proved());
            t.previousA=a;t.previousB=b;t.lastProcessed=now;
            if(definitelySeparate) {
                t.lastPositiveSeparation=true;
                t.lastSeparatedTick=now;
                try {
                    var before=credit.evidence(p);
                    var d=credit.step(new Frame(p,now,List.of(),true,false,t.a,t.b));
                    if(d.phase()==Phase.ARMED)worldClearance.contact(p);
                    if(VERF_ENABLED && before.phase()!=d.phase())
                        log.info("[PDD-VERF-SEPARATION] serverTick={} pair={}/{} source={} " +
                                "stateBefore={} stateAfter={} separatedBefore={} separatedAfter={} " +
                                "aabbGap={} sourceVerified=true mutation=OFF",
                                now,p.a().stableKey(),p.b().stableKey(),
                                worldSide?"VERIFIED_WORLD_CLEARANCE":"OBSERVED_AABB_GAP",before.phase(),d.phase(),
                                before.separatedSamples(),credit.evidence(p).separatedSamples(),
                                worldSide?"VERIFIED_EMPTY_REGION":verifiedGap(a,b));
                }
                catch(RuntimeException ignored) { /* invalid snapshot: Q=0 */ }
            }
        }
        // Pre-contact state construction (§22). Silence alone never proves
        // separation: plot-plot uses real Sable world AABBs; plot-world uses a
        // complete, loaded, bounded world-collider clearance inspection.
        prearmIndependentPairs(server,now,worldBounds,contacted,remainingClearanceChecks,remainingAtomicConfirmChecks,log);
        if(now%100==0) {
            credit.prune(now);
            regionLedger.prune(now);
            worldClearance.prune(now);
            generations.entrySet().removeIf(e->!e.getValue().present
                    && e.getValue().lastSeen>=0 && now-e.getValue().lastSeen>600);
            // A pre-contact ARMED pair is processed every tick, but has never
            // had a contact. Expiring on lastSeen (-1) discards its verified
            // clearance/AABB evidence while GameImpactCredit stays ARMED.
            tracked.entrySet().removeIf(e->trackedIdleExpired(now,e.getValue().lastProcessed));
            log.info("[PDD-0.4.0finalFix] phase=SUMMARY observed={} credited={} zero={} ownerMissing={} targetMissing={} ambiguous={} trackedPairs={} r2Verified={} r2Rejected={} r3Verified={} prearmed={} r1Fallback={} regionGroups={} regionCount={} regionTooBroad={} ledgerRejected={} activeCredits={} observerErrors={} nativeDropped={} nativeMalformed={} nativeCapSaturatedPolls={} r2Audits={} r2SkippedDuplicate={} r2ActualShapeChecks={} callbackDropped={} preHistoryDropped={} generation=IDS-1.0.7 mutation=OFF",
                    observed,credited,zero,missingOwner,missingTarget,ambiguousOwners,tracked.size(),
                    r2Verified,r2Rejected,r3Verified,prearmed,r1Fallback,
                    multiGroupCredits,multiRegions,multiTooBroad,ledgerRejected,regionLedger.size(),
                    NativeCollisionTelemetry.observerErrors(),
                    NativeCollisionTelemetry.dropped(),NativeCollisionTelemetry.malformed(),
                    NativeCollisionTelemetry.nativeCapSaturatedPolls(),r2Audits,r2SkippedDuplicate,r2ShapeChecks,
                    com.physicaldestroydeform.diagnostics.SableContactQueue.dropped(),SableFrameCapture.preHistoryDropped());
            log.info("[PDD-MATERIAL-PLAN] phase=SUMMARY targetsPlanned={} missingMaterialEvents={} " +
                    "planRejected={} mode=PREVIEW_ONLY storedEntries={} fixedTotal={} " +
                    "handledCredits={} duplicatePreview={} previewCapacityRejected={} " +
                    "directionCorroborated={} directionUnknown={} previewFailures={} applied=0 mutation=OFF",
                    materialPlanned,materialMissing,materialRejected,preview.size(),preview.fixedTotal(),
                    preview.handledCredits(),preview.duplicates(),preview.capacityRejected(),
                    directionApproved,directionUnknown,previewFailures);
            if(VERF_ENABLED && preview.size()>0) {
                try {
                    byte[] encoded=preview.verifyRoundTrip();
                    log.info("[PDD-PREVIEW-CODEC] status=PASS entries={} bytes={} " +
                                    "roundTrips={} fixedTotal={} schema={} corruptionVeto=PASS " +
                                    "authoritative=false mutation=OFF",
                            preview.size(),encoded.length,preview.roundTrips(),preview.fixedTotal(),
                            com.physicaldestroydeform.state.SparseStateCodec.SCHEMA_VERSION);
                }catch(RuntimeException invalid) {
                    log.warn("[PDD-PREVIEW-CODEC] status=FAIL reason={} " +
                            "roundTripErrors={} mutation=OFF",invalid.getClass().getSimpleName(),
                            preview.roundTripErrors(),invalid);
                }
            }
            if(VERF_ENABLED) {
                var evidence=forceCalibration.snapshot();
                var settings=credit.settings();
                log.info("[PDD-SUPERFIX-CAL] approved={} saturated={} minPeak={} maxPeak={} minRawQ={} maxRawQ={} " +
                        "forceBuckets=[0:16,16:64,64:256,256:1024,1024:4096,4096:16384,16384:65536,65536:inf] counts={} " +
                        "forceFloor={} forceGain={} maxGroupQ={} mutation=OFF",
                        evidence.approved(),evidence.saturated(),evidence.minPeak(),evidence.maxPeak(),
                        evidence.minRawQ(),evidence.maxRawQ(),evidence.forceBuckets(),
                        settings.forceFloor(),settings.forceGain(),settings.maxGroupQ());
                log.info("[PDD-SUPERFIX-CLEARANCE-PROF] calls={} cellReads={} elapsedNanos={} proved={} " +
                        "maxCells={} confirmPerTick={} mutation=OFF",
                        clearanceCalls,clearanceCellReads,clearanceElapsedNanos,clearanceProved,
                        WorldClearanceVerifier.MAX_CELLS,WorldClearanceVerifier.MAX_CONFIRM_PER_TICK);
            }
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
        worldClearance.invalidateOwner(owner);
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
    /** Fair, budgeted precontact clearance. Never loads a chunk or guesses a collider. */
    private void prearmIndependentPairs(MinecraftServer server,long now,
                                       Map<String,Map<String,Box>> bounds,Set<Pair> contacted,
                                       int[] remaining,int[] confirmationRemaining,Logger log) {
        int attempted=0;
        for(var dimEntry:bounds.entrySet()) {
            String dim=dimEntry.getKey();
            var entries=new ArrayList<>(dimEntry.getValue().entrySet());
            entries.sort(Map.Entry.comparingByKey());
            // Rotate work order so a busy/large ship cannot starve later plots.
            if(!entries.isEmpty()) java.util.Collections.rotate(entries,(int)(now%entries.size()));
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
                Pair worldPair=Pair.canonical(runId,dim,new Owner("WORLD:"+dim,1),
                        new Owner(first.getKey(),currentGeneration(dim,first.getKey())));
                if(!contacted.contains(worldPair) && credit.phase(worldPair)!=Phase.ARMED &&
                        (tracked.get(worldPair)==null || tracked.get(worldPair).lastProcessed!=now)) {
                    var clearance=inspectWorldClearance(server,worldPair,firstBox,now,remaining,confirmationRemaining);
                    Tracked trackedPair=tracked.get(worldPair);
                    if(trackedPair!=null)logClearance(log,trackedPair,now,worldPair,clearance);
                    if(clearance.proved())
                        seedSeparation(worldPair,now,contacted,"WORLD:"+dim,null,firstBox,log);
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
    /** Track last *verified processing*, not last contact. Both maps must retain
     * the same live pre-contact episode after 600+ ticks of proven clearance. */
    static boolean trackedIdleExpired(long now,long lastProcessed) {
        return now>lastProcessed && now-lastProcessed>600;
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
            if(d.phase()==Phase.ARMED)worldClearance.contact(pair);
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
    private WorldClearanceVerifier.Result inspectWorldClearance(MinecraftServer server,Pair pair,
                                                          Box plotBox,long now,int[] remaining,int[] confirmationRemaining) {
        if(plotBox==null)
            return new WorldClearanceVerifier.Result(WorldClearanceVerifier.Status.UNAVAILABLE,
                    0,0,"PLOT_BOUNDS_UNAVAILABLE");
        for(ServerLevel level:server.getAllLevels()) {
            if(!level.dimension().location().toString().equals(pair.dimension()))continue;
            var container=SubLevelContainer.getContainer(level);
            if(container==null){worldClearance.contact(pair);return new WorldClearanceVerifier.Result(
                    WorldClearanceVerifier.Status.UNAVAILABLE,0,0,"NO_SABLE_CONTAINER");}
            long startNanos=VERF_ENABLED?System.nanoTime():0;
            int[] cellsRead={0};
            var result=worldClearance.inspect(pair,plotBox,now,remaining,confirmationRemaining,(x,y,z)->{
                cellsRead[0]++;
                BlockPos pos=new BlockPos(x,y,z);
                try {
                    if(!level.hasChunkAt(pos))return WorldClearanceVerifier.Cell.UNAVAILABLE;
                    var chunk=new ChunkPos(pos);
                    if(container.inBounds(chunk)||container.getPlot(chunk)!=null)
                        return WorldClearanceVerifier.Cell.UNAVAILABLE;
                    var state=level.getBlockState(pos);
                    return !state.isAir()&&!state.getCollisionShape(level,pos).isEmpty()
                            ? WorldClearanceVerifier.Cell.COLLIDER:WorldClearanceVerifier.Cell.EMPTY;
                } catch(RuntimeException invalid) {return WorldClearanceVerifier.Cell.UNAVAILABLE;}
            });
            if(VERF_ENABLED) {
                clearanceCalls++;
                clearanceCellReads+=cellsRead[0];
                clearanceElapsedNanos+=Math.max(0L,System.nanoTime()-startNanos);
                if(result.proved())clearanceProved++;
            }
            return result;
        }
        worldClearance.contact(pair);
        return new WorldClearanceVerifier.Result(WorldClearanceVerifier.Status.UNAVAILABLE,
                0,0,"DIMENSION_UNAVAILABLE");
    }
    private static void logClearance(Logger log,Tracked tracked,long now,Pair pair,
                                      WorldClearanceVerifier.Result result) {
        if(tracked==null || result==null || !VERF_ENABLED)return;
        String signature=result.status()+":"+result.reason();
        if(!signature.equals(tracked.lastClearanceStatus)
                || now-tracked.lastClearanceStatusTick>=VERF_SAMPLE_TICKS) {
            log.info("[PDD-WORLD-CLEARANCE] serverTick={} pair={}/{} status={} checked={} total={} " +
                    "reason={} mutation=OFF",now,pair.a().stableKey(),pair.b().stableKey(),
                    result.status(),result.checked(),result.total(),result.reason());
            tracked.lastClearanceStatus=signature;tracked.lastClearanceStatusTick=now;
        }
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

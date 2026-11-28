package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.LogSelection;
import com.physicaldestroydeform.diagnostics.RogTrace;


import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.diagnostics.DiagnosticMode;
import com.physicaldestroydeform.diagnostics.TraceBudget;
import com.physicaldestroydeform.material.MaterialRegistry;
import com.physicaldestroydeform.material.CopycatMaterial;
import com.physicaldestroydeform.material.PreviewDamageLedger;
import com.physicaldestroydeform.material.TargetMaterials;
import com.physicaldestroydeform.world.WorldCommitService;
import com.physicaldestroydeform.plot.PlotCommitService;
import com.physicaldestroydeform.plot.NeoForgePlotGateway;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private static final boolean F_TRACE_ENABLED=LogSelection.enabled(LogSelection.Channel.FORCE)&&Boolean.parseBoolean(
            System.getProperty("pdd.f.trace.enabled",Boolean.toString(DiagnosticMode.trace())));
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
            System.getProperty("pdd.verf.enabled",Boolean.toString(LogSelection.any())));
    private static final boolean DETAIL_ENABLED=(LogSelection.enabled(LogSelection.Channel.CONTACT)||LogSelection.enabled(LogSelection.Channel.NATIVE))&&(DiagnosticMode.debug()||Boolean.getBoolean("pdd.verf.enabled"));
    private static final boolean NORMAL_SAMPLES_ENABLED=LogSelection.enabled(LogSelection.Channel.NATIVE)
            &&(DETAIL_ENABLED||System.getProperty("pdd.normal.samples.maxPerCredit")!=null);
    private static final long VERF_SAMPLE_TICKS=Math.max(20L,
            Long.getLong("pdd.verf.sampleTicks",100L));
    private String runId=UUID.randomUUID().toString();
    private final GameImpactCredit credit=new GameImpactCredit(Settings.fromSystemProperties());
    private final DamageModel.Settings damageSettings=DamageModel.Settings.fromSystemProperties();
    private final ContactCreditLedger regionLedger=new ContactCreditLedger();
    private final VerifiedContactGraph.History contactHistory=new VerifiedContactGraph.History();
    private final WorldClearanceVerifier worldClearance=new WorldClearanceVerifier();
    private final ForceCalibration forceCalibration=new ForceCalibration();
    private final PreviewDamageLedger preview=new PreviewDamageLedger();
    enum PreviewMode {
        OFF, SUMMARY, FULL;
        static PreviewMode parse(String configured) {
            return switch(configured.trim().toUpperCase(Locale.ROOT)) {
                case "OFF" -> OFF;
                case "FULL" -> FULL;
                default -> SUMMARY;
            };
        }
    }
    private PreviewMode previewMode=PreviewMode.SUMMARY;
    private boolean previewCheckpointEnabled;
    private WorldCommitService worldCommit;
    private PlotCommitService plotCommit;
    /** Side-effect commit is intentionally outside physics/native callback. */
    public void setWorldCommitService(WorldCommitService next){this.worldCommit=next;}
    public void setPlotCommitService(PlotCommitService next){this.plotCommit=next;}
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
    private long repeatedDiagnosticGroupsSuppressed;
    static boolean eligibleSecondary(Decision result,EvidenceSnapshot before,
                                     boolean priorTracked,boolean separated,boolean independentApproach) {
        return priorTracked&&!separated&&!independentApproach
                &&before.phase()==Phase.ACTIVE&&!before.hasUnspentAuthorization()
                &&result.phase()==Phase.ACTIVE&&result.reason()==Reason.DUPLICATE_EPISODE
                &&!result.credited();
    }
    /** One bounded sampling state per existing owner pair; never read by the credit gate. */
    static final class RejectionTrace {
        private Reason lastReason;
        private SableContactRegions.Status lastStatus;
        private long lastTick=-1;
        boolean sample(boolean fullTrace,boolean evidence,long now,Reason reason,
                       SableContactRegions.Status status,long interval) {
            if(!fullTrace && !evidence && lastReason==reason && lastStatus==status
                    && now>=lastTick && now-lastTick<interval)return false;
            lastReason=reason;lastStatus=status;lastTick=now;
            return true;
        }
    }
    private static final class Tracked {
        final RejectionTrace rejectionTrace=new RejectionTrace();
        boolean nativeCapActive;
        String lastGrantFailure="";long lastGrantFailureTick=-1;
        final DirectionalContactRegions regionHistory=new DirectionalContactRegions();
        Target a,b;
        Box previousA,previousB;
        long lastSeen=-1;
        long lastProcessed=-1;
        boolean lastPositiveSeparation;
        long lastSeparatedTick=-1;
        double lastPeak=Double.NaN;
        double lastCreditedPeak=Double.NaN;
        int quietForceFrames;
        long lastCreditedTick=-1;
        String lastVerfRegion="";
        long lastPlotRogWitnessTick=-1;
        long lastPlotRogEpochTick=-1;
        long lastVerfRegionTick=-1;
        String lastVerfFsm="";
        long lastVerfFsmTick=-1;
        long lastHighFLogTick=-1;
        String lastVerfR4="";
        long lastVerfR4Tick=-1;
        String lastClearanceStatus="";
        long lastClearanceStatusTick=-1;
    }
    static final class Generation {
        int runtimeId;
        long number=1;
        boolean present=true;
        private long absentSince=-1;
        long lifecycleRevision=-1;
        Generation(int runtimeId){this.runtimeId=runtimeId;}
        void setPresent(boolean live,long serverTick) {
            if(live)absentSince=-1;
            else if(present||absentSince<0)absentSince=serverTick;
            present=live;
        }
        boolean idleExpired(long serverTick) {
            return !present&&absentSince>=0&&trackedIdleExpired(serverTick,absentSince);
        }
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
    static boolean secondaryPairEligible(boolean directlyAuthorized,int ownerPairCount){
        return !directlyAuthorized&&ownerPairCount==1;
    }
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
        contactHistory.clear();
        runId=UUID.randomUUID().toString();
        refreshDropBaseline();
        credited=observed=zero=missingOwner=ambiguousOwners=missingTarget=0;
        r2Verified=r2Rejected=r3Verified=prearmed=r1Fallback=0;
        multiGroupCredits=multiRegions=ledgerRejected=multiTooBroad=0;
        repeatedDiagnosticGroupsSuppressed=0;
        r2Audits=r2SkippedDuplicate=r2ShapeChecks=0;
        clearanceCalls=clearanceCellReads=clearanceElapsedNanos=clearanceProved=0;
        forceCalibration.clear();
        materialPlanned=materialMissing=materialRejected=0;
        directionApproved=directionUnknown=previewFailures=0;
        preview.clear();
        worldCommit=null;
        plotCommit=null;
        previewMode=PreviewMode.SUMMARY;
        previewCheckpointEnabled=false;
        previewCheckpointWritable=true;
        previewPolicyId=null;
    }

    private void refreshDropBaseline() {
        lastDropped=NativeCollisionTelemetry.dropped();
        lastMalformed=NativeCollisionTelemetry.malformed();
        lastCallbackDropped=com.physicaldestroydeform.diagnostics.SableContactQueue.dropped();
        lastPreHistoryDropped=SableFrameCapture.preHistoryDropped();
    }

    /** Diagnostic snapshot ONLY. No connection to real World/Plot damage persistence. */
    public void loadPreview(MinecraftServer server,Logger log) {
        // Counters are JVM-wide; captures from the stopped server are not losses
        // in this new session. Runtime stamps independently reject old evidence.
        refreshDropBaseline();
        var q=credit.settings();
        { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-POLICY] revision={} forceEnter={} forceFloor={} forceGain={} maxGroupQ={} maxGameF={} " +
                        "damageGain={} directShare={} plasticShare={} stressShare={} fixedPointScale={} " +
                        "F_FIRST=TRUE halfTransfer=0.5 maxNodesPerSide={} minRemainingF={} " +
                        "source=POST_EFFECT_GAME_PROXY measured=false mutation=OFF",
                GameImpactCredit.POLICY_REVISION,q.forceEnter(),q.forceFloor(),q.forceGain(),
                q.maxGroupQ(),GameImpactCredit.MAX_GAME_F,damageSettings.damageGain(),DamageModel.DIRECT_SHARE,
                DamageModel.PLASTIC_SHARE,DamageModel.STRESS_SHARE,
                com.physicaldestroydeform.BlockDamageState.FIXED_SCALE,
                ForcePropagation.MAX_NODES_PER_SIDE,ForcePropagation.MIN_PROPAGATED_F); }
        { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-FACE-POLICY] revision=astraFixFix-PAIRED-LOCAL-PATCH nativeWitnesses=1+ " +
                "maxFaceArea=16384 ownerLocalShapeProof=REQUIRED sourceBudget=ONE_COLLISION " +
                "expansion=BILATERAL_EXACT_THEN_PAIRED_LOCAL_GAME_PATCH syntheticNativeContact=NONE"); }
        { if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info("[PDD-F-SPREAD-POLICY] revision=spreadFix-DIRECTION-70-30 " +
                "inwardWeight=0.70 lateralWeight=0.30 transferShare=0.50 " +
                "reverseEdges=FORBIDDEN verifiedNeighbors=REQUIRED " +
                "shapeConeVeto=DISABLED FSourceBudget=ONE_COLLISION"); }
        previewMode=PreviewMode.parse(System.getProperty("pdd.preview.mode","SUMMARY"));
        previewCheckpointEnabled=previewMode!=PreviewMode.OFF&&PreviewCheckpoint.enabled();
        { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-PREVIEW-POLICY] mode={} periodicCodec={} checkpoint={} maxEntries={} maxCredits={} authoritative=false",
                previewMode,previewMode==PreviewMode.FULL,previewCheckpointEnabled,
                PreviewDamageLedger.MAX_ENTRIES,PreviewDamageLedger.MAX_CREDITS); }
        if(!previewCheckpointEnabled) {
            { if(RogTrace.legacySlot(LogSelection.Channel.STORAGE)) log.info("[PDD-PREVIEW-SAVE] phase=START status=DISABLED persisted=false mutation=OFF"); }
            return;
        }
        try {
            // Keep saved diagnostic state isolated by calibration policy.
            previewPolicyId=calibrationIdentity();
            var path=previewPath(server);
            var bytes=PreviewCheckpoint.load(path);
            if(bytes==null) {
                { if(RogTrace.legacySlot(LogSelection.Channel.STORAGE)) log.info("[PDD-PREVIEW-SAVE] phase=START status=EMPTY file={} restored=0 mutation=OFF",path); }
                return;
            }
            preview.restore(bytes);
            { if(RogTrace.legacySlot(LogSelection.Channel.STORAGE)) log.info("[PDD-PREVIEW-SAVE] phase=START status=RESTORED file={} entries={} fixedTotal={} " +
                    "bytes={} authoritative=false replay=false mutation=OFF",
                    path,preview.size(),preview.fixedTotal(),bytes.length); }
        }catch(Exception invalid) {
            // An invalid checkpoint is never partially used or treated as legitimate game damage.
            preview.clear();
            previewCheckpointWritable=false;
            log.warn("[PDD-PREVIEW-SAVE] phase=START status=REJECTED reason={} " +
                    "authoritative=false mutation=OFF",invalid.getClass().getSimpleName(),invalid);
        }
    }
    public void savePreview(MinecraftServer server,Logger log) {
        if(!previewCheckpointEnabled)return;
        if(!previewCheckpointWritable) {
            log.warn("[PDD-PREVIEW-SAVE] phase=STOP status=SKIPPED_BAD_INPUT " +
                    "originalCheckpointPreserved=true mutation=OFF");
            return;
        }
        try {
            if(previewPolicyId==null || !previewPolicyId.equals(calibrationIdentity())) {
                log.warn("[PDD-PREVIEW-SAVE] phase=STOP status=SKIPPED_POLICY_CHANGED " +
                        "originalCheckpointPreserved=true mutation=OFF");
                return;
            }
            if(!preview.changedSinceRestore()) {
                { if(RogTrace.legacySlot(LogSelection.Channel.STORAGE)) log.info("[PDD-PREVIEW-SAVE] phase=STOP status=SKIPPED_UNCHANGED entries={} fixedTotal={} " +
                        "authoritative=false mutation=OFF",preview.size(),preview.fixedTotal()); }
                return;
            }
            byte[] bytes=preview.verifyRoundTrip();
            var path=previewPath(server);
            PreviewCheckpoint.save(path,bytes);
            { if(RogTrace.legacySlot(LogSelection.Channel.STORAGE)) log.info("[PDD-PREVIEW-SAVE] phase=STOP status=WRITTEN file={} entries={} fixedTotal={} " +
                    "bytes={} authoritative=false mutation=OFF",
                    path,preview.size(),preview.fixedTotal(),bytes.length); }
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

    private static void logObservation(Logger log,NativeEffectGroups.Group group,NativeEffectGroups.Batch batch) {
        { if(RogTrace.legacySlot(LogSelection.Channel.NATIVE)) log.info("[PDD-OBS] source=POST_EFFECT dimension={} tick={} bodyIds={}/{} rawRecords={} peakForce={} discarded={} invalid={} mutation=OFF",
                group.dimension(),group.tick(),group.runtimePair().lowId(),group.runtimePair().highId(),
                group.originalRecordCount(),group.forcePeak(),batch.discardedRecords(),batch.invalidRecords()); }
    }
    private static void logNativeTarget(Logger log,Stamp stamp,Pair pair,String methodA,String methodB,
                                        SableContactRegions.Result regions,boolean callbackA,boolean callbackB,boolean saturatedPoll) {
        { if(RogTrace.legacySlot(LogSelection.Channel.NATIVE)) log.info("[PDD-NATIVE-TARGET] dim={} tick={} ownerA={} ownerB={} methodA={} reasonA={} checkedA={} methodB={} reasonB={} checkedB={} regionCount={} rejectedPoints={} callbackHintA={} callbackHintB={} sourceCapSaturated={} worldMutation=OFF",
                stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey(),
                methodA,regions.status(),regions.checksA(),methodB,regions.status(),regions.checksB(),
                regions.regions().size(),regions.rejected(),callbackA,callbackB,saturatedPoll); }
    }

    /** Called only from ServerTickEvent.Post. Never mutate any world or native buffer. */
    public void tick(MinecraftServer server,List<RawObservation> callbacks,
                     List<NativeCollisionTelemetry.Contact> nativeEvents,
                     List<CollisionFrame.Snapshot> preInput,
                     Logger log) {
        Objects.requireNonNull(server);Objects.requireNonNull(callbacks);
        Objects.requireNonNull(nativeEvents);Objects.requireNonNull(preInput);Objects.requireNonNull(log);
        final long now=server.getTickCount();
        // Freeze the live dimension/server identity once at ServerTickEvent.Post.
        // Old constructor data keeps its legacy direct-path interpretation, but
        // no unknown capture stamp may contribute a cross-owner edge.
        final Map<String,ContactBatchStamp> consuming=new HashMap<>();
        for(var level:server.getAllLevels()){
            var stamp=ContactBatchStamp.capture(level);
            if(stamp.captureServerTick()==now)consuming.put(stamp.dimension(),stamp);
        }
        final List<CollisionFrame.Snapshot> pre=preInput.stream().filter(p->p!=null&&
                (!p.runtimeStamp().known()||p.runtimeStamp().statusAgainst(consuming.get(p.dimension()))
                        ==ContactBatchStamp.Status.CURRENT)).toList();
        int staleNative=0;
        final boolean fullTrace=DETAIL_ENABLED&&Boolean.parseBoolean(System.getProperty("pdd.diagnostics.fullTrace",
                "false"));
        if(plotCommit!=null)plotCommit.refreshLifecycle(now);
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
            if(c==null)continue;
            if(c.runtimeStamp().known()){
                var live=consuming.getOrDefault(c.dimension(),ContactBatchStamp.UNKNOWN);
                var status=c.runtimeStamp().statusAgainst(live);
                if(status!=ContactBatchStamp.Status.CURRENT){
                    if(RogTrace.sample(Stage.R01,"STALE_NATIVE"))RogTrace.detail(Stage.R01,"STALE_NATIVE","dimension={} sourceGameTick={} liveGameTick={} sourceServerTick={} processingServerTick={} status={}",c.dimension(),c.serverTick(),live.sourceGameTick(),c.captureServerTick(),now,status);
                    if(staleNative++==0)log.warn("[PDD-CROSS-OWNER-STAMP] dimension={} sourceGameTick={} liveGameTick={} sourceServerTick={} processingServerTick={} reason={}",
                            c.dimension(),c.serverTick(),live.sourceGameTick(),c.captureServerTick(),now,status);
                    continue;
                }
            }
            if(c.dimension()!=null&&c.serverTick()>=0)
                raw.computeIfAbsent(new Stamp(c.dimension(),c.serverTick()),k->new ArrayList<>()).add(c);
        }
        if(staleNative>0||pre.size()!=preInput.size()){if(RogTrace.sample(Stage.R01,"NONCURRENT_CAPTURE"))RogTrace.detail(Stage.R01,"NONCURRENT_CAPTURE","nativeRejected={} preRejected={}",staleNative,preInput.size()-pre.size());}
        if(staleNative>0||pre.size()!=preInput.size())
            log.warn("[PDD-CROSS-OWNER-STAMP] processingServerTick={} rejectedNative={} rejectedPre={} reason=NONCURRENT_CAPTURE",
                    now,staleNative,preInput.size()-pre.size());
        final Map<String,Integer> dimensionBatches=new HashMap<>();
        raw.keySet().forEach(key->dimensionBatches.merge(key.dimension,1,Integer::sum));
        final Map<SceneKey,ObservedTargets> targetEvidence=callbackTargets(server,callbacks);
        final Map<Stamp,OwnerSnapshot> ownerIndex=ownerSnapshots(pre);
        final var massEpochs=new NativeContactMassEpoch(pre);
        final Map<String,Map<String,LivePlot>> worldBounds=readServerBounds(server);
        invalidateUnloadedOwners(worldBounds,now);
        final Map<String,Box> knownWorldBlocks=new HashMap<>();
        // Pre-solver snapshots may be from multiple substeps. A runtime pair is
        // accepted only when every matching snapshot maps to the same stable pair.
        final int[] logCounts={0,0};
        final int[] fTraceLines={0};
        final var contactBudgetTop=new ContactBudgetTop();
        // Clearance is safety-critical. R4 is diagnostic-only: separate
        // bounded budgets prevent R4 from starving positive separation proof.
        int[] remainingClearanceChecks={RealFixBoundaryCandidates.MAX_PER_TICK};
        int[] remainingLocalGapChecks={256};
        int[] remainingAtomicConfirmChecks={WorldClearanceVerifier.MAX_CONFIRM_PER_TICK};
        int[] remainingR4Checks={Math.min(128,RealFixBoundaryCandidates.MAX_PER_TICK)};
        final Set<Pair> contacted=new HashSet<>();
        for(var stampEntry:raw.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Stamp,List<NativeCollisionTelemetry.Contact>> x)->x.getKey().dimension)
                        .thenComparingLong(x->x.getKey().tick)).toList()) {
            Stamp stamp=stampEntry.getKey();
            List<NativeCollisionTelemetry.Contact> values=stampEntry.getValue();
            final ContactBatchStamp capture=values.getFirst().runtimeStamp();
            final ContactBatchStamp consume=consuming.getOrDefault(stamp.dimension,ContactBatchStamp.UNKNOWN);
            final var nativeBatchStatus=dimensionBatches.getOrDefault(stamp.dimension,0)!=1
                    ?ContactBatchStamp.Status.NATIVE_CONTRADICTORY:ContactBatchStamp.nativeStatus(capture,
                    values.stream().map(NativeCollisionTelemetry.Contact::runtimeStamp).toList());
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
            if(RogTrace.sample(Stage.R01,"BATCH_VALIDATION"))RogTrace.detail(Stage.R01,"BATCH_VALIDATION","dimension={} tick={} invalid={} discarded={} malformedArray={}",stamp.dimension,stamp.tick,batch.invalidRecords(),batch.discardedRecords(),batch.malformedArray());
            RogTrace.context(stamp.dimension,stamp.tick,null,null);
            if(RogTrace.sample(Stage.R01,"NATIVE_BATCH"))RogTrace.detail(Stage.R01,"NATIVE_BATCH","records={} groups={} stampStatus={} capture={} consume={} complete={}",values.size(),batch.groups().size(),nativeBatchStatus,capture,consume,complete);
            var contactsByPair=new HashMap<NativeEffectGroups.RuntimePair,List<NativeCollisionTelemetry.Contact>>();
            for(var c:values) {
                if(c.idA()< -1 || c.idB()< -1 || c.idA()==c.idB())continue;
                contactsByPair.computeIfAbsent(NativeEffectGroups.RuntimePair.of(c.idA(),c.idB()),
                        ignored->new ArrayList<>()).add(c);
            }
            // First decide ALL direct authorizations for this native stamp.
            // Build the secondary graph only from final, non-authorized
            // duplicate episodes. A failed seal cannot retroactively make
            // an independently authorized pair a secondary contact.
            var contactGraph=new VerifiedContactGraph(server,stamp.dimension,stamp.tick,
                    owner->{var binding=lifecycleBinding(stamp.dimension,owner);
                        return binding!=null&&(!binding.active()||binding.orphaned())?-1:
                                currentGeneration(stamp.dimension,owner);},contactHistory,capture,consume,nativeBatchStatus);
            var resolvedOwners=new HashMap<NativeEffectGroups.RuntimePair,RuntimeOwners>();
            var ownerPairCounts=new HashMap<SceneKey,Integer>();
            var secondary=new java.util.LinkedHashMap<Pair,List<NativeCollisionTelemetry.Contact>>();
            var directAuthorized=new HashSet<Pair>();
            var deferredPlans=new ArrayList<Runnable>();
            for(var group:batch.groups()) {
                var records=contactsByPair.getOrDefault(group.runtimePair(),List.of());
                RogTrace.context(stamp.dimension,stamp.tick,group.runtimePair(),null);
                var owners=resolveOwners(ownerIndex,stamp,group,records,now,worldBounds.getOrDefault(stamp.dimension(),Map.of()));
                if(owners==null)continue;
                resolvedOwners.put(group.runtimePair(),owners);
                Pair pair=owners.pair();
                ownerPairCounts.merge(new SceneKey(stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey()),1,Integer::sum);
            }
            for(var group:batch.groups()) {
                observed++;
                RogTrace.context(stamp.dimension,stamp.tick,group.runtimePair(),null);
                if(fullTrace && logCounts[0]++<MAX_LOG_PER_TICK)logObservation(log,group,batch);
                List<NativeCollisionTelemetry.Contact> records=contactsByPair.getOrDefault(group.runtimePair(),List.of());
                RuntimeOwners owners=resolvedOwners.get(group.runtimePair());
                if(owners==null) {
                    missingOwner++;zero++;
                    if(!fullTrace && logCounts[0]++<MAX_LOG_PER_TICK)logObservation(log,group,batch);
                    if(logCounts[0]++<MAX_LOG_PER_TICK){ if(RogTrace.legacySlot(LogSelection.Channel.NATIVE)) log.info("[PDD-OBS] source=POST_EFFECT dimension={} tick={} ids={}/{} peak={} result=OWNER_UNVERIFIED mutation=OFF",
                            stamp.dimension,stamp.tick,group.runtimePair().lowId(),group.runtimePair().highId(),group.forcePeak()); }
                    continue;
                }
                Pair pair=owners.pair();
                RogTrace.context(stamp.dimension,stamp.tick,group.runtimePair(),pair);
                // Observed native contact invalidates any incomplete clearance scan.
                worldClearance.contact(pair);
                SceneKey scene=new SceneKey(stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey());
                if(ownerPairCounts.getOrDefault(scene,0)!=1) {
                    contacted.add(pair);
                    contactHistory.invalidatePair(pair);
                    var oldTrack=tracked.get(pair);if(oldTrack!=null)oldTrack.regionHistory.incomplete(now);
                    zero++;
                    if(RogTrace.sample(Stage.R02,"AMBIGUOUS_STABLE_PAIR"))RogTrace.detail(Stage.R02,"AMBIGUOUS_STABLE_PAIR","pairCount={} records={}",ownerPairCounts.getOrDefault(scene,0),records.size());
                    if(logCounts[0]++<MAX_LOG_PER_TICK){ if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.warn("[PDD-EPISODE] dimension={} tick={} reason=AMBIGUOUS_STABLE_OWNER_PAIR Q=0",stamp.dimension,stamp.tick); }
                    continue;
                }
                ObservedTargets candidate=targetEvidence.get(scene);
                // §21.4: inspect each native contact point once, in its own
                // Sable consumer pose; aggregate only source-proven block keys.
                // The owner/pair/tick has a separate bounded R2 shape-check budget (32768 unique cells/side); verified damage targets are capped at 16384/side.
                var beforeAudit=credit.evidence(pair);
                Tracked priorTracking=tracked.get(pair);
                Tracked trackedPair=priorTracking;
                if(trackedPair==null && tracked.size()<MAX_TRACKED){trackedPair=new Tracked();tracked.put(pair,trackedPair);}
                final Tracked t=trackedPair;
                if(t==null){contacted.add(pair);zero++;continue;}
                boolean fullPairArmed=beforeAudit.phase()==Phase.ARMED&&t.lastSeparatedTick>t.lastSeen;
                var massSafe=massEpochs.validate(stamp.dimension,stamp.tick,records);
                boolean sourceComplete=complete&&batch.invalidRecords()==0&&batch.discardedRecords()==0&&!batch.malformedArray()
                        &&nativeBatchStatus==ContactBatchStamp.Status.CURRENT&&ContactBatchStamp.preStatus(capture,pre)==ContactBatchStamp.Status.CURRENT
                        &&massSafe.maskedFrames()==0;
                long r2Start=LogSelection.enabled(LogSelection.Channel.PERF)?System.nanoTime():0;
                final DcrtContactBatch selection;
                try {
                    selection=DcrtContactBatch.select(server,pair,massSafe.contacts(),pre,stamp.tick,now,sourceComplete,fullPairArmed,credit.settings(),t.regionHistory,
                            track->localRegionGap(server,pair,track,worldBounds,remainingLocalGapChecks));
                }catch(RuntimeException invalid){
                    t.regionHistory.incomplete(now);t.lastSeen=now;t.lastProcessed=now;
                    contacted.add(pair);contactHistory.invalidatePair(pair);zero++;
                    if(logCounts[0]++<MAX_LOG_PER_TICK)log.warn("[PDD-DCRT] region audit failed pair={} reason={} Q=0",pair,invalid.getClass().getSimpleName());
                    continue;
                }
                var directRegions=selection.direct();
                var regions=directRegions;
                var preAuthNormal=selection.normal();
                var targetDirections=selection.directions();
                boolean auditEligible=selection.authorized();
                double currentPeak=auditEligible?selection.peak():group.forcePeak();
                long r2Nanos=r2Start==0?0:System.nanoTime()-r2Start;
                if(auditEligible){
                    r2Audits++;r2ShapeChecks+=regions.checksA()+regions.checksB();
                    { if(RogTrace.legacySlot(LogSelection.Channel.PERF)) log.info("[PDD-R2-PERF] pair={}/{} phase=AUTHORIZED_AUDIT nanos={} records={} " +
                                    "checkedA={} checkedB={} regions={} status={} mutation=OFF",
                            pair.a().stableKey(),pair.b().stableKey(),r2Nanos,records.size(),
                            regions.checksA(),regions.checksB(),regions.regions().size(),regions.status()); }
                }
                else r2SkippedDuplicate++;
                boolean saturatedPoll=records.stream().anyMatch(NativeCollisionTelemetry.Contact::capSaturated);
                if(LogSelection.enabled(LogSelection.Channel.NATIVE)){
                    if(saturatedPoll&&RogTrace.periodic(Stage.R01,"NATIVE_CAP_AFFECTED_PAIR"))RogTrace.detail(Stage.R01,"NATIVE_CAP_AFFECTED_PAIR","pair={} records={} totalSaturatedPolls={} cap=100 omittedCount=UNKNOWN",pair,records.size(),NativeCollisionTelemetry.nativeCapSaturatedPolls());
                    if(saturatedPoll!=t.nativeCapActive){t.nativeCapActive=saturatedPoll;
                        if(RogTrace.eventSlot(Stage.R01,"NATIVE_CAP_TRANSITION"))RogTrace.detail(Stage.R01,"NATIVE_CAP_TRANSITION","pair={} saturatedObserved={} records={} totalSaturatedPolls={} authority=NONE",pair,saturatedPoll,records.size(),NativeCollisionTelemetry.nativeCapSaturatedPolls());
                    }
                }
                if(RogTrace.sample(Stage.R04,regions.status().name()))RogTrace.detail(Stage.R04,regions.status().name(),"auditEligible={} nativeCap={} checksA={} checksB={} rejected={} regions={} trace={}",auditEligible,saturatedPoll,regions.checksA(),regions.checksB(),regions.rejected(),regions.regions().size(),regions.trace());
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
                boolean nativeApproach=selection.authorized();
                boolean regionMoved=nativeApproach&&!fullPairArmed;
                boolean regionalReimpact=regionMoved&&beforeAudit.episode()>0;
                boolean approach=nativeApproach;
                long separationEvidenceTick=t.lastSeparatedTick;
                double rise=Double.isFinite(t.lastPeak)?group.forcePeak()-t.lastPeak:Double.NaN;
                t.lastPeak=currentPeak;
                // A sustained resting force cannot reissue Q. Two actual low-F
                // native frames and a later independent fast approach are needed.
                if(Double.isFinite(t.lastCreditedPeak) && currentPeak>0
                        && currentPeak<t.lastCreditedPeak/3.0)
                    t.quietForceFrames=Math.min(8,t.quietForceFrames+1);
                else if(!regionalReimpact)t.quietForceFrames=0;
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
                String regionSignature=DETAIL_ENABLED?regions.status()+":"+trace.failure():"";
                if(DETAIL_ENABLED && VERF_ENABLED && regions.status()!=SableContactRegions.Status.PROVED
                        && ( !regionSignature.equals(t.lastVerfRegion)
                        || now-t.lastVerfRegionTick>=VERF_SAMPLE_TICKS)) {
                    { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-VERF-REGION] nativeTick={} serverTick={} pair={}/{} bodyIds={}/{} " +
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
                            sampleFrame(records,true),sampleFrame(records,false)); }
                    t.lastVerfRegion=regionSignature;t.lastVerfRegionTick=now;
                }
                if(VERF_ENABLED && massSafe!=null && massSafe.maskedFrames()>0 &&
                        (t.lastPlotRogEpochTick<0 || now-t.lastPlotRogEpochTick>=VERF_SAMPLE_TICKS)) {
                    { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-PLOTROG-EPOCH] nativeTick={} serverTick={} pair={}/{} " +
                                    "maskedOwnerFrames={} sourceRecords={} policy=UNSTABLE_COM_SIDE_Q_ZERO mutation=OFF",
                            stamp.tick,now,pair.a().stableKey(),pair.b().stableKey(),
                            massSafe.maskedFrames(),records.size()); }
                    t.lastPlotRogEpochTick=now;
                }
                // Sample witness separation independent of whether R2 approved.
                // Effect-phase projected points may span different substeps;
                // never turn this diagnostic into a hard contact gate.
                if(LogSelection.enabled(LogSelection.Channel.CONTACT) && DETAIL_ENABLED && VERF_ENABLED && auditEligible && (t.lastPlotRogWitnessTick<0 || now-t.lastPlotRogWitnessTick>=VERF_SAMPLE_TICKS)) {
                    for(int i=0;i<records.size();i++) {
                        var contact=records.get(i);
                        var wd=SableConsumerTargets.witnessDiagnostic(contact);
                        if("WITNESS_SEPARATED_GT_0.25".equals(wd.residual().reason())) {
                            { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-PLOTROG-WITNESS] nativeTick={} serverTick={} bodyIds={}/{} " +
                                    "sourceIndex={} sourceRecords={} ownerA={} ownerB={} " +
                                    "worldA={} worldB={} delta={} distance={} alongNormal={} " +
                                    "transverse={} normalAlignment={} diagnosis={} r2Status={} " +
                                    "policy=INDEPENDENT_OWNER_LOCAL_R2_NO_DISTANCE_GATE mutation=OFF",
                                    stamp.tick,now,contact.idA(),contact.idB(),i,records.size(),
                                    pair.a().stableKey(),pair.b().stableKey(),
                                    wd.residual().a(),wd.residual().b(),wd.residual().delta(),
                                    wd.residual().distance(),wd.alongNormal(),wd.transverse(),
                                    wd.normalAlignment(),wd.reason(),regions.status()); }
                            t.lastPlotRogWitnessTick=now;
                            break;
                        }
                    }
                }
                boolean incomplete=!complete || batch.invalidRecords()>0 || batch.discardedRecords()>0 || batch.malformedArray();
                if(LogSelection.enabled(LogSelection.Channel.NATIVE)&&(fullTrace||incomplete&&RogTrace.periodic(Stage.R01,"INCOMPLETE_TARGET")) && logCounts[0]++<MAX_LOG_PER_TICK)
                    logNativeTarget(log,stamp,pair,methodA,methodB,regions,r1a!=null,r1b!=null,saturatedPoll);
                // R4 shadow audit. This only describes plausible loaded world
                // blocks; WITHOUT in-game ground truth it can never mint credit.
                if(LogSelection.enabled(LogSelection.Channel.CONTACT) && DETAIL_ENABLED && auditEligible && pair.a().stableKey().startsWith("WORLD:") && a==null) {
                    int before=remainingR4Checks[0];
                    R4Probe probe=probeWorldR4(server,stamp.dimension,sweptB,records,remainingR4Checks);
                    if(logCounts[0]++<MAX_LOG_PER_TICK){ if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-TARGET] source=R4_SWEPT_PROVISIONAL dimension={} tick={} worldCandidate={} credited=false",stamp.dimension,stamp.tick,probe.status()); }
                    if(!probe.status().startsWith("PROVISIONAL_ONLY"))
                        logVerfR4(log,t,now,stamp.tick,pair,probe,records.size(),before,remainingR4Checks[0],sweptB);
                }
                if(LogSelection.enabled(LogSelection.Channel.CONTACT) && DETAIL_ENABLED && auditEligible && pair.b().stableKey().startsWith("WORLD:") && b==null) {
                    int before=remainingR4Checks[0];
                    R4Probe probe=probeWorldR4(server,stamp.dimension,sweptA,records,remainingR4Checks);
                    if(logCounts[0]++<MAX_LOG_PER_TICK){ if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-TARGET] source=R4_SWEPT_PROVISIONAL dimension={} tick={} worldCandidate={} credited=false",stamp.dimension,stamp.tick,probe.status()); }
                    if(!probe.status().startsWith("PROVISIONAL_ONLY"))
                        logVerfR4(log,t,now,stamp.tick,pair,probe,records.size(),before,remainingR4Checks[0],sweptA);
                }
                if(incomplete) {
                    contactHistory.invalidatePair(pair);
                    zero++;
                    if(!fullTrace && RogTrace.periodic(Stage.R01,"INCOMPLETE_INPUT")&&logCounts[0]++<MAX_LOG_PER_TICK)logObservation(log,group,batch);
                    if(RogTrace.periodic(Stage.R05,"INCOMPLETE_INPUT")&&logCounts[0]++<MAX_LOG_PER_TICK){ if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-EPISODE] dimension={} tick={} ownerA={} ownerB={} reason=INCOMPLETE_INPUT Q=0 worldMutation=OFF",
                            stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey()); }
                    continue;
                }
                Decision authorization=null;
                try {
                    // AABB overlap is UNKNOWN, never 'contact proved'. New Q
                    // requires independently observed prior clearance and a source-
                    // mapped, verified owner-local block. Force is a GAME proxy.
                    var frame=selection.authorized()?new Frame(pair,now,selection.forces(),false,true,a,b,selection.peak()):new Frame(pair,now,group.samples(),false,false,null,null,group.forcePeak());
                    var result=selection.authorized()?credit.stepRegional(frame):credit.step(frame);
                    authorization=result;
                    RogTrace.episode(result.episodeGeneration());
                    if(RogTrace.sample(Stage.R05,result.reason().name()))RogTrace.detail(Stage.R05,result.reason().name(),"decision={} previous={} gapTick={} armedFromGap={} previousSeparated={} approach={} aabbClosing={} regionalReimpact={} rawF={} targetA={} targetB={}",result,phaseBefore,separationEvidenceTick,armedFromGap,previousSeparated,approach,aabbClosing,regionalReimpact,group.forcePeak(),a,b);
                    if(result.credited()) {
                        selection.reserve(t.regionHistory,now);
                        directAuthorized.add(pair);
                        contactHistory.invalidatePair(pair);
                    } else if(selection.secondarySafe()&&sourceComplete&&eligibleSecondary(result,gapBefore,priorTracking!=null,
                            previousSeparated||armedFromGap,approach)) {
                        secondary.put(pair,records);
                    }
                    // Keep actual audit evidence, every approved credit, all state/rejection changes
                    // and a periodic witness. Suppress only repetitive diagnostic groups, not credit work.
                    boolean diagnosticGroup=LogSelection.enabled(LogSelection.Channel.CONTACT)||LogSelection.enabled(LogSelection.Channel.NATIVE);
                    boolean showGroup=diagnosticGroup&&t.rejectionTrace.sample(false,
                            result.credited() || phaseBefore!=result.phase(),
                            now,result.reason(),regions.status(),VERF_SAMPLE_TICKS);
                    if(diagnosticGroup&&!showGroup)repeatedDiagnosticGroupsSuppressed++;
                    if(!fullTrace && showGroup) {
                        if(logCounts[0]++<MAX_LOG_PER_TICK)logObservation(log,group,batch);
                        if(LogSelection.enabled(LogSelection.Channel.NATIVE)&&DETAIL_ENABLED&&logCounts[0]++<MAX_LOG_PER_TICK)
                            logNativeTarget(log,stamp,pair,methodA,methodB,regions,r1a!=null,r1b!=null,saturatedPoll);
                    }
                    // A large force is diagnostic evidence, not permission to
                    // bypass the episode gate. Sample the actual rejection
                    // cause even when no F/Q credit was issued.
                    if(VERF_ENABLED && group.forcePeak()>=1_000_000
                            && (result.credited() || t.lastHighFLogTick<0
                                || now-t.lastHighFLogTick>=VERF_SAMPLE_TICKS)) {
                        { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-HIGH-F] pair={}/{} nativeTick={} rawF={} cappedF={} " +
                                        "gameMaxF={} phaseBefore={} verifiedApproach={} fullPairArmed={} " +
                                        "hasTarget={} regionStatus={} approval={} issuedQ={} " +
                                        "nativeApproach={} regionalReimpact={} quietForceFrames={} " +
                                        "source=POST_EFFECT_GAME_POLICY mutation=OFF",
                                pair.a().stableKey(),pair.b().stableKey(),stamp.tick,
                                group.forcePeak(),result.peakForce(),GameImpactCredit.MAX_GAME_F,
                                gapBefore.phase(),approach,fullPairArmed,
                                a!=null||b!=null,regions.status(),result.reason(),result.issuedQ(),
                                nativeApproach,regionalReimpact,t.quietForceFrames); }
                        t.lastHighFLogTick=now;
                        if(!result.credited()){ if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-IMPACT-DECISION] pair={}/{} tick={} " +
                                        "source=SABLE_POST_EFFECT rawF={} issuedQ=0 gate={} " +
                                        "nativeApproach={} regionMoved={} creditStatus=REJECTED " +
                                        "phase={} regionStatus={} mutation=OFF",
                                pair.a().stableKey(),pair.b().stableKey(),stamp.tick,currentPeak,
                                result.reason(),nativeApproach,regionMoved,result.phase(),regions.status()); }
                    }
                    if(result.credited() && selection.peak()>GameImpactCredit.MAX_GAME_F
                            && logCounts[0]++<MAX_LOG_PER_TICK)
                        { if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info("[PDD-F-CAP] pair={}/{} rawPeak={} eligiblePeak={} cappedF={} issuedQ={} " +
                                        "action=SATURATE_NOT_REJECT source=NATIVE_EFFECT_GAME_PROXY mutation=OFF",
                                pair.a().stableKey(),pair.b().stableKey(),group.forcePeak(),selection.peak(),
                                result.peakForce(),result.issuedQ()); }
                    var gapAfter=credit.evidence(pair);
                    String fsmSignature=DETAIL_ENABLED?result.reason()+":"+gapBefore.phase()+":"+gapAfter.phase():"";
                    if(DETAIL_ENABLED && VERF_ENABLED && (result.credited() || gapBefore.phase()!=gapAfter.phase()
                            || !fsmSignature.equals(t.lastVerfFsm) || now-t.lastVerfFsmTick>=VERF_SAMPLE_TICKS)) {
                        { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-VERF-FSM] nativeTick={} serverTick={} bodyIds={}/{} pair={}/{} " +
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
                                result.reason(),gapBefore.pendingApproach()&&!gapAfter.pendingApproach()); }
                        t.lastVerfFsm=fsmSignature;t.lastVerfFsmTick=now;
                    }
                    // Freeze optional geometry on the same server-thread snapshot,
                    // before deferred graph/propagation offers can mutate any owner.
                    var reservedMaterialSnapshot=MaterialRegistry.snapshot();
                    long reservedMaterialGeneration=reservedMaterialSnapshot.generation();
                    if(result.credited()){ if(RogTrace.eventSlot(Stage.R06,"CONTACT_CREDIT_RESERVED"))RogTrace.detail(Stage.R06,"CONTACT_CREDIT_RESERVED","episode={} eventF={} sideF={} state=COLLECTING mutation=OFF",result.episodeGeneration(),result.peakForce(),result.peakForce()/2); }
                    var damageRegions=result.credited()?selection.expand(pair):regions;
                    if(result.credited()&&!damageRegions.verified())throw new IllegalStateException("DCRT expansion incomplete or over budget");
                    if(result.credited())selection.inputLog(result.episodeGeneration(),result.peakForce());
                    var positionalMaterials=result.credited()?freezePositionalMaterials(server,stamp.dimension,reservedMaterialSnapshot,damageRegions.regions()):Map.<Target,Optional<CopycatMaterial.Resolved>>of();
                    if(result.credited()){ if(RogTrace.eventSlot(Stage.R06,"CONTACT_CREDIT_SEALED_INPUT"))RogTrace.detail(Stage.R06,"CONTACT_CREDIT_SEALED_INPUT","episode={} regions={} patchSeeds={} state=SEALED mutation=OFF",result.episodeGeneration(),damageRegions.regions().size(),damageRegions.patchSeeds().size()); }
                    if(result.credited()&&damageRegions!=regions)
                        r2ShapeChecks+=Math.max(0,damageRegions.checksA()+damageRegions.checksB()-regions.checksA()-regions.checksB());
                    // Freeze decisions in batch order; all plans/offers run
                    // only after the contact graph has been finalized below.
                    Runnable deferred=()-> {
                    RogTrace.context(stamp.dimension,stamp.tick,group.runtimePair(),pair);
                    RogTrace.episode(result.episodeGeneration());
                    try {
                    if(result.credited()&&(currentGeneration(stamp.dimension,pair.a().stableKey())!=pair.a().generation()
                            ||currentGeneration(stamp.dimension,pair.b().stableKey())!=pair.b().generation()))
                        throw new IllegalStateException("stale owner generation before deferred plan");
                    var materialSnapshot=MaterialRegistry.snapshot();
                    if(result.credited()&&materialSnapshot.generation()!=reservedMaterialGeneration)
                        throw new IllegalStateException("material generation changed before contact seal");
                    var targetMaterials=new TargetMaterials(server,stamp.dimension,materialSnapshot);
                    for(var item:positionalMaterials.entrySet())if(!item.getValue().equals(Optional.ofNullable(targetMaterials.resolve(item.getKey()))))
                        throw new IllegalStateException("positional material revision changed before seal");
                    targetMaterials.setDirections(targetDirections);
                    // Sable 2.0.6 exports opposing local manifold normals per
                    // native record. Validate each pair, then average all
                    // witnesses in the SAME owner-local effect frame.
                    var nativeNormal=result.credited()?preAuthNormal
                            :NativeEffectDirection.unknown("NOT_CREDITED");
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
                                    { if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info("[PDD-F-EDGE] credit={}/{} generation={} owner={} " +
                                            "layer={} parent={} child={} parentReceivedF={} parentLocalF={} " +
                                            "parentOutgoingF={} directionWeight={} sentF={} " +
                                            "normalFrame=OWNER_LOCAL normal={} normalEvidence={} mutation=OFF",
                                            pair.a().stableKey(),pair.b().stableKey(),result.episodeGeneration(),
                                            edge.parent().ownerKey(),edge.layer(),edge.parent().localBlock(),
                                            edge.child().localBlock(),edge.parentReceivedF(),edge.parentLocalF(),
                                            edge.parentOutgoingF(),edge.directionWeight(),edge.sentF(),
                                            nativeNormal.forOwner(edge.parent().ownerKey()).inward(),
                                            nativeNormal.forOwner(edge.parent().ownerKey()).evidence()); }
                                } else if(traceEvent instanceof ForcePropagation.Receipt receipt) {
                                    { if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info("[PDD-F-MERGE] credit={}/{} generation={} owner={} " +
                                            "layer={} child={} parentCount={} totalReceivedF={} " +
                                            "processedOnce={} mutation=OFF",
                                            pair.a().stableKey(),pair.b().stableKey(),result.episodeGeneration(),
                                            receipt.child().ownerKey(),receipt.layer(),receipt.child().localBlock(),
                                            receipt.parentCount(),receipt.totalReceivedF(),receipt.processedOnce()); }
                                } else if(traceEvent instanceof ForcePropagation.Node node) {
                                    { if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info("[PDD-F-NODE] credit={}/{} generation={} owner={} layer={} " +
                                                    "local={} receivedF={} localF={} outgoingSentF={} " +
                                                    "dampedF={} recoveredTerminalF={} thresholdDiscardF={} " +
                                                    "candidateQ={} finalQ={} candidates={} paidChildren={} " +
                                                    "decision={} mutation=OFF",
                                            pair.a().stableKey(),pair.b().stableKey(),result.episodeGeneration(),
                                            node.target().ownerKey(),node.layer(),node.target().localBlock(),
                                            node.receivedF(),node.localF(),node.forwardedF(),node.dampedF(),
                                            node.terminalRecoveredF(),node.discardedF(),node.rawQ(),
                                            node.finalQ(),node.eligibleChildren(),node.paidChildren(),node.decision()); }
                                } else if(traceEvent instanceof ForcePropagation.Cutoff cutoff) {
                                    { if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info("[PDD-F-CUTOFF] credit={}/{} generation={} owner={} " +
                                                    "layer={} parent={} child={} F={} reason={} mutation=OFF",
                                            pair.a().stableKey(),pair.b().stableKey(),result.episodeGeneration(),
                                            cutoff.from().ownerKey(),cutoff.layer(),cutoff.from().localBlock(),
                                            cutoff.to()==null?"NONE":cutoff.to().localBlock(),
                                            cutoff.rejectedF(),cutoff.reason()); }
                                }
                            }:null;
                    long propagationStart=LogSelection.enabled(LogSelection.Channel.PERF)?System.nanoTime():0;
                    var propagationReader=result.credited()?OwnerResolver.propagationSnapshot(server,stamp.dimension):null;
                    int reserveA=result.credited()?damageRegions.reservedA():0,reserveB=result.credited()?damageRegions.reservedB():0;
                    ContactCreditLedger.Grant grant=result.credited()?regionLedger.issueContactSeeds(
                            pair,result,ContactCreditLedger.Source.SABLE_POST_EFFECT,damageRegions.regions(),reserveA,reserveB,
                            propagationReader,
                            target->OwnerResolver.matchesSnapshot(propagationReader,target)?targetMaterials.material(target):null,credit.settings(),damageSettings,
                            nativeNormal.owners(),targetDirections.hasProvenSeed()||fTrace!=null
                                    ?event->{targetDirections.trace(event);if(fTrace!=null)fTrace.accept(event);}:null,
                            contactGraph.planContext(),selection.seedDirections()):null;
                    long propagationNanos=propagationStart==0?0:System.nanoTime()-propagationStart;
                    if(grant!=null){if(RogTrace.sample(Stage.R06,grant.status().name()))RogTrace.detail(Stage.R06,grant.status().name(),"credit={} issuedQ={} inputF={} dampedF={} unresolvedF={} unusedQ={} targetsA={} targetsB={} failure={} elapsedNs={}",grant.id(),grant.issuedQ(),grant.inputF(),grant.dampedF(),grant.unresolvedF(),grant.unused(),grant.a().size(),grant.b().size(),regionLedger.lastPropagationFailure(),propagationNanos);}
                    boolean granted=grant!=null && grant.credited();
                    if(granted)selection.finish(true);
                    if(granted&&RogTrace.eventSlot(Stage.R06,"DCRT_CREDIT_SEAL"))RogTrace.detail(Stage.R06,"DCRT_CREDIT_SEAL","credit={} state=GRANTED eligiblePeak={} inputF={} issuedQ={} sideA={} sideB={} seedsA={} seedsB={} unusedQ={}",grant.id(),result.peakForce(),grant.inputF(),grant.issuedQ(),grant.allocatedA(),grant.allocatedB(),grant.a().size(),grant.b().size(),grant.unused());
                    if(result.credited() && !granted) {
                        var pendingId=new ContactCreditLedger.CreditId(pair,result.episodeGeneration());
                        boolean sealed=regionLedger.lookup(pendingId)!=null;
                        boolean aborted=!sealed&&credit.abortUnissued(pair,result);
                        if(sealed)selection.finish(true);else if(aborted)selection.finish(false);else selection.quarantine();
                        String failure=(grant==null?"NULL":grant.status().name())+":"+regionLedger.lastPropagationFailure()+":"+aborted;
                        boolean showFailure=!failure.equals(t.lastGrantFailure)||t.lastGrantFailureTick<0||now-t.lastGrantFailureTick>=VERF_SAMPLE_TICKS;
                        if(showFailure){t.lastGrantFailure=failure;t.lastGrantFailureTick=now;
                        log.warn("[PDD-Q-ISSUE] pair={}/{} tick={} rawF={} cappedF={} " +
                                        "requestedQ={} ledgerStatus={} failure={} rolledBack={} " +
                                        "reason=UNSEALED_NO_WORLD_COMMIT mutation=OFF",
                                pair.a().stableKey(),pair.b().stableKey(),stamp.tick,
                                currentPeak,result.peakForce(),result.issuedQ(),
                                grant==null?"NULL":grant.status(),regionLedger.lastPropagationFailure(),aborted);}
                    }
                    if(granted) {
                        t.lastGrantFailure="";
                        { if(RogTrace.eventSlot(Stage.R06,"CONTACT_SEED_SEALED"))RogTrace.detail(Stage.R06,"CONTACT_SEED_SEALED","credit={} directRegions={} finalRegions={} reservedA={} reservedB={} ownerLocalPatchSeeds={} state=ISSUED",grant.id(),directRegions.regions().size(),damageRegions.regions().size(),reserveA,reserveB,damageRegions.patchSeeds().size()); }
                        if(RogTrace.sample(Stage.R06,"SEALED_CREDIT"))RogTrace.detail(Stage.R06,"SEALED_CREDIT","credit={} propagationA={} propagationB={} owners={} rawF={} cappedF={} Q={}",grant.id(),grant.propagationA()==null?"NONE":grant.propagationA().endReason(),grant.propagationB()==null?"NONE":grant.propagationB().endReason(),nativeNormal.owners(),currentPeak,result.peakForce(),grant.issuedQ());
                        targetDirections.bind(grant,credit.settings(),targetMaterials::material);
                        if(DETAIL_ENABLED){ if(RogTrace.legacySlot(LogSelection.Channel.DEFORM)) log.info("[PDD-DEFORM-EVIDENCE] credit={} {}",grant.id(),targetDirections.summary()); }
                        t.lastCreditedPeak=currentPeak;
                        t.quietForceFrames=0;
                        t.lastCreditedTick=now;
                        { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-IMPACT-DECISION] pair={}/{} tick={} credit={} " +
                                        "source={} rawF={} issuedQ={} gate={} nativeApproach={} " +
                                        "regionalReimpact={} movedSurface={} verifiedCells={} " +
                                        "spreadStatus={} reason={} mutation=WORLD_STATIC_ONLY",
                                pair.a().stableKey(),pair.b().stableKey(),stamp.tick,grant.id(),
                                "SABLE_POST_EFFECT",currentPeak,result.issuedQ(),result.reason(),
                                nativeApproach,regionalReimpact,regionMoved,regions.regions().size(),
                                grant.status(),"APPROVED"); }
                        { if(RogTrace.legacySlot(LogSelection.Channel.PERF)) log.info("[PDD-F-PERF] credit={} planNanos={} ownerCount=2 " +
                                        "maxNodesPerSide={} eventF={} eventQCap={} rawPeak={} fSaturated={} " +
                                        "normalMode={} traceEnabled={} includesTraceOutput={} mutation=OFF",
                                grant.id(),propagationNanos,ForcePropagation.MAX_NODES_PER_SIDE,
                                result.peakForce(),result.issuedQ(),group.forcePeak(),
                                group.forcePeak()>GameImpactCredit.MAX_GAME_F,nativeNormal.reason(),
                                F_TRACE_ENABLED,F_TRACE_ENABLED); }
                        { if(RogTrace.legacySlot(LogSelection.Channel.PERF)) log.info("[PDD-NORMAL-PERF] credit={} regionalAuditNanos={} availableRecords={} " +
                                        "capSampled={} verified={} mutation=OFF",
                                grant.id(),r2Nanos,massSafe==null?0:massSafe.contacts().size(),
                                saturatedPoll,nativeNormal.owners().size()==2); }
                        if(F_TRACE_ENABLED){ if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info("[PDD-F-TRACE] credit={} edges={} receipts={} " +
                                        "nodes={} cutoffs={} " +
                                        "emitted={} suppressed={} maxPerTick={} maxPerCredit={} " +
                                        "quantization=0.1 directionUnknownEqualSplit=true mutation=OFF",
                                grant.id(),traceCounts[0],traceCounts[1],traceCounts[3],traceCounts[4],
                                traceCounts[2],traceCounts[0]+traceCounts[1]+traceCounts[3]+traceCounts[4]-traceCounts[2],
                                MAX_F_TRACE_PER_TICK,MAX_F_TRACE_PER_CREDIT); }
                        if(DETAIL_ENABLED&&VERF_ENABLED){ if(RogTrace.legacySlot(LogSelection.Channel.NATIVE)) log.info("[PDD-NATIVE-NORMAL] credit={} status={} " +
                                        "reason={} records={} meanResultant={} minimumAlignment={} " +
                                        "meanPolicy=UNIT_NORMAL_EQUAL_RECORD_WEIGHT ownerVectors={} nativeCapSampled={} effectPhase=true " +
                                        "exactSolverContact=false deformationAuthorized=false mutation=OFF",
                                grant.id(),nativeNormal.owners().size()==2?"CORROBORATED":"UNKNOWN",
                                nativeNormal.reason(),nativeNormal.corroboratedRecords(),
                                nativeNormal.meanResultant(),nativeNormal.minimumAlignment(),nativeNormal.owners(),
                                saturatedPoll); }
                        if(NORMAL_SAMPLES_ENABLED&&VERF_ENABLED&&massSafe!=null) {
                            int shown=0;
                            for(var sample:massSafe.contacts()) {
                                if(shown++>=MAX_NATIVE_NORMAL_SAMPLES)break;
                                { if(RogTrace.legacySlot(LogSelection.Channel.NATIVE)) log.info("[PDD-NORMAL-SAMPLE] credit={} ordinal={} idA={} idB={} " +
                                                "normalA_local=({},{},{}) normalB_local=({},{},{}) " +
                                                "frameA={} frameB={} capSaturated={} mutation=OFF",
                                        grant.id(),shown-1,sample.idA(),sample.idB(),
                                        sample.ax(),sample.ay(),sample.az(),sample.bx(),sample.by(),sample.bz(),
                                        sample.frameA()==null?"WORLD":sample.frameA().uuid(),
                                        sample.frameB()==null?"WORLD":sample.frameB().uuid(),sample.capSaturated()); }
                            }
                            if(massSafe.contacts().size()>MAX_NATIVE_NORMAL_SAMPLES)
                                { if(RogTrace.legacySlot(LogSelection.Channel.NATIVE)) log.info("[PDD-NORMAL-SAMPLES] credit={} logged={} suppressed={} mutation=OFF",
                                        grant.id(),MAX_NATIVE_NORMAL_SAMPLES,
                                        massSafe.contacts().size()-MAX_NATIVE_NORMAL_SAMPLES); }
                        }
                        credited++;
                        if(grant.propagated()) {
                            double fInput=grant.inputF(),fAccounted=grant.propagationA().assignedF()+
                                    grant.propagationA().dampedF()+grant.propagationA().contactDampedF()+grant.propagationA().unresolvedF()+
                                    grant.propagationA().audit().thresholdDiscardF()+
                                    grant.propagationB().assignedF()+grant.propagationB().dampedF()+grant.propagationB().contactDampedF()+
                                    grant.propagationB().unresolvedF()+
                                    grant.propagationB().audit().thresholdDiscardF();
                            double qError=grant.issuedQ()-grant.accounted();
                            double fError=fInput-fAccounted;
                            { if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info("[PDD-CONTACT-ELASTIC] credit={} alpha={} contactDampedF={} revision={}",grant.id(),DamageModel.CONTACT_ELASTICITY_ALPHA,grant.contactDampedF(),grant.algorithmRevision()); }
                            { if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info("[PDD-CHAIN-LEDGER] credit={} actualOwners={} crossEdges={} candidateF={} assignedF={} contactDampedF={} internalDampedF={} unresolvedF={} thresholdDiscardF={}",
                                    grant.id(),java.util.stream.Stream.concat(grant.a().stream(),grant.b().stream()).map(s->s.target().ownerKey()).distinct().count(),
                                    grant.propagationA().audit().crossEdges()+grant.propagationB().audit().crossEdges(),
                                    fInput,grant.propagationA().assignedF()+grant.propagationB().assignedF(),grant.contactDampedF(),grant.dampedF(),grant.unresolvedF(),
                                    grant.propagationA().audit().thresholdDiscardF()+grant.propagationB().audit().thresholdDiscardF()); }
                            // Retain the largest absolute unresolved budgets, not the first four events.
                            contactBudgetTop.consider(new ContactBudgetTop.Sample(
                                    String.valueOf(grant.id()),fInput,grant.unresolvedF(),
                                    String.valueOf(grant.propagationA().endReason()),
                                    String.valueOf(grant.propagationB().endReason()),
                                    saturatedPoll,String.valueOf(regions.status())));
                            { if(RogTrace.eventSlot(Stage.R06,"F_CREDIT_SUMMARY"))RogTrace.detail(Stage.R06,"F_CREDIT_SUMMARY","credit={} sideA={} sideB={} directUnresolvedA={} directUnresolvedB={} propagationUnresolvedA={} propagationUnresolvedB={} directDampedA={} directDampedB={} contactDampedTotal={} internalDamped={} assignedF={} thresholdDiscardF={} candidateQA={} candidateQB={} preCapQA={} preCapQB={} issuedQ={} allocatedA={} allocatedB={} unusedQ={} fResidual={} qResidual={} endA={} endB={}",
                                    grant.id(),grant.propagationA().inputF(),grant.propagationB().inputF(),
                                    grant.propagationA().directUnresolvedF(),grant.propagationB().directUnresolvedF(),
                                    grant.propagationA().propagationUnresolvedF(),grant.propagationB().propagationUnresolvedF(),
                                    grant.propagationA().directContactDampedF(),grant.propagationB().directContactDampedF(),
                                    grant.contactDampedF(),grant.dampedF(),grant.propagationA().assignedF()+grant.propagationB().assignedF(),
                                    grant.propagationA().audit().thresholdDiscardF()+grant.propagationB().audit().thresholdDiscardF(),
                                    grant.propagationA().candidateQ(),grant.propagationB().candidateQ(),grant.propagationA().finalQ(),grant.propagationB().finalQ(),
                                    grant.issuedQ(),grant.allocatedA(),grant.allocatedB(),grant.unused(),fError,qError,grant.propagationA().endReason(),grant.propagationB().endReason()); }
                            traceSealedContact(grant.id(),selection.records(),directRegions,damageRegions);
                            if(!Double.isFinite(fError)||!Double.isFinite(qError)||Math.abs(fError)>Math.max(1e-7,fInput*1e-7)||Math.abs(qError)>Math.max(1e-7,grant.issuedQ()*1e-7))
                                log.error("[PDD-F-INVARIANT] credit={} FError={} QError={} status=FAIL",grant.id(),fError,qError);
                            { if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info("[PDD-F-INVARIANT] credit={} FInput={} FAccounted={} FError={} " +
                                            "issuedQ={} reservedA={} reservedB={} unusedQ={} QError={} " +
                                            "FStatus={} QStatus={} mutation=OFF",
                                    grant.id(),fInput,fAccounted,fError,grant.issuedQ(),
                                    grant.allocatedA(),grant.allocatedB(),grant.unused(),qError,
                                    Math.abs(fError)<=Math.max(1e-7,fInput*1e-7)?"PASS":"FAIL",
                                    Math.abs(qError)<=Math.max(1e-7,grant.issuedQ()*1e-7)?"PASS":"FAIL"); }
                            { if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info("[PDD-Q-SEAL] credit={} ownerA={} ownerB={} " +
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
                                    grant.unused()); }
                            for(var side:List.of(grant.propagationA(),grant.propagationB())) {
                                String owner=side==grant.propagationA()?pair.a().stableKey():pair.b().stableKey();
                                if(logCounts[0]++<MAX_LOG_PER_TICK){ if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info(
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
                                        side.audit().maxDepth(),side.endReason(),side.shares().size()); }
                                int shownNodes=0;
                                if(DETAIL_ENABLED)for(var node:side.shares())if(shownNodes++<4 && logCounts[0]++<MAX_LOG_PER_TICK){ if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info(
                                        "[PDD-F-PROPAGATION] credit={} owner={} layer={} local={} F={} " +
                                        "candidateQ={} finalQ={} E={} mutation=OFF",
                                        grant.id(),owner,node.layer(),node.target().localBlock(),
                                        node.allocatedF(),node.rawQ(),node.finalQ(),node.elasticityPercent()); }
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
                        } else if(!nativeNormal.owners().isEmpty()&&pair.a().stableKey().startsWith("PLOT:")
                                &&pair.b().stableKey().startsWith("PLOT:")) {
                            witness=NativePlotDirection.prepare(selection.records(),pre).ownerLocalDirection(
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
                        if(DETAIL_ENABLED&&VERF_ENABLED){ if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-DIRECTION] credit={} status={} reason={} " +
                                "fVectorStatus={} fVectorReason={} gridAxisStatus={} " +
                                "auditedPreFrames={} witnesses={} exactContact=false " +
                                "deformationAuthorized=false mutation=OFF",
                                grant.id(),fVectorValid?"F_VECTOR_CORROBORATED":"UNKNOWN",
                                witness.reason(),fVectorValid?"CORROBORATED":"UNKNOWN",nativeNormal.reason(),
                                witness.owners().isEmpty()?"NONAXIAL_OR_UNVERIFIED":"AXIS_CORROBORATED",
                                witness.auditedFrames(),witness.owners()); }
                        if(previewMode!=PreviewMode.OFF)
                            logCounts[1]=recordPreview(grant,materialSnapshot,targetMaterials,witness,now,logCounts[1],log);
                        if(worldCommit!=null) {
                            try {worldCommit.offer(grant,materialSnapshot,targetMaterials);}
                            catch(RuntimeException commitError) {
                                log.error("[PDD-COMMIT] phase=OFFER status=ABORTED " +
                                        "credit={} reason=UNEXPECTED_ADAPTER_ERROR",grant.id(),commitError);
                            }
                        }
                        if(plotCommit!=null) {
                            try {plotCommit.offer(grant,materialSnapshot,targetMaterials);}
                            catch(RuntimeException commitError) {
                                log.error("[PDD-PLOT] phase=OFFER status=ABORTED " +
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
                    if(grant!=null && logCounts[0]++<MAX_LOG_PER_TICK)
                        { if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.info("[PDD-LEDGER] dimension={} tick={} episodeGeneration={} source={} status={} regions={} checksA={} checksB={} rejectedPoints={} Q={} allocatedA={} allocatedB={} unused={} aTargets={} bTargets={} aSample={} bSample={} ownerEpochA={} ownerEpochB={} sourceCapSaturated={} mutation=OFF",
                                stamp.dimension,stamp.tick,grant.id().episodeGeneration(),grant.source(),grant.status(),
                                grant.observedRegions(),damageRegions.checksA(),damageRegions.checksB(),damageRegions.rejected(),
                                grant.issuedQ(),grant.allocatedA(),grant.allocatedB(),grant.unused(),
                                grant.a().size(),grant.b().size(),DETAIL_ENABLED?TraceBudget.sample(grant.a(),fullTrace?32:4):List.of(),DETAIL_ENABLED?TraceBudget.sample(grant.b(),fullTrace?32:4):List.of(),pair.a().generation(),pair.b().generation(),saturatedPoll); }
                    if(DETAIL_ENABLED && showGroup && logCounts[0]++<MAX_LOG_PER_TICK) { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-TARGET] dimension={} tick={} ownerA={} ownerAGeneration={} ownerB={} ownerBGeneration={} targetA={} fingerprintA={} targetB={} fingerprintB={} frameMethodA={} frameMethodB={} shapeAudit={} quality={} reason={} approachByAabb={} armedFromGap={} recentGap={} worldMutation=OFF",
                            stamp.dimension,stamp.tick,pair.a().stableKey(),pair.a().generation(),pair.b().stableKey(),pair.b().generation(),
                            a==null?"UNVERIFIED":a.localBlock(),a==null?"UNVERIFIED":a.fingerprint(),
                            b==null?"UNVERIFIED":b.localBlock(),b==null?"UNVERIFIED":b.fingerprint(),
                            methodA,methodB,
                            auditEligible?"OWNER_SHAPE_CHECKED":"SKIPPED_DUPLICATE",
                            !auditEligible?"NOT_AUDITED_DUPLICATE":a==null&&b==null?"SOURCE_UNVERIFIED":"POST_EFFECT_CORROBORATED_GAME_PROXY",
                            !auditEligible?"NO_Q_AUTHORIZATION":a==null&&b==null?"TARGET_UNVERIFIED":"SABLE_NATIVE_CONSUMER_MAPPED",
                            aabbClosing,armedFromGap,previousSeparated); }
                    if(showGroup && logCounts[0]++<MAX_LOG_PER_TICK){ if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-EPISODE] dimension={} tick={} bodyIds={}/{} records={} peak={} previousSeparated={} separationEvidenceTick={} phaseBefore={} armedFromGap={} aabbClosing={} forceRise={} state={} reason={} creditCount={} episodeGeneration={} authorizationTick={} Q={} shareA={} shareB={} unused={} regionQuality={} groupCause={} source=POST_EFFECT_GAME_POLICY worldMutation=OFF",
                            stamp.dimension,stamp.tick,group.runtimePair().lowId(),group.runtimePair().highId(),
                            group.originalRecordCount(),group.forcePeak(),previousSeparated,separationEvidenceTick,
                            phaseBefore,armedFromGap,aabbClosing,
                            Double.isFinite(rise)?Double.toString(rise):"UNKNOWN",result.phase(),
                            grant!=null&&!granted?"LEDGER_"+grant.status():result.reason().name(),result.episode(),
                            result.episodeGeneration(),result.authorizationTick(),
                            granted?grant.issuedQ():0.0,granted?grant.allocatedA():0.0,
                            granted?grant.allocatedB():0.0,granted?grant.unused():0.0,
                            regions.status(),"PAIR_EPISODE_"+result.episodeGeneration()); }
                    } catch(RuntimeException unsupported) {
                        if(RogTrace.sample(Stage.R06,"PLAN_EXCEPTION"))RogTrace.detail(Stage.R06,"PLAN_EXCEPTION","episode={} type={} message={}",result.episodeGeneration(),unsupported.getClass().getName(),unsupported.getMessage());
                        // An unsealed approval must not consume authorization.
                        if(result.credited()){
                            boolean sealed=regionLedger.lookup(new ContactCreditLedger.CreditId(pair,result.episodeGeneration()))!=null;
                            if(sealed)selection.finish(true);
                            else if(credit.abortUnissued(pair,result))selection.finish(false);
                            else selection.quarantine();
                        }
                        zero++;
                        if(logCounts[0]++<MAX_LOG_PER_TICK)log.warn("[PDD-EPISODE] dimension={} tick={} Q=0 reason=INVALID_PLAN_INPUT type={} worldMutation=OFF",
                                stamp.dimension,stamp.tick,unsupported.getClass().getSimpleName());
                    } finally {RogTrace.clearContext();}
                    };
                    if(result.credited())deferredPlans.add(deferred);else deferred.run();
                } catch(RuntimeException unsupported) {
                    if(RogTrace.sample(Stage.R05,"BRIDGE_EXCEPTION"))RogTrace.detail(Stage.R05,"BRIDGE_EXCEPTION","type={} message={}",unsupported.getClass().getName(),unsupported.getMessage());
                    if(authorization!=null&&authorization.credited()){
                        boolean sealed=regionLedger.lookup(new ContactCreditLedger.CreditId(pair,authorization.episodeGeneration()))!=null;
                        if(sealed)selection.finish(true);
                        else if(credit.abortUnissued(pair,authorization))selection.finish(false);
                        else selection.quarantine();
                    }
                    secondary.remove(pair);
                    zero++;
                    if(logCounts[0]++<MAX_LOG_PER_TICK)log.warn("[PDD-EPISODE] dimension={} tick={} Q=0 reason=INVALID_BRIDGE_INPUT type={} worldMutation=OFF",
                            stamp.dimension,stamp.tick,unsupported.getClass().getSimpleName());
                }
            }
            RogTrace.clearContext();
            int positiveCrossPairs=0,positiveCrossEdges=0,crossDetailedEmitted=0;
            for(var candidate:secondary.entrySet()) {
                Pair pair=candidate.getKey();
                var scene=new SceneKey(stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey());
                if(!secondaryPairEligible(directAuthorized.contains(pair),ownerPairCounts.getOrDefault(scene,0)))continue;
                boolean validBatch=complete&&batch.invalidRecords()==0&&batch.discardedRecords()==0&&!batch.malformedArray();
                int admitted=contactGraph.observe(pair,candidate.getValue(),pre,validBatch);
                if(admitted>0){
                    positiveCrossPairs++;positiveCrossEdges+=admitted;
                    if((fullTrace||crossDetailedEmitted<2)&&logCounts[0]++<MAX_LOG_PER_TICK){
                        { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-CROSS-OWNER] pair={}/{} tick={} admitted={} proof=FINAL_DUPLICATE_CURRENT_NATIVE",pair.a().stableKey(),pair.b().stableKey(),stamp.tick,admitted); }
                        crossDetailedEmitted++;
                    }
                }
            }
            if(positiveCrossPairs>crossDetailedEmitted&&!fullTrace)
                { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-CROSS-OWNER-SUMMARY] tick={} positivePairs={} admittedTotal={} detailedLogged={} suppressedPairs={} proof=CURRENT_NATIVE",
                        stamp.tick,positiveCrossPairs,positiveCrossEdges,crossDetailedEmitted,positiveCrossPairs-crossDetailedEmitted); }
            RogTrace.clearContext();
            for(Runnable plan:deferredPlans)plan.run();
            RogTrace.clearContext();
            if((!secondary.isEmpty()||!directAuthorized.isEmpty())&&(fullTrace||!directAuthorized.isEmpty()||now%VERF_SAMPLE_TICKS==0)&&logCounts[0]++<MAX_LOG_PER_TICK)
                { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-CROSS-OWNER-AUDIT] tick={} direct={} secondary={} history={} stats={} sourceGameTick={} liveGameTick={} sourceServerTick={} processingServerTick={} stampReason={}",
                        stamp.tick,directAuthorized.size(),secondary.size(),contactGraph.historySize(),contactGraph.stats(),
                        capture.sourceGameTick(),contactGraph.liveGameTick(),capture.captureServerTick(),now,contactGraph.runtimeStatus()); }
        }
        for(var sample:contactBudgetTop.samples())
            { if(RogTrace.legacySlot(LogSelection.Channel.FORCE)) log.warn("[PDD-CONTACT-BUDGET] credit={} unresolvedRatio={} unresolvedF={} " +
                            "reasonA={} reasonB={} nativeCap={} regionStatus={} " +
                            "policy=WITHHOLD_UNPROVED mutation=OFF",
                    sample.credit(),sample.ratio(),sample.unresolvedF(),
                    sample.reasonA(),sample.reasonB(),sample.nativeCap(),sample.regionStatus()); }
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
                t.regionHistory.fullSeparation(now);
                t.lastPositiveSeparation=true;
                contactHistory.invalidatePair(p);
                t.lastSeparatedTick=now;
                try {
                    var before=credit.evidence(p);
                    var d=credit.step(new Frame(p,now,List.of(),true,false,t.a,t.b));
                    if(d.phase()==Phase.ARMED)worldClearance.contact(p);
                    if(VERF_ENABLED && before.phase()!=d.phase())
                        { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-VERF-SEPARATION] serverTick={} pair={}/{} source={} " +
                                "stateBefore={} stateAfter={} separatedBefore={} separatedAfter={} " +
                                "aabbGap={} sourceVerified=true mutation=OFF",
                                now,p.a().stableKey(),p.b().stableKey(),
                                worldSide?"VERIFIED_WORLD_CLEARANCE":"OBSERVED_AABB_GAP",before.phase(),d.phase(),
                                before.separatedSamples(),credit.evidence(p).separatedSamples(),
                                worldSide?"VERIFIED_EMPTY_REGION":verifiedGap(a,b)); }
                }
                catch(RuntimeException ignored) { /* invalid snapshot: Q=0 */ }
            } else {
                credit.invalidateSeparationStreak(p);
                for(var track:t.regionHistory.tracks())if(localRegionGap(server,p,track,worldBounds,remainingLocalGapChecks))t.regionHistory.separated(track,now);else t.regionHistory.unknown(track,now);
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
            // All owner-dependent evidence was invalidated when the owner
            // disappeared. Only ephemeral runtime tombstones are removed;
            // the PlotOwnerFence remains authoritative for persisted owners.
            int beforeGenerationPrune=generations.size();
            generations.entrySet().removeIf(e->e.getValue().idleExpired(now));
            { if(RogTrace.legacySlot(LogSelection.Channel.PERF)) log.info("[PDD-CACHE-PERF] generationEntries={} retiredGenerations={}",
                    generations.size(),beforeGenerationPrune-generations.size()); }
            // A pre-contact ARMED pair is processed every tick, but has never
            // had a contact. Expiring on lastSeen (-1) discards its verified
            // clearance/AABB evidence while GameImpactCredit stays ARMED.
            tracked.entrySet().removeIf(e->trackedIdleExpired(now,e.getValue().lastProcessed));
            { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-0.4.0finalFix] phase=SUMMARY observed={} credited={} zero={} ownerMissing={} targetMissing={} ambiguous={} trackedPairs={} r2Verified={} r2Rejected={} r3Verified={} prearmed={} r1Fallback={} regionGroups={} regionCount={} regionTooBroad={} ledgerRejected={} activeCredits={} observerErrors={} nativeDropped={} nativeMalformed={} nativeCapSaturatedPolls={} r2Audits={} r2SkippedDuplicate={} r2ActualShapeChecks={} callbackDropped={} preHistoryDropped={} repeatedDiagnosticGroupsSuppressed={} generation=IDS-1.0.7 mutation=OFF",
                    observed,credited,zero,missingOwner,missingTarget,ambiguousOwners,tracked.size(),
                    r2Verified,r2Rejected,r3Verified,prearmed,r1Fallback,
                    multiGroupCredits,multiRegions,multiTooBroad,ledgerRejected,regionLedger.size(),
                    NativeCollisionTelemetry.observerErrors(),
                    NativeCollisionTelemetry.dropped(),NativeCollisionTelemetry.malformed(),
                    NativeCollisionTelemetry.nativeCapSaturatedPolls(),r2Audits,r2SkippedDuplicate,r2ShapeChecks,
                    com.physicaldestroydeform.diagnostics.SableContactQueue.dropped(),SableFrameCapture.preHistoryDropped(),repeatedDiagnosticGroupsSuppressed); }
            if(previewMode!=PreviewMode.OFF){ if(RogTrace.legacySlot(LogSelection.Channel.DAMAGE)) log.info("[PDD-MATERIAL-PLAN] phase=SUMMARY targetsPlanned={} missingMaterialEvents={} " +
                    "planRejected={} mode=PREVIEW_ONLY detail={} storedEntries={} fixedTotal={} " +
                    "handledCredits={} duplicatePreview={} previewCapacityRejected={} " +
                    "directionCorroborated={} directionUnknown={} previewFailures={} codecRoundTrips={} " +
                    "codecRoundTripErrors={} applied=0 mutation=OFF",
                    materialPlanned,materialMissing,materialRejected,previewMode,preview.size(),preview.fixedTotal(),
                    preview.handledCredits(),preview.duplicates(),preview.capacityRejected(),
                    directionApproved,directionUnknown,previewFailures,preview.roundTrips(),preview.roundTripErrors()); }
            if(previewMode==PreviewMode.FULL) {
                try {
                    var bytes=preview.verifyPendingRoundTrip();
                    if(bytes.isPresent()){ if(RogTrace.legacySlot(LogSelection.Channel.STORAGE)) log.info("[PDD-PREVIEW-CODEC] status=PASS entries={} bytes={} " +
                                    "roundTrips={} fixedTotal={} schema={} corruptionVeto=PASS " +
                                    "authoritative=false mutation=OFF",
                            preview.size(),bytes.getAsInt(),preview.roundTrips(),preview.fixedTotal(),
                            com.physicaldestroydeform.state.SparseStateCodec.SCHEMA_VERSION); }
                }catch(RuntimeException invalid) {
                    log.warn("[PDD-PREVIEW-CODEC] status=FAIL reason={} " +
                            "roundTripErrors={} mutation=OFF",invalid.getClass().getSimpleName(),
                            preview.roundTripErrors(),invalid);
                }
            }
            if(VERF_ENABLED) {
                var evidence=forceCalibration.snapshot();
                var settings=credit.settings();
                { if(RogTrace.legacySlot(LogSelection.Channel.PERF)) log.info("[PDD-SUPERFIX-CAL] approved={} saturated={} minPeak={} maxPeak={} minRawQ={} maxRawQ={} " +
                        "forceBuckets=[0:16,16:64,64:256,256:1024,1024:4096,4096:16384,16384:65536,65536:inf] counts={} " +
                        "forceFloor={} forceGain={} maxGroupQ={} mutation=OFF",
                        evidence.approved(),evidence.saturated(),evidence.minPeak(),evidence.maxPeak(),
                        evidence.minRawQ(),evidence.maxRawQ(),evidence.forceBuckets(),
                        settings.forceFloor(),settings.forceGain(),settings.maxGroupQ()); }
                { if(RogTrace.legacySlot(LogSelection.Channel.PERF)) log.info("[PDD-SUPERFIX-CLEARANCE-PROF] calls={} cellReads={} elapsedNanos={} proved={} " +
                        "maxCells={} confirmPerTick={} mutation=OFF",
                        clearanceCalls,clearanceCellReads,clearanceElapsedNanos,clearanceProved,
                        WorldClearanceVerifier.MAX_CELLS,WorldClearanceVerifier.MAX_CONFIRM_PER_TICK); }
            }
        }
    }

    /** Only position-dependent material needs a second read at the seal boundary.
     * Ordinary materials are immutable under the already checked generation. */
    private static Map<Target,Optional<CopycatMaterial.Resolved>> freezePositionalMaterials(
            MinecraftServer server,String dimension,MaterialRegistry.Snapshot snapshot,List<ContactCreditLedger.Region> regions){
        var targets=new HashSet<Target>();
        for(var r:regions){
            if(r.a()!=null&&CopycatMaterial.isCopycat(r.a().fingerprint()))targets.add(r.a());
            if(r.b()!=null&&CopycatMaterial.isCopycat(r.b().fingerprint()))targets.add(r.b());
            for(var t:r.opposingCandidates())if(CopycatMaterial.isCopycat(t.fingerprint()))targets.add(t);
        }
        if(targets.isEmpty())return Map.of();
        var reader=new TargetMaterials(server,dimension,snapshot);var out=new HashMap<Target,Optional<CopycatMaterial.Resolved>>();
        for(var t:targets)out.put(t,Optional.ofNullable(reader.resolve(t)));
        return Map.copyOf(out);
    }

    private static void traceSealedContact(ContactCreditLedger.CreditId creditId,List<NativeCollisionTelemetry.Contact> records,
                                           SableContactRegions.Result direct,SableContactRegions.Result sealed){
        if(!RogTrace.traceSelected()||!RogTrace.stageEnabled(Stage.R04)&&!RogTrace.stageEnabled(Stage.R01))return;
        try{
            int cap=RogTrace.rawSelected()?Math.min(records.size(),RogTrace.seedLimit()):0;
            { if(RogTrace.eventSlot(Stage.R01,"NATIVE_CAPTURE_AUDIT"))RogTrace.detail(Stage.R01,"NATIVE_CAPTURE_AUDIT","credit={} rawRecords={} listedLimit={} stride={} exactSubstep=UNAVAILABLE posePhase=POST_EFFECT truncated={}",creditId,records.size(),cap,NativeCollisionTelemetry.FIELDS,cap<records.size()); }
            for(int i=0;i<cap;i++){
                if(!RogTrace.evidenceSlot(Stage.R01,"NATIVE_CONTACT_RECORD"))break;
                var c=records.get(i);
                RogTrace.detail(Stage.R01,"NATIVE_CONTACT_RECORD","credit={} index={} ids={}/{} scalar={} localA={}/{}/{} localB={}/{}/{} normalA={}/{}/{} normalB={}/{}/{} frameA={} frameB={} nativeTick={} captureTick={} pollId={} saturated={}",creditId,i,c.idA(),c.idB(),c.rawImpactScalar(),c.contactAX(),c.contactAY(),c.contactAZ(),c.contactBX(),c.contactBY(),c.contactBZ(),c.ax(),c.ay(),c.az(),c.bx(),c.by(),c.bz(),c.frameA(),c.frameB(),c.serverTick(),c.captureServerTick(),c.pollId(),c.capSaturated());
            }
            if(!RogTrace.stageEnabled(Stage.R04))return;
            for(boolean onA:new boolean[]{true,false}){
                var initial=new HashSet<GameImpactCredit.Target>();var confirmed=new HashSet<GameImpactCredit.Target>();
                for(var region:direct.regions()){var target=onA?region.a():region.b();if(target!=null)initial.add(target);}
                for(var region:sealed.regions()){var target=onA?region.a():region.b();if(target!=null)confirmed.add(target);}
                var sorted=confirmed.stream().sorted(java.util.Comparator.comparing(GameImpactCredit.Target::localBlock)).toList();
                int count=Math.min(sorted.size(),RogTrace.seedLimit());
                { if(RogTrace.eventSlot(Stage.R04,"CONTACT_SEED_COORD_SUMMARY"))RogTrace.detail(Stage.R04,"CONTACT_SEED_COORD_SUMMARY","credit={} side={} directCount={} sealedCount={} listedLimit={} truncated={}",creditId,onA?"A":"B",initial.size(),sorted.size(),count,count<sorted.size()); }
                for(int offset=0;offset<count;offset+=32){
                    if(!RogTrace.evidenceSlot(Stage.R04,"CONTACT_SEED_COORDS"))break;
                    var cells=new ArrayList<String>();int end=Math.min(count,offset+32);
                    for(int i=offset;i<end;i++){var target=sorted.get(i);cells.add(target.localBlock()+"|"+(initial.contains(target)?"DIRECT_NATIVE":sealed.patchSeeds().contains(target)?"OWNER_LOCAL_PATCH":"BILATERAL_EXACT"));}
                    RogTrace.detail(Stage.R04,"CONTACT_SEED_COORDS","credit={} side={} offset={} end={} total={} cells={}",creditId,onA?"A":"B",offset,end,sorted.size(),cells);
                }
            }
        }catch(RuntimeException|LinkageError ignored){/* diagnostic only */}
    }

    private int recordPreview(ContactCreditLedger.Grant grant,MaterialRegistry.Snapshot materials,
                              TargetMaterials targetMaterials,NativePlotDirection.Prepared.DirectionResult witness,
                              long tick,int materialLines,Logger log) {
        boolean fullTrace=previewMode==PreviewMode.FULL;
        try {
            boolean timePreview=LogSelection.enabled(LogSelection.Channel.PERF)&&fullTrace&&materialLines<MAX_MATERIAL_LOGS_PER_TICK;
            long start=timePreview?System.nanoTime():0;
            var result=preview.recordTargets(grant,materials.generation(),tick,
                    targetMaterials::material,damageSettings,
                    key->fullTrace?Optional.ofNullable(witness.owners().get(key.ownerSpace()+":"+key.ownerId()))
                            :Optional.empty());
            if(timePreview) {
                materialLines++;
                { if(RogTrace.legacySlot(LogSelection.Channel.PERF)) log.info("[PDD-MATERIAL-PERF] credit={} previewNanos={} targetsA={} targetsB={} status={} mutation=OFF",
                        grant.id(),System.nanoTime()-start,grant.a().size(),grant.b().size(),result.status()); }
            }
            if(result.status()!=PreviewDamageLedger.ResultStatus.ACCEPTED) {
                previewFailures++;
                if(materialLines<MAX_MATERIAL_LOGS_PER_TICK&&(fullTrace||previewFailures==1)) {
                    materialLines++;
                    log.warn("[PDD-MATERIAL-PLAN] credit={} status={} " +
                            "reason=PREVIEW_DEDUP_OR_BOUNDED_CAP mutation=OFF",grant.id(),result.status());
                }
                return materialLines;
            }
            var report=result.report();
            materialPlanned+=report.plans().size();
            if(report.missingMaterialQ()>0)materialMissing++;
            if(materialLines>=MAX_MATERIAL_LOGS_PER_TICK)return materialLines;
            materialLines++;
            { if(RogTrace.legacySlot(LogSelection.Channel.DAMAGE)) log.info("[PDD-MATERIAL-PLAN] credit={} revision={} materialGeneration={} verifiedTargets={} " +
                            "missingMaterialQ={} reservedQ={} absorbedQ={} directQ={} unusedPlannedQ={} " +
                            "fixedIncrement={} directionWitnesses={} stress=INACTIVE plastic=INACTIVE " +
                            "state=PREVIEW_ONLY committedDamage=0 previewEntries={} previewFixedBefore={} " +
                            "previewFixedAfter={} previewUpdated={} mutation=OFF",
                    report.creditId(),DamageModel.ALGORITHM_REVISION,report.materialGeneration(),report.plans().size(),
                    report.missingMaterialQ(),grant.issuedQ(),report.absorbedQ(),report.directQ(),report.unusedPlannedQ(),
                    report.fixedIncrementTotal(),witness.owners().size(),result.entries(),result.fixedBefore(),
                    result.fixedAfter(),result.updatedTargets()); }
            if(!fullTrace||materialLines>=MAX_MATERIAL_LOGS_PER_TICK)return materialLines;
            int targetsLogged=Math.min(report.plans().size(),MAX_MATERIAL_LOGS_PER_TICK-materialLines-1);
            for(int i=0;i<targetsLogged;i++) {
                var target=report.plans().get(i);
                var material=target.material();var planned=target.plan();
                { if(RogTrace.legacySlot(LogSelection.Channel.DAMAGE)) log.info("[PDD-MATERIAL-TARGET] key={} H={} T={} D={} E={} unbreakable={} " +
                                "Q={} absorbed={} direct={} plasticUnused={} stressUnused={} " +
                                "rawDamage={} incrementFixed={} predictedDamage={} remainder={} " +
                                "thresholdReached={} plasticPreview={} direction={} " +
                                "applied=false reason=SHADOW_PREVIEW_ONLY mutation=OFF",
                        target.key(),material.hardness(),material.threshold(),material.ductilityPercent(),
                        material.elasticityPercent(),material.unbreakable(),planned.incomingQ(),
                        planned.absorbedQ(),planned.directQ(),planned.plasticQ(),planned.stressQ(),
                        planned.rawDamage(),planned.fixedIncrement(),planned.plannedDamageUnits(),
                        planned.plannedRemainderU8(),planned.thresholdReached(),planned.plannedPlasticIncrement(),
                        target.direction().map(Object::toString).orElse("UNKNOWN")); }
            }
            { if(RogTrace.legacySlot(LogSelection.Channel.DAMAGE)) log.info("[PDD-MATERIAL-TRACE] credit={} plannedTargets={} logged={} suppressed={} maxPerTick={} mutation=OFF",
                    grant.id(),report.plans().size(),targetsLogged,report.plans().size()-targetsLogged,MAX_MATERIAL_LOGS_PER_TICK); }
            return materialLines+targetsLogged+1;
        }catch(RuntimeException invalidMaterialPlan) {
            materialRejected++;
            log.warn("[PDD-MATERIAL-PLAN] reason=PLAN_REJECTED " +
                    "credit={} Q={} applied=false mutation=OFF",grant.id(),grant.issuedQ(),invalidMaterialPlan);
            return materialLines;
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
                                         List<NativeCollisionTelemetry.Contact> contacts,long now,
                                         Map<String,LivePlot> livePlots) {
        if(contacts.isEmpty()){if(RogTrace.sample(Stage.R02,"NO_RECORDS"))RogTrace.detail(Stage.R02,"NO_RECORDS","runtimePair={}",group.runtimePair());return null;}
        String low=null,high=null;
        int idLo=group.runtimePair().lowId(),idHi=group.runtimePair().highId();
        for(var contact:contacts) {
            String la=sourceOwner(stamp.dimension(),contact.idA(),contact.frameA());
            String lb=sourceOwner(stamp.dimension(),contact.idB(),contact.frameB());
            if(la==null || lb==null || la.equals(lb)){if(RogTrace.sample(Stage.R02,"SOURCE_OWNER_MISSING_OR_SAME"))RogTrace.detail(Stage.R02,"SOURCE_OWNER_MISSING_OR_SAME","runtimeA={} runtimeB={} sourceA={} sourceB={} frameA={} frameB={}",contact.idA(),contact.idB(),la,lb,contact.frameA(),contact.frameB());return null;}
            String currentLow=contact.idA()==idLo?la:lb;
            String currentHigh=contact.idA()==idHi?la:lb;
            if(low!=null && !low.equals(currentLow)){if(RogTrace.sample(Stage.R02,"LOW_OWNER_CONFLICT"))RogTrace.detail(Stage.R02,"LOW_OWNER_CONFLICT","previous={} current={} runtimeId={}",low,currentLow,idLo);return null;}
            if(high!=null && !high.equals(currentHigh)){if(RogTrace.sample(Stage.R02,"HIGH_OWNER_CONFLICT"))RogTrace.detail(Stage.R02,"HIGH_OWNER_CONFLICT","previous={} current={} runtimeId={}",high,currentHigh,idHi);return null;}
            low=currentLow;high=currentHigh;
        }
        OwnerSnapshot pre=ownerIndex.get(stamp);
        if(pre!=null && pre.complete) {
            String preLow=owner(pre,stamp.dimension(),idLo);
            String preHigh=owner(pre,stamp.dimension(),idHi);
            if(preLow!=null && !preLow.equals(low) || preHigh!=null && !preHigh.equals(high)){if(RogTrace.sample(Stage.R02,"PRE_OWNER_MISMATCH"))RogTrace.detail(Stage.R02,"PRE_OWNER_MISMATCH","preLow={} preHigh={} effectLow={} effectHigh={}",preLow,preHigh,low,high);return null;}
        }
        var lowBinding=lifecycleBinding(stamp.dimension(),low);
        var highBinding=lifecycleBinding(stamp.dimension(),high);
        // Lifecycle bindings are stamped with server ticks, not dimension age.
        // Known runtime stamps have already passed the CURRENT batch gate.
        boolean stamped=contacts.getFirst().runtimeStamp().known();
        long observedTick=stamped?contacts.getFirst().captureServerTick():stamp.tick();
        if(!validLifecycleFrame(lowBinding,idLo,observedTick)||!validLifecycleFrame(highBinding,idHi,observedTick)){if(RogTrace.sample(Stage.R02,"LIFECYCLE_EPOCH_REJECTED"))RogTrace.detail(Stage.R02,"LIFECYCLE_EPOCH_REJECTED","low={} high={} lowBinding={} highBinding={} observedTick={}",low,high,lowBinding,highBinding,observedTick);return null;}
        if(stamped&&(!runtimeMatches(livePlots.get(low),idLo)||!runtimeMatches(livePlots.get(high),idHi))){if(RogTrace.sample(Stage.R02,"LIVE_RUNTIME_MISMATCH"))RogTrace.detail(Stage.R02,"LIVE_RUNTIME_MISMATCH","low={} high={} runtimeLow={} runtimeHigh={} liveLow={} liveHigh={}",low,high,idLo,idHi,livePlots.get(low),livePlots.get(high));return null;}
        Owner a=new Owner(low,generation(stamp.dimension(),low,idLo,now,lowBinding));
        Owner b=new Owner(high,generation(stamp.dimension(),high,idHi,now,highBinding));
        if(RogTrace.sample(Stage.R02,"OWNER_VERIFIED"))RogTrace.detail(Stage.R02,"OWNER_VERIFIED","runtimeLow={} runtimeHigh={} ownerLow={} ownerHigh={} observedTick={}",idLo,idHi,a,b,observedTick);
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
    private NeoForgePlotGateway.LifecycleBinding lifecycleBinding(String dim,String stable){
        if(plotCommit==null||!stable.startsWith("PLOT:"))return null;
        return plotCommit.lifecycleBinding(dim,UUID.fromString(stable.substring(5)));
    }
    static boolean validLifecycleFrame(NeoForgePlotGateway.LifecycleBinding binding,int runtimeId,long observedTick){
        if(binding==null)return true;
        if(observedTick<=binding.changedTick())return false; // No old solver evidence may span an unload/add boundary.
        // Quarantined Plot state stays blocked by its own gateway; independent World
        // damage from future live source observations is not globally disabled.
        return binding.orphaned()||(binding.active()&&binding.runtimeId()==runtimeId);
    }
    private long generation(String dimension,String owner,int runtimeId,long now,
                            NeoForgePlotGateway.LifecycleBinding binding) {
        if(owner.startsWith("WORLD:"))return 1;
        String key=dimension+"|"+owner;
        Generation g=generations.computeIfAbsent(key,k->new Generation(runtimeId));
        if(binding!=null&&!binding.orphaned()){
            if(g.number!=binding.generation()||g.runtimeId!=runtimeId||!g.present
                    ||lifecycleChanged(g,binding))invalidateOwner(owner);
            g.number=binding.generation();g.lifecycleRevision=binding.revision();
            g.runtimeId=runtimeId;g.setPresent(true,now);
            return g.number;
        }
        if(g.runtimeId!=runtimeId || !g.present){
            g.runtimeId=runtimeId;
            // Missing owners were incremented when they disappeared; don't
            // double-increment on reappearance with the same runtime id.
            if(g.present)g.number++;
            invalidateOwner(owner);
        }
        g.setPresent(true,now);
        return g.number;
    }
    /** Unload/split invalidates every old target/episode, even if the native
     * runtime integer happens to be reused when the plot returns. */
    private static boolean lifecycleChanged(Generation g,NeoForgePlotGateway.LifecycleBinding binding){
        return g.lifecycleRevision<0?binding.revision()>0:g.lifecycleRevision!=binding.revision();
    }
    private void invalidateUnloadedOwners(Map<String,Map<String,LivePlot>> bounds,long now) {
        for(var entry:generations.entrySet()) {
            String key=entry.getKey();int separator=key.indexOf('|');
            if(separator<0)continue;
            String dim=key.substring(0,separator),owner=key.substring(separator+1);
            Generation g=entry.getValue();
            LivePlot loadedPlot=bounds.getOrDefault(dim,Map.of()).get(owner);
            boolean live=loadedPlot!=null;
            var binding=lifecycleBinding(dim,owner);
            if(binding!=null&&!binding.orphaned()){
                boolean loaded=live&&binding.active();
                if(lifecycleChanged(g,binding)||g.number!=binding.generation()
                        ||(g.present&&!loaded)||(loaded&&g.runtimeId!=binding.runtimeId()))invalidateOwner(owner);
                g.number=binding.generation();g.lifecycleRevision=binding.revision();
                if(loaded)g.runtimeId=binding.runtimeId();
                g.setPresent(loaded,now);
                continue;
            }
            if(binding!=null&&lifecycleChanged(g,binding)){
                g.lifecycleRevision=binding.revision();g.number++;
                invalidateOwner(owner);
                if(live)g.runtimeId=loadedPlot.runtimeId();
                g.setPresent(live,now);continue;
            }
            if(live) {
                if(g.runtimeId!=loadedPlot.runtimeId()) {
                    if(g.present)g.number++;
                    invalidateOwner(owner);
                    g.runtimeId=loadedPlot.runtimeId();
                }
                g.setPresent(true,now);
                continue;
            }
            if(g.present){
                g.setPresent(false,now);
                g.number++;
                invalidateOwner(owner);
            }
        }
    }
    private void invalidateOwner(String owner) {
        // Old owner-generation credits must never authorize a recycled body ID.
        contactHistory.invalidateOwner(owner);
        worldClearance.invalidateOwner(owner);
        tracked.keySet().removeIf(pair->pair.a().stableKey().equals(owner)
                || pair.b().stableKey().equals(owner));
        credit.invalidateOwner(owner);
        regionLedger.invalidateOwner(owner);
    }
    private record LivePlot(Box bounds,int runtimeId) { }
    private static boolean runtimeMatches(LivePlot plot,int runtimeId) {
        return plot==null||plot.runtimeId()==runtimeId;
    }
    private static Map<String,Map<String,LivePlot>> readServerBounds(MinecraftServer server) {
        Map<String,Map<String,LivePlot>> result=new HashMap<>();
        for(ServerLevel level:server.getAllLevels()) {
            var container=SubLevelContainer.getContainer(level);
            if(container==null)continue;
            Map<String,LivePlot> boxes=new HashMap<>();
            for(ServerSubLevel sub:container.getAllSubLevels()){
                if(sub==null||sub.isRemoved()||sub.getUniqueId()==null)continue;
                try {
                    var b=sub.boundingBox();
                    if(b!=null) boxes.put("PLOT:"+sub.getUniqueId(),new LivePlot(new Box(
                            b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()),sub.getRuntimeId()));
                } catch(RuntimeException invalid) { /* fail closed */ }
            }
            result.put(level.dimension().location().toString(),boxes);
        }
        return result;
    }
    /** Fair, budgeted precontact clearance. Never loads a chunk or guesses a collider. */
    private void prearmIndependentPairs(MinecraftServer server,long now,
                                       Map<String,Map<String,LivePlot>> bounds,Set<Pair> contacted,
                                       int[] remaining,int[] confirmationRemaining,Logger log) {
        int attempted=0;
        for(var dimEntry:bounds.entrySet()) {
            String dim=dimEntry.getKey();
            var entries=new ArrayList<>(dimEntry.getValue().entrySet());
            entries.sort(Map.Entry.comparingByKey());
            // Rotate work order so a busy/large ship cannot starve later plots.
            if(!entries.isEmpty()) java.util.Collections.rotate(entries,(int)(now%entries.size()));
            for(int i=0;i<entries.size() && attempted<128;i++) {
                var first=entries.get(i);Box firstBox=first.getValue().bounds();
                if(firstBox==null)continue;
                for(int j=i+1;j<entries.size() && attempted<128;j++) {
                    var second=entries.get(j);Box secondBox=second.getValue().bounds();
                    if(secondBox==null)continue;
                    attempted++;
                    if(firstBox.separatedFrom(secondBox)) {
                        long genA=prearmGeneration(dim,first.getKey(),first.getValue(),now);
                        long genB=prearmGeneration(dim,second.getKey(),second.getValue(),now);
                        if(genA<=0||genB<=0)continue;
                        Pair pair=Pair.canonical(runId,dim,
                                new Owner(first.getKey(),genA),new Owner(second.getKey(),genB));
                        seedSeparation(pair,now,contacted,first.getKey(),firstBox,secondBox,log);
                    }
                }
                if(attempted>=128)break;
                attempted++;
                long gen=prearmGeneration(dim,first.getKey(),first.getValue(),now);
                if(gen<=0)continue;
                Pair worldPair=Pair.canonical(runId,dim,new Owner("WORLD:"+dim,1),
                        new Owner(first.getKey(),gen));
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
    /** Register precontact-only owners too. Otherwise their cached clearance and
     * ARMED episodes have no generation entry for unload invalidation. */
    private long prearmGeneration(String dim,String owner,LivePlot plot,long now) {
        if(plot.runtimeId()<0)return -1;
        var binding=lifecycleBinding(dim,owner);
        if(binding!=null&&!binding.orphaned()&&(!binding.active()||binding.runtimeId()!=plot.runtimeId()))return -1;
        return generation(dim,owner,plot.runtimeId(),now,binding);
    }
    private long currentGeneration(String dimension,String owner) {
        var binding=lifecycleBinding(dimension,owner);
        if(binding!=null&&!binding.orphaned())return binding.generation();
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
            t.regionHistory.fullSeparation(now);
            if(d.phase()==Phase.ARMED)worldClearance.contact(pair);
            if(VERF_ENABLED && prior.phase()!=d.phase())
                { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-VERF-SEPARATION] serverTick={} pair={}/{} source=PRECONTACT_CLEARANCE " +
                        "stateBefore={} stateAfter={} separatedBefore={} separatedAfter={} " +
                        "aabbGap={} sourceVerified=true mutation=OFF",
                        now,pair.a().stableKey(),pair.b().stableKey(),prior.phase(),d.phase(),
                        prior.separatedSamples(),credit.evidence(pair).separatedSamples(),
                        verifiedGap(ordered[0],ordered[1])); }
            t.lastPositiveSeparation=true;
            contactHistory.invalidatePair(pair);
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
            long startNanos=LogSelection.enabled(LogSelection.Channel.PERF)&&VERF_ENABLED?System.nanoTime():0;
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
                if(startNanos!=0)clearanceElapsedNanos+=Math.max(0L,System.nanoTime()-startNanos);
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
        if(tracked==null || result==null || !VERF_ENABLED || !DETAIL_ENABLED)return;
        String signature=result.status()+":"+result.reason();
        if(!signature.equals(tracked.lastClearanceStatus)
                || now-tracked.lastClearanceStatusTick>=VERF_SAMPLE_TICKS) {
            { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-WORLD-CLEARANCE] serverTick={} pair={}/{} status={} checked={} total={} " +
                    "reason={} mutation=OFF",now,pair.a().stableKey(),pair.b().stableKey(),
                    result.status(),result.checked(),result.total(),result.reason()); }
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
        { if(RogTrace.legacySlot(LogSelection.Channel.CONTACT)) log.info("[PDD-VERF-R4] nativeTick={} serverTick={} pair={}/{} reason={} detail={} " +
                "sourceRecords={} sourcePoints={} candidateCells={} failedPointIndex={} " +
                "tickBudgetBefore={} tickBudgetAfter={} sweptBounds={} credited=false mutation=OFF",
                nativeTick,now,pair.a().stableKey(),pair.b().stableKey(),probe.status(),probe.detail(),
                records,probe.points(),probe.candidateCells(),probe.failedPoint(),before,after,swept); }
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

    private static boolean localRegionGap(MinecraftServer server,Pair pair,DirectionalContactRegions.Track track,
                                          Map<String,Map<String,LivePlot>> bounds,int[] budget){
        var f=track.footprint;if(f==null)return false;
        String other=f.owner().equals(pair.a().stableKey())?pair.b().stableKey():pair.a().stableKey();
        var plot=bounds.getOrDefault(pair.dimension(),Map.of()).get(other);
        if(plot==null||!DcrtContactBatch.positiveWorldGap(f,plot.bounds())||budget[0]<f.targets().size())return false;
        for(var target:f.targets()){
            budget[0]--;
            try{
                var xyz=target.localBlock().split(",",-1);if(xyz.length!=3)return false;
                var pos=new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]));
                var checked=OwnerResolver.auditNative(server,pair.dimension(),f.owner(),pos,null);
                if(!checked.verified()||!checked.fullWorldCollisionCube()||!target.fingerprint().equals(checked.key().fingerprint()))return false;
            }catch(RuntimeException invalid){return false;}
        }
        return true;
    }
    private static Box box(MinecraftServer server,String owner,Target target,String dimension,
                           Map<String,Map<String,LivePlot>> plotBounds,Map<String,Box> worldBlocks) {
        if(owner.startsWith("PLOT:")) {
            LivePlot plot=plotBounds.getOrDefault(dimension,Map.of()).get(owner);
            return plot==null?null:plot.bounds();
        }
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

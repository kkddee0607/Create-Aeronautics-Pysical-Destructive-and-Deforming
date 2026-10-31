package com.physicaldestroydeform;

import com.physicaldestroydeform.collision.SableFrameCapture;
import com.physicaldestroydeform.collision.CollisionFrame;
import com.physicaldestroydeform.collision.ContactPairMatcher;
import com.physicaldestroydeform.collision.OwnerResolver;
import com.physicaldestroydeform.collision.TargetGate;
import com.physicaldestroydeform.collision.ImpactMath;
import com.physicaldestroydeform.collision.ImpactLiveAudit;
import com.physicaldestroydeform.collision.ImpactEpisodeLedger;
import com.physicaldestroydeform.collision.ImpactContinuityGate;
import com.physicaldestroydeform.collision.ImpactFaceEstimate;
import com.physicaldestroydeform.collision.NativePlotDirection;
import com.physicaldestroydeform.collision.FrameMath;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import com.physicaldestroydeform.diagnostics.PddDiagnostics;
import com.physicaldestroydeform.diagnostics.AcceptanceReport;
import com.physicaldestroydeform.diagnostics.RawObservationDispatcher;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.diagnostics.SableContactQueue;
import com.physicaldestroydeform.diagnostics.WhyRejectDiagnostics;
import com.physicaldestroydeform.diagnostics.RawObservation;
import com.physicaldestroydeform.material.MaterialRegistry;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Universal collision capture only: no unverified coordinate inference or world mutation. */
@Mod(PhysicalDestroyDeform.MOD_ID)
public final class PhysicalDestroyDeform {
    public static final String MOD_ID = "physical_destroy_deform";
    public static final String BUILD_VERSION = "0.3.0-moreRog";
    private static final Logger LOGGER = LoggerFactory.getLogger(PhysicalDestroyDeform.class);
    private final AcceptanceReport acceptanceReport = new AcceptanceReport();
    // Diagnostics only: never affect callback return, proxy approval, or mutation.
    private final boolean whyRejectEnabled = Boolean.parseBoolean(System.getProperty("pdd.whyReject.enabled","true"));
    private final WhyRejectDiagnostics whyReject = new WhyRejectDiagnostics();
    private final boolean moreRogEnabled=Boolean.parseBoolean(System.getProperty("pdd.moreRog.enabled","false"));
    private final boolean debugCollisions = Boolean.parseBoolean(System.getProperty("pdd.debugCollisions", "true"));
    private long lastTotal, lastDropped, lastPolls, lastMalformed;
    private long lastCallbackTotal, lastCallbackDropped, lastCaptureErrors;
    private int ticks;
    private long verifiedPairs, worldPlotPairs, plotPlotPairs, worldWorldPairs;
    private long unpairedContacts, invalidContacts, ambiguousContacts;
    private long targetsVerifiedBoth, targetsUnresolved, geometryUnresolved, geometryDiagnostic;
    private long verifiedWorldPlot, verifiedPlotPlot;
    private final Map<TargetGate.Outcome, Long> targetOutcomes = new EnumMap<>(TargetGate.Outcome.class);
    private final Map<OwnerResolver.Status, Long> hitFailures = new EnumMap<>(OwnerResolver.Status.class);
    private final Map<OwnerResolver.Status, Long> otherFailures = new EnumMap<>(OwnerResolver.Status.class);
    private final Map<String, Long> geometryReasons = new HashMap<>();
    private final Map<ImpactMath.Reason, Long> impactReasons = new EnumMap<>(ImpactMath.Reason.class);
    private long evaluatedEpisodes, uniqueEpisodeTargets, noOtherCallbacks, approvedEpisodes;
    private long nativePlotAccepted,nativePlotRejected,nativePlotAcceptedBudgets;
    private final Map<NativePlotDirection.Reject,Long> nativePlotRejectionCauses =
            new EnumMap<>(NativePlotDirection.Reject.class);
    private long seenNativeDropped,seenNativeMalformed;
    // P-16 requires explicit opt-in plus both numeric parameters; nothing is silently calibrated.
    private final ImpactMath.ProxyPolicy proxyPolicy = ImpactMath.ProxyPolicy.fromSystemProperties();
    private final ImpactContinuityGate continuityGate = new ImpactContinuityGate();
    private ImpactMath.Result sampleImpactResult;
    private Sample sampleApproved;
    private ImpactMath.Result sampleApprovedResult;
    private record Sample(RawObservation observation, OwnerResolver.Pair ownership,
                          TargetGate.Outcome outcome) {}
    private Sample sampleWorldPlot, sampleStone, samplePlotPlot, sampleOriginal;
    private Sample sampleApprovedPlot;
    private ImpactMath.Result sampleApprovedPlotResult;
    private FrameMath.Vec sampleApprovedPlotNormal;
    private Sample sampleBelt, sampleFragile, sampleUnresolvedTarget, sampleUnresolvedGeometry, sampleRotated, sampleImpactInput;
    private NativeCollisionTelemetry.Contact sampleNative;

    public PhysicalDestroyDeform() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        LOGGER.info("[PDD-DIAG] INIT modId={} version={} revision=IDS-1.0.3-G-MATH-01-MOREROG status=LOADED capture=ALWAYS_ON logging={}", MOD_ID, BUILD_VERSION, debugCollisions);
        PddDiagnostics.runModelSelfTest(LOGGER);
        LOGGER.info("[PDD-DIAG] RUNTIME_INTEGRATION observation=UNIVERSAL_BAKERY_DECORATOR_PLUS_NATIVE_EFFECT_FEED " +
                "worldMutation=DISABLED impactMath=EXACT_AND_PROXY_GATED callbackFrame=UNVERIFIED " +
                "validation=LIVE_OWNER_BLOCK_AUDIT_GEOMETRY_INDEPENDENT nativePlotNormals=POST_EFFECT_TICK_GATED pairMatcher=DIAGNOSTIC_ONLY targetGate=LIVE_BLOCK_ONLY");
        LOGGER.info("[PDD-WHY-REJECT] phase=CONFIG enabled={} episodeLineLimitPerTick={} pairTypeLimit={} perEpisodeTargetLimit={} " +
                "interpretation=CALLBACK_PAIR_NOT_SOLVER_MANIFOLD worldMutation=OFF",
                whyRejectEnabled,WhyRejectDiagnostics.MAX_EPISODE_LINES_PER_TICK,
                WhyRejectDiagnostics.MAX_PAIR_TYPES,WhyRejectDiagnostics.MAX_TARGET_PAIRS_PER_EPISODE);
        LOGGER.info("[PDD-MORE-ROG] phase=CONFIG enabled={} method=READ_ONLY_ROTATION_AND_NATIVE_PAIR_NORMAL_AUDIT " +
                "manifoldId=UNAVAILABLE nativeEffectSubstep=UNAVAILABLE worldMutation=OFF",moreRogEnabled);
        LOGGER.info("[PDD-G-MATH-01] POLICY proxyEnabled={} factor={} stepCap={} " +
                        "note=NO_HIDDEN_COEFFICIENTS mutation=DISABLED",
                proxyPolicy.enabled(),proxyPolicy.factor(),proxyPolicy.availableStepCap());
    }
    private void onServerStarted(ServerStartedEvent event) {
        MaterialRegistry.rebuild(event.getServer(), LOGGER);
        LOGGER.info("[PDD-DIAG] SABLE_BRIDGE source=RapierVoxelColliderBakery.buildPhysicsDataForBlock " +
                "mode=ORIGINAL_PRESERVING_DECORATOR status=CONFIGURED_NOT_YET_PROVEN");
    }
    private void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) MaterialRegistry.rebuild(event.getPlayerList().getServer(), LOGGER);
    }
    private void onServerStopped(ServerStoppedEvent event) {
        MaterialRegistry.clear();
        NativeCollisionTelemetry.drain();
        SableContactQueue.drain();
        SableFrameCapture.clear();
        continuityGate.clear();
        whyReject.clear();
        acceptanceReport.close();
    }
    private void onServerTick(ServerTickEvent.Post event) {
        // Always drain: diagnostics and native callbacks must never be governed by verbosity.
        var callbacks = SableContactQueue.drain();
        var nativeFeed = NativeCollisionTelemetry.drain();
        boolean completeNativeTick=NativeCollisionTelemetry.dropped()==seenNativeDropped
                && NativeCollisionTelemetry.malformed()==seenNativeMalformed;
        seenNativeDropped=NativeCollisionTelemetry.dropped();
        seenNativeMalformed=NativeCollisionTelemetry.malformed();
        var plotDirections = NativePlotDirection.prepare(
                completeNativeTick?nativeFeed:java.util.List.of(),SableFrameCapture.drainPreHistory());
        nativePlotAccepted+=plotDirections.acceptedPairs();
        nativePlotRejected+=plotDirections.rejectedPairs();
        plotDirections.rejectedReasons().forEach((why,count)->
                nativePlotRejectionCauses.merge(why,count,Long::sum));

        // Physics-thread snapshots propose owners; ONLY the server-tick Sable
        // container/block lookup may verify their actual storage identity.
        var evidence = callbacks.stream().map(RawObservation::frameEvidence).toList();
        var pairs = ContactPairMatcher.match(evidence);
        var normalized = callbacks;
        for (var match : pairs.matches()) {
            verifiedPairs++;
            var a = match.firstEvidence();
            var b = match.secondEvidence();
            if (a.hit().space() == CollisionFrame.Space.STATIC_WORLD
                    && b.hit().space() == CollisionFrame.Space.STATIC_WORLD) worldWorldPairs++;
            else if (a.hit().space() == CollisionFrame.Space.SABLE_PLOT
                    && b.hit().space() == CollisionFrame.Space.SABLE_PLOT) plotPlotPairs++;
            else worldPlotPairs++;
        }
        unpairedContacts += pairs.unpaired();
        invalidContacts += pairs.invalid();
        ambiguousContacts += pairs.ambiguous();

        var ownerCache = new HashMap<OwnerResolver.CacheKey, OwnerResolver.Check>();
        var budgetEpisodes = new ImpactEpisodeLedger();
        var budgetCandidates = new HashMap<ImpactEpisodeLedger.Key,Sample>();
        for (RawObservation c: normalized) {
            var e = c.frameEvidence();
            OwnerResolver.Pair audited = OwnerResolver.audit(event.getServer(),e,c.blockId(),ownerCache);
            // Target quality is a function of LIVE owners/blocks only, not geometry
            // or a forced one-to-one match between callback contact points.
            TargetGate.Outcome outcome = TargetGate.evaluate(c.hasOther(),
                    audited.hit().verified(), audited.other().verified());
            targetOutcomes.merge(outcome, 1L, Long::sum);
            if (!audited.hit().verified()) hitFailures.merge(audited.hit().status(), 1L, Long::sum);
            if (c.hasOther() && !audited.other().verified())
                otherFailures.merge(audited.other().status(), 1L, Long::sum);
            if (outcome == TargetGate.Outcome.BOTH_VERIFIED) {
                targetsVerifiedBoth++;
                if(e.hit().space()==CollisionFrame.Space.SABLE_PLOT
                        && e.other().space()==CollisionFrame.Space.SABLE_PLOT) verifiedPlotPlot++;
                else if(e.hit().space()!=e.other().space()) verifiedWorldPlot++;
            } else if(outcome==TargetGate.Outcome.NO_OTHER)noOtherCallbacks++;
            else targetsUnresolved++;
            if(outcome==TargetGate.Outcome.BOTH_VERIFIED) {
                try {
                    var key=ImpactEpisodeLedger.Key.of(e.dimension(),e.epoch(),
                            ownerId(e.hit()),ownerId(e.other()));
                    var episode=budgetEpisodes.observe(key,audited.hit().key().toString(),
                            audited.other().key().toString());
                    // Once one candidate owns this episode budget, further
                    // callbacks still contribute target identities, not more math.
                    var candidate=episode.decision()!=null && episode.decision().approved()
                            ? ImpactMath.Result.reject(ImpactMath.Reason.DUPLICATE_EPISODE)
                            : ImpactLiveAudit.evaluate(e,true,
                                    audited.hit().fullWorldCollisionCube(),proxyPolicy,plotDirections);
                    if(whyRejectEnabled) {
                        String normalStatus=e.hit().plot() && e.other().plot()
                                ? plotDirections.explain(e)
                                : "WORLD_FACE:"+ImpactFaceEstimate.fromCallback(e,
                                    audited.hit().fullWorldCollisionCube()).reason();
                        String hitRuntime=e.hitBody()==null?"STATIC_WORLD":Integer.toString(e.hitBody().runtimeId());
                        String otherRuntime=e.otherBody()==null?"STATIC_WORLD":Integer.toString(e.otherBody().runtimeId());
                        String canonicalRuntimeIds=key.firstOwner().equals(ownerId(e.hit()))
                                ?hitRuntime+"/"+otherRuntime:otherRuntime+"/"+hitRuntime;
                        whyReject.observe(key,e.serverTick(),canonicalRuntimeIds,
                                whyTarget(audited.hit().key()),whyTarget(audited.other().key()),
                                candidate.reason(),normalStatus,()->whyPreSolverSample(e,
                                        plotDirections,audited.hit().fullWorldCollisionCube()),
                                moreRogEnabled?()->moreRogSample(e,plotDirections,
                                        audited.hit().fullWorldCollisionCube()):null);
                    }
                    episode.approveOnce(candidate);
                    if(debugCollisions && candidate.approved())
                        budgetCandidates.putIfAbsent(key,new Sample(c,audited,outcome));
                } catch (IllegalArgumentException invalidGrouping) {
                    impactReasons.merge(ImpactMath.Reason.OWNER_UNVERIFIED,1L,Long::sum);
                }
            }
            if(whyRejectEnabled && outcome!=TargetGate.Outcome.BOTH_VERIFIED)
                whyReject.unverified(c.hasOther(),audited.hit().status().name(),audited.other().status().name(),c.blockId());
            boolean unresolvedGeometry = e.quality()==CollisionFrame.Quality.INVALID
                    || !"HIT_FRAME_HYPOTHESIS_PLAUSIBLE".equals(e.reason());
            geometryReasons.merge(e.reason(), 1L, Long::sum);
            if (unresolvedGeometry) geometryUnresolved++;
            else geometryDiagnostic++;
            if (debugCollisions) {
                Sample sample=new Sample(c,audited,outcome);
                boolean both = outcome == TargetGate.Outcome.BOTH_VERIFIED;
                boolean plotPlot = both && e.hit().space()==CollisionFrame.Space.SABLE_PLOT
                        && e.other().space()==CollisionFrame.Space.SABLE_PLOT;
                boolean worldPlot = both && e.hit().space()!=e.other().space();
                if(sampleWorldPlot==null && worldPlot) sampleWorldPlot=sample;
                if(sampleStone==null && worldPlot && isStone(c, audited)) sampleStone=sample;
                if(samplePlotPlot==null && plotPlot) samplePlotPlot=sample;
                if(sampleOriginal==null && c.hadOriginal()) sampleOriginal=sample;
                if(sampleBelt==null && c.callbackType().contains("Belt")) sampleBelt=sample;
                if(sampleFragile==null && c.callbackType().contains("Fragile")) sampleFragile=sample;
                if(sampleUnresolvedTarget==null && !both) sampleUnresolvedTarget=sample;
                if(sampleUnresolvedGeometry==null && unresolvedGeometry) sampleUnresolvedGeometry=sample;
                if(sampleRotated==null && both && isRotated(e)) sampleRotated=sample;
                if(sampleImpactInput==null && both && (
                        (e.hitBody()!=null && e.hitBody().impactBody()!=null) ||
                        (e.otherBody()!=null && e.otherBody().impactBody()!=null)))
                    sampleImpactInput=sample;
            }
        }
        // Do not turn callbacks into budgets. One episode per pair/physics step,
        // independent of how many callback blocks or directions reported it.
        // Deterministic order matters when multiple targets share an explicit step cap.
        var ordered=budgetEpisodes.entries().entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<ImpactEpisodeLedger.Key,ImpactEpisodeLedger.Episode> x)->x.getKey().dimension())
                        .thenComparingLong(x->x.getKey().epoch())
                        .thenComparing(x->x.getKey().firstOwner())
                        .thenComparing(x->x.getKey().secondOwner()))
                .toList();
        var remainingPerStep=new HashMap<String,Double>();
        int episodeLines=0;
        int focusEpisodeLines=0;
        for(var entry:ordered) {
            evaluatedEpisodes++;
            var episode=entry.getValue();
            uniqueEpisodeTargets+=episode.uniqueTargets();
            var decision=episode.decision();
            if(decision!=null) {
                var key=entry.getKey();
                // Check shared step capacity BEFORE minting persistent contact credit.
                String step=key.dimension()+"#"+key.epoch();
                double remain=remainingPerStep.getOrDefault(step,proxyPolicy.availableStepCap());
                if(decision.approved() && decision.kind()==ImpactMath.Kind.CONSERVATIVE_PROXY
                        && (!Double.isFinite(remain)||remain<=0))
                    decision=ImpactMath.Result.reject(ImpactMath.Reason.NO_APPROVED_BUDGET);
                // Sustained contact across solver steps/ticks cannot mint repeated credit.
                decision=continuityGate.observe(ImpactContinuityGate.Pair.from(key),
                        event.getServer().getTickCount(),decision);
                if(decision.approved() && decision.kind()==ImpactMath.Kind.CONSERVATIVE_PROXY) {
                    double allocated=Math.min(remain,decision.budget());
                    remainingPerStep.put(step,Math.max(0,remain-allocated));
                    decision=new ImpactMath.Result(decision.kind(),decision.reason(),allocated,
                            decision.closingSpeed(),decision.effectiveMass(),decision.source());
                }
                if(debugCollisions && sampleApproved==null && decision.approved()) {
                    sampleApproved=budgetCandidates.get(key);
                    sampleApprovedResult=decision;
                }
                if(whyRejectEnabled) {
                    var trace=whyReject.finish(key,decision);
                    // All owner episode outcomes are counted; log size is bounded.
                    // Suppressed records remain visible in the cumulative summary.
                    boolean standard=episodeLines++<WhyRejectDiagnostics.MAX_EPISODE_LINES_PER_TICK;
                    boolean reserved=!standard && trace.focusPhysical()
                            && focusEpisodeLines++<WhyRejectDiagnostics.EXTRA_FOCUS_EPISODE_LINES_PER_TICK;
                    if(standard||reserved) {
                        LOGGER.info("[PDD-WHY-REJECT] phase={} dimension={} physicsEpoch={} sourceServerTick={} nativeRuntimeIdsCanonicalOwners={} ownerA={} ownerB={} " +
                                        "callbackPairs={} loggedUniqueBlockPairs={} omittedBlockPairSamples={} " +
                                        "finalReason={} budgetKind={} closingSpeed={} effectiveMass={} budget={} source={} " +
                                        "targetPairEvidence=CALLBACK_PAIR_NOT_EXACT_MANIFOLD pairs={} worldMutation=OFF",
                                reserved?"FOCUS_EPISODE":"EPISODE",
                                key.dimension(),key.epoch(),trace.serverTick(),trace.nativeRuntimeIds(),
                                key.firstOwner(),key.secondOwner(),
                                trace.callbacks(),trace.distinctPairs(),trace.unlistedPairs(),
                                decision.reason(),decision.kind(),decision.closingSpeed(),
                                decision.effectiveMass(),decision.budget(),decision.source(),trace.pairs());
                        if(moreRogEnabled)
                            LOGGER.info("[PDD-MORE-ROG] phase=EPISODE_EVIDENCE dimension={} physicsEpoch={} sourceServerTick={} " +
                                            "ownerA={} ownerB={} finalReason={} focusPhysical={} evidence={} worldMutation=OFF",
                                    key.dimension(),key.epoch(),trace.serverTick(),
                                    key.firstOwner(),key.secondOwner(),decision.reason(),
                                    trace.focusPhysical(),trace.moreRogEvidence());
                    } else whyReject.suppressEpisodeLine();
                }
                impactReasons.merge(decision.reason(),1L,Long::sum);
                if(decision.approved()) {
                    approvedEpisodes++;
                    if(decision.source().startsWith("NATIVE_PLOT_PAIR_")) {
                        nativePlotAcceptedBudgets++;
                        if(debugCollisions && sampleApprovedPlot==null) {
                            Sample sourceSample=budgetCandidates.get(key);
                            if(sourceSample!=null) {
                                sampleApprovedPlot=sourceSample;
                                sampleApprovedPlotResult=decision;
                                sampleApprovedPlotNormal=plotDirections.approvedNormal(
                                        sourceSample.observation().frameEvidence());
                            }
                        }
                    }
                }
                if(sampleImpactResult==null || decision.approved()
                        || (sampleImpactResult.reason()==ImpactMath.Reason.MASS_INVALID
                        && decision.reason()!=ImpactMath.Reason.MASS_INVALID))sampleImpactResult=decision;
            }
        }
        if(ticks%100==0)continuityGate.prune(event.getServer().getTickCount());
        // 0.3.0 builds an impact budget model; world mutation belongs to later milestones.
        RawObservationDispatcher.dispatch(normalized);
        if (debugCollisions && sampleNative == null && !nativeFeed.isEmpty())
            sampleNative = nativeFeed.get(0);
        if (++ticks % 100 != 0) return;

        acceptanceReport.log(LOGGER);
        LOGGER.info("[PDD-G-COL-02] phase=SUMMARY {} reciprocalOwnerPairs={} " +
                        "worldPlotPairs={} plotPlotPairs={} worldWorldPairs={} " +
                        "unpairedContacts={} invalidPairCandidates={} pairAmbiguous={} " +
                        "targetVerifiedBoth={} targetUnresolved={} verifiedWorldPlot={} verifiedPlotPlot={} " +
                        "geometryDiagnostic={} geometryUnresolved={} noOtherCallbacks={} targetGate=LIVE_BLOCK_ONLY " +
                        "pairMatcher=DIAGNOSTIC_ONLY geometryGate=NON_BLOCKING_DIAGNOSTIC " +
                        "gateStatus=EVIDENCE_ONLY tolerance=DIAGNOSTIC_ONLY mutation=DISABLED",
                SableFrameCapture.summary(),verifiedPairs,worldPlotPairs,plotPlotPairs,
                worldWorldPairs,unpairedContacts,invalidContacts,ambiguousContacts,
                targetsVerifiedBoth,targetsUnresolved,verifiedWorldPlot,verifiedPlotPlot,
                geometryDiagnostic,geometryUnresolved,noOtherCallbacks);
        LOGGER.info("[PDD-G-COL-02] phase=QUALITY targetOutcomes={} hitFailures={} " +
                "otherFailures={} geometryReasons={} mutation=DISABLED",
                targetOutcomes,hitFailures,otherFailures,geometryReasons);
        if(whyRejectEnabled) {
            LOGGER.info("[PDD-WHY-REJECT] phase=SUMMARY verifiedCallbackPairs={} ownerEpisodes={} approvedOwnerEpisodes={} " +
                            "chainPlotStone={} reinforcedPlotStone={} noOtherCallbacks={} unverifiedCallbacks={} " +
                            "unverifiedReasons={} pairTypes={} pairTypeOverflow={} unlistedBlockPairSamples={} " +
                            "episodeLogLinesSuppressed={} nativeDroppedTotal={} nativeMalformedTotal={} " +
                            "callbackDroppedTotal={} captureErrorsTotal={} interpretation=CALLBACK_PAIRS_NOT_EXACT_CONTACTS",
                    whyReject.observedCallbacks(),whyReject.allEpisodes(),whyReject.allApproved(),
                    whyReject.focusCount("chainPlotStone"),whyReject.focusCount("reinforcedPlotStone"),
                    whyReject.missingOther(),whyReject.unverifiedTargets(),whyReject.unverifiedReasons(),
                    whyReject.pairTypeCount(),whyReject.unlistedCategories(),whyReject.unlistedPairSamples(),
                    whyReject.episodeLinesSuppressed(),NativeCollisionTelemetry.dropped(),
                    NativeCollisionTelemetry.malformed(),SableContactQueue.dropped(),SableContactQueue.captureErrors());
            // Focus counts are exhaustive even when the episode log is rate-limited.
            LOGGER.info("[PDD-WHY-REJECT] phase=RAW_UNVERIFIED_HIT_TYPES top={} " +
                            "interpretation=RAW_HIT_ONLY_NO_VERIFIED_BLOCK_PAIR",
                    whyReject.unverifiedHitBlockTypes(WhyRejectDiagnostics.MAX_SUMMARY_TYPES));
            for(String type:whyReject.focusTypes())
                LOGGER.info("[PDD-WHY-REJECT] phase=FOCUS_PAIR_TYPE {} confidence=VERIFIED_CALLBACK_TARGETS_ONLY",type);
            for(String type:whyReject.leadingTypes(WhyRejectDiagnostics.MAX_SUMMARY_TYPES))
                LOGGER.info("[PDD-WHY-REJECT] phase=TOP_PAIR_TYPE {} confidence=VERIFIED_CALLBACK_TARGETS_ONLY",type);
        }
        LOGGER.info("[PDD-G-MATH-01] phase=NATIVE_PLOT_BRIDGE contactsTotal={} acceptedPairsTotal={} rejectedPairsTotal={} nativeApprovedEpisodesTotal={} pairRejections={} " +
                        "gate=ALL_PRE_SUBSTEPS_AND_OPPOSING_NORMALS nativePhase=POST_TICK_EFFECT_ONLY mutation=OFF",
                NativeCollisionTelemetry.total(),nativePlotAccepted,nativePlotRejected,nativePlotAcceptedBudgets,
                nativePlotRejectionCauses);
        LOGGER.info("[PDD-G-MATH-01] phase=SUMMARY episodes={} uniqueTargetIncidences={} approvedEpisodes={} " +
                        "reasons={} exactOrProxyWorldMutation=DISABLED nativeEffectScalarAuthority=DIAGNOSTIC_ONLY nativeEffectNormals=STRICT_PLOT_PROXY_DIRECTION_ONLY " +
                        "budgetApproval=EVIDENCE_GATED",
                evaluatedEpisodes,uniqueEpisodeTargets,approvedEpisodes,impactReasons);
        if(sampleImpactResult!=null)LOGGER.info(
                "[PDD-G-MATH-01] phase=SAMPLE inputQuality={} budgetKind={} " +
                        "reason={} approvedBudget={} closingSpeed={} effectiveMass={} source={} mutation=OFF",
                sampleImpactResult.approved()
                        ? (sampleImpactResult.source().startsWith("NATIVE_PLOT_PAIR_")
                            ? "EQUIVALENT_VALIDATED_TICK_WIDE_MIN" : "PRE_SOLVER_VALIDATED")
                        : "DIAGNOSTIC_ONLY",
                sampleImpactResult.kind(),sampleImpactResult.reason(),
                sampleImpactResult.budget(),sampleImpactResult.closingSpeed(),
                sampleImpactResult.effectiveMass(),sampleImpactResult.source());
        sampleImpactResult=null;
        long total = NativeCollisionTelemetry.total();
        long dropped = NativeCollisionTelemetry.dropped();
        long polls = NativeCollisionTelemetry.polls();
        long malformed = NativeCollisionTelemetry.malformed();
        long callbackTotal = SableContactQueue.total();
        long callbackDropped = SableContactQueue.dropped();
        long captureErrors = SableContactQueue.captureErrors();
        LOGGER.info("[PDD-COLLISION] summary ticks=100 nativeCalls={} nativeContacts={} samplesDropped={} malformedArrays={} status={}",
                polls-lastPolls,total-lastTotal,dropped-lastDropped,malformed-lastMalformed,
                polls > lastPolls ? "HOOK_ACTIVE" : "HOOK_NOT_OBSERVED");
        LOGGER.info("[PDD-RAW] summary ticks=100 callbackInvocations={} rawDropped={} captureErrors={} " +
                        "wrappedWithOriginal={} wrappedNull={} status={} damage=DISABLED",
                callbackTotal-lastCallbackTotal,callbackDropped-lastCallbackDropped,captureErrors-lastCaptureErrors,
                SableContactQueue.wrappedOriginal(),SableContactQueue.wrappedNull(),
                callbackTotal > lastCallbackTotal ? "CALLBACK_INVOKED" : "NO_CALLBACK_OBSERVED");
        lastTotal=total; lastDropped=dropped; lastPolls=polls; lastMalformed=malformed;
        lastCallbackTotal=callbackTotal; lastCallbackDropped=callbackDropped; lastCaptureErrors=captureErrors;
        if (debugCollisions) {
            logSample("WORLD_PLOT", sampleWorldPlot);
            logSample("STONE_WALL", sampleStone);
            logSample("PLOT_PLOT", samplePlotPlot);
            logSample("ORIGINAL_CALLBACK", sampleOriginal);
            logSample("BELT_CALLBACK", sampleBelt);
            logSample("FRAGILE_CALLBACK", sampleFragile);
            logSample("UNRESOLVED_TARGET", sampleUnresolvedTarget);
            logSample("UNRESOLVED_GEOMETRY", sampleUnresolvedGeometry);
            logSample("ROTATED_PLOT", sampleRotated);
            if(sampleImpactInput!=null)logImpactInput(sampleImpactInput);
            if(sampleApproved!=null && sampleApprovedResult!=null)
                logApproved(sampleApproved,sampleApprovedResult,
                        sampleApprovedResult.source().startsWith("NATIVE_PLOT_PAIR_")
                                ? plotDirections.approvedNormal(sampleApproved.observation().frameEvidence()) : null);
            if(sampleApprovedPlot!=null && sampleApprovedPlotResult!=null && sampleApprovedPlot!=sampleApproved)
                logApproved(sampleApprovedPlot,sampleApprovedPlotResult,sampleApprovedPlotNormal);
            if (sampleNative!=null) logNative(sampleNative);
        }
        sampleWorldPlot=null; sampleStone=null; samplePlotPlot=null; sampleOriginal=null;
        sampleBelt=null; sampleFragile=null; sampleUnresolvedTarget=null;
        sampleUnresolvedGeometry=null; sampleRotated=null; sampleImpactInput=null;
        sampleApproved=null; sampleApprovedResult=null; sampleNative=null;
        sampleApprovedPlot=null; sampleApprovedPlotResult=null; sampleApprovedPlotNormal=null;
    }

    /** Reads existing evidence only. Rotation uses a voxel-corner upper bound;
     * native collision effect records do not expose manifold IDs or substeps.
     * This must never change G-MATH-01 budgets or source callback behavior. */
    private String moreRogSample(CollisionFrame.Evidence e,
                                        NativePlotDirection.Prepared directions,
                                        boolean fullWorldCube) {
        if(e.hit().plot()&&e.other().plot())
            return "path=PLOT_PLOT,nativePair={"+directions.moreRogNativeEvidence(e)
                    +"},substeps={"+directions.moreRogSubsteps(e,
                            proxyPolicy)+"}";
        var normal=ImpactFaceEstimate.fromCallback(e,fullWorldCube).normalBtoA();
        if(normal==null)return "path=WORLD_PLOT,rotation=NOT_EVALUABLE_WITHOUT_VERIFIED_NORMAL";
        try {
            return "path=WORLD_PLOT,rotation={"+ImpactLiveAudit.rotationEvidence(e,normal).explain()+"}";
        }catch(IllegalArgumentException invalid) {
            return "path=WORLD_PLOT,rotation=INVALID_INPUT";
        }
    }

    private static String whyPreSolverSample(CollisionFrame.Evidence e,
                                              NativePlotDirection.Prepared directions,
                                              boolean fullWorldCube) {
        var normal=e.hit().plot()&&e.other().plot()
                ?directions.approvedNormal(e)
                :ImpactFaceEstimate.fromCallback(e,fullWorldCube).normalBtoA();
        return "normalBtoA="+normal+",hit="+whyBodySample(e.hitBody())
                +",other="+whyBodySample(e.otherBody())
                +",callbackGeometryQuality="+e.quality()+",callbackGeometryReason="+e.reason();
    }
    private static String whyBodySample(CollisionFrame.Body captured) {
        if(captured==null)return "STATIC_WORLD_OR_UNAVAILABLE";
        var body=captured.impactBody();
        return body==null?"MASS_OR_VELOCITY_UNAVAILABLE"
                :"(mass="+body.mass()+",linearWorld="+body.linearWorld()
                    +",angularWorld="+body.angularWorld()+")";
    }

    private static WhyRejectDiagnostics.Target whyTarget(OwnerResolver.TargetKey target) {
        if(target==null)throw new IllegalArgumentException("missing verified target");
        String fingerprint=target.fingerprint();
        int separator=fingerprint.indexOf('|');
        String blockId=separator<0?fingerprint:fingerprint.substring(0,separator);
        return new WhyRejectDiagnostics.Target(target.space().name(),
                target.stablePlotId()==null?null:target.stablePlotId().toString(),
                target.x(),target.y(),target.z(),blockId);
    }

    private static String ownerId(CollisionFrame.BlockTarget target) {
        if(target.space()==CollisionFrame.Space.STATIC_WORLD)return "WORLD";
        if(target.space()==CollisionFrame.Space.SABLE_PLOT && target.stablePlotId()!=null)
            return "PLOT:"+target.stablePlotId();
        throw new IllegalArgumentException("unresolved owner identity");
    }

    private static boolean isStone(RawObservation observation, OwnerResolver.Pair owners) {
        return observation.blockId().equals("minecraft:stone")
                || (owners.other().key()!=null
                    && owners.other().key().fingerprint().startsWith("minecraft:stone|"));
    }

    private static boolean isRotated(CollisionFrame.Evidence evidence) {
        return rotated(evidence.hitBody()) || rotated(evidence.otherBody());
    }

    private static boolean rotated(CollisionFrame.Body body) {
        if (body==null || body.pose()==null) return false;
        var pose=body.pose();
        // Quaternion is diagnostic, not a geometry/owner validation shortcut.
        return Math.abs(pose.qx())+Math.abs(pose.qy())+Math.abs(pose.qz())>1e-4
                || Math.abs(Math.abs(pose.qw())-1)>1e-4;
    }

    private static void logSample(String scenario, Sample sample) {
        if(sample==null)return;
        var c=sample.observation();
        var e=c.frameEvidence();
        LOGGER.info("[PDD-RAW] phase=LIVE_DATA scenario={} source={} block={} original={} callbackType={} " +
                        "hit=({},{},{}) otherPresent={} other=({},{},{}) " +
                        "impact=({},{},{}) impactFrame={} ownerSpace={} signedVelocityRaw={} removed={} mutation=OFF",
                scenario,c.source(),c.blockId(),c.hadOriginal(),c.callbackType(),
                c.hitX(),c.hitY(),c.hitZ(),c.hasOther(),c.otherX(),c.otherY(),c.otherZ(),
                c.impactX(),c.impactY(),c.impactZ(),c.coordinateFrame(),c.ownerSpace(),
                c.signedVelocity(),c.removeCollision());
        LOGGER.info("[PDD-G-COL-02] phase=SAMPLE scenario={} dimension={} epoch={} tick={} sourcePhase={} targetOutcome={} " +
                        "hitOwner={} otherOwner={} rawImpact={} worldFromHitHypothesis={} otherLocalHypothesis={} " +
                        "geometryQuality={} geometryReason={} " +
                        "hitTargetQuality={} hitTargetKey={} hitTargetReason={} " +
                        "otherTargetQuality={} otherTargetKey={} otherTargetReason={} " +
                        "originalCallbackInvoked={} originalResultPreserved=true mutation=OFF",
                scenario,e.dimension(),e.epoch(),e.serverTick(),e.phase(),sample.outcome(),e.hit(),e.other(),
                e.contactRaw(),e.contactWorldHypothesis(),e.otherContactLocalHypothesis(),
                e.quality(),e.reason(),
                sample.ownership().hit().status(),sample.ownership().hit().key(),sample.ownership().hit().detail(),
                sample.ownership().other().status(),sample.ownership().other().key(),sample.ownership().other().detail(),
                c.hadOriginal());
    }

    private static void logImpactInput(Sample sample) {
        var e=sample.observation().frameEvidence();
        var a=ImpactLiveAudit.body(e.hit(),e.hitBody());
        var b=ImpactLiveAudit.body(e.other(),e.otherBody());
        var face=ImpactFaceEstimate.fromCallback(e,sample.ownership().hit().fullWorldCollisionCube());
        LOGGER.info("[PDD-G-MATH-01] phase=INPUT epoch={} serverTick={} source=PRE_SOLVER " +
                        "hitOwner={} otherOwner={} hitMass={} hitInverseMass={} " +
                        "hitLinear={} hitAngular={} hitCenter={} otherMass={} otherInverseMass={} " +
                        "otherLinear={} otherAngular={} otherCenter={} contactFrame=UNVERIFIED " +
                        "normalEvidence={} normalBtoA={} quality={} mutation=OFF",
                e.epoch(),e.serverTick(),e.hit(),e.other(),
                a==null?"UNAVAILABLE":a.mass(),a==null?"UNAVAILABLE":a.inverseMass(),
                a==null?"UNAVAILABLE":a.linearWorld(),a==null?"UNAVAILABLE":a.angularWorld(),
                a==null?"UNAVAILABLE":a.centerWorld(),
                b==null?"UNAVAILABLE":b.mass(),b==null?"UNAVAILABLE":b.inverseMass(),
                b==null?"UNAVAILABLE":b.linearWorld(),b==null?"UNAVAILABLE":b.angularWorld(),
                b==null?"UNAVAILABLE":b.centerWorld(),
                face.reason(),face.normalBtoA(),
                face.verified()?"PRE_SOLVER_FACE_ESTIMATE":"DIAGNOSTIC_ONLY");
    }

    private static void logApproved(Sample sample, ImpactMath.Result approved,FrameMath.Vec plotNormal) {
        var e=sample.observation().frameEvidence();
        var direction=ImpactFaceEstimate.fromCallback(e,
                sample.ownership().hit().fullWorldCollisionCube());
        LOGGER.info("[PDD-G-MATH-01] phase=APPROVED epoch={} dimension={} " +
                        "hitTarget={} otherTarget={} budgetKind={} source={} " +
                        "normalBtoA={} directionEvidence={} closingSpeed={} " +
                        "effectiveMass={} budget={} capScope={} " +
                        "callbackVelocity=ABSOLUTE_DIAGNOSTIC_ONLY worldMutation=OFF",
                e.epoch(),e.dimension(),sample.ownership().hit().key(),
                sample.ownership().other().key(),approved.kind(),approved.source(),
                plotNormal!=null?plotNormal:direction.normalBtoA(),
                plotNormal!=null?"NATIVE_OPPOSING_PLOT_NORMAL_ALL_PRE_SUBSTEPS":direction.reason(),
                approved.closingSpeed(),approved.effectiveMass(),approved.budget(),
                plotNormal!=null?"TICK_MIN_OVER_PRE_SUBSTEPS":"PER_PHYSICS_STEP");
    }

    private static void logNative(NativeCollisionTelemetry.Contact c) {
        LOGGER.info("[PDD-COLLISION] source=SABLE_RAPIER_NATIVE phase=LIVE_DATA " +
                        "idA={} idB={} rawImpactScalar={} " +
                        "rawVector0=({},{},{}) rawVector1=({},{},{}) " +
                        "rawVector2=({},{},{}) rawVector3=({},{},{}) frame=UNVERIFIED mutation=OFF",
                c.idA(), c.idB(), c.rawImpactScalar(),
                c.ax(), c.ay(), c.az(),c.bx(), c.by(), c.bz(),
                c.contactAX(),c.contactAY(),c.contactAZ(),
                c.contactBX(),c.contactBY(),c.contactBZ());
    }
}

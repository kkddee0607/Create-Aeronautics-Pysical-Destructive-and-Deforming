package com.physicaldestroydeform;

import com.physicaldestroydeform.collision.SableFrameCapture;
import com.physicaldestroydeform.collision.CollisionFrame;
import com.physicaldestroydeform.collision.ContactPairMatcher;
import com.physicaldestroydeform.collision.OwnerResolver;
import com.physicaldestroydeform.collision.TargetGate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import com.physicaldestroydeform.diagnostics.PddDiagnostics;
import com.physicaldestroydeform.diagnostics.AcceptanceReport;
import com.physicaldestroydeform.diagnostics.RawObservationDispatcher;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.diagnostics.SableContactQueue;
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
    public static final String BUILD_VERSION = "0.2.13";
    private static final Logger LOGGER = LoggerFactory.getLogger(PhysicalDestroyDeform.class);
    private final AcceptanceReport acceptanceReport = new AcceptanceReport();
    private final boolean debugCollisions = NativeCollisionTelemetry.ENABLED;
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
    private record Sample(RawObservation observation, OwnerResolver.Pair ownership,
                          TargetGate.Outcome outcome) {}
    private Sample sampleWorldPlot, sampleStone, samplePlotPlot, sampleOriginal;
    private Sample sampleBelt, sampleFragile, sampleUnresolvedTarget, sampleUnresolvedGeometry, sampleRotated;
    private NativeCollisionTelemetry.Contact sampleNative;

    public PhysicalDestroyDeform() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        LOGGER.info("[PDD-DIAG] INIT modId={} version={} revision=IDS-1.0.3-FIX status=LOADED capture=ALWAYS_ON logging={}", MOD_ID, BUILD_VERSION, debugCollisions);
        PddDiagnostics.runModelSelfTest(LOGGER);
        LOGGER.info("[PDD-DIAG] RUNTIME_INTEGRATION observation=UNIVERSAL_BAKERY_DECORATOR_PLUS_NATIVE_EFFECT_FEED " +
                "worldMutation=DISABLED energyCalibration=DISABLED callbackFrame=UNVERIFIED " +
                "validation=LIVE_OWNER_BLOCK_AUDIT_GEOMETRY_INDEPENDENT pairMatcher=DIAGNOSTIC_ONLY targetGate=LIVE_BLOCK_ONLY");
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
        acceptanceReport.close();
    }
    private void onServerTick(ServerTickEvent.Post event) {
        // Always drain: diagnostics and native callbacks must never be governed by verbosity.
        var callbacks = SableContactQueue.drain();
        var nativeFeed = NativeCollisionTelemetry.drain();

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
            } else targetsUnresolved++;
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
            }
        }
        // No BlockTarget or geometry result reaches a mutation adapter in 0.2.13.
        RawObservationDispatcher.dispatch(normalized);
        if (debugCollisions && sampleNative == null && !nativeFeed.isEmpty())
            sampleNative = nativeFeed.get(0);
        if (++ticks % 100 != 0) return;

        acceptanceReport.log(LOGGER);
        LOGGER.info("[PDD-G-COL-02] phase=SUMMARY {} reciprocalOwnerPairs={} " +
                        "worldPlotPairs={} plotPlotPairs={} worldWorldPairs={} " +
                        "unpairedContacts={} invalidPairCandidates={} pairAmbiguous={} " +
                        "targetVerifiedBoth={} targetUnresolved={} verifiedWorldPlot={} verifiedPlotPlot={} " +
                        "geometryDiagnostic={} geometryUnresolved={} targetGate=LIVE_BLOCK_ONLY " +
                        "pairMatcher=DIAGNOSTIC_ONLY geometryGate=NON_BLOCKING_DIAGNOSTIC " +
                        "gateStatus=EVIDENCE_ONLY tolerance=DIAGNOSTIC_ONLY mutation=DISABLED",
                SableFrameCapture.summary(),verifiedPairs,worldPlotPairs,plotPlotPairs,
                worldWorldPairs,unpairedContacts,invalidContacts,ambiguousContacts,
                targetsVerifiedBoth,targetsUnresolved,verifiedWorldPlot,verifiedPlotPlot,
                geometryDiagnostic,geometryUnresolved);
        LOGGER.info("[PDD-G-COL-02] phase=QUALITY targetOutcomes={} hitFailures={} " +
                "otherFailures={} geometryReasons={} mutation=DISABLED",
                targetOutcomes,hitFailures,otherFailures,geometryReasons);
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
            if (sampleNative!=null) logNative(sampleNative);
        }
        sampleWorldPlot=null; sampleStone=null; samplePlotPlot=null; sampleOriginal=null;
        sampleBelt=null; sampleFragile=null; sampleUnresolvedTarget=null;
        sampleUnresolvedGeometry=null; sampleRotated=null; sampleNative=null;
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
                        "hitOwner={} otherOwner={} rawImpact={} worldFromHit={} otherLocalFromWorld={} " +
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

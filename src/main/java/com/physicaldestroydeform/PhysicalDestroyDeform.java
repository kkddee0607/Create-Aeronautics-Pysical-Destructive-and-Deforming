package com.physicaldestroydeform;

import com.physicaldestroydeform.collision.SableFrameCapture;
import com.physicaldestroydeform.collision.CollisionFrame;
import com.physicaldestroydeform.collision.ContactPairMatcher;
import java.util.ArrayList;
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
    private long unpairedContacts, invalidContacts;
    // Prefer independently confirmed samples; preserve one invalid example for diagnosis.
    private RawObservation sampleFirst, sampleSecond, sampleInvalid, sampleFallback;
    private NativeCollisionTelemetry.Contact sampleNative;

    public PhysicalDestroyDeform() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        LOGGER.info("[PDD-DIAG] INIT modId={} version={} status=LOADED capture=ALWAYS_ON logging={}", MOD_ID, BUILD_VERSION, debugCollisions);
        PddDiagnostics.runModelSelfTest(LOGGER);
        LOGGER.info("[PDD-DIAG] RUNTIME_INTEGRATION observation=UNIVERSAL_BAKERY_DECORATOR_PLUS_NATIVE_EFFECT_FEED " +
                "worldMutation=DISABLED energyCalibration=DISABLED callbackFrame=HIT_OWNER_FRAME " +
                "validation=INDEPENDENT_RECIPROCAL_PRE_SOLVER_PAIR");
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

        // Classify every callback BEFORE dispatch: downstream consumers must see the
        // actually validated frame, not a permanently diagnostic-only placeholder.
        var evidence = callbacks.stream().map(RawObservation::frameEvidence).toList();
        var pairs = ContactPairMatcher.match(evidence);
        var normalized = new ArrayList<>(callbacks);
        for (var match : pairs.matches()) {
            int first = match.first(), second = match.second();
            var a = match.verifiedFirst();
            var b = match.verifiedSecond();
            normalized.set(first, callbacks.get(first).withEvidence(a));
            normalized.set(second, callbacks.get(second).withEvidence(b));
            verifiedPairs++;
            if (a.hit().space() == CollisionFrame.Space.STATIC_WORLD
                    && b.hit().space() == CollisionFrame.Space.STATIC_WORLD) worldWorldPairs++;
            else if (a.hit().space() == CollisionFrame.Space.SABLE_PLOT
                    && b.hit().space() == CollisionFrame.Space.SABLE_PLOT) plotPlotPairs++;
            else worldPlotPairs++;
            if (debugCollisions && sampleFirst == null) {
                sampleFirst = normalized.get(first);
                sampleSecond = normalized.get(second);
            }
        }
        unpairedContacts += pairs.unpaired();
        invalidContacts += pairs.invalid();
        if (debugCollisions && sampleInvalid == null) {
            for (RawObservation c : normalized) {
                if (c.frameEvidence().quality() == CollisionFrame.Quality.INVALID) {
                    sampleInvalid = c;
                    break;
                }
            }
        }
        RawObservationDispatcher.dispatch(normalized);
        if (debugCollisions && sampleFallback == null && !normalized.isEmpty())
            sampleFallback = normalized.get(0);
        if (debugCollisions && sampleNative == null && !nativeFeed.isEmpty())
            sampleNative = nativeFeed.get(0);
        if (++ticks % 100 != 0) return;

        acceptanceReport.log(LOGGER);
        LOGGER.info("[PDD-G-COL-02] phase=SUMMARY {} validatedPairs={} " +
                        "worldPlotPairs={} plotPlotPairs={} worldWorldPairs={} " +
                        "unpairedContacts={} invalidContacts={} toleranceBlocks={} mutation=DISABLED",
                SableFrameCapture.summary(),verifiedPairs,worldPlotPairs,plotPlotPairs,
                worldWorldPairs,unpairedContacts,invalidContacts,ContactPairMatcher.TOLERANCE_BLOCKS);
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
            logSample(sampleFirst != null ? sampleFirst : sampleFallback);
            logSample(sampleSecond);
            if (sampleInvalid != sampleFirst && sampleInvalid != sampleFallback) logSample(sampleInvalid);
            if (sampleNative!=null) logNative(sampleNative);
        }
        sampleFirst=null; sampleSecond=null; sampleInvalid=null; sampleFallback=null; sampleNative=null;
    }

    private static void logSample(RawObservation c) {
        if(c==null)return;
        var e=c.frameEvidence();
        LOGGER.info("[PDD-RAW] phase=LIVE_DATA source={} block={} original={} callbackType={} " +
                        "hit=({},{},{}) otherPresent={} other=({},{},{}) " +
                        "impact=({},{},{}) impactFrame={} ownerSpace={} signedVelocityRaw={} removed={} mutation=OFF",
                c.source(),c.blockId(),c.hadOriginal(),c.callbackType(),
                c.hitX(),c.hitY(),c.hitZ(),c.hasOther(),c.otherX(),c.otherY(),c.otherZ(),
                c.impactX(),c.impactY(),c.impactZ(),c.coordinateFrame(),c.ownerSpace(),
                c.signedVelocity(),c.removeCollision());
        LOGGER.info("[PDD-G-COL-02] dimension={} epoch={} tick={} phase={} " +
                        "hitOwner={} otherOwner={} rawImpact={} worldFromHit={} otherLocalFromWorld={} " +
                        "inputQuality={} reason={} frameValidation={} mutation=OFF",
                e.dimension(),e.epoch(),e.serverTick(),e.phase(),e.hit(),e.other(),
                e.contactRaw(),e.contactWorldHypothesis(),e.otherContactLocalHypothesis(),
                e.quality(),e.reason(),
                e.quality()==CollisionFrame.Quality.PRE_SOLVER_VALIDATED ?
                        "RECIPROCAL_MATCH" : "UNVERIFIED_OR_REJECTED");
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

package com.physicaldestroydeform;

import com.physicaldestroydeform.collision.SableFrameCapture;
import com.physicaldestroydeform.diagnostics.PddDiagnostics;
import com.physicaldestroydeform.diagnostics.AcceptanceReport;
import com.physicaldestroydeform.diagnostics.RawObservationDispatcher;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.diagnostics.SableContactQueue;
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

    public PhysicalDestroyDeform() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        LOGGER.info("[PDD-DIAG] INIT modId={} version={} status=LOADED capture=ALWAYS_ON logging={}", MOD_ID, BUILD_VERSION, debugCollisions);
        PddDiagnostics.runModelSelfTest(LOGGER);
        LOGGER.info("[PDD-DIAG] RUNTIME_INTEGRATION observation=UNIVERSAL_BAKERY_DECORATOR_PLUS_NATIVE_EFFECT_FEED " +
                "worldMutation=DISABLED energyCalibration=DISABLED worldAndPlotFrame=HYPOTHESIS_ONLY");
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
        // Drain even when verbose logs are disabled: the capture feature is never gated by logging.
        var callbacks = SableContactQueue.drain();
        RawObservationDispatcher.dispatch(callbacks);
        var nativeFeed = NativeCollisionTelemetry.drain();
        if (++ticks % 100 == 0) {
            acceptanceReport.log(LOGGER);
            LOGGER.info("[PDD-G-COL-02] phase=SUMMARY {} mutation=DISABLED", SableFrameCapture.summary());
        }
        if (!debugCollisions) return;
        int printed = 0;
        for (var contact : callbacks) {
            if (printed++ >= 8) break;
            LOGGER.info("[PDD-RAW] phase=LIVE_DATA source={} block={} original={} callbackType={} " +
                            "captureNanos={} hit=({},{},{}) otherPresent={} other=({},{},{}) " +
                            "impact=({},{},{}) signedVelocity={} removed={} owner={} frame={} mutation=OFF",
                    contact.source(), contact.blockId(), contact.hadOriginal(), contact.callbackType(),
                    contact.capturedNanos(), contact.hitX(), contact.hitY(), contact.hitZ(),
                    contact.hasOther(), contact.otherX(), contact.otherY(), contact.otherZ(),
                    contact.impactX(), contact.impactY(), contact.impactZ(), contact.signedVelocity(),
                    contact.removeCollision(), contact.ownerSpace(), contact.coordinateFrame());
        }
        printed = 0;
        for (var contact : callbacks) {
            if (printed++ >= 8) break;
            var e = contact.frameEvidence();
            LOGGER.info("[PDD-G-COL-02] dimension={} epoch={} tick={} phase={} " +
                            "hitOwner={} otherOwner={} rawImpact={} worldFromHit={} worldFromOther={} " +
                            "hitBody={} otherBody={} rawSignedVelocity={} " +
                            "inputQuality={} reason={} frameValidation=INGAME_PENDING mutation=OFF",
                    e.dimension(), e.epoch(), e.serverTick(), e.phase(),
                    e.hit(), e.other(), e.contactRaw(), e.contactWorldHypothesis(), e.otherWorldHypothesis(),
                    e.hitBody(), e.otherBody(), contact.signedVelocity(), e.quality(), e.reason());
        }
        printed = 0;
        for (var collision : nativeFeed) {
            if (printed++ >= 8) break;
            LOGGER.info("[PDD-COLLISION] source=SABLE_RAPIER_NATIVE phase=LIVE_DATA " +
                            "idA={} idB={} rawImpactScalar={} " +
                            "rawVector0=({},{},{}) rawVector1=({},{},{}) " +
                            "rawVector2=({},{},{}) rawVector3=({},{},{}) frame=UNVERIFIED mutation=OFF",
                    collision.idA(), collision.idB(), collision.rawImpactScalar(),
                    collision.ax(), collision.ay(), collision.az(),
                    collision.bx(), collision.by(), collision.bz(),
                    collision.contactAX(), collision.contactAY(), collision.contactAZ(),
                    collision.contactBX(), collision.contactBY(), collision.contactBZ());
        }
        if (ticks % 100 == 0) {
            long total = NativeCollisionTelemetry.total();
            long dropped = NativeCollisionTelemetry.dropped();
            long polls = NativeCollisionTelemetry.polls();
            long malformed = NativeCollisionTelemetry.malformed();
            long callbackTotal = SableContactQueue.total();
            long callbackDropped = SableContactQueue.dropped();
            long captureErrors = SableContactQueue.captureErrors();
            LOGGER.info("[PDD-COLLISION] summary ticks=100 nativeCalls={} nativeContacts={} samplesDropped={} malformedArrays={} status={}",
                    polls-lastPolls, total-lastTotal, dropped-lastDropped, malformed-lastMalformed,
                    polls > lastPolls ? "HOOK_ACTIVE" : "HOOK_NOT_OBSERVED");
            LOGGER.info("[PDD-RAW] summary ticks=100 callbackInvocations={} rawDropped={} captureErrors={} " +
                            "wrappedWithOriginal={} wrappedNull={} status={} damage=DISABLED",
                    callbackTotal-lastCallbackTotal, callbackDropped-lastCallbackDropped, captureErrors-lastCaptureErrors,
                    SableContactQueue.wrappedOriginal(), SableContactQueue.wrappedNull(),
                    callbackTotal > lastCallbackTotal ? "CALLBACK_INVOKED" : "NO_CALLBACK_OBSERVED");
            lastTotal=total; lastDropped=dropped; lastPolls=polls; lastMalformed=malformed;
            lastCallbackTotal=callbackTotal; lastCallbackDropped=callbackDropped; lastCaptureErrors=captureErrors;
        }
    }
}

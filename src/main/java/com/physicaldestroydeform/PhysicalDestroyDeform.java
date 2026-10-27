package com.physicaldestroydeform;

import com.physicaldestroydeform.diagnostics.PddDiagnostics;
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

/** Diagnostic integration only: no world mutation, energy conversion or damage application. */
@Mod(PhysicalDestroyDeform.MOD_ID)
public final class PhysicalDestroyDeform {
    public static final String MOD_ID = "physical_destroy_deform";
    public static final String BUILD_VERSION = "0.2.11-debug";
    private static final Logger LOGGER = LoggerFactory.getLogger(PhysicalDestroyDeform.class);
    private final boolean debugCollisions = NativeCollisionTelemetry.ENABLED;
    private long lastTotal;
    private long lastDropped;
    private long lastPolls;
    private long lastMalformed;
    private long lastCallbackTotal;
    private long lastCallbackDropped;
    private int ticks;

    public PhysicalDestroyDeform() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        LOGGER.info("[PDD-DIAG] INIT modId={} version={} status=LOADED debugCollisions={}", MOD_ID, BUILD_VERSION, debugCollisions);
        PddDiagnostics.runModelSelfTest(LOGGER);
        LOGGER.info("[PDD-DIAG] RUNTIME_INTEGRATION observation=NATIVE_CONTACT_FEED_PLUS_EXISTING_CALLBACKS " +
                "worldMutation=DISABLED energyCalibration=DISABLED staticAndSublevelFrame=UNVERIFIED");
    }
    private void onServerStarted(ServerStartedEvent event) {
        MaterialRegistry.rebuild(event.getServer(), LOGGER);
        LOGGER.info("[PDD-DIAG] SABLE_BRIDGE target=RapierPhysicsPipeline.processCollisionEffects/clearCollisions phase=RETURN_ARRAY status=CONFIGURED_NOT_YET_PROVEN format=15_DOUBLES_PER_CONTACT");
    }
    private void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) MaterialRegistry.rebuild(event.getPlayerList().getServer(), LOGGER);
    }
    private void onServerStopped(ServerStoppedEvent event) {
        MaterialRegistry.clear();
        NativeCollisionTelemetry.drain();
        SableContactQueue.drain();
    }
    private void onServerTick(ServerTickEvent.Post event) {
        if (!debugCollisions) return;
        var callbackBatch = SableContactQueue.drain();
        int callbackPrinted = 0;
        for (var contact : callbackBatch) {
            if (callbackPrinted++ >= 8) break;
            LOGGER.info("[PDD-SABLE-CALLBACK] source=SABLE_BLOCK_CALLBACK callbackType={} hit=({},{},{}) otherPresent={} other=({},{},{}) " +
                            "impact=({},{},{}) signedNormalVelocity={} sableResponse={} originalResponse=UNCHANGED",
                    contact.callbackType(), contact.x(), contact.y(), contact.z(),
                    contact.hasOther(), contact.otherX(), contact.otherY(), contact.otherZ(),
                    contact.impactX(), contact.impactY(), contact.impactZ(),
                    contact.signedVelocity(), contact.sableResponse());
        }
        var batch = NativeCollisionTelemetry.drain();
        int printed = 0;
        for (var collision : batch) {
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
        if (++ticks % 100 == 0) {
            long total = NativeCollisionTelemetry.total();
            long dropped = NativeCollisionTelemetry.dropped();
            long polls = NativeCollisionTelemetry.polls();
            long malformed = NativeCollisionTelemetry.malformed();
            long callbacks = SableContactQueue.total();
            long callbackDropped = SableContactQueue.dropped();
            LOGGER.info("[PDD-COLLISION] summary ticks=100 source=SABLE_RAPIER_NATIVE " +
                            "nativeCalls={} nativeContacts={} samplesDropped={} malformedArrays={} " +
                            "queued={} status={} frame=UNVERIFIED damage=DISABLED",
                    polls-lastPolls,total-lastTotal,dropped-lastDropped,malformed-lastMalformed,
                    NativeCollisionTelemetry.size(), (polls-lastPolls > 0 ? "HOOK_ACTIVE" : "HOOK_NOT_OBSERVED"));
            LOGGER.info("[PDD-SABLE-CALLBACK] summary ticks=100 existingCallbacks={} dropped={} queue={} " +
                            "status={} ordinaryCallbackFreeBlocks=NOT_COVERED_BY_THIS_HOOK",
                    callbacks-lastCallbackTotal, callbackDropped-lastCallbackDropped, SableContactQueue.size(),
                    callbacks > lastCallbackTotal ? "CALLBACK_INVOKED" : "NO_CALLBACK_OBSERVED");
            lastTotal=total;lastDropped=dropped;lastPolls=polls;lastMalformed=malformed;
            lastCallbackTotal=callbacks;lastCallbackDropped=callbackDropped;
        }
    }
}

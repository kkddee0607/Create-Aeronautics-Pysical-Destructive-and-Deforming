package com.physicaldestroydeform;

import com.physicaldestroydeform.collision.RealFixRuntimeBridge;
import com.physicaldestroydeform.collision.SableFrameCapture;
import com.physicaldestroydeform.diagnostics.AcceptanceReport;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.diagnostics.PddDiagnostics;
import com.physicaldestroydeform.diagnostics.RawObservationDispatcher;
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

/**
 * IDS 1.0.5-A: consume Sable's unchanged native post-effect records and
 * original non-null special callbacks. No block, force, or solver mutation.
 */
@Mod(PhysicalDestroyDeform.MOD_ID)
public final class PhysicalDestroyDeform {
    public static final String MOD_ID = "physical_destroy_deform";
    public static final String BUILD_VERSION = "0.4.0finalFix";
    private static final Logger LOGGER = LoggerFactory.getLogger(PhysicalDestroyDeform.class);
    private final AcceptanceReport acceptanceReport = new AcceptanceReport();
    private final RealFixRuntimeBridge bridge = new RealFixRuntimeBridge();
    private long lastNative, lastCallbacks, lastNativeDropped, lastCallbackDropped;
    private int ticks;

    public PhysicalDestroyDeform() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        LOGGER.info("[PDD-DIAG] INIT modId={} version={} revision=IDS-1.0.7 source=SABLE_ORIGINAL_POST_EFFECT " +
                "target=NATIVE_BODY_LOCAL_INDEPENDENT_R2_R3_UNCALIBRATED_R4_PROVISIONAL state=FOUR_PHASE_REGIONAL_LEDGER " +
                "worldMutation=OFF",MOD_ID,BUILD_VERSION);
        PddDiagnostics.runModelSelfTest(LOGGER);
    }

    private void onServerStarted(ServerStartedEvent event) {
        MaterialRegistry.rebuild(event.getServer(), LOGGER);
        bridge.loadPreview(event.getServer(), LOGGER);
        LOGGER.info("[PDD-0.4.0finalFix] phase=START bridge=READ_ONLY nativeScalar=GAME_FORCE_PROXY " +
                "newNativeABI=NOT_REQUIRED customNullCallback=DISABLED developmentBaseSha=bc0267464f9ebd3f63f76a124979df2c549b642b worldMutation=OFF");
    }
    private void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer()==null) MaterialRegistry.rebuild(event.getPlayerList().getServer(), LOGGER);
    }
    private void onServerStopped(ServerStoppedEvent event) {
        bridge.savePreview(event.getServer(), LOGGER);
        bridge.clear();
        MaterialRegistry.clear();
        NativeCollisionTelemetry.drain();
        SableContactQueue.drain();
        SableFrameCapture.clear();
        acceptanceReport.close();
        LOGGER.info("[PDD-0.4.0finalFix] phase=STOP state=CLEARED worldMutation=OFF");
    }
    private void onServerTick(ServerTickEvent.Post event) {
        // Snapshot and native inputs are consumed exactly once on the server tick.
        // The Sable return array has already been handed back unchanged by Mixin.
        var callbacks=SableContactQueue.drain();
        var nativeEffects=NativeCollisionTelemetry.drain();
        var preHistory=SableFrameCapture.drainPreHistory();
        try {
            bridge.tick(event.getServer(),callbacks,nativeEffects,preHistory,LOGGER);
        } catch (RuntimeException invalid) {
            // Never let a diagnostic bridge disrupt the physics/game tick.
            LOGGER.error("[PDD-0.4.0finalFix] phase=BRIDGE_EXCEPTION reason={} nativeEffects={} " +
                    "callbacks={} Q=0 worldMutation=OFF",invalid.getClass().getName(),
                    nativeEffects.size(),callbacks.size(),invalid);
        } finally {
            RawObservationDispatcher.dispatch(callbacks);
        }
        if(++ticks%100!=0)return;
        acceptanceReport.log(LOGGER);
        LOGGER.info("[PDD-OBS] phase=SUMMARY physicsSnapshots={} nativeRecords={} callbackRecords={} " +
                "nativeDropped={} callbackDropped={} nativeMalformed={} nativeCapSaturatedPolls={} reason=READ_ONLY " +
                "worldMutation=OFF",SableFrameCapture.summary(),
                NativeCollisionTelemetry.total()-lastNative,
                SableContactQueue.total()-lastCallbacks,
                NativeCollisionTelemetry.dropped()-lastNativeDropped,
                SableContactQueue.dropped()-lastCallbackDropped,
                NativeCollisionTelemetry.malformed(),NativeCollisionTelemetry.nativeCapSaturatedPolls());
        lastNative=NativeCollisionTelemetry.total();
        lastCallbacks=SableContactQueue.total();
        lastNativeDropped=NativeCollisionTelemetry.dropped();
        lastCallbackDropped=SableContactQueue.dropped();
    }
}

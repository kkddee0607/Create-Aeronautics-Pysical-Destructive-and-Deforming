package com.physicaldestroydeform;

import com.physicaldestroydeform.collision.RealFixRuntimeBridge;
import com.physicaldestroydeform.collision.SableFrameCapture;
import com.physicaldestroydeform.diagnostics.AcceptanceReport;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.diagnostics.PddDiagnostics;
import com.physicaldestroydeform.diagnostics.RawObservationDispatcher;
import com.physicaldestroydeform.diagnostics.SableContactQueue;
import com.physicaldestroydeform.material.MaterialRegistry;
import com.physicaldestroydeform.world.WorldCommitService;
import com.physicaldestroydeform.world.WorldBlockMutationTracker;
import com.physicaldestroydeform.plot.PlotCommitService;
import com.physicaldestroydeform.plot.PlotPhysicsProbe;
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
    public static final String BUILD_VERSION = "0.5.1-safeFix";
    private static final Logger LOGGER = LoggerFactory.getLogger(PhysicalDestroyDeform.class);
    private final AcceptanceReport acceptanceReport = new AcceptanceReport();
    private final RealFixRuntimeBridge bridge = new RealFixRuntimeBridge();
    private WorldCommitService worldCommit;
    private PlotCommitService plotCommit;
    private long lastNative, lastCallbacks, lastNativeDropped, lastCallbackDropped;
    private int ticks;

    public PhysicalDestroyDeform() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        LOGGER.info("[PDD-DIAG] INIT modId={} version={} releaseSpec=IDS-1.0.8 policyMath=IDS-1.0.7 source=SABLE_ORIGINAL_POST_EFFECT " +
                "target=NATIVE_BODY_LOCAL_INDEPENDENT_R2_R3_UNCALIBRATED_R4_PROVISIONAL state=FOUR_PHASE_REGIONAL_LEDGER " +
                "worldMutation=WORLD_STATIC_ONLY plotMutation=SABLE_PLOT",MOD_ID,BUILD_VERSION);
        PddDiagnostics.runModelSelfTest(LOGGER);
    }

    private void onServerStarted(ServerStartedEvent event) {
        // A new integrated server may reuse the same JVM; discard prior session diagnostics.
        PlotPhysicsProbe.clear();
        MaterialRegistry.rebuild(event.getServer(), LOGGER);
        bridge.loadPreview(event.getServer(), LOGGER);
        try {
            worldCommit=new WorldCommitService(event.getServer(),
                    com.physicaldestroydeform.DamageModel.Settings.fromSystemProperties(),LOGGER);
            bridge.setWorldCommitService(worldCommit);
        }catch(java.io.IOException|RuntimeException unsafeJournal){
            // Never switch on World mutations without an initialized durable journal.
            worldCommit=null;bridge.setWorldCommitService(null);
            LOGGER.error("[PDD-SAVE] phase=START status=DISABLED reason=JOURNAL_INIT_FAILED " +
                    "worldMutation=OFF",unsafeJournal);
        }
        try {
            plotCommit=new PlotCommitService(event.getServer(),
                    DamageModel.Settings.fromSystemProperties(),LOGGER);
            bridge.setPlotCommitService(plotCommit);
            WorldBlockMutationTracker.attachPlot(plotCommit);
        }catch(java.io.IOException|RuntimeException unsafePlotJournal){
            plotCommit=null;bridge.setPlotCommitService(null);
            WorldBlockMutationTracker.attachPlot(null);
            LOGGER.error("[PDD-PLOT] phase=START status=DISABLED reason=JOURNAL_INIT_FAILED " +
                    "plotMutation=OFF worldMutation=UNCHANGED",unsafePlotJournal);
        }
        LOGGER.info("[PDD-0.5.1-safeFix] phase=START bridge=READ_ONLY nativeScalar=GAME_FORCE_PROXY " +
                "newNativeABI=NOT_REQUIRED customNullCallback=DISABLED faceRecovery=MULTI_SURFACE_PLUS_SPARSE_SHAPE_HALO worldMutation={} plotMutation={} ",
                worldCommit==null?"DISABLED":"WORLD_STATIC_ONLY",plotCommit==null?"DISABLED":"SABLE_PLOT");
    }
    private void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer()==null) MaterialRegistry.rebuild(event.getPlayerList().getServer(), LOGGER);
    }
    private void onServerStopped(ServerStoppedEvent event) {
        bridge.savePreview(event.getServer(), LOGGER);
        if(worldCommit!=null){worldCommit.stop();worldCommit=null;}
        WorldBlockMutationTracker.attachPlot(null);
        if(plotCommit!=null){plotCommit.stop();plotCommit=null;}
        bridge.clear();
        MaterialRegistry.clear();
        NativeCollisionTelemetry.drain();
        PlotPhysicsProbe.clear();
        SableContactQueue.drain();
        SableFrameCapture.clear();
        acceptanceReport.close();
        LOGGER.info("[PDD-0.5.1-safeFix] phase=STOP state=CLEARED worldMutation=STOPPED");
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
            LOGGER.error("[PDD-0.5.1] phase=BRIDGE_EXCEPTION reason={} nativeEffects={} " +
                    "callbacks={} Q=0 worldMutation=OFF",invalid.getClass().getName(),
                    nativeEffects.size(),callbacks.size(),invalid);
        } finally {
            RawObservationDispatcher.dispatch(callbacks);
        }
        if(worldCommit!=null) {
            try {worldCommit.tick(event.getServer().getTickCount());}
            catch(RuntimeException commitError) {
                LOGGER.error("[PDD-COMMIT] phase=TICK status=FAULT reason=UNEXPECTED_COMMIT_ERROR",commitError);
                worldCommit.stop();worldCommit=null;bridge.setWorldCommitService(null);
            }
        }
        if(plotCommit!=null){
            try {plotCommit.tick(event.getServer().getTickCount());}
            catch(RuntimeException commitError){
                LOGGER.error("[PDD-PLOT] phase=TICK status=FAULT reason=UNEXPECTED_COMMIT_ERROR",commitError);
                WorldBlockMutationTracker.attachPlot(null);
                plotCommit.stop();plotCommit=null;bridge.setPlotCommitService(null);
            }
        }
        if(++ticks%100!=0)return;
        acceptanceReport.log(LOGGER);
        LOGGER.info("[PDD-OBS] phase=SUMMARY physicsSnapshots={} nativeRecords={} callbackRecords={} " +
                "nativeDropped={} callbackDropped={} nativeMalformed={} nativeCapSaturatedPolls={} sourceTruncation=UNVERIFIED reason=READ_ONLY " +
                "worldMutation={}",SableFrameCapture.summary(),
                NativeCollisionTelemetry.total()-lastNative,
                SableContactQueue.total()-lastCallbacks,
                NativeCollisionTelemetry.dropped()-lastNativeDropped,
                SableContactQueue.dropped()-lastCallbackDropped,
                NativeCollisionTelemetry.malformed(),NativeCollisionTelemetry.nativeCapSaturatedPolls(),
                (worldCommit==null?"OFF":"WORLD_STATIC")+"|"+(plotCommit==null?"OFF":"SABLE_PLOT"));
        lastNative=NativeCollisionTelemetry.total();
        lastCallbacks=SableContactQueue.total();
        lastNativeDropped=NativeCollisionTelemetry.dropped();
        lastCallbackDropped=SableContactQueue.dropped();
    }
}

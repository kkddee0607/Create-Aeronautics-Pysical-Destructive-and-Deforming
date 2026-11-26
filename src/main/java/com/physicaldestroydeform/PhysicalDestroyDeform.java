package com.physicaldestroydeform;

import com.physicaldestroydeform.diagnostics.RogTrace;
import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
import com.physicaldestroydeform.collision.RealFixRuntimeBridge;
import com.physicaldestroydeform.stress.StressRuntime;
import com.physicaldestroydeform.collision.SableFrameCapture;
import com.physicaldestroydeform.diagnostics.AcceptanceReport;
import com.physicaldestroydeform.diagnostics.RuntimeDiagnostics;
import com.physicaldestroydeform.diagnostics.RuntimeDiagnostics.Phase;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.diagnostics.PddDiagnostics;
import com.physicaldestroydeform.diagnostics.RawObservationDispatcher;
import com.physicaldestroydeform.diagnostics.SableContactQueue;
import com.physicaldestroydeform.material.MaterialRegistry;
import com.physicaldestroydeform.material.TargetMaterials;
import com.physicaldestroydeform.world.WorldCommitService;
import com.physicaldestroydeform.world.WorldBlockMutationTracker;
import com.physicaldestroydeform.world.NativeLineageTransfer;
import com.physicaldestroydeform.world.LineageRecovery;
import com.physicaldestroydeform.plot.PlotCommitService;
import com.physicaldestroydeform.plot.PlotPhysicsProbe;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IDS 1.0.5-A: consume Sable's unchanged native post-effect records and
 * original non-null special callbacks. No block, force, or solver mutation.
 */
@Mod(PhysicalDestroyDeform.MOD_ID)
public final class PhysicalDestroyDeform {
    public static final String MOD_ID = "physical_destroy_deform";
    public static final String BUILD_VERSION = "0.6.1-originlastFix";
    private static final Logger LOGGER = LoggerFactory.getLogger(PhysicalDestroyDeform.class);
    private final AcceptanceReport acceptanceReport = new AcceptanceReport();
    private final RealFixRuntimeBridge bridge = new RealFixRuntimeBridge();
    private final RuntimeDiagnostics runtimeDiagnostics=new RuntimeDiagnostics();
    private MinecraftServer activeServer;
    private boolean mutationHalted;
    private WorldCommitService worldCommit;
    private PlotCommitService plotCommit;
    private StressRuntime stress;
    private long lastNative, lastCallbacks, lastNativeDropped, lastCallbackDropped;
    private int ticks;

    public PhysicalDestroyDeform() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        LOGGER.info("[PDD-DIAG] INIT modId={} version={} releaseSpec=IDS-1.0.9 policyMath=IDS-1.0.7 source=SABLE_ORIGINAL_POST_EFFECT " +
                "target=NATIVE_OWNER_LOCAL_DIRECT_CERTIFIED_BILATERAL_OPTIONAL state=FOUR_PHASE_REGIONAL_LEDGER " +
                "worldMutation=WORLD_STATIC_ONLY plotMutation=SABLE_PLOT",MOD_ID,BUILD_VERSION);
        PddDiagnostics.runModelSelfTest(LOGGER);
    }

    private void onServerStarted(ServerStartedEvent event) {
        // A new integrated server may reuse the same JVM; discard prior session diagnostics.
        activeServer=event.getServer();
        mutationHalted=false;
        acceptanceReport.start();
        ticks=0;
        RogTrace.start();
        runtimeDiagnostics.reset(System.nanoTime());
        lastNative=NativeCollisionTelemetry.total();lastCallbacks=SableContactQueue.total();
        lastNativeDropped=NativeCollisionTelemetry.dropped();lastCallbackDropped=SableContactQueue.dropped();
        TargetMaterials.clearSession();
        PlotPhysicsProbe.clear();
        LOGGER.info("[PDD-SESSION] phase=START serverIdentity={} mode={}",System.identityHashCode(activeServer),com.physicaldestroydeform.diagnostics.DiagnosticMode.current());
        MaterialRegistry.rebuild(event.getServer(), LOGGER);
        LOGGER.info("[PDD-COPYCAT] phase=START compatibility={} policy={}",
                com.physicaldestroydeform.material.CopycatMaterial.compatibilityStatus(),com.physicaldestroydeform.material.CopycatMaterial.POLICY);
        // Registry mismatch is external compatibility evidence, not a reason to remap blocks.
        var catwalk=net.minecraft.resources.ResourceLocation.tryParse("copycats:copycat_catwalk");
        if(catwalk!=null)LOGGER.info("[PDD-COPYCAT] phase=REGISTRY id={} blockRegistered={} itemRegistered={} authority=NONE",
                catwalk,net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(catwalk),net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(catwalk));
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
        LineageRecovery.start(worldCommit,plotCommit,event.getServer());
        try {
            stress=new StressRuntime(event.getServer(),worldCommit,plotCommit,LOGGER);
        }catch(java.io.IOException|RuntimeException unsafeStressJournal){
            // Do not execute pending origins without their reservation hold/recovery contract.
            bridge.setWorldCommitService(null);bridge.setPlotCommitService(null);
            LineageRecovery.clear();
            cleanup("world",()->{if(worldCommit!=null)try{worldCommit.stop();}finally{worldCommit=null;}});
            cleanup("plot",()->{if(plotCommit!=null)try{plotCommit.stop();}finally{plotCommit=null;}});
            WorldBlockMutationTracker.attach(null);
            WorldBlockMutationTracker.attachPlot(null);
            LOGGER.error("[PDD-STRESS-LEDGER] phase=START status=HALTED reason=JOURNAL_INIT_FAILED mutations=OFF",unsafeStressJournal);
        }
        LOGGER.info("[PDD-0.6.1] phase=START revision=0.6.1-originlastFix CONTACT_ELASTIC_ALPHA04_PERSISTENT_CONTACT bridge=READ_ONLY nativeScalar=GAME_FORCE_PROXY " +
                "newNativeABI=NOT_REQUIRED customNullCallback=DISABLED faceRecovery=APPROVED_BILATERAL_ONLY worldMutation={} plotMutation={} ",
                worldCommit==null?"DISABLED":"WORLD_STATIC_ONLY",plotCommit==null?"DISABLED":"SABLE_PLOT");
    }
    private void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer()==null) MaterialRegistry.rebuild(event.getPlayerList().getServer(), LOGGER);
    }
    private void onServerStopped(ServerStoppedEvent event) {
        if(activeServer!=null&&activeServer!=event.getServer())return;
        // ServerStopped preserves the existing checkpoint ordering and native callback contract.
        // Each cleanup owns its failures so a failed checkpoint cannot retain later session resources.
        boolean clean=cleanup("lineage-stack",NativeLineageTransfer::expire);
        clean&=cleanup("lineage-recovery",LineageRecovery::clear);
        clean&=cleanup("preview",()->bridge.savePreview(event.getServer(), LOGGER));
        clean&=cleanup("stress",()->{if(stress!=null)try{stress.stop();}finally{stress=null;}});
        clean&=cleanup("world",()->{if(worldCommit!=null)try{worldCommit.stop();}finally{worldCommit=null;}});
        WorldBlockMutationTracker.attach(null);WorldBlockMutationTracker.attachPlot(null);
        clean&=cleanup("plot",()->{if(plotCommit!=null)try{plotCommit.stop();}finally{plotCommit=null;}});
        clean&=cleanup("bridge",bridge::clear);
        clean&=cleanup("material-cache",TargetMaterials::clearSession);
        clean&=cleanup("material-registry",MaterialRegistry::clear);
        clean&=cleanup("native-intake",()->NativeCollisionTelemetry.drain());
        clean&=cleanup("plot-probe",PlotPhysicsProbe::clear);
        clean&=cleanup("callback-intake",()->SableContactQueue.drain());
        clean&=cleanup("frame-capture",SableFrameCapture::clear);
        clean&=cleanup("acceptance",acceptanceReport::close);
        RogTrace.stop();
        activeServer=null;
        runtimeDiagnostics.reset(System.nanoTime());
        LOGGER.info("[PDD-SESSION] phase=STOP cleanup={} worldMutation=STOPPED",clean?"CLOSED":"CLEANUP_FAILED");
        LOGGER.info("[PDD-0.6.0] phase=STOP state={} worldMutation=STOPPED",clean?"CLEARED":"CLEANUP_FAILED");
    }
    private static boolean cleanup(String component,Runnable action){
        try{action.run();return true;}
        catch(RuntimeException|LinkageError failed){LOGGER.error("[PDD-SESSION] cleanup={} status=FAILED",component,failed);return false;}
    }
    private void haltMutations(RuntimeException failure){
        if(mutationHalted)return;
        mutationHalted=true;
        // Keep the shared stress reservation holds alive until shutdown. Never
        // enqueue into a stopped service or release a pending origin on I/O failure.
        if(worldCommit!=null)worldCommit.lineageFault(failure);
        if(plotCommit!=null)plotCommit.lineageFault(failure);
        LineageRecovery.clear();
        bridge.setWorldCommitService(null);bridge.setPlotCommitService(null);
        WorldBlockMutationTracker.attach(null);WorldBlockMutationTracker.attachPlot(null);
        LOGGER.error("[PDD-SESSION] phase=HALT mutations=OFF reason=COMMIT_FAULT wal=RETAINED",failure);
    }
    private void checkReceiptHealth(){
        if(!mutationHalted&&((worldCommit!=null&&worldCommit.lineageJournal().receiptLookupFaulted())
                ||(plotCommit!=null&&plotCommit.lineageJournal().receiptLookupFaulted())))
            haltMutations(new IllegalStateException("RECEIPT_DISK_LOOKUP_FAILED"));
    }
    private void onServerTick(ServerTickEvent.Post event) {
        if(activeServer!=event.getServer())return;
        long started=System.nanoTime();
        RogTrace.begin(event.getServer().getTickCount());
        try{processServerTick(event);}
        finally{
            long ended=System.nanoTime();runtimeDiagnostics.recordTick(ended-started);
            RogTrace.endTick();
            if(runtimeDiagnostics.reportDue(ended)){
                try{if(RogTrace.sample(Stage.R13,"REPORT"))RogTrace.detail(Stage.R13,"REPORT","serverTick={} mutationHalted={} worldAvailable={} plotAvailable={} stressAvailable={}",event.getServer().getTickCount(),mutationHalted,worldCommit!=null,plotCommit!=null,stress!=null);runtimeDiagnostics.log(LOGGER);TargetMaterials.logPerformance(LOGGER);
                    if(worldCommit!=null){LOGGER.info("[PDD-STORAGE-PERF] owner=WORLD {}",worldCommit.storagePerformance());
                        LOGGER.info("[PDD-JOURNAL-IO] owner=WORLD {}",worldCommit.ioPerformance());}
                    if(plotCommit!=null){LOGGER.info("[PDD-STORAGE-PERF] owner=PLOT {}",plotCommit.storagePerformance());
                        LOGGER.info("[PDD-JOURNAL-IO] owner=PLOT {}",plotCommit.ioPerformance());}
                    if(stress!=null)LOGGER.info("[PDD-STRESS-IO] {}",stress.ioPerformance());}
                catch(RuntimeException|LinkageError unavailable){LOGGER.warn("[PDD-PERFORMANCE] status=DIAGNOSTICS_UNAVAILABLE",unavailable);}
            }
            runtimeDiagnostics.recordPhase(Phase.DIAGNOSTICS,System.nanoTime()-ended);
        }
    }
    private void processServerTick(ServerTickEvent.Post event) {
        long phaseStart=System.nanoTime();
        checkReceiptHealth();
        NativeLineageTransfer.expire();
        LineageRecovery.tick(event.getServer());
        runtimeDiagnostics.recordPhase(Phase.LINEAGE,System.nanoTime()-phaseStart);
        phaseStart=System.nanoTime();
        // Snapshot and native inputs are consumed exactly once on the server tick.
        // The Sable return array has already been handed back unchanged by Mixin.
        var callbacks=SableContactQueue.drain();
        var nativeEffects=NativeCollisionTelemetry.drain();
        var preHistory=SableFrameCapture.drainPreHistory();
        SableFrameCapture.releaseEndedFrames(event.getServer(),event.getServer().getTickCount());
        if(RogTrace.sample(Stage.R01,"DRAIN"))RogTrace.detail(Stage.R01,"DRAIN","nativeRecords={} callbacks={} preFrames={} nativeDropped={} callbackDropped={} nativeMalformed={} preHistoryDropped={} capSaturatedPolls={}",nativeEffects.size(),callbacks.size(),preHistory.size(),NativeCollisionTelemetry.dropped(),SableContactQueue.dropped(),NativeCollisionTelemetry.malformed(),SableFrameCapture.preHistoryDropped(),NativeCollisionTelemetry.nativeCapSaturatedPolls());
        runtimeDiagnostics.recordPhase(Phase.CAPTURE,System.nanoTime()-phaseStart);
        long bridgeStart=System.nanoTime();
        try {
            bridge.tick(event.getServer(),callbacks,nativeEffects,preHistory,LOGGER);
        } catch (RuntimeException invalid) {
            // Never let a diagnostic bridge disrupt the physics/game tick.
            LOGGER.error("[PDD-0.6.0] phase=BRIDGE_EXCEPTION reason={} nativeEffects={} " +
                    "callbacks={} Q=0 worldMutation=OFF",invalid.getClass().getName(),
                    nativeEffects.size(),callbacks.size(),invalid);
        } finally {
            RogTrace.clearContext();
            long bridgeEnd=System.nanoTime();
            runtimeDiagnostics.recordBridge(bridgeEnd-bridgeStart);
            runtimeDiagnostics.recordPhase(Phase.BRIDGE,bridgeEnd-bridgeStart);
            long dispatchStart=System.nanoTime();
            try{RawObservationDispatcher.dispatch(callbacks);}
            finally{runtimeDiagnostics.recordPhase(Phase.DISPATCH,System.nanoTime()-dispatchStart);}
        }
        checkReceiptHealth();
        phaseStart=System.nanoTime();
        try{if(!mutationHalted&&stress!=null)stress.tick();}
        finally{runtimeDiagnostics.recordPhase(Phase.STRESS,System.nanoTime()-phaseStart);}
        checkReceiptHealth();
        phaseStart=System.nanoTime();
        try{
        if(!mutationHalted&&worldCommit!=null) {
            try {worldCommit.tick(event.getServer().getTickCount());}
            catch(RuntimeException commitError) {
                LOGGER.error("[PDD-COMMIT] phase=TICK status=FAULT reason=UNEXPECTED_COMMIT_ERROR",commitError);
                haltMutations(commitError);
            }
        }
        }finally{runtimeDiagnostics.recordPhase(Phase.WORLD_COMMIT,System.nanoTime()-phaseStart);}
        checkReceiptHealth();
        phaseStart=System.nanoTime();
        try{
        if(!mutationHalted&&plotCommit!=null){
            try {plotCommit.tick(event.getServer().getTickCount());}
            catch(RuntimeException commitError){
                LOGGER.error("[PDD-PLOT] phase=TICK status=FAULT reason=UNEXPECTED_COMMIT_ERROR",commitError);
                haltMutations(commitError);
            }
        }
        }finally{runtimeDiagnostics.recordPhase(Phase.PLOT_COMMIT,System.nanoTime()-phaseStart);}
        checkReceiptHealth();
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
                (mutationHalted||worldCommit==null?"OFF":"WORLD_STATIC")+"|"+(mutationHalted||plotCommit==null?"OFF":"SABLE_PLOT"));
        lastNative=NativeCollisionTelemetry.total();
        lastCallbacks=SableContactQueue.total();
        lastNativeDropped=NativeCollisionTelemetry.dropped();
        lastCallbackDropped=SableContactQueue.dropped();
    }
}

package com.physicaldestroydeform;

import com.physicaldestroydeform.diagnostics.PddDiagnostics;
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
    public static final String BUILD_VERSION = "0.2.1-debug";
    private static final Logger LOGGER = LoggerFactory.getLogger(PhysicalDestroyDeform.class);
    private final boolean debugCollisions = SableContactQueue.ENABLED;
    private long lastTotal;
    private long lastDropped;
    private int ticks;

    public PhysicalDestroyDeform() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        LOGGER.info("[PDD-DIAG] INIT modId={} version={} status=LOADED debugCollisions={}", MOD_ID, BUILD_VERSION, debugCollisions);
        PddDiagnostics.runModelSelfTest(LOGGER);
        LOGGER.info("[PDD-DIAG] RUNTIME_INTEGRATION callbackCoverage=EXISTING_CALLBACKS_ONLY " +
                "worldMutation=DISABLED energyCalibration=DISABLED staticAndSublevelFrame=UNVERIFIED");
    }
    private void onServerStarted(ServerStartedEvent event) {
        MaterialRegistry.rebuild(event.getServer(), LOGGER);
        LOGGER.info("[PDD-DIAG] SABLE_BRIDGE target=Sable.FragileBlockCallback+BeltBlockCallback.sable$onCollision phase=RETURN state=RUNTIME_TEST_REQUIRED");
    }
    private void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) MaterialRegistry.rebuild(event.getPlayerList().getServer(), LOGGER);
    }
    private void onServerStopped(ServerStoppedEvent event) { MaterialRegistry.clear(); SableContactQueue.drain(); }
    private void onServerTick(ServerTickEvent.Post event) {
        if (!debugCollisions) return;
        var batch = SableContactQueue.drain();
        int printed = 0;
        for (var contact : batch) {
            if (printed++ >= 8) break; // never emit thousands of entries during collision storms
            LOGGER.info("[PDD-COLLISION] scope=CALLBACK_ONLY frame=UNVERIFIED callbackType={} hit=({},{},{}) other={} " +
                            "impact=({},{},{}) signedNormalVelocity={} sableResponse={} " +
                            "state=OBSERVED_UNCALIBRATED",
                    contact.callbackType(), contact.x(), contact.y(), contact.z(),
                    contact.hasOther() ? "(" + contact.otherX() + "," + contact.otherY() + "," + contact.otherZ() + ")" : "null",
                    contact.impactX(), contact.impactY(), contact.impactZ(), contact.signedVelocity(),
                    contact.sableResponse());
        }
        if (++ticks % 100 == 0) {
            long total = SableContactQueue.total(), dropped = SableContactQueue.dropped();
            LOGGER.info("[PDD-COLLISION] summary ticks=100 callbacks={} overflowDropped={} queue={} samplesPrintedAtMostPerTick=8 " +
                            "damage=DISABLED", total - lastTotal, dropped - lastDropped, SableContactQueue.size());
            lastTotal = total;
            lastDropped = dropped;
        }
    }
}

package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** §21.1-21.5: same numerical coordinates never merge a Plot with static world. */
final class SableConsumerTargetsTest {
    private static final UUID PLOT=UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static NativeCollisionTelemetry.OwnerFrame plotFrame() {
        return new NativeCollisionTelemetry.OwnerFrame(PLOT,new FrameMath.Pose(
                new FrameMath.Vec(50,70,-80),new FrameMath.Vec(512,64,1024),
                new FrameMath.Vec(1,1,1),0,0,0,1));
    }
    private static NativeCollisionTelemetry.Contact contact() {
        return new NativeCollisionTelemetry.Contact(7,-1,42,1,0,0,-1,0,0,
                0.375,0.5,0.25,50.375,70.5,-79.75,
                "minecraft:overworld",33,plotFrame(),null);
    }
    @Test void originalSableConsumerMapsNativeLocalPointToAllocatedPlotGrid() {
        var c=contact();
        assertEquals(new FrameMath.Vec(512.375,64.5,1024.25),SableConsumerTargets.candidate(c,true));
        assertEquals(new FrameMath.Vec(50.375,70.5,-79.75),SableConsumerTargets.candidate(c,false));
        assertEquals(new BlockPos(512,64,1024),SableConsumerTargets.cells(SableConsumerTargets.candidate(c,true)).getFirst());
        assertEquals(new BlockPos(50,70,-80),SableConsumerTargets.cells(SableConsumerTargets.candidate(c,false)).getFirst());
    }
    @Test void twoEffectPointsNeedIndependentWorldSpaceCorroboration() {
        assertTrue(SableConsumerTargets.corroboratedContact(contact()));
        var differentWorldPoint=new NativeCollisionTelemetry.Contact(7,-1,42,1,0,0,-1,0,0,
                0.375,0.5,0.25,51.375,70.5,-79.75,
                "minecraft:overworld",33,plotFrame(),null);
        assertFalse(SableConsumerTargets.corroboratedContact(differentWorldPoint));
        var unknownPlotFrame=new NativeCollisionTelemetry.Contact(7,-1,42,1,0,0,-1,0,0,
                0.375,0.5,0.25,50.375,70.5,-79.75,"minecraft:overworld",33);
        assertFalse(SableConsumerTargets.corroboratedContact(unknownPlotFrame));
    }
    @Test void sameContactIsCorrectWithRotatedPlotPose() {
        double c=Math.sqrt(0.5);
        var f=new NativeCollisionTelemetry.OwnerFrame(PLOT,new FrameMath.Pose(
                new FrameMath.Vec(100,75,200),new FrameMath.Vec(512,64,1024),
                new FrameMath.Vec(1,1,1),0,c,0,c));
        // 90 degrees about Y: (1, 0, 0) -> (0, 0, -1)
        var r=new NativeCollisionTelemetry.Contact(7,-1,10,1,0,0,-1,0,0,
                1,0,0,100,75,199,"minecraft:overworld",33,f,null);
        assertTrue(SableConsumerTargets.corroboratedContact(r));
        assertEquals(new FrameMath.Vec(513,64,1024),SableConsumerTargets.candidate(r,true));
    }
    @Test void epsilonBoundaryIsBoundedAndNeverEnumeratesWholeShip() {
        assertEquals(8,SableConsumerTargets.cells(new FrameMath.Vec(3,4,5)).size());
        assertEquals(1,SableConsumerTargets.cells(new FrameMath.Vec(3.5,4.5,5.5)).size());
        assertEquals(0,SableConsumerTargets.cells(new FrameMath.Vec(Double.NaN,3,3)).size());
    }
    @Test void recordEvidenceDeduplicatesSameTargetButRejectsTwoDifferentBlocks() {
        var target=new GameImpactCredit.Target("PLOT:"+PLOT,"1,2,3","minecraft:stone");
        var other=new GameImpactCredit.Target("PLOT:"+PLOT,"1,2,4","minecraft:chain");
        var same=new SableConsumerTargets.Scan();
        same.confirmed.add(target);
        same.confirmed.add(target);
        same.checked=6;
        same.skipped=2;
        var verified=same.finish("R2");
        assertTrue(verified.verified());
        assertEquals(target,verified.target());
        assertEquals(6,verified.checked());
        assertEquals("R2_PARTIAL",verified.stage());
        same.confirmed.add(other);
        var ambiguous=same.finish("R2");
        assertEquals(SableConsumerTargets.Reason.MULTIPLE_BLOCKS,ambiguous.reason());
        assertFalse(ambiguous.verified());
    }
    @Test void groupTargetAuditIsCappedWithoutEverInferringFromSampleCount() {
        assertEquals(32,SableConsumerTargets.MAX_GROUP_CHECKS);
        var missing=new SableConsumerTargets.Scan();
        missing.checked=32;
        missing.missing=SableConsumerTargets.Reason.TOO_MANY_CANDIDATES;
        var r=missing.finish("R3");
        assertFalse(r.verified());
        assertEquals(SableConsumerTargets.Reason.TOO_MANY_CANDIDATES,r.reason());
    }
    @Test void missingOwnerFrameDoesNotBecomeWorldSpace() {
        var c=new NativeCollisionTelemetry.Contact(7,-1,42,1,0,0,-1,0,0,
                0.375,0.5,0.25,50.375,70.5,-79.75,"minecraft:overworld",33);
        assertNull(SableConsumerTargets.candidate(c,true));
        assertNotNull(SableConsumerTargets.candidate(c,false));
    }
}

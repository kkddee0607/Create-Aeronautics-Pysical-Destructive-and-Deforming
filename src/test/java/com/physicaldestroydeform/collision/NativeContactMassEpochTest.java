package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class NativeContactMassEpochTest {
    private static final UUID PLOT=UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final FrameMath.Vec ZERO=new FrameMath.Vec(0,0,0);
    private static final FrameMath.Vec UNIT=new FrameMath.Vec(1,1,1);
    private static FrameMath.Pose pose(double x) {
        return new FrameMath.Pose(ZERO,new FrameMath.Vec(x,0,0),UNIT,0,0,0,1);
    }
    private static CollisionFrame.Snapshot snapshot(double x) {
        var body=new CollisionFrame.Body(PLOT,0,0,0,0,pose(x),ZERO,ZERO,
                new FrameMath.Vec(x,0,0),true,null,7);
        return new CollisionFrame.Snapshot(1,33,"minecraft:overworld",
                CollisionFrame.Phase.PRE_SOLVER,List.of(body),true);
    }
    private static NativeCollisionTelemetry.Contact contact(double x) {
        var frame=new NativeCollisionTelemetry.OwnerFrame(PLOT,pose(x));
        return new NativeCollisionTelemetry.Contact(7,-1,42,1,0,0,-1,0,0,
                0.4,0.5,0.5,1.4,0.5,0.5,"minecraft:overworld",33,frame,null);
    }
    @Test void constantOriginAcrossSubstepsPreservesOriginalEvidence() {
        var original=contact(512);
        var result=new NativeContactMassEpoch(List.of(snapshot(512),snapshot(512)))
                .validate("minecraft:overworld",33,List.of(original));
        assertEquals(0,result.maskedFrames());
        assertSame(original,result.contacts().getFirst());
    }
    @Test void shiftedMassOriginRejectsOnlyTheAffectedPlotSide() {
        var original=contact(513);
        var result=new NativeContactMassEpoch(List.of(snapshot(512),snapshot(513)))
                .validate("minecraft:overworld",33,List.of(original));
        assertEquals(1,result.maskedFrames());
        var masked=result.contacts().getFirst();
        assertNull(masked.frameA());
        assertEquals(2,SableConsumerTargets.sourceSide("WORLD:minecraft:overworld",masked,"minecraft:overworld"));
        assertNotNull(original.frameA()); // read-only, no source mutation
    }
    @Test void missingBodyInOneSubstepMustNotReuseStaleOrigin() {
        var noBody=new CollisionFrame.Snapshot(2,33,"minecraft:overworld",
                CollisionFrame.Phase.PRE_SOLVER,List.of(),true);
        var result=new NativeContactMassEpoch(List.of(snapshot(512),noBody))
                .validate("minecraft:overworld",33,List.of(contact(512)));
        assertEquals(1,result.maskedFrames());
    }
    @Test void lastEffectOriginMustMatchObservedStableOrigin() {
        var result=new NativeContactMassEpoch(List.of(snapshot(512)))
                .validate("minecraft:overworld",33,List.of(contact(513)));
        assertEquals(1,result.maskedFrames());
    }
    @Test void maskedOriginPreservesNativePollSaturationMarker() {
        var original=contact(513);
        var tagged=new NativeCollisionTelemetry.Contact(original.idA(),original.idB(),original.rawImpactScalar(),
                original.ax(),original.ay(),original.az(),original.bx(),original.by(),original.bz(),
                original.contactAX(),original.contactAY(),original.contactAZ(),
                original.contactBX(),original.contactBY(),original.contactBZ(),
                original.dimension(),original.serverTick(),original.frameA(),original.frameB(),true);
        var result=new NativeContactMassEpoch(List.of(snapshot(512),snapshot(513)))
                .validate("minecraft:overworld",33,List.of(tagged));
        assertEquals(1,result.maskedFrames());
        assertTrue(result.contacts().getFirst().capSaturated());
    }
    @Test void missingSnapshotCannotBeMistakenForStableOrigin() {
        var result=new NativeContactMassEpoch(List.of())
                .validate("minecraft:overworld",33,List.of(contact(512)));
        assertEquals(1,result.maskedFrames());
        assertNull(result.contacts().getFirst().frameA());
    }
}

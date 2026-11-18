package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.deform.MovePlan;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TargetDirectionsTest {
    private static final GameImpactCredit.Target A=new GameImpactCredit.Target("WORLD:minecraft:overworld","0,0,0","minecraft:stone");
    private static final GameImpactCredit.Target B=new GameImpactCredit.Target("WORLD:minecraft:overworld","1,0,0","minecraft:stone");
    private static final MovePlan.SignedAxes POS=new MovePlan.SignedAxes(1,0,0,true,1);
    private static final MovePlan.SignedAxes NEG=new MovePlan.SignedAxes(-1,0,0,true,1);
    @Test void unrelatedBackFaceDoesNotPoisonPriorVerifiedFace(){
        var d=new TargetDirections();
        d.observe(List.of(B),List.of(B),POS,false);
        d.observe(List.of(A,B),List.of(A),POS,false);
        assertEquals(POS,d.get(A));assertEquals(POS,d.get(B));
    }
    @Test void conflictingOrIncompleteWitnessRemainsUnknown(){
        var d=new TargetDirections();
        d.observe(List.of(A),List.of(A),POS,false);
        d.observe(List.of(A),List.of(A),NEG,false);
        assertNull(d.get(A));
        d.observe(List.of(B),List.of(B),POS,true);
        d.observe(List.of(B),List.of(B),POS,false);
        assertNull(d.get(B));
    }
    @Test void derivedFaceCannotOverrideUnknownDirectSeed(){
        var d=new TargetDirections();
        d.observe(List.of(A),List.of(),null,false);
        d.patch(A,POS);d.patch(B,POS);
        assertNull(d.get(A));assertEquals(POS,d.get(B));
    }
    @Test void paidChildInheritsButUnknownSeedDoesNot(){
        var d=new TargetDirections();
        d.observe(List.of(A),List.of(A),POS,false);
        var edge=new ForcePropagation.Edge(A,B,0,10,5,5,1,5);
        d.seed(B);d.trace(edge);assertNull(d.get(B));
        var child=new TargetDirections();child.observe(List.of(A),List.of(A),POS,false);
        child.trace(edge);assertEquals(POS,child.get(B));
    }
    @Test void eventAxesRequireActualClosingMotionAndUntruncatedSource(){
        String dim="minecraft:overworld";
        var id=java.util.UUID.fromString("a9667991-83c3-4a18-b6e6-86844635111b");
        String plot="PLOT:"+id,world="WORLD:"+dim;
        var pair=GameImpactCredit.Pair.canonical("run",dim,new GameImpactCredit.Owner(plot,1),new GameImpactCredit.Owner(world,1));
        var zero=new FrameMath.Vec(0,0,0);
        var pose=new FrameMath.Pose(zero,zero,new FrameMath.Vec(1,1,1),0,0,0,1);
        var frame=new com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.OwnerFrame(id,pose);
        var pre=new CollisionFrame.Snapshot(41,10,dim,CollisionFrame.Phase.PRE_SOLVER,
                List.of(new CollisionFrame.Body(id,0,15,0,15,pose,new FrameMath.Vec(0,-2,0),zero,zero,true,null,7)),true);
        var contact=new com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact(-1,7,100,0,1,0,0,-1,0,
                0,1,0,0,0,0,dim,10,null,frame,false);
        var d=TargetDirections.forEvent(pair,List.of(contact),List.of(pre),10,true);
        assertEquals(new MovePlan.SignedAxes(0,-1,0,true,41),d.uniformPatchAxis(world));
        assertEquals(new MovePlan.SignedAxes(0,1,0,true,41),d.axis(plot,contact));
        var capped=new com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact(-1,7,100,0,1,0,0,-1,0,
                0,1,0,0,0,0,dim,10,null,frame,true);
        var unknown=TargetDirections.forEvent(pair,List.of(capped),List.of(pre),10,true);
        assertFalse(unknown.active());assertNull(unknown.axis(world,capped));
        assertTrue(unknown.summary().contains("gate=NATIVE_CAP"));
        assertNull(TargetDirections.forEvent(pair,List.of(contact),List.of(pre),10,false).axis(world,contact));
    }
    @Test void signedDirectionRequiresTheCorrespondingActualFace(){
        var inward=new MovePlan.SignedAxes(1,0,0,true,1);
        assertTrue(TargetDirections.onInwardFace(0,.5,.5,inward));
        assertFalse(TargetDirections.onInwardFace(1,.5,.5,inward));
        assertFalse(TargetDirections.onInwardFace(0,1.5,.5,inward));
        assertFalse(TargetDirections.onInwardFace(Double.NaN,.5,.5,inward));
    }
    @Test void diagonalNeedsBothFaces(){
        var inward=new MovePlan.SignedAxes(1,0,-1,true,1);
        assertTrue(TargetDirections.onInwardFace(0,.5,1,inward));
        assertFalse(TargetDirections.onInwardFace(0,.5,.5,inward));
    }
}

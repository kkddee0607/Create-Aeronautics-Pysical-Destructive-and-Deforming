package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.SuperFixBilateralSurfaceTest.*;

/** Tests the production surface resolver with actual captured poses, not synthetic damage seeds. */
class FinalLastFixSurfaceTest {
    private static FrameMath.Pose pose(double x,double y){
        return new FrameMath.Pose(new FrameMath.Vec(x,y,0),ZERO,new FrameMath.Vec(1,1,1),0,0,0,1);
    }
    private static NativeCollisionTelemetry.Contact record(FrameMath.Pose effect){
        return new NativeCollisionTelemetry.Contact(7,-1,3000,0,1,0,0,-1,0,
                .5,65,.5,.5,65,.5,DIM,5,new NativeCollisionTelemetry.OwnerFrame(ID,effect),null);
    }
    private static CollisionFrame.Snapshot pre(FrameMath.Pose p){
        return new CollisionFrame.Snapshot(1,5,DIM,CollisionFrame.Phase.PRE_SOLVER,
                List.of(new CollisionFrame.Body(ID,0,15,0,15,p,ZERO,ZERO,ZERO,true,null,7)),true);
    }
    @Test void observedNormalCrossingRecoversWholeFaceEvenWithSeparatedEffectWitnesses(){
        var frame=BilateralContactSurface.fromRecord(PAIR,record(pose(0,.2)),5,List.of(pre(pose(0,-.2))));
        assertNotNull(frame);
        var result=BilateralContactSurface.reconstruct(frame,reader(false,false),32768);
        assertFalse(result.incomplete(),result.gate());assertEquals(25,result.matches().size());
        assertTrue(result.matches().contains(new BilateralContactSurface.Match(target(0,2,2),target(1,2,2))));
    }
    @Test void persistentGapWithoutObservedCrossingDoesNotCertifyFace(){
        var frame=BilateralContactSurface.fromRecord(PAIR,record(pose(0,-.2)),5,List.of(pre(pose(0,-.4))));
        assertNotNull(frame);assertTrue(BilateralContactSurface.reconstruct(frame,reader(false,false),32768).matches().isEmpty());
    }
    @Test void tangentialDriftCannotAuthorizeNormalOnlySweep(){
        var frame=BilateralContactSurface.fromRecord(PAIR,record(pose(0,.2)),5,List.of(pre(pose(.4,-.2))));
        assertNotNull(frame);assertTrue(BilateralContactSurface.reconstruct(frame,reader(false,false),32768).matches().isEmpty());
    }
    @Test void normalCrossingDoesNotFillAnOppositeHole(){
        var frame=BilateralContactSurface.fromRecord(PAIR,record(pose(0,.2)),5,List.of(pre(pose(0,-.2))));
        var result=BilateralContactSurface.reconstruct(frame,reader(true,false),32768);
        assertEquals(24,result.matches().size());assertFalse(result.matches().stream().anyMatch(m->m.b().equals(target(1,2,2))));
    }
    @Test void alignedOneThreeFiveAndRingUseOnlyActualContactCells(){
        for(int size:new int[]{1,3,5}){
            var result=BilateralContactSurface.reconstruct(BilateralContactSurface.fromRecord(PAIR,record(pose(0,0)),5),surface(size,false),32768);
            assertFalse(result.incomplete(),result.gate());assertEquals(size*size,result.matches().size());
        }
        var ring=BilateralContactSurface.reconstruct(BilateralContactSurface.fromRecord(PAIR,record(pose(0,0)),5),surface(5,true),32768);
        assertFalse(ring.incomplete(),ring.gate());assertEquals(16,ring.matches().size());
        assertFalse(ring.matches().stream().anyMatch(m->m.a().equals(target(0,2,2))||m.b().equals(target(1,2,2))));
    }
    private static BilateralContactSurface.ShapeReader surface(int size,boolean ring){
        return new BilateralContactSurface.ShapeReader(){
            @Override public BilateralContactSurface.Cell get(int side,net.minecraft.core.BlockPos p){
                int x=p.getX(),z=p.getZ();
                if(x<0||x>=size||z<0||z>=size||p.getY()!=(side==0?64:65))return null;
                if(ring&&x>0&&x<size-1&&z>0&&z<size-1)return null;
                return new BilateralContactSurface.Cell(target(side,x,z),p,List.of(CUBE));
            }
            @Override public boolean exposed(int side,FrameMath.Vec p,com.physicaldestroydeform.deform.MovePlan.SignedAxes axis){return true;}
        };
    }

}

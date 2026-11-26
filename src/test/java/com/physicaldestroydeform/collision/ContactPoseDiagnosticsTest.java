package com.physicaldestroydeform.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContactPoseDiagnosticsTest {
    private static final FrameMath.Vec ZERO=new FrameMath.Vec(0,0,0),ONE=new FrameMath.Vec(1,1,1);
    private static final FrameMath.Pose ID=new FrameMath.Pose(ZERO,ZERO,ONE,0,0,0,1);
    private static final AABB CUBE=new AABB(0,0,0,1,1,1);
    @Test void touchingAndSeparatedAxisAlignedBoxesHaveExpectedSignedGap(){
        assertEquals(0,ContactPoseDiagnostics.signedGap(ID,BlockPos.ZERO,CUBE,ID,new BlockPos(1,0,0),CUBE),1e-9);
        assertEquals(1,ContactPoseDiagnostics.signedGap(ID,BlockPos.ZERO,CUBE,ID,new BlockPos(2,0,0),CUBE),1e-9);
        assertEquals(-1,ContactPoseDiagnostics.signedGap(ID,BlockPos.ZERO,CUBE,ID,BlockPos.ZERO,CUBE),1e-9);
    }
    @Test void rotatedCubeAtOriginReportsOverlapButNotAuthority(){
        var pose=new FrameMath.Pose(new FrameMath.Vec(.5,.5,.5),new FrameMath.Vec(.5,.5,.5),ONE,0,0,Math.sin(Math.PI/8),Math.cos(Math.PI/8));
        assertTrue(ContactPoseDiagnostics.signedGap(ID,BlockPos.ZERO,CUBE,pose,BlockPos.ZERO,CUBE)<0);
        assertTrue(ContactPoseDiagnostics.signedGap(ID,new BlockPos(4,0,0),CUBE,pose,BlockPos.ZERO,CUBE)>0);
    }
    @Test void scaledOrInvalidPoseIsNotInterpretedAsRigidContact(){
        var pose=new FrameMath.Pose(ZERO,ZERO,new FrameMath.Vec(2,1,1),0,0,0,1);
        assertTrue(Double.isNaN(ContactPoseDiagnostics.signedGap(ID,BlockPos.ZERO,CUBE,pose,BlockPos.ZERO,CUBE)));
    }
}

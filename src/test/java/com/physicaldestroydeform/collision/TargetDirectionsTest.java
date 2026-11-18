package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.deform.MovePlan;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TargetDirectionsTest {
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

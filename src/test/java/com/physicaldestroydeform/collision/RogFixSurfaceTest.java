package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.deform.MovePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.SuperFixBilateralSurfaceTest.*;

class RogFixSurfaceTest {
    private static final MovePlan.SignedAxes DOWN=new MovePlan.SignedAxes(0,-1,0,true,5), UP=new MovePlan.SignedAxes(0,1,0,true,5);
    private static BilateralContactSurface.Frame tilted(double dy){
        double angle=.1;
        var b=new FrameMath.Pose(new FrameMath.Vec(0,65+dy,0),new FrameMath.Vec(0,65,0),
                new FrameMath.Vec(1,1,1),0,0,Math.sin(angle/2),Math.cos(angle/2));
        return new BilateralContactSurface.Frame(POSE,b,DOWN,UP,new FrameMath.Vec(.1,65,.5),new FrameMath.Vec(.1,65,.5));
    }
    private static BilateralContactSurface.Cell cell(int side,int y,AABB... boxes){
        return new BilateralContactSurface.Cell(new GameImpactCredit.Target(side==0?PLOT:WORLD,"0,"+y+",0","minecraft:stone"),new BlockPos(0,y,0),List.of(boxes));
    }
    @Test void tiltedFaceUsesPositiveAreaWithinDepthBandNotDistantCorners(){
        assertTrue(BilateralContactSurface.positiveOverlap(tilted(-.02),cell(0,64,CUBE),cell(1,65,CUBE)));
    }
    @Test void tiltedExactEdgeIsNotPositiveContactArea(){
        assertFalse(BilateralContactSurface.positiveOverlap(tilted(0),cell(0,64,CUBE),cell(1,65,CUBE)));
    }
    @Test void separatedTiltedFaceDoesNotCreateContactArea(){
        assertFalse(BilateralContactSurface.positiveOverlap(tilted(.01),cell(0,64,CUBE),cell(1,65,CUBE)));
    }
    private static BilateralContactSurface.Frame flat(){
        return new BilateralContactSurface.Frame(POSE,POSE,DOWN,UP,new FrameMath.Vec(.5,64.5,.5),new FrameMath.Vec(.5,64.5,.5));
    }
    @Test void sameCellCoveredFaceCannotAuthorizeInitialContact(){
        var lower=cell(0,64,new AABB(0,0,0,1,.5,1),new AABB(0,.5,0,.5,1,1));
        assertFalse(BilateralContactSurface.positiveOverlap(flat(),lower,cell(1,64,new AABB(0,.5,0,.4,1,1))));
    }
    @Test void partiallyCoveredFaceRetainsRealExposedArea(){
        var lower=cell(0,64,new AABB(0,0,0,1,.5,1),new AABB(0,.5,0,.5,1,1));
        assertTrue(BilateralContactSurface.positiveOverlap(flat(),lower,cell(1,64,new AABB(.1,.5,0,.9,1,1))));
    }
    @Test void outsideCellIsExactOwnerLocalFaceFootprint(){
        assertEquals(List.of(new BlockPos(0,65,0)),BilateralContactSurface.exteriorCellPositions(cell(0,64,CUBE),CUBE,DOWN));
        assertEquals(List.of(new BlockPos(0,64,0)),BilateralContactSurface.exteriorCellPositions(cell(1,65,CUBE),CUBE,UP));
        var half=new AABB(0,0,0,1,.5,1);
        assertTrue(BilateralContactSurface.exteriorCellPositions(cell(0,64,half),half,DOWN).isEmpty());
    }
    @Test void nineByNineSparseAnchorReachesInterior(){
        var frame=new BilateralContactSurface.Frame(POSE,POSE,DOWN,UP,new FrameMath.Vec(.5,65,.5),new FrameMath.Vec(.5,65,.5));
        var result=BilateralContactSurface.reconstruct(frame,new BilateralContactSurface.ShapeReader(){
            public BilateralContactSurface.Cell get(int side,BlockPos p){
                if(p.getY()!=(side==0?64:65)||p.getX()<0||p.getX()>=9||p.getZ()<0||p.getZ()>=9)return null;
                return new BilateralContactSurface.Cell(target(side,p.getX(),p.getZ()),p,List.of(CUBE));
            }
            public boolean exposed(int side,FrameMath.Vec p,MovePlan.SignedAxes inward){return true;}
        },32768);
        assertFalse(result.incomplete(),result.gate());assertEquals(81,result.matches().size());
        assertTrue(result.matches().contains(new BilateralContactSurface.Match(target(0,4,4),target(1,4,4))));
    }
}

package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.deform.MovePlan;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** In-game failures: asymmetric rotated grids, repeated anchors and incomplete
 * faces MUST NOT turn into extra credits or partial corner-only force. */
class OriginFixSurfaceTest {
    private static final AABB CUBE=new AABB(0,0,0,1,1,1);
    private static final FrameMath.Vec ZERO=new FrameMath.Vec(0,0,0),ONE=new FrameMath.Vec(1,1,1);
    private static final FrameMath.Pose ID=new FrameMath.Pose(ZERO,ZERO,ONE,0,0,0,1);
    private static final MovePlan.SignedAxes DOWN=new MovePlan.SignedAxes(0,-1,0,true,3);
    private static final MovePlan.SignedAxes UP=new MovePlan.SignedAxes(0,1,0,true,3);
    private static GameImpactCredit.Target target(int side,int x,int z){
        return new GameImpactCredit.Target(side==0?"PLOT:origin":"WORLD:minecraft:overworld",
                x+","+(side==0?64:65)+","+z,"minecraft:stone");
    }
    private static BilateralContactSurface.Frame frame(double aX,double bX,double shift){
        var offset=new FrameMath.Pose(new FrameMath.Vec(shift,0,0),ZERO,ONE,0,0,0,1);
        return new BilateralContactSurface.Frame(ID,offset,DOWN,UP,
                new FrameMath.Vec(aX,65,.5),new FrameMath.Vec(bX,65,.5));
    }
    private static BilateralContactSurface.ShapeReader twoSideReader(int aLength,int bLength){
        return new BilateralContactSurface.ShapeReader(){
            @Override public BilateralContactSurface.Cell get(int side,BlockPos p){
                int width=side==0?aLength:bLength;
                if(p.getX()<0||p.getX()>=width||p.getZ()!=0||p.getY()!=(side==0?64:65))return null;
                return new BilateralContactSurface.Cell(target(side,p.getX(),0),p,List.of(CUBE));
            }
            @Override public boolean exposed(int side,FrameMath.Vec p,MovePlan.SignedAxes inward){return true;}
        };
    }
    @Test void bSideFrontierReachesSecondOpposingCellWhenASideHasOneCell(){
        // A covers [0,1]; B is shifted -0.5, so its 0 and 1 cells both overlap A.
        var result=BilateralContactSurface.reconstruct(frame(.75,1.25,-.5),twoSideReader(1,2),100);
        assertFalse(result.incomplete(),result.gate());
        assertEquals(2,result.matches().size());
        assertTrue(result.matches().contains(new BilateralContactSurface.Match(target(0,0,0),target(1,0,0))));
        assertTrue(result.matches().contains(new BilateralContactSurface.Match(target(0,0,0),target(1,1,0))));
    }
    @Test void allNativeAnchorsShareOneSurfaceTraversalNotOneTraversalPerRecord(){
        var reader=new BilateralContactSurface.ShapeReader(){
            @Override public BilateralContactSurface.Cell get(int side,BlockPos p){
                if(p.getX()<0||p.getX()>=5||p.getZ()<0||p.getZ()>=5||p.getY()!=(side==0?64:65))return null;
                return new BilateralContactSurface.Cell(target(side,p.getX(),p.getZ()),p,List.of(CUBE));
            }
            @Override public boolean exposed(int side,FrameMath.Vec p,MovePlan.SignedAxes inward){return true;}
        };
        var one=BilateralContactSurface.reconstruct(frame(.5,.5,0),reader,1000);
        var anchors=new ArrayList<BilateralContactSurface.Frame>();
        for(int i=0;i<100;i++)anchors.add(frame(i%5+.5,i%5+.5,0));
        var batched=BilateralContactSurface.reconstructAll(anchors,reader,1000);
        assertFalse(batched.incomplete(),batched.gate());
        assertEquals(25,one.matches().size());
        assertEquals(one.matches().size(),batched.matches().size());
        assertTrue(batched.checks()<=one.checks()+50,
                "Same physical surface must not repeat a 25-cell traversal for 100 witnesses");
    }
    @Test void largeAlignedFaceTestsOnlyTrueOpposingCellsNotBoundaryFanout(){
        // 100x100 must stay inside the unchanged 32,768 geometry budget.
        // With inclusive tangent grid boundaries the earlier implementation
        // spent >80,000 polygon checks and failed closed despite a flat face.
        var reader=new BilateralContactSurface.ShapeReader(){
            @Override public BilateralContactSurface.Cell get(int side,BlockPos p){
                if(p.getX()<0||p.getX()>=100||p.getZ()<0||p.getZ()>=100
                        ||p.getY()!=(side==0?64:65))return null;
                return new BilateralContactSurface.Cell(target(side,p.getX(),p.getZ()),p,List.of(CUBE));
            }
            @Override public boolean exposed(int side,FrameMath.Vec point,MovePlan.SignedAxes inward){
                return true;
            }
        };
        var result=BilateralContactSurface.reconstruct(frame(.5,.5,0),reader,32768);
        assertFalse(result.incomplete(),result.gate());
        assertEquals(10000,result.matches().size());
        assertEquals(10000,result.checks(),"Do not polygon-test tangent-only adjacent cells");
    }
    @Test void overflowNeverPublishesAnIncompleteSurface(){
        var result=BilateralContactSurface.reconstruct(frame(.5,.5,0),twoSideReader(5,5),1);
        assertTrue(result.incomplete());
        assertTrue(result.matches().isEmpty());
    }
    @Test void moreThanEightVerifiedOpposingCellsAreKeptNotDiscarded(){
        var a=target(0,0,0);
        var pairs=new ArrayList<BilateralContactSurface.Match>();
        for(int x=0;x<9;x++)pairs.add(new BilateralContactSurface.Match(a,target(1,x,0)));
        var base=List.of(new ContactCreditLedger.Region(a,null));
        var regions=SableContactRegions.mergeBilateralPairs(base,pairs);
        assertEquals(19,regions.size());
        assertTrue(regions.contains(new ContactCreditLedger.Region(a,null)),
                "An independently unresolved native observation must not be silently erased");
        assertEquals(9,regions.stream().filter(r->a.equals(r.a())
                &&r.opposingCandidates().size()==1).count());
        assertEquals(9,regions.stream().filter(r->r.b()!=null).count());
    }
    @Test void anUnprovedNativeOtherSideIsNotInventedByTheSurfaceMerge(){
        var nativeOnly=target(0,15,0);
        var a=target(0,0,0);var b=target(1,0,0);
        var current=List.of(new ContactCreditLedger.Region(nativeOnly,null),
                new ContactCreditLedger.Region(a,null));
        var merged=SableContactRegions.mergeBilateralPairs(current,
                List.of(new BilateralContactSurface.Match(a,b)));
        assertTrue(merged.contains(new ContactCreditLedger.Region(nativeOnly,null)));
        assertTrue(merged.contains(new ContactCreditLedger.Region(a,null,List.of(b))));
        assertEquals(4,merged.size());
    }
    @Test void closedComponentDoesNotEraseExistingNonemptyOppositeEvidence(){
        var a=target(0,0,0);var b=target(1,0,0);var c=target(1,1,0);
        var prior=new ContactCreditLedger.Region(a,null,List.of(c));
        var merged=SableContactRegions.mergeBilateralPairs(List.of(prior),
                List.of(new BilateralContactSurface.Match(a,b)));
        assertTrue(merged.contains(prior));
        assertTrue(merged.contains(new ContactCreditLedger.Region(a,null,List.of(b))));
        assertTrue(merged.contains(new ContactCreditLedger.Region(null,b,List.of(a))));
    }
    @Test void certifiedComponentDoesNotSuppressUnpairedNativeSeed(){
        var a=target(0,0,0);var b=target(1,0,0);var other=target(0,4,0);
        var evidence=List.of(new ContactCreditLedger.Region(a,b),new ContactCreditLedger.Region(other,null));
        assertTrue(evidence.contains(new ContactCreditLedger.Region(other,null)));
        assertEquals(evidence,SableContactRegions.mergeBilateralPairs(evidence,List.of()));
    }
    @Test void failedExpansionDoesNotIntroduceAnyPendingSeeds(){
        var a=target(0,0,0);var b=target(1,0,0);
        var base=List.of(new ContactCreditLedger.Region(a,b));
        var failed=BilateralContactSurface.reconstruct(frame(.5,.5,0),twoSideReader(5,5),1);
        assertTrue(failed.incomplete());assertTrue(failed.matches().isEmpty());
        assertEquals(base,SableContactRegions.mergeBilateralPairs(base,failed.matches()));
    }
}

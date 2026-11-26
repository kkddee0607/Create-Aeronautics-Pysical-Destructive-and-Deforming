package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.deform.MovePlan;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** finalFix: a valid contact component cannot be invalidated by another
 * unproved contact or a bounded scan, and an imprecise Native witness is NOT
 * a substitute for exact opposing exterior VoxelShape proof. */
class FinalFixContactSurfaceTest {
    private static final FrameMath.Vec ZERO=new FrameMath.Vec(0,0,0);
    private static final FrameMath.Vec ONE=new FrameMath.Vec(1,1,1);
    private static final FrameMath.Pose POSE=new FrameMath.Pose(ZERO,ZERO,ONE,0,0,0,1);
    private static final MovePlan.SignedAxes DOWN=new MovePlan.SignedAxes(0,-1,0,true,5);
    private static final MovePlan.SignedAxes UP=new MovePlan.SignedAxes(0,1,0,true,5);
    private static final AABB CUBE=new AABB(0,0,0,1,1,1);
    private static final String DIM="minecraft:overworld";
    private static final UUID PLOT_UUID=UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String OWNER_A="PLOT:"+PLOT_UUID,OWNER_B="WORLD:"+DIM;
    private static final GameImpactCredit.Pair PAIR=GameImpactCredit.Pair.canonical("finalfix",DIM,
            new GameImpactCredit.Owner(OWNER_A,1),new GameImpactCredit.Owner(OWNER_B,1));
    private static GameImpactCredit.Target target(int side,int x){
        return new GameImpactCredit.Target(side==0?OWNER_A:OWNER_B,x+","+(side==0?64:65)+",0","minecraft:stone");
    }
    private static BilateralContactSurface.Frame frame(double x){
        return new BilateralContactSurface.Frame(POSE,POSE,DOWN,UP,
                new FrameMath.Vec(x,65,.5),new FrameMath.Vec(x,65,.5));
    }
    /** Two disconnected contact components: one isolated cell and a long face.
     * A real Native anchor is available at each component. */
    private static BilateralContactSurface.ShapeReader twoComponents(){
        return new BilateralContactSurface.ShapeReader(){
            @Override public BilateralContactSurface.Cell get(int side,BlockPos p){
                if(p.getY()!=(side==0?64:65)||p.getZ()!=0||p.getX()<0||p.getX()>8||p.getX()==1)
                    return null;
                return new BilateralContactSurface.Cell(target(side,p.getX()),p,List.of(CUBE));
            }
            @Override public boolean exposed(int side,FrameMath.Vec p,MovePlan.SignedAxes inward){return true;}
        };
    }
    @Test void completeDisconnectedComponentsRemainIndependentlyCertified(){
        var result=BilateralContactSurface.reconstructAll(
                List.of(frame(.5),frame(2.5)),twoComponents(),32);
        assertFalse(result.incomplete(),result.gate());
        assertEquals(8,result.matches().size());
        assertEquals("BILATERAL_SURFACE",result.gate());
        assertTrue(result.matches().contains(new BilateralContactSurface.Match(target(0,0),target(1,0))));
        assertTrue(result.matches().contains(new BilateralContactSurface.Match(target(0,8),target(1,8))));
    }
    @Test void laterBudgetOverflowCannotDiscardEarlierCompleteComponent(){
        var result=BilateralContactSurface.reconstructAll(
                List.of(frame(.5),frame(2.5)),twoComponents(),3);
        assertTrue(result.incomplete());
        assertEquals("PARTIAL_CERTIFIED_COMPONENTS",result.gate());
        assertEquals(List.of(new BilateralContactSurface.Match(target(0,0),target(1,0))),result.matches(),
                "The second face is incomplete; the first closed face retains proof");
    }
    @Test void incompleteFirstComponentMustNotPublishPartialFace(){
        var result=BilateralContactSurface.reconstructAll(
                List.of(frame(2.5),frame(.5)),twoComponents(),2);
        assertTrue(result.incomplete());
        assertTrue(result.matches().isEmpty());
    }
    @Test void identicalNativeWitnessesReuseOneComponentTraversal(){
        var result=BilateralContactSurface.reconstructAll(
                java.util.Collections.nCopies(100,frame(2.5)),twoComponents(),32);
        assertFalse(result.incomplete(),result.gate());
        assertEquals(7,result.matches().size());
        assertEquals(7,result.checks());
    }
    @Test void anOverlyStrictNativeWitnessGapCannotRejectVerifiedShapeContact(){
        // The effect witness differs by 0.02 blocks; authoritative faces still
        // lie on the same physical plane. Old along>1e-5 rejected this event.
        var witness=new NativeCollisionTelemetry.Contact(7,-1,100,
                0,1,0,0,-1,0,.5,65,.5,.5,65.02,.5,DIM,5,
                new NativeCollisionTelemetry.OwnerFrame(PLOT_UUID,POSE),null,true);
        var anchored=BilateralContactSurface.fromRecord(PAIR,witness,5);
        assertNotNull(anchored,"Native cap and witness noise cannot veto independent actual shape proof");
        var result=BilateralContactSurface.reconstruct(anchored,twoComponents(),32);
        assertEquals(List.of(new BilateralContactSurface.Match(target(0,0),target(1,0))),result.matches());
    }
    @Test void aLargeInconsistentNativeWitnessCannotAuthorizeShapeTraversal(){
        var witness=new NativeCollisionTelemetry.Contact(7,-1,100,
                0,1,0,0,-1,0,.5,65,.5,.5,65.2,.5,DIM,5,
                new NativeCollisionTelemetry.OwnerFrame(PLOT_UUID,POSE),null,true);
        var frame=BilateralContactSurface.fromRecord(PAIR,witness,5);
        assertNotNull(frame);
        assertTrue(BilateralContactSurface.reconstruct(frame,twoComponents(),32).matches().isEmpty());
    }
    @Test void sourceReadBudgetExhaustionCannotCertifyAnOpenContactComponent(){
        var reader=new BilateralContactSurface.ShapeReader(){
            int remaining=1;boolean exhausted;
            @Override public BilateralContactSurface.Cell get(int side,BlockPos p){
                if(remaining--<=0){exhausted=true;return null;}
                return new BilateralContactSurface.Cell(target(side,p.getX()),p,List.of(CUBE));
            }
            @Override public boolean exposed(int side,FrameMath.Vec p,MovePlan.SignedAxes inward){return true;}
            @Override public boolean budgetExhausted(){return exhausted;}
        };
        var result=BilateralContactSurface.reconstruct(frame(.5),reader,20);
        assertTrue(result.incomplete());
        assertTrue(result.matches().isEmpty());
    }
    @Test void rogTraceDoesNotChangeCertifiedComponentsOrBudget(){
        var frames=List.of(frame(.5),frame(2.5));
        var expected=BilateralContactSurface.reconstructAll(frames,twoComponents(),3);
        com.physicaldestroydeform.diagnostics.RogTrace.start();
        try{
            com.physicaldestroydeform.diagnostics.RogTrace.begin(1);
            assertEquals(expected,BilateralContactSurface.reconstructAll(frames,twoComponents(),3));
        }finally{com.physicaldestroydeform.diagnostics.RogTrace.stop();}
    }
    @Test void unknownFirstComponentDoesNotVetoIndependentLaterComponent(){
        var known=twoComponents();
        var reader=new BilateralContactSurface.ShapeReader(){
            @Override public BilateralContactSurface.Cell get(int side,BlockPos p){
                return p.getX()<2?null:known.get(side,p);
            }
            @Override public boolean unresolvedCell(int side,BlockPos p){return p.getX()==0;}
            @Override public boolean exposed(int side,FrameMath.Vec p,MovePlan.SignedAxes inward){return true;}
        };
        var result=BilateralContactSurface.reconstructAll(List.of(frame(.5),frame(2.5)),reader,32);
        assertTrue(result.incomplete());
        assertEquals(7,result.matches().size());
        assertFalse(result.matches().stream().anyMatch(m->m.a().equals(target(0,0))));
    }
    @Test void unknownExteriorCannotBeCachedAsKnownNoncontact(){
        var known=twoComponents();
        var reader=new BilateralContactSurface.ShapeReader(){
            boolean unknown;
            public BilateralContactSurface.Cell get(int side,BlockPos p){return known.get(side,p);}
            public boolean exposed(int side,FrameMath.Vec p,MovePlan.SignedAxes axis){
                if(p.x()>3&&p.x()<4){unknown=true;return false;}return true;
            }
            public boolean takeUncertainExterior(){boolean b=unknown;unknown=false;return b;}
        };
        var result=BilateralContactSurface.reconstructAll(List.of(frame(.5),frame(2.5),frame(4.5)),reader,32);
        assertTrue(result.incomplete());
        assertEquals(List.of(new BilateralContactSurface.Match(target(0,0),target(1,0))),result.matches());
    }
}

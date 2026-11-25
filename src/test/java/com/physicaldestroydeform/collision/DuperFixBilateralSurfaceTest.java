package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A sparse *single* Native witness must suffice when bilateral real shapes
 * prove a connected face. The old four-corner-only fixtures cannot test this. */
class DuperFixBilateralSurfaceTest {
    private static final String DIM="minecraft:overworld",WORLD="WORLD:"+DIM;
    private static final UUID ID=UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final String PLOT="PLOT:"+ID;
    private static final FrameMath.Vec ZERO=new FrameMath.Vec(0,0,0);
    private static final FrameMath.Vec ONE=new FrameMath.Vec(1,1,1);
    private static final FrameMath.Pose IDENTITY=new FrameMath.Pose(ZERO,ZERO,ONE,0,0,0,1);
    private static final AABB CUBE=new AABB(0,0,0,1,1,1);
    private static final GameImpactCredit.Pair PAIR=GameImpactCredit.Pair.canonical("duper",DIM,
            new GameImpactCredit.Owner(PLOT,1),new GameImpactCredit.Owner(WORLD,1));
    private static GameImpactCredit.Target target(int side,int x,int z){
        return new GameImpactCredit.Target(side==0?PLOT:WORLD,x+","+(side==0?64:65)+","+z,"minecraft:stone");
    }
    private static BilateralContactSurface.Frame frame(FrameMath.Pose pose){
        var a=new com.physicaldestroydeform.deform.MovePlan.SignedAxes(0,-1,0,true,5);
        var b=new com.physicaldestroydeform.deform.MovePlan.SignedAxes(0,1,0,true,5);
        return new BilateralContactSurface.Frame(pose,pose,a,b,new FrameMath.Vec(.5,65,.5),new FrameMath.Vec(.5,65,.5));
    }
    private static BilateralContactSurface.ShapeReader reader(int aWidth,int bWidth,boolean hole,boolean internal){
        return new BilateralContactSurface.ShapeReader(){
            @Override public BilateralContactSurface.Cell get(int side,BlockPos p){
                int w=side==0?aWidth:bWidth;
                int y=side==0?64:65;
                if(internal&&side==0&&p.getY()==65&&p.getX()>=0&&p.getX()<w&&p.getZ()>=0&&p.getZ()<w)
                    return new BilateralContactSurface.Cell(new GameImpactCredit.Target(PLOT,
                            p.getX()+",65,"+p.getZ(),"minecraft:stone"),p,List.of(CUBE));
                if(p.getY()!=y||p.getX()<0||p.getZ()<0||p.getX()>=w||p.getZ()>=w
                        ||hole&&side==1&&p.getX()==2&&p.getZ()==2)return null;
                return new BilateralContactSurface.Cell(target(side,p.getX(),p.getZ()),p,List.of(CUBE));
            }
            @Override public boolean exposed(int side,FrameMath.Vec p,
                    com.physicaldestroydeform.deform.MovePlan.SignedAxes inward){
                var beyond=p.minus(new FrameMath.Vec(inward.x(),inward.y(),inward.z()).times(.001));
                for(var at:SableConsumerTargets.cells(beyond)){
                    var cell=get(side,at);
                    if(cell==null)continue;
                    if(beyond.x()>at.getX()&&beyond.x()<at.getX()+1
                            &&beyond.y()>at.getY()&&beyond.y()<at.getY()+1
                            &&beyond.z()>at.getZ()&&beyond.z()<at.getZ()+1)return false;
                }
                return true;
            }
        };
    }
    private static BilateralContactSurface.Result area(BilateralContactSurface.Frame f,
            BilateralContactSurface.ShapeReader reader){
        return BilateralContactSurface.reconstruct(f,reader,20_000);
    }
    @Test void oneNativeWitnessRecoversWholeFiveByFiveFaceWithoutCornerExtrapolation(){
        var contact=new NativeCollisionTelemetry.Contact(7,-1,100,
                0,1,0,0,-1,0,.5,65,.5,.5,65,.5,DIM,5,
                new NativeCollisionTelemetry.OwnerFrame(ID,IDENTITY),null);
        var proven=BilateralContactSurface.fromRecord(PAIR,contact,5);
        assertNotNull(proven,"One same-effect dual-frame witness is sufficient as an anchor");
        var result=area(proven,reader(5,5,false,false));
        assertFalse(result.incomplete(),result.gate());
        assertEquals("BILATERAL_SURFACE",result.gate());
        assertEquals(25,result.matches().size());
        assertTrue(result.matches().contains(new BilateralContactSurface.Match(target(0,2,2),target(1,2,2))));
    }
    @Test void actualOppositeHoleDoesNotReceiveFabricatedForce(){
        var result=area(frame(IDENTITY),reader(5,5,true,false));
        assertEquals(24,result.matches().size());
        assertFalse(result.matches().stream().anyMatch(m->m.b().equals(target(1,2,2))));
    }
    @Test void narrowerOppositeFaceCanOnlyPayItsNineRealCells(){
        var result=area(frame(IDENTITY),reader(5,3,false,false));
        assertEquals(9,result.matches().size());
        assertTrue(result.matches().stream().allMatch(m->Integer.parseInt(m.a().localBlock().split(",")[0])<3));
    }
    @Test void internalInterfaceIsNotAnExteriorCollisionFace(){
        assertTrue(area(frame(IDENTITY),reader(5,5,false,true)).matches().isEmpty());
    }
    @Test void bothPlotsRotatedTogetherRetainSameContactSurface(){
        double half=Math.sin(Math.PI/8),w=Math.cos(Math.PI/8);
        var rotate=new FrameMath.Pose(ZERO,ZERO,ONE,0,half,0,w);
        assertEquals(25,area(frame(rotate),reader(5,5,false,false)).matches().size());
    }
    @Test void missingOrStaleNativeRecordCannotAuthorizeArea(){
        var bad=new NativeCollisionTelemetry.Contact(7,-1,100,
                0,1,0,0,-1,0,.5,65,.5,.5,65,.5,DIM,4,
                new NativeCollisionTelemetry.OwnerFrame(ID,IDENTITY),null);
        assertNull(BilateralContactSurface.fromRecord(PAIR,bad,5));
        var separated=new BilateralContactSurface.Frame(IDENTITY,
                new FrameMath.Pose(new FrameMath.Vec(0,1,0),ZERO,ONE,0,0,0,1),
                frame(IDENTITY).inwardA(),frame(IDENTITY).inwardB(),
                new FrameMath.Vec(.5,65,.5),new FrameMath.Vec(.5,65,.5));
        assertTrue(area(separated,reader(5,5,false,false)).matches().isEmpty());
    }
    @Test void nearbyButSeparatedFacesDoNotBecomeAContactArea(){
        var shifted=new BilateralContactSurface.Frame(IDENTITY,
                new FrameMath.Pose(new FrameMath.Vec(0,.01,0),ZERO,ONE,0,0,0,1),
                frame(IDENTITY).inwardA(),frame(IDENTITY).inwardB(),
                new FrameMath.Vec(.5,65,.5),new FrameMath.Vec(.5,65,.5));
        assertEquals(0,area(shifted,reader(5,5,false,false)).matches().size());
    }
    @Test void shallowRealPenetrationUsesEachBodysActualExteriorFace(){
        var compressed=new BilateralContactSurface.Frame(IDENTITY,
                new FrameMath.Pose(new FrameMath.Vec(0,-.02,0),ZERO,ONE,0,0,0,1),
                frame(IDENTITY).inwardA(),frame(IDENTITY).inwardB(),
                new FrameMath.Vec(.5,65,.5),new FrameMath.Vec(.5,65,.5));
        assertEquals(25,area(compressed,reader(5,5,false,false)).matches().size());
    }
    private static BilateralContactSurface.ShapeReader disconnectedFace(int interruptedSide){
        return new BilateralContactSurface.ShapeReader(){
            @Override public BilateralContactSurface.Cell get(int side,BlockPos p){
                if(p.getX()<0||p.getX()>1||p.getZ()!=0||p.getY()!=(side==0?64:65))return null;
                var shape=side==interruptedSide
                        ?(p.getX()==0?new AABB(0,0,0,.4,1,1):new AABB(.6,0,0,1,1,1))
                        :CUBE;
                return new BilateralContactSurface.Cell(target(side,p.getX(),0),p,List.of(shape));
            }
            @Override public boolean exposed(int side,FrameMath.Vec p,
                    com.physicaldestroydeform.deform.MovePlan.SignedAxes inward){return true;}
        };
    }
    private static BilateralContactSurface.Frame anchoredAt(double x){
        return new BilateralContactSurface.Frame(IDENTITY,IDENTITY,
                frame(IDENTITY).inwardA(),frame(IDENTITY).inwardB(),
                new FrameMath.Vec(x,65,.5),new FrameMath.Vec(x,65,.5));
    }
    @Test void disconnectedPartialCollisionBoxesDoNotBridgeTheFloodFill(){
        var result=area(anchoredAt(.2),disconnectedFace(0));
        assertFalse(result.incomplete(),result.gate());
        assertEquals(List.of(new BilateralContactSurface.Match(target(0,0,0),target(1,0,0))),result.matches(),
                "A connected B face must not jump over a missing A surface seam");
    }
    @Test void connectedAFaceCannotBridgeDisconnectedBBoxes(){
        var result=area(anchoredAt(.2),disconnectedFace(1));
        assertFalse(result.incomplete(),result.gate());
        assertEquals(List.of(new BilateralContactSurface.Match(target(0,0,0),target(1,0,0))),result.matches(),
                "The same no-bridge rule must hold with A and B exchanged");
    }
    @Test void independentNativeWitnessesCanValidateTwoDisconnectedAreas(){
        var result=BilateralContactSurface.reconstructAll(
                List.of(anchoredAt(.2),anchoredAt(1.8)),disconnectedFace(0),100);
        assertFalse(result.incomplete(),result.gate());
        assertEquals(2,result.matches().size());
        assertTrue(result.matches().contains(new BilateralContactSurface.Match(target(0,0,0),target(1,0,0))));
        assertTrue(result.matches().contains(new BilateralContactSurface.Match(target(0,1,0),target(1,1,0))));
    }
    @Test void pairedAreaMergesIntoSingleOriginalCreditWithoutNewNativeExactPairs(){
        var matches=area(frame(IDENTITY),reader(5,5,false,false)).matches();
        var root=new ContactCreditLedger.Region(target(0,0,0),target(1,0,0));
        var evidence=SableContactRegions.mergeBilateralPairs(List.of(root),matches);
        assertEquals(49,evidence.size());
        assertEquals(1,evidence.stream().filter(r->r.a()!=null&&r.b()!=null).count());
        assertEquals(48,evidence.stream().filter(r->!r.opposingCandidates().isEmpty()).count());
        var policy=new GameImpactCredit(GameImpactCredit.Settings.provisional());
        policy.step(new GameImpactCredit.Frame(PAIR,1,List.of(),true,false,null,null));
        policy.step(new GameImpactCredit.Frame(PAIR,2,List.of(),true,false,null,null));
        var approved=policy.step(new GameImpactCredit.Frame(PAIR,5,
                List.of(new GameImpactCredit.ObservedForce(7,-1,50_000,0)),false,true,
                target(0,0,0),target(1,0,0)));
        var material=new MaterialProperties(0,255,0,0,false);
        var ledger=new ContactCreditLedger();
        var grant=ledger.issuePropagatedTargets(PAIR,approved,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                evidence,(from,x,y,z)->ForcePropagation.Probe.empty(),ignored->material,
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                Map.of(),null,ignored->material);
        assertEquals(ContactCreditLedger.Status.ISSUED,grant.status(),ledger.lastPropagationFailure());
        assertEquals(25,grant.propagationA().shares().stream().filter(s->s.layer()==0).count());
        assertEquals(25,grant.propagationB().shares().stream().filter(s->s.layer()==0).count());
        assertEquals(grant.issuedQ(),grant.accounted(),1e-5);
    }
    @Test void interruptedBudgetNeverPaysAPartialUnverifiedFace(){
        var result=BilateralContactSurface.reconstruct(frame(IDENTITY),reader(5,5,false,false),4);
        assertTrue(result.incomplete());assertTrue(result.matches().isEmpty());
    }
}

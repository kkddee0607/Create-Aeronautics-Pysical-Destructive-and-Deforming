package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Runtime-equivalent bilateral expansion and F-first ledger, no unilateral polygon. */
final class SuperFixBilateralSurfaceTest {
    static final String DIM="minecraft:overworld";
    static final UUID ID=UUID.fromString("12345678-1234-1234-1234-123456789abc");
    static final String PLOT="PLOT:"+ID,WORLD="WORLD:"+DIM;
    static final GameImpactCredit.Pair PAIR=GameImpactCredit.Pair.canonical("face-proof",DIM,new GameImpactCredit.Owner(PLOT,1),new GameImpactCredit.Owner(WORLD,1));
    static final FrameMath.Vec ZERO=new FrameMath.Vec(0,0,0);
    static final FrameMath.Pose POSE=new FrameMath.Pose(ZERO,ZERO,new FrameMath.Vec(1,1,1),0,0,0,1);
    static final AABB CUBE=new AABB(0,0,0,1,1,1);
    static GameImpactCredit.Target target(int side,int x,int z){return new GameImpactCredit.Target(side==0?PLOT:WORLD,x+","+(side==0?64:65)+","+z,"minecraft:stone");}
    static List<NativeCollisionTelemetry.Contact> contacts(boolean cap,double gap){
        var list=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int x:new int[]{0,4})for(int z:new int[]{0,4})list.add(new NativeCollisionTelemetry.Contact(7,-1,3000,0,1,0,0,-1,0,
                x+.5,65,z+.5,x+.5,65+gap,z+.5,DIM,5,new NativeCollisionTelemetry.OwnerFrame(ID,POSE),null,cap));
        if(cap)while(list.size()<NativeCollisionTelemetry.NATIVE_EFFECT_MAX_RECORDS)list.add(list.get(list.size()%4));
        return List.copyOf(list);
    }
    static List<ContactCreditLedger.Region> roots(){var list=new ArrayList<ContactCreditLedger.Region>();
        for(int x:new int[]{0,4})for(int z:new int[]{0,4})list.add(new ContactCreditLedger.Region(target(0,x,z),target(1,x,z)));
        return List.copyOf(list);
    }
    static BilateralContactSurface.ShapeReader reader(boolean missingCentre,boolean unknownCentre){
        return new BilateralContactSurface.ShapeReader(){
            public BilateralContactSurface.Cell get(int side,BlockPos p){
                if(p.getX()<0||p.getX()>=5||p.getZ()<0||p.getZ()>=5||p.getY()!=(side==0?64:65))return null;
                if(side==1&&missingCentre&&p.getX()==2&&p.getZ()==2)return null;
                return new BilateralContactSurface.Cell(target(side,p.getX(),p.getZ()),p,List.of(CUBE));
            }
            public boolean unresolvedCell(int side,BlockPos p){return unknownCentre&&side==1&&p.getX()==2&&p.getZ()==2&&p.getY()==65;}
            public boolean exposed(int side,FrameMath.Vec p,com.physicaldestroydeform.deform.MovePlan.SignedAxes axis){return true;}
        };
    }
    static BilateralContactSurface.Result surface(boolean cap,boolean missing,boolean unknown,double gap){
        return BilateralContactSurface.reconstructAll(contacts(cap,gap).stream().map(c->BilateralContactSurface.fromRecord(PAIR,c,5)).filter(Objects::nonNull).toList(),reader(missing,unknown),32768);
    }
    private static GameImpactCredit.Decision approved(){
        var credit=new GameImpactCredit(GameImpactCredit.Settings.provisional());
        credit.step(new GameImpactCredit.Frame(PAIR,1,List.of(),true,false,null,null));
        credit.step(new GameImpactCredit.Frame(PAIR,2,List.of(),true,false,null,null));
        return credit.step(new GameImpactCredit.Frame(PAIR,5,List.of(new GameImpactCredit.ObservedForce(7,-1,50_000,0)),false,true,target(0,0,0),target(1,0,0)));
    }
    private static ContactCreditLedger.Grant grant(ContactCreditLedger ledger,GameImpactCredit.Decision decision,List<ContactCreditLedger.Region> regions){
        return ledger.issueContactSeeds(PAIR,decision,ContactCreditLedger.Source.SABLE_POST_EFFECT,regions,0,0,
                (from,x,y,z)->ForcePropagation.Probe.empty(),t->new MaterialProperties(0,255,0,0,false),
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),Map.of(),null,null);
    }
    @Test void fourNativeCornersProveTwentyFiveFullFaceTargetsPerOwnerInProductionLedger(){
        var found=surface(false,false,false,0);assertFalse(found.incomplete());assertEquals(25,found.matches().size());
        var regions=SableContactRegions.mergeBilateralPairs(roots(),found.matches());
        assertEquals(46,regions.size());
        var ledger=new ContactCreditLedger();var decision=approved();var grant=grant(ledger,decision,regions);
        assertTrue(grant.credited(),ledger.lastPropagationFailure());
        assertEquals(25,grant.propagationA().shares().stream().filter(s->s.layer()==0).count());
        assertEquals(25,grant.propagationB().shares().stream().filter(s->s.layer()==0).count());
        assertEquals(0,grant.propagationA().directUnresolvedF(),1e-7);assertEquals(0,grant.propagationB().directUnresolvedF(),1e-7);
        assertEquals(grant.issuedQ(),grant.accounted(),1e-5);
        assertEquals(ContactCreditLedger.Status.DUPLICATE_CAUSE,grant(ledger,decision,regions).status());
    }
    @Test void nativeCapKeepsDamageDirectionUnknownButCanStillPairExactObservedSurface(){
        var records=contacts(true,0);assertEquals(100,records.size());
        var body=new CollisionFrame.Body(ID,0,15,0,15,POSE,ZERO,ZERO,ZERO,true,null,7);
        var pre=new CollisionFrame.Snapshot(1,5,DIM,CollisionFrame.Phase.PRE_SOLVER,List.of(body),true);
        var directions=TargetDirections.forEvent(PAIR,records,List.of(pre),5,true);
        assertFalse(directions.active());assertNull(directions.axis(PLOT,records.getFirst()));
        assertEquals(25,surface(true,false,false,0).matches().size());
    }
    @Test void absentOppositeVoxelCannotGainAFictitiousMaterialPair(){
        var found=surface(false,true,false,0);assertFalse(found.incomplete());assertEquals(24,found.matches().size());
        var merged=SableContactRegions.mergeBilateralPairs(roots(),found.matches());
        assertTrue(merged.stream().noneMatch(r->target(0,2,2).equals(r.a())||target(1,2,2).equals(r.b())));
        var g=grant(new ContactCreditLedger(),approved(),merged);assertTrue(g.credited());
        assertEquals(24,g.propagationA().shares().stream().filter(s->s.layer()==0).count());
        assertEquals(24,g.propagationB().shares().stream().filter(s->s.layer()==0).count());
        assertEquals(0,g.propagationA().directUnresolvedF(),1e-7);assertEquals(g.issuedQ(),g.accounted(),1e-5);
    }
    @Test void incoherentNativeContactPlaneDoesNotVetoNativeRoots(){
        var found=surface(false,false,false,.2);assertTrue(found.matches().isEmpty());
        assertEquals(roots(),SableContactRegions.mergeBilateralPairs(roots(),found.matches()));
    }
    @Test void noPrePoseRequiredForIndependentDirectMaterialIdentity(){
        var contacts=contacts(false,0);var d=TargetDirections.forEvent(PAIR,contacts,List.of(),5,true);
        assertFalse(d.active());assertNull(d.axis(PLOT,contacts.getFirst()));
        var r=SableContactRegions.collect(PAIR,contacts,(c,owner,budget)->new SableContactRegions.Check(List.of(target(owner.equals(PLOT)?0:1,(int)c.contactAX(),(int)c.contactAZ())),1));
        assertEquals(4,r.regions().size());assertTrue(r.verified());
    }
    @Test void unknownShapeEvidenceMustNotReplaceACertifiedNativeCornerPair(){
        var found=surface(false,true,true,0);assertTrue(found.incomplete());assertTrue(found.matches().isEmpty());
        assertEquals(roots(),SableContactRegions.mergeBilateralPairs(roots(),found.matches()));
    }
}

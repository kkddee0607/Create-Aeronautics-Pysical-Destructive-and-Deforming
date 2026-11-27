package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.OwnerFrame;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.SuperFixBilateralSurfaceTest.*;

class AstraFixContactPatchTest {
    private static final ContactBatchStamp STAMP=new ContactBatchStamp(new Object(),new Object(),DIM,5,5);
    private static FrameMath.Pose effect(){return new FrameMath.Pose(new FrameMath.Vec(.1,.2,0),ZERO,new FrameMath.Vec(1,1,1),0,0,Math.sin(.025),Math.cos(.025));}
    private static Contact witness(double x,double z,double bx,double bz,long poll,double scalar){
        return new Contact(7,-1,scalar,0,1,0,0,-1,0,x,65,z,bx,65,bz,DIM,5,new OwnerFrame(ID,effect()),null,false,STAMP,poll);
    }
    private static List<Contact> corners(int size){var out=new ArrayList<Contact>();for(double x:new double[]{.1,size-.1})for(double z:new double[]{.1,size-.1})out.add(witness(x,z,x,z,1,3000));return out;}
    private static BilateralContactSurface.ShapeReader surface(int a,int b,boolean ring,boolean unknown){
        return new BilateralContactSurface.ShapeReader(){
            public BilateralContactSurface.Cell get(int side,BlockPos pos){
                int n=side==0?a:b,x=pos.getX(),z=pos.getZ();
                if(pos.getY()!=(side==0?64:65)||x<0||z<0||x>=n||z>=n)return null;
                if(ring&&x>0&&z>0&&x<n-1&&z<n-1||unknown&&side==1&&x==2&&z==2)return null;
                return new BilateralContactSurface.Cell(target(side,x,z),pos,List.of(CUBE));
            }
            public boolean unresolvedCell(int side,BlockPos p){return unknown&&side==1&&p.equals(new BlockPos(2,65,2));}
            public boolean exposed(int side,FrameMath.Vec p,com.physicaldestroydeform.deform.MovePlan.SignedAxes axis){return true;}
        };
    }
    private static ContactFacePatch.Result model(List<Contact> records,BilateralContactSurface.ShapeReader reader,int cap){
        return ContactFacePatch.reconstruct(records.stream().map(c->new ContactFacePatch.Witness(c,BilateralContactSurface.patchFrame(PAIR,c,5))).toList(),reader,cap);
    }
    @Test void rotatedEffectWithIndependentLocalAnchorsRecoversEveryCell(){
        for(int n:new int[]{1,3,5,9}){
            var r=model(corners(n),surface(n,n,false,false),32768);
            assertFalse(r.incomplete());assertEquals(n*n,r.pairs().size(),"size="+n);
            assertTrue(r.pairs().contains(new BilateralContactSurface.Match(target(0,n/2,n/2),target(1,n/2,n/2))));
        }
    }
    @Test void ringDoesNotFillItsHoleAndPartialCounterpartDoesNotExtrapolate(){
        var ring=model(corners(5),surface(5,5,true,false),32768);
        assertEquals(16,ring.pairs().size());assertFalse(ring.incomplete());
        assertEquals(9,model(corners(3),surface(5,3,false,false),32768).pairs().size());
    }
    @Test void sparseAndCollinearEvidenceCannotAuthorizeArea(){
        assertTrue(model(corners(5).subList(0,1),surface(5,5,false,false),32768).pairs().isEmpty());
        var line=List.of(witness(.1,.1,.1,.1,1,3000),witness(2,.1,2,.1,1,3000),witness(4.9,.1,4.9,.1,1,3000));
        assertTrue(model(line,surface(5,5,false,false),32768).pairs().isEmpty());
    }
    @Test void separatePollsOrScalarsCannotCreateOnePatch(){
        var records=corners(5);var split=new ArrayList<Contact>();int i=0;
        for(var c:records)split.add(witness(c.contactAX(),c.contactAZ(),c.contactBX(),c.contactBZ(),++i,3000));
        assertTrue(model(split,surface(5,5,false,false),32768).pairs().isEmpty());
        split.clear();i=0;for(var c:records)split.add(witness(c.contactAX(),c.contactAZ(),c.contactBX(),c.contactBZ(),1,3000+ ++i));
        assertTrue(model(split,surface(5,5,false,false),32768).pairs().isEmpty());
    }
    @Test void incompatibleCorrespondenceAndUnloadedCellsCannotCertifyPatch(){
        var records=new ArrayList<>(corners(5));records.set(3,witness(4.9,4.9,3.9,4.9,1,3000));
        assertTrue(model(records,surface(5,5,false,false),32768).pairs().isEmpty());
        var unknown=model(corners(5),surface(5,5,false,true),32768);assertTrue(unknown.incomplete());assertTrue(unknown.pairs().isEmpty());
        var capped=model(corners(5),surface(5,5,false,false),1);assertTrue(capped.incomplete());assertTrue(capped.pairs().isEmpty());
    }
    @Test void realDisjointIslandsCannotUseOneHullToBridgeGap(){
        var reader=new BilateralContactSurface.ShapeReader(){
            public BilateralContactSurface.Cell get(int side,BlockPos p){return p.getX()==2?null:surface(5,5,false,false).get(side,p);}
            public boolean exposed(int side,FrameMath.Vec p,com.physicaldestroydeform.deform.MovePlan.SignedAxes axis){return true;}
        };
        assertTrue(model(corners(5),reader,32768).pairs().isEmpty());
    }
    private static GameImpactCredit.Decision decision(){
        var p=new GameImpactCredit(GameImpactCredit.Settings.provisional());
        p.step(new GameImpactCredit.Frame(PAIR,1,List.of(),true,false,null,null));p.step(new GameImpactCredit.Frame(PAIR,2,List.of(),true,false,null,null));
        return p.step(new GameImpactCredit.Frame(PAIR,5,List.of(new GameImpactCredit.ObservedForce(7,-1,50000,0)),false,true,target(0,0,0),target(1,0,0)));
    }
    private static ContactCreditLedger.Grant grant(ContactCreditLedger ledger,GameImpactCredit.Decision decision,List<ContactCreditLedger.Region> regions){
        return ledger.issueContactSeeds(PAIR,decision,ContactCreditLedger.Source.SABLE_POST_EFFECT,regions,0,0,
                (from,x,y,z)->ForcePropagation.Probe.empty(),t->new MaterialProperties(0,255,0,0,false),GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),Map.of(),null,null);
    }
    @Test void integratedLedgerUsesUnionOnceWithTwentyFiveEqualSeedsPerSide(){
        var base=new SableContactRegions.Result(List.of(new ContactCreditLedger.Region(target(0,0,0),target(1,0,0))),SableContactRegions.Status.PROVED,1,1,0,"R2","R2");
        var merged=SableContactRegions.mergePatch(base,model(corners(5),surface(5,5,false,false),32768));
        assertEquals(48,merged.patchSeeds().size());assertEquals(49,merged.regions().size());
        var ledger=new ContactCreditLedger();var d=decision();var g=grant(ledger,d,merged.regions());assertTrue(g.credited(),ledger.lastPropagationFailure());
        for(var side:List.of(g.propagationA(),g.propagationB())){
            assertEquals(25,side.shares().stream().filter(s->s.layer()==0).count());
            for(var share:side.shares())assertEquals(d.peakForce()/2/25,share.allocatedF(),1e-7);
            assertEquals(0,side.directUnresolvedF(),1e-7);
        }
        assertEquals(g.issuedQ(),g.accounted(),1e-6);assertEquals(ContactCreditLedger.Status.DUPLICATE_CAUSE,grant(ledger,d,merged.regions()).status());
    }
    @Test void patchReservationRefinementRequiresSameWitnessEvidence(){
        var c1=witness(.1,.1,.1,.1,1,3000);var c2=witness(.2,.1,.2,.1,1,3000);
        var candidates=List.of(target(0,0,0),target(0,0,1));
        var base=SableContactRegions.collect(PAIR,List.of(c1,c2),(c,owner,budget)->owner.equals(PLOT)?new SableContactRegions.Check(candidates,2):new SableContactRegions.Check(List.of(target(1,0,0)),1));
        assertEquals(2,base.reservedA());var a=Set.copyOf(candidates);
        var pairs=List.of(new BilateralContactSurface.Match(candidates.get(0),target(1,0,0)),new BilateralContactSurface.Match(candidates.get(1),target(1,0,0)));
        var one=SableContactRegions.mergePatch(base,new ContactFacePatch.Result(pairs,Map.of(c1,a),Map.of(),1,false));
        assertEquals(1,one.reservedA());
        var both=SableContactRegions.mergePatch(base,new ContactFacePatch.Result(pairs,Map.of(c1,a,c2,a),Map.of(),1,false));
        assertEquals(0,both.reservedA());
        assertEquals(2,both.patchSeeds().size());
    }
    @Test void modelDoesNotAverageMixedOpposingElasticity(){
        var a=target(0,1,1);var b=target(1,1,1);var c=target(1,1,2);
        var regions=List.of(new ContactCreditLedger.Region(target(0,0,0),target(1,0,0)),new ContactCreditLedger.Region(a,null,List.of(b,c),true));
        var d=decision();var g=new ContactCreditLedger().issueContactSeeds(PAIR,d,ContactCreditLedger.Source.SABLE_POST_EFFECT,regions,0,0,
                (from,x,y,z)->ForcePropagation.Probe.empty(),t->new MaterialProperties(0,255,0,t.equals(c)?100:0,false),GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),Map.of(),null,null);
        assertTrue(g.credited());assertEquals(d.peakForce()/4,g.propagationA().directUnresolvedF(),1e-7);
        assertTrue(g.propagationA().shares().stream().noneMatch(v->v.target().equals(a)));
        assertEquals(g.issuedQ(),g.accounted(),1e-6);
    }
    @Test void patchCannotAuthorizeFreshRegionalImpact(){
        var exact=List.of(new ContactCreditLedger.Region(target(0,0,0),target(1,0,0)));
        var patch=new ContactCreditLedger.Region(target(0,1,1),null,List.of(target(1,1,1)),true);
        assertFalse(RegionalContactProof.hasNewCells(RegionalContactProof.capture(exact),List.of(patch)));
        assertTrue(RegionalContactProof.capture(List.of(patch)).a().contains(patch.a().localBlock()));
        assertFalse(grant(new ContactCreditLedger(),decision(),List.of(patch)).credited());
    }
    @Test void exactAuthorizationDoesNotExecuteGamePatchBeforeSeal(){
        var base=new SableContactRegions.Result(roots(),SableContactRegions.Status.PROVED,1,1,0,"R2","R2");
        int[] calls={0,0};var p=new SableContactRegions.Prepared(base,()->{calls[0]++;return base;},r->{calls[1]++;return r;});
        assertSame(base,p.exact());assertArrayEquals(new int[]{1,0},calls);assertSame(base,p.expand());assertSame(base,p.expand());assertArrayEquals(new int[]{1,1},calls);
    }
}

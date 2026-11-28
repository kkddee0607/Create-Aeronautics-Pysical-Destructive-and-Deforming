package com.physicaldestroydeform.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.FrameMath.*;
import static com.physicaldestroydeform.collision.GameImpactCredit.*;

class DuoMoreFixRegionTest {
    private static final Vec X=new Vec(1,0,0);
    private static final DirectionalContactRegions.Direction DIR=new DirectionalContactRegions.Direction(X,X.times(-1));
    private static final Pose POSE=new Pose(new Vec(0,0,0),new Vec(0,0,0),new Vec(1,1,1),0,0,0,1);
    private static Target target(String owner,int x,String state){return new Target(owner,x+",0,0",state);}
    private static DcrtSpatialFootprint.Face face(int x,int y,double y0,double y1){
        var p=new BlockPos(x,y,0);
        return DcrtSpatialFootprint.Face.of(new Target("WORLD:d",x+","+y+",0","stone"),p,new AABB(0,y0,0,1,y1,1),0,1);
    }
    private static SableContactRegions.Result result(ContactCreditLedger.Region... regions){
        return new SableContactRegions.Result(List.of(regions),SableContactRegions.Status.PROVED,0,0,0,"TEST","TEST");
    }
    @Test void grantFailureDoesNotConsumeAndRequiresFreshProof(){
        var h=new DirectionalContactRegions();var t=h.newTrack(DIR,1,false);
        assertTrue(h.eligible(t,true,false));h.reserve(t);
        assertFalse(t.consumed);assertEquals(-1,t.lastCredit);assertFalse(h.eligible(t,true,false));
        h.finish(t,1,false);assertFalse(t.consumed);assertTrue(h.touch(t,2,false));
        assertFalse(h.eligible(t,false,false));assertTrue(h.eligible(t,true,false));
        h.reserve(t);h.finish(t,2,true);assertTrue(t.consumed);assertEquals(2,t.lastCredit);
        h.finish(t,1,false);assertTrue(t.consumed);assertTrue(h.touch(t,3,false));assertFalse(h.eligible(t,true,false));
    }
    @Test void uncertainGrantDoesNotRestoreFreshAuthority(){
        var h=new DirectionalContactRegions();var t=h.newTrack(DIR,1,false);h.reserve(t);h.finish(t,1,false);h.unknown(t,1);
        assertTrue(h.touch(t,2,false));assertFalse(h.eligible(t,true,false));
    }
    @Test void capDoesNotEvictConsumedTrack(){
        var h=new DirectionalContactRegions();var t=h.newTrack(DIR,1,false);h.reserve(t);h.finish(t,1,true);
        for(int i=1;i<DirectionalContactRegions.MAX_TRACKS;i++)assertNotNull(h.newTrack(DIR,1,false));
        assertNull(h.newTrack(DIR,2,false));assertTrue(t.consumed);assertEquals(DirectionalContactRegions.MAX_TRACKS,h.tracks().size());
    }
    @Test void actualShapeConnectivityNotCellAdjacency(){
        var left=face(0,0,0,.25);var right=face(0,0,.75,1);var bridge=face(0,0,.25,.75);
        assertFalse(left.connected(right));assertTrue(left.connected(bridge));assertTrue(bridge.connected(right));
        assertTrue(DcrtSpatialFootprint.disconnected(List.of(left),List.of(right),(f,p)->p.equals(left.cell())?List.of(left,right):List.of(),new DcrtSpatialFootprint.Work()));
        assertFalse(DcrtSpatialFootprint.disconnected(List.of(left),List.of(right),(f,p)->p.equals(left.cell())?List.of(left,bridge,right):List.of(),new DcrtSpatialFootprint.Work()));
        assertFalse(DcrtSpatialFootprint.disconnected(List.of(left),List.of(right),(f,p)->null,new DcrtSpatialFootprint.Work()));
    }
    @Test void sameFaceOrSweptRelativeTranslationStaysContinuous(){
        var f=face(0,0,0,1);var surface=new DcrtSpatialFootprint.Surface(List.of(f),List.of(f));
        var old=new DcrtSpatialFootprint.Sample(new Vec(0,.5,.5),new Vec(0,.5,.5),POSE,POSE);
        var probe=DcrtSpatialFootprint.Probe.of(List.of(old));
        assertEquals(DcrtSpatialFootprint.Relation.CONNECTED,surface.relation(probe,old));
        var moved=new Pose(new Vec(0,2,0),new Vec(0,0,0),new Vec(1,1,1),0,0,0,1);
        var next=new DcrtSpatialFootprint.Sample(new Vec(0,2.5,.5),new Vec(0,-1.5,.5),POSE,moved);
        assertEquals(DcrtSpatialFootprint.Relation.CONNECTED,surface.relation(probe,next));
        var remote=new DcrtSpatialFootprint.Sample(new Vec(0,5,.5),new Vec(0,5,.5),POSE,POSE);
        assertEquals(DcrtSpatialFootprint.Relation.DISJOINT,surface.relation(probe,remote));
        assertEquals(DcrtSpatialFootprint.Relation.UNKNOWN,surface.relation(null,remote));
    }
    @Test void directPatchOverlapKeepsPeerEvidenceWithoutSecondPatchSeed(){
        var a=target("PLOT:a",0,"stone");var b=target("WORLD:d",1,"stone");var peer=target("WORLD:d",2,"iron");
        var exact=new ContactCreditLedger.Region(a,b);var patch=new ContactCreditLedger.Region(a,null,List.of(peer),true);
        var out=DcrtContactBatch.combine(List.of(result(exact),result(patch),result(exact)));
        assertTrue(out.verified());assertTrue(out.regions().contains(exact));assertTrue(out.regions().contains(new ContactCreditLedger.Region(a,null,List.of(peer))));
        assertFalse(out.regions().stream().anyMatch(ContactCreditLedger.Region::ownerLocalPatch));assertFalse(out.patchSeeds().contains(a));assertEquals(0,out.reservedA());
    }
    @Test void conflictingPatchModelsHoldOnlyAffectedTarget(){
        var a=target("PLOT:a",0,"stone");var other=target("PLOT:a",1,"stone");var b=target("WORLD:d",1,"stone");var peer=target("WORLD:d",2,"iron");
        var out=DcrtContactBatch.combine(List.of(result(new ContactCreditLedger.Region(a,null,List.of(b),true)),result(new ContactCreditLedger.Region(a,null,List.of(peer),true),new ContactCreditLedger.Region(other,b))));
        assertTrue(out.verified());assertEquals(other,out.firstA());assertFalse(out.patchSeeds().contains(a));assertEquals(1,out.reservedA());
    }
    @Test void finiteForceSaturatesButInvalidInputDoesNotIssue(){
        var s=Settings.provisional();double cap=40_000_000;
        assertEquals(3_999_999.7,s.candidate(cap-1),1e-8);assertEquals(3_999_999.8,s.candidate(cap),1e-8);
        assertEquals(s.candidate(cap),s.candidate(cap+1));assertEquals(s.candidate(cap),s.candidate(Double.MAX_VALUE));
        assertEquals(0,s.candidate(Double.NaN));assertEquals(0,s.candidate(Double.POSITIVE_INFINITY));assertEquals(0,s.candidate(Double.NEGATIVE_INFINITY));
        assertTrue(s.candidate(11)<1);assertEquals(1,s.candidate(12));
    }
}

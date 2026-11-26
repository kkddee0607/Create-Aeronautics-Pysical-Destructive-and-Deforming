package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regress the actual material-only surface bridge into issuePropagatedTargets.
 * Native points establish an event and real boundary; synthetic shape probes
 * stand in for the server-only VoxelShape reader, not a new solver contact. */
final class SuperFixBilateralSurfaceTest {
    private static final String DIM="minecraft:overworld";
    private static final UUID ID=UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final String PLOT="PLOT:"+ID;
    private static final String WORLD="WORLD:"+DIM;
    private static final FrameMath.Vec ZERO=new FrameMath.Vec(0,0,0);
    private static final FrameMath.Pose POSE=new FrameMath.Pose(ZERO,ZERO,
            new FrameMath.Vec(1,1,1),0,0,0,1);
    private record Fixture(GameImpactCredit.Pair pair,
                           List<NativeCollisionTelemetry.Contact> contacts,
                           CollisionFrame.Snapshot pre,
                           ContactFacePatch.Result[] patches,
                           List<ContactCreditLedger.Region> initial,
                           TargetDirections directions) {
        SableContactRegions.SurfacePairing paired(){
            return SableContactRegions.pairVerifiedSurfaces(initial,patches,
                    new BlockPos[]{BlockPos.ZERO,BlockPos.ZERO},directions,
                    (side,point,axis)->{
                        if(!point.finite()||axis==null||point.y()!=65
                                ||axis.x()!=0||axis.z()!=0
                                ||axis.y()!=(side==0?-1:1))return null;
                        int x=(int)Math.floor(point.x()),z=(int)Math.floor(point.z());
                        if(x<0||x>=5||z<0||z>=5)return null;
                        return target(side,x,z);
                    });
        }
    }
    private static GameImpactCredit.Target target(int side,int x,int z){
        return new GameImpactCredit.Target(side==0?PLOT:WORLD,
                x+","+(side==0?64:65)+","+z,"minecraft:stone");
    }
    private static Fixture fixture(boolean saturated,boolean missingCentre,double noise){
        var pair=GameImpactCredit.Pair.canonical("face-proof",DIM,
                new GameImpactCredit.Owner(PLOT,1),new GameImpactCredit.Owner(WORLD,1));
        var frame=new NativeCollisionTelemetry.OwnerFrame(ID,POSE);
        var contacts=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int x:new int[]{0,4})for(int z:new int[]{0,4}){
            double y=65+(x==4&&z==4?noise:0);
            contacts.add(new NativeCollisionTelemetry.Contact(7,-1,3000,
                    0,1,0,0,-1,0,x+.5,y,z+.5,x+.5,y,z+.5,DIM,5,frame,null,saturated));
        }
        // Match the actual Sable native truncation boundary of 100 entries.
        if(saturated){
            var four=List.copyOf(contacts);
            while(contacts.size()<NativeCollisionTelemetry.NATIVE_EFFECT_MAX_RECORDS)
                contacts.add(four.get(contacts.size()%four.size()));
        }
        var body=new CollisionFrame.Body(ID,0,15,0,15,POSE,ZERO,ZERO,ZERO,true,null,7);
        var pre=new CollisionFrame.Snapshot(1,5,DIM,CollisionFrame.Phase.PRE_SOLVER,List.of(body),true);
        var roots=new ArrayList<ContactCreditLedger.Region>();
        var plotRoots=new ArrayList<GameImpactCredit.Target>();
        var worldRoots=new ArrayList<GameImpactCredit.Target>();
        for(int x:new int[]{0,4})for(int z:new int[]{0,4}){
            var a=target(0,x,z);var b=target(1,x,z);
            plotRoots.add(a);worldRoots.add(b);
            roots.add(new ContactCreditLedger.Region(a,b));
        }
        var a=ContactFacePatch.reconstruct(DIM,PLOT,BlockPos.ZERO,plotRoots,contacts,
                (pos,p)->pos.getX()>=0&&pos.getX()<5&&pos.getZ()>=0&&pos.getZ()<5
                        &&pos.getY()==64?target(0,pos.getX(),pos.getZ()):null);
        var b=ContactFacePatch.reconstruct(DIM,WORLD,BlockPos.ZERO,worldRoots,contacts,
                (pos,p)->pos.getX()>=0&&pos.getX()<5&&pos.getZ()>=0&&pos.getZ()<5
                        &&pos.getY()==65&&(!missingCentre||pos.getX()!=2||pos.getZ()!=2)
                        ?target(1,pos.getX(),pos.getZ()):null);
        for(var t:a.added())roots.add(new ContactCreditLedger.Region(t,null));
        for(var t:b.added())roots.add(new ContactCreditLedger.Region(null,t));
        return new Fixture(pair,List.copyOf(contacts),pre,new ContactFacePatch.Result[]{a,b},
                List.copyOf(roots),TargetDirections.forEvent(pair,contacts,List.of(pre),5,true));
    }
    @Test void fourNativeCornersProveTwentyFiveFullFaceTargetsPerOwnerInProductionLedger(){
        var fixture=fixture(false,false,0);
        assertEquals(21,fixture.patches()[0].added().size());
        assertEquals(21,fixture.patches()[1].added().size());
        var paired=fixture.paired();
        assertEquals(21,paired.pairedA());assertEquals(21,paired.pairedB());
        assertEquals(46,paired.regions().size());
        assertEquals(42,paired.regions().stream().filter(r->!r.opposingCandidates().isEmpty()).count());
        var policy=new GameImpactCredit(GameImpactCredit.Settings.provisional());
        policy.step(new GameImpactCredit.Frame(fixture.pair(),1,List.of(),true,false,null,null));
        policy.step(new GameImpactCredit.Frame(fixture.pair(),2,List.of(),true,false,null,null));
        var approved=policy.step(new GameImpactCredit.Frame(fixture.pair(),5,
                List.of(new GameImpactCredit.ObservedForce(7,-1,50_000,0)),false,true,
                target(0,0,0),target(1,0,0)));
        assertTrue(approved.credited());
        var ledger=new ContactCreditLedger();
        var material=new MaterialProperties(0,255,0,0,false);
        var grant=ledger.issuePropagatedTargets(fixture.pair(),approved,
                ContactCreditLedger.Source.SABLE_POST_EFFECT,paired.regions(),
                (from,x,y,z)->ForcePropagation.Probe.empty(),ignored->material,
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                Map.of(),null,ignored->material);
        assertEquals(ContactCreditLedger.Status.ISSUED,grant.status(),ledger.lastPropagationFailure());
        assertEquals(25,grant.propagationA().shares().stream().filter(s->s.layer()==0).count());
        assertEquals(25,grant.propagationB().shares().stream().filter(s->s.layer()==0).count());
        assertTrue(grant.propagationA().endReason().contains("CONTACT_UNPROVED=0"));
        assertTrue(grant.propagationB().endReason().contains("CONTACT_UNPROVED=0"));
        assertEquals(grant.issuedQ(),grant.accounted(),1e-5);
        assertEquals(ContactCreditLedger.Status.DUPLICATE_CAUSE,
                ledger.issue(fixture.pair(),approved,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                        paired.regions()).status());
    }
    @Test void nativeCapKeepsDamageDirectionUnknownButCanStillPairExactObservedSurface(){
        var fixture=fixture(true,false,0);
        assertEquals(NativeCollisionTelemetry.NATIVE_EFFECT_MAX_RECORDS,fixture.contacts().size());
        assertFalse(fixture.directions().active());
        assertTrue(fixture.directions().summary().contains("gate=NATIVE_CAP"));
        assertNull(fixture.directions().axis(PLOT,fixture.contacts().getFirst()));
        assertEquals(21,fixture.paired().pairedA());
        assertEquals(21,fixture.paired().pairedB());
    }
    @Test void absentOppositeVoxelCannotGainAFictitiousMaterialPair(){
        var fixture=fixture(false,true,0);
        var paired=fixture.paired();
        assertEquals(20,paired.pairedA());
        assertEquals(20,paired.pairedB());
        assertTrue(paired.regions().contains(new ContactCreditLedger.Region(target(0,2,2),null)));
        assertTrue(paired.regions().stream().noneMatch(r->r.opposingCandidates().contains(target(1,2,2))));
        var policy=new GameImpactCredit(GameImpactCredit.Settings.provisional());
        policy.step(new GameImpactCredit.Frame(fixture.pair(),1,List.of(),true,false,null,null));
        policy.step(new GameImpactCredit.Frame(fixture.pair(),2,List.of(),true,false,null,null));
        var approved=policy.step(new GameImpactCredit.Frame(fixture.pair(),5,
                List.of(new GameImpactCredit.ObservedForce(7,-1,50_000,0)),false,true,
                target(0,0,0),target(1,0,0)));
        var material=new MaterialProperties(0,255,0,0,false);
        var grant=new ContactCreditLedger().issuePropagatedTargets(fixture.pair(),approved,
                ContactCreditLedger.Source.SABLE_POST_EFFECT,paired.regions(),
                (from,x,y,z)->ForcePropagation.Probe.empty(),ignored->material,
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                Map.of(),null,ignored->material);
        assertTrue(grant.credited());
        assertTrue(grant.propagationA().unresolvedF()>0);
        // Missing opposite is one of 25 equal source seeds: 25,000/25=1,000 F.
        assertEquals(1000,grant.propagationA().directUnresolvedF(),1e-7);
        assertEquals(grant.propagationA().unresolvedF(),grant.propagationA().directUnresolvedF()+grant.propagationA().propagationUnresolvedF(),1e-7);
        assertEquals(24,grant.propagationB().shares().stream().filter(s->s.layer()==0).count());
        assertEquals(grant.issuedQ(),grant.accounted(),1e-5);
    }
    // The legacy witness-hull pairing remains strictly fail-closed on noisy
    // planes. duperFix has a SEPARATE bilateral shape proof (see
    // DuperFixBilateralSurfaceTest), not a relaxation of these assertions.
    @Test void legacyIncoherentContactPlaneCannotBePromotedIntoFullFacePair(){
        var fixture=fixture(false,false,.1);
        assertTrue(fixture.patches()[0].footprints().isEmpty());
        assertTrue(fixture.patches()[1].footprints().isEmpty());
        assertEquals(0,fixture.paired().pairedA());
        assertEquals(0,fixture.paired().pairedB());
    }
    @Test void mismatchedPrePoseRejectsSourceToOppositeFrameMapping(){
        var fixture=fixture(false,false,0);
        var shifted=new FrameMath.Pose(new FrameMath.Vec(.1,0,0),ZERO,
                new FrameMath.Vec(1,1,1),0,0,0,1);
        var movedBody=new CollisionFrame.Body(ID,0,15,0,15,shifted,ZERO,ZERO,ZERO,true,null,7);
        var wrong=new CollisionFrame.Snapshot(1,5,DIM,CollisionFrame.Phase.PRE_SOLVER,List.of(movedBody),true);
        var unsafe=TargetDirections.forEvent(fixture.pair(),fixture.contacts(),List.of(wrong),5,true);
        var paired=SableContactRegions.pairVerifiedSurfaces(fixture.initial(),fixture.patches(),
                new BlockPos[]{BlockPos.ZERO,BlockPos.ZERO},unsafe,(side,p,axis)->target(side,2,2));
        assertEquals(0,paired.pairedA());
        assertEquals(0,paired.pairedB());
    }
    @Test void unknownShapeEvidenceMustNotReplaceACertifiedNativeCornerPair(){
        var fixture=fixture(true,true,0);
        var paired=fixture.paired();
        assertEquals(4,paired.regions().stream().filter(r->r.a()!=null&&r.b()!=null).count());
        assertEquals(20,paired.pairedA());
    }
}

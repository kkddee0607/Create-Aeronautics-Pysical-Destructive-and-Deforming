package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.OwnerFrame;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static com.physicaldestroydeform.collision.FrameMath.*;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedContactGraphTest {
    private static final String DIM="minecraft:overworld";
    private static final UUID ID=UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final GameImpactCredit.Owner PLOT=new GameImpactCredit.Owner("PLOT:"+ID,2);
    private static final GameImpactCredit.Owner WORLD=new GameImpactCredit.Owner("WORLD:"+DIM,1);
    private static final GameImpactCredit.Pair PAIR=GameImpactCredit.Pair.canonical("run",DIM,PLOT,WORLD);
    private static final Vec ZERO=new Vec(0,0,0);
    private static final Vec UNIT=new Vec(1,1,1);
    private static final Vec X=new Vec(1,0,0);
    private static final Pose IDENTITY=new Pose(ZERO,ZERO,UNIT,0,0,0,1);
    private static CollisionFrame.Snapshot frame(long epoch,Pose pose,Vec linear,Vec angular){
        return new CollisionFrame.Snapshot(epoch,10,DIM,CollisionFrame.Phase.PRE_SOLVER,
                List.of(new CollisionFrame.Body(ID,0,15,0,15,pose,linear,angular,
                        pose.rotationPoint(),true,null,7)),true);
    }
    private static Contact contact(Pose pose,Vec localB,Vec worldPoint,Vec outwardPlot,Vec outwardWorld,boolean capped){
        return new Contact(7,-1,100,outwardPlot.x(),outwardPlot.y(),outwardPlot.z(),
                outwardWorld.x(),outwardWorld.y(),outwardWorld.z(),localB.x(),localB.y(),localB.z(),
                worldPoint.x(),worldPoint.y(),worldPoint.z(),DIM,10,new OwnerFrame(ID,pose),null,capped);
    }
    @Test void stationaryNativePairProvidesFrameAndCompressionTransport(){
        var c=contact(IDENTITY,new Vec(1,.5,.5),new Vec(1,.5,.5),X,X.times(-1),false);
        var geometry=VerifiedContactGraph.geometry(PAIR,c,List.of(frame(31,IDENTITY,ZERO,ZERO),
                frame(32,IDENTITY,ZERO,ZERO)));
        assertNotNull(geometry);
        assertEquals(31,geometry.firstEpoch());
        assertEquals(32,geometry.lastEpoch());
        assertNotNull(VerifiedContactGraph.transport(new ForcePropagation.Orientation(X,"TEST"),geometry.aPose(),geometry.bPose(),geometry.worldOutwardA()));
        assertNull(VerifiedContactGraph.transport(new ForcePropagation.Orientation(X.times(-1),"TEST"),geometry.aPose(),geometry.bPose(),geometry.worldOutwardA()));
        assertNull(VerifiedContactGraph.transport(ForcePropagation.Orientation.unknown(),geometry.aPose(),geometry.bPose(),geometry.worldOutwardA()));
        assertNull(VerifiedContactGraph.transport(new ForcePropagation.Orientation(new Vec(0,1,0),"TEST"),geometry.aPose(),geometry.bPose(),geometry.worldOutwardA()));
    }
    @Test void nearbyButSeparatedOrTransverselyMismatchedWitnessCannotJoinOwners(){
        var pre=List.of(frame(31,IDENTITY,ZERO,ZERO));
        assertNull(VerifiedContactGraph.geometry(PAIR,
                contact(IDENTITY,new Vec(1,.5,.5),new Vec(1.01,.5,.5),X,X.times(-1),false),pre));
        assertNull(VerifiedContactGraph.geometry(PAIR,
                contact(IDENTITY,new Vec(1,.5,.5),new Vec(1,.51,.5),X,X.times(-1),false),pre));
    }
    @Test void motionOrPoseEpochChangeDoesNotProveAStaticSecondaryContact(){
        var c=contact(IDENTITY,new Vec(1,.5,.5),new Vec(1,.5,.5),X,X.times(-1),false);
        assertNull(VerifiedContactGraph.geometry(PAIR,c,List.of(frame(31,IDENTITY,X,ZERO))));
        assertNull(VerifiedContactGraph.geometry(PAIR,c,List.of(frame(31,IDENTITY,ZERO,X))));
        var moved=new Pose(new Vec(.1,0,0),ZERO,UNIT,0,0,0,1);
        assertNull(VerifiedContactGraph.geometry(PAIR,c,List.of(frame(31,IDENTITY,ZERO,ZERO),frame(32,moved,ZERO,ZERO))));
    }
    @Test void rotationTransportUsesOwnLocalFrameAtBothEnds(){
        double r=Math.sqrt(.5);
        var rotated=new Pose(ZERO,ZERO,UNIT,0,0,r,r);
        var worldY=new Vec(0,1,0);
        var raw=new Vec(1,.5,.5);
        var c=contact(rotated,raw,rotated.toWorldPoint(raw),X,worldY.times(-1),false);
        var geometry=VerifiedContactGraph.geometry(PAIR,c,List.of(frame(31,rotated,ZERO,ZERO)));
        assertNotNull(geometry);
        var transport=VerifiedContactGraph.transport(new ForcePropagation.Orientation(X,"TEST"),
                geometry.aPose(),geometry.bPose(),geometry.worldOutwardA());
        assertNotNull(transport);
        assertEquals(1,transport.projection(),1e-9);
        assertEquals(1,transport.orientation().inward().dot(worldY),1e-9);
        var reverse=VerifiedContactGraph.transport(new ForcePropagation.Orientation(worldY.times(-1),"TEST"),
                geometry.bPose(),geometry.aPose(),geometry.worldOutwardA().times(-1));
        assertNotNull(reverse);
        assertEquals(1,reverse.orientation().inward().dot(X.times(-1)),1e-9);
    }
    @Test void missingPreSnapshotSaturatedPollWrongOwnerAndInvalidGeometryAreNotProof(){
        var point=new Vec(1,.5,.5);
        var valid=contact(IDENTITY,point,point,X,X.times(-1),false);
        assertNull(VerifiedContactGraph.geometry(PAIR,valid,List.of()));
        assertNull(VerifiedContactGraph.geometry(PAIR,contact(IDENTITY,point,point,X,X.times(-1),true),List.of(frame(31,IDENTITY,ZERO,ZERO))));
        assertNull(VerifiedContactGraph.geometry(PAIR,contact(IDENTITY,point,point,X,X,false),List.of(frame(31,IDENTITY,ZERO,ZERO))));
        assertNull(VerifiedContactGraph.geometry(PAIR,contact(IDENTITY,point,new Vec(Double.NaN,0,0),X,X.times(-1),false),List.of(frame(31,IDENTITY,ZERO,ZERO))));
        var wrongPair=GameImpactCredit.Pair.canonical("run",DIM,new GameImpactCredit.Owner("PLOT:"+UUID.randomUUID(),2),WORLD);
        assertNull(VerifiedContactGraph.geometry(wrongPair,valid,List.of(frame(31,IDENTITY,ZERO,ZERO))));
    }
    @Test void smallOrientationDriftCannotInventLargeRadiusWitnessContact(){
        var point=new Vec(30_000,.5,.5);
        var c=contact(IDENTITY,point,point,X,X.times(-1),false);
        // Numerically small frame difference, but a real 0.006-block lateral
        // mismatch at this witness. Pose epsilon alone is insufficient proof.
        var rotated=new Pose(ZERO,ZERO,UNIT,0,0,1e-7,1);
        assertNull(VerifiedContactGraph.geometry(PAIR,c,List.of(frame(31,rotated,ZERO,ZERO))));
    }
    @Test void repeatOrIncompletePreEpochAndUncalibratedScaleReject(){
        var point=new Vec(1,.5,.5);
        var c=contact(IDENTITY,point,point,X,X.times(-1),false);
        var pre=frame(31,IDENTITY,ZERO,ZERO);
        assertNull(VerifiedContactGraph.geometry(PAIR,c,List.of(pre,pre)));
        var incomplete=new CollisionFrame.Snapshot(32,10,DIM,CollisionFrame.Phase.PRE_SOLVER,pre.bodies(),false);
        assertNull(VerifiedContactGraph.geometry(PAIR,c,List.of(pre,incomplete)));
        var scaled=new Pose(ZERO,ZERO,new Vec(1.01,1,1),0,0,0,1);
        assertNull(VerifiedContactGraph.geometry(PAIR,
                contact(scaled,point,scaled.toWorldPoint(point),X,X.times(-1),false),
                List.of(frame(31,scaled,ZERO,ZERO))));
    }
    @Test void penetrationCanBeBoundedButPositiveGapCannotBeApproximatedAsOverlap(){
        var p=new Vec(1,.5,.5);var pre=List.of(frame(31,IDENTITY,ZERO,ZERO));
        assertNotNull(VerifiedContactGraph.geometry(PAIR,
                contact(IDENTITY,p,new Vec(.99,.5,.5),X,X.times(-1),false),pre));
        assertNull(VerifiedContactGraph.geometry(PAIR,
                contact(IDENTITY,p,new Vec(.9,.5,.5),X,X.times(-1),false),pre));
        assertNull(VerifiedContactGraph.geometry(PAIR,
                contact(IDENTITY,p,new Vec(1.0001,.5,.5),X,X.times(-1),false),pre));
    }
    @Test void currentPositiveCappedWitnessCanBeRevalidatedWithoutStationarity(){
        var p=new Vec(1,.5,.5);
        var c=contact(IDENTITY,p,p,X,X.times(-1),true);
        // Velocity is evidence of motion, but actual witness geometry is still
        // valid at every retained pre frame and the effect consumer.
        assertNotNull(VerifiedContactGraph.revalidateGeometry(PAIR,c,
                List.of(frame(31,IDENTITY,X,ZERO),frame(32,IDENTITY,X,ZERO))));
        assertNull(VerifiedContactGraph.geometry(PAIR,c,List.of(frame(31,IDENTITY,X,ZERO))));
    }
    @Test void movingPairMustStillHaveContactAtEveryFeasiblePreFrame(){
        var p=new Vec(1,.5,.5);
        var c=contact(IDENTITY,p,p,X,X.times(-1),true);
        var separated=new Pose(new Vec(-.1,0,0),ZERO,UNIT,0,0,0,1);
        assertNull(VerifiedContactGraph.revalidateGeometry(PAIR,c,
                List.of(frame(31,separated,X,ZERO),frame(32,IDENTITY,X,ZERO))));
        var penetrated=new Pose(new Vec(.01,0,0),ZERO,UNIT,0,0,0,1);
        assertNotNull(VerifiedContactGraph.revalidateGeometry(PAIR,c,
                List.of(frame(31,penetrated,X,ZERO),frame(32,IDENTITY,X,ZERO))));
    }
    @Test void exactCubeProofRejectsClearanceDespiteBoundingOverlapAlongOtherAxes(){
        var a=new Vec(0,0,0);var b=new Vec(1,0,0);
        assertTrue(VerifiedContactGraph.cubesTouch(IDENTITY,a,IDENTITY,b,X));
        assertFalse(VerifiedContactGraph.cubesTouch(IDENTITY,a,
                new Pose(new Vec(.0001,0,0),ZERO,UNIT,0,0,0,1),b,X));
        assertFalse(VerifiedContactGraph.cubesTouch(IDENTITY,a,
                new Pose(new Vec(0,1.1,0),ZERO,UNIT,0,0,0,1),b,X));
        // Deep interpenetration is not acceptable face-contact evidence.
        assertFalse(VerifiedContactGraph.cubesTouch(IDENTITY,a,IDENTITY,new Vec(.5,0,0),X));
        // Both world-axis projections overlap slightly, but a rotated cube
        // has a genuine separating axis. AABB checks alone would accept it.
        var rotated=new Pose(new Vec(.5+1.207,.5+1.1,.5),new Vec(.5,.5,.5),UNIT,
                0,0,Math.sin(Math.PI/8),Math.cos(Math.PI/8));
        assertFalse(VerifiedContactGraph.cubesTouch(IDENTITY,a,rotated,a,X));
    }
    @Test void historyIsExactPairAndGenerationBoundedByTtl(){
        var history=new VerifiedContactGraph.History();
        var ta=new GameImpactCredit.Target(PLOT.stableKey(),"0,0,0","a");
        var tb=new GameImpactCredit.Target(WORLD.stableKey(),"1,0,0","b");
        var m=new com.physicaldestroydeform.material.CopycatMaterial.Resolved(
                new com.physicaldestroydeform.MaterialProperties(100,20,30),"",false);
        var a=new VerifiedContactGraph.EndIdentity(ta,-1,0,0,m);
        var b=new VerifiedContactGraph.EndIdentity(tb,1,0,0,m);
        history.remember(PAIR,a,b,10);
        assertTrue(history.hasRecent(PAIR,10));
        assertTrue(history.matches(PAIR,a,b,11));
        assertTrue(history.matches(PAIR,b,a,11));
        assertFalse(history.matches(PAIR,new VerifiedContactGraph.EndIdentity(ta,1,0,0,m),b,11));
        var changed=new GameImpactCredit.Target(PLOT.stableKey(),"0,0,0","changed");
        assertFalse(history.matches(PAIR,new VerifiedContactGraph.EndIdentity(changed,-1,0,0,m),b,11));
        var changedMaterial=new com.physicaldestroydeform.material.CopycatMaterial.Resolved(
                new com.physicaldestroydeform.MaterialProperties(100,20,30),"new-revision",false);
        assertFalse(history.matches(PAIR,new VerifiedContactGraph.EndIdentity(ta,-1,0,0,changedMaterial),b,11));
        var next=new GameImpactCredit.Pair(PAIR.runId(),DIM,
                new GameImpactCredit.Owner(PLOT.stableKey(),PLOT.generation()+1),WORLD);
        assertFalse(history.hasRecent(next,11));
        assertFalse(history.hasRecent(PAIR,10+VerifiedContactGraph.History.TTL+1));
        history.remember(PAIR,a,b,100);
        history.invalidateOwner(PLOT.stableKey());
        assertFalse(history.hasRecent(PAIR,100));
    }
    @Test void boundedHistoryCannotActAsCurrentProofAndSeparationInvalidatesPair(){
        var history=new VerifiedContactGraph.History();
        var ta=new GameImpactCredit.Target(PLOT.stableKey(),"0,0,0","a");
        var m=new com.physicaldestroydeform.material.CopycatMaterial.Resolved(
                new com.physicaldestroydeform.MaterialProperties(100,20,30),"",false);
        var a=new VerifiedContactGraph.EndIdentity(ta,-1,0,0,m);
        for(int i=0;i<VerifiedContactGraph.History.CAP+10;i++){
            var tb=new GameImpactCredit.Target(WORLD.stableKey(),i+",0,0","b");
            history.remember(PAIR,a,new VerifiedContactGraph.EndIdentity(tb,1,0,0,m),100);
        }
        assertEquals(VerifiedContactGraph.History.CAP,history.size());
        assertTrue(history.hasRecent(PAIR,100));
        // A history match is not a substitute for an actual current record.
        assertNull(VerifiedContactGraph.revalidateGeometry(PAIR,null,List.of(frame(31,IDENTITY,ZERO,ZERO))));
        history.invalidatePair(PAIR);
        assertFalse(history.hasRecent(PAIR,100));
        assertEquals(0,history.size());
    }
}

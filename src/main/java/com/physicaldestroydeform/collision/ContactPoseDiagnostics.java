package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.RogTrace;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact;

import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import java.util.List;
import static com.physicaldestroydeform.collision.FrameMath.*;

/** Effect-pose geometry is diagnostic ONLY: no solver substep pose or native
 * feature ID is available. Reuse cached R2 cells and never read the world here. */
final class ContactPoseDiagnostics {
    private static final Vec ZERO=new Vec(0,0,0),ONE=new Vec(1,1,1);
    private static final Pose WORLD=new Pose(ZERO,ZERO,ONE,0,0,0,1);
    private static final int SHADOW_BOX_PAIRS=64;
    private ContactPoseDiagnostics(){}
    private static boolean rigid(Pose p){return p!=null&&p.valid()&&Math.abs(p.scale().x()-1)<1e-5&&Math.abs(p.scale().y()-1)<1e-5&&Math.abs(p.scale().z()-1)<1e-5;}
    private record Box(Vec center,Vec[] axes,double[] half){}
    private static Box box(Pose p,BlockPos pos,AABB a){
        var center=new Vec(pos.getX()+(a.minX+a.maxX)/2,pos.getY()+(a.minY+a.maxY)/2,pos.getZ()+(a.minZ+a.maxZ)/2);
        return new Box(p.toWorldPoint(center),new Vec[]{p.toWorldVector(new Vec(1,0,0)).unit(),p.toWorldVector(new Vec(0,1,0)).unit(),p.toWorldVector(new Vec(0,0,1)).unit()},
                new double[]{(a.maxX-a.minX)*p.scale().x()/2,(a.maxY-a.minY)*p.scale().y()/2,(a.maxZ-a.minZ)*p.scale().z()/2});
    }
    private static double gap(Box a,Box b,Vec delta,Vec direction){
        if(direction.squareLength()<1e-16)return Double.NEGATIVE_INFINITY;
        Vec n=direction.unit();double radius=0;
        for(int i=0;i<3;i++)radius+=a.half()[i]*Math.abs(n.dot(a.axes()[i]))+b.half()[i]*Math.abs(n.dot(b.axes()[i]));
        return Math.abs(delta.dot(n))-radius;
    }
    /** Maximum normalized SAT separation on 15 axes, not the Euclidean distance
     * or a solver penetration measurement. <=0 means effect-pose OBB overlap. */
    static double signedGap(Pose pa,BlockPos a,AABB shapeA,Pose pb,BlockPos b,AABB shapeB){
        if(!rigid(pa)||!rigid(pb))return Double.NaN;
        try{
            var x=box(pa,a,shapeA);var y=box(pb,b,shapeB);var delta=y.center().minus(x.center());
            double result=Double.NEGATIVE_INFINITY;
            for(int i=0;i<3;i++){
                result=Math.max(result,gap(x,y,delta,x.axes()[i]));
                result=Math.max(result,gap(x,y,delta,y.axes()[i]));
                for(int j=0;j<3;j++)result=Math.max(result,gap(x,y,delta,x.axes()[i].cross(y.axes()[j])));
            }
            return Double.isFinite(result)?result:Double.NaN;
        }catch(RuntimeException invalid){return Double.NaN;}
    }
    private static Pose pose(Contact c,boolean a){var frame=a?c.frameA():c.frameB();return (a?c.idA():c.idB())==-1?WORLD:frame==null?null:frame.pose();}
    private static Vec raw(Contact c,boolean a){return a?new Vec(c.contactAX(),c.contactAY(),c.contactAZ()):new Vec(c.contactBX(),c.contactBY(),c.contactBZ());}
    private static Vec normal(Contact c,boolean a){return a?new Vec(c.ax(),c.ay(),c.az()):new Vec(c.bx(),c.by(),c.bz());}
    private static String preSummary(Contact c,boolean a,List<CollisionFrame.Snapshot> pre,Pose effect,Vec allocated){
        if((a?c.idA():c.idB())==-1)return "STATIC";
        var owner=a?c.frameA():c.frameB();if(owner==null||effect==null||allocated==null)return "UNKNOWN";
        int count=0;double maxPoint=0,maxTranslation=0,maxAngle=0;long first=-1,last=-1;
        Vec firstProjection=null,lastProjection=null;int runtime=a?c.idA():c.idB();
        // No new history retained; matching pre-epochs are descriptive only.
        for(var s:pre){
            if(s.phase()!=CollisionFrame.Phase.PRE_SOLVER||s.serverTick()!=c.serverTick()||!c.dimension().equals(s.dimension()))continue;
            for(var body:s.bodies())if(body.runtimeId()==runtime&&owner.uuid().equals(body.plotId())&&rigid(body.pose())){
                var p=body.pose();var projected=p.toWorldPoint(allocated);var effectPoint=effect.toWorldPoint(allocated);
                double angleDot=Math.abs(p.qx()*effect.qx()+p.qy()*effect.qy()+p.qz()*effect.qz()+p.qw()*effect.qw());
                double pn=Math.sqrt(p.qx()*p.qx()+p.qy()*p.qy()+p.qz()*p.qz()+p.qw()*p.qw());
                double en=Math.sqrt(effect.qx()*effect.qx()+effect.qy()*effect.qy()+effect.qz()*effect.qz()+effect.qw()*effect.qw());
                maxPoint=Math.max(maxPoint,Math.sqrt(projected.minus(effectPoint).squareLength()));
                maxTranslation=Math.max(maxTranslation,Math.sqrt(p.position().minus(effect.position()).squareLength()));
                maxAngle=Math.max(maxAngle,2*Math.acos(Math.min(1,angleDot/(pn*en))));
                if(first<0||s.epoch()<first){first=s.epoch();firstProjection=projected;}
                if(last<0||s.epoch()>last){last=s.epoch();lastProjection=projected;}
                count++;break;
            }
        }
        return "epochs="+count+" first="+first+" last="+last+" preFirst="+firstProjection+" preLast="+lastProjection+
                " maxPointDelta="+maxPoint+" maxTranslation="+maxTranslation+" maxAngleRad="+maxAngle;
    }
    static void observe(GameImpactCredit.Pair pair,Contact c,int index,List<CollisionFrame.Snapshot> pre,
                        OwnerResolver.NativeAuditBatch batch){
        if(!RogTrace.enabled())return;
        try{
            var a=pose(c,true);var b=pose(c,false);var la=SableConsumerTargets.candidate(c,true);var lb=SableConsumerTargets.candidate(c,false);
            if(!rigid(a)||!rigid(b)||la==null||lb==null){{ if(RogTrace.eventSlot(Stage.R03,"POSE_CALIBRATION"))RogTrace.detail(Stage.R03,"POSE_CALIBRATION","uniqueRecordIndex={} outcome=POSE_UNAVAILABLE substepKnown=false",index); }return;}
            var wa=a.toWorldPoint(la);var wb=b.toWorldPoint(lb);var na=a.toWorldNormal(normal(c,true));var nb=b.toWorldNormal(normal(c,false));
            var delta=wb.minus(wa);double along=delta.dot(na),tangent=Math.sqrt(delta.minus(na.times(along)).squareLength());
            { if(RogTrace.eventSlot(Stage.R03,"POSE_CALIBRATION"))RogTrace.detail(Stage.R03,"POSE_CALIBRATION","uniqueRecordIndex={} nativeA={} nativeB={} captureServerTick={} sourceGameTick={} rawA={} rawB={} normalA={} normalB={} effectPoseA={} effectPoseB={} allocatedA={} allocatedB={} worldA={} worldB={} along={} tangent={} normalDot={} roundTripA={} roundTripB={} preA={} preB={} substepKnown=false manifoldKnown=false authority=NONE",
                    index,c.idA(),c.idB(),c.captureServerTick(),c.serverTick(),raw(c,true),raw(c,false),normal(c,true),normal(c,false),a,b,la,lb,wa,wb,along,tangent,na.dot(nb),
                    Math.sqrt(a.toLocalPoint(wa).minus(la).squareLength()),Math.sqrt(b.toLocalPoint(wb).minus(lb).squareLength()),preSummary(c,true,pre,a,la),preSummary(c,false,pre,b,lb)); }
            int side=SableConsumerTargets.sourceSide(pair.a().stableKey(),c,pair.dimension());
            if(side==0)return;
            String ownerA=side==1?pair.a().stableKey():pair.b().stableKey(),ownerB=side==1?pair.b().stableKey():pair.a().stableKey();
            int checked=0,overlaps=0;double minGap=Double.POSITIVE_INFINITY;boolean capped=false;
            outer:for(var ca:SableConsumerTargets.cells(la))for(var cb:SableConsumerTargets.cells(lb)){
                // boxes() is cache-only; absent entries are UNKNOWN, never air proof.
                for(var ba:batch.boxes(ownerA,ca))for(var bb:batch.boxes(ownerB,cb)){
                    if(checked>=SHADOW_BOX_PAIRS){capped=true;break outer;}
                    checked++;double separation=signedGap(a,ca,ba,b,cb,bb);
                    if(!Double.isFinite(separation))continue;
                    minGap=Math.min(minGap,separation);if(separation<=1e-5)overlaps++;
                }
            }
            { if(RogTrace.eventSlot(Stage.R04,"SHADOW_OBB"))RogTrace.detail(Stage.R04,"SHADOW_OBB","uniqueRecordIndex={} effectBoxPairs={} overlapPairs={} minimumSatGap={} capped={} exactSubstep=false newWorldReads=0 authority=NONE",
                    index,checked,overlaps,Double.isFinite(minGap)?minGap:Double.NaN,capped); }
        }catch(RuntimeException|LinkageError failure){{ if(RogTrace.eventSlot(Stage.R03,"POSE_CALIBRATION"))RogTrace.detail(Stage.R03,"POSE_CALIBRATION","uniqueRecordIndex={} outcome=DIAGNOSTIC_FAILURE type={} authority=NONE",index,failure.getClass().getSimpleName()); }}
    }
}

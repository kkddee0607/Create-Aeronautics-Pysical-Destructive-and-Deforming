package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.deform.MovePlan;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Direction authorization is per target and original native record, not owner-average.
 * A derived direction is retained only if every paid parent carries the same axes. */
public final class TargetDirections {
    private final Map<GameImpactCredit.Target,MovePlan.SignedAxes> directions=new HashMap<>();
    private final Set<GameImpactCredit.Target> ambiguous=new HashSet<>();
    private final Set<GameImpactCredit.Target> original=new HashSet<>();
    private final Map<GameImpactCredit.Target,MovePlan.Evidence> sealed=new HashMap<>();
    public void bind(ContactCreditLedger.Grant grant){
        bind(grant,null,null);
    }
    public void bind(ContactCreditLedger.Grant grant,GameImpactCredit.Settings policy,
                     java.util.function.Function<GameImpactCredit.Target,com.physicaldestroydeform.MaterialProperties> materials){
        if(!grant.credited())return;
        for(var side:java.util.Arrays.asList(grant.propagationA(),grant.propagationB()))if(side!=null)
            for(var share:side.shares()){
                var direction=get(share.target());
                var material=materials==null?null:materials.apply(share.target());
                double candidate=policy==null||material==null?-1:policy.candidate(share.allocatedF()*(1-MovePlan.yieldMultiplier(material.ductilityPercent())));
                if(direction!=null)sealed.put(share.target(),new MovePlan.Evidence(direction,share.allocatedF(),share.layer(),candidate));
            }
    }
    public MovePlan.Evidence evidence(GameImpactCredit.Target target){return sealed.get(target);}
    public MovePlan.SignedAxes get(GameImpactCredit.Target target){return ambiguous.contains(target)?null:directions.get(target);}
    private void merge(GameImpactCredit.Target target,MovePlan.SignedAxes value){
        if(value==null){ambiguous.add(target);directions.remove(target);return;}
        if(ambiguous.contains(target))return;
        var prior=directions.putIfAbsent(target,value);
        if(prior!=null&&!prior.equals(value)){ambiguous.add(target);directions.remove(target);}
    }
    public void trace(ForcePropagation.Trace event){
        if(event instanceof ForcePropagation.Edge edge&&!original.contains(edge.child()))merge(edge.child(),get(edge.parent()));
    }
    public static TargetDirections capture(MinecraftServer server,GameImpactCredit.Pair pair,List<Contact> contacts,
            List<CollisionFrame.Snapshot> pre,long tick,boolean complete){
        var result=new TargetDirections();
        if(!complete||contacts.isEmpty()||contacts.stream().anyMatch(Contact::capSaturated))return result;
        long epoch=pre.stream().filter(p->p.phase()==CollisionFrame.Phase.PRE_SOLVER&&p.serverTick()==tick&&pair.dimension().equals(p.dimension())).mapToLong(CollisionFrame.Snapshot::epoch).min().orElse(-1);
        if(epoch<0)return result;
        var batch=new OwnerResolver.NativeAuditBatch(server,pair.dimension());
        for(Contact contact:contacts){
            var normals=NativeEffectDirection.verify(pair,List.of(contact),pre);
            boolean closing=NativeApproachEvidence.approaching(pair,pre,normals,tick);
            for(String owner:List.of(pair.a().stableKey(),pair.b().stableKey())){
                int side=SableConsumerTargets.sourceSide(owner,contact,pair.dimension());if(side==0)continue;
                var point=SableConsumerTargets.candidate(contact,side==1);if(point==null)continue;
                var normal=normals.forOwner(owner).inward();MovePlan.SignedAxes axes=null;
                if(closing&&normal!=null)try{axes=MovePlan.quantizeDirection(normal.x(),normal.y(),normal.z(),true,epoch);}catch(IllegalArgumentException ignored){}
                var candidates=new ArrayList<GameImpactCredit.Target>();
                boolean unknown=false;
                for(BlockPos pos:SableConsumerTargets.cells(point)){
                    var check=batch.inspect(owner,pos,point).result();
                    if(!check.verified()){
                        if(check.status()!=OwnerResolver.Status.NO_TARGET&&!Set.of("CACHED_SHAPE_POINT_MISS","PLOT_SHAPE_EMPTY","WORLD_SHAPE_EMPTY","BLOCK_MISSING_OR_AIR").contains(check.detail()))unknown=true;
                        continue;
                    }
                    var key=check.key();var target=new GameImpactCredit.Target(owner,key.x()+","+key.y()+","+key.z(),key.fingerprint());
                    result.original.add(target);
                    if(axes!=null&&batch.targetFace(owner,pos,point,axes))candidates.add(target);
                    else result.merge(target,null);
                }
                if(!unknown&&candidates.size()==1)result.merge(candidates.getFirst(),axes);
                else for(var target:candidates)result.merge(target,null);
            }
        }
        return result;
    }
    /** A positive inward axis has its contact on the low face, negative on high.
     * Oblique evidence must independently corroborate every selected axis. */
    public static boolean onInwardFace(double x,double y,double z,MovePlan.SignedAxes axes){
        return face(x,axes.x())&&face(y,axes.y())&&face(z,axes.z());
    }
    private static boolean face(double value,int axis){
        if(!Double.isFinite(value)||value<-SableConsumerTargets.EPSILON||value>1+SableConsumerTargets.EPSILON)return false;
        return axis==0||Math.abs(value-(axis>0?0:1))<=SableConsumerTargets.EPSILON;
    }
}

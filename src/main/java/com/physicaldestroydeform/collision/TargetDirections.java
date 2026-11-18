package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.deform.MovePlan;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact;
import java.util.*;

/** Direction authorization is per target and original native record, not owner-average.
 * A derived direction is retained only if every paid parent carries the same axes. */
public final class TargetDirections {
    private final Map<GameImpactCredit.Target,MovePlan.SignedAxes> directions=new HashMap<>();
    private final Set<GameImpactCredit.Target> ambiguous=new HashSet<>();
    private final Set<GameImpactCredit.Target> original=new HashSet<>();
    private final Map<GameImpactCredit.Target,MovePlan.Evidence> sealed=new HashMap<>();
    private final Map<Contact,NativeEffectDirection.Result> normals=new HashMap<>();
    private GameImpactCredit.Pair pair;
    private List<CollisionFrame.Snapshot> pre=List.of();
    private List<Contact> contacts=List.of();
    private long tick,epoch=-1;
    private String gate="NO_EVENT";
    private int acceptedDirect,acceptedPatch,unknownWitnesses,faceMiss,faceAmbiguous,incompleteWitnesses;

    /** Same event and owner/shape snapshot as the real target audit. No new world reads. */
    static TargetDirections forEvent(GameImpactCredit.Pair pair,List<Contact> contacts,
            List<CollisionFrame.Snapshot> pre,long tick,boolean complete){
        var d=new TargetDirections();
        if(!complete){d.gate="INCOMPLETE_SOURCE";return d;}
        if(contacts.isEmpty()){d.gate="NO_CONTACTS";return d;}
        if(contacts.stream().anyMatch(Contact::capSaturated)){d.gate="NATIVE_CAP";return d;}
        long epoch=pre.stream().filter(p->p.phase()==CollisionFrame.Phase.PRE_SOLVER&&p.serverTick()==tick&&pair.dimension().equals(p.dimension())).mapToLong(CollisionFrame.Snapshot::epoch).min().orElse(-1);
        if(epoch<0){d.gate="NO_PRE_EPOCH";return d;}
        d.pair=pair;d.contacts=contacts;d.pre=pre;d.tick=tick;d.epoch=epoch;d.gate="AUDIT";
        return d;
    }
    /** Compatibility entry; production calls forEvent and passes it to the single region audit. */
    public static TargetDirections capture(net.minecraft.server.MinecraftServer server,GameImpactCredit.Pair pair,
            List<Contact> contacts,List<CollisionFrame.Snapshot> pre,long tick,boolean complete){
        var d=forEvent(pair,contacts,pre,tick,complete);
        if(d.active())SableContactRegions.audit(server,pair.dimension(),pair,contacts,d);
        return d;
    }
    MovePlan.SignedAxes axis(String owner,Contact contact){
        if(epoch<0||contact.serverTick()!=tick)return null;
        var result=normals.computeIfAbsent(contact,c->{
            var n=NativeEffectDirection.verify(pair,List.of(c),pre);
            return NativeApproachEvidence.approaching(pair,pre,n,tick)?n:NativeEffectDirection.unknown("APPROACH_UNVERIFIED");
        });
        var normal=result.forOwner(owner).inward();
        if(normal==null)return null;
        try{return MovePlan.quantizeDirection(normal.x(),normal.y(),normal.z(),true,epoch);}
        catch(IllegalArgumentException invalid){return null;}
    }
    /** Expanded polygons require unanimous verified native axes and nearly axial normals.
     * Never promote a sampled cap, sparse halo, ambiguous manifold or two-axis approximation. */
    MovePlan.SignedAxes uniformPatchAxis(String owner){
        if(epoch<0)return null;
        MovePlan.SignedAxes common=null;
        var previous=new ArrayList<FrameMath.Vec>();
        for(var c:contacts){
            var axes=axis(owner,c);
            if(axes==null||axes.steps().size()!=1||common!=null&&!common.equals(axes))return null;
            var n=normals.get(c).forOwner(owner).inward();
            if(Math.abs(n.x()*axes.x()+n.y()*axes.y()+n.z()*axes.z())<.98)return null;
            for(var prior:previous)if(n.dot(prior)<.98)return null;
            previous.add(n);
            common=axes;
        }
        return common;
    }
    void observe(Collection<GameImpactCredit.Target> touched,Collection<GameImpactCredit.Target> faces,
                 MovePlan.SignedAxes axes,boolean incomplete){
        original.addAll(touched);
        if(axes!=null&&!incomplete&&faces.size()==1){merge(faces.iterator().next(),axes);acceptedDirect++;return;}
        // A non-facing neighbouring voxel is not contradictory evidence when one
        // actual inward face uniquely identifies the hit. Never poison it in that case.
        unknownWitnesses++;
        if(incomplete)incompleteWitnesses++;
        else if(axes!=null&&faces.isEmpty())faceMiss++;
        else if(faces.size()>1)faceAmbiguous++;
        for(var target:incomplete||axes==null||faces.isEmpty()?touched:faces)merge(target,null);
    }
    void patch(GameImpactCredit.Target target,MovePlan.SignedAxes axes){
        if(axes!=null&&!original.contains(target)){merge(target,axes);acceptedPatch++;}
        original.add(target);
    }
    void seed(GameImpactCredit.Target target){original.add(target);}
    boolean active(){return epoch>=0;}
    boolean hasProvenSeed(){return !directions.isEmpty();}
    String summary(){
        var reasons=new TreeMap<String,Integer>();
        for(var value:normals.values())reasons.merge(value.reason(),1,Integer::sum);
        return "gate="+gate+" directWitnesses="+acceptedDirect+" patchTargets="+acceptedPatch+
                " unknownWitnesses="+unknownWitnesses+" faceMiss="+faceMiss+" faceAmbiguous="+faceAmbiguous+
                " incompleteWitnesses="+incompleteWitnesses+" normalRecords="+reasons+
                " ambiguous="+ambiguous.size()+" authorized="+sealed.size();
    }
    public void bind(ContactCreditLedger.Grant grant){
        bind(grant,null,null);
    }
    public void bind(ContactCreditLedger.Grant grant,GameImpactCredit.Settings policy,
                     java.util.function.Function<GameImpactCredit.Target,com.physicaldestroydeform.MaterialProperties> materials){
        if(!grant.credited())return;
        for(var side:java.util.Arrays.asList(grant.propagationA(),grant.propagationB()))if(side!=null)
            for(var share:side.shares()){
                var direction=get(share.target());
                if(direction==null)continue;
                var material=materials==null?null:materials.apply(share.target());
                double candidate=policy==null||material==null?-1:policy.candidate(share.allocatedF()*(1-MovePlan.yieldMultiplier(material.ductilityPercent())));
                sealed.put(share.target(),new MovePlan.Evidence(direction,share.allocatedF(),share.layer(),candidate));
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

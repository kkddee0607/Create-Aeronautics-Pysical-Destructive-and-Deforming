package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.deform.MovePlan;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.OwnerFrame;
import java.util.*;

/** Direction authorization is per target and original native record, not owner-average.
 * A derived direction is retained only if every paid parent carries the same axes. */
public final class TargetDirections {
    private final Map<GameImpactCredit.Target,MovePlan.SignedAxes> directions=new HashMap<>();
    // Multi-face witnesses constrain an axis but never establish the target alone.
    private final Map<GameImpactCredit.Target,MovePlan.SignedAxes> unresolvedFaces=new HashMap<>();
    private final Set<GameImpactCredit.Target> ambiguous=new HashSet<>();
    private final Set<GameImpactCredit.Target> original=new HashSet<>();
    private final Map<GameImpactCredit.Target,MovePlan.Evidence> sealed=new HashMap<>();
    private final Map<Contact,NativeEffectDirection.Result> normals=new HashMap<>();
    // Proof depends on IDs, normals, effect frames, dimension/tick and cap status,
    // not contact points or force. Event-local only: all native shape reads remain independent.
    private record NormalKey(int idA,int idB,double ax,double ay,double az,double bx,double by,double bz,
                             String dimension,long tick,OwnerFrame frameA,OwnerFrame frameB,boolean capped) {
        static NormalKey of(Contact c){return new NormalKey(c.idA(),c.idB(),c.ax(),c.ay(),c.az(),
                c.bx(),c.by(),c.bz(),c.dimension(),c.serverTick(),c.frameA(),c.frameB(),c.capSaturated());}
    }
    private final Map<NormalKey,NativeEffectDirection.Result> normalProofs=new HashMap<>();
    private GameImpactCredit.Pair pair;
    private List<CollisionFrame.Snapshot> pre=List.of();
    private long tick,epoch=-1;
    private String gate="NO_EVENT";
    private int acceptedDirect,unknownWitnesses,faceMiss,faceAmbiguous,incompleteWitnesses;

    /** Same event and owner/shape snapshot as the real target audit. No new world reads. */
    static TargetDirections forEvent(GameImpactCredit.Pair pair,List<Contact> contacts,
            List<CollisionFrame.Snapshot> pre,long tick,boolean complete){
        var d=new TargetDirections();
        if(!complete){d.gate="INCOMPLETE_SOURCE";return d;}
        if(contacts.isEmpty()){d.gate="NO_CONTACTS";return d;}
        // A saturated native poll disables movement direction authorization,
        // not independent exact-frame shape correspondence for the records
        // that were actually observed. Never infer missing native contacts.
        long epoch=pre.stream().filter(p->p.phase()==CollisionFrame.Phase.PRE_SOLVER&&p.serverTick()==tick&&pair.dimension().equals(p.dimension())).mapToLong(CollisionFrame.Snapshot::epoch).min().orElse(-1);
        if(epoch<0){d.gate="NO_PRE_EPOCH";return d;}
        d.pair=pair;d.pre=pre;d.tick=tick;d.epoch=epoch;
        d.gate=contacts.stream().anyMatch(Contact::capSaturated)?"NATIVE_CAP":"AUDIT";
        return d;
    }
    /** Compatibility entry; production calls forEvent and passes it to the single region audit. */
    public static TargetDirections capture(net.minecraft.server.MinecraftServer server,GameImpactCredit.Pair pair,
            List<Contact> contacts,List<CollisionFrame.Snapshot> pre,long tick,boolean complete){
        var d=forEvent(pair,contacts,pre,tick,complete);
        if(d.active())SableContactRegions.audit(server,pair.dimension(),pair,contacts,d);
        return d;
    }
    List<CollisionFrame.Snapshot> diagnosticPreFrames(){return pre;}
    MovePlan.SignedAxes axis(String owner,Contact contact){
        if(!active()||contact.serverTick()!=tick)return null;
        var result=normals.computeIfAbsent(contact,c->normalProofs.computeIfAbsent(NormalKey.of(c),key->{
            var n=NativeEffectDirection.verify(pair,List.of(c),pre);
            return NativeApproachEvidence.approaching(pair,pre,n,tick)?n:NativeEffectDirection.unknown("APPROACH_UNVERIFIED");
        }));
        var normal=result.forOwner(owner).inward();
        if(normal==null)return null;
        try{return MovePlan.quantizeDirection(normal.x(),normal.y(),normal.z(),true,epoch);}
        catch(IllegalArgumentException invalid){return null;}
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
        if(!incomplete&&axes!=null&&faces.size()>1){
            // The target is ambiguous, not the signed normal itself. A unique
            // independent face can corroborate this same axis in either order.
            for(var target:faces)constrain(target,axes);
        }else for(var target:touched)merge(target,null);
    }
    void seed(GameImpactCredit.Target target){original.add(target);}
    boolean active(){return epoch>=0 && "AUDIT".equals(gate);}
    boolean hasProvenSeed(){return !directions.isEmpty();}
    String summary(){
        var reasons=new TreeMap<String,Integer>();
        for(var value:normals.values())reasons.merge(value.reason(),1,Integer::sum);
        return "gate="+gate+" directWitnesses="+acceptedDirect+
                " unknownWitnesses="+unknownWitnesses+" faceMiss="+faceMiss+" faceAmbiguous="+faceAmbiguous+
                " incompleteWitnesses="+incompleteWitnesses+" normalRecords="+reasons+
                " uniqueNormalProofs="+normalProofs.size()+" ambiguous="+ambiguous.size()+
                " unresolvedFaceCandidates="+unresolvedFaces.size()+" authorized="+sealed.size();
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
    private void veto(GameImpactCredit.Target target){
        ambiguous.add(target);directions.remove(target);unresolvedFaces.remove(target);
    }
    private void constrain(GameImpactCredit.Target target,MovePlan.SignedAxes value){
        if(ambiguous.contains(target))return;
        var proven=directions.get(target);
        if(proven!=null){if(!proven.equals(value))veto(target);return;}
        var previous=unresolvedFaces.putIfAbsent(target,value);
        if(previous!=null&&!previous.equals(value))veto(target);
    }
    private void merge(GameImpactCredit.Target target,MovePlan.SignedAxes value){
        if(value==null){veto(target);return;}
        if(ambiguous.contains(target))return;
        var constraint=unresolvedFaces.remove(target);
        if(constraint!=null&&!constraint.equals(value)){veto(target);return;}
        var prior=directions.putIfAbsent(target,value);
        if(prior!=null&&!prior.equals(value))veto(target);
    }
    public void trace(ForcePropagation.Trace event){
        if(event instanceof ForcePropagation.Edge edge&&!original.contains(edge.child())) {
            // The receiving owner's lattice may be rotated. A propagated F
            // direction is not precise voxel-movement authorization there.
            if(!edge.child().ownerKey().equals(edge.parent().ownerKey()))veto(edge.child());
            else merge(edge.child(),get(edge.parent()));
        }
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

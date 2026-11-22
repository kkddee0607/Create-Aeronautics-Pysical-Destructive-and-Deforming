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
    private List<Contact> contacts=List.of();
    private long tick,epoch=-1;
    private String gate="NO_EVENT";
    private String patchPairGate="NOT_ATTEMPTED";
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
        var result=normals.computeIfAbsent(contact,c->normalProofs.computeIfAbsent(NormalKey.of(c),key->{
            var n=NativeEffectDirection.verify(pair,List.of(c),pre);
            return NativeApproachEvidence.approaching(pair,pre,n,tick)?n:NativeEffectDirection.unknown("APPROACH_UNVERIFIED");
        }));
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
        var previous=new LinkedHashSet<FrameMath.Vec>();
        for(var c:contacts){
            var axes=axis(owner,c);
            if(axes==null||Math.abs(axes.x())+Math.abs(axes.y())+Math.abs(axes.z())!=1
                    ||common!=null&&!common.equals(axes))return null;
            var n=normals.get(c).forOwner(owner).inward();
            if(Math.abs(n.x()*axes.x()+n.y()*axes.y()+n.z()*axes.z())<.98)return null;
            // Distinct normals still need every pairwise check. Identical ones
            // only need their self-comparison; repeating it adds no evidence.
            if(previous.contains(n)){if(n.dot(n)<.98)return null;}
            else {
                for(var prior:previous)if(n.dot(prior)<.98)return null;
                previous.add(n);
            }
            common=axes;
        }
        return common;
    }
    /** Patch correspondence needs a stricter proof than owner-local damage
     * propagation. Every native record must have one stable dual pose and
     * coherent lateral mapping across all captured pre-solver epochs. */
    record PatchFrames(FrameMath.Pose a,FrameMath.Pose b,FrameMath.Vec delta,
                       MovePlan.SignedAxes inwardA,MovePlan.SignedAxes inwardB) {
        FrameMath.Vec opposite(FrameMath.Vec allocated,boolean onA){
            return onA?b.toLocalPoint(a.toWorldPoint(allocated).plus(delta))
                    :a.toLocalPoint(b.toWorldPoint(allocated).minus(delta));
        }
        MovePlan.SignedAxes axis(boolean onA){return onA?inwardA:inwardB;}
    }
    PatchFrames patchFrames(){
        if(!active()){patchPairGate="INACTIVE";return null;}
        // Conservative separate budget for dual-frame patch proof. Native
        // candidate consensus remains available even if this extra work is held.
        long cost=0;
        for(var f:pre)if(f.phase()==CollisionFrame.Phase.PRE_SOLVER&&f.serverTick()==tick&&pair.dimension().equals(f.dimension()))
            cost+=2L*Math.max(1,f.bodies().size());
        if(Math.max(1,cost)>8L*SableContactRegions.RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE/Math.max(1,contacts.size())){
            patchPairGate="FRAME_WORK_BUDGET";return null;
        }
        PatchFrames common=null;
        try{
            for(var c:contacts){
                // An exactly revalidated current-record witness need not be
                // stationary: all captured pre/effect frames must agree on
                // each source point, and both strict non-collinear polygons
                // later establish the common tangential mapping.
                var g=VerifiedContactGraph.revalidateGeometry(pair,c,pre);
                if(g==null){patchPairGate="FRAME_REVALIDATION_REJECTED";return null;}
                boolean nativeA=SableConsumerTargets.sourceSide(pair.a().stableKey(),c,pair.dimension())==1;
                var an=nativeA?g.outwardA():g.outwardB();var bn=nativeA?g.outwardB():g.outwardA();
                var aa=patchAxis(an,g.firstEpoch());var ba=patchAxis(bn,g.firstEpoch());
                if(aa==null||ba==null){patchPairGate="NONAXIAL_DUAL_WITNESS";return null;}
                var next=new PatchFrames(nativeA?g.aPose():g.bPose(),nativeA?g.bPose():g.aPose(),
                        nativeA?g.worldB().minus(g.worldA()):g.worldA().minus(g.worldB()),aa,ba);
                if(common!=null&&(!common.a().equals(next.a())||!common.b().equals(next.b())
                        ||!common.inwardA().equals(next.inwardA())||!common.inwardB().equals(next.inwardB())
                        ||common.delta().minus(next.delta()).squareLength()>ContactFacePatch.PAIR_EPS*ContactFacePatch.PAIR_EPS)){
                    patchPairGate="MAPPING_INCOHERENT";return null;
                }
                if(common==null)common=next;
            }
        }catch(RuntimeException unavailable){patchPairGate="FRAME_PROOF_ERROR";return null;}
        patchPairGate=common==null?"NO_WITNESSES":"FRAME_VERIFIED";
        return common;
    }
    private static MovePlan.SignedAxes patchAxis(FrameMath.Vec outward,long epoch){
        var a=MovePlan.quantizeDirection(-outward.x(),-outward.y(),-outward.z(),true,epoch);
        return Math.abs(a.x())+Math.abs(a.y())+Math.abs(a.z())==1
                &&Math.abs(outward.dot(new FrameMath.Vec(a.x(),a.y(),a.z())))>=1-ContactFacePatch.PAIR_EPS?a:null;
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
                " uniqueNormalProofs="+normalProofs.size()+" ambiguous="+ambiguous.size()+
                " unresolvedFaceCandidates="+unresolvedFaces.size()+" authorized="+sealed.size()+" patchPairGate="+patchPairGate;
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

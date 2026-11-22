package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.deform.MovePlan;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.OwnerFrame;
import com.physicaldestroydeform.material.CopycatMaterial;
import com.physicaldestroydeform.material.MaterialRegistry;
import com.physicaldestroydeform.material.TargetMaterials;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.*;
import java.util.function.ToLongFunction;

import static com.physicaldestroydeform.collision.FrameMath.*;
import static com.physicaldestroydeform.collision.GameImpactCredit.*;

/**
 * Same-tick, native-witnessed contact graph for compression of already stationary
 * structures only. Rapier's effect feed has no source substep or causal ID.
 * Consequently each admitted edge must be geometrically valid across EVERY
 * captured pre pose and the final consumer pose, and no relative motion is
 * inferred as a consequence of another impact. The caller separately excludes
 * every pair that may issue its own direct Credit. The graph never mints one.
 *
 * The native record, exact dual owner-shape faces, stable transforms, generation
 * and material snapshot together are the evidence; AABB overlap/proximity alone
 * is insufficient. No block scan, contact extrapolation, chunk load, or callback
 * mutation. This graph expires at the end of its server-thread processing stamp.
 */
public final class VerifiedContactGraph implements ForcePropagation.CrossOwnerContext {
    private static final double EPS=1.0e-5;
    private static final double MAX_PENETRATION=1.0/32.0;
    private static final double STILL_SPEED=1.0e-3;
    private static final Vec ZERO=new Vec(0,0,0);
    private static final Pose WORLD_POSE=new Pose(ZERO,ZERO,new Vec(1,1,1),0,0,0,1);
    private static final int MAX_FRAME_EDGES=NativeEffectGroups.MAX_RECORDS;
    private static final Set<String> EMPTY_SHAPE_RESULTS=Set.of("CACHED_SHAPE_POINT_MISS",
            "BLOCK_MISSING_OR_AIR","PLOT_SHAPE_EMPTY","WORLD_SHAPE_EMPTY");

    /** Original native A/B order, not pair canonical order. The substep index
     * remains unknown: first/lastEpoch record the ALL-pre-epochs proof. */
    record Geometry(Pose aPose,Pose bPose,Vec localA,Vec localB,Vec worldA,Vec worldB,
                    Vec outwardA,Vec outwardB,Vec worldOutwardA,long firstEpoch,long lastEpoch) {}
    record Transport(ForcePropagation.Orientation orientation,double projection) {}
    private record Endpoint(Owner owner,Target target,BlockPos allocated,Vec point,
                            MovePlan.SignedAxes inward,Pose pose,CopycatMaterial.Resolved material) {}
    private record Proof(Endpoint a,Endpoint b,Geometry geometry) {}
    private record Identity(Target low,Target high) {}
    private record Cell(String owner,String position) {
        static Cell of(Target target){return new Cell(target.ownerKey(),target.localBlock());}
    }
    private record Lookup(Target from,ForcePropagation.Orientation inward,
                          List<ForcePropagation.CrossEdge> edges,boolean unresolved) {}

    private final MinecraftServer server;
    private final String dimension;
    private final long tick;
    private final ToLongFunction<String> currentGeneration;
    private final MaterialRegistry.Snapshot snapshot;
    private final TargetMaterials materials;
    private final OwnerResolver.NativeAuditBatch shapeBatch;
    private final Map<Cell,List<Proof>> adjacency=new HashMap<>();
    private final Map<Identity,Proof> proofs=new HashMap<>();
    private final Set<Identity> ambiguous=new HashSet<>();
    private final Map<String,Owner> owners=new HashMap<>();
    private int admitted;
    private boolean saturated;
    private Lookup nextLookup;

    public VerifiedContactGraph(MinecraftServer server,String dimension,long tick,
                                ToLongFunction<String> currentGeneration) {
        this.server=Objects.requireNonNull(server);
        if(!server.isSameThread()||dimension==null||dimension.isBlank()||tick<0)
            throw new IllegalArgumentException("invalid server contact stamp");
        this.dimension=dimension;this.tick=tick;
        this.currentGeneration=Objects.requireNonNull(currentGeneration);
        this.snapshot=MaterialRegistry.snapshot();
        this.materials=new TargetMaterials(server,dimension,snapshot);
        this.shapeBatch=new OwnerResolver.NativeAuditBatch(server,dimension);
    }

    /**
     * Observe only a secondary pair which cannot mint a direct Credit in the
     * current stamp. A partial/malformed/capped native poll never authorizes
     * cross-owner F. Face-patch extrapolations intentionally are not used.
     */
    public int observe(Pair pair,List<Contact> records,List<CollisionFrame.Snapshot> pre,boolean complete) {
        if(saturated||!complete||pair==null||!dimension.equals(pair.dimension())
                ||!fresh(pair.a())||!fresh(pair.b())||records==null||records.isEmpty()
                ||records.size()>MAX_FRAME_EDGES)return 0;
        try {
            // All records must support the same stationary pair. Reject a mixed
            // dynamic or malformed group, even if its first record looks valid.
            var geometries=new ArrayList<Geometry>(records.size());
            for(var record:records) {
                if(record==null||record.serverTick()!=tick)return 0;
                Geometry g=geometry(pair,record,pre);
                if(g==null)return 0;
                geometries.add(g);
            }
            int before=admitted;
            for(int i=0;i<records.size();i++) {
                var record=records.get(i);var g=geometries.get(i);
                Owner oa=owner(pair,record.idA(),record.frameA());
                Owner ob=owner(pair,record.idB(),record.frameB());
                Endpoint a=endpoint(oa,g.localA(),g.outwardA(),g.aPose(),g.firstEpoch());
                Endpoint b=endpoint(ob,g.localB(),g.outwardB(),g.bPose(),g.firstEpoch());
                if(a==null||b==null)continue;
                admit(new Proof(a,b,g));
                if(saturated)return 0;
            }
            return Math.max(0,admitted-before);
        }catch(RuntimeException|LinkageError unavailable){return 0;}
    }

    private Endpoint endpoint(Owner owner,Vec local,Vec outward,Pose pose,long epoch) {
        return endpoint(owner,local,outward,pose,epoch,shapeBatch,materials);
    }
    private static Endpoint endpoint(Owner owner,Vec local,Vec outward,Pose pose,long epoch,
                                     OwnerResolver.NativeAuditBatch shapes,TargetMaterials materials) {
        if(owner==null)return null;
        MovePlan.SignedAxes axis=MovePlan.quantizeDirection(-outward.x(),-outward.y(),-outward.z(),true,epoch);
        if(Math.abs(axis.x())+Math.abs(axis.y())+Math.abs(axis.z())!=1
                ||Math.abs(outward.dot(new Vec(axis.x(),axis.y(),axis.z())))<1-EPS)return null;
        Endpoint found=null;
        var candidates=SableConsumerTargets.cells(local);
        for(BlockPos pos:candidates) {
            var result=shapes.inspect(owner.stableKey(),pos,local).result();
            if(!result.verified()) {
                if(!EMPTY_SHAPE_RESULTS.contains(result.detail()))return null;
                continue;
            }
            if(!shapes.targetFace(owner.stableKey(),pos,local,axis,EPS))continue;
            if(found!=null)return null; // corner/edge identifies more than one face
            var key=result.key();
            var target=new Target(owner.stableKey(),key.x()+","+key.y()+","+key.z(),key.fingerprint());
            var material=materials.resolve(target);
            if(material==null)return null;
            found=new Endpoint(owner,target,pos.immutable(),local,axis,pose,material);
        }
        if(found!=null){
            // An apparent face shared by two same-owner cubes is an internal
            // seam, not an exterior connection to an independent structure.
            Vec outside=local.plus(outward.times(EPS*4));
            for(BlockPos pos:candidates)
                if(shapes.contains(owner.stableKey(),pos,outside,EPS/4))return null;
        }
        return found;
    }

    private void admit(Proof p) {
        Endpoint a=p.a(),b=p.b();
        boolean canonical=a.owner().compareTo(b.owner())<0;
        Identity id=new Identity(canonical?a.target():b.target(),canonical?b.target():a.target());
        if(ambiguous.contains(id))return;
        Proof old=proofs.get(id);
        if(old!=null) {
            Vec first=old.a().target().equals(a.target())?old.geometry().worldOutwardA():
                    old.geometry().worldOutwardA().times(-1);
            if(first.dot(p.geometry().worldOutwardA())<1-EPS) {
                proofs.remove(id);ambiguous.add(id);admitted--;
                adjacency.get(Cell.of(a.target())).remove(old);adjacency.get(Cell.of(b.target())).remove(old);
            }
            return;
        }
        if(proofs.size()+ambiguous.size()>=MAX_FRAME_EDGES) {
            saturated=true;adjacency.clear();proofs.clear();ambiguous.clear();owners.clear();admitted=0;
            return;
        }
        proofs.put(id,p);admitted++;
        owners.put(a.owner().stableKey(),a.owner());owners.put(b.owner().stableKey(),b.owner());
        adjacency.computeIfAbsent(Cell.of(a.target()),k->new ArrayList<>()).add(p);
        adjacency.computeIfAbsent(Cell.of(b.target()),k->new ArrayList<>()).add(p);
    }

    @Override public Map<String,Owner> owners() {
        if(saturated)return Map.of();
        var live=new HashMap<String,Owner>();
        owners.forEach((key,owner)->{if(fresh(owner))live.put(key,owner);});
        return Map.copyOf(live);
    }

    @Override public List<ForcePropagation.CrossEdge> edges(Target from,ForcePropagation.Orientation inward) {
        Lookup value=nextLookup;
        nextLookup=null;
        if(value==null||!Objects.equals(from,value.from())||!Objects.equals(inward,value.inward()))
            value=lookup(from,inward);
        return value.edges();
    }

    @Override public boolean unresolved(Target from,ForcePropagation.Orientation inward) {
        nextLookup=lookup(from,inward);
        return nextLookup.unresolved();
    }

    private Lookup lookup(Target from,ForcePropagation.Orientation inward) {
        if(from==null||inward==null||!inward.verified()||saturated)return new Lookup(from,inward,List.of(),false);
        Cell fromCell=Cell.of(from);
        var candidates=adjacency.get(fromCell);
        // Most F nodes are internal, so no extra world/BE reads for those.
        if(candidates==null||candidates.isEmpty())return new Lookup(from,inward,List.of(),false);
        if(!server.isSameThread()||!liveTick()||MaterialRegistry.generation()!=snapshot.generation())
            return new Lookup(from,inward,List.of(),true);
        // Live re-audit before any cross-owner use; a graph captured before
        // another queued plan must never release F to changed/removed material.
        var liveShape=new OwnerResolver.NativeAuditBatch(server,dimension);
        var liveMaterials=new TargetMaterials(server,dimension,snapshot);
        var out=new ArrayList<ForcePropagation.CrossEdge>();
        boolean unresolved=false;
        for(var proof:candidates) {
            boolean forward=Cell.of(proof.a().target()).equals(fromCell);
            var a=forward?proof.a():proof.b();var b=forward?proof.b():proof.a();
            Vec outward=forward?proof.geometry().worldOutwardA():proof.geometry().worldOutwardA().times(-1);
            var direction=transport(inward,a.pose(),b.pose(),outward);
            if(direction==null)continue; // not compression
            if(!a.target().equals(from)||!stillLive(a,liveShape,liveMaterials)||!stillLive(b,liveShape,liveMaterials)){
                unresolved=true;continue;
            }
            out.add(new ForcePropagation.CrossEdge(b.target(),direction.orientation(),direction.projection()));
        }
        out.sort(Comparator.comparing((ForcePropagation.CrossEdge e)->e.target().ownerKey())
                .thenComparing(e->e.target().localBlock()));
        return new Lookup(from,inward,List.copyOf(out),unresolved);
    }

    private boolean fresh(Owner owner) {
        try{return owner!=null&&currentGeneration.applyAsLong(owner.stableKey())==owner.generation();}
        catch(RuntimeException unavailable){return false;}
    }

    private boolean liveTick() {
        for(var level:server.getAllLevels())if(level.dimension().location().toString().equals(dimension))
            return level.getGameTime()==tick;
        return false;
    }

    private boolean stillLive(Endpoint endpoint,OwnerResolver.NativeAuditBatch shapes,TargetMaterials current) {
        if(!fresh(endpoint.owner()))return false;
        // No use of an old-world transform even if the local voxel still exists.
        // Re-read the authoritative pose of this loaded Plot after the snapshot.
        if(!livePose(endpoint))return false;
        var axis=endpoint.inward();
        var outward=new Vec(-axis.x(),-axis.y(),-axis.z());
        var live=endpoint(endpoint.owner(),endpoint.point(),outward,endpoint.pose(),axis.transformEpoch(),shapes,current);
        return endpoint.equals(live);
    }
    private boolean livePose(Endpoint endpoint){
        String owner=endpoint.owner().stableKey();
        if(owner.equals("WORLD:"+dimension))return true;
        try {
            var uuid=UUID.fromString(owner.substring(5));
            for(var level:server.getAllLevels())if(level.dimension().location().toString().equals(dimension)){
                var container=SubLevelContainer.getContainer(level);
                if(container==null||!(container.getSubLevel(uuid) instanceof ServerSubLevel sub)||sub.isRemoved())return false;
                var p=sub.logicalPose();var q=p.orientation();
                Pose live=new Pose(new Vec(p.position().x(),p.position().y(),p.position().z()),
                        new Vec(p.rotationPoint().x(),p.rotationPoint().y(),p.rotationPoint().z()),
                        new Vec(p.scale().x(),p.scale().y(),p.scale().z()),q.x(),q.y(),q.z(),q.w());
                return samePose(endpoint.pose(),live);
            }
        }catch(RuntimeException|LinkageError unsafe){return false;}
        return false;
    }

    /** Pure transform/compression test. The source direction is a vector,
     * not a normal or point, and must enter the receiver (not pull it). */
    static Transport transport(ForcePropagation.Orientation inward,Pose source,Pose receiver,Vec outward) {
        try {
            if(inward==null||!inward.verified()||source==null||receiver==null||outward==null)return null;
            Vec world=source.toWorldVector(inward.inward()).unit();
            double projection=world.dot(outward.unit());
            if(!Double.isFinite(projection)||projection<=EPS)return null;
            Vec receiving=receiver.toLocalVector(world).unit();
            return new Transport(new ForcePropagation.Orientation(receiving,
                    "VERIFIED_CONTACT_STATIONARY_COMPRESSION"),Math.min(1,projection));
        }catch(RuntimeException unsafe){return null;}
    }

    /**
     * Reject cross-phase projections unless EVERY pre frame was stationary
     * and had the same rigid transform as the actual effect consumer. Exact
     * native per-contact substep is unavailable and is never guessed.
     */
    static Geometry geometry(Pair pair,Contact c,List<CollisionFrame.Snapshot> pre) {
        if(pair==null||c==null||pre==null||c.capSaturated()||c.serverTick()<0
                ||!pair.dimension().equals(c.dimension())||!SableConsumerTargets.sourceRecordValid(c)
                ||c.rawImpactScalar()<=0)return null;
        try {
            Owner a=owner(pair,c.idA(),c.frameA()),b=owner(pair,c.idB(),c.frameB());
            if(a==null||b==null||a.equals(b))return null;
            Pose ap=pose(c.idA(),c.frameA()),bp=pose(c.idB(),c.frameB());
            if(!unitScale(ap)||!unitScale(bp))return null;
            Vec al=SableConsumerTargets.candidate(c,true),bl=SableConsumerTargets.candidate(c,false);
            if(al==null||bl==null)return null;
            Vec an=new Vec(c.ax(),c.ay(),c.az()).unit(),bn=new Vec(c.bx(),c.by(),c.bz()).unit();
            Vec aw=ap.toWorldPoint(al),bw=bp.toWorldPoint(bl);
            Vec na=ap.toWorldNormal(an),nb=bp.toWorldNormal(bn);
            if(na.dot(nb)>-1+EPS||!contactGeometry(aw,bw,na))return null;
            long first=Long.MAX_VALUE,last=-1;
            var epochs=new HashSet<Long>();
            for(var f:pre) {
                if(f==null||f.phase()!=CollisionFrame.Phase.PRE_SOLVER
                        ||f.serverTick()!=c.serverTick()||!pair.dimension().equals(f.dimension()))continue;
                if(!f.containerAvailable()||f.epoch()<0||!epochs.add(f.epoch()))return null;
                Pose preA=stationaryPose(f,c.idA(),c.frameA(),ap,al);
                Pose preB=stationaryPose(f,c.idB(),c.frameB(),bp,bl);
                if(preA==null||preB==null)return null;
                Vec preAw=preA.toWorldPoint(al.minus(ap.rotationPoint()).plus(preA.rotationPoint()));
                Vec preBw=preB.toWorldPoint(bl.minus(bp.rotationPoint()).plus(preB.rotationPoint()));
                Vec preAn=preA.toWorldNormal(an),preBn=preB.toWorldNormal(bn);
                if(preAn.dot(preBn)>-1+EPS||!contactGeometry(preAw,preBw,preAn))return null;
                first=Math.min(first,f.epoch());last=Math.max(last,f.epoch());
            }
            if(last<0)return null;
            return new Geometry(ap,bp,al,bl,aw,bw,an,bn,na,first,last);
        }catch(RuntimeException invalid){return null;}
    }

    private static boolean contactGeometry(Vec a,Vec b,Vec outwardA) {
        Vec delta=b.minus(a);
        double signedGap=delta.dot(outwardA);
        Vec lateral=delta.minus(outwardA.times(signedGap));
        return Double.isFinite(signedGap)&&signedGap<=EPS&&signedGap>=-MAX_PENETRATION
                &&lateral.squareLength()<=EPS*EPS;
    }

    private static Owner owner(Pair pair,int id,OwnerFrame frame) {
        String key=id==-1?"WORLD:"+pair.dimension():frame==null?null:"PLOT:"+frame.uuid();
        return pair.a().stableKey().equals(key)?pair.a():pair.b().stableKey().equals(key)?pair.b():null;
    }
    private static Pose pose(int id,OwnerFrame f){return id==-1?WORLD_POSE:f==null?null:f.pose();}
    private static boolean unitScale(Pose p) {
        // Retain the existing native COM/scale gate; Rapier did not export a
        // non-unit collider-scale transform in the 15-double ABI.
        return p!=null&&p.valid()&&Math.abs(p.scale().x()-1)<=1e-6
                &&Math.abs(p.scale().y()-1)<=1e-6&&Math.abs(p.scale().z()-1)<=1e-6;
    }
    private static boolean close(Vec a,Vec b){return a!=null&&b!=null&&a.finite()&&b.finite()&&a.minus(b).squareLength()<=EPS*EPS;}
    private static boolean samePose(Pose a,Pose b){
        if(!unitScale(a)||!unitScale(b)||!close(a.position(),b.position())||!close(a.rotationPoint(),b.rotationPoint()))return false;
        for(Vec v:List.of(new Vec(1,0,0),new Vec(0,1,0),new Vec(0,0,1)))
            if(!close(a.toWorldVector(v),b.toWorldVector(v)))return false;
        return true;
    }
    private static Pose stationaryPose(CollisionFrame.Snapshot f,int id,OwnerFrame effect,Pose effectPose,Vec localPoint) {
        if(id==-1)return WORLD_POSE;
        CollisionFrame.Body match=null;
        for(var body:f.bodies())if(body.runtimeId()==id) {
            if(match!=null)return null;
            match=body;
        }
        if(match==null||!match.valid()||effect==null||!effect.uuid().equals(match.plotId())
                ||!samePose(effectPose,match.pose())||!close(match.centerRaw(),match.pose().rotationPoint()))return null;
        if(!slow(match.linearRaw())||!slow(match.angularRaw()))return null;
        Vec point=localPoint.minus(effectPose.rotationPoint()).plus(match.pose().rotationPoint());
        return slow(contactVelocity(match.linearRaw(),match.angularRaw(),
                match.pose().toWorldPoint(point),match.pose().position()))?match.pose():null;
    }
    private static boolean slow(Vec v){return v!=null&&v.finite()&&v.squareLength()<=STILL_SPEED*STILL_SPEED;}
}

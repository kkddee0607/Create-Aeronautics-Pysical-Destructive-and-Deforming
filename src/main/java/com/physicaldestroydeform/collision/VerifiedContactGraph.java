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
 * Native-witnessed contact graph for independent structures. Rapier's effect
 * feed has no source substep or causal ID.
 * Consequently each admitted edge must be geometrically valid across EVERY
 * captured pre pose and final consumer pose. The caller separately excludes
 * every pair that may issue its own direct Credit. The graph never mints one.
 *
 * The native record, exact dual owner-shape faces, stable transforms, generation
 * and material snapshot together are the evidence; AABB overlap/proximity alone
 * is insufficient. No block scan, contact extrapolation, chunk load, or callback
 * mutation. The live graph expires at its processing stamp. Bounded positive
 * history only revalidates exact endpoint identities; it cannot authorize a
 * contact without a fresh native record and actual current geometry.
 */
public final class VerifiedContactGraph implements ForcePropagation.CrossOwnerContext {
    private static final double EPS=1.0e-5;
    private static final double MAX_PENETRATION=1.0/32.0;
    private static final double STILL_SPEED=1.0e-3;
    private static final Vec ZERO=new Vec(0,0,0);
    private static final Pose WORLD_POSE=new Pose(ZERO,ZERO,new Vec(1,1,1),0,0,0,1);
    private static final int MAX_FRAME_EDGES=NativeEffectGroups.MAX_RECORDS;
    private static final int MAX_PRE_FRAMES=256;
    private static final int MAX_CUBE_FRAME_CHECKS=8192;
    private static final Set<String> EMPTY_SHAPE_RESULTS=Set.of("CACHED_SHAPE_POINT_MISS",
            "BLOCK_MISSING_OR_AIR","PLOT_SHAPE_EMPTY","WORLD_SHAPE_EMPTY");

    /** Original native A/B order, not pair canonical order. The substep index
     * remains unknown: first/lastEpoch record the ALL-pre-epochs proof. */
    record Geometry(Pose aPose,Pose bPose,Vec localA,Vec localB,Vec worldA,Vec worldB,
                    Vec outwardA,Vec outwardB,Vec worldOutwardA,long firstEpoch,long lastEpoch) {}
    record Transport(ForcePropagation.Orientation orientation,double projection) {}
    private record Endpoint(Owner owner,Target target,BlockPos allocated,Vec point,
                            MovePlan.SignedAxes inward,Pose pose,CopycatMaterial.Resolved material,
                            Vec nativeOutward,boolean cube,boolean exactFace) {
        EndIdentity identity(){return new EndIdentity(target,inward.x(),inward.y(),inward.z(),material);}
        Vec corner(){return new Vec(allocated.getX(),allocated.getY(),allocated.getZ());}
    }
    private record Proof(Endpoint a,Endpoint b,Geometry geometry) {}
    private record Identity(Target low,Target high) {}
    private record Cell(String owner,String position) {
        static Cell of(Target target){return new Cell(target.ownerKey(),target.localBlock());}
    }
    private record Lookup(Target from,ForcePropagation.Orientation inward,
                          List<ForcePropagation.CrossEdge> edges,boolean unresolved) {}
    private record PosePair(Pose a,Pose b) {}
    private record NativeFrames(Geometry geometry,List<PosePair> poses,boolean exactPoints) {}
    record EndIdentity(Target target,int nx,int ny,int nz,CopycatMaterial.Resolved material) {}
    private record HistoryKey(Pair pair,EndIdentity a,EndIdentity b) {
        static HistoryKey of(Pair pair,EndIdentity a,EndIdentity b){
            if(pair.a().stableKey().equals(a.target().ownerKey())&&pair.b().stableKey().equals(b.target().ownerKey()))
                return new HistoryKey(pair,a,b);
            if(pair.b().stableKey().equals(a.target().ownerKey())&&pair.a().stableKey().equals(b.target().ownerKey()))
                return new HistoryKey(pair,b,a);
            throw new IllegalArgumentException("history endpoints do not match pair");
        }
    }
    /** Server-scoped positive observations only, independent of direct credit.
     * A generation, fingerprint, face or material change cannot match old proof. */
    public static final class History {
        public static final long TTL=20;
        public static final int CAP=4096;
        private final LinkedHashMap<HistoryKey,Long> entries=new LinkedHashMap<>(16,.75f,true);
        private final Map<Pair,Set<HistoryKey>> byPair=new HashMap<>();
        private java.lang.ref.WeakReference<MinecraftServer> server=new java.lang.ref.WeakReference<>(null);
        private void bind(MinecraftServer current){
            if(server.get()!=current){clear();server=new java.lang.ref.WeakReference<>(current);}
        }
        public int size(){return entries.size();}
        public void clear(){entries.clear();byPair.clear();}
        public void invalidatePair(Pair pair){
            var keys=byPair.remove(pair);
            if(keys!=null)keys.forEach(entries::remove);
        }
        public void invalidateOwner(String owner){
            for(var pair:List.copyOf(byPair.keySet()))
                if(pair.a().stableKey().equals(owner)||pair.b().stableKey().equals(owner))invalidatePair(pair);
        }
        public boolean hasRecent(Pair pair,long tick){
            var keys=byPair.get(pair);
            if(keys==null)return false;
            boolean found=false;
            for(var key:List.copyOf(keys))if(recent(key,tick))found=true;
            return found;
        }
        boolean matches(Pair pair,EndIdentity a,EndIdentity b,long tick){
            return recent(HistoryKey.of(pair,a,b),tick);
        }
        private boolean recent(HistoryKey key,long tick){
            Long last=entries.get(key);
            if(last==null)return false;
            if(tick>=last&&tick-last<=TTL)return true;
            remove(key);return false;
        }
        private void remove(HistoryKey key){
            entries.remove(key);
            var keys=byPair.get(key.pair());
            if(keys!=null){keys.remove(key);if(keys.isEmpty())byPair.remove(key.pair());}
        }
        void remember(Pair pair,EndIdentity a,EndIdentity b,long tick){
            if(tick<0)throw new IllegalArgumentException("negative contact tick");
            var key=HistoryKey.of(pair,a,b);
            entries.put(key,tick);byPair.computeIfAbsent(pair,k->new HashSet<>()).add(key);
            while(entries.size()>CAP)remove(entries.keySet().iterator().next());
        }
    }

    private final MinecraftServer server;
    private final String dimension;
    private final long tick;
    private final ToLongFunction<String> currentGeneration;
    private final MaterialRegistry.Snapshot snapshot;
    private final TargetMaterials materials;
    private final OwnerResolver.NativeAuditBatch shapeBatch;
    private final History history;
    private final Map<String,Integer> stats=new TreeMap<>();
    private final Map<Cell,List<Proof>> adjacency=new HashMap<>();
    private final Map<Identity,Proof> proofs=new HashMap<>();
    private final Set<Identity> ambiguous=new HashSet<>();
    private final Map<String,Owner> owners=new HashMap<>();
    private int admitted;
    private int cubeFrameChecks;
    private boolean saturated;
    private Lookup nextLookup;

    public VerifiedContactGraph(MinecraftServer server,String dimension,long tick,
                                ToLongFunction<String> currentGeneration) {
        this(server,dimension,tick,currentGeneration,new History());
    }
    public VerifiedContactGraph(MinecraftServer server,String dimension,long tick,
                                ToLongFunction<String> currentGeneration,History history) {
        this.server=Objects.requireNonNull(server);
        if(!server.isSameThread()||dimension==null||dimension.isBlank()||tick<0)
            throw new IllegalArgumentException("invalid server contact stamp");
        this.dimension=dimension;this.tick=tick;
        this.currentGeneration=Objects.requireNonNull(currentGeneration);
        this.snapshot=MaterialRegistry.snapshot();
        this.materials=new TargetMaterials(server,dimension,snapshot);
        this.shapeBatch=new OwnerResolver.NativeAuditBatch(server,dimension);
        this.history=Objects.requireNonNull(history);
        this.history.bind(server);
    }
    private void count(String reason){stats.merge(reason,1,Integer::sum);}
    public Map<String,Integer> stats(){return Map.copyOf(stats);}
    public int historySize(){return history.size();}

    /**
     * Observe only a secondary pair which cannot mint a direct Credit in the
     * current stamp. A native poll cap means omitted records are unknown; a
     * supplied positive record can still independently prove its endpoints.
     * Face-patch extrapolations intentionally are not used.
     */
    public int observe(Pair pair,List<Contact> records,List<CollisionFrame.Snapshot> pre,boolean complete) {
        if(saturated||!complete||pair==null||!dimension.equals(pair.dimension())
                ||!fresh(pair.a())||!fresh(pair.b())||records==null||records.isEmpty()
                ||records.size()>MAX_FRAME_EDGES){count("INCOMPLETE_OR_STALE");return 0;}
        if(!liveTick()){count("STALE_STAMP");return 0;}
        try {
            int before=admitted;
            Set<Identity> observed=new HashSet<>();
            for(var record:records) {
                try {
                if(record==null||record.serverTick()!=tick){count("RECORD_STAMP");continue;}
                NativeFrames frames=nativeFrames(pair,record,pre);
                if(frames==null){count("FRAME_OR_NORMAL");continue;}
                if(record.capSaturated())count("POSITIVE_CAPPED_RECORD");
                var g=frames.geometry();
                Owner oa=owner(pair,record.idA(),record.frameA());
                Owner ob=owner(pair,record.idB(),record.frameB());
                var as=endpoints(oa,g.localA(),g.outwardA(),g.aPose(),g.firstEpoch(),shapeBatch,materials);
                var bs=endpoints(ob,g.localB(),g.outwardB(),g.bPose(),g.firstEpoch(),shapeBatch,materials);
                if(as.isEmpty()||bs.isEmpty()){count("ENDPOINT_OR_MATERIAL");continue;}
                // Do not choose an arbitrary first endpoint when a complete
                // comparison of all candidates cannot fit this work budget.
                long checks=(long)as.size()*bs.size()*frames.poses().size();
                if(checks>MAX_CUBE_FRAME_CHECKS-cubeFrameChecks){count("CUBE_WORK_CAP");continue;}
                cubeFrameChecks+=(int)checks;
                var candidates=new ArrayList<Proof>();
                for(var a:as)for(var b:bs) {
                    if(frames.exactPoints()&&a.exactFace()&&b.exactFace()
                            ||a.cube()&&b.cube()&&allCubesTouch(a,b,frames))
                        candidates.add(new Proof(a,b,g));
                }
                if(candidates.isEmpty()){count("CONTACT_GEOMETRY");continue;}
                Proof selected=candidates.size()==1?candidates.getFirst():null;
                if(selected==null) {
                    // History may distinguish an exact previously established
                    // endpoint pair only when each candidate has ALREADY passed
                    // positive current witness AND current shape contact.
                    var matches=candidates.stream().filter(p->history.matches(pair,
                            p.a().identity(),p.b().identity(),tick)).toList();
                    if(matches.size()!=1){count("AMBIGUOUS_ENDPOINTS");continue;}
                    selected=matches.getFirst();count("HISTORY_DISAMBIGUATED_CURRENT");
                }else count(history.matches(pair,selected.a().identity(),selected.b().identity(),tick)
                        ?"HISTORY_REVALIDATED_CURRENT":"NEW_POSITIVE_CURRENT");
                Identity identity=identity(selected);
                observed.add(identity);admit(selected);
                if(saturated){count("GRAPH_CAP");return 0;}
                }catch(RuntimeException|LinkageError unavailable){count("RECORD_AUDIT_EXCEPTION");}
            }
            if(observed.stream().anyMatch(ambiguous::contains))history.invalidatePair(pair);
            for(var id:observed){
                var proof=proofs.get(id);
                if(proof!=null&&!ambiguous.contains(id))
                    history.remember(pair,proof.a().identity(),proof.b().identity(),tick);
            }
            return Math.max(0,admitted-before);
        }catch(RuntimeException|LinkageError unavailable){count("AUDIT_EXCEPTION");return 0;}
    }

    private static List<Endpoint> endpoints(Owner owner,Vec local,Vec outward,Pose pose,long epoch,
                                     OwnerResolver.NativeAuditBatch shapes,TargetMaterials materials) {
        if(owner==null)return List.of();
        MovePlan.SignedAxes axis=MovePlan.quantizeDirection(-outward.x(),-outward.y(),-outward.z(),true,epoch);
        boolean axial=Math.abs(axis.x())+Math.abs(axis.y())+Math.abs(axis.z())==1
                &&Math.abs(outward.dot(new Vec(axis.x(),axis.y(),axis.z())))>=1-EPS;
        var found=new ArrayList<Endpoint>();
        var candidates=SableConsumerTargets.cells(local);
        for(BlockPos pos:candidates) {
            var result=shapes.inspect(owner.stableKey(),pos,local).result();
            if(!result.verified()) {
                if(!EMPTY_SHAPE_RESULTS.contains(result.detail()))return List.of();
                continue;
            }
            boolean exact=axial&&shapes.targetFace(owner.stableKey(),pos,local,axis,EPS);
            boolean cube=shapes.fullCube(owner.stableKey(),pos);
            if(!exact&&(!cube||!shapes.targetFace(owner.stableKey(),pos,local,axis,SableConsumerTargets.EPSILON)
                    ||!cubeSourceFace(local,pos,outward)))continue;
            var key=result.key();
            var target=new Target(owner.stableKey(),key.x()+","+key.y()+","+key.z(),key.fingerprint());
            var material=materials.resolve(target);
            if(material==null)return List.of();
            found.add(new Endpoint(owner,target,pos.immutable(),local,axis,pose,material,outward,cube,exact));
        }
        found.removeIf(endpoint->{
            // An apparent face shared by two same-owner cubes is an internal
            // seam, not an exterior connection to an independent structure.
            Vec face=endpoint.cube()?snapFace(local,endpoint.allocated(),axis):local;
            Vec outside=face.plus(outward.times(EPS*4));
            for(BlockPos pos:candidates)
                if(shapes.contains(owner.stableKey(),pos,outside,EPS/4))return true;
            return false;
        });
        return List.copyOf(found);
    }
    private static Vec snapFace(Vec point,BlockPos pos,MovePlan.SignedAxes axis){
        return new Vec(snap(point.x(),pos.getX(),axis.x()),snap(point.y(),pos.getY(),axis.y()),snap(point.z(),pos.getZ(),axis.z()));
    }
    private static boolean cubeSourceFace(Vec p,BlockPos pos,Vec outward){
        double support=.5*(Math.abs(outward.x())+Math.abs(outward.y())+Math.abs(outward.z()));
        var center=new Vec(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5);
        double gap=support-p.minus(center).dot(outward);
        return Double.isFinite(gap)&&Math.abs(gap)<=SableConsumerTargets.EPSILON;
    }
    private static double snap(double p,int min,int sign){
        return sign>0?min:sign<0?min+1.0:Math.max(min,Math.min(min+1.0,p));
    }

    private static Identity identity(Proof p){
        Endpoint a=p.a(),b=p.b();
        boolean canonical=a.owner().compareTo(b.owner())<0;
        return new Identity(canonical?a.target():b.target(),canonical?b.target():a.target());
    }
    private void admit(Proof p) {
        Endpoint a=p.a(),b=p.b();
        Identity id=identity(p);
        if(ambiguous.contains(id))return;
        Proof old=proofs.get(id);
        if(old!=null) {
            boolean forward=old.a().target().equals(a.target());
            Vec first=forward?old.geometry().worldOutwardA():
                    old.geometry().worldOutwardA().times(-1);
            if(first.dot(p.geometry().worldOutwardA())<.98
                    ||!(forward?old.a():old.b()).identity().equals(a.identity())
                    ||!(forward?old.b():old.a()).identity().equals(b.identity())) {
                count("CONFLICTING_CONTACT_NORMAL");
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
                count("STALE_LIVE_ENDPOINT");unresolved=true;continue;
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
        var live=endpoints(endpoint.owner(),endpoint.point(),endpoint.nativeOutward(),endpoint.pose(),
                endpoint.inward().transformEpoch(),shapes,current);
        return live.contains(endpoint);
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
                    "VERIFIED_CONTACT_CURRENT_COMPRESSION"),Math.min(1,projection));
        }catch(RuntimeException unsafe){return null;}
    }

    /** Current-record proof, including cap-marked positive records and motion.
     * A single observed contact does not prove the completeness of a capped
     * poll. Nothing is inferred about omitted contacts or force causation. */
    static Geometry revalidateGeometry(Pair pair,Contact c,List<CollisionFrame.Snapshot> pre) {
        var frames=nativeFrames(pair,c,pre);
        return frames!=null&&frames.exactPoints()?frames.geometry():null;
    }
    private static NativeFrames nativeFrames(Pair pair,Contact c,List<CollisionFrame.Snapshot> pre) {
        if(pair==null||c==null||pre==null||c.serverTick()<0
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
            if(na.dot(nb)>-.98)return null;
            boolean exact=na.dot(nb)<=-1+EPS&&contactGeometry(aw,bw,na);
            long first=Long.MAX_VALUE,last=-1;
            var epochs=new HashSet<Long>();
            var poses=new ArrayList<PosePair>();
            poses.add(new PosePair(ap,bp));
            for(var f:pre) {
                if(f==null||f.phase()!=CollisionFrame.Phase.PRE_SOLVER
                        ||f.serverTick()!=c.serverTick()||!pair.dimension().equals(f.dimension()))continue;
                if(!f.containerAvailable()||f.epoch()<0||!epochs.add(f.epoch()))return null;
                if(epochs.size()>MAX_PRE_FRAMES)return null;
                Pose preA=knownPose(f,c.idA(),c.frameA(),ap);
                Pose preB=knownPose(f,c.idB(),c.frameB(),bp);
                if(preA==null||preB==null)return null;
                Vec preAw=preA.toWorldPoint(al.minus(ap.rotationPoint()).plus(preA.rotationPoint()));
                Vec preBw=preB.toWorldPoint(bl.minus(bp.rotationPoint()).plus(preB.rotationPoint()));
                Vec preAn=preA.toWorldNormal(an),preBn=preB.toWorldNormal(bn);
                if(preAn.dot(preBn)>-.98)return null;
                exact&=preAn.dot(preBn)<=-1+EPS&&contactGeometry(preAw,preBw,preAn);
                poses.add(new PosePair(preA,preB));
                first=Math.min(first,f.epoch());last=Math.max(last,f.epoch());
            }
            if(last<0||last-first+1!=epochs.size())return null;
            return new NativeFrames(new Geometry(ap,bp,al,bl,aw,bw,an,bn,na,first,last),List.copyOf(poses),exact);
        }catch(RuntimeException invalid){return null;}
    }
    private static Pose knownPose(CollisionFrame.Snapshot f,int id,OwnerFrame effect,Pose effectPose){
        if(id==-1)return WORLD_POSE;
        CollisionFrame.Body match=null;
        for(var body:f.bodies())if(body.runtimeId()==id){
            if(match!=null)return null;
            match=body;
        }
        if(match==null||!match.valid()||effect==null||!effect.uuid().equals(match.plotId())
                ||!unitScale(match.pose())||!close(match.centerRaw(),match.pose().rotationPoint())
                ||!close(match.pose().rotationPoint(),effectPose.rotationPoint()))return null;
        return match.pose();
    }
    private static boolean allCubesTouch(Endpoint a,Endpoint b,NativeFrames frames){
        for(var f:frames.poses())if(!cubesTouch(f.a(),a.corner(),f.b(),b.corner(),
                f.a().toWorldNormal(frames.geometry().outwardA())))return false;
        return true;
    }
    private record Cube(Vec center,List<Vec> axes,List<Double> halves){
        static Cube at(Pose pose,Vec corner){
            var localAxes=List.of(new Vec(1,0,0),new Vec(0,1,0),new Vec(0,0,1));
            var axes=new ArrayList<Vec>(3);var halves=new ArrayList<Double>(3);
            for(var axis:localAxes){
                Vec v=pose.toWorldVector(axis);
                axes.add(v.unit());halves.add(Math.sqrt(v.squareLength())*.5);
            }
            return new Cube(pose.toWorldPoint(corner.plus(new Vec(.5,.5,.5))),List.copyOf(axes),List.copyOf(halves));
        }
        double radius(Vec axis){
            double r=0;
            for(int i=0;i<3;i++)r+=Math.abs(axis.dot(axes.get(i)))*halves.get(i);
            return r;
        }
    }
    /** Exact oriented FULL collision cubes only. The three axes of each cube
     * and all nine cross products are required (not an AABB overlap check).
     * Source shape/native face proof is established separately by the caller.
     * Positive clearance on ANY axis rejects. The native outward normal
     * additionally must describe a shallow compressive face/contact boundary. */
    static boolean cubesTouch(Pose aPose,Vec aCorner,Pose bPose,Vec bCorner,Vec outward) {
        if(!unitScale(aPose)||!unitScale(bPose)||aCorner==null||bCorner==null||outward==null)return false;
        try {
            Cube a=Cube.at(aPose,aCorner),b=Cube.at(bPose,bCorner);
            Vec delta=b.center().minus(a.center()),normal=outward.unit();
            double depth=a.radius(normal)+b.radius(normal)-delta.dot(normal);
            if(!Double.isFinite(depth)||depth<-EPS||depth>MAX_PENETRATION)return false;
            for(var axis:a.axes())if(separated(a,b,delta,axis))return false;
            for(var axis:b.axes())if(separated(a,b,delta,axis))return false;
            for(var x:a.axes())for(var y:b.axes()){
                Vec axis=x.cross(y);
                if(axis.squareLength()>1e-18&&separated(a,b,delta,axis.unit()))return false;
            }
            return true;
        }catch(RuntimeException invalid){return false;}
    }
    private static boolean separated(Cube a,Cube b,Vec delta,Vec axis){
        double gap=Math.abs(delta.dot(axis))-a.radius(axis)-b.radius(axis);
        return !Double.isFinite(gap)||gap>EPS;
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

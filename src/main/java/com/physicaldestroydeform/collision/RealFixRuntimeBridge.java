package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.diagnostics.RawObservation;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.physicaldestroydeform.collision.GameImpactCredit.*;
import static com.physicaldestroydeform.collision.RealFixBoundsEvidence.*;

/**
 * Read-only 1.0.5-A game-impact adapter. Does not reinterpret the native force
 * as impulse and does not require a hypothetical Rapier JNI contact API.
 *
 * Source groups: original clearCollisions array observed by existing Mixin.
 * Source owners: same-dimension, same-tick Sable pre-physics body snapshots.
 * Source targets: already-existing, verified original non-null callback audit.
 * Source separation: positive gap in actual Sable world-space sublevel AABBs.
 *
 * The R2/R3 point transform is NOT marked calibrated; R4 is never promoted to
 * verified target by guessing a world block. Incomplete evidence yields Q=0.
 */
public final class RealFixRuntimeBridge {
    private static final int MAX_TRACKED=4096;
    private static final int MAX_LOG_PER_TICK=64;
    private final String runId=UUID.randomUUID().toString();
    private final GameImpactCredit credit=new GameImpactCredit(Settings.fromSystemProperties());
    private final Map<Pair,Tracked> tracked=new HashMap<>();
    private final Map<String,Generation> generations=new HashMap<>();
    private long credited,observed,zero,missingOwner,ambiguousOwners,missingTarget;
    private long lastDropped,lastMalformed,lastCallbackDropped,lastPreHistoryDropped;
    private static final class Tracked {
        Target a,b;
        Box previousA,previousB;
        long lastSeen=-1;
        long lastProcessed=-1;
        boolean lastPositiveSeparation;
        double lastPeak=Double.NaN;
    }
    private static final class Generation {
        int runtimeId;
        long number=1;
        Generation(int runtimeId){this.runtimeId=runtimeId;}
    }
    private record Stamp(String dimension,long tick) { }
    private record SceneKey(String dimension,long tick,String lowOwner,String highOwner) { }
    private static final class ObservedTargets {
        final Map<String,Set<Target>> sides=new HashMap<>();
        void add(String owner,Target target) {if(target!=null)sides.computeIfAbsent(owner,k->new HashSet<>()).add(target);}
        Target unique(String owner) {var s=sides.get(owner);return s!=null&&s.size()==1?s.iterator().next():null;}
        boolean ambiguous(String owner) {var s=sides.get(owner);return s!=null&&s.size()>1;}
    }
    private record RuntimeOwners(Pair pair) { }
    private static final String AMBIGUOUS_OWNER = "\u0000";
    private static final int MAX_INDEX_BODIES_PER_TICK = 16384;
    private static final class OwnerSnapshot {
        final Map<Integer,String> bodies=new HashMap<>();
        boolean complete=true;
        int scanned;
    }

    /** Build owner->runtime evidence once per server tick, not once per native record. */
    private static Map<Stamp,OwnerSnapshot> ownerSnapshots(List<CollisionFrame.Snapshot> pre) {
        Map<Stamp,OwnerSnapshot> result=new HashMap<>();
        for(var snap:pre) {
            if(snap.phase()!=CollisionFrame.Phase.PRE_SOLVER || snap.serverTick()<0
                    || snap.dimension()==null)continue;
            Stamp stamp=new Stamp(snap.dimension(),snap.serverTick());
            OwnerSnapshot index=result.computeIfAbsent(stamp,k->new OwnerSnapshot());
            if(!snap.containerAvailable()) {index.complete=false;continue;}
            if(!index.complete)continue;
            for(var b:snap.bodies()) {
                if(++index.scanned>MAX_INDEX_BODIES_PER_TICK) {
                    index.complete=false;index.bodies.clear();break;
                }
                if(b==null || !b.ownerCandidateValid() || b.runtimeId()<0 || b.plotId()==null)continue;
                String stable="PLOT:"+b.plotId();
                index.bodies.merge(b.runtimeId(),stable,(oldValue,newValue)->
                        oldValue.equals(newValue)?oldValue:AMBIGUOUS_OWNER);
            }
        }
        return result;
    }

    public void clear() {
        credit.clear();tracked.clear();generations.clear();
        lastDropped=lastMalformed=lastCallbackDropped=lastPreHistoryDropped=0;
    }

    /** Called only from ServerTickEvent.Post. Never mutate any world or native buffer. */
    public void tick(MinecraftServer server,List<RawObservation> callbacks,
                     List<NativeCollisionTelemetry.Contact> nativeEvents,
                     List<CollisionFrame.Snapshot> pre,
                     Logger log) {
        Objects.requireNonNull(server);Objects.requireNonNull(callbacks);
        Objects.requireNonNull(nativeEvents);Objects.requireNonNull(pre);Objects.requireNonNull(log);
        final long now=server.getTickCount();
        final boolean complete=NativeCollisionTelemetry.dropped()==lastDropped
                && NativeCollisionTelemetry.malformed()==lastMalformed
                && com.physicaldestroydeform.diagnostics.SableContactQueue.dropped()==lastCallbackDropped
                && SableFrameCapture.preHistoryDropped()==lastPreHistoryDropped;
        lastDropped=NativeCollisionTelemetry.dropped();
        lastMalformed=NativeCollisionTelemetry.malformed();
        lastCallbackDropped=com.physicaldestroydeform.diagnostics.SableContactQueue.dropped();
        lastPreHistoryDropped=SableFrameCapture.preHistoryDropped();
        final Map<Stamp,List<NativeCollisionTelemetry.Contact>> raw=new HashMap<>();
        for(var c:nativeEvents){
            if(c.dimension()!=null&&c.serverTick()>=0)
                raw.computeIfAbsent(new Stamp(c.dimension(),c.serverTick()),k->new ArrayList<>()).add(c);
        }
        final Map<SceneKey,ObservedTargets> targetEvidence=callbackTargets(server,callbacks);
        final Map<Stamp,OwnerSnapshot> ownerIndex=ownerSnapshots(pre);
        final Map<String,Map<String,Box>> worldBounds=readServerBounds(server);
        final Map<String,Box> knownWorldBlocks=new HashMap<>();
        // Pre-solver snapshots may be from multiple substeps. A runtime pair is
        // accepted only when every matching snapshot maps to the same stable pair.
        int lines=0;
        int[] remainingR4Checks={RealFixBoundaryCandidates.MAX_PER_TICK};
        final Set<Pair> contacted=new HashSet<>();
        for(var stampEntry:raw.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Stamp,List<NativeCollisionTelemetry.Contact>> x)->x.getKey().dimension)
                        .thenComparingLong(x->x.getKey().tick)).toList()) {
            Stamp stamp=stampEntry.getKey();
            List<NativeCollisionTelemetry.Contact> values=stampEntry.getValue();
            double[] copied=new double[values.size()*15];
            for(int i=0;i<values.size();i++) {
                var c=values.get(i);int o=i*15;
                copied[o]=c.idA();copied[o+1]=c.idB();copied[o+2]=c.rawImpactScalar();
                copied[o+3]=c.ax();copied[o+4]=c.ay();copied[o+5]=c.az();
                copied[o+6]=c.bx();copied[o+7]=c.by();copied[o+8]=c.bz();
                copied[o+9]=c.contactAX();copied[o+10]=c.contactAY();copied[o+11]=c.contactAZ();
                copied[o+12]=c.contactBX();copied[o+13]=c.contactBY();copied[o+14]=c.contactBZ();
            }
            NativeEffectGroups.Batch batch=NativeEffectGroups.aggregate(stamp.dimension,stamp.tick,copied);
            for(var group:batch.groups()) {
                observed++;
                if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-OBS] source=POST_EFFECT dimension={} tick={} bodyIds={}/{} rawRecords={} peakForce={} discarded={} invalid={} mutation=OFF",
                        stamp.dimension,stamp.tick,group.runtimePair().lowId(),group.runtimePair().highId(),
                        group.originalRecordCount(),group.forcePeak(),batch.discardedRecords(),batch.invalidRecords());
                RuntimeOwners owners=resolveOwners(ownerIndex,stamp,group);
                if(owners==null) {
                    missingOwner++;zero++;
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-OBS] source=POST_EFFECT dimension={} tick={} ids={}/{} peak={} result=OWNER_UNVERIFIED mutation=OFF",
                            stamp.dimension,stamp.tick,group.runtimePair().lowId(),group.runtimePair().highId(),group.forcePeak());
                    continue;
                }
                Pair pair=owners.pair();
                SceneKey scene=new SceneKey(stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey());
                ObservedTargets candidate=targetEvidence.get(scene);
                Target a=candidate==null?null:candidate.unique(pair.a().stableKey());
                Target b=candidate==null?null:candidate.unique(pair.b().stableKey());
                if(candidate!=null && (candidate.ambiguous(pair.a().stableKey())||candidate.ambiguous(pair.b().stableKey()))) {
                    ambiguousOwners++;a=null;b=null;
                }
                Tracked t=tracked.get(pair);
                if(t==null && tracked.size()<MAX_TRACKED) {
                    t=new Tracked();tracked.put(pair,t);
                }
                if(t==null){zero++;continue;}
                if(a!=null)t.a=a;
                if(b!=null)t.b=b;
                Box boxA=box(server,pair.a().stableKey(),a==null?t.a:a,stamp.dimension,worldBounds,knownWorldBlocks);
                Box boxB=box(server,pair.b().stableKey(),b==null?t.b:b,stamp.dimension,worldBounds,knownWorldBlocks);
                boolean approach=RealFixBoundsEvidence.approaching(t.previousA,t.previousB,boxA,boxB);
                boolean previousSeparated=t.lastPositiveSeparation;
                double rise=Double.isFinite(t.lastPeak)?group.forcePeak()-t.lastPeak:Double.NaN;
                t.lastPeak=group.forcePeak();
                t.lastPositiveSeparation=false;
                t.previousA=boxA;t.previousB=boxB;
                t.lastSeen=now;t.lastProcessed=now;
                contacted.add(pair);
                if(a==null&&b==null)missingTarget++;
                // R4 shadow audit. This only describes plausible loaded world
                // blocks; WITHOUT in-game ground truth it can never mint credit.
                if(pair.a().stableKey().startsWith("WORLD:") && a==null) {
                    String status=probeWorldR4(server,stamp.dimension,boxB,remainingR4Checks);
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-TARGET] source=R4_SWEPT_PROVISIONAL dimension={} tick={} worldCandidate={} credited=false",stamp.dimension,stamp.tick,status);
                }
                if(pair.b().stableKey().startsWith("WORLD:") && b==null) {
                    String status=probeWorldR4(server,stamp.dimension,boxA,remainingR4Checks);
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-TARGET] source=R4_SWEPT_PROVISIONAL dimension={} tick={} worldCandidate={} credited=false",stamp.dimension,stamp.tick,status);
                }
                if(!complete || batch.invalidRecords()>0 || batch.discardedRecords()>0 || batch.malformedArray()) {
                    zero++;
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-EPISODE] dimension={} tick={} ownerA={} ownerB={} reason=INCOMPLETE_INPUT Q=0 worldMutation=OFF",
                            stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey());
                    continue;
                }
                try {
                    // AABB overlap is UNKNOWN, never 'contact proved'. New Q
                    // requires previous positive separation AND an actual closing
                    // gap AND at least one verified callback-owned block target.
                    var frame=new Frame(pair,now,group.samples(),false,approach,a,b,group.forcePeak());
                    var result=credit.step(frame);
                    if(result.credited()) credited++; else zero++;
                    if(lines++<MAX_LOG_PER_TICK) log.info("[PDD-TARGET] dimension={} tick={} ownerA={} ownerAGeneration={} ownerB={} ownerBGeneration={} targetA={} fingerprintA={} targetB={} fingerprintB={} frameMethod=R1_OR_NONE shapeDistance=NOT_CALIBRATED reason={} approachByAabb={} worldMutation=OFF",
                            stamp.dimension,stamp.tick,pair.a().stableKey(),pair.a().generation(),pair.b().stableKey(),pair.b().generation(),
                            a==null?"UNVERIFIED":a.localBlock(),a==null?"UNVERIFIED":a.fingerprint(),
                            b==null?"UNVERIFIED":b.localBlock(),b==null?"UNVERIFIED":b.fingerprint(),
                            a==null&&b==null?"TARGET_UNVERIFIED":"SOURCE_CALLBACK_LIVE_AUDIT",approach);
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-EPISODE] dimension={} tick={} bodyIds={}/{} records={} peak={} previousSeparated={} forceRise={} state={} reason={} creditCount={} Q={} shareA={} shareB={} unused={} source=POST_EFFECT_GAME_POLICY worldMutation=OFF",
                            stamp.dimension,stamp.tick,group.runtimePair().lowId(),group.runtimePair().highId(),
                            group.originalRecordCount(),group.forcePeak(),previousSeparated,
                            Double.isFinite(rise)?Double.toString(rise):"UNKNOWN",result.phase(),result.reason(),result.episode(),
                            result.issuedQ(),result.allocation().sideA(),result.allocation().sideB(),result.allocation().unused());
                } catch(RuntimeException unsupported) {
                    zero++;
                    if(lines++<MAX_LOG_PER_TICK)log.warn("[PDD-EPISODE] dimension={} tick={} Q=0 reason=INVALID_BRIDGE_INPUT type={} worldMutation=OFF",
                            stamp.dimension,stamp.tick,unsupported.getClass().getSimpleName());
                }
            }
        }
        // Bounded state update: missing native effects NEVER imply separation.
        // Only actual disjoint 3D world AABBs may rearm an already known pair.
        for(var entry:tracked.entrySet()) {
            Pair p=entry.getKey(); Tracked t=entry.getValue();
            if(contacted.contains(p) || t.lastProcessed==now) continue;
            Box a=box(server,p.a().stableKey(),t.a,p.dimension(),worldBounds,knownWorldBlocks);
            Box b=box(server,p.b().stableKey(),t.b,p.dimension(),worldBounds,knownWorldBlocks);
            boolean definitelySeparate=a!=null&&b!=null&&a.separatedFrom(b);
            t.previousA=a;t.previousB=b;t.lastProcessed=now;
            if(definitelySeparate) {
                t.lastPositiveSeparation=true;
                try {credit.step(new Frame(p,now,List.of(),true,false,t.a,t.b));}
                catch(RuntimeException ignored) { /* invalid snapshot: Q=0 */ }
            }
        }
        if(now%100==0) {
            credit.prune(now);
            tracked.entrySet().removeIf(e->now>e.getValue().lastSeen && now-e.getValue().lastSeen>600);
            log.info("[PDD-REALFIX] phase=SUMMARY observed={} credited={} zero={} ownerMissing={} targetMissing={} ambiguous={} trackedPairs={} nativeDropped={} nativeMalformed={} callbackDropped={} preHistoryDropped={} generation=IDS-1.0.5-A mutation=OFF",
                    observed,credited,zero,missingOwner,missingTarget,ambiguousOwners,tracked.size(),
                    NativeCollisionTelemetry.dropped(),NativeCollisionTelemetry.malformed(),
                    com.physicaldestroydeform.diagnostics.SableContactQueue.dropped(),SableFrameCapture.preHistoryDropped());
        }
    }

    private Map<SceneKey,ObservedTargets> callbackTargets(MinecraftServer server,List<RawObservation> callbacks) {
        Map<SceneKey,ObservedTargets> mapped=new HashMap<>();
        var cache=new HashMap<OwnerResolver.CacheKey,OwnerResolver.Check>();
        for(var c:callbacks) {
            if(!c.hasOther() || c.frameEvidence()==null)continue;
            var e=c.frameEvidence();
            if(e.hit()==null||e.other()==null||e.epoch()<0)continue;
            var result=OwnerResolver.audit(server,e,c.blockId(),cache);
            String oa=stable(e.hit()),ob=stable(e.other());
            if(oa==null||ob==null||oa.equals(ob))continue;
            SceneKey key=oa.compareTo(ob)<0?new SceneKey(e.dimension(),e.serverTick(),oa,ob)
                    :new SceneKey(e.dimension(),e.serverTick(),ob,oa);
            ObservedTargets set=mapped.computeIfAbsent(key,ignored->new ObservedTargets());
            if(result.hit().verified())set.add(oa,target(oa,result.hit().key()));
            if(result.other().verified())set.add(ob,target(ob,result.other().key()));
        }
        return mapped;
    }
    private static Target target(String owner,OwnerResolver.TargetKey k) {
        return new Target(owner,k.x()+","+k.y()+","+k.z(),k.fingerprint());
    }
    private static String stable(CollisionFrame.BlockTarget t) {
        if(t==null||!t.valid())return null;
        if(t.space()==CollisionFrame.Space.STATIC_WORLD)return "WORLD:"+t.dimension();
        if(t.space()==CollisionFrame.Space.SABLE_PLOT&&t.stablePlotId()!=null)return "PLOT:"+t.stablePlotId();
        return null;
    }
    private RuntimeOwners resolveOwners(Map<Stamp,OwnerSnapshot> ownerIndex,Stamp stamp,
                                         NativeEffectGroups.Group group) {
        OwnerSnapshot frame=ownerIndex.get(stamp);
        if(frame==null || !frame.complete)return null;
        String low=owner(frame,stamp.dimension(),group.runtimePair().lowId());
        String high=owner(frame,stamp.dimension(),group.runtimePair().highId());
        if(low==null||high==null||low.equals(high))return null;
        Owner a=new Owner(low,generation(stamp.dimension(),low,group.runtimePair().lowId()));
        Owner b=new Owner(high,generation(stamp.dimension(),high,group.runtimePair().highId()));
        return new RuntimeOwners(Pair.canonical(runId,stamp.dimension(),a,b));
    }
    private static String owner(OwnerSnapshot index,String dimension,int runtimeId) {
        if(runtimeId==-1)return "WORLD:"+dimension;
        String stable=index.bodies.get(runtimeId);
        return AMBIGUOUS_OWNER.equals(stable)?null:stable;
    }
    private long generation(String dimension,String owner,int runtimeId) {
        if(owner.startsWith("WORLD:"))return 1;
        String key=dimension+"|"+owner;
        Generation g=generations.computeIfAbsent(key,k->new Generation(runtimeId));
        if(g.runtimeId!=runtimeId){g.runtimeId=runtimeId;g.number++;}
        return g.number;
    }
    private static Map<String,Map<String,Box>> readServerBounds(MinecraftServer server) {
        Map<String,Map<String,Box>> result=new HashMap<>();
        for(ServerLevel level:server.getAllLevels()) {
            var container=SubLevelContainer.getContainer(level);
            if(container==null)continue;
            Map<String,Box> boxes=new HashMap<>();
            for(ServerSubLevel sub:container.getAllSubLevels()){
                if(sub==null||sub.isRemoved()||sub.getUniqueId()==null)continue;
                try {
                    var b=sub.boundingBox();
                    if(b!=null) boxes.put("PLOT:"+sub.getUniqueId(),new Box(
                            b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()));
                } catch(RuntimeException invalid) { /* fail closed */ }
            }
            result.put(level.dimension().location().toString(),boxes);
        }
        return result;
    }
    private static String probeWorldR4(MinecraftServer server,String dimension,Box moving,
                                       int[] remainingChecks) {
        var candidates=RealFixBoundaryCandidates.nearOverlap(moving,remainingChecks[0]);
        if(candidates.reason()!=RealFixBoundaryCandidates.Reason.CANDIDATES)
            return candidates.reason().name();
        int found=0;String sole="NONE";
        for(ServerLevel level:server.getAllLevels()) {
            if(!level.dimension().location().toString().equals(dimension))continue;
            var c=SubLevelContainer.getContainer(level);
            if(c==null)return "CONTAINER_MISSING";
            for(var block:candidates.blocks()) {
                if(--remainingChecks[0]<0)return "TICK_CANDIDATE_LIMIT";
                BlockPos pos=new BlockPos(block.x(),block.y(),block.z());
                try {
                    if(!level.hasChunkAt(pos))continue;
                    var chunk=new ChunkPos(pos);
                    if(c.inBounds(chunk)||c.getPlot(chunk)!=null)continue;
                    var state=level.getBlockState(pos);
                    if(state.isAir()||state.getCollisionShape(level,pos).isEmpty())continue;
                    found++;
                    if(found>1)return "TARGET_MULTI_UNRESOLVED";
                    sole=block.x()+","+block.y()+","+block.z()+":"+
                            BuiltInRegistries.BLOCK.getKey(state.getBlock());
                }catch(RuntimeException skipped){return "R4_READ_ERROR";}
            }
            return found==1?"PROVISIONAL_ONLY:"+sole:"TARGET_UNRESOLVED";
        }
        return "DIMENSION_MISSING";
    }

    private static Box box(MinecraftServer server,String owner,Target target,String dimension,
                           Map<String,Map<String,Box>> plotBounds,Map<String,Box> worldBlocks) {
        if(owner.startsWith("PLOT:"))return plotBounds.getOrDefault(dimension,Map.of()).get(owner);
        if(target==null)return null;
        String cacheKey=owner+"|"+target.localBlock()+"|"+target.fingerprint();
        if(worldBlocks.containsKey(cacheKey))return worldBlocks.get(cacheKey);
        try {
            String[] coords=target.localBlock().split(",",-1);
            if(coords.length!=3)return null;
            BlockPos pos=new BlockPos(Integer.parseInt(coords[0]),Integer.parseInt(coords[1]),Integer.parseInt(coords[2]));
            for(ServerLevel level:server.getAllLevels()) {
                if(!level.dimension().location().toString().equals(dimension))continue;
                if(!level.hasChunkAt(pos))return null; // never force load
                var sublevels=SubLevelContainer.getContainer(level);
                if(sublevels==null || sublevels.inBounds(new ChunkPos(pos))
                        || sublevels.getPlot(new ChunkPos(pos))!=null)return null;
                var state=level.getBlockState(pos);
                String fingerprint=BuiltInRegistries.BLOCK.getKey(state.getBlock())+"|"+state;
                if(!fingerprint.equals(target.fingerprint()))return null;
                Box b=Box.block(pos.getX(),pos.getY(),pos.getZ());
                worldBlocks.put(cacheKey,b);
                return b;
            }
        }catch(RuntimeException invalid){return null;}
        return null;
    }
}

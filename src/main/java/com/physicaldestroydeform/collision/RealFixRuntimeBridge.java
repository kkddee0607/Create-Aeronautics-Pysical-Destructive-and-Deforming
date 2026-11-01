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
 * Source owners: Sable effect-phase activeSubLevels snapshots, cross-audited by pre-physics history.
 * Source targets: live block/shape audits of source-phase Sable-consumer R2/R3 candidates.
 * Source separation: positive gap in actual Sable world-space sublevel AABBs.
 *
 * The exact Sable consumer convention is not a general collider-pose calibration;
 * R4 is never promoted to verified target by guessing. Incomplete evidence yields Q=0.
 */
public final class RealFixRuntimeBridge {
    private static final int MAX_TRACKED=4096;
    private static final int MAX_LOG_PER_TICK=64;
    private final String runId=UUID.randomUUID().toString();
    private final GameImpactCredit credit=new GameImpactCredit(Settings.fromSystemProperties());
    private final Map<Pair,Tracked> tracked=new HashMap<>();
    private final Map<String,Generation> generations=new HashMap<>();
    private long credited,observed,zero,missingOwner,ambiguousOwners,missingTarget;
    private long r2Verified, r2Rejected, r3Verified, prearmed, r1Fallback;
    private long lastDropped,lastMalformed,lastCallbackDropped,lastPreHistoryDropped;
    private static final class Tracked {
        Target a,b;
        Box previousA,previousB;
        long lastSeen=-1;
        long lastProcessed=-1;
        boolean lastPositiveSeparation;
        long lastSeparatedTick=-1;
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
            var contactsByPair=new HashMap<NativeEffectGroups.RuntimePair,List<NativeCollisionTelemetry.Contact>>();
            for(var c:values) {
                if(c.idA()< -1 || c.idB()< -1 || c.idA()==c.idB())continue;
                contactsByPair.computeIfAbsent(NativeEffectGroups.RuntimePair.of(c.idA(),c.idB()),
                        ignored->new ArrayList<>()).add(c);
            }
            for(var group:batch.groups()) {
                observed++;
                if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-OBS] source=POST_EFFECT dimension={} tick={} bodyIds={}/{} rawRecords={} peakForce={} discarded={} invalid={} mutation=OFF",
                        stamp.dimension,stamp.tick,group.runtimePair().lowId(),group.runtimePair().highId(),
                        group.originalRecordCount(),group.forcePeak(),batch.discardedRecords(),batch.invalidRecords());
                List<NativeCollisionTelemetry.Contact> records=contactsByPair.getOrDefault(group.runtimePair(),List.of());
                RuntimeOwners owners=resolveOwners(ownerIndex,stamp,group,records);
                if(owners==null) {
                    missingOwner++;zero++;
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-OBS] source=POST_EFFECT dimension={} tick={} ids={}/{} peak={} result=OWNER_UNVERIFIED mutation=OFF",
                            stamp.dimension,stamp.tick,group.runtimePair().lowId(),group.runtimePair().highId(),group.forcePeak());
                    continue;
                }
                Pair pair=owners.pair();
                SceneKey scene=new SceneKey(stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey());
                ObservedTargets candidate=targetEvidence.get(scene);
                // Native records carry Sable-consumer-phase owner pose. Read the actual
                // block from THAT owner's allocated plot grid, never from world XYZ.
                var r2a=SableConsumerTargets.resolve(server,stamp.dimension,pair.a().stableKey(),records);
                var r2b=SableConsumerTargets.resolve(server,stamp.dimension,pair.b().stableKey(),records);
                // R3 is permitted only when the independently transformed
                // two-sided native points coincide within a bounded tolerance.
                var r3a=r2a.verified()?null:SableConsumerTargets.crossProject(
                        server,stamp.dimension,pair.a().stableKey(),records);
                var r3b=r2b.verified()?null:SableConsumerTargets.crossProject(
                        server,stamp.dimension,pair.b().stableKey(),records);
                Target a=r2a.verified()?r2a.target():r3a!=null && r3a.verified()?r3a.target():null;
                Target b=r2b.verified()?r2b.target():r3b!=null && r3b.verified()?r3b.target():null;
                if(r2a.verified())r2Verified++;else r2Rejected++;
                if(r2b.verified())r2Verified++;else r2Rejected++;
                if(r3a!=null && r3a.verified())r3Verified++;
                if(r3b!=null && r3b.verified())r3Verified++;
                String methodA=r2a.verified()?r2a.stage():r3a!=null&&r3a.verified()?r3a.stage():"UNVERIFIED";
                String methodB=r2b.verified()?r2b.stage():r3b!=null&&r3b.verified()?r3b.stage():"UNVERIFIED";
                // An existing non-null callback may identify a block owner, but
                // it is NOT asserted to be the same native effect/manifold.
                // Restrict impact credit to Sable's native source-correlated sides.
                Target r1a=candidate==null?null:candidate.unique(pair.a().stableKey());
                Target r1b=candidate==null?null:candidate.unique(pair.b().stableKey());
                if(candidate!=null && (candidate.ambiguous(pair.a().stableKey())||candidate.ambiguous(pair.b().stableKey()))) {
                    ambiguousOwners++;r1a=null;r1b=null;
                }
                if(a==null && r1a!=null)r1Fallback++;
                if(b==null && r1b!=null)r1Fallback++;
                Tracked t=tracked.get(pair);
                if(t==null && tracked.size()<MAX_TRACKED) {
                    t=new Tracked();tracked.put(pair,t);
                }
                if(t==null){zero++;continue;}
                if(a!=null)t.a=a;
                if(b!=null)t.b=b;
                Box boxA=box(server,pair.a().stableKey(),a==null?t.a:a,stamp.dimension,worldBounds,knownWorldBlocks);
                Box boxB=box(server,pair.b().stableKey(),b==null?t.b:b,stamp.dimension,worldBounds,knownWorldBlocks);
                boolean previousSeparated=t.lastPositiveSeparation
                        && t.lastSeparatedTick>=0 && now-t.lastSeparatedTick<=2;
                boolean approach=RealFixBoundsEvidence.approaching(t.previousA,t.previousB,boxA,boxB)
                        || previousSeparated; // recent verified clearance + native impact
                double rise=Double.isFinite(t.lastPeak)?group.forcePeak()-t.lastPeak:Double.NaN;
                t.lastPeak=group.forcePeak();
                t.lastPositiveSeparation=false;
                t.previousA=boxA;t.previousB=boxB;
                t.lastSeen=now;t.lastProcessed=now;
                contacted.add(pair);
                if(a==null&&b==null)missingTarget++;
                if(lines++<MAX_LOG_PER_TICK) log.info("[PDD-NATIVE-TARGET] dim={} tick={} ownerA={} ownerB={} methodA={} reasonA={} checkedA={} methodB={} reasonB={} checkedB={} r3A={} r3B={} callbackHintA={} callbackHintB={} worldMutation=OFF",
                        stamp.dimension,stamp.tick,pair.a().stableKey(),pair.b().stableKey(),
                        methodA,r2a.reason(),r2a.checked(),methodB,r2b.reason(),r2b.checked(),
                        r3a==null?"SKIPPED":r3a.reason(),r3b==null?"SKIPPED":r3b.reason(),
                        r1a!=null,r1b!=null);
                // R4 shadow audit. This only describes plausible loaded world
                // blocks; WITHOUT in-game ground truth it can never mint credit.
                if(pair.a().stableKey().startsWith("WORLD:") && a==null) {
                    String status=probeWorldR4(server,stamp.dimension,boxB,records,remainingR4Checks);
                    if(lines++<MAX_LOG_PER_TICK)log.info("[PDD-TARGET] source=R4_SWEPT_PROVISIONAL dimension={} tick={} worldCandidate={} credited=false",stamp.dimension,stamp.tick,status);
                }
                if(pair.b().stableKey().startsWith("WORLD:") && b==null) {
                    String status=probeWorldR4(server,stamp.dimension,boxA,records,remainingR4Checks);
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
                    // requires independently observed prior clearance and a source-
                    // mapped, verified owner-local block. Force is a GAME proxy.
                    var frame=new Frame(pair,now,group.samples(),false,approach,a,b,group.forcePeak());
                    var result=credit.step(frame);
                    if(result.credited()) credited++; else zero++;
                    if(lines++<MAX_LOG_PER_TICK) log.info("[PDD-TARGET] dimension={} tick={} ownerA={} ownerAGeneration={} ownerB={} ownerBGeneration={} targetA={} fingerprintA={} targetB={} fingerprintB={} frameMethodA={} frameMethodB={} shapeAudit=OWNER_SHAPE_CHECKED quality={} reason={} approachByAabb={} worldMutation=OFF",
                            stamp.dimension,stamp.tick,pair.a().stableKey(),pair.a().generation(),pair.b().stableKey(),pair.b().generation(),
                            a==null?"UNVERIFIED":a.localBlock(),a==null?"UNVERIFIED":a.fingerprint(),
                            b==null?"UNVERIFIED":b.localBlock(),b==null?"UNVERIFIED":b.fingerprint(),
                            methodA,methodB,
                            a==null&&b==null?"SOURCE_UNVERIFIED":"POST_EFFECT_CORROBORATED_GAME_PROXY",
                            a==null&&b==null?"TARGET_UNVERIFIED":"SABLE_NATIVE_CONSUMER_MAPPED",approach);
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
                t.lastSeparatedTick=now;
                try {credit.step(new Frame(p,now,List.of(),true,false,t.a,t.b));}
                catch(RuntimeException ignored) { /* invalid snapshot: Q=0 */ }
            }
        }
        // Pre-contact state construction (§22). Silence alone never proves
        // separation: plot-plot uses real Sable world AABBs; plot-world uses a
        // complete, loaded, bounded world-collider clearance inspection.
        prearmIndependentPairs(server,now,worldBounds,contacted,remainingR4Checks);
        if(now%100==0) {
            credit.prune(now);
            tracked.entrySet().removeIf(e->now>e.getValue().lastSeen && now-e.getValue().lastSeen>600);
            log.info("[PDD-SUPERFIX] phase=SUMMARY observed={} credited={} zero={} ownerMissing={} targetMissing={} ambiguous={} trackedPairs={} r2Verified={} r2Rejected={} r3Verified={} prearmed={} r1Fallback={} observerErrors={} nativeDropped={} nativeMalformed={} callbackDropped={} preHistoryDropped={} generation=IDS-1.0.5-A mutation=OFF",
                    observed,credited,zero,missingOwner,missingTarget,ambiguousOwners,tracked.size(),
                    r2Verified,r2Rejected,r3Verified,prearmed,r1Fallback,NativeCollisionTelemetry.observerErrors(),
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
    /** Ownership comes from exact-phase Sable activeSubLevels, not approximate
     * AABB proximity. Pre-history is a cross-check when complete, not a substitute. */
    private RuntimeOwners resolveOwners(Map<Stamp,OwnerSnapshot> ownerIndex,Stamp stamp,
                                         NativeEffectGroups.Group group,
                                         List<NativeCollisionTelemetry.Contact> contacts) {
        if(contacts.isEmpty())return null;
        String low=null,high=null;
        int idLo=group.runtimePair().lowId(),idHi=group.runtimePair().highId();
        for(var contact:contacts) {
            String la=sourceOwner(stamp.dimension(),contact.idA(),contact.frameA());
            String lb=sourceOwner(stamp.dimension(),contact.idB(),contact.frameB());
            if(la==null || lb==null || la.equals(lb))return null;
            String currentLow=contact.idA()==idLo?la:lb;
            String currentHigh=contact.idA()==idHi?la:lb;
            if(low!=null && !low.equals(currentLow))return null;
            if(high!=null && !high.equals(currentHigh))return null;
            low=currentLow;high=currentHigh;
        }
        OwnerSnapshot pre=ownerIndex.get(stamp);
        if(pre!=null && pre.complete) {
            String preLow=owner(pre,stamp.dimension(),idLo);
            String preHigh=owner(pre,stamp.dimension(),idHi);
            if(preLow!=null && !preLow.equals(low) || preHigh!=null && !preHigh.equals(high))return null;
        }
        Owner a=new Owner(low,generation(stamp.dimension(),low,idLo));
        Owner b=new Owner(high,generation(stamp.dimension(),high,idHi));
        return new RuntimeOwners(Pair.canonical(runId,stamp.dimension(),a,b));
    }
    private static String sourceOwner(String dimension,int id,
                                      NativeCollisionTelemetry.OwnerFrame frame) {
        if(id==-1)return "WORLD:"+dimension;
        return frame==null?null:"PLOT:"+frame.uuid();
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
    /** Bounded verified precontact clearance. Will not load a chunk or scan a big ship. */
    private void prearmIndependentPairs(MinecraftServer server,long now,
                                       Map<String,Map<String,Box>> bounds,Set<Pair> contacted,
                                       int[] remaining) {
        int attempted=0;
        for(var dimEntry:bounds.entrySet()) {
            String dim=dimEntry.getKey();
            var entries=new ArrayList<>(dimEntry.getValue().entrySet());
            entries.sort(Map.Entry.comparingByKey());
            for(int i=0;i<entries.size() && attempted<128;i++) {
                var first=entries.get(i);Box firstBox=first.getValue();
                if(firstBox==null)continue;
                for(int j=i+1;j<entries.size() && attempted<128;j++) {
                    var second=entries.get(j);Box secondBox=second.getValue();
                    if(secondBox==null)continue;
                    attempted++;
                    if(firstBox.separatedFrom(secondBox)) {
                        Pair pair=Pair.canonical(runId,dim,
                                new Owner(first.getKey(),currentGeneration(dim,first.getKey())),
                                new Owner(second.getKey(),currentGeneration(dim,second.getKey())));
                        seedSeparation(pair,now,contacted,first.getKey(),firstBox,secondBox);
                    }
                }
                if(attempted>=128)break;
                attempted++;
                if(confirmedWorldClearance(server,dim,firstBox,remaining)) {
                    Pair pair=Pair.canonical(runId,dim,new Owner("WORLD:"+dim,1),
                            new Owner(first.getKey(),currentGeneration(dim,first.getKey())));
                    seedSeparation(pair,now,contacted,"WORLD:"+dim,null,firstBox);
                }
            }
        }
    }
    private long currentGeneration(String dimension,String owner) {
        Generation g=generations.get(dimension+"|"+owner);
        return g==null?1:g.number;
    }
    /** Pair.canonical orders by stable owner, NOT by native ID or iteration order. */
    static Box[] alignBounds(Pair pair,String firstOwner,Box first,Box second) {
        if(pair==null || firstOwner==null)return null;
        if(pair.a().stableKey().equals(firstOwner))return new Box[]{first,second};
        if(pair.b().stableKey().equals(firstOwner))return new Box[]{second,first};
        return null;
    }
    private void seedSeparation(Pair pair,long now,Set<Pair> contacted,
                                String firstOwner,Box first,Box second) {
        if(contacted.contains(pair) || tracked.size()>=MAX_TRACKED && !tracked.containsKey(pair))return;
        Box[] ordered=alignBounds(pair,firstOwner,first,second);
        if(ordered==null)return;
        Tracked t=tracked.computeIfAbsent(pair,k->new Tracked());
        try {
            var d=credit.step(new Frame(pair,now,List.of(),true,false,null,null));
            if(d.reason()==GameImpactCredit.Reason.STALE_FRAME)return;
            t.lastPositiveSeparation=true;
            t.lastSeparatedTick=now;
            t.lastProcessed=now;
            t.previousA=ordered[0];t.previousB=ordered[1];
            prearmed++;
        } catch(RuntimeException invalid){ /* fail closed */ }
    }
    private static boolean confirmedWorldClearance(MinecraftServer server,String dim,Box box,
                                                   int[] remaining) {
        // This is a finite empty-region proof only; returning false proves nothing.
        var candidates=RealFixBoundaryCandidates.nearOverlap(new Box(
                box.minX()-0.25,box.minY()-0.25,box.minZ()-0.25,
                box.maxX()+0.25,box.maxY()+0.25,box.maxZ()+0.25),remaining[0]);
        if(candidates.reason()!=RealFixBoundaryCandidates.Reason.CANDIDATES)return false;
        for(ServerLevel level:server.getAllLevels()) {
            if(!level.dimension().location().toString().equals(dim))continue;
            var container=SubLevelContainer.getContainer(level);
            if(container==null)return false;
            for(var block:candidates.blocks()) {
                if(--remaining[0]<0)return false;
                BlockPos pos=new BlockPos(block.x(),block.y(),block.z());
                if(!level.hasChunkAt(pos))return false;
                try {
                    var chunk=new ChunkPos(pos);
                    if(container.inBounds(chunk)||container.getPlot(chunk)!=null)return false;
                    var state=level.getBlockState(pos);
                    if(!state.isAir() && !state.getCollisionShape(level,pos).isEmpty())return false;
                } catch(RuntimeException invalid) {return false;}
            }
            return true;
        }
        return false;
    }
    private static String probeWorldR4(MinecraftServer server,String dimension,Box moving,
                                       List<NativeCollisionTelemetry.Contact> records,
                                       int[] remainingChecks) {
        // World-side native point is in the coordinate convention Sable itself
        // uses for static BlockPos. Do not sample arbitrary ship AABB faces.
        if(records.isEmpty())return "NATIVE_WORLD_POINT_MISSING";
        RealFixBoundaryCandidates.Result candidates=null;
        for(var c:records) {
            double x,y,z;
            if(c.idA()==-1) {x=c.contactAX();y=c.contactAY();z=c.contactAZ();}
            else if(c.idB()==-1) {x=c.contactBX();y=c.contactBY();z=c.contactBZ();}
            else return "NOT_WORLD_COLLIDER";
            var r=RealFixBoundaryCandidates.nearPlanePoint(moving,x,y,z,remainingChecks[0]);
            if(r.reason()!=RealFixBoundaryCandidates.Reason.CANDIDATES)return r.reason().name();
            if(candidates!=null && !candidates.blocks().equals(r.blocks()))return "MULTIPLE_CONTACT_REGIONS";
            candidates=r;
        }
        if(candidates==null)return "NO_WORLD_POINT";
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

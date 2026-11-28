package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.OwnerFrame;
import com.physicaldestroydeform.diagnostics.RogTrace;
import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.AABB;
import java.util.*;
import java.util.function.Predicate;
import static com.physicaldestroydeform.collision.GameImpactCredit.*;
import static com.physicaldestroydeform.collision.FrameMath.*;

/** Same-capture regional authority. Geometry/credits remain in the existing
 * R2, F ledger and safe commit paths. No native callback or solver changes. */
final class DcrtContactBatch {
    private static final int MAX_GAP_BOXES=128,MAX_FOOTPRINT=128;
    private record NormalKey(int a,int b,double ax,double ay,double az,double bx,double by,double bz,
                             OwnerFrame fa,OwnerFrame fb) {
        static NormalKey of(Contact c){return new NormalKey(c.idA(),c.idB(),c.ax(),c.ay(),c.az(),c.bx(),c.by(),c.bz(),c.frameA(),c.frameB());}
    }
    private record Candidate(List<DirectionalContactRegions.Track> tracks,List<Contact> records,
                             NativeEffectDirection.Result normal,double closing) {}
    private record Part(Candidate candidate,TargetDirections directions,SableContactRegions.Prepared prepared,
                        SableContactRegions.Result authorized) {}
    private final List<Part> parts;
    private DirectionalContactRegions pendingHistory;
    private long pendingTick=-1;
    private final List<Contact> records;
    private final SableContactRegions.Result direct;
    private final TargetDirections directions=new TargetDirections();
    private final boolean secondarySafe;
    private final int observed,excluded,unknown;
    private final double excludedPeak;
    private Map<Target,ForcePropagation.Orientation> seedDirections=Map.of();
    private SableContactRegions.Result expanded;
    private DcrtContactBatch(List<Part> parts,boolean secondarySafe,int observed,int excluded,int unknown,double excludedPeak){
        this.parts=List.copyOf(parts);this.secondarySafe=secondarySafe;this.observed=observed;this.excluded=excluded;this.unknown=unknown;this.excludedPeak=excludedPeak;
        var list=new ArrayList<Contact>();var results=new ArrayList<SableContactRegions.Result>();
        for(var part:parts){list.addAll(part.candidate.records);results.add(part.authorized);}
        records=List.copyOf(list);direct=combine(results);
    }
    boolean authorized(){return !parts.isEmpty()&&direct.verified();}
    boolean secondarySafe(){return secondarySafe;}
    void reserve(DirectionalContactRegions history,long tick){
        if(pendingHistory!=null)throw new IllegalStateException("batch already reserved");
        // Register before the loop so a partial reservation is also resolved by the caller.
        pendingHistory=history;pendingTick=tick;
        for(var part:parts)for(var track:part.candidate.tracks)history.reserve(track);
    }
    void finish(boolean sealed){
        if(pendingHistory==null)return;
        for(var part:parts)for(var track:part.candidate.tracks)pendingHistory.finish(track,pendingTick,sealed);
        pendingHistory=null;pendingTick=-1;
    }
    void quarantine(){
        if(pendingHistory==null)return;
        for(var part:parts)for(var track:part.candidate.tracks){pendingHistory.finish(track,pendingTick,false);pendingHistory.unknown(track,pendingTick);}
        pendingHistory=null;pendingTick=-1;
    }
    List<Contact> records(){return records;}
    SableContactRegions.Result direct(){return direct;}
    TargetDirections directions(){return directions;}
    Map<Target,ForcePropagation.Orientation> seedDirections(){return seedDirections;}
    NativeEffectDirection.Result normal(){return parts.size()==1?parts.getFirst().candidate.normal:NativeEffectDirection.unknown(parts.isEmpty()?"DCRT_NO_APPROVAL":"DCRT_MULTIPLE_REGION_DIRECTIONS");}
    double peak(){return records.stream().mapToDouble(Contact::rawImpactScalar).max().orElse(0);}
    List<ObservedForce> forces(){
        var out=new ArrayList<ObservedForce>(records.size());int i=0;
        for(var c:records)out.add(new ObservedForce(c.idA(),c.idB(),c.rawImpactScalar(),i++));
        return out;
    }
    SableContactRegions.Result expand(Pair pair){
        if(expanded!=null)return expanded;
        var results=new ArrayList<SableContactRegions.Result>();var orientations=new HashMap<Target,ForcePropagation.Orientation>();
        for(var part:parts){
            var result=part.prepared.expand();
            results.add(result);directions.mergeFrom(part.directions);
            for(var r:result.regions()){
                if(r.a()!=null)mergeDirection(orientations,r.a(),part.candidate.normal.forOwner(pair.a().stableKey()));
                if(r.b()!=null)mergeDirection(orientations,r.b(),part.candidate.normal.forOwner(pair.b().stableKey()));
            }
        }
        orientations.forEach((target,n)->{if("DCRT_CONFLICT".equals(n.evidence()))directions.denyMixedSeed(target);});
        expanded=combine(results);
        var seeds=new HashSet<Target>();for(var r:expanded.regions()){if(r.a()!=null)seeds.add(r.a());if(r.b()!=null)seeds.add(r.b());}
        orientations.keySet().retainAll(seeds);seedDirections=Map.copyOf(orientations);
        return expanded;
    }
    void inputLog(long episode,double peak){
        if(RogTrace.eventSlot(Stage.R06,"DCRT_AUTHORIZED_INPUT"))RogTrace.detail(Stage.R06,"DCRT_AUTHORIZED_INPUT",
                "state=AUTHORIZED_INPUT actualGrant=NOT_IMPLIED episode={} eligibleRegions={} eligibleRecords={} excludedRecords={} unknownRecords={} excludedPeak={} eventF={} sideF={} seedDirections={} rawSubstep=UNKNOWN",
                episode,parts.size(),records.size(),excluded,unknown,excludedPeak,peak,peak/2,seedDirections.size());
    }
    private static void mergeDirection(Map<Target,ForcePropagation.Orientation> out,Target target,ForcePropagation.Orientation normal){
        out.merge(target,normal,(a,b)->a.verified()&&b.verified()&&a.inward().dot(b.inward())>1-1e-8?a:new ForcePropagation.Orientation(null,"DCRT_CONFLICT"));
    }

    private static final class Route {
        DirectionalContactRegions.Track track;
        final List<Contact> records=new ArrayList<>();
        final List<DcrtSpatialFootprint.Sample> samples=new ArrayList<>();
        final List<DirectionalContactRegions.Track> prior;
        boolean unknown,withhold;
        Route(DirectionalContactRegions.Track track,List<DirectionalContactRegions.Track> prior){this.track=track;this.prior=prior;}
        void add(Contact c,DcrtSpatialFootprint.Sample s){records.add(c);samples.add(s);}
    }
    private record Cluster(DirectionalContactRegions.Direction direction,List<Route> routes) {}
    private static DcrtContactBatch incomplete(DirectionalContactRegions history,long now,List<Contact> records){
        history.incomplete(now);return new DcrtContactBatch(List.of(),false,0,records.size(),records.size(),peakOf(records));
    }
    static DcrtContactBatch select(MinecraftServer server,Pair pair,List<Contact> records,List<CollisionFrame.Snapshot> pre,
                                   long tick,long now,boolean complete,boolean fullPairArmed,Settings settings,
                                   DirectionalContactRegions history,Predicate<DirectionalContactRegions.Track> localGap){
        boolean nativeCap=records.stream().anyMatch(Contact::capSaturated);
        if(!complete||records.isEmpty()||records.size()>DirectionalContactRegions.MAX_WITNESSES||nativeCap){
            if(RogTrace.periodic(Stage.R05,"DCRT_BUDGET"))RogTrace.detail(Stage.R05,"DCRT_BUDGET","records={} complete={} nativeCap={} reason=UNKNOWN_SOURCE_NO_REARM",records.size(),complete,nativeCap);
            return incomplete(history,now,records);
        }
        var proofs=new HashMap<NormalKey,NativeEffectDirection.Result>();
        var samples=new ArrayList<DcrtSpatialFootprint.Sample>(records.size());
        var normals=new ArrayList<DirectionalContactRegions.Direction>(records.size());
        for(var c:records){
            var n=proofs.computeIfAbsent(NormalKey.of(c),k->NativeEffectDirection.verify(pair,List.of(c),pre));
            var sample=DcrtSpatialFootprint.Sample.of(pair,c);
            if(n.owners().size()!=2||sample==null)return incomplete(history,now,records);
            samples.add(sample);normals.add(new DirectionalContactRegions.Direction(n.forOwner(pair.a().stableKey()).inward(),n.forOwner(pair.b().stableKey()).inward()));
        }
        var groups=DirectionalContactRegions.cluster(normals);
        if(groups.isEmpty())return incomplete(history,now,records);
        if(fullPairArmed)history.resetForFullPair();
        var previous=List.copyOf(history.tracks());
        var clusters=new ArrayList<Cluster>();
        var used=new HashMap<DirectionalContactRegions.Track,Route>();
        var spatialWork=new DcrtSpatialFootprint.Work();
        boolean anyUnknown=false;
        // Direction is a routing key, not authority. Match original witnesses
        // against all compatible old surfaces before inspecting a new target.
        for(var indices:groups){
            var group=indices.stream().map(records::get).toList();
            var normal=NativeEffectDirection.verify(pair,group,pre);
            if(normal.owners().size()!=2)return incomplete(history,now,records);
            var direction=new DirectionalContactRegions.Direction(normal.forOwner(pair.a().stableKey()).inward(),normal.forOwner(pair.b().stableKey()).inward());
            var prior=previous.stream().filter(t->history.directionMatches(t,direction)).toList();
            var matched=new LinkedHashMap<DirectionalContactRegions.Track,Route>();
            var fresh=new Route(null,prior);
            for(int i:indices){
                var sample=samples.get(i);var possible=new ArrayList<DirectionalContactRegions.Track>();
                for(var t:prior){
                    var relation=spatialWork.relation(t.surface,t.probe,sample);
                    // Unverified/old footprint is not proof of disjointness.
                    if(relation!=DcrtSpatialFootprint.Relation.DISJOINT||t.lastSeen!=now-1&&t.phase!=DirectionalContactRegions.Phase.ARMED)possible.add(t);
                }
                if(spatialWork.exhausted())return incomplete(history,now,records);
                if(possible.size()>1){
                    anyUnknown=true;
                    for(var t:possible){t.ambiguousTick=now;history.unknown(t,now);}
                    continue;
                }
                if(possible.isEmpty())fresh.add(records.get(i),sample);
                else matched.computeIfAbsent(possible.getFirst(),t->new Route(t,List.of())).add(records.get(i),sample);
            }
            var routes=new ArrayList<>(matched.values());
            for(var r:routes){
                if(used.putIfAbsent(r.track,r)!=null||!history.touch(r.track,now,fullPairArmed)){
                    r.track.ambiguousTick=now;history.unknown(r.track,now);anyUnknown=true;
                }
                if(!footprintStillCompatible(r.track,pair,r.records))r.track.footprint=null;
            }
            if(!fresh.records.isEmpty()){
                // Provisional components can only split authority after actual
                // shape proof. Sparse anchors of one surface remain together.
                var components=DcrtSpatialFootprint.components(fresh.samples);
                if(components.isEmpty())return incomplete(history,now,records);
                if(components.size()>DirectionalContactRegions.MAX_TRACKS)routes.add(fresh);
                else for(var component:components){var r=new Route(null,prior);for(int i:component)r.add(fresh.records.get(i),fresh.samples.get(i));routes.add(r);}
            }
            clusters.add(new Cluster(direction,routes));
        }
        var closing=new HashMap<NormalKey,Double>();
        var eligibleApproach=new HashSet<Contact>();
        for(var c:records){var key=NormalKey.of(c);double v=closing.computeIfAbsent(key,k->NativeApproachEvidence.earliestClosing(pair,pre,proofs.get(k),tick));if(NativeApproachEvidence.regionApproach(v))eligibleApproach.add(c);}
        // Audit once per direction, not once per provisional component. All
        // restricted views and separation probes share this charged cache.
        int auditCount=0;
        for(var cluster:clusters)if(needsAudit(cluster,history,eligibleApproach,settings,fullPairArmed,now))auditCount++;
        int budget=SableContactRegions.RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE/Math.max(1,auditCount);
        var accepted=new ArrayList<Part>();var admitted=new HashSet<Contact>();boolean restingOnly=true;
        for(var cluster:clusters){
            var routes=cluster.routes;
            boolean audit=needsAudit(cluster,history,eligibleApproach,settings,fullPairArmed,now);
            SableContactRegions.Prepared prepared=null;TargetDirections auditDirections=null;
            var auditRecords=new ArrayList<Contact>();
            if(audit){
                for(var r:routes)if(r.track==null||r.track.ambiguousTick!=now) auditRecords.addAll(r.records);
                if(!auditRecords.isEmpty()){
                    auditDirections=TargetDirections.forEvent(pair,auditRecords,pre,tick,true);
                    prepared=SableContactRegions.prepare(server,pair.dimension(),pair,auditRecords,auditDirections,budget);
                }
            }
            var fresh=new ArrayList<Route>();
            for(var r:routes){
                if(r.track!=null)continue;
                var face=prepared==null?null:surface(pair,r.records,prepared.auditBatch(),spatialWork);
                // New same-direction region needs actual bilateral disconnection
                // from old, still valid shapes. Unknown is never a new track.
                boolean independent=true;
                for(var old:r.prior){
                    if(face==null||old.surface==null||!prepared.revalidateSpatial(old.surface,budget)||!prepared.disconnected(old.surface,face,budget,spatialWork)){independent=false;break;}
                }
                if(!independent){
                    anyUnknown=true;
                    if(r.prior.size()==1&&r.prior.getFirst().ambiguousTick!=now){
                        var old=r.prior.getFirst();var route=used.get(old);
                        if(route==null&&history.touch(old,now,false)){
                            r.track=old;r.withhold=true;used.put(old,r);
                        }else if(route!=null&&routes.contains(route)){
                            route.records.addAll(r.records);route.samples.addAll(r.samples);route.withhold=true;r.unknown=true;
                        }else{r.unknown=true;old.ambiguousTick=now;history.unknown(old,now);}
                    }else{r.unknown=true;for(var old:r.prior){old.ambiguousTick=now;history.unknown(old,now);}}
                    continue;
                }
                // Merge raw fragments unless actual bilateral shapes prove
                // disconnection. This preserves wide, sparsely sampled patches.
                var joins=new ArrayList<Route>();
                for(var other:fresh){
                    var previousFace=surface(pair,other.records,prepared.auditBatch(),spatialWork);
                    if(face==null||previousFace==null||!prepared.disconnected(previousFace,face,budget,spatialWork))joins.add(other);
                }
                if(joins.isEmpty())fresh.add(r);
                else{
                    var merge=joins.getFirst();merge.records.addAll(r.records);merge.samples.addAll(r.samples);r.unknown=true;
                    for(int i=1;i<joins.size();i++){var other=joins.get(i);merge.records.addAll(other.records);merge.samples.addAll(other.samples);other.unknown=true;fresh.remove(other);}
                }
            }
            if(spatialWork.exhausted())return incomplete(history,now,records);
            for(var r:fresh){
                r.track=history.newTrack(cluster.direction,now,fullPairArmed);
                if(r.track==null)return incomplete(history,now,records);
            }
            var eligible=new ArrayList<Contact>();var tracks=new ArrayList<DirectionalContactRegions.Track>();
            for(var r:routes){
                var track=r.track;
                if(r.unknown||track==null||track.ambiguousTick==now){anyUnknown|=track==null&&!r.unknown||track!=null&&track.ambiguousTick==now;restingOnly=false;continue;}
                var probe=DcrtSpatialFootprint.Probe.of(r.samples);
                if(prepared!=null){track.surface=surface(pair,r.records,prepared.auditBatch(),spatialWork);track.probe=probe;}
                else if(track.surface==null){track.probe=probe;}
                // Keep the old pose together with its verified surface; do
                // not replace only the pose using unaudited sliding samples.
                if(!track.observed||track.phase!=DirectionalContactRegions.Phase.ACTIVE_SPENT&&track.phase!=DirectionalContactRegions.Phase.OBSERVED_UNSPENT)restingOnly=false;
                var approaching=r.records.stream().filter(eligibleApproach::contains).toList();
                boolean candidate=!r.withhold&&prepared!=null&&history.eligible(track,true,fullPairArmed)&&!approaching.isEmpty()&&settings.candidate(peakOf(approaching))>=1;
                List<Contact> proved=List.of();boolean retryable=false;
                if(candidate){
                    restingOnly=false;
                    var proof=fullPairArmed?new GapProof(approaching,false):provedRecords(pair,approaching,pre,proofs,prepared);
                    proved=proof.records;retryable=proof.retryable;
                    if(settings.candidate(peakOf(proved))<1)proved=List.of();
                    if(!proved.isEmpty()){eligible.addAll(proved);tracks.add(track);}
                    if(!proved.isEmpty()?RogTrace.eventSlot(Stage.R05,"DCRT_APPROACH"):RogTrace.periodic(Stage.R05,"DCRT_APPROACH_REJECTED"))RogTrace.detail(Stage.R05,proved.isEmpty()?"DCRT_APPROACH_REJECTED":"DCRT_APPROACH","region={} positiveGap={} fullPairArmed={} directStatus={} eligibleRecords={} observedRecords={} budgetPerSide={}",track.id,!proved.isEmpty(),fullPairArmed,prepared.direct.status(),proved.size(),r.records.size(),budget);
                }
                // Only a proved but not yet sealed candidate may retry. A
                // fresh unspent region may also get a later independent proof.
                history.observed(track,!proved.isEmpty()||retryable);
            }
            if(!eligible.isEmpty()){
                var normal=NativeEffectDirection.verify(pair,eligible,pre);
                boolean unchanged=!prepared.extraReads()&&eligible.equals(auditRecords);
                var td=unchanged?auditDirections:TargetDirections.forEvent(pair,eligible,pre,tick,true);
                var selected=unchanged?prepared:prepared.restrict(server,pair,eligible,td,budget);
                var audited=selected.direct;
                if(fullPairArmed&&!audited.verified())audited=selected.exact();
                if(audited.verified()&&normal.owners().size()==2){
                    for(var track:tracks){
                        var region=routes.stream().filter(r->r.track==track).findFirst().orElseThrow();
                        var own=region.records.stream().filter(eligible::contains).toList();
                        track.footprint=footprint(pair,own,normal,selected.direct);
                    }
                    accepted.add(new Part(new Candidate(List.copyOf(tracks),List.copyOf(eligible),normal,NativeApproachEvidence.earliestClosing(pair,pre,normal,tick)),td,selected,audited));admitted.addAll(eligible);
                }
            }
        }
        if(spatialWork.exhausted())return incomplete(history,now,records);
        for(var track:history.tracks())if(track.lastSeen!=now){
            if(!anyUnknown&&localGap.test(track)){history.separated(track,now);if(RogTrace.sample(Stage.R05,"DCRT_SEPARATION"))RogTrace.detail(Stage.R05,"DCRT_SEPARATION","region={} gapSamples={} state={}",track.id,track.gaps,track.phase);}
            else history.unknown(track,now);
        }
        if(accepted.stream().flatMap(p->p.candidate.tracks.stream()).anyMatch(t->t.ambiguousTick==now))return incomplete(history,now,records);
        int excluded=0;double excludedPeak=0;for(var c:records)if(!admitted.contains(c)){excluded++;excludedPeak=Math.max(excludedPeak,c.rawImpactScalar());}
        if(!accepted.isEmpty()&&RogTrace.eventSlot(Stage.R05,"DCRT_EXCLUDED_SUPPORT"))RogTrace.detail(Stage.R05,"DCRT_EXCLUDED_SUPPORT","observedRegions={} approvedRegions={} excludedRecords={} excludedPeak={} unknown={}",groups.size(),accepted.size(),excluded,excludedPeak,anyUnknown);
        return new DcrtContactBatch(accepted,restingOnly&&!anyUnknown,groups.size(),excluded,anyUnknown?excluded:0,excludedPeak);
    }
    private static boolean needsAudit(Cluster cluster,DirectionalContactRegions history,Set<Contact> approaching,Settings settings,boolean fullPairArmed,long now){
        for(var r:cluster.routes){
            if(r.track==null)return true; // first observation, including support
            if(r.track.ambiguousTick!=now&&history.eligible(r.track,true,fullPairArmed)&&r.records.stream().anyMatch(c->approaching.contains(c)&&settings.candidate(c.rawImpactScalar())>=1))return true;
        }
        return false;
    }
    private static DcrtSpatialFootprint.Surface surface(Pair pair,List<Contact> records,OwnerResolver.NativeAuditBatch batch,DcrtSpatialFootprint.Work work){
        var a=new LinkedHashSet<DcrtSpatialFootprint.Face>();var b=new LinkedHashSet<DcrtSpatialFootprint.Face>();
        for(var c:records)for(boolean onA:new boolean[]{true,false}){
            String owner=(onA?pair.a():pair.b()).stableKey();int source=SableConsumerTargets.sourceSide(owner,c,pair.dimension());
            if(source==0)return null;
            var axis=SableConsumerTargets.identityAxis(c,source==1);var witness=exactWitness(owner,c,source==1,batch);
            if(axis==null||witness==null||Math.abs(axis.x())+Math.abs(axis.y())+Math.abs(axis.z())!=1)return null;
            int i=axis.x()!=0?0:axis.y()!=0?1:2;int sign=axis.x()+axis.y()+axis.z();
            var point=SableConsumerTargets.candidate(c,source==1);var out=onA?a:b;boolean found=false;
            for(var box:witness.boxes){if(!work.charge(1))return null;var face=DcrtSpatialFootprint.Face.of(witness.target,witness.pos,box,i,sign);if(face.contains(point,new Vec(0,0,0),SableConsumerTargets.EPSILON)){out.add(face);found=true;}}
            if(!found||out.size()>MAX_FOOTPRINT)return null;
        }
        var result=new DcrtSpatialFootprint.Surface(List.copyOf(a),List.copyOf(b));return result.valid()?result:null;
    }
    private static double peakOf(List<Contact> records){return records.stream().mapToDouble(Contact::rawImpactScalar).max().orElse(0);}

    enum GapStatus { PROVED, AMBIGUOUS_TARGET, NO_POSITIVE_GAP, INVALID_POSE, INVALID_CAPTURE, SUPPORT_CONTACT, APPROACH_UNPROVED, AUDIT_BUDGET_EXHAUSTED }
    /** Independent evidence belongs to a single original native record. The
     * complete-input gate is applied before this method, never bypassed here. */
    private record GapProof(List<Contact> records,boolean retryable) {}
    private static GapProof provedRecords(Pair pair,List<Contact> records,List<CollisionFrame.Snapshot> pre,
                          Map<NormalKey,NativeEffectDirection.Result> normals,SableContactRegions.Prepared prepared){
        if(!prepared.direct.verified()||prepared.direct.trace().failure()!=SableContactRegions.AuditFailure.NONE){
            if(RogTrace.sample(Stage.R05,"DCRT_WITNESS_PROOF"))RogTrace.detail(Stage.R05,"DCRT_WITNESS_PROOF","records={} eligible=0 reason=AUDIT_UNPROVED failure={}",records.size(),prepared.direct.trace().failure());
            return new GapProof(List.of(),true);
        }
        var first=NativeApproachEvidence.earliest(pair,pre,records.getFirst().serverTick());
        if(first==null)return new GapProof(List.of(),false);
        var paired=new HashSet<ContactCreditLedger.Region>();
        for(var r:prepared.direct.regions())if(r.a()!=null&&r.b()!=null&&!r.ownerLocalPatch())paired.add(r);
        var accepted=new ArrayList<Contact>();boolean retryable=false;
        var counts=RogTrace.stageEnabled(Stage.R05)?new EnumMap<GapStatus,Integer>(GapStatus.class):null;
        for(var record:records){
            var normal=normals.get(NormalKey.of(record));
            var status=preGapStatus(pair,record,first,normal,prepared.auditBatch(),paired);
            if(status==GapStatus.PROVED){
                double closing=NativeApproachEvidence.earliestClosing(pair,pre,normal,record.serverTick());
                if(!NativeApproachEvidence.regionApproach(closing))status=Double.isFinite(closing)?GapStatus.SUPPORT_CONTACT:GapStatus.APPROACH_UNPROVED;
            }
            // Invalid capture/pose affects temporal completeness, not just the
            // material identity of one record. Fail the whole region closed.
            if(status==GapStatus.INVALID_CAPTURE||status==GapStatus.INVALID_POSE||status==GapStatus.AUDIT_BUDGET_EXHAUSTED){
                if(RogTrace.sample(Stage.R05,"DCRT_WITNESS_PROOF"))RogTrace.detail(Stage.R05,"DCRT_WITNESS_PROOF","records={} eligible=0 reason={}",records.size(),status);
                return new GapProof(List.of(),status==GapStatus.AUDIT_BUDGET_EXHAUSTED);
            }
            retryable|=status==GapStatus.AMBIGUOUS_TARGET;
            if(status==GapStatus.PROVED)accepted.add(record);
            if(counts!=null)counts.merge(status,1,Integer::sum);
        }
        if(counts!=null&&RogTrace.sample(Stage.R05,"DCRT_WITNESS_PROOF"))RogTrace.detail(Stage.R05,"DCRT_WITNESS_PROOF","records={} eligible={} outcomes={}",records.size(),accepted.size(),counts);
        return new GapProof(List.copyOf(accepted),retryable);
    }
    private static GapStatus preGapStatus(Pair pair,Contact record,CollisionFrame.Snapshot first,
                          NativeEffectDirection.Result normal,OwnerResolver.NativeAuditBatch batch,Set<ContactCreditLedger.Region> paired){
        int sa=SableConsumerTargets.sourceSide(pair.a().stableKey(),record,pair.dimension());
        int sb=SableConsumerTargets.sourceSide(pair.b().stableKey(),record,pair.dimension());
        if(sa==0||sb==0||sa==sb||record.runtimeStamp().known()&&!record.runtimeStamp().sameCapture(first.runtimeStamp()))return GapStatus.INVALID_CAPTURE;
        if(normal==null||normal.owners().size()!=2)return GapStatus.INVALID_POSE;
        var pa=BilateralContactSurface.prePose(record,sa==1,first);var pb=BilateralContactSurface.prePose(record,sb==1,first);
        if(pa==null||pb==null)return GapStatus.INVALID_POSE;
        try{
            Vec inward=pa.toWorldNormal(normal.forOwner(pair.a().stableKey()).inward());
            var a=exactWitness(pair.a().stableKey(),record,sa==1,batch);
            var b=exactWitness(pair.b().stableKey(),record,sb==1,batch);
            if(a==null||b==null||!paired.contains(new ContactCreditLedger.Region(a.target,b.target)))return GapStatus.AMBIGUOUS_TARGET;
            double minA=projection(a.boxes,a.pos,pa,inward,true),maxB=projection(b.boxes,b.pos,pb,inward,false);
            if(!Double.isFinite(minA)||!Double.isFinite(maxB))return GapStatus.INVALID_POSE;
            return minA-maxB>RealFixBoundsEvidence.APPROACH_DELTA?GapStatus.PROVED:GapStatus.NO_POSITIVE_GAP;
        }catch(RuntimeException invalid){return GapStatus.INVALID_POSE;}
    }
    private record Witness(Target target,BlockPos pos,List<AABB> boxes) {}
    private static Witness exactWitness(String owner,Contact record,boolean a,OwnerResolver.NativeAuditBatch batch){
        var point=SableConsumerTargets.candidate(record,a);var axis=SableConsumerTargets.identityAxis(record,a);
        if(point==null||axis==null)return null;
        Witness found=null;
        for(var pos:SableConsumerTargets.cells(point)){
            if(!batch.cached(owner,pos))return null;
            var checked=batch.inspectCell(owner,pos).result();
            if(!checked.verified()){
                if(!OwnerResolver.knownNoShape(checked))return null;
                continue;
            }
            if(!batch.contains(owner,pos,point,1e-6)||!batch.targetFace(owner,pos,point,axis))continue;
            var k=checked.key();var target=new Target(owner,k.x()+","+k.y()+","+k.z(),k.fingerprint());
            var boxes=batch.boxes(owner,pos);if(boxes.isEmpty()||boxes.size()>MAX_GAP_BOXES||found!=null)return null;
            found=new Witness(target,pos,boxes);
        }
        return found;
    }
    private static double projection(List<AABB> boxes,BlockPos pos,Pose pose,Vec normal,boolean minimum){
        double value=minimum?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY;
        for(var box:boxes)for(int i=0;i<8;i++){
            var p=new Vec(pos.getX()+((i&1)==0?box.minX:box.maxX),pos.getY()+((i&2)==0?box.minY:box.maxY),pos.getZ()+((i&4)==0?box.minZ:box.maxZ));
            double d=pose.toWorldPoint(p).dot(normal);value=minimum?Math.min(value,d):Math.max(value,d);
        }
        return value;
    }
    /** Local rearm only for a bounded planar World footprint. The caller
     * must verify loaded, unchanged full collision cubes and whole-Plot gap.
     * No absence-of-record or outward-speed rearm. */
    private static DirectionalContactRegions.WorldFootprint footprint(Pair pair,List<Contact> records,NativeEffectDirection.Result normal,SableContactRegions.Result direct){
        String owner=pair.a().stableKey().startsWith("WORLD:")?pair.a().stableKey():pair.b().stableKey().startsWith("WORLD:")?pair.b().stableKey():null;
        if(owner==null)return null;
        Vec n=normal.forOwner(owner).inward();if(n==null)return null;
        Vec axis=cardinal(n);if(axis==null)return null;
        var targets=new LinkedHashSet<Target>();
        boolean onA=owner.equals(pair.a().stableKey());
        for(var r:direct.regions()){
            var t=onA?r.a():r.b();if(t!=null)targets.add(t);
            if(targets.size()>MAX_FOOTPRINT)return null;
        }
        if(targets.isEmpty()||(onA?direct.reservedA():direct.reservedB())!=0)return null;
        double plane=Double.NaN;
        for(var c:records){int side=SableConsumerTargets.sourceSide(owner,c,pair.dimension());if(side==0)return null;var p=SableConsumerTargets.candidate(c,side==1);if(p==null)return null;
            double d=p.dot(axis);if(!Double.isFinite(d)||Double.isFinite(plane)&&Math.abs(d-plane)>SableConsumerTargets.EPSILON)return null;plane=d;}
        for(var t:targets){var q=t.localBlock().split(",");if(q.length!=3)return null;
            try{int x=Integer.parseInt(q[0]),y=Integer.parseInt(q[1]),z=Integer.parseInt(q[2]);double near=new Vec(x,y,z).dot(axis)+Math.min(0,axis.x())+Math.min(0,axis.y())+Math.min(0,axis.z());if(Math.abs(near-plane)>SableConsumerTargets.EPSILON)return null;}
            catch(NumberFormatException bad){return null;}}
        return new DirectionalContactRegions.WorldFootprint(owner,axis,plane,List.copyOf(targets));
    }
    private static Vec cardinal(Vec n){
        double[] c={n.x(),n.y(),n.z()};int i=0;for(int j=1;j<3;j++)if(Math.abs(c[j])>Math.abs(c[i]))i=j;
        if(Math.abs(c[i])<1-1e-8)return null;double v=Math.copySign(1,c[i]);return new Vec(i==0?v:0,i==1?v:0,i==2?v:0);
    }
    private static boolean footprintStillCompatible(DirectionalContactRegions.Track track,Pair pair,List<Contact> records){
        var f=track.footprint;if(f==null)return true;
        for(var c:records){int side=SableConsumerTargets.sourceSide(f.owner(),c,pair.dimension());if(side==0)return false;var p=SableConsumerTargets.candidate(c,side==1);if(p==null||Math.abs(p.dot(f.inward())-f.plane())>SableConsumerTargets.EPSILON)return false;}
        return true;
    }
    static boolean positiveWorldGap(DirectionalContactRegions.WorldFootprint f,RealFixBoundsEvidence.Box plot){
        if(f==null||plot==null)return false;Vec n=f.inward();
        double max=(n.x()>=0?plot.maxX():plot.minX())*n.x()+(n.y()>=0?plot.maxY():plot.minY())*n.y()+(n.z()>=0?plot.maxZ():plot.minZ())*n.z();
        return f.plane()-SableConsumerTargets.EPSILON-max>=RealFixBoundsEvidence.SEPARATION_THRESHOLD;
    }
    private record CellKey(String owner,String block){
        static CellKey of(Target t){return new CellKey(t.ownerKey(),t.localBlock());}
    }
    private static void addIdentity(Map<CellKey,Target> identities,Set<CellKey> conflicts,Target target){
        if(target==null)return;
        var key=CellKey.of(target);var prior=identities.putIfAbsent(key,target);
        if(prior!=null&&!prior.equals(target))conflicts.add(key);
    }
    private static boolean conflict(Set<CellKey> conflicts,Target target){return target!=null&&conflicts.contains(CellKey.of(target));}
    private static List<Target> canonicalGroup(List<Target> group,Map<CellKey,Target> identities){
        return group.stream().map(t->identities.getOrDefault(CellKey.of(t),t)).distinct()
                .sorted(Comparator.comparing(Target::ownerKey).thenComparing(Target::localBlock).thenComparing(Target::fingerprint)).toList();
    }
    private static void mergeSources(Map<Contact,List<Target>> into,Map<Contact,List<Target>> from){
        from.forEach((k,v)->into.merge(k,v,(a,b)->{var out=new LinkedHashSet<Target>(a);out.addAll(b);return List.copyOf(out);}));
    }
    /** One pair/generation, one payable identity per cell. A patch that overlaps a
     * direct seed may supply only material evidence, never a second patch seed.
     * Conflicting identities/models are reserved, not redistributed. */
    static SableContactRegions.Result combine(List<SableContactRegions.Result> input){
        if(input.isEmpty())return SableContactRegions.skippedDuplicate();
        var raw=new LinkedHashSet<ContactCreditLedger.Region>();
        var aa=new HashSet<Target>();var ab=new HashSet<Target>();
        var ga=new ArrayList<List<Target>>();var gb=new ArrayList<List<Target>>();
        var sa=new HashMap<Contact,List<Target>>();var sb=new HashMap<Contact,List<Target>>();
        var identities=new HashMap<CellKey,Target>();var conflicts=new HashSet<CellKey>();
        int ca=0,cb=0,ua=0,ub=0,rejected=0,processed=0;boolean complete=true;
        for(var r:input){
            raw.addAll(r.regions());aa.addAll(r.ambiguousA());ab.addAll(r.ambiguousB());ga.addAll(r.groupsA());gb.addAll(r.groupsB());
            mergeSources(sa,r.sourceGroupsA());mergeSources(sb,r.sourceGroupsB());
            ca+=r.checksA();cb+=r.checksB();ua+=r.unknownA();ub+=r.unknownB();rejected+=r.rejected();processed+=r.trace().processedRecords();complete&=r.status()==SableContactRegions.Status.PROVED;
        }
        for(var r:raw){addIdentity(identities,conflicts,r.a());addIdentity(identities,conflicts,r.b());for(var t:r.opposingCandidates())addIdentity(identities,conflicts,t);}
        for(var g:ga)for(var t:g)addIdentity(identities,conflicts,t);
        for(var g:gb)for(var t:g)addIdentity(identities,conflicts,t);
        var regions=new LinkedHashSet<ContactCreditLedger.Region>();
        var models=new LinkedHashMap<Target,Set<ContactCreditLedger.Region>>();
        var direct=new HashSet<Target>();var patch=new HashSet<Target>();
        for(var r:raw){
            boolean badA=conflict(conflicts,r.a()),badB=conflict(conflicts,r.b());
            if(badA)ga.add(List.of(identities.get(CellKey.of(r.a()))));
            if(badB)gb.add(List.of(identities.get(CellKey.of(r.b()))));
            Target a=badA?null:r.a(),b=badB?null:r.b();
            if(a==null&&b==null)continue;
            var peers=badA||badB||r.opposingCandidates().stream().anyMatch(t->conflict(conflicts,t))?List.<Target>of():r.opposingCandidates();
            var clean=new ContactCreditLedger.Region(a,b,peers,r.ownerLocalPatch());
            if(r.ownerLocalPatch())models.computeIfAbsent(a!=null?a:b,k->new LinkedHashSet<>()).add(clean);
            else{regions.add(clean);if(a!=null)direct.add(a);if(b!=null)direct.add(b);}
        }
        int overlap=0,withheld=0;
        for(var entry:models.entrySet()){
            var target=entry.getKey();var set=entry.getValue();var r=set.iterator().next();
            if(direct.contains(target)){
                overlap++;
                // Exact/direct evidence is retained. Approximate peers contribute
                // only if they agree; incompatible models retain an unresolved share.
                regions.add(new ContactCreditLedger.Region(r.a(),r.b(),set.size()==1?r.opposingCandidates():List.of()));
            }else if(set.size()==1){regions.add(r);patch.add(target);}
            else{(r.a()!=null?ga:gb).add(List.of(target));withheld++;}
        }
        if(input.size()==1&&overlap==0&&withheld==0&&conflicts.isEmpty())return input.getFirst();
        var a=new HashSet<Target>();var b=new HashSet<Target>();
        for(var r:regions){if(r.a()!=null)a.add(r.a());if(r.b()!=null)b.add(r.b());}
        var groupsA=ga.stream().map(g->canonicalGroup(g,identities)).toList();
        var groupsB=gb.stream().map(g->canonicalGroup(g,identities)).toList();
        sa.replaceAll((k,v)->canonicalGroup(v,identities));sb.replaceAll((k,v)->canonicalGroup(v,identities));
        aa=new HashSet<>(canonicalGroup(new ArrayList<>(aa),identities));ab=new HashSet<>(canonicalGroup(new ArrayList<>(ab),identities));
        for(var g:groupsA)aa.addAll(g);for(var g:groupsB)ab.addAll(g);
        // A withheld patch is not confirmed just because another patch candidate
        // mentions it; existing source ambiguity remains part of the reservation.
        var confirmedA=new HashSet<>(a);confirmedA.removeAll(patch);var confirmedB=new HashSet<>(b);confirmedB.removeAll(patch);
        int ra=Math.addExact(ua,SableContactRegions.additionalReservation(groupsA,confirmedA));
        int rb=Math.addExact(ub,SableContactRegions.additionalReservation(groupsB,confirmedB));
        int cap=SableContactRegions.RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE;
        boolean over=ca>cap||cb>cap||(long)a.size()+ra>ContactCreditLedger.MAX_TARGETS_PER_SIDE||(long)b.size()+rb>ContactCreditLedger.MAX_TARGETS_PER_SIDE||regions.size()>ContactCreditLedger.MAX_REGIONS;
        var status=over?SableContactRegions.Status.TOO_BROAD:regions.isEmpty()?SableContactRegions.Status.NO_VERIFIED_REGION:complete&&conflicts.isEmpty()&&withheld==0?SableContactRegions.Status.PROVED:SableContactRegions.Status.PARTIAL;
        var trace=new SableContactRegions.Trace(over?SableContactRegions.AuditFailure.REGION_LIMIT:SableContactRegions.AuditFailure.NONE,-1,processed,Math.max(0,cap-ca),Math.max(0,cap-cb),0,0,a.size(),b.size(),regions.size(),rejected,"DCRT_REGIONS="+input.size()+" duplicatePatch="+overlap+" withheldModel="+withheld+" identityConflict="+conflicts.size());
        if(RogTrace.sample(Stage.R04,"DCRT_CANONICAL_SEEDS"))RogTrace.detail(Stage.R04,"DCRT_CANONICAL_SEEDS","regions={} directPatchOverlap={} withheldModels={} identityConflicts={} reservedA={} reservedB={}",regions.size(),overlap,withheld,conflicts.size(),ra,rb);
        return new SableContactRegions.Result(over?List.of():List.copyOf(regions),status,ca,cb,rejected,a.isEmpty()?"UNVERIFIED":"R2_DCRT_REGION",b.isEmpty()?"UNVERIFIED":"R2_DCRT_REGION",trace,aa,ab,ua,ub,groupsA,groupsB,patch,sa,sb,ra,rb);
    }
}

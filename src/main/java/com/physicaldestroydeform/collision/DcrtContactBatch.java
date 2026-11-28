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
    private record Candidate(DirectionalContactRegions.Track track,List<Contact> records,
                             NativeEffectDirection.Result normal,double closing) {}
    private record Part(Candidate candidate,TargetDirections directions,SableContactRegions.Prepared prepared,
                        SableContactRegions.Result authorized) {}
    private final List<Part> parts;
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
            var result=part.prepared.expand();results.add(result);directions.mergeFrom(part.directions);
            for(var r:result.regions()){
                if(r.a()!=null)mergeDirection(orientations,r.a(),part.candidate.normal.forOwner(pair.a().stableKey()));
                if(r.b()!=null)mergeDirection(orientations,r.b(),part.candidate.normal.forOwner(pair.b().stableKey()));
            }
        }
        orientations.forEach((target,n)->{if("DCRT_CONFLICT".equals(n.evidence()))directions.denyMixedSeed(target);});
        seedDirections=Map.copyOf(orientations);expanded=combine(results);
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

    static DcrtContactBatch select(MinecraftServer server,Pair pair,List<Contact> records,List<CollisionFrame.Snapshot> pre,
                                   long tick,long now,boolean complete,boolean fullPairArmed,Settings settings,
                                   DirectionalContactRegions history,Predicate<DirectionalContactRegions.Track> localGap){
        if(!complete||records.isEmpty()||records.size()>DirectionalContactRegions.MAX_WITNESSES||records.stream().anyMatch(Contact::capSaturated)){
            history.incomplete(now);
            if(RogTrace.eventSlot(Stage.R05,"DCRT_BUDGET"))RogTrace.detail(Stage.R05,"DCRT_BUDGET","records={} complete={} nativeCap={} reason=UNKNOWN_SOURCE_NO_REARM",records.size(),complete,records.stream().anyMatch(Contact::capSaturated));
            return new DcrtContactBatch(List.of(),false,0,records.size(),records.size(),peakOf(records));
        }
        var proofs=new HashMap<NormalKey,NativeEffectDirection.Result>();var normals=new ArrayList<DirectionalContactRegions.Direction>(records.size());
        for(var c:records){
            var n=proofs.computeIfAbsent(NormalKey.of(c),k->NativeEffectDirection.verify(pair,List.of(c),pre));
            if(n.owners().size()!=2){history.incomplete(now);return new DcrtContactBatch(List.of(),false,0,records.size(),records.size(),peakOf(records));}
            normals.add(new DirectionalContactRegions.Direction(n.forOwner(pair.a().stableKey()).inward(),n.forOwner(pair.b().stableKey()).inward()));
        }
        var groups=DirectionalContactRegions.cluster(normals);
        if(groups.isEmpty()){history.incomplete(now);return new DcrtContactBatch(List.of(),false,0,records.size(),records.size(),peakOf(records));}
        var candidates=new ArrayList<Candidate>();
        for(var indices:groups){
            var group=indices.stream().map(records::get).toList();
            var normal=NativeEffectDirection.verify(pair,group,pre);
            if(normal.owners().size()!=2){history.incomplete(now);return new DcrtContactBatch(List.of(),false,groups.size(),records.size(),records.size(),peakOf(records));}
            var direction=new DirectionalContactRegions.Direction(normal.forOwner(pair.a().stableKey()).inward(),normal.forOwner(pair.b().stableKey()).inward());
            var track=history.observe(direction,now,true,fullPairArmed);
            double closing=NativeApproachEvidence.earliestClosing(pair,pre,normal,tick);
            if(track!=null&&!footprintStillCompatible(track,pair,group))track.footprint=null;
            candidates.add(new Candidate(track,group,normal,closing));
            if(RogTrace.sample(Stage.R05,"DCRT_REGION_CLUSTER"))RogTrace.detail(Stage.R05,"DCRT_REGION_CLUSTER","region={} records={} normalA={} normalB={} closing={} captureTick={} peak={}",track==null?-1:track.id,group.size(),direction.a(),direction.b(),closing,tick,peakOf(group));
        }
        boolean anyUnknown=candidates.stream().anyMatch(c->c.track==null||c.track.ambiguousTick==now);
        if(anyUnknown){
            // Ambiguous split/merge must not create a second authorization for
            // a still-active contact. Other directions retain their latches.
            for(var c:candidates)if(c.track!=null&&c.track.ambiguousTick==now)history.unknown(c.track,now);
        }
        for(var track:history.tracks())if(track.lastSeen!=now){
            if(!anyUnknown&&localGap.test(track)){
                history.separated(track,now);
                if(RogTrace.sample(Stage.R05,"DCRT_SEPARATION"))RogTrace.detail(Stage.R05,"DCRT_SEPARATION","region={} gapSamples={} state={}",track.id,track.gaps,track.phase);
            }else history.unknown(track,now);
        }
        // Reserve the old per-pair cell budget, not one entire budget per region.
        int auditCount=0;
        for(var c:candidates)if(history.eligible(c.track,true,fullPairArmed)&&NativeApproachEvidence.regionApproach(c.closing)&&settings.candidate(peakOf(c.records))>=1)auditCount++;
        int budget=SableContactRegions.RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE/Math.max(1,auditCount);
        var accepted=new ArrayList<Part>();int excluded=0,unknown=0;double excludedPeak=0;boolean restingOnly=true;
        for(var c:candidates){
            var track=c.track;
            boolean candidate=history.eligible(track,true,fullPairArmed)&&NativeApproachEvidence.regionApproach(c.closing)&&settings.candidate(peakOf(c.records))>=1;
            if(track==null||!track.observed||track.phase!=DirectionalContactRegions.Phase.ACTIVE_SPENT)restingOnly=false;
            boolean approved=false;
            String reason=track==null||track.ambiguousTick==now?"UNKNOWN_TRACK":!candidate?"NO_INDEPENDENT_APPROACH_OR_SPENT":"R2_OR_GAP_UNPROVED";
            if(candidate){
                if(RogTrace.eventSlot(Stage.R05,"DCRT_REGION_MATCH"))RogTrace.detail(Stage.R05,"DCRT_REGION_MATCH","region={} priorObserved={} consumed={} state={} priorGapSamples={} anchor={} spatialPolicy=CONSERVATIVE_DIRECTION_TRACK",track.id,track.observed,track.consumed,track.phase,track.gaps,track.anchor);
                restingOnly=false;
                var td=TargetDirections.forEvent(pair,c.records,pre,tick,true);
                var prepared=SableContactRegions.prepare(server,pair.dimension(),pair,c.records,td,budget);
                var audited=prepared.direct;
                boolean preGap=fullPairArmed||prepared.positivePreGap(pre,c.normal);
                // A full-Pair independently verified clearance is retained as
                // an alternate path. A regional reimpact never originates in a patch.
                if(fullPairArmed&&!audited.verified())audited=prepared.exact();
                if(audited.verified()&&history.eligible(track,preGap,fullPairArmed)&&preGap){
                    accepted.add(new Part(c,td,prepared,audited));approved=true;reason="APPROVED";
                    track.footprint=footprint(pair,c.records,c.normal,prepared.direct);
                }
                if(RogTrace.eventSlot(Stage.R05,"DCRT_APPROACH"))RogTrace.detail(Stage.R05,"DCRT_APPROACH","region={} closing={} positiveGap={} fullPairArmed={} directStatus={} authorized={} budgetPerSide={}",track.id,c.closing,preGap,fullPairArmed,audited.status(),approved,budget);
            }
            if(!approved){excluded+=c.records.size();excludedPeak=Math.max(excludedPeak,peakOf(c.records));if(track==null||track.ambiguousTick==now)unknown+=c.records.size();}
            if(track!=null&&track.ambiguousTick!=now){
                var old=track.phase;
                history.contact(track,approved);
                if(old!=track.phase&&RogTrace.sample(Stage.R05,"DCRT_REGION_TRANSITION"))RogTrace.detail(Stage.R05,"DCRT_REGION_TRANSITION","region={} before={} after={} consumed={}",track.id,old,track.phase,track.consumed);
            }
            if(approved||candidate){if(RogTrace.eventSlot(Stage.R05,"DCRT_AUTHORIZATION"))RogTrace.detail(Stage.R05,"DCRT_AUTHORIZATION","region={} records={} peak={} closing={} reason={}",track==null?-1:track.id,c.records.size(),peakOf(c.records),c.closing,reason);}
        }
        if(!accepted.isEmpty()&&RogTrace.eventSlot(Stage.R05,"DCRT_EXCLUDED_SUPPORT"))RogTrace.detail(Stage.R05,"DCRT_EXCLUDED_SUPPORT","observedRegions={} approvedRegions={} excludedRecords={} excludedPeak={} unknownRecords={}",groups.size(),accepted.size(),excluded,excludedPeak,unknown);
        return new DcrtContactBatch(accepted,restingOnly&&!anyUnknown,groups.size(),excluded,unknown,excludedPeak);
    }
    private static double peakOf(List<Contact> records){return records.stream().mapToDouble(Contact::rawImpactScalar).max().orElse(0);}

    /** Positive pre-gap of actual R2-identified collision shapes. Every
     * contributing record must have its own exact two-sided witness; no
     * position-only guess, no raw witness-point distance as gap evidence. */
    static boolean preGap(Pair pair,List<Contact> records,List<CollisionFrame.Snapshot> pre,
                          NativeEffectDirection.Result normal,OwnerResolver.NativeAuditBatch batch,SableContactRegions.Result direct){
        if(!direct.verified()||records.isEmpty())return false;
        var first=NativeApproachEvidence.earliest(pair,pre,records.getFirst().serverTick());
        if(first==null)return false;
        var paired=new HashSet<ContactCreditLedger.Region>();
        for(var r:direct.regions())if(r.a()!=null&&r.b()!=null&&!r.ownerLocalPatch())paired.add(r);
        if(paired.isEmpty())return false;
        for(var record:records){
            int sa=SableConsumerTargets.sourceSide(pair.a().stableKey(),record,pair.dimension());
            int sb=SableConsumerTargets.sourceSide(pair.b().stableKey(),record,pair.dimension());
            if(sa==0||sb==0||sa==sb||record.runtimeStamp().known()&&!record.runtimeStamp().sameCapture(first.runtimeStamp()))return false;
            var pa=BilateralContactSurface.prePose(record,sa==1,first);var pb=BilateralContactSurface.prePose(record,sb==1,first);
            if(pa==null||pb==null)return false;
            try{
                Vec inward=pa.toWorldNormal(normal.forOwner(pair.a().stableKey()).inward());
                var a=exactWitness(pair.a().stableKey(),record,sa==1,batch);
                var b=exactWitness(pair.b().stableKey(),record,sb==1,batch);
                if(a==null||b==null||!paired.contains(new ContactCreditLedger.Region(a.target,b.target)))return false;
                double minA=projection(a.boxes,a.pos,pa,inward,true),maxB=projection(b.boxes,b.pos,pb,inward,false);
                if(!Double.isFinite(minA)||!Double.isFinite(maxB)||minA-maxB<=RealFixBoundsEvidence.APPROACH_DELTA)return false;
            }catch(RuntimeException invalid){return false;}
        }
        return true;
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
    private static SableContactRegions.Result combine(List<SableContactRegions.Result> input){
        if(input.isEmpty())return SableContactRegions.skippedDuplicate();if(input.size()==1)return input.getFirst();
        var regions=new LinkedHashSet<ContactCreditLedger.Region>();var aa=new HashSet<Target>();var ab=new HashSet<Target>();var patch=new HashSet<Target>();
        var ga=new ArrayList<List<Target>>();var gb=new ArrayList<List<Target>>();var sa=new HashMap<Contact,List<Target>>();var sb=new HashMap<Contact,List<Target>>();
        int ca=0,cb=0,ua=0,ub=0,ra=0,rb=0,rejected=0,processed=0;boolean complete=true;
        for(var r:input){regions.addAll(r.regions());aa.addAll(r.ambiguousA());ab.addAll(r.ambiguousB());patch.addAll(r.patchSeeds());ga.addAll(r.groupsA());gb.addAll(r.groupsB());sa.putAll(r.sourceGroupsA());sb.putAll(r.sourceGroupsB());
            ca+=r.checksA();cb+=r.checksB();ua+=r.unknownA();ub+=r.unknownB();ra+=r.reservedA();rb+=r.reservedB();rejected+=r.rejected();processed+=r.trace().processedRecords();complete&=r.status()==SableContactRegions.Status.PROVED;}
        var a=new HashSet<Target>();var b=new HashSet<Target>();for(var r:regions){if(r.a()!=null)a.add(r.a());if(r.b()!=null)b.add(r.b());if(!r.ownerLocalPatch()){patch.remove(r.a());patch.remove(r.b());}}
        int cap=SableContactRegions.RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE;
        boolean over=ca>cap||cb>cap||a.size()+ra>ContactCreditLedger.MAX_TARGETS_PER_SIDE||b.size()+rb>ContactCreditLedger.MAX_TARGETS_PER_SIDE||regions.size()>ContactCreditLedger.MAX_REGIONS;
        var status=over?SableContactRegions.Status.TOO_BROAD:regions.isEmpty()?SableContactRegions.Status.NO_VERIFIED_REGION:complete?SableContactRegions.Status.PROVED:SableContactRegions.Status.PARTIAL;
        var trace=new SableContactRegions.Trace(over?SableContactRegions.AuditFailure.REGION_LIMIT:SableContactRegions.AuditFailure.NONE,-1,processed,Math.max(0,cap-ca),Math.max(0,cap-cb),0,0,a.size(),b.size(),regions.size(),rejected,"DCRT_REGIONS="+input.size());
        return new SableContactRegions.Result(over?List.of():List.copyOf(regions),status,ca,cb,rejected,a.isEmpty()?"UNVERIFIED":"R2_DCRT_REGION",b.isEmpty()?"UNVERIFIED":"R2_DCRT_REGION",trace,aa,ab,ua,ub,ga,gb,patch,sa,sb,ra,rb);
    }
}

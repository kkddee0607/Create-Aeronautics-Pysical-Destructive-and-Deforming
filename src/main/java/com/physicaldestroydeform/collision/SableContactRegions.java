package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.RogTrace;
import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import net.minecraft.server.MinecraftServer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.function.Supplier;
import com.physicaldestroydeform.diagnostics.DiagnosticMode;
import net.minecraft.core.BlockPos;

/**
 * Bounded contact-region target audit for IDS §21.4/§22.4. Audit each source
 * record's contact frame independently, then deduplicate actual block keys.
 * The record index is NOT a stable native contact/manifold identifier.
 */
public final class SableContactRegions {
    private SableContactRegions() {}
    /** Legacy pure-policy test budget; collect() retains its existing contract. */
    public static final int MAX_CHECKS_PER_SIDE=32;
    /** Bounded read budget, independent of 16,384 verified damage targets. */
    public static final int RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE=32_768;
    public enum Status { PROVED, PARTIAL, NO_VERIFIED_REGION, TOO_BROAD, SKIPPED_DUPLICATE }
    public record Check(List<GameImpactCredit.Target> targets,int inspected,boolean incomplete,
                        String method,String detail,GameImpactCredit.Target directTarget,boolean completeCandidates) {
        public Check(List<GameImpactCredit.Target> targets,int inspected,boolean incomplete,String method,String detail,GameImpactCredit.Target directTarget) {
            this(targets,inspected,incomplete,method,detail,directTarget,!incomplete&&!targets.isEmpty()
                    &&targets.size()<=SableConsumerTargets.MAX_CANDIDATES);
        }
        public Check(List<GameImpactCredit.Target> targets,int inspected,boolean incomplete,String method,String detail) {
            this(targets,inspected,incomplete,method,detail,!incomplete&&targets.size()==1?targets.getFirst():null);
        }
        public Check(List<GameImpactCredit.Target> targets,int inspected,boolean incomplete,String method) {
            this(targets,inspected,incomplete,method,"NONE");
        }
        public Check(List<GameImpactCredit.Target> targets,int inspected) {
            this(targets,inspected,false,"TEST");
        }
        public Check(List<GameImpactCredit.Target> targets,int inspected,boolean incomplete) {
            this(targets,inspected,incomplete,"UNVERIFIED");
        }
        public Check {
            targets=List.copyOf(targets);
            if(inspected<0 || inspected>RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE)throw new IllegalArgumentException("unbounded contact audit");
            if(directTarget!=null&&(incomplete||!targets.contains(directTarget)))throw new IllegalArgumentException("unproved direct target");
            if(completeCandidates&&(incomplete||targets.isEmpty()||targets.size()>SableConsumerTargets.MAX_CANDIDATES))
                throw new IllegalArgumentException("invalid candidate coverage");
        }
        /** Only an exhaustive same-record native target search can prove all
         * possible opposing E, even when the exact collider cell is ambiguous. */
        List<GameImpactCredit.Target> opposingCandidates(){
            if(directTarget!=null)return List.of(directTarget);
            return completeCandidates?targets:List.of();
        }
    }
    @FunctionalInterface public interface Inspector {
        Check verify(NativeCollisionTelemetry.Contact record,String owner,int remainingChecks);
    }
    /** Why an otherwise valid native event could not establish a target set. */
    public enum AuditFailure {
        NONE, UNCORROBORATED, SIDE_A_INCOMPLETE, SIDE_B_INCOMPLETE,
        SIDE_A_AND_B_INCOMPLETE, CHECK_BUDGET_A, CHECK_BUDGET_B,
        OWNER_MISMATCH_A, OWNER_MISMATCH_B, TARGET_LIMIT_A, TARGET_LIMIT_B,
        REGION_LIMIT, NO_VERIFIED_REGION
    }
    public record Trace(AuditFailure failure,int sourceIndex,int processedRecords,
                        int remainingA,int remainingB,int requestedA,int requestedB,
                        int verifiedTargetsA,int verifiedTargetsB,int regionCount,
                        int rejectedContacts,String detail) {
        public Trace {Objects.requireNonNull(failure);detail=detail==null?"NONE":detail;}
    }
    public record Result(List<ContactCreditLedger.Region> regions,Status status,
                         int checksA,int checksB,int rejected,String methodA,String methodB,
                         Trace trace,Set<GameImpactCredit.Target> ambiguousA,Set<GameImpactCredit.Target> ambiguousB,
                         int unknownA,int unknownB,List<List<GameImpactCredit.Target>> groupsA,List<List<GameImpactCredit.Target>> groupsB,
                         Set<GameImpactCredit.Target> patchSeeds,
                         Map<NativeCollisionTelemetry.Contact,List<GameImpactCredit.Target>> sourceGroupsA,
                         Map<NativeCollisionTelemetry.Contact,List<GameImpactCredit.Target>> sourceGroupsB,int reservedA,int reservedB) {
        public Result(List<ContactCreditLedger.Region> regions,Status status,int checksA,int checksB,int rejected,
                      String methodA,String methodB,Trace trace,Set<GameImpactCredit.Target> ambiguousA,Set<GameImpactCredit.Target> ambiguousB,
                      int unknownA,int unknownB,List<List<GameImpactCredit.Target>> groupsA,List<List<GameImpactCredit.Target>> groupsB,
                      Set<GameImpactCredit.Target> patchSeeds,Map<NativeCollisionTelemetry.Contact,List<GameImpactCredit.Target>> sourceGroupsA,
                      Map<NativeCollisionTelemetry.Contact,List<GameImpactCredit.Target>> sourceGroupsB){
            this(regions,status,checksA,checksB,rejected,methodA,methodB,trace,ambiguousA,ambiguousB,unknownA,unknownB,groupsA,groupsB,patchSeeds,sourceGroupsA,sourceGroupsB,-1,-1);
        }
        public Result(List<ContactCreditLedger.Region> regions,Status status,int checksA,int checksB,int rejected,
                      String methodA,String methodB,Trace trace,Set<GameImpactCredit.Target> ambiguousA,
                      Set<GameImpactCredit.Target> ambiguousB,int unknownA,int unknownB,
                      List<List<GameImpactCredit.Target>> groupsA,List<List<GameImpactCredit.Target>> groupsB){
            this(regions,status,checksA,checksB,rejected,methodA,methodB,trace,ambiguousA,ambiguousB,unknownA,unknownB,groupsA,groupsB,Set.of(),Map.of(),Map.of());
        }
        public Result(List<ContactCreditLedger.Region> regions,Status status,int checksA,int checksB,int rejected,
                      String methodA,String methodB,Trace trace,Set<GameImpactCredit.Target> ambiguousA,
                      Set<GameImpactCredit.Target> ambiguousB,int unknownA,int unknownB){
            this(regions,status,checksA,checksB,rejected,methodA,methodB,trace,ambiguousA,ambiguousB,unknownA,unknownB,
                    ambiguousA.stream().map(List::of).toList(),ambiguousB.stream().map(List::of).toList());
        }
        public Result {
            regions=List.copyOf(regions);Objects.requireNonNull(trace);
            ambiguousA=Set.copyOf(ambiguousA);ambiguousB=Set.copyOf(ambiguousB);
            groupsA=groupsA.stream().map(List::copyOf).toList();groupsB=groupsB.stream().map(List::copyOf).toList();
            patchSeeds=Set.copyOf(patchSeeds);sourceGroupsA=Map.copyOf(sourceGroupsA);sourceGroupsB=Map.copyOf(sourceGroupsB);
            if(unknownA<0||unknownB<0)throw new IllegalArgumentException("invalid unresolved contact count");
            if(reservedA<0)reservedA=reservation(regions,patchSeeds,groupsA,unknownA,true);
            if(reservedB<0)reservedB=reservation(regions,patchSeeds,groupsB,unknownB,false);
        }
        public Result(List<ContactCreditLedger.Region> regions,Status status,int checksA,int checksB,int rejected,
                      String methodA,String methodB,Trace trace){
            this(regions,status,checksA,checksB,rejected,methodA,methodB,trace,Set.of(),Set.of(),0,0);
        }
        private static int reservation(List<ContactCreditLedger.Region> regions,Set<GameImpactCredit.Target> patchSeeds,
                List<List<GameImpactCredit.Target>> groups,int unknown,boolean onA){
            if(groups.isEmpty())return unknown;
            var confirmed=new HashSet<GameImpactCredit.Target>();
            for(var r:regions){var target=onA?r.a():r.b();if(target!=null&&!patchSeeds.contains(target))confirmed.add(target);}
            return Math.addExact(additionalReservation(groups,confirmed),unknown);
        }
        public Result(List<ContactCreditLedger.Region> regions,Status status,
                      int checksA,int checksB,int rejected,String methodA,String methodB) {
            this(regions,status,checksA,checksB,rejected,methodA,methodB,
                    new Trace(AuditFailure.NONE,-1,0,MAX_CHECKS_PER_SIDE-checksA,
                            MAX_CHECKS_PER_SIDE-checksB,0,0,0,0,regions.size(),rejected,"NONE"));
        }
        public boolean verified() {return status==Status.PROVED || status==Status.PARTIAL;}
        public GameImpactCredit.Target firstA() {
            return regions.stream().map(ContactCreditLedger.Region::a).filter(Objects::nonNull).findFirst().orElse(null);
        }
        public GameImpactCredit.Target firstB() {
            return regions.stream().map(ContactCreditLedger.Region::b).filter(Objects::nonNull).findFirst().orElse(null);
        }
    }
    /** No shape check is required to advance an already exhausted ACTIVE episode.
     * This is NOT a negative or rejected target audit. */
    public static Result skippedDuplicate() {
        return new Result(List.of(),Status.SKIPPED_DUPLICATE,0,0,0,
                "NOT_AUDITED_DUPLICATE","NOT_AUDITED_DUPLICATE",
                new Trace(AuditFailure.NONE,-1,0,MAX_CHECKS_PER_SIDE,
                        MAX_CHECKS_PER_SIDE,0,0,0,0,0,0,"AUDIT_SKIPPED_NO_Q_AUTHORIZATION"));
    }
    /** A source-local audit and its optional same-tick geometric completion.
     * The cache never escapes the authorization loop or survives a world mutation. */
    static final class Prepared {
        final Result direct;
        private Supplier<Result> expansion;
        private java.util.function.Function<Result,Result> model;
        private Result exact,expanded;
        Prepared(Result direct,Supplier<Result> expansion){this(direct,expansion,r->r);}
        Prepared(Result direct,Supplier<Result> expansion,java.util.function.Function<Result,Result> model){this.direct=direct;this.expansion=expansion;this.model=model;}
        Result exact(){
            if(exact==null){exact=expansion.get();expansion=null;}
            return exact;
        }
        Result expand(){
            if(expanded==null){expanded=model.apply(exact());model=null;}
            return expanded;
        }
    }
    public static Result audit(MinecraftServer server,String dimension,GameImpactCredit.Pair pair,
                               List<NativeCollisionTelemetry.Contact> records) {
        return prepare(server,dimension,pair,records,new TargetDirections()).direct;
    }
    static Result audit(MinecraftServer server,String dimension,GameImpactCredit.Pair pair,
                        List<NativeCollisionTelemetry.Contact> records,TargetDirections directions) {
        return prepare(server,dimension,pair,records,directions).direct;
    }
    static Prepared prepare(MinecraftServer server,String dimension,GameImpactCredit.Pair pair,
                            List<NativeCollisionTelemetry.Contact> records,TargetDirections directions){
        var cache=new HashMap<AuditKey,Check>();
        var witnesses=new HashMap<AuditKey,DirectionWitness>();
        var nativeBatch=new OwnerResolver.NativeAuditBatch(server,dimension);
        Result collected=collectWithBudget(pair,records,(contact,owner,remainingChecks)-> {
            int sourceSide=SableConsumerTargets.sourceSide(owner,contact,dimension);
            if(sourceSide==0)return new Check(List.of(),0,false,"UNVERIFIED","OWNER_FRAME_MISMATCH");
            boolean onA=sourceSide==1;
            var point=SableConsumerTargets.candidate(contact,onA);
            if(point==null)return new Check(List.of(),0,false,"UNVERIFIED","NATIVE_POINT_OR_FRAME_MISSING");
            var key=new AuditKey(owner,point,onA?contact.frameA():contact.frameB());
            var prior=cache.get(key);
            if(prior!=null){
                return directCheck(prior,0,directions,nativeBatch,contact,owner,point,witnesses.get(key));
            }
            var positions=SableConsumerTargets.cells(point);
            var found=new LinkedHashSet<GameImpactCredit.Target>();
            var cells=new HashMap<GameImpactCredit.Target,net.minecraft.core.BlockPos>();
            boolean uncertain=false;
            int checked=0,reused=0;
            for(var pos:positions){
                // Only a *new owner/cell* consumes a block lookup budget.
                // Every source point still passes the actual cached shape check.
                if(!nativeBatch.cached(owner,pos) && checked>=remainingChecks)
                    return new Check(List.of(),checked,true,"UNVERIFIED",
                            "R2_UNIQUE_CELL_BUDGET requested="+positions.size()
                            +" examinedNew="+checked+" remaining="+remainingChecks);
                var sampled=nativeBatch.inspect(owner,pos,point);
                if(sampled.newCell())checked++;else reused++;
                var evidence=sampled.result();
                if(evidence.verified()) {
                    var targetKey=evidence.key();
                    var target=new GameImpactCredit.Target(owner,targetKey.x()+","+targetKey.y()+","+targetKey.z(),targetKey.fingerprint());
                    found.add(target);cells.put(target,pos);
                }else if(evidence.status()!=OwnerResolver.Status.NO_TARGET&&!OwnerResolver.knownNoShape(evidence)&&!"CACHED_SHAPE_POINT_MISS".equals(evidence.detail()))uncertain=true;
            }
            Check result;
            if(!found.isEmpty())result=new Check(new ArrayList<>(found),checked,false,"R2_CONTACT_REGION",
                    "R2_SHAPE_VERIFIED reusedCells="+reused);
            else {
                // Sable native contacts are body-local. Projecting the other
                // witness through last-post poses silently mixes substeps.
                // R3 remains diagnostic-only until per-contact poses exist.
                result=new Check(List.of(),checked,false,"UNVERIFIED",
                        "R2_NO_SHAPE_R3_CROSS_PHASE_UNCALIBRATED");
            }
            // Only the unambiguous original shape result may be cached as a
            // direct target; per-record direction disambiguation is ephemeral.
            if(uncertain)result=new Check(result.targets(),result.inspected(),result.incomplete(),result.method(),result.detail(),null,false);
            cache.put(key,result);
            var witness=new DirectionWitness(cells,uncertain);witnesses.put(key,witness);
            return directCheck(result,checked,directions,nativeBatch,contact,owner,point,witness);
        }, RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE);
        var direct=withStats(collected,nativeBatch);
        // Direct targets are sealed independently of direction/geometry.
        for(var r:direct.regions()){
            if(r.a()!=null)directions.seed(r.a());
            if(r.b()!=null)directions.seed(r.b());
        }
        if(RogTrace.sample(Stage.R04,"DIRECT_SEEDS"))RogTrace.detail(Stage.R04,"DIRECT_SEEDS",
                "regions={} confirmedA={} confirmedB={} reservedA={} reservedB={} status={} authority=OWNER_LOCAL",
                direct.regions().size(),direct.trace().verifiedTargetsA(),direct.trace().verifiedTargetsB(),direct.reservedA(),direct.reservedB(),direct.status());
        return new Prepared(direct,()->expand(pair,records,directions,nativeBatch,direct),
                exact->expandPatch(pair,records,directions,nativeBatch,direct,exact));
    }
    private static Result expand(GameImpactCredit.Pair pair,List<NativeCollisionTelemetry.Contact> records,
                                 TargetDirections directions,OwnerResolver.NativeAuditBatch nativeBatch,Result collected){
        // A failed/over-budget owner may not be resurrected by speculative reads.
        if(collected.trace().failure()!=AuditFailure.NONE&&collected.trace().failure()!=AuditFailure.NO_VERIFIED_REGION)
            return collected;
        String[] owners={pair.a().stableKey(),pair.b().stableKey()};
        int[] remainingSurface={RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-collected.checksA(),RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-collected.checksB()};
        boolean[] surfaceReadLimited={false,false};
        int surfacePairChecks=0,surfaceMatches=0,surfaceRejected=0;
        var visitedRecords=new LinkedHashSet<>(records);
        var surfaceReader=shapeReader(nativeBatch,owners,remainingSurface,surfaceReadLimited);
            var surfaceEvidence=new java.util.ArrayList<BilateralContactSurface.Match>();
            int pairRemaining=RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE;
            var validFrames=new java.util.ArrayList<BilateralContactSurface.Frame>();
            if(remainingSurface[0]>0&&remainingSurface[1]>0){
                int ordinal=0;boolean calibrationLogged=false;
                for(var witness:visitedRecords){
                    int index=ordinal++;
                    if(witness==null){surfaceRejected++;continue;}
                    var frame=BilateralContactSurface.fromRecord(pair,witness,witness.serverTick(),directions.diagnosticPreFrames());
                    if(frame==null){
                        surfaceRejected++;
                        if(!calibrationLogged&&RogTrace.enabled()&&DiagnosticMode.debug()){
                            ContactPoseDiagnostics.observe(pair,witness,index,directions.diagnosticPreFrames(),nativeBatch);
                            calibrationLogged=true;
                        }
                        continue;
                    }
                    validFrames.add(frame);
                }
                if(!validFrames.isEmpty()){
                    // All native anchors sharing an exact effect pose/face
                    // contribute to ONE traversal. Never rescan the same
                    // connected component once per solver witness.
                    var found=BilateralContactSurface.reconstructAll(validFrames,surfaceReader,pairRemaining);
                    surfacePairChecks=found.checks();
                    // Incomplete means *some* component failed, not that all
                    // independently closed components lost their shape proof.
                    if(found.incomplete()||surfaceReadLimited[0]||surfaceReadLimited[1])surfaceRejected++;
                    surfaceMatches=found.matches().size();
                    surfaceEvidence.addAll(found.matches());
                }
            }
        var merged=mergeBilateralPairs(collected.regions(),surfaceEvidence);
        int ca=RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-remainingSurface[0],cb=RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-remainingSurface[1];
        var a=new HashSet<GameImpactCredit.Target>();var b=new HashSet<GameImpactCredit.Target>();
        for(var r:merged){if(r.a()!=null)a.add(r.a());if(r.b()!=null)b.add(r.b());}
        var t=collected.trace();
        var next=new Result(merged,merged.equals(collected.regions())?collected.status():a.isEmpty()||b.isEmpty()||collected.rejected()>0||collected.unknownA()>0||collected.unknownB()>0||!a.containsAll(collected.ambiguousA())||!b.containsAll(collected.ambiguousB())?Status.PARTIAL:Status.PROVED,
                ca,cb,collected.rejected(),a.isEmpty()?collected.methodA():"R2_BILATERAL",b.isEmpty()?collected.methodB():"R2_BILATERAL",
                new Trace(merged.isEmpty()?t.failure():AuditFailure.NONE,t.sourceIndex(),t.processedRecords(),remainingSurface[0],remainingSurface[1],
                        t.requestedA(),t.requestedB(),a.size(),b.size(),merged.size(),t.rejectedContacts(),
                        t.detail()+" bilateralPairs="+surfaceMatches+" surfaceChecks="+surfacePairChecks+" surfaceRejected="+surfaceRejected+
                                " surfaceBudgetA="+surfaceReadLimited[0]+" surfaceBudgetB="+surfaceReadLimited[1]),
                collected.ambiguousA(),collected.ambiguousB(),collected.unknownA(),collected.unknownB(),collected.groupsA(),collected.groupsB(),Set.of(),collected.sourceGroupsA(),collected.sourceGroupsB());
        int reservedA=next.reservedA(),reservedB=next.reservedB();
        if((long)a.size()+reservedA>ContactCreditLedger.MAX_TARGETS_PER_SIDE||
                (long)b.size()+reservedB>ContactCreditLedger.MAX_TARGETS_PER_SIDE)return withStats(new Result(collected.regions(),collected.status(),ca,cb,collected.rejected(),collected.methodA(),collected.methodB(),collected.trace(),collected.ambiguousA(),collected.ambiguousB(),collected.unknownA(),collected.unknownB(),collected.groupsA(),collected.groupsB(),Set.of(),collected.sourceGroupsA(),collected.sourceGroupsB()),nativeBatch);
        // Expanded blocks are material/force seeds, not independently proved move axes.
        for(var r:merged){if(r.a()!=null)directions.seed(r.a());if(r.b()!=null)directions.seed(r.b());}
        RogTrace.event(Stage.R04,"BILATERAL_COVERAGE","baseRegions={} finalRegions={} bilateralPairs={} surfaceChecks={} rejected={} reservedA={} reservedB={} authority=NONE",
                collected.regions().size(),merged.size(),surfaceMatches,surfacePairChecks,surfaceRejected,reservedA,reservedB);
        return withStats(next,nativeBatch);
    }
    private static BilateralContactSurface.ShapeReader shapeReader(OwnerResolver.NativeAuditBatch nativeBatch,
            String[] owners,int[] remainingSurface,boolean[] surfaceReadLimited){
        return new BilateralContactSurface.ShapeReader(){
                @Override public BilateralContactSurface.Cell get(int side,net.minecraft.core.BlockPos pos){
                    String key=owners[side];
                    if(!nativeBatch.cached(key,pos)&&remainingSurface[side]<=0){surfaceReadLimited[side]=true;if(RogTrace.sample(Stage.R04,"SHAPE_READ_CAP"))RogTrace.detail(Stage.R04,"SHAPE_READ_CAP","owner={} side={} allocated={} remaining={}",key,side,pos,remainingSurface[side]);return null;}
                    var item=nativeBatch.inspectCell(key,pos);
                    if(item.newCell())remainingSurface[side]--;
                    if(!item.result().verified()){if(RogTrace.sample(Stage.R04,"SHAPE_READ_REJECTED"))RogTrace.detail(Stage.R04,"SHAPE_READ_REJECTED","owner={} side={} allocated={} result={} detail={}",key,side,pos,item.result().status(),item.result().detail());return null;}
                    var id=item.result().key();
                    return new BilateralContactSurface.Cell(new GameImpactCredit.Target(key,
                            id.x()+","+id.y()+","+id.z(),id.fingerprint()),pos,nativeBatch.boxes(key,pos));
                }
                @Override public boolean budgetExhausted(){
                    return surfaceReadLimited[0] || surfaceReadLimited[1];
                }
                private boolean exteriorUnknown;
                @Override public boolean takeUncertainExterior(){boolean value=exteriorUnknown;exteriorUnknown=false;return value;}
                @Override public boolean unresolvedCell(int side,net.minecraft.core.BlockPos pos){
                    if(!nativeBatch.cached(owners[side],pos))return true;
                    var sample=nativeBatch.inspectCell(owners[side],pos).result();
                    return !OwnerResolver.knownNoShape(sample);
                }
                @Override public List<BilateralContactSurface.Cell> exteriorCells(int side,BilateralContactSurface.Cell cell,
                        net.minecraft.world.phys.AABB face,com.physicaldestroydeform.deform.MovePlan.SignedAxes inward){
                    var positions=BilateralContactSurface.exteriorCellPositions(cell,face,inward);
                    if(positions==null){exteriorUnknown=true;return null;}
                    var neighbors=new ArrayList<BilateralContactSurface.Cell>();
                    for(var pos:positions){
                        var candidate=get(side,pos);
                        if(surfaceReadLimited[side]||candidate==null&&unresolvedCell(side,pos)){exteriorUnknown=true;return null;}
                        if(candidate!=null)neighbors.add(candidate);
                    }
                    return neighbors;
                }
                @Override public boolean exposed(int side,FrameMath.Vec point,
                        com.physicaldestroydeform.deform.MovePlan.SignedAxes inward){
                    // A face between two blocks of the SAME structure is not
                    // an exterior contact. Unknown/unloaded cells fail closed.
                    var beyond=point.minus(new FrameMath.Vec(inward.x(),inward.y(),inward.z()).times(.001));
                    for(var cell:SableConsumerTargets.cells(beyond)){
                        var observed=get(side,cell);
                        if(surfaceReadLimited[side]){exteriorUnknown=true;return false;}
                        if(observed!=null){
                            if(nativeBatch.contains(owners[side],cell,beyond,1e-6)){if(RogTrace.sample(Stage.R04,"EXTERIOR_OCCUPIED"))RogTrace.detail(Stage.R04,"EXTERIOR_OCCUPIED","side={} owner={} cell={} beyond={}",side,owners[side],cell,beyond);return false;}
                        } else {
                            // Absent/unloaded is NOT proof of air: only an
                            // authoritative empty shape may leave this exposed.
                            var sample=nativeBatch.inspectCell(owners[side],cell).result();
                            if(!OwnerResolver.knownNoShape(sample)){exteriorUnknown=true;if(RogTrace.sample(Stage.R04,"EXTERIOR_UNKNOWN"))RogTrace.detail(Stage.R04,"EXTERIOR_UNKNOWN","side={} owner={} cell={} status={} detail={}",side,owners[side],cell,sample.status(),sample.detail());return false;}
                        }
                    }
                    return true;
                }
            };
    }
    /** Model-only expansion runs AFTER a real collision authorization. It cannot
     * supply the direct/approach predicate, a cross-owner edge or a move axis. */
    private static Result expandPatch(GameImpactCredit.Pair pair,List<NativeCollisionTelemetry.Contact> records,
            TargetDirections directions,OwnerResolver.NativeAuditBatch batch,Result direct,Result exact){
        if(direct.trace().failure()!=AuditFailure.NONE&&direct.trace().failure()!=AuditFailure.NO_VERIFIED_REGION)return exact;
        if(!exact.verified())return exact; // no independent authorized cell
        String[] owners={pair.a().stableKey(),pair.b().stableKey()};
        int[] remaining={RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-exact.checksA(),RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-exact.checksB()};
        boolean[] limited={false,false};
        var witness=new ArrayList<ContactFacePatch.Witness>();
        var unique=new LinkedHashSet<>(records);var pollCounts=new HashMap<Long,Integer>();
        for(var c:unique)if(c!=null&&c.pollId()>0)pollCounts.merge(c.pollId(),1,Integer::sum);
        for(var c:unique){
            if(c==null||pollCounts.getOrDefault(c.pollId(),0)<3)continue;
            var frame=BilateralContactSurface.patchFrame(pair,c,c.serverTick());
            if(frame!=null)witness.add(new ContactFacePatch.Witness(c,frame));
        }
        // Same event's shape cache and bounded work. Exact completed components
        // are immutable; model expansion never edits their opposing evidence.
        var model=ContactFacePatch.reconstruct(witness,shapeReader(batch,owners,remaining,limited),RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE);
        var candidate=mergePatch(exact,model);
        int ca=RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-remaining[0],cb=RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-remaining[1];
        var r=candidate;
        var t=r.trace();
        r=new Result(r.regions(),r.status(),ca,cb,r.rejected(),r.methodA(),r.methodB(),
                new Trace(t.failure(),t.sourceIndex(),t.processedRecords(),remaining[0],remaining[1],t.requestedA(),t.requestedB(),
                targets(r.regions(),true).size(),targets(r.regions(),false).size(),r.regions().size(),t.rejectedContacts(),
                t.detail()+" modelPairs="+model.pairs().size()+" patchSeeds="+r.patchSeeds().size()+" patchWork="+model.work()+" patchIncomplete="+model.incomplete()),
                r.ambiguousA(),r.ambiguousB(),r.unknownA(),r.unknownB(),r.groupsA(),r.groupsB(),r.patchSeeds(),r.sourceGroupsA(),r.sourceGroupsB(),r.reservedA(),r.reservedB());
        for(var seed:r.patchSeeds())directions.seed(seed); // no direction inferred by the patch
        RogTrace.event(Stage.R04,"CONTACT_COVERAGE","baseRegions={} exactRegions={} finalRegions={} modelPairs={} ownerLocalPatchSeeds={} patchWork={} patchIncomplete={} reservedA={} reservedB={} exactSubstep=UNAVAILABLE authority=GAME_PROXY",
                direct.regions().size(),exact.regions().size(),r.regions().size(),model.pairs().size(),r.patchSeeds().size(),model.work(),model.incomplete(),r.reservedA(),r.reservedB());
        return withStats(r,batch);
    }
    private static Set<GameImpactCredit.Target> targets(List<ContactCreditLedger.Region> regions,boolean a){
        var out=new HashSet<GameImpactCredit.Target>();for(var r:regions){var t=a?r.a():r.b();if(t!=null)out.add(t);}return out;
    }
    /** A game-patch peer set is the complete overlap set under the explicitly
     * approximate chart, NOT a list of newly proved native pairs. Keep existing
     * exact or direct evidence unchanged; mixed E is still withheld by the ledger. */
    static Result mergePatch(Result exact,ContactFacePatch.Result model){
        if(model.pairs().isEmpty())return exact;
        var a=targets(exact.regions(),true);var b=targets(exact.regions(),false);
        var peersA=new HashMap<GameImpactCredit.Target,Set<GameImpactCredit.Target>>();var peersB=new HashMap<GameImpactCredit.Target,Set<GameImpactCredit.Target>>();
        for(var match:model.pairs()){
            if(!a.contains(match.a()))peersA.computeIfAbsent(match.a(),k->new HashSet<>()).add(match.b());
            if(!b.contains(match.b()))peersB.computeIfAbsent(match.b(),k->new HashSet<>()).add(match.a());
        }
        if((long)a.size()+peersA.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE||(long)b.size()+peersB.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE
                ||(long)exact.regions().size()+peersA.size()+peersB.size()>ContactCreditLedger.MAX_REGIONS)return exact;
        var merged=new ArrayList<>(exact.regions());var patch=new HashSet<GameImpactCredit.Target>();
        var order=java.util.Comparator.comparing(GameImpactCredit.Target::ownerKey).thenComparing(GameImpactCredit.Target::localBlock).thenComparing(GameImpactCredit.Target::fingerprint);
        for(boolean onA:new boolean[]{true,false}){
            var peers=onA?peersA:peersB;
            for(var target:peers.keySet().stream().sorted(order).toList()){
                var candidates=peers.get(target);
                // Do not invent an E when a complex model has more possible peers
                // than the existing per-region material-only evidence contract.
                var list=candidates.size()<=SableConsumerTargets.MAX_CANDIDATES?List.copyOf(candidates):List.<GameImpactCredit.Target>of();
                merged.add(new ContactCreditLedger.Region(onA?target:null,onA?null:target,list,true));patch.add(target);
            }
        }
        if(patch.isEmpty())return exact;
        var ga=refineGroups(exact.groupsA(),exact.sourceGroupsA(),model.coveredA(),patch);
        var gb=refineGroups(exact.groupsB(),exact.sourceGroupsB(),model.coveredB(),patch);
        var next=new Result(merged,Status.PARTIAL,exact.checksA(),exact.checksB(),exact.rejected(),"R2_AND_GAME_PATCH","R2_AND_GAME_PATCH",exact.trace(),
                exact.ambiguousA(),exact.ambiguousB(),exact.unknownA(),exact.unknownB(),ga,gb,patch,exact.sourceGroupsA(),exact.sourceGroupsB());
        if((long)a.size()+peersA.size()+next.reservedA()>ContactCreditLedger.MAX_TARGETS_PER_SIDE||
                (long)b.size()+peersB.size()+next.reservedB()>ContactCreditLedger.MAX_TARGETS_PER_SIDE)return exact;
        return next;
    }
    /** Approximate coverage can resolve only candidates from its OWN paired
     * witness. An unrelated approximate seed never erases native ambiguity. */
    static List<List<GameImpactCredit.Target>> refineGroups(List<List<GameImpactCredit.Target>> original,
            Map<NativeCollisionTelemetry.Contact,List<GameImpactCredit.Target>> sources,
            Map<NativeCollisionTelemetry.Contact,Set<GameImpactCredit.Target>> coverage,Set<GameImpactCredit.Target> patch){
        if(sources.isEmpty())return original;
        var byEvidence=new HashMap<NativeCollisionTelemetry.Contact,Set<GameImpactCredit.Target>>();
        coverage.forEach((k,v)->byEvidence.computeIfAbsent(evidenceKey(k),x->new HashSet<>()).addAll(v));
        var out=new ArrayList<List<GameImpactCredit.Target>>();
        for(var item:sources.entrySet()){
            var covered=byEvidence.getOrDefault(item.getKey(),Set.of());
            out.add(item.getValue().stream().filter(t->!patch.contains(t)||!covered.contains(t)).toList());
        }
        return out;
    }
    private static Result withStats(Result r,OwnerResolver.NativeAuditBatch batch){
        var t=r.trace();var stats=batch.stats();
        return new Result(r.regions(),r.status(),r.checksA(),r.checksB(),r.rejected(),r.methodA(),r.methodB(),
                new Trace(t.failure(),t.sourceIndex(),t.processedRecords(),t.remainingA(),t.remainingB(),t.requestedA(),t.requestedB(),
                        t.verifiedTargetsA(),t.verifiedTargetsB(),t.regionCount(),t.rejectedContacts(),t.detail()+
                        " auditedUniqueOwnerCells="+stats.newCells()+" reusedCellReads="+stats.reusedCells()+
                        " shapePointTests="+stats.pointTests()+" testedShapeBoxes="+stats.testedBoxes()),
                r.ambiguousA(),r.ambiguousB(),r.unknownA(),r.unknownB(),r.groupsA(),r.groupsB(),r.patchSeeds(),r.sourceGroupsA(),r.sourceGroupsB(),r.reservedA(),r.reservedB());
    }
    /** Keep all native evidence, including a genuine missing opponent. Add
     * only certified pair relations not already covered by native evidence.
     * A cap must never truncate an existing direct seed set. */
    static List<ContactCreditLedger.Region> mergeBilateralPairs(
            List<ContactCreditLedger.Region> current,List<BilateralContactSurface.Match> verified){
        if(verified.isEmpty())return current;
        var kept=new LinkedHashSet<>(current);
        var presentA=new HashSet<GameImpactCredit.Target>();var presentB=new HashSet<GameImpactCredit.Target>();
        var coveredA=new HashMap<GameImpactCredit.Target,Set<GameImpactCredit.Target>>();
        var coveredB=new HashMap<GameImpactCredit.Target,Set<GameImpactCredit.Target>>();
        for(var r:current){
            if(r.a()!=null){presentA.add(r.a());if(r.b()!=null||r.opposingCandidates().size()==1)coveredA.computeIfAbsent(r.a(),k->new HashSet<>()).addAll(r.b()==null?r.opposingCandidates():List.of(r.b()));}
            if(r.b()!=null){presentB.add(r.b());if(r.a()!=null||r.opposingCandidates().size()==1)coveredB.computeIfAbsent(r.b(),k->new HashSet<>()).addAll(r.a()==null?r.opposingCandidates():List.of(r.a()));}
        }
        for(var match:new LinkedHashSet<>(verified)){
            presentA.add(match.a());presentB.add(match.b());
            if(coveredA.computeIfAbsent(match.a(),k->new HashSet<>()).add(match.b()))
                kept.add(new ContactCreditLedger.Region(match.a(),null,List.of(match.b())));
            if(coveredB.computeIfAbsent(match.b(),k->new HashSet<>()).add(match.a()))
                kept.add(new ContactCreditLedger.Region(null,match.b(),List.of(match.a())));
            if(presentA.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE||presentB.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE||kept.size()>ContactCreditLedger.MAX_REGIONS)return current;
        }
        return List.copyOf(kept);
    }
    private record AuditKey(String owner, FrameMath.Vec point,
                            NativeCollisionTelemetry.OwnerFrame frame) {}
    private record DirectionWitness(java.util.Map<GameImpactCredit.Target,net.minecraft.core.BlockPos> cells,boolean uncertain) {}
    private static Check directCheck(Check shape,int inspected,TargetDirections directions,
                                     OwnerResolver.NativeAuditBatch batch,NativeCollisionTelemetry.Contact contact,
                                     String owner,FrameMath.Vec point,DirectionWitness witness){
        var direct=shape.directTarget();
        if(direct==null&&shape.completeCandidates()){
            // Native local normal is identity evidence only. Never grant a move
            // from it without the independent PRE/approach direction verifier.
            var exact=new ArrayList<GameImpactCredit.Target>();
            for(var entry:witness.cells().entrySet())if(batch.contains(owner,entry.getValue(),point,1e-6))exact.add(entry.getKey());
            if(exact.size()==1)direct=exact.getFirst();
            else {
                var axis=SableConsumerTargets.identityAxis(contact,SableConsumerTargets.sourceSide(owner,contact,contact.dimension())==1);
                if(axis!=null){
                    var faces=new ArrayList<GameImpactCredit.Target>();
                    for(var entry:witness.cells().entrySet())if((exact.isEmpty()||exact.contains(entry.getKey()))&&batch.targetFace(owner,entry.getValue(),point,axis))faces.add(entry.getKey());
                    if(faces.size()==1)direct=faces.getFirst();
                }
            }
        }
        if(directions.active()){
            var axes=directions.axis(owner,contact);
            var faces=new ArrayList<GameImpactCredit.Target>();
            if(axes!=null)for(var entry:witness.cells().entrySet())if(batch.targetFace(owner,entry.getValue(),point,axes))faces.add(entry.getKey());
            directions.observe(witness.cells().keySet(),direct!=null&&!faces.contains(direct)?List.of():faces,axes,witness.uncertain()||direct!=null&&!faces.contains(direct));
            // Stronger direction evidence may resolve identity, never override a
            // conflicting already-confirmed independent shape target.
            if(direct==null&&!witness.uncertain()&&faces.size()==1)direct=faces.getFirst();
        }
        if(RogTrace.sample(Stage.R04,direct!=null?"DIRECT_TARGET_RESOLVED":"DIRECT_TARGET_AMBIGUOUS"))RogTrace.detail(Stage.R04,
                direct!=null?"DIRECT_TARGET_RESOLVED":"DIRECT_TARGET_AMBIGUOUS","owner={} point={} candidates={} direct={} exhaustive={}",
                owner,point,shape.targets().size(),direct,shape.completeCandidates());
        return new Check(shape.targets(),inspected,shape.incomplete(),shape.method(),shape.detail(),direct,shape.completeCandidates());
    }
    /** Testable no-world-evidence policy: never approve a side from a guessed owner. */
    public static Result collect(GameImpactCredit.Pair pair,
                                 List<NativeCollisionTelemetry.Contact> records,Inspector inspector) {
        return collectWithBudget(pair,records,inspector,MAX_CHECKS_PER_SIDE);
    }
    /** Runtime audits shape cells under a separate budget from bounded targets. */
    static Result collectWithBudget(GameImpactCredit.Pair pair,
                                    List<NativeCollisionTelemetry.Contact> records,
                                    Inspector inspector,int shapeBudgetPerSide) {
        Objects.requireNonNull(pair);Objects.requireNonNull(records);Objects.requireNonNull(inspector);
        if(shapeBudgetPerSide<1 || shapeBudgetPerSide>RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE)
            throw new IllegalArgumentException("invalid shape audit budget");
        var regions=new LinkedHashSet<ContactCreditLedger.Region>();
        var targetsA=new LinkedHashSet<GameImpactCredit.Target>();
        var targetsB=new LinkedHashSet<GameImpactCredit.Target>();
        var ambiguousA=new HashSet<GameImpactCredit.Target>();var ambiguousB=new HashSet<GameImpactCredit.Target>();
        var groupsA=new java.util.LinkedHashMap<NativeCollisionTelemetry.Contact,List<GameImpactCredit.Target>>();
        var groupsB=new java.util.LinkedHashMap<NativeCollisionTelemetry.Contact,List<GameImpactCredit.Target>>();
        var unknownA=new HashMap<AuditKey,Integer>();var unknownB=new HashMap<AuditKey,Integer>();
        var potentialA=new HashSet<GameImpactCredit.Target>();var potentialB=new HashSet<GameImpactCredit.Target>();
        long unknownCountA=0,unknownCountB=0;
        int checkedA=0,checkedB=0,skipped=0,processed=0,uncorroborated=0,emptyChecks=0;
        boolean exceededA=false,exceededB=false,regionCompacted=false;
        boolean incompleteEverA=false,incompleteEverB=false;
        String incompleteDetailA="NONE",incompleteDetailB="NONE";
        int overflowIndex=-1,overflowCountA=0,overflowCountB=0;
        String firstFrameMismatch="NONE";
        int witnessSeparated=0,worldProjectionUnavailable=0;
        String firstEmptyA="NONE",firstEmptyB="NONE";
        String methodA="UNVERIFIED",methodB="UNVERIFIED";
        for(var record:records) {
            int index=processed++;
            // Independent Rapier witness points need not coincide. A source
            // record is rejected only for invalid native fields, not distance.
            if(!SableConsumerTargets.sourceRecordValid(record)
                    || !pair.dimension().equals(record.dimension())) {
                skipped++;uncorroborated++;continue;
            }
            var residue=SableConsumerTargets.frameResidual(record);
            if("WITNESS_SEPARATED_GT_0.25".equals(residue.reason()))witnessSeparated++;
            if("EFFECT_POSE_UNAVAILABLE".equals(residue.reason()))worldProjectionUnavailable++;
            if(firstFrameMismatch.equals("NONE") && "WITNESS_SEPARATED_GT_0.25".equals(residue.reason())) {
                firstFrameMismatch="nativeRecord="+index+" frameReason="+residue.reason()
                        +" rawA=("+record.contactAX()+","+record.contactAY()+","+record.contactAZ()+")"
                        +" rawB=("+record.contactBX()+","+record.contactBY()+","+record.contactBZ()+")"
                        +" nativeFrameA="+record.frameA()+" nativeFrameB="+record.frameB()
                        +" worldA="+residue.a()+" worldB="+residue.b()
                        +" worldDelta="+residue.delta()+" distance="+residue.distance();
            }
            int remainA=shapeBudgetPerSide-checkedA,remainB=shapeBudgetPerSide-checkedB;
            Check a=exceededA?new Check(List.of(),0,false,"UNVERIFIED","TARGET_LIMIT_A_SIDE_QUARANTINED")
                    :SableConsumerTargets.sourceSide(pair.a().stableKey(),record,record.dimension())==0
                            ?new Check(List.of(),0,false,"UNVERIFIED","OWNER_FRAME_MISMATCH_A")
                            :inspector.verify(record,pair.a().stableKey(),remainA);
            Check b=exceededB?new Check(List.of(),0,false,"UNVERIFIED","TARGET_LIMIT_B_SIDE_QUARANTINED")
                    :SableConsumerTargets.sourceSide(pair.b().stableKey(),record,record.dimension())==0
                            ?new Check(List.of(),0,false,"UNVERIFIED","OWNER_FRAME_MISMATCH_B")
                            :inspector.verify(record,pair.b().stableKey(),remainB);
            int askedA=a==null?0:a.inspected(),askedB=b==null?0:b.inspected();
            // Source validity is checked above. If the local audit cannot
            // complete for one owner, quarantine ONLY that owner; the other
            // owner's confirmed cells still belong to the same source event.
            boolean incompleteA=a==null||a.incomplete();
            boolean incompleteB=b==null||b.incomplete();
            if(incompleteA) {
                incompleteEverA=true;
                if(incompleteDetailA.equals("NONE"))incompleteDetailA=a==null?"NULL":a.detail();
                if(!exceededA){exceededA=true;overflowIndex=index;}
            }
            if(incompleteB) {
                incompleteEverB=true;
                if(incompleteDetailB.equals("NONE"))incompleteDetailB=b==null?"NULL":b.detail();
                if(!exceededB){exceededB=true;overflowIndex=index;}
            }
            checkedA+=askedA; checkedB+=askedB;
            if(incompleteA || incompleteB) {
                if(incompleteA) a=new Check(List.of(),0,false,"UNVERIFIED","SIDE_A_INCOMPLETE");
                if(incompleteB) b=new Check(List.of(),0,false,"UNVERIFIED","SIDE_B_INCOMPLETE");
            }
            if(checkedA>shapeBudgetPerSide || checkedB>shapeBudgetPerSide)
                return traced(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED",
                        checkedA>shapeBudgetPerSide?AuditFailure.CHECK_BUDGET_A:AuditFailure.CHECK_BUDGET_B,
                        index,processed,remainA,remainB,askedA,askedB,targetsA.size(),targetsB.size(),regions.size(),
                        "inspector_claim_over_budget");
            if(a.targets().isEmpty()&&b.targets().isEmpty())emptyChecks++;
            if(a.targets().isEmpty() && firstEmptyA.equals("NONE"))firstEmptyA=a.detail();
            if(b.targets().isEmpty() && firstEmptyB.equals("NONE"))firstEmptyB=b.detail();
            if(!a.targets().isEmpty())methodA=mergeMethod(methodA,a.method());
            if(!b.targets().isEmpty())methodB=mergeMethod(methodB,b.method());
            for(var target:a.targets()) {
                if(!target.ownerKey().equals(pair.a().stableKey()))
                    return traced(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                            "UNVERIFIED","UNVERIFIED",AuditFailure.OWNER_MISMATCH_A,
                            index,processed,remainA,remainB,askedA,askedB,targetsA.size(),targetsB.size(),regions.size(),
                            "target_owner="+target.ownerKey());
                if(target.equals(a.directTarget())){targetsA.add(target);potentialA.add(target);}
            }
            for(var target:b.targets()) {
                if(!target.ownerKey().equals(pair.b().stableKey()))
                    return traced(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                            "UNVERIFIED","UNVERIFIED",AuditFailure.OWNER_MISMATCH_B,
                            index,processed,remainA,remainB,askedA,askedB,targetsA.size(),targetsB.size(),regions.size(),
                            "target_owner="+target.ownerKey());
                if(target.equals(b.directTarget())){targetsB.add(target);potentialB.add(target);}
            }
            if(!exceededA)unknownCountA+=reserveAmbiguity(a,record,pair.a().stableKey(),ambiguousA,potentialA,unknownA,groupsA);
            if(!exceededB)unknownCountB+=reserveAmbiguity(b,record,pair.b().stableKey(),ambiguousB,potentialB,unknownB,groupsB);
            long countA=potentialA.size()+unknownCountA,countB=potentialB.size()+unknownCountB;
            // If one side exceeds the bounded target budget, NEVER concentrate its Q on
            // an arbitrary subset. Quarantine that entire side, continue
            // auditing the opposite side, and leave the bad side's Q unused.
            if(countA>ContactCreditLedger.MAX_TARGETS_PER_SIDE && !exceededA) {
                exceededA=true;overflowIndex=index;overflowCountA=(int)Math.min(Integer.MAX_VALUE,countA);
            }
            if(countB>ContactCreditLedger.MAX_TARGETS_PER_SIDE && !exceededB) {
                exceededB=true;overflowIndex=index;overflowCountB=(int)Math.min(Integer.MAX_VALUE,countB);
            }
            if(exceededA&&exceededB)
                return traced(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED",
                        incompleteEverA&&incompleteEverB ? AuditFailure.SIDE_A_AND_B_INCOMPLETE :
                                incompleteEverA ? AuditFailure.SIDE_A_INCOMPLETE :
                                incompleteEverB ? AuditFailure.SIDE_B_INCOMPLETE : AuditFailure.TARGET_LIMIT_A,
                        index,processed,remainA,remainB,askedA,askedB,targetsA.size(),targetsB.size(),regions.size(),
                        "both_sides_unverifiable capacity="+ContactCreditLedger.MAX_TARGETS_PER_SIDE
                                +" incompleteA="+incompleteDetailA+" incompleteB="+incompleteDetailB);
            if(!exceededA && !exceededB && !regionCompacted) {
                if(a.directTarget()!=null&&b.directTarget()!=null)regions.add(new ContactCreditLedger.Region(a.directTarget(),b.directTarget()));
                else {
                    if(a.directTarget()!=null)regions.add(new ContactCreditLedger.Region(a.directTarget(),null,b.opposingCandidates()));
                    if(b.directTarget()!=null)regions.add(new ContactCreditLedger.Region(null,b.directTarget(),a.opposingCandidates()));
                }
            }
            if(a.targets().isEmpty()&&b.targets().isEmpty())skipped++;
            if(regions.size()>ContactCreditLedger.MAX_REGIONS) {
                // Avoid inventing cross-side pairs or losing independently
                // verified block identities. Per-side singleton regions are
                // sufficient for the ONE common Q budget; <=32 per owner.
                regions.clear();
                regionCompacted=true;
            }
        }
        if(exceededA || exceededB || regionCompacted) {
            regions.clear();
            if(!exceededA)for(var target:targetsA)regions.add(new ContactCreditLedger.Region(target,null));
            if(!exceededB)for(var target:targetsB)regions.add(new ContactCreditLedger.Region(null,target));
            if(exceededA){methodA="UNVERIFIED";ambiguousA.clear();unknownA.clear();groupsA.clear();}
            if(exceededB){methodB="UNVERIFIED";ambiguousB.clear();unknownB.clear();groupsB.clear();}
        }
        AuditFailure failure=exceededA?(incompleteEverA?AuditFailure.SIDE_A_INCOMPLETE:AuditFailure.TARGET_LIMIT_A):
                exceededB?(incompleteEverB?AuditFailure.SIDE_B_INCOMPLETE:AuditFailure.TARGET_LIMIT_B):
                regionCompacted?AuditFailure.REGION_LIMIT:regions.isEmpty()?(uncorroborated==records.size()&& !records.isEmpty()?
                        AuditFailure.UNCORROBORATED:AuditFailure.NO_VERIFIED_REGION):AuditFailure.NONE;
        // A valid one-sided Q reservation is PARTIAL, even if every record
        // was inspected. Never describe a missing source owner as PROVED.
        boolean hasA=regions.stream().anyMatch(r->r.a()!=null);
        boolean hasB=regions.stream().anyMatch(r->r.b()!=null);
        Status status=regions.isEmpty()?Status.NO_VERIFIED_REGION:
                skipped==0 && !exceededA && !exceededB && !regionCompacted && hasA && hasB
                        && unknownCountA==0 && unknownCountB==0 && targetsA.containsAll(ambiguousA) && targetsB.containsAll(ambiguousB)
                        ?Status.PROVED:Status.PARTIAL;
        var base=traced(new ArrayList<>(regions),status,checkedA,checkedB,skipped,
                regions.isEmpty()?"UNVERIFIED":methodA,regions.isEmpty()?"UNVERIFIED":methodB,
                failure,overflowIndex>=0?overflowIndex:-1,processed,shapeBudgetPerSide-checkedA,shapeBudgetPerSide-checkedB,
                0,0,targetsA.size(),targetsB.size(),regions.size(),
                "invalidSourceRecords="+uncorroborated+" witnessSeparated="+witnessSeparated+
                " worldProjectionUnavailable="+worldProjectionUnavailable+
                " noShapeContactRecords="+emptyChecks+
                " firstEmptyA="+firstEmptyA+" firstEmptyB="+firstEmptyB+
                " incompleteA="+incompleteDetailA+" incompleteB="+incompleteDetailB+
                " overflowA="+exceededA+" overflowB="+exceededB+
                " overflowCountA="+overflowCountA+" overflowCountB="+overflowCountB+
                " compactedRegions="+regionCompacted+
                " firstFrameMismatch={"+firstFrameMismatch+"}");
        return new Result(base.regions(),base.status(),base.checksA(),base.checksB(),base.rejected(),base.methodA(),base.methodB(),base.trace(),
                ambiguousA,ambiguousB,exceededA?0:Math.toIntExact(unknownCountA),exceededB?0:Math.toIntExact(unknownCountB),new ArrayList<>(groupsA.values()),new ArrayList<>(groupsB.values()),Set.of(),groupsA,groupsB);
    }
    private static int reserveAmbiguity(Check check,NativeCollisionTelemetry.Contact record,String owner,
                                         Set<GameImpactCredit.Target> ambiguous,Set<GameImpactCredit.Target> potential,Map<AuditKey,Integer> unknown,
                                         Map<NativeCollisionTelemetry.Contact,List<GameImpactCredit.Target>> groups){
        if(check.directTarget()!=null)return 0;
        if(check.completeCandidates()){
            ambiguous.addAll(check.targets());potential.addAll(check.targets());
            groups.putIfAbsent(evidenceKey(record),check.targets());return 0;
        }
        int side=SableConsumerTargets.sourceSide(owner,record,record.dimension());
        if(side==0)return unknown.putIfAbsent(new AuditKey(owner,null,null),ContactCreditLedger.MAX_TARGETS_PER_SIDE)==null?ContactCreditLedger.MAX_TARGETS_PER_SIDE:0;
        var point=SableConsumerTargets.candidate(record,side==1);
        var cells=SableConsumerTargets.cells(point);
        // Unknown native cells are NOT made into damage targets. Reserve at most
        // the bounded number of possible lattice cells for this distinct point.
        int upper=cells.isEmpty()?ContactCreditLedger.MAX_TARGETS_PER_SIDE:cells.size();
        return unknown.putIfAbsent(new AuditKey(owner,point,side==1?record.frameA():record.frameB()),upper)==null?upper:0;
    }
    /** Same raw evidence in this capture is not another independent contact.
     * Native scalar and cap flag are pair/poll metadata, not feature identity. */
    private static NativeCollisionTelemetry.Contact evidenceKey(NativeCollisionTelemetry.Contact c){
        return new NativeCollisionTelemetry.Contact(c.idA(),c.idB(),0,c.ax(),c.ay(),c.az(),c.bx(),c.by(),c.bz(),
                c.contactAX(),c.contactAY(),c.contactAZ(),c.contactBX(),c.contactBY(),c.contactBZ(),
                c.dimension(),c.serverTick(),c.frameA(),c.frameB(),false,c.runtimeStamp(),c.pollId());
    }
    /** Maximum number of ADDITIONAL distinct blocks the observed complete
     * records could represent. No matching result becomes a damage seed.
     * An iterative augmenting path avoids stack growth on adversarial inputs. */
    static int additionalReservation(List<List<GameImpactCredit.Target>> groups,Set<GameImpactCredit.Target> confirmed){
        var matched=new HashMap<GameImpactCredit.Target,Integer>();
        int work=0,cap=RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE*SableConsumerTargets.MAX_CANDIDATES;
        for(int start=0;start<groups.size();start++){
            var queue=new java.util.ArrayDeque<Integer>();queue.add(start);
            var visited=new HashSet<Integer>();visited.add(start);
            var parent=new HashMap<GameImpactCredit.Target,Integer>();
            var incoming=new HashMap<Integer,GameImpactCredit.Target>();
            GameImpactCredit.Target end=null;
            while(!queue.isEmpty()&&end==null){
                int node=queue.removeFirst();
                for(var candidate:groups.get(node)){
                    if(++work>cap){
                        // A partial maximum matching is a lower bound, not a
                        // safe reservation. Fall back to the proved upper bound.
                        var union=new HashSet<GameImpactCredit.Target>();
                        for(var group:groups)for(var target:group)if(!confirmed.contains(target))union.add(target);
                        int bound=Math.min(groups.size(),union.size());
                        if(RogTrace.sample(Stage.R04,"RESERVATION_MATCH_BOUND"))RogTrace.detail(Stage.R04,"RESERVATION_MATCH_BOUND","work={} cap={} groups={} union={} partialMatching={} reserveUpperBound={}",work,cap,groups.size(),union.size(),matched.size(),bound);
                        return bound;
                    }
                    if(confirmed.contains(candidate)||parent.putIfAbsent(candidate,node)!=null)continue;
                    var prior=matched.get(candidate);
                    if(prior==null){end=candidate;break;}
                    if(visited.add(prior)){incoming.put(prior,candidate);queue.addLast(prior);}
                }
            }
            while(end!=null){int node=parent.get(end);matched.put(end,node);end=incoming.get(node);}
        }
        return matched.size();
    }
    private static Result traced(List<ContactCreditLedger.Region> regions,Status status,
                                 int checkedA,int checkedB,int skipped,String methodA,String methodB,
                                 AuditFailure reason,int recordIndex,int processed,int remainingA,int remainingB,
                                 int askedA,int askedB,int targetsA,int targetsB,int regionCount,String detail) {
        return new Result(regions,status,checkedA,checkedB,skipped,methodA,methodB,
                new Trace(reason,recordIndex,processed,remainingA,remainingB,
                        askedA,askedB,targetsA,targetsB,regionCount,skipped,detail));
    }
    private static String mergeMethod(String existing,String next) {
        if(existing.equals("UNVERIFIED"))return next;
        return existing.equals(next)?existing:"R2_R3_MIXED_REGION";
    }
}

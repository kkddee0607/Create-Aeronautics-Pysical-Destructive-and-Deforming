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
                         Trace trace) {
        public Result {regions=List.copyOf(regions);Objects.requireNonNull(trace);}
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
    /** Runtime inspector: Sable-consumer local R2 shape cells; independent R3
     * only when R2 finds no block. Multiple real contact cells are preserved. */
    public static Result audit(MinecraftServer server,String dimension,GameImpactCredit.Pair pair,
                               List<NativeCollisionTelemetry.Contact> records) {
        return audit(server,dimension,pair,records,new TargetDirections());
    }
    static Result audit(MinecraftServer server,String dimension,GameImpactCredit.Pair pair,
                        List<NativeCollisionTelemetry.Contact> records,TargetDirections directions) {
        // Repeated identical native points share shape audits, not credit.
        var cache=new HashMap<AuditKey,Check>();
        var witnesses=new HashMap<AuditKey,DirectionWitness>();
        var nativeBatch=new OwnerResolver.NativeAuditBatch(server,dimension);
        var ownerOffsets=new HashMap<String,net.minecraft.core.BlockPos>();
        var inconsistentOffsets=new java.util.HashSet<String>();
        Result collected=collectWithBudget(pair,records,(contact,owner,remainingChecks)-> {
            int sourceSide=SableConsumerTargets.sourceSide(owner,contact,dimension);
            if(sourceSide==0)return new Check(List.of(),0,false,"UNVERIFIED","OWNER_FRAME_MISMATCH");
            boolean onA=sourceSide==1;
            var point=SableConsumerTargets.candidate(contact,onA);
            if(point==null)return new Check(List.of(),0,false,"UNVERIFIED","NATIVE_POINT_OR_FRAME_MISSING");
            var key=new AuditKey(owner,point,onA?contact.frameA():contact.frameB());
            var prior=cache.get(key);
            if(prior!=null){
                var direct=prior.directTarget();
                if(directions.active()){
                    var face=observeDirection(directions,nativeBatch,contact,owner,point,witnesses.get(key));
                    // A new native record at the same point can have different
                    // normals. Reuse the shape witness only, not its pairing.
                    if(prior.targets().size()!=1)direct=face;
                }
                return new Check(prior.targets(),0,false,prior.method(),prior.detail(),direct,prior.completeCandidates());
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
                    var offset=new net.minecraft.core.BlockPos(pos.getX()-targetKey.x(),
                            pos.getY()-targetKey.y(),pos.getZ()-targetKey.z());
                    var previous=ownerOffsets.putIfAbsent(owner,offset);
                    if(previous!=null && !previous.equals(offset))inconsistentOffsets.add(owner);
                    var target=new GameImpactCredit.Target(owner,targetKey.x()+","+targetKey.y()+","+targetKey.z(),targetKey.fingerprint());
                    found.add(target);if(directions.active())cells.put(target,pos);
                }else if(evidence.status()!=OwnerResolver.Status.NO_TARGET&&!java.util.Set.of("CACHED_SHAPE_POINT_MISS","PLOT_SHAPE_EMPTY","WORLD_SHAPE_EMPTY","BLOCK_MISSING_OR_AIR").contains(evidence.detail()))uncertain=true;
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
            if(directions.active()){
                var witness=new DirectionWitness(cells,uncertain);witnesses.put(key,witness);
                var face=observeDirection(directions,nativeBatch,contact,owner,point,witness);
                if(face!=null&&result.directTarget()==null)
                    result=new Check(result.targets(),result.inspected(),result.incomplete(),result.method(),result.detail(),face,result.completeCandidates());
            }
            return result;
        }, RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE);
        // Each evidenced source-local surface may contribute real shape-proven
        // block targets, not only one rectangle from four corner witnesses.
        // The original native pair/tick keeps exactly ONE shared F/Q envelope.
        int expandedA=0, expandedB=0, attemptedA=0, attemptedB=0;
        int pairedPatchA=0,pairedPatchB=0;
        int pairProofTestsA=0,pairProofTestsB=0;
        boolean pairBudgetA=false,pairBudgetB=false;
        int shapeChecksA=0, shapeChecksB=0;
        String patchStatus="NOT_ELIGIBLE";
        // One owner may be incomplete while the opposite source-local owner
        // is independently proved. Never discard that already-safe face.
        if(collected.verified()) {
            var joined=new LinkedHashSet<>(collected.regions());
            String[] owners={pair.a().stableKey(),pair.b().stableKey()};
            var pairedSurfaces=new ContactFacePatch.Result[2];
            StringBuilder descriptions=new StringBuilder();
            // originFix: independently reconstruct connected, actually opposing
            // VoxelShape faces from each witnessed native contact. A solver
            // corner is only an ANCHOR, never an assumed whole-face polygon.
            // This is material/Layer-0 evidence, not move-direction authority.
            int[] remainingSurface={RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-collected.checksA()-shapeChecksA,
                    RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-collected.checksB()-shapeChecksB};
            boolean[] surfaceReadLimited={false,false};
            int surfacePairChecks=0,surfaceMatches=0,surfaceRejected=0;
            java.util.Set<NativeCollisionTelemetry.Contact> visitedRecords=new java.util.LinkedHashSet<>(records);
            var surfaceReader=new BilateralContactSurface.ShapeReader(){
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
                @Override public boolean unresolvedCell(int side,net.minecraft.core.BlockPos pos){
                    var sample=nativeBatch.inspectCell(owners[side],pos).result();
                    return sample.status()!=OwnerResolver.Status.BLOCK_UNAVAILABLE ||
                            !java.util.Set.of("BLOCK_MISSING_OR_AIR","WORLD_SHAPE_EMPTY","PLOT_SHAPE_EMPTY")
                                    .contains(sample.detail());
                }
                @Override public boolean exposed(int side,FrameMath.Vec point,
                        com.physicaldestroydeform.deform.MovePlan.SignedAxes inward){
                    // A face between two blocks of the SAME structure is not
                    // an exterior contact. Unknown/unloaded cells fail closed.
                    var beyond=point.minus(new FrameMath.Vec(inward.x(),inward.y(),inward.z()).times(.001));
                    for(var cell:SableConsumerTargets.cells(beyond)){
                        var observed=get(side,cell);
                        if(surfaceReadLimited[side])return false;
                        if(observed!=null){
                            if(nativeBatch.contains(owners[side],cell,beyond,1e-6)){if(RogTrace.sample(Stage.R04,"EXTERIOR_OCCUPIED"))RogTrace.detail(Stage.R04,"EXTERIOR_OCCUPIED","side={} owner={} cell={} beyond={}",side,owners[side],cell,beyond);return false;}
                        } else {
                            // Absent/unloaded is NOT proof of air: only an
                            // authoritative empty shape may leave this exposed.
                            var sample=nativeBatch.inspectCell(owners[side],cell).result();
                            if(sample.status()!=OwnerResolver.Status.BLOCK_UNAVAILABLE||
                                    !java.util.Set.of("BLOCK_MISSING_OR_AIR","WORLD_SHAPE_EMPTY","PLOT_SHAPE_EMPTY")
                                        .contains(sample.detail())){if(RogTrace.sample(Stage.R04,"EXTERIOR_UNKNOWN"))RogTrace.detail(Stage.R04,"EXTERIOR_UNKNOWN","side={} owner={} cell={} status={} detail={}",side,owners[side],cell,sample.status(),sample.detail());return false;}
                        }
                    }
                    return true;
                }
            };
            var surfaceEvidence=new java.util.ArrayList<BilateralContactSurface.Match>();
            int pairRemaining=RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE;
            var validFrames=new java.util.ArrayList<BilateralContactSurface.Frame>();
            if(remainingSurface[0]>0&&remainingSurface[1]>0){
                for(var witness:visitedRecords){
                    if(witness==null){surfaceRejected++;continue;}
                    var frame=BilateralContactSurface.fromRecord(pair,witness,witness.serverTick());
                    if(frame==null){surfaceRejected++;continue;}
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
            int distinctSurfaceA=(int)surfaceEvidence.stream().map(BilateralContactSurface.Match::a).distinct().count();
            int distinctSurfaceB=(int)surfaceEvidence.stream().map(BilateralContactSurface.Match::b).distinct().count();
            var baseRegions=new ArrayList<>(joined);
            var updatedRegions=mergeBilateralPairs(baseRegions,surfaceEvidence);
            var seenPairA=new java.util.HashMap<GameImpactCredit.Target,java.util.Set<GameImpactCredit.Target>>();
            var seenPairB=new java.util.HashMap<GameImpactCredit.Target,java.util.Set<GameImpactCredit.Target>>();
            for(var r:updatedRegions){
                if(r.a()!=null){
                    var targets=r.b()==null?r.opposingCandidates():List.of(r.b());
                    seenPairA.computeIfAbsent(r.a(),k->new java.util.HashSet<>()).addAll(targets);
                }
                if(r.b()!=null){
                    var targets=r.a()==null?r.opposingCandidates():List.of(r.a());
                    seenPairB.computeIfAbsent(r.b(),k->new java.util.HashSet<>()).addAll(targets);
                }
            }
            boolean allPairsProved=!surfaceEvidence.isEmpty();
            for(var pairEvidence:surfaceEvidence)if(!seenPairA.getOrDefault(pairEvidence.a(),java.util.Set.of()).contains(pairEvidence.b())
                    ||!seenPairB.getOrDefault(pairEvidence.b(),java.util.Set.of()).contains(pairEvidence.a())){
                allPairsProved=false;break;
            }
            // A capped *telemetry sample* is not grounds to reject a fully
            // closed geometric contact component. The cap still prevents any
            // claim that ALL unobserved components are known, and never grants
            // movement direction. These are separate proof levels.
            boolean certifiedSurface=allPairsProved;
            boolean nativeSaturated=visitedRecords.stream().anyMatch(NativeCollisionTelemetry.Contact::capSaturated);
            boolean completeSurface=certifiedSurface&&surfaceRejected==0
                    &&!nativeSaturated&&!surfaceReadLimited[0]&&!surfaceReadLimited[1];
            int surfaceAdded=Math.max(0,updatedRegions.size()-baseRegions.size());
            if(RogTrace.sample(Stage.R04,"SURFACE_MERGE"))RogTrace.detail(Stage.R04,"SURFACE_MERGE","pair={} base={} matches={} distinctA={} distinctB={} merged={} rejected={} certified={} complete={} nativeCap={} remainingA={} remainingB={}",pair,baseRegions.size(),surfaceEvidence.size(),distinctSurfaceA,distinctSurfaceB,updatedRegions.size(),surfaceRejected,certifiedSurface,completeSurface,nativeSaturated,remainingSurface[0],remainingSurface[1]);
            if(certifiedSurface)joined=new LinkedHashSet<>(updatedRegions);
            // For an incomplete result preserve the previous native/legacy
            // proof path. In particular do not seal a fragment of a large face.
            shapeChecksA+=RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-collected.checksA()-shapeChecksA-remainingSurface[0];
            shapeChecksB+=RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-collected.checksB()-shapeChecksB-remainingSurface[1];
            if(!certifiedSurface || directions.active()) for(int side=0;side<2;side++) {
                final String owner=owners[side];
                var originals=new LinkedHashSet<GameImpactCredit.Target>();
                for(var region:collected.regions()) {
                    var candidate=side==0?region.a():region.b();
                    if(candidate!=null)originals.add(candidate);
                }
                var offset=ownerOffsets.get(owner);
                if(offset==null || inconsistentOffsets.contains(owner)) {
                    descriptions.append(side==0?"A=":" B=").append("OWNER_GRID_OFFSET_UNVERIFIED");
                    continue;
                }
                int already=side==0?collected.checksA()+shapeChecksA:collected.checksB()+shapeChecksB;
                int[] additional={0};
                boolean[] exceeded={false};
                var patchAxis=directions.uniformPatchAxis(owner);
                var patchFaces=new java.util.HashSet<GameImpactCredit.Target>();
                var patch=ContactFacePatch.reconstruct(dimension,owner,offset,originals,records,(pos,point)-> {
                    net.minecraft.core.BlockPos absolute;
                    try { absolute=new net.minecraft.core.BlockPos(Math.addExact(pos.getX(),offset.getX()),
                            Math.addExact(pos.getY(),offset.getY()),Math.addExact(pos.getZ(),offset.getZ())); }
                    catch(ArithmeticException overflow){return null;}
                    if(!nativeBatch.cached(owner,absolute) && already+additional[0]>=RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE) {
                        exceeded[0]=true;
                        return null;
                    }
                    var sample=nativeBatch.inspect(owner,absolute,new FrameMath.Vec(
                            point.x()+offset.getX(),point.y()+offset.getY(),point.z()+offset.getZ()));
                    if(sample.newCell())additional[0]++;
                    if(!sample.result().verified())return null;
                    var key=sample.result().key();
                    var target=new GameImpactCredit.Target(owner,key.x()+","+key.y()+","+key.z(),key.fingerprint());
                    if(patchAxis!=null&&nativeBatch.targetFace(owner,absolute,new FrameMath.Vec(
                            point.x()+offset.getX(),point.y()+offset.getY(),point.z()+offset.getZ()),patchAxis))patchFaces.add(target);
                    return target;
                });
                if(side==0){shapeChecksA+=additional[0];attemptedA=patch.attempted();}
                else {shapeChecksB+=additional[0];attemptedB=patch.attempted();}
                int size=originals.size()+patch.added().size();
                if(!exceeded[0] && size<=ContactCreditLedger.MAX_TARGETS_PER_SIDE
                        && joined.size()+patch.added().size()<=ContactCreditLedger.MAX_REGIONS) {
                    pairedSurfaces[side]=patch;
                    for(var target:patch.added()){
                        if(!certifiedSurface)joined.add(side==0?new ContactCreditLedger.Region(target,null):new ContactCreditLedger.Region(null,target));
                        if(directions.active())directions.patch(target,patch.planarInterior().contains(target)&&patchFaces.contains(target)?patchAxis:null);
                    }
                    if(side==0)expandedA=patch.added().size();
                    else expandedB=patch.added().size();
                }
                descriptions.append(side==0?"A=":" B=")
                        .append(exceeded[0]?"SHAPE_BUDGET":size>ContactCreditLedger.MAX_TARGETS_PER_SIDE
                                ?"TARGET_BUDGET":patch.reason())
                        .append(" tested=").append(patch.attempted());
            }
            // A material-only pairing can be established from the independently
            // revalidated source records on EACH surface. Direction approval is
            // deliberately separate: a sampled native cap must not grant move
            // direction, but it does not invalidate a positive exact-frame hit.
            if(!certifiedSurface&&pairedSurfaces[0]!=null&&pairedSurfaces[1]!=null&&
                    (!pairedSurfaces[0].interiorPoints().isEmpty()||!pairedSurfaces[1].interiorPoints().isEmpty())){
                var remaining=new int[]{RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-collected.checksA()-shapeChecksA,
                        RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-collected.checksB()-shapeChecksB};
                int firstA=remaining[0],firstB=remaining[1];
                var pairing=pairVerifiedSurfaces(new ArrayList<>(joined),pairedSurfaces,
                        new net.minecraft.core.BlockPos[]{
                            ownerOffsets.get(owners[0]),ownerOffsets.get(owners[1])},directions,
                        (side,point,axis)->uniquePatchTarget(nativeBatch,owners[side],point,axis,remaining,side));
                joined=new LinkedHashSet<>(pairing.regions());
                pairedPatchA=pairing.pairedA();pairedPatchB=pairing.pairedB();
                pairProofTestsA=pairing.checksA();pairProofTestsB=pairing.checksB();
                pairBudgetA=pairing.limitedA();pairBudgetB=pairing.limitedB();
                shapeChecksA+=firstA-remaining[0];shapeChecksB+=firstB-remaining[1];
            }
            descriptions.append(" bilateralPairs=").append(surfaceMatches)
                    .append(" bilateralCertifiedA=").append(distinctSurfaceA)
                    .append(" bilateralCertifiedB=").append(distinctSurfaceB)
                    .append(" surfaceAdded=").append(surfaceAdded)
                    .append(" surfaceChecks=").append(surfacePairChecks)
                    .append(" surfaceRejected=").append(surfaceRejected)
                    .append(" surfaceBudgetA=").append(surfaceReadLimited[0])
                    .append(" surfaceBudgetB=").append(surfaceReadLimited[1]);
            descriptions.append(" completeSurface=").append(completeSurface)
                    .append(" certifiedComponentsPresent=").append(certifiedSurface)
                    .append(" nativeCapSaturated=").append(nativeSaturated)
                    .append(" sharedFrameCount=").append(validFrames.size());
            patchStatus=descriptions.toString();
            collected=new Result(new ArrayList<>(joined),
                    collected.status(),collected.checksA()+shapeChecksA,collected.checksB()+shapeChecksB,
                    collected.rejected(),collected.methodA(),collected.methodB(),collected.trace());
        }
        var t=collected.trace();
        if(directions.active())for(var region:collected.regions()){
            if(region.a()!=null)directions.seed(region.a());
            if(region.b()!=null)directions.seed(region.b());
        }
        var stats=nativeBatch.stats();
        var t2=new Trace(t.failure(),t.sourceIndex(),t.processedRecords(),
                Math.max(0,RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-collected.checksA()),
                Math.max(0,RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE-collected.checksB()),
                t.requestedA(),t.requestedB(),
                t.verifiedTargetsA(),t.verifiedTargetsB(),t.regionCount(),t.rejectedContacts(),
                t.detail()+" auditedUniqueOwnerCells="+stats.newCells()
                        +" reusedCellReads="+stats.reusedCells()
                        +" shapePointTests="+stats.pointTests()
                        +" testedShapeBoxes="+stats.testedBoxes()
                        +" cellBudgetPerSide="+RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE
                        +" damageTargetCapPerSide="+ContactCreditLedger.MAX_TARGETS_PER_SIDE
                        +" contactPatch="+patchStatus
                        +" facePatchAddedA="+expandedA+" facePatchAddedB="+expandedB
                        +" facePatchTotalTargetsA="+(t.verifiedTargetsA()+expandedA)
                        +" facePatchTotalTargetsB="+(t.verifiedTargetsB()+expandedB)
                        +" facePatchAttemptedA="+attemptedA+" facePatchAttemptedB="+attemptedB);
        t2=new Trace(t2.failure(),t2.sourceIndex(),t2.processedRecords(),t2.remainingA(),t2.remainingB(),
                t2.requestedA(),t2.requestedB(),t2.verifiedTargetsA(),t2.verifiedTargetsB(),t2.regionCount(),t2.rejectedContacts(),
                t2.detail()+" patchMaterialPairedA="+pairedPatchA+" patchMaterialPairedB="+pairedPatchB+
                        " exactPairs="+collected.regions().stream().filter(r->r.a()!=null&&r.b()!=null).count()+
                        " candidateSets="+collected.regions().stream().filter(r->!r.opposingCandidates().isEmpty()).count()+
                        " pairProofTestsA="+pairProofTestsA+" pairProofTestsB="+pairProofTestsB+
                        " pairBudgetA="+pairBudgetA+" pairBudgetB="+pairBudgetB+
                        " unpaired="+collected.regions().stream().filter(r->(r.a()==null||r.b()==null)&&r.opposingCandidates().isEmpty()).count());
        return new Result(collected.regions(),collected.status(),collected.checksA(),collected.checksB(),
                collected.rejected(),collected.methodA(),collected.methodB(),t2);
    }
    /** A geometric proof may have more than eight real opposite cells. Do not
     * discard that face: encode each REAL overlap as a singleton opposite
     * candidate (not a fabricated native exact pair). contactPlan deduplicates
     * the seed and divides that seed's original F among all proved opponents.
     * This preserves old Region and ledger APIs without pretending a clustered
     * solver witness was a distinct native contact. */
    static List<ContactCreditLedger.Region> mergeBilateralPairs(
            List<ContactCreditLedger.Region> current,List<BilateralContactSurface.Match> verified){
        if(verified.isEmpty())return current;
        var pairs=new java.util.LinkedHashSet<>(verified);
        var provedA=new java.util.HashSet<GameImpactCredit.Target>();
        var provedB=new java.util.HashSet<GameImpactCredit.Target>();
        var nativeExact=new java.util.HashSet<BilateralContactSurface.Match>();
        for(var region:current)if(region.a()!=null&&region.b()!=null)
            nativeExact.add(new BilateralContactSurface.Match(region.a(),region.b()));
        for(var pair:pairs){provedA.add(pair.a());provedB.add(pair.b());}
        var kept=new java.util.LinkedHashSet<ContactCreditLedger.Region>();
        var presentA=new java.util.HashSet<GameImpactCredit.Target>();
        var presentB=new java.util.HashSet<GameImpactCredit.Target>();
        for(var region:current){
            if(region.a()!=null)presentA.add(region.a());
            if(region.b()!=null)presentB.add(region.b());
            // A fully certified counterpart supersedes a speculative one-sided
            // seed for the same owner/block. Leaving both adds an empty candidate
            // set and wrongly withholds half of this seed's F.
            if(region.a()!=null&&region.b()==null&&provedA.contains(region.a())
                    ||region.b()!=null&&region.a()==null&&provedB.contains(region.b()))continue;
            kept.add(region);
        }
        if(presentA.size()+provedA.stream().filter(t->!presentA.contains(t)).count()>ContactCreditLedger.MAX_TARGETS_PER_SIDE
                ||presentB.size()+provedB.stream().filter(t->!presentB.contains(t)).count()>ContactCreditLedger.MAX_TARGETS_PER_SIDE)
            return current;
        var sorted=new java.util.ArrayList<>(pairs);
        java.util.Comparator<GameImpactCredit.Target> order=java.util.Comparator
                .comparing(GameImpactCredit.Target::ownerKey).thenComparing(GameImpactCredit.Target::localBlock)
                .thenComparing(GameImpactCredit.Target::fingerprint);
        sorted.sort(java.util.Comparator.comparing(BilateralContactSurface.Match::a,order)
                .thenComparing(BilateralContactSurface.Match::b,order));
        for(var pair:sorted)if(!nativeExact.contains(pair)){
            kept.add(new ContactCreditLedger.Region(pair.a(),null,List.of(pair.b())));
            kept.add(new ContactCreditLedger.Region(null,pair.b(),List.of(pair.a())));
            if(kept.size()>ContactCreditLedger.MAX_REGIONS)return current;
        }
        // Even if the exact native pair was the only edge for one side, it
        // already supplies the peer. Do not add an empty duplicate seed.
        return List.copyOf(kept);
    }

    @FunctionalInterface interface SurfaceProbe {
        GameImpactCredit.Target verify(int side,FrameMath.Vec point,
                com.physicaldestroydeform.deform.MovePlan.SignedAxes inward);
    }
    record SurfacePairing(List<ContactCreditLedger.Region> regions,int pairedA,int pairedB,
                          int checksA,int checksB,boolean limitedA,boolean limitedB){
        SurfacePairing {regions=List.copyOf(regions);}
    }
    private record ProvenFootprint(ContactFacePatch.Footprint face,TargetDirections.PatchFrames frame){}

    /** Pure pairing policy shared by the live auditor and regression tests.
     * A source-local shape patch is only a candidate; a matching independent
     * opposite footprint AND two exterior VoxelShape checks are required.
     * No F/Q is issued and no cross-owner propagation edge is manufactured. */
    static SurfacePairing pairVerifiedSurfaces(List<ContactCreditLedger.Region> regions,
            ContactFacePatch.Result[] patches,net.minecraft.core.BlockPos[] offsets,
            TargetDirections directions,SurfaceProbe proof){
        var joined=new LinkedHashSet<>(regions);
        if(patches==null||patches.length!=2||patches[0]==null||patches[1]==null
                ||offsets==null||offsets.length!=2
                ||offsets[0]==null||offsets[1]==null||directions==null||proof==null)
            return new SurfacePairing(new ArrayList<>(joined),0,0,0,0,false,false);
        var footprintProofs=List.of(new ArrayList<ProvenFootprint>(),new ArrayList<ProvenFootprint>());
        for(int side=0;side<2;side++){
            for(var face:patches[side].footprints()){
                var frame=directions.patchFrames(face.witnesses());
                if(frame!=null)footprintProofs.get(side).add(new ProvenFootprint(face,frame));
            }
        }
        @SuppressWarnings("unchecked")
        java.util.Set<GameImpactCredit.Target>[] seeds=new java.util.Set[]{new java.util.HashSet<>(),new java.util.HashSet<>()};
        for(var region:regions){if(region.a()!=null)seeds[0].add(region.a());
            if(region.b()!=null)seeds[1].add(region.b());}
        int[] paired=new int[2],checks=new int[2];
        boolean[] limited=new boolean[2];
        for(int side=0;side<2;side++){
            final int pairedSide=side; // Lambda captures an immutable side per iteration (Java 21).
            final boolean onA=side==0;
            var ordered=new ArrayList<>(patches[side].interiorPoints().entrySet());
            ordered.sort(java.util.Comparator.comparing(e->e.getKey().localBlock()));
            targetLoop: for(var entry:ordered){
                var previous=onA?new ContactCreditLedger.Region(entry.getKey(),null)
                        :new ContactCreditLedger.Region(null,entry.getKey());
                if(!joined.contains(previous))continue;
                var source=offset(entry.getValue(),offsets[side],1);
                for(var face:footprintProofs.get(side)){
                    if(checks[side]>=RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE){limited[side]=true;break targetLoop;}
                    checks[side]++;
                    if(!face.face().covers(entry.getValue()))continue;
                    var frame=face.frame();
                    var mapped=frame.opposite(source,onA);
                    if(!mapped.finite())continue;
                    var otherCenter=offset(mapped,offsets[1-side],-1);
                    for(var other:footprintProofs.get(1-side)){
                        if(checks[side]>=RUNTIME_MAX_UNIQUE_CELLS_PER_SIDE){limited[side]=true;break targetLoop;}
                        checks[side]++;
                        if(!frame.coherent(other.frame()))continue;
                        var relative=ContactFacePatch.commonSurfacePoint(face.face(),other.face(),
                                entry.getValue(),otherCenter,p->offset(frame.opposite(
                                        offset(p,offsets[pairedSide],1),onA),offsets[1-pairedSide],-1));
                        if(relative==null)continue;
                        // Recheck the SAME positive-area point on both shapes.
                        var opposite=offset(relative,offsets[1-side],1);
                        var actualSource=frame.opposite(opposite,!onA);
                        var self=proof.verify(side,actualSource,frame.axis(onA));
                        if(!entry.getKey().equals(self))continue;
                        var target=proof.verify(1-side,opposite,frame.axis(!onA));
                        if(target==null||!seeds[1-side].contains(target))continue;
                        if(joined.remove(previous)){
                            joined.add(onA?new ContactCreditLedger.Region(self,null,List.of(target))
                                    :new ContactCreditLedger.Region(null,self,List.of(target)));
                            paired[side]++;
                        }
                        break;
                    }
                    if(!joined.contains(previous))break;
                }
            }
        }
        return new SurfacePairing(new ArrayList<>(joined),paired[0],paired[1],
                checks[0],checks[1],limited[0],limited[1]);
    }

    private static FrameMath.Vec offset(FrameMath.Vec p,net.minecraft.core.BlockPos d,int sign){
        return p.plus(new FrameMath.Vec(sign*(double)d.getX(),sign*(double)d.getY(),sign*(double)d.getZ()));
    }
    private static GameImpactCredit.Target uniquePatchTarget(OwnerResolver.NativeAuditBatch batch,String owner,
            FrameMath.Vec point,com.physicaldestroydeform.deform.MovePlan.SignedAxes axis,int[] remaining,int side){
        var candidates=SableConsumerTargets.cells(point);
        GameImpactCredit.Target target=null;
        for(var pos:candidates){
            if(!batch.cached(owner,pos)&&remaining[side]<=0)return null;
            var sample=batch.inspect(owner,pos,point);if(sample.newCell())remaining[side]--;
            var evidence=sample.result();
            if(!evidence.verified()){
                if(!java.util.Set.of("CACHED_SHAPE_POINT_MISS","BLOCK_MISSING_OR_AIR","PLOT_SHAPE_EMPTY","WORLD_SHAPE_EMPTY").contains(evidence.detail()))return null;
                continue;
            }
            if(!batch.targetFace(owner,pos,point,axis,ContactFacePatch.PAIR_EPS))continue;
            if(target!=null)return null;
            var key=evidence.key();target=new GameImpactCredit.Target(owner,key.x()+","+key.y()+","+key.z(),key.fingerprint());
        }
        if(target!=null){
            var outside=point.plus(new FrameMath.Vec(-axis.x(),-axis.y(),-axis.z()).times(ContactFacePatch.PAIR_EPS*4));
            for(var pos:candidates)if(batch.contains(owner,pos,outside,ContactFacePatch.PAIR_EPS/4))return null;
        }
        return target;
    }
    private record AuditKey(String owner, FrameMath.Vec point,
                            NativeCollisionTelemetry.OwnerFrame frame) {}
    private record DirectionWitness(java.util.Map<GameImpactCredit.Target,net.minecraft.core.BlockPos> cells,boolean uncertain) {}
    private static GameImpactCredit.Target observeDirection(TargetDirections directions,OwnerResolver.NativeAuditBatch batch,
                                         NativeCollisionTelemetry.Contact contact,String owner,FrameMath.Vec point,DirectionWitness witness){
        var axes=directions.axis(owner,contact);
        var faces=new ArrayList<GameImpactCredit.Target>();
        if(axes!=null)for(var entry:witness.cells().entrySet())
            if(batch.targetFace(owner,entry.getValue(),point,axes))faces.add(entry.getKey());
        directions.observe(witness.cells().keySet(),faces,axes,witness.uncertain());
        return !witness.uncertain()&&faces.size()==1?faces.getFirst():null;
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
                targetsA.add(target);
            }
            for(var target:b.targets()) {
                if(!target.ownerKey().equals(pair.b().stableKey()))
                    return traced(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                            "UNVERIFIED","UNVERIFIED",AuditFailure.OWNER_MISMATCH_B,
                            index,processed,remainA,remainB,askedA,askedB,targetsA.size(),targetsB.size(),regions.size(),
                            "target_owner="+target.ownerKey());
                targetsB.add(target);
            }
            // If one side exceeds the bounded target budget, NEVER concentrate its Q on
            // an arbitrary subset. Quarantine that entire side, continue
            // auditing the opposite side, and leave the bad side's Q unused.
            if(targetsA.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE && !exceededA) {
                exceededA=true;overflowIndex=index;overflowCountA=targetsA.size();
            }
            if(targetsB.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE && !exceededB) {
                exceededB=true;overflowIndex=index;overflowCountB=targetsB.size();
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
                if(a.directTarget()!=null && b.directTarget()!=null){
                    regions.add(new ContactCreditLedger.Region(a.directTarget(),b.directTarget()));
                    for(var target:a.targets())if(!target.equals(a.directTarget()))regions.add(new ContactCreditLedger.Region(target,null,b.opposingCandidates()));
                    for(var target:b.targets())if(!target.equals(b.directTarget()))regions.add(new ContactCreditLedger.Region(null,target,a.opposingCandidates()));
                }else {
                    for(var target:a.targets())regions.add(new ContactCreditLedger.Region(target,null,b.opposingCandidates()));
                    for(var target:b.targets())regions.add(new ContactCreditLedger.Region(null,target,a.opposingCandidates()));
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
            if(exceededA)methodA="UNVERIFIED";
            if(exceededB)methodB="UNVERIFIED";
        }
        AuditFailure failure=exceededA?(incompleteEverA?AuditFailure.SIDE_A_INCOMPLETE:AuditFailure.TARGET_LIMIT_A):
                exceededB?(incompleteEverB?AuditFailure.SIDE_B_INCOMPLETE:AuditFailure.TARGET_LIMIT_B):
                regions.isEmpty()?(uncorroborated==records.size()&& !records.isEmpty()?
                        AuditFailure.UNCORROBORATED:AuditFailure.NO_VERIFIED_REGION):AuditFailure.NONE;
        // A valid one-sided Q reservation is PARTIAL, even if every record
        // was inspected. Never describe a missing source owner as PROVED.
        boolean hasA=regions.stream().anyMatch(r->r.a()!=null);
        boolean hasB=regions.stream().anyMatch(r->r.b()!=null);
        Status status=regions.isEmpty()?Status.NO_VERIFIED_REGION:
                skipped==0 && !exceededA && !exceededB && hasA && hasB
                        ?Status.PROVED:Status.PARTIAL;
        return traced(new ArrayList<>(regions),status,checkedA,checkedB,skipped,
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

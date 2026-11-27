package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.RogTrace;


import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * IDS 1.0.6 §22.4: one read-only debit ledger for a verified pair episode.
 * Native contact point ordinals are never solver contact IDs or credit IDs.
 * A single approved source event has one Q upper envelope. Legacy issue()
 * splits Q uniformly among contact-proved targets; issuePropagated() assigns
 * F through owner-verified connected cells before deriving target Q.
 */
public final class ContactCreditLedger {
    public static final String LEGACY_ALGORITHM_REVISION = "PDD-IDS-1.0.7/0.4.1-spreadFix-MULTIFACE-70-30";
    public static final String PREVIOUS_ALGORITHM_REVISION = "PDD-IDS-1.0.9/0.6.1-CONTACT-ELASTIC-CHAIN";
    public static final String ALGORITHM_REVISION = "PDD-IDS-1.0.9/0.6.1-astraFixFix-PAIRED-LOCAL-PATCH-ALPHA04";
    // Bounded but large enough for a 100x100 fully verified planar contact patch.
    public static final int MAX_TARGETS_PER_SIDE = 16_384;
    public static final int MAX_REGIONS = 2 * MAX_TARGETS_PER_SIDE;
    public static final int MAX_ENTRIES = 4096;
    public static final long IDLE_TICKS = 600;

    /** Only sources with a demonstrated common cause may share a CreditId. */
    public enum Source { SOLVER_VERIFIED, SABLE_POST_EFFECT, SPECIAL_CALLBACK_HINT }
    public enum Status { ISSUED, DUPLICATE_CAUSE, INVALID_TARGET_SET, LEDGER_CAPACITY, INSUFFICIENT_EVIDENCE, HINT_NOT_AUTHORITATIVE }
    public record CreditId(GameImpactCredit.Pair pair, long episodeGeneration) {
        public CreditId {
            Objects.requireNonNull(pair);
            if (episodeGeneration <= 0) throw new IllegalArgumentException("invalid episode generation");
        }
    }
    /** One source observation. A singleton may retain its exhaustive opposite
     * candidate set without claiming an exact block correspondence. */
    public record Region(GameImpactCredit.Target a, GameImpactCredit.Target b,
                         List<GameImpactCredit.Target> opposingCandidates,boolean ownerLocalPatch) {
        public Region(GameImpactCredit.Target a,GameImpactCredit.Target b){this(a,b,List.of(),false);}
        public Region(GameImpactCredit.Target a,GameImpactCredit.Target b,List<GameImpactCredit.Target> opposingCandidates){this(a,b,opposingCandidates,false);}
        public Region {
            if (a == null && b == null) throw new IllegalArgumentException("empty contact region");
            if(ownerLocalPatch&&a!=null&&b!=null)throw new IllegalArgumentException("game patch is not an exact native pair");
            Objects.requireNonNull(opposingCandidates);
            if(opposingCandidates.size()>SableConsumerTargets.MAX_CANDIDATES||
                    a!=null&&b!=null&&!opposingCandidates.isEmpty())throw new IllegalArgumentException("invalid opposite evidence");
            var ordered=new java.util.TreeSet<>(CONTACT_ORDER);ordered.addAll(opposingCandidates);
            opposingCandidates=List.copyOf(ordered);
        }
    }
    public record Share(GameImpactCredit.Target target, double q) {
        public Share {
            Objects.requireNonNull(target);
            if (!Double.isFinite(q) || q < 0) throw new IllegalArgumentException("invalid debit");
        }
    }
    public record Grant(CreditId id, Source source, Status status, long tick,
                        double issuedQ, List<Share> a, List<Share> b,
                        double unused, int observedRegions, String algorithmRevision,
                        ForcePropagation.Result propagationA, ForcePropagation.Result propagationB,
                        Map<String,GameImpactCredit.Owner> additionalOwners) {
        public Grant {
            a = List.copyOf(a);
            b = List.copyOf(b);
            additionalOwners=Map.copyOf(additionalOwners);
        }
        public Grant(CreditId id,Source source,Status status,long tick,double issuedQ,
                     List<Share> a,List<Share> b,double unused,int regions,String revision,
                     ForcePropagation.Result propagationA,ForcePropagation.Result propagationB) {
            this(id,source,status,tick,issuedQ,a,b,unused,regions,revision,propagationA,propagationB,Map.of());
        }
        /** Compatibility constructor for legacy 0.4.0 callers/tests and Q-reservation fixtures. */
        public Grant(CreditId id,Source source,Status status,long tick,double issuedQ,
                     List<Share> a,List<Share> b,double unused,int regions,String revision) {
            this(id,source,status,tick,issuedQ,a,b,unused,regions,revision,null,null);
        }
        public boolean propagated(){return propagationA!=null || propagationB!=null;}
        public double inputF(){return (propagationA==null?0:propagationA.inputF())+
                (propagationB==null?0:propagationB.inputF());}
        public double dampedF(){return (propagationA==null?0:propagationA.dampedF())+
                (propagationB==null?0:propagationB.dampedF());}
        public double contactDampedF(){return (propagationA==null?0:propagationA.contactDampedF())+
                (propagationB==null?0:propagationB.contactDampedF());}
        public double unresolvedF(){return (propagationA==null?0:propagationA.unresolvedF())+
                (propagationB==null?0:propagationB.unresolvedF());}
        public double elasticallyDampedQ(){return (propagationA==null?0:propagationA.dampedQ())+
                (propagationB==null?0:propagationB.dampedQ());}
        public double allocatedA() {return a.stream().mapToDouble(Share::q).sum();}
        public double allocatedB() {return b.stream().mapToDouble(Share::q).sum();}
        public double accounted() {return allocatedA()+allocatedB()+unused;}
        /** All issued Q is sealed into reserved shares or unused on issue. */
        public double availableQ() {return 0;}
        /** 0.4.0 material plans are predictions, not committed world damage. */
        public double spent() {return 0;}
        public boolean credited() {return status==Status.ISSUED && issuedQ>0;}
        /** A side is an original budget, not the receiving node's owner. */
        public GameImpactCredit.Owner ownerFor(GameImpactCredit.Target target) {
            var pair=id.pair();
            if(pair.a().stableKey().equals(target.ownerKey()))return pair.a();
            if(pair.b().stableKey().equals(target.ownerKey()))return pair.b();
            var owner=additionalOwners.get(target.ownerKey());
            return owner!=null&&owner.generation()>0&&owner.stableKey().equals(target.ownerKey())?owner:null;
        }
    }
    private record Stored(Source source, long tick, Grant grant) {}
    private final Map<CreditId, Stored> recorded = new HashMap<>();
    private final Set<CreditId> planning=new HashSet<>();
    // Ephemeral and server-thread only: why the last proposed graph was rejected.
    private String lastPropagationFailure="NONE";
    public String lastPropagationFailure() {return lastPropagationFailure;}
    public int size() {return recorded.size();}
    public Grant lookup(CreditId id) {
        var item=recorded.get(Objects.requireNonNull(id));
        return item==null?null:item.grant();
    }
    public void clear() {recorded.clear();lastPropagationFailure="NONE";}
    public void invalidateOwner(String owner) {
        recorded.keySet().removeIf(k -> k.pair().a().stableKey().equals(owner)
                || k.pair().b().stableKey().equals(owner)
                || recorded.get(k).grant().additionalOwners().containsKey(owner));
    }
    /** Unobserved events never mint debt; stale history is only garbage-collected. */
    public void prune(long now) {
        recorded.entrySet().removeIf(e -> now > e.getValue().tick
                && now - e.getValue().tick > IDLE_TICKS);
    }

    /** The caller must first pass GameImpactCredit's ARMED/new-approach gate. */
    public Grant issue(GameImpactCredit.Pair pair, GameImpactCredit.Decision decision,
                       Source source, List<Region> evidence) {
        var validation=validateSeeds(pair,decision,source,evidence,false,0,0);
        CreditId id=validation.id();
        if(validation.status()!=Status.ISSUED)return empty(id,source,validation.status(),decision.tick(),evidence.size());
        var a=validation.a();var b=validation.b();
        var partsA=split(a,decision.allocation().sideA());
        var partsB=split(b,decision.allocation().sideB());
        double sum=partsA.stream().mapToDouble(Share::q).sum()
                +partsB.stream().mapToDouble(Share::q).sum();
        double unused=decision.issuedQ()-sum;
        if (!Double.isFinite(unused) || unused < -1e-8 || sum > decision.issuedQ()+1e-8)
            return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
        // No block/world commit before v0.4.1/0.5.1. Share means reserved Q.
        Grant issued=new Grant(id,source,Status.ISSUED,decision.tick(),decision.issuedQ(),
                partsA,partsB,Math.max(0,unused),evidence.size(),LEGACY_ALGORITHM_REVISION);
        recorded.put(id,new Stored(source,decision.tick(),issued));
        return issued;
    }
    private record SeedValidation(CreditId id,Status status,Set<GameImpactCredit.Target> a,Set<GameImpactCredit.Target> b){}
    private SeedValidation validateSeeds(GameImpactCredit.Pair pair,GameImpactCredit.Decision decision,
                                         Source source,List<Region> evidence,boolean materialOnly,int reservedA,int reservedB){
        Objects.requireNonNull(pair); Objects.requireNonNull(decision);
        Objects.requireNonNull(source); Objects.requireNonNull(evidence);
        CreditId id=new CreditId(pair,Math.max(1,decision.episodeGeneration()));
        if (!decision.credited())
            return new SeedValidation(id,Status.INSUFFICIENT_EVIDENCE,Set.of(),Set.of());
        if (recorded.containsKey(id)||planning.contains(id))
            return new SeedValidation(id,Status.DUPLICATE_CAUSE,Set.of(),Set.of());
        if (source==Source.SPECIAL_CALLBACK_HINT)
            return new SeedValidation(id,Status.HINT_NOT_AUTHORITATIVE,Set.of(),Set.of());
        if (recorded.size()+planning.size()>=MAX_ENTRIES)
            return new SeedValidation(id,Status.LEDGER_CAPACITY,Set.of(),Set.of());

        // Linked source contacts may report the same target repeatedly. They
        // only increase observation count, never target Q or source Q.
        if (!Double.isFinite(decision.issuedQ())||!Double.isFinite(decision.peakForce())||decision.peakForce()<0
                ||decision.allocation()==null||Math.abs(decision.allocation().total()-decision.issuedQ())>Math.max(1e-7,decision.issuedQ()*1e-9)
                ||evidence.isEmpty() || evidence.size()>MAX_REGIONS)
            return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
        Set<GameImpactCredit.Target> a=new HashSet<>(), b=new HashSet<>();
        Map<String,String> aCells=new HashMap<>(), bCells=new HashMap<>();
        var model=new HashMap<GameImpactCredit.Target,List<GameImpactCredit.Target>>();var independent=new HashSet<GameImpactCredit.Target>();
        for(Region region:evidence) {
            if (region==null) return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
            if(region.ownerLocalPatch()){
                if(!materialOnly||source!=Source.SABLE_POST_EFFECT)return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
                var target=region.a()!=null?region.a():region.b();var prior=model.putIfAbsent(target,region.opposingCandidates());
                if(prior!=null&&!prior.equals(region.opposingCandidates()))return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
            }else{
                if(region.a()!=null)independent.add(region.a());if(region.b()!=null)independent.add(region.b());
            }
            if(region.a()!=null) {
                if(!region.a().ownerKey().equals(pair.a().stableKey()))
                    return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
                String earlier=aCells.putIfAbsent(region.a().localBlock(),region.a().fingerprint());
                if(earlier!=null && !earlier.equals(region.a().fingerprint()))
                    return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
                a.add(region.a());
            }
            if(region.b()!=null) {
                if(!region.b().ownerKey().equals(pair.b().stableKey()))
                    return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
                String earlier=bCells.putIfAbsent(region.b().localBlock(),region.b().fingerprint());
                if(earlier!=null && !earlier.equals(region.b().fingerprint()))
                    return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
                b.add(region.b());
            }
        }
        if(!model.isEmpty()&&(independent.isEmpty()||model.keySet().stream().anyMatch(independent::contains)))
            return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
        if(a.size()>MAX_TARGETS_PER_SIDE || b.size()>MAX_TARGETS_PER_SIDE || a.isEmpty()&&b.isEmpty())
            return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
        if(reservedA<0||reservedB<0||(long)a.size()+reservedA>MAX_TARGETS_PER_SIDE
                ||(long)b.size()+reservedB>MAX_TARGETS_PER_SIDE)
            return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
        // A material-only candidate has no Layer-0 debit. The live material
        // resolver must independently revalidate its state/fingerprint. Legacy
        // Q fixtures retain their historical all-candidates-are-seeds contract.
        for(var region:evidence)for(var candidate:region.opposingCandidates()){
            boolean onA=region.a()==null;
            if(!candidate.ownerKey().equals((onA?pair.a():pair.b()).stableKey())||
                    !materialOnly&&!(onA?a:b).contains(candidate))
                return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
            var cells=onA?aCells:bCells;
            var prior=cells.putIfAbsent(candidate.localBlock(),candidate.fingerprint());
            if(prior!=null&&!prior.equals(candidate.fingerprint())||cells.size()>MAX_TARGETS_PER_SIDE)
                return new SeedValidation(id,Status.INVALID_TARGET_SET,Set.of(),Set.of());
        }
        return new SeedValidation(id,Status.ISSUED,a,b);

    }
    /** colFix: use R2 source-verified contacts as seeds, then inspect actual
     * connected World/Plot neighbors. Keep GameImpactCredit's approval/cap as
     * ONE cause envelope; only the sealed per-block F-derived Q can be spent.
     * Legacy issue() remains available to verify pre-colFix regression contracts. */
    public Grant issuePropagated(GameImpactCredit.Pair pair,GameImpactCredit.Decision decision,
                                 Source source,List<Region> evidence,
                                 ForcePropagation.NeighborReader neighbors,
                                 java.util.function.Function<String,com.physicaldestroydeform.MaterialProperties> materials,
                                 GameImpactCredit.Settings forces,
                                 com.physicaldestroydeform.DamageModel.Settings damage) {
        return issuePropagated(pair,decision,source,evidence,neighbors,materials,forces,damage,null);
    }
    /** A non-null direction map selects the bounded owner-local propagation
     * engine, including its bounded UNKNOWN-direction fallback. The original
     * signature remains for historical pure regression fixtures. */
    public Grant issuePropagated(GameImpactCredit.Pair pair,GameImpactCredit.Decision decision,
                                 Source source,List<Region> evidence,
                                 ForcePropagation.NeighborReader neighbors,
                                 java.util.function.Function<String,com.physicaldestroydeform.MaterialProperties> materials,
                                 GameImpactCredit.Settings forces,
                                 com.physicaldestroydeform.DamageModel.Settings damage,
                                 Map<String,ForcePropagation.Orientation> directions) {
        return issuePropagated(pair,decision,source,evidence,neighbors,materials,forces,damage,
                directions,null);
    }
    /** Trace is delivered only after a credit is successfully sealed; the
     * in-memory ledger never owns the potentially large edge records. */
    public Grant issuePropagated(GameImpactCredit.Pair pair,GameImpactCredit.Decision decision,
                                 Source source,List<Region> evidence,
                                 ForcePropagation.NeighborReader neighbors,
                                 java.util.function.Function<String,com.physicaldestroydeform.MaterialProperties> materials,
                                 GameImpactCredit.Settings forces,
                                 com.physicaldestroydeform.DamageModel.Settings damage,
                                 Map<String,ForcePropagation.Orientation> directions,
                                 java.util.function.Consumer<ForcePropagation.Trace> trace) {
        return issuePropagatedInternal(pair,decision,source,evidence,neighbors,t->Objects.requireNonNull(materials.apply(t.fingerprint()),"missing material"),
                forces,damage,directions,trace,materials,false,null,false,0,0);
    }
    public Grant issuePropagatedTargets(GameImpactCredit.Pair pair,GameImpactCredit.Decision decision,
                                 Source source,List<Region> evidence,ForcePropagation.NeighborReader neighbors,
                                 java.util.function.Function<GameImpactCredit.Target,com.physicaldestroydeform.MaterialProperties> materials,
                                 GameImpactCredit.Settings forces,com.physicaldestroydeform.DamageModel.Settings damage,
                                 Map<String,ForcePropagation.Orientation> directions,
                                 java.util.function.Consumer<ForcePropagation.Trace> trace,
                                 java.util.function.Function<String,com.physicaldestroydeform.MaterialProperties> legacyMaterials) {
        return issuePropagatedTargets(pair,decision,source,evidence,neighbors,materials,forces,damage,directions,
                trace,legacyMaterials,null);
    }
    public Grant issuePropagatedTargets(GameImpactCredit.Pair pair,GameImpactCredit.Decision decision,
                                 Source source,List<Region> evidence,ForcePropagation.NeighborReader neighbors,
                                 Function<GameImpactCredit.Target,MaterialProperties> materials,
                                 GameImpactCredit.Settings forces,DamageModel.Settings damage,
                                 Map<String,ForcePropagation.Orientation> directions,
                                 java.util.function.Consumer<ForcePropagation.Trace> trace,
                                 Function<String,MaterialProperties> legacyMaterials,
                                 ForcePropagation.CrossOwnerContext crossOwner) {
        return issuePropagatedInternal(pair,decision,source,evidence,neighbors,materials,forces,damage,
                directions==null?Map.of():directions,trace,legacyMaterials,true,crossOwner,false,0,0);
    }
    /** Current runtime: one authorized source envelope, independently verified
     * direct/closed-surface seeds, and non-spendable ambiguous contact slots. */
    public Grant issueContactSeeds(GameImpactCredit.Pair pair,GameImpactCredit.Decision decision,
                                  Source source,List<Region> evidence,int reservedA,int reservedB,
                                  ForcePropagation.NeighborReader neighbors,Function<GameImpactCredit.Target,MaterialProperties> materials,
                                  GameImpactCredit.Settings forces,DamageModel.Settings damage,
                                  Map<String,ForcePropagation.Orientation> directions,
                                  java.util.function.Consumer<ForcePropagation.Trace> trace,
                                  ForcePropagation.CrossOwnerContext crossOwner){
        return issuePropagatedInternal(pair,decision,source,evidence,neighbors,materials,forces,damage,
                directions==null?Map.of():directions,trace,null,true,crossOwner,true,reservedA,reservedB);
    }
    private Grant issuePropagatedInternal(GameImpactCredit.Pair pair,GameImpactCredit.Decision decision,
                                 Source source,List<Region> evidence,ForcePropagation.NeighborReader neighbors,
                                 Function<GameImpactCredit.Target,MaterialProperties> materials,
                                 GameImpactCredit.Settings forces,DamageModel.Settings damage,
                                 Map<String,ForcePropagation.Orientation> directions,
                                 java.util.function.Consumer<ForcePropagation.Trace> trace,
                                 Function<String,MaterialProperties> legacyMaterials,boolean bilateral,
                                 ForcePropagation.CrossOwnerContext crossOwner,boolean materialOnly,int reservedA,int reservedB) {
        // Validate the single source envelope without minting a legacy Q grant.
        lastPropagationFailure="NONE";
        var validated=validateSeeds(pair,decision,source,evidence,materialOnly,reservedA,reservedB);
        if(validated.status()!=Status.ISSUED) {
            lastPropagationFailure="SOURCE_OR_EVIDENCE_GATE_"+validated.status();
            return empty(validated.id(),source,validated.status(),decision.tick(),evidence.size());
        }
        List<ForcePropagation.Trace> traces=trace==null?null:new ArrayList<>();
        Grant issued;
        List<SeedAudit> seedAudits=bilateral&&RogTrace.stageEnabled(Stage.R06)?new ArrayList<>(2):null;
        planning.add(validated.id());
        try {
            List<GameImpactCredit.Target> seedsA=!materialOnly&&decision.allocation().sideA()==0?List.of():validated.a().stream().sorted(CONTACT_ORDER).toList();
            List<GameImpactCredit.Target> seedsB=!materialOnly&&decision.allocation().sideB()==0?List.of():validated.b().stream().sorted(CONTACT_ORDER).toList();
            double sideF=decision.peakForce()/2;
            if(crossOwner!=null){
                var owners=crossOwner.owners();
                for(var owner:List.of(pair.a(),pair.b())){
                    var candidate=owners.get(owner.stableKey());
                    if(candidate!=null&&!candidate.equals(owner))throw new IllegalArgumentException("stale source owner generation");
                }
            }
            var pa=bilateral?contactPlan(pair.a().stableKey(),seedsA,sideF,reservedA,evidence,true,neighbors,materials,forces,
                    damage,directions.getOrDefault(pair.a().stableKey(),ForcePropagation.Orientation.unknown()),
                    traces==null?null:traces::add,crossOwner==null?null:crossOwner.forSide(pair.a().stableKey(),pair.b().stableKey()),seedAudits)
                    :directions==null
                    ?ForcePropagation.plan(pair.a().stableKey(),seedsA,sideF,neighbors,legacyMaterials,forces,damage)
                    :ForcePropagation.planTargets(pair.a().stableKey(),seedsA,sideF,neighbors,materials,forces,damage,
                            directions.getOrDefault(pair.a().stableKey(),ForcePropagation.Orientation.unknown()),
                            traces==null?null:traces::add);
            var pb=bilateral?contactPlan(pair.b().stableKey(),seedsB,sideF,reservedB,evidence,false,neighbors,materials,forces,
                    damage,directions.getOrDefault(pair.b().stableKey(),ForcePropagation.Orientation.unknown()),
                    traces==null?null:traces::add,crossOwner==null?null:crossOwner.forSide(pair.b().stableKey(),pair.a().stableKey()),seedAudits)
                    :directions==null
                    ?ForcePropagation.plan(pair.b().stableKey(),seedsB,sideF,neighbors,legacyMaterials,forces,damage)
                    :ForcePropagation.planTargets(pair.b().stableKey(),seedsB,sideF,neighbors,materials,forces,damage,
                            directions.getOrDefault(pair.b().stableKey(),ForcePropagation.Orientation.unknown()),
                            traces==null?null:traces::add);
            // Ambiguous or over-budget cross-owner plans must never destroy the
            // original, independently verified direct-side reservation. Replan
            // with just the uncertain boundary held, without minting Credit/F.
            if(bilateral&&crossOwner!=null&&!crossValid(pair,pa,pb,crossOwner)){
                if(traces!=null)traces.clear();
                if(seedAudits!=null)seedAudits.clear();
                pa=contactPlan(pair.a().stableKey(),seedsA,sideF,reservedA,evidence,true,neighbors,materials,forces,damage,
                        directions.getOrDefault(pair.a().stableKey(),ForcePropagation.Orientation.unknown()),
                        traces==null?null:traces::add,holdCross(crossOwner.forSide(pair.a().stableKey(),pair.b().stableKey())),seedAudits);
                pb=contactPlan(pair.b().stableKey(),seedsB,sideF,reservedB,evidence,false,neighbors,materials,forces,damage,
                        directions.getOrDefault(pair.b().stableKey(),ForcePropagation.Orientation.unknown()),
                        traces==null?null:traces::add,holdCross(crossOwner.forSide(pair.b().stableKey(),pair.a().stableKey())),seedAudits);
            }
            // An independently certified late expansion may recover a formerly
            // missing side. It can spend only its ORIGINAL half, never the other half.
            var a=seal(pa,materialOnly?decision.issuedQ()/2:decision.allocation().sideA());
            var b=seal(pb,materialOnly?decision.issuedQ()/2:decision.allocation().sideB());
            double sum=a.stream().mapToDouble(Share::q).sum()+b.stream().mapToDouble(Share::q).sum();
            double unused=decision.issuedQ()-sum;
            if(!Double.isFinite(unused) || unused < -1e-7 ||
                    sum > decision.issuedQ()+1e-7)
                throw new IllegalArgumentException("propagated Q exceeded source envelope");
            var additionalOwners=new HashMap<String,GameImpactCredit.Owner>();
            // Both original side budgets are disjoint. If an external node was
            // admitted by both sides, reject before either side can issue Q.
            // No assumption about common cause is made from a shared node alone.
            record Cell(String owner,String local){}
            var admittedCells=new HashSet<Cell>();
            for(var side:List.of(pa,pb))for(var share:side.shares()){
                var target=share.target();
                if(!admittedCells.add(new Cell(target.ownerKey(),target.localBlock())))
                    throw new IllegalStateException("cross-side node admitted twice");
            }
            var liveOwners=crossOwner==null?Map.<String,GameImpactCredit.Owner>of():crossOwner.owners();
            for(var side:List.of(a,b))for(var share:side){
                String owner=share.target().ownerKey();
                if(owner.equals(pair.a().stableKey())||owner.equals(pair.b().stableKey()))continue;
                var actual=liveOwners.get(owner);
                if(actual==null||actual.generation()<=0||!actual.stableKey().equals(owner))throw new IllegalArgumentException("recipient generation unverified");
                additionalOwners.put(owner,actual);
            }
            if(additionalOwners.size()+2>ForcePropagation.MAX_CROSS_OWNERS)
                throw new IllegalStateException("event cross-owner budget exceeded");
            Grant grant=new Grant(validated.id(),source,Status.ISSUED,decision.tick(),decision.issuedQ(),
                    a,b,Math.max(0,unused),evidence.size(),bilateral?ALGORITHM_REVISION:LEGACY_ALGORITHM_REVISION,pa,pb,additionalOwners);
            recorded.put(grant.id(),new Stored(source,decision.tick(),grant));
            issued=grant;
        }catch(RuntimeException unsafe) {
            // Diagnostic reason is not persisted; only verified plans can mint Q.
            lastPropagationFailure=unsafe.getClass().getSimpleName()+":"+
                    String.valueOf(unsafe.getMessage());
            // Nothing is committed to the preview or source on a failed plan.
            return empty(validated.id(),source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
        }finally{planning.remove(validated.id());}
        // A diagnostic consumer must never revoke the already accepted credit.
        if(traces!=null)for(var event:traces) {
            try {trace.accept(event);}catch(RuntimeException ignored) {break;}
        }
        if(seedAudits!=null)try{for(var audit:seedAudits)audit.emit(issued.id());}catch(RuntimeException|LinkageError ignored){}
        return issued;
    }

    private record SeedAudit(String owner,List<GameImpactCredit.Target> seeds,Map<GameImpactCredit.Target,Double> effective,double split,double sideF,int reserved,double unresolved,double damped,int exact,int consensus,int patchConsensus,int withheld,int missingMaterial,int mixedMaterial,int unproved){
        void emit(CreditId credit){
            { if(RogTrace.eventSlot(Stage.R06,"CONTACT_SIDE_SEAL"))RogTrace.detail(Stage.R06,"CONTACT_SIDE_SEAL","credit={} owner={} sideF={} forwardF={} contactDampedF={} unresolvedF={} exact={} consensus={} patchConsensus={} withheld={} missingMaterial={} mixedE={} unproved={} seedCount={} reservedUnknown={} effectiveCount={}",credit,owner,sideF,sideF-damped-unresolved,damped,unresolved,exact,consensus,patchConsensus,withheld,missingMaterial,mixedMaterial,unproved,seeds.size(),reserved,effective.size()); }
            if(!RogTrace.traceSelected())return;
            int count=Math.min(seeds.size(),RogTrace.seedLimit());
            { if(RogTrace.eventSlot(Stage.R06,"CONTACT_SEED_EFFECTIVE_SUMMARY"))RogTrace.detail(Stage.R06,"CONTACT_SEED_EFFECTIVE_SUMMARY","credit={} owner={} seedCount={} effectiveCount={} sideF={} preContactSeedF={} reservedUnknown={} unresolvedF={} contactDampedF={} listedLimit={} truncated={}",credit,owner,seeds.size(),effective.size(),sideF,split,reserved,unresolved,damped,count,count<seeds.size()); }
            for(int offset=0;offset<count;offset+=16){
                if(!RogTrace.evidenceSlot(Stage.R06,"CONTACT_SEED_EFFECTIVE"))break;
                var entries=new ArrayList<String>();int end=Math.min(count,offset+16);
                for(int i=offset;i<end;i++){var seed=seeds.get(i);entries.add(seed.localBlock()+"="+effective.getOrDefault(seed,0.0));}
                RogTrace.detail(Stage.R06,"CONTACT_SEED_EFFECTIVE","credit={} owner={} offset={} end={} total={} retainedInitialF={} authority=NONE",credit,owner,offset,end,seeds.size(),entries);
            }
        }
    }

    private static final Comparator<GameImpactCredit.Target> CONTACT_ORDER=Comparator
            .comparing(GameImpactCredit.Target::ownerKey).thenComparing(GameImpactCredit.Target::localBlock)
            .thenComparing(GameImpactCredit.Target::fingerprint);

    private static boolean crossValid(GameImpactCredit.Pair pair,ForcePropagation.Result a,ForcePropagation.Result b,ForcePropagation.CrossOwnerContext graph){
        if(a.audit().crossEdges()+b.audit().crossEdges()>ForcePropagation.MAX_CROSS_EDGES)return false;
        record Cell(String owner,String local){}
        var cells=new HashSet<Cell>();var owners=new HashSet<String>();
        owners.add(pair.a().stableKey());owners.add(pair.b().stableKey());
        var verified=graph.owners();
        for(var side:List.of(a,b))for(var share:side.shares()){
            var t=share.target();if(!cells.add(new Cell(t.ownerKey(),t.localBlock())))return false;
            if(!owners.contains(t.ownerKey())&&!verified.containsKey(t.ownerKey()))return false;
            owners.add(t.ownerKey());
        }
        return owners.size()<=ForcePropagation.MAX_CROSS_OWNERS;
    }
    private static ForcePropagation.CrossOwnerContext holdCross(ForcePropagation.CrossOwnerContext original){
        return new ForcePropagation.CrossOwnerContext(){
            public Map<String,GameImpactCredit.Owner> owners(){return original.owners();}
            public List<ForcePropagation.CrossEdge> edges(GameImpactCredit.Target from,ForcePropagation.Orientation inward){return List.of();}
            public boolean unresolved(GameImpactCredit.Target from,ForcePropagation.Orientation inward){
                boolean unresolved=original.unresolved(from,inward);
                return !original.edges(from,inward).isEmpty()||unresolved;
            }
        };
    }

    /** Preserve the equal-F allocation per unique seed. Exact opposite targets
     * and exhaustive native candidate sets share a seed budget; identical
     * evidence coverage is deduplicated. A candidate set proves E only when
     * every possible material is resolved and has the same E. */
    private static ForcePropagation.Result contactPlan(String owner,List<GameImpactCredit.Target> seeds,double sideF,int reserved,
             List<Region> evidence,boolean onA,ForcePropagation.NeighborReader neighbors,
             Function<GameImpactCredit.Target,MaterialProperties> materials,GameImpactCredit.Settings forces,
             DamageModel.Settings damage,ForcePropagation.Orientation direction,
             java.util.function.Consumer<ForcePropagation.Trace> trace,ForcePropagation.CrossOwnerContext crossOwner,List<SeedAudit> seedAudits) {
        if(!Double.isFinite(sideF)||sideF<0)throw new IllegalArgumentException("invalid contact F");
        var opponents=new HashMap<GameImpactCredit.Target,Set<List<GameImpactCredit.Target>>>();
        var patchSeeds=new HashSet<GameImpactCredit.Target>();
        for(var region:evidence){
            var self=onA?region.a():region.b();var other=onA?region.b():region.a();
            if(self==null)continue;
            if(region.ownerLocalPatch())patchSeeds.add(self);
            opponents.computeIfAbsent(self,k->new HashSet<>()).add(other==null?region.opposingCandidates():List.of(other));
        }
        var effective=new HashMap<GameImpactCredit.Target,Double>();
        double split=seeds.isEmpty()?0:sideF/(seeds.size()+reserved);
        double contactDamped=0,unresolved=seeds.isEmpty()?sideF:split*reserved,forwardF=0;
        int exact=0,consensus=0,patchConsensus=0,withheld=0,missingMaterial=0,mixedMaterial=0,unproved=0;
        for(var seed:seeds){
            var candidates=opponents.get(seed);
            if(candidates==null||candidates.isEmpty()){
                unresolved+=split;withheld++;unproved++;if(RogTrace.sample(Stage.R06,"CONTACT_UNPROVED"))RogTrace.detail(Stage.R06,"CONTACT_UNPROVED","seed={} owner={} withheldF={} sideF={} seeds={}",seed,owner,split,sideF,seeds.size());continue;
            }
            if(materials.apply(seed)==null){
                unresolved+=split;withheld++;missingMaterial++;if(RogTrace.sample(Stage.R06,"CONTACT_SEED_MATERIAL_MISSING"))RogTrace.detail(Stage.R06,"CONTACT_SEED_MATERIAL_MISSING","seed={} withheldF={}",seed,split);continue;
            }
            // A complete possible set already wholly covered by independently
            // proved individual peers cannot introduce another distinct peer.
            // Do not discard an empty or partially covered observation.
            var ordered=new ArrayList<>(normalizeOpponents(candidates));ordered.sort(ContactCreditLedger::compareCandidateSets);
            double part=split/ordered.size();
            double received=0;
            for(var possible:ordered){
                int e=consensusElasticity(possible,materials);
                if(e<0){unresolved+=part;withheld++;if(e==-2)missingMaterial++;else if(e==-3)mixedMaterial++;else unproved++;if(RogTrace.sample(Stage.R06,e==-2?"CONTACT_MISSING_MATERIAL":e==-3?"CONTACT_MIXED_E":"CONTACT_UNPROVED_CANDIDATES"))RogTrace.detail(Stage.R06,e==-2?"CONTACT_MISSING_MATERIAL":e==-3?"CONTACT_MIXED_E":"CONTACT_UNPROVED_CANDIDATES","seed={} candidates={} candidateCount={} withheldF={} elasticityStatus={}",seed,com.physicaldestroydeform.diagnostics.TraceBudget.sample(possible,4),possible.size(),part,e);continue;}
                boolean patch=patchSeeds.contains(seed);
                if(patch)patchConsensus++;else if(possible.size()==1)exact++;else consensus++;
                double retained=part*DamageModel.contactElasticityTransmissionPercent(e,damage);
                received+=retained;
                contactDamped+=part-retained;
                String proof=patch?"CONTACT_PATCH_CONSENSUS":possible.size()==1?"CONTACT_EXACT":"CONTACT_CONSENSUS";
                if(RogTrace.sample(Stage.R06,proof))RogTrace.detail(Stage.R06,proof,"seed={} candidates={} candidateCount={} incomingF={} retainedF={} dampedF={} opposingE={}",seed,com.physicaldestroydeform.diagnostics.TraceBudget.sample(possible,4),possible.size(),part,retained,part-retained,e);
            }
            if(received>0){effective.put(seed,received);forwardF+=received;}
        }
        double tolerance=Math.max(1e-7,sideF*1e-7);
        if(!Double.isFinite(forwardF)||Math.abs(sideF-forwardF-contactDamped-unresolved)>tolerance)
            throw new IllegalStateException("direct contact F ledger mismatch");
        if(seedAudits!=null)seedAudits.add(new SeedAudit(owner,seeds,effective,split,sideF,reserved,unresolved,contactDamped,exact,consensus,patchConsensus,withheld,missingMaterial,mixedMaterial,unproved));
        var plan=ForcePropagation.planTargets(owner,effective,neighbors,materials,forces,damage,direction,trace,crossOwner);
        if(Math.abs(plan.inputF()-forwardF)>tolerance)throw new IllegalStateException("seed F budget mismatch");
        return new ForcePropagation.Result(plan.shares(),sideF,plan.assignedF(),plan.dampedF(),
                plan.unresolvedF()+unresolved,plan.candidateQ(),plan.finalQ(),plan.dampedQ(),
                plan.endReason()+" CONTACT_EXACT="+exact+" CONTACT_CONSENSUS="+consensus+" CONTACT_PATCH_CONSENSUS="+patchConsensus+" CONTACT_WITHHELD="+withheld+
                        " CONTACT_UNPROVED="+unproved+" CONTACT_MISSING_MATERIAL="+missingMaterial+" CONTACT_MIXED_E="+mixedMaterial,plan.audit(),
                plan.contactDampedF()+contactDamped,unresolved,contactDamped);
    }
    static Set<List<GameImpactCredit.Target>> normalizeOpponents(Set<List<GameImpactCredit.Target>> candidates){
        var exact=new HashSet<GameImpactCredit.Target>();
        for(var possible:candidates)if(possible.size()==1)exact.add(possible.getFirst());
        if(exact.isEmpty())return candidates;
        var normalized=new HashSet<>(candidates);
        normalized.removeIf(possible->possible.size()>1&&exact.containsAll(possible));
        return normalized;
    }
    private static int consensusElasticity(List<GameImpactCredit.Target> possible,
            Function<GameImpactCredit.Target,MaterialProperties> materials){
        int e=-1;
        for(var target:possible){
            var material=materials.apply(target);
            if(material==null)return -2;
            if(e>=0&&e!=material.elasticityPercent())return -3;
            e=material.elasticityPercent();
        }
        return e;
    }
    private static int compareCandidateSets(List<GameImpactCredit.Target> a,List<GameImpactCredit.Target> b){
        for(int i=0;i<Math.min(a.size(),b.size());i++){
            int c=CONTACT_ORDER.compare(a.get(i),b.get(i));if(c!=0)return c;
        }
        return Integer.compare(a.size(),b.size());
    }
    private static List<Share> seal(ForcePropagation.Result plan,double sideQcap) {
        double factor=plan.finalQ()>sideQcap && plan.finalQ()>0?
                Math.max(0,sideQcap/plan.finalQ()):1;
        var out=new ArrayList<Share>(plan.shares().size());
        for(var node:plan.shares()) {
            double q=node.finalQ()*factor;
            out.add(new Share(node.target(),q<ForcePropagation.MIN_ISSUED_Q?0:q));
        }
        return List.copyOf(out);
    }

    private static List<Share> split(Set<GameImpactCredit.Target> targets,double total) {
        if(targets.isEmpty() || total==0)return List.of();
        var ordered=new ArrayList<>(targets);
        ordered.sort(Comparator.comparing(GameImpactCredit.Target::ownerKey)
                .thenComparing(GameImpactCredit.Target::localBlock)
                .thenComparing(GameImpactCredit.Target::fingerprint));
        var out=new ArrayList<Share>(ordered.size());
        double prior=0;
        for(int i=0;i<ordered.size();i++) {
            double amount=i==ordered.size()-1?total-prior:total/ordered.size();
            out.add(new Share(ordered.get(i),amount));
            prior+=amount;
        }
        return List.copyOf(out);
    }
    private static Grant empty(CreditId id,Source source,Status status,long tick,int regions) {
        return new Grant(id,source,status,tick,0,List.of(),List.of(),0,regions,ALGORITHM_REVISION);
    }
}

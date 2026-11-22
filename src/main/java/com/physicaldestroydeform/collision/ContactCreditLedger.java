package com.physicaldestroydeform.collision;

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
    public static final String ALGORITHM_REVISION = "PDD-IDS-1.0.9/0.6.1-CONTACT-ELASTIC-CHAIN";
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
    /** One verified source-region observation. Null on one side means unverified. */
    public record Region(GameImpactCredit.Target a, GameImpactCredit.Target b) {
        public Region {
            if (a == null && b == null) throw new IllegalArgumentException("empty contact region");
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
        Objects.requireNonNull(pair); Objects.requireNonNull(decision);
        Objects.requireNonNull(source); Objects.requireNonNull(evidence);
        CreditId id=new CreditId(pair,Math.max(1,decision.episodeGeneration()));
        if (!decision.credited())
            return empty(id,source,Status.INSUFFICIENT_EVIDENCE,decision.tick(),evidence.size());
        if (recorded.containsKey(id))
            return empty(id,source,Status.DUPLICATE_CAUSE,decision.tick(),evidence.size());
        if (source==Source.SPECIAL_CALLBACK_HINT)
            return empty(id,source,Status.HINT_NOT_AUTHORITATIVE,decision.tick(),evidence.size());
        if (recorded.size()>=MAX_ENTRIES)
            return empty(id,source,Status.LEDGER_CAPACITY,decision.tick(),evidence.size());

        // Linked source contacts may report the same target repeatedly. They
        // only increase observation count, never target Q or source Q.
        if (evidence.isEmpty() || evidence.size()>MAX_REGIONS)
            return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
        Set<GameImpactCredit.Target> a=new HashSet<>(), b=new HashSet<>();
        Map<String,String> aCells=new HashMap<>(), bCells=new HashMap<>();
        for(Region region:evidence) {
            if (region==null) return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
            if(region.a()!=null) {
                if(!region.a().ownerKey().equals(pair.a().stableKey()))
                    return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
                String earlier=aCells.putIfAbsent(region.a().localBlock(),region.a().fingerprint());
                if(earlier!=null && !earlier.equals(region.a().fingerprint()))
                    return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
                a.add(region.a());
            }
            if(region.b()!=null) {
                if(!region.b().ownerKey().equals(pair.b().stableKey()))
                    return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
                String earlier=bCells.putIfAbsent(region.b().localBlock(),region.b().fingerprint());
                if(earlier!=null && !earlier.equals(region.b().fingerprint()))
                    return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
                b.add(region.b());
            }
        }
        if(a.size()>MAX_TARGETS_PER_SIDE || b.size()>MAX_TARGETS_PER_SIDE || a.isEmpty()&&b.isEmpty())
            return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());

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
                forces,damage,directions,trace,materials,false,null);
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
                directions==null?Map.of():directions,trace,legacyMaterials,true,crossOwner);
    }
    private Grant issuePropagatedInternal(GameImpactCredit.Pair pair,GameImpactCredit.Decision decision,
                                 Source source,List<Region> evidence,ForcePropagation.NeighborReader neighbors,
                                 Function<GameImpactCredit.Target,MaterialProperties> materials,
                                 GameImpactCredit.Settings forces,DamageModel.Settings damage,
                                 Map<String,ForcePropagation.Orientation> directions,
                                 java.util.function.Consumer<ForcePropagation.Trace> trace,
                                 Function<String,MaterialProperties> legacyMaterials,boolean bilateral,
                                 ForcePropagation.CrossOwnerContext crossOwner) {
        // Preserve every existing owner/region/episode validation and one-credit rule.
        lastPropagationFailure="NONE";
        Grant base=issue(pair,decision,source,evidence);
        if(!base.credited()) {
            lastPropagationFailure="SOURCE_OR_EVIDENCE_GATE_"+base.status();
            return base;
        }
        List<ForcePropagation.Trace> traces=trace==null?null:new ArrayList<>();
        Grant issued;
        try {
            List<GameImpactCredit.Target> seedsA=base.a().stream().map(Share::target).toList();
            List<GameImpactCredit.Target> seedsB=base.b().stream().map(Share::target).toList();
            double sideF=decision.peakForce()/2;
            if(crossOwner!=null){
                var owners=crossOwner.owners();
                for(var owner:List.of(pair.a(),pair.b())){
                    var candidate=owners.get(owner.stableKey());
                    if(candidate!=null&&!candidate.equals(owner))throw new IllegalArgumentException("stale source owner generation");
                }
            }
            var pa=bilateral?contactPlan(pair.a().stableKey(),seedsA,sideF,evidence,true,neighbors,materials,forces,
                    damage,directions.getOrDefault(pair.a().stableKey(),ForcePropagation.Orientation.unknown()),
                    traces==null?null:traces::add,crossOwner==null?null:crossOwner.forSide(pair.a().stableKey(),pair.b().stableKey()))
                    :directions==null
                    ?ForcePropagation.plan(pair.a().stableKey(),seedsA,sideF,neighbors,legacyMaterials,forces,damage)
                    :ForcePropagation.planTargets(pair.a().stableKey(),seedsA,sideF,neighbors,materials,forces,damage,
                            directions.getOrDefault(pair.a().stableKey(),ForcePropagation.Orientation.unknown()),
                            traces==null?null:traces::add);
            var pb=bilateral?contactPlan(pair.b().stableKey(),seedsB,sideF,evidence,false,neighbors,materials,forces,
                    damage,directions.getOrDefault(pair.b().stableKey(),ForcePropagation.Orientation.unknown()),
                    traces==null?null:traces::add,crossOwner==null?null:crossOwner.forSide(pair.b().stableKey(),pair.a().stableKey()))
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
                pa=contactPlan(pair.a().stableKey(),seedsA,sideF,evidence,true,neighbors,materials,forces,damage,
                        directions.getOrDefault(pair.a().stableKey(),ForcePropagation.Orientation.unknown()),
                        traces==null?null:traces::add,holdCross(crossOwner.forSide(pair.a().stableKey(),pair.b().stableKey())));
                pb=contactPlan(pair.b().stableKey(),seedsB,sideF,evidence,false,neighbors,materials,forces,damage,
                        directions.getOrDefault(pair.b().stableKey(),ForcePropagation.Orientation.unknown()),
                        traces==null?null:traces::add,holdCross(crossOwner.forSide(pair.b().stableKey(),pair.a().stableKey())));
            }
            var a=seal(pa,decision.allocation().sideA());
            var b=seal(pb,decision.allocation().sideB());
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
            Grant grant=new Grant(base.id(),source,Status.ISSUED,base.tick(),decision.issuedQ(),
                    a,b,Math.max(0,unused),base.observedRegions(),bilateral?ALGORITHM_REVISION:LEGACY_ALGORITHM_REVISION,pa,pb,additionalOwners);
            recorded.put(grant.id(),new Stored(source,base.tick(),grant));
            issued=grant;
        }catch(RuntimeException unsafe) {
            // Diagnostic reason is not persisted; only verified plans can mint Q.
            lastPropagationFailure=unsafe.getClass().getSimpleName()+":"+
                    String.valueOf(unsafe.getMessage());
            // Nothing is committed to the preview or source on a failed plan.
            recorded.remove(base.id());
            return empty(base.id(),source,Status.INVALID_TARGET_SET,base.tick(),evidence.size());
        }
        // A diagnostic consumer must never revoke the already accepted credit.
        if(traces!=null)for(var event:traces) {
            try {trace.accept(event);}catch(RuntimeException ignored) {break;}
        }
        return issued;
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

    /** Preserve each side's original equal-F allocation per unique seed. Within
     * a seed, distinct opposite native contacts share that seed's budget equally.
     * A repeated witness cannot increase a pair's weight or debit the same
     * share twice. No material is inferred from a singleton or other block. */
    private static ForcePropagation.Result contactPlan(String owner,List<GameImpactCredit.Target> seeds,double sideF,
             List<Region> evidence,boolean onA,ForcePropagation.NeighborReader neighbors,
             Function<GameImpactCredit.Target,MaterialProperties> materials,GameImpactCredit.Settings forces,
             DamageModel.Settings damage,ForcePropagation.Orientation direction,
             java.util.function.Consumer<ForcePropagation.Trace> trace,ForcePropagation.CrossOwnerContext crossOwner) {
        if(!Double.isFinite(sideF)||sideF<0)throw new IllegalArgumentException("invalid contact F");
        var opponents=new HashMap<GameImpactCredit.Target,Set<GameImpactCredit.Target>>();
        var unpaired=new HashSet<GameImpactCredit.Target>();
        for(var region:evidence){
            var self=onA?region.a():region.b();var other=onA?region.b():region.a();
            if(self==null)continue;
            if(other==null){unpaired.add(self);continue;}
            opponents.computeIfAbsent(self,k->new HashSet<>()).add(other);
        }
        var effective=new HashMap<GameImpactCredit.Target,Double>();
        double contactDamped=0,unresolved=seeds.isEmpty()?sideF:0,forwardF=0;
        double split=seeds.isEmpty()?0:sideF/seeds.size();
        for(var seed:seeds){
            var candidates=opponents.get(seed);
            if(candidates==null||candidates.isEmpty()||materials.apply(seed)==null){
                unresolved+=split;continue;
            }
            var ordered=new ArrayList<>(candidates);ordered.sort(CONTACT_ORDER);
            double part=split/(ordered.size()+(unpaired.contains(seed)?1:0));
            if(unpaired.contains(seed))unresolved+=part;
            double received=0;
            for(var other:ordered){
                var opposite=materials.apply(other);
                if(opposite==null){unresolved+=part;continue;}
                double retained=part*DamageModel.elasticityTransmission(opposite,damage);
                received+=retained;
                contactDamped+=part-retained;
            }
            if(received>0){effective.put(seed,received);forwardF+=received;}
        }
        double tolerance=Math.max(1e-7,sideF*1e-7);
        if(!Double.isFinite(forwardF)||Math.abs(sideF-forwardF-contactDamped-unresolved)>tolerance)
            throw new IllegalStateException("direct contact F ledger mismatch");
        var plan=ForcePropagation.planTargets(owner,effective,neighbors,materials,forces,damage,direction,trace,crossOwner);
        if(Math.abs(plan.inputF()-forwardF)>tolerance)throw new IllegalStateException("seed F budget mismatch");
        return new ForcePropagation.Result(plan.shares(),sideF,plan.assignedF(),plan.dampedF(),
                plan.unresolvedF()+unresolved,plan.candidateQ(),plan.finalQ(),plan.dampedQ(),plan.endReason(),plan.audit(),
                plan.contactDampedF()+contactDamped);
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

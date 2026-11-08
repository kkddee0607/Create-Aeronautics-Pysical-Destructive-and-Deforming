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
 * Bounded owner-local F-first prediction. World and Plot block coordinates
 * remain in their respective stable lattices throughout one server-thread
 * event. Native normals select allowed edges; they never identify a block.
 * Neither collision impulse nor world mutation is inferred from this graph.
 */
public final class ForcePropagation {
    // 128 truncated a radius-3.5 Plot and a small patch of terrain. The bound
    // still prevents unbounded work on huge connected World colliders.
    public static final int MAX_NODES_PER_SIDE = 32_768;
    public static final double MIN_REMAINING_F = 1.0 / 256.0;
    public static final double MIN_PROPAGATED_F = 1.0;
    public static final double MIN_ISSUED_Q = 1.0;
    private static final int[][] DIRECTIONS = {{-1,0,0},{0,-1,0},{0,0,-1},{0,0,1},{0,1,0},{1,0,0}};
    private static final Comparator<GameImpactCredit.Target> TARGET_ORDER =
            Comparator.comparing(GameImpactCredit.Target::localBlock)
                    .thenComparing(GameImpactCredit.Target::fingerprint);

    public enum ProbeStatus { PRESENT, EMPTY, UNKNOWN }
    public record Probe(ProbeStatus status, GameImpactCredit.Target target) {
        public Probe {
            Objects.requireNonNull(status);
            if ((status == ProbeStatus.PRESENT) != (target != null))
                throw new IllegalArgumentException("invalid neighbor evidence");
        }
        public static Probe present(GameImpactCredit.Target target) { return new Probe(ProbeStatus.PRESENT, target); }
        public static Probe empty() { return new Probe(ProbeStatus.EMPTY, null); }
        public static Probe unknown() { return new Probe(ProbeStatus.UNKNOWN, null); }
    }
    @FunctionalInterface public interface NeighborReader {
        /** Must return a current, owner-verified block or an explicit empty/unknown result. */
        Probe read(GameImpactCredit.Target from, int x, int y, int z);
    }
    public record Share(GameImpactCredit.Target target, double allocatedF,
                        double rawQ, double finalQ, int layer, int elasticityPercent) {}
    /** Ephemeral proof of an actual forwarded payment. Never store this in a persistent credit. */
    public sealed interface Trace permits Edge, Receipt, Node, Cutoff {}
    public record Edge(GameImpactCredit.Target parent, GameImpactCredit.Target child,
                       int layer, double parentReceivedF, double parentLocalF,
                       double parentOutgoingF, double directionWeight, double sentF) implements Trace {}
    public record Receipt(GameImpactCredit.Target child, int layer, int parentCount,
                          double totalReceivedF, boolean processedOnce) implements Trace {}
    public record Node(GameImpactCredit.Target target, int layer, double receivedF,
                       double localF, double forwardedF, double dampedF, double terminalRecoveredF,
                       double discardedF, double rawQ, double finalQ, int eligibleChildren,
                       int paidChildren, String decision) implements Trace {}
    public record Cutoff(GameImpactCredit.Target from, GameImpactCredit.Target to,
                         int layer, double rejectedF, String reason) implements Trace {}
    public record Audit(double thresholdDiscardF, double thresholdDiscardQ,
                        double terminalRecoveredF, int terminalNodes, int maxDepth,
                        int paidEdges, int mergedNodes, int safetyBlockedNodes) {}
    public record Result(List<Share> shares, double inputF, double assignedF,
                         double dampedF, double unresolvedF, double candidateQ,
                         double finalQ, double dampedQ, String endReason, Audit audit) {
        public Result {
            shares = List.copyOf(shares);
            Objects.requireNonNull(audit);
        }
        public Result(List<Share> shares, double inputF, double assignedF,
                      double dampedF, double unresolvedF, double candidateQ,
                      double finalQ, double dampedQ, String endReason) {
            this(shares,inputF,assignedF,dampedF,unresolvedF,candidateQ,finalQ,dampedQ,
                    endReason,new Audit(0,0,0,0,0,0,0,0));
        }
    }
    private static final class Pending {
        final GameImpactCredit.Target target;
        final double force, rawQ;
        final int layer, elasticity;
        double finalQ;
        Pending(GameImpactCredit.Target target, double force, double rawQ, int layer, int elasticity) {
            this.target=target;this.force=force;this.rawQ=rawQ;this.finalQ=rawQ;
            this.layer=layer;this.elasticity=elasticity;
        }
    }
    private ForcePropagation() {}

    /** Equal first-contact seeds receive equal F. Later breadth layers receive half
     * the surviving F, split equally within that layer; a PROVED terminal takes
     * all remaining F. An elastic layer attenuates the preceding layer's pending
     * Q once and the outgoing F once (arithmetic mean for parallel blocks).
     * This parallel-layer choice is provisional because v0.1 has no branch rule. */
    public static Result plan(String ownerKey, List<GameImpactCredit.Target> seeds, double inputF,
                              NeighborReader neighbors,
                              Function<String,MaterialProperties> materials,
                              GameImpactCredit.Settings forceSettings,
                              DamageModel.Settings damageSettings) {
        Objects.requireNonNull(ownerKey);Objects.requireNonNull(seeds);
        Objects.requireNonNull(neighbors);Objects.requireNonNull(materials);
        Objects.requireNonNull(forceSettings);Objects.requireNonNull(damageSettings);
        if (!Double.isFinite(inputF) || inputF<0) throw new IllegalArgumentException("invalid source F");
        if (inputF == 0 || seeds.isEmpty())
            return new Result(List.of(),inputF,0,0,inputF,0,0,0,"NO_VERIFIED_SEEDS");
        var seen=new HashMap<String, GameImpactCredit.Target>();
        for (var seed:seeds) add(seen, ownerKey, seed);
        if (seen.size()>MAX_NODES_PER_SIDE) throw new IllegalArgumentException("too many contact seeds");
        List<GameImpactCredit.Target> layer=new ArrayList<>(seen.values());
        layer.sort(TARGET_ORDER);
        List<Pending> pending=new ArrayList<>();
        List<Pending> previous=List.of();
        double remaining=inputF, allocated=0, dampedF=0, dampedQ=0;
        boolean uncertain=false;
        String reason="BOUNDED";
        for (int depth=0;!layer.isEmpty();depth++) {
            List<GameImpactCredit.Target> next=new ArrayList<>();
            Set<String> nextSeen=new HashSet<>();
            boolean capped=false;
            for (var node:layer) {
                String[] parts=node.localBlock().split(",", -1);
                if (parts.length!=3)throw new IllegalArgumentException("bad target coordinates");
                int x=Integer.parseInt(parts[0]),y=Integer.parseInt(parts[1]),z=Integer.parseInt(parts[2]);
                for (var step:DIRECTIONS) {
                    int nx,ny,nz;
                    try {nx=Math.addExact(x,step[0]);ny=Math.addExact(y,step[1]);nz=Math.addExact(z,step[2]);}
                    catch(ArithmeticException outOfWorld){uncertain=true;continue;}
                    Probe found=Objects.requireNonNull(neighbors.read(node,nx,ny,nz),"missing neighbor proof");
                    if(found.status()==ProbeStatus.UNKNOWN) {uncertain=true;continue;}
                    if(found.status()!=ProbeStatus.PRESENT)continue;
                    var target=found.target();
                    if(!ownerKey.equals(target.ownerKey()) ||
                            !target.localBlock().equals(nx+","+ny+","+nz))
                        throw new IllegalArgumentException("neighbor owner/coordinate mismatch");
                    var old=seen.get(target.localBlock());
                    if(old!=null) {
                        if(!old.fingerprint().equals(target.fingerprint()))
                            throw new IllegalArgumentException("conflicting neighbor fingerprint");
                    }else if(seen.size()>=MAX_NODES_PER_SIDE) capped=true;
                    else {
                        add(seen,ownerKey,target);
                        if(nextSeen.add(target.localBlock()))next.add(target);
                    }
                }
            }
            next.sort(TARGET_ORDER);
            boolean terminal=next.isEmpty() && !capped && !uncertain;
            double layerF=terminal?remaining:remaining/2;
            double perBlockF=layerF/layer.size();
            List<Pending> current=new ArrayList<>();
            double sumTransmission=0;
            for (var target:layer) {
                MaterialProperties material=materials.apply(target.fingerprint());
                if(material==null)throw new IllegalArgumentException("unresolved material fingerprint");
                var node=new Pending(target,perBlockF,forceSettings.candidate(perBlockF),
                        depth,material.elasticityPercent());
                current.add(node);pending.add(node);
                sumTransmission+=DamageModel.elasticityTransmission(material,damageSettings);
            }
            // The pending Q of the preceding layer has not been persisted yet.
            double transmission=sumTransmission/layer.size();
            if(!previous.isEmpty()) for(var prior:previous) {
                double reduction=prior.finalQ*(1-transmission);
                prior.finalQ-=reduction; dampedQ+=reduction;
            }
            allocated+=layerF;
            if (terminal) {remaining=0;reason="VERIFIED_TERMINAL";break;}
            double onward=remaining-layerF;
            double reduction=onward*(1-transmission);
            dampedF+=reduction;
            remaining=onward-reduction;
            previous=current;
            if(capped)uncertain=true;
            if(next.isEmpty()) {reason=uncertain?"UNVERIFIED_BOUNDARY":"NO_NEIGHBOR";break;}
            if(remaining<MIN_REMAINING_F) {reason="MIN_REMAINING_F";break;}
            layer=next;
        }
        var shares=new ArrayList<Share>(pending.size());
        double candidate=0,finalQ=0;
        for(var p:pending) {
            candidate+=p.rawQ;finalQ+=p.finalQ;
            shares.add(new Share(p.target,p.force,p.rawQ,p.finalQ,p.layer,p.elasticity));
        }
        double tol=1e-7*Math.max(1,inputF);
        if(!Double.isFinite(candidate)||!Double.isFinite(finalQ)||
                Math.abs(allocated+dampedF+remaining-inputF)>tol ||
                finalQ>candidate+1e-7 || finalQ < 0)
            throw new IllegalStateException("F/Q propagation accounting error");
        return new Result(shares,inputF,allocated,dampedF,Math.max(0,remaining),
                candidate,finalQ,dampedQ,reason);
    }

    /** A verified owner-local native manifold normal, or explicit UNKNOWN.
     * This field is an inward direction for gameplay F propagation only.
     * It does not claim the exact impact point, substep or solver impulse. */
    public record Orientation(FrameMath.Vec inward, String evidence) {
        public Orientation {
            Objects.requireNonNull(evidence);
            if (inward != null) inward=inward.unit();
        }
        public boolean verified() {return inward!=null;}
        public static Orientation unknown() {return new Orientation(null,"UNKNOWN");}
    }
    // Actual production geometry is bounded. Neither radius nor a safety cap is
    // a real block boundary, so no terminal remainder may be manufactured there.
    // No depth-based loss. A node is visited once in an acyclic BFS; only the
    // finite node cap and the explicit 1.0 F cutoff bound traversal.
    public static final int MAX_UNKNOWN_DEPTH=Integer.MAX_VALUE;
    public static final int MAX_NATIVE_DEPTH=Integer.MAX_VALUE;
    // A geometric cone previously vetoed ALL initial lateral movement outside
    // the native seed cells. Those seeds can be sparse solver corner witnesses.
    // spreadFix uses owner-verified block neighbors plus per-parent F decay,
    // not a synthetic contact-hull or hard-coded cone radius, to bound spread.
    private static final double GEOMETRY_EPS=1.0e-10;

    private static final class Flow {
        final GameImpactCredit.Target target;
        final int x,y,z,depth,elasticity;
        final java.util.Map<Flow,Double> parents=new java.util.HashMap<>();
        double incoming,localF,rawQ,finalQ,forwarded,damped,terminalBonus,discarded;
        int eligibleChildren,paidChildren;
        String decision="UNPROCESSED";
        Flow(GameImpactCredit.Target t,int depth,int e) {
            this.target=t;this.depth=depth;this.elasticity=e;
            String[] p=t.localBlock().split(",",-1);
            if(p.length!=3)throw new IllegalArgumentException("bad target coordinates");
            x=Integer.parseInt(p[0]);y=Integer.parseInt(p[1]);z=Integer.parseInt(p[2]);
        }
    }

    /**
     * Direction-aware WORLD/Plot engine. Each parent sends its own outgoing
     * F to its eligible unvisited children; a child with multiple parents
     * sums incoming F and is processed exactly once. Elasticity affects only
     * its upstream graph parents' provisional Q and its OWN outgoing F, not
     * unrelated sibling branches. No global mean transmission is used.
     *
     * UNKNOWN direction uses an auditable equal-weight traversal with no depth cap. Native
     * inward direction reserves 70% of outgoing F for inward children and 30%
     * for lateral children when both exist; backward edges remain forbidden. A Plot
     * may move in world coordinates while its block-local positions stay fixed.
     * Re-sampling a world pose for every BFS layer would mix physics epochs.
     */
    public static Result plan(String ownerKey,List<GameImpactCredit.Target> seeds,double inputF,
                              NeighborReader neighbors,Function<String,MaterialProperties> materials,
                              GameImpactCredit.Settings forces,DamageModel.Settings damage,
                              Orientation direction) {
        return plan(ownerKey,seeds,inputF,neighbors,materials,forces,damage,direction,null);
    }

    /** Optional trace is a per-approved-event, non-authoritative diagnostic sink.
     * It cannot alter the collision decision, ledger or Q computation. */
    public static Result plan(String ownerKey,List<GameImpactCredit.Target> seeds,double inputF,
                              NeighborReader neighbors,Function<String,MaterialProperties> materials,
                              GameImpactCredit.Settings forces,DamageModel.Settings damage,
                              Orientation direction,java.util.function.Consumer<Trace> trace) {
        Objects.requireNonNull(ownerKey);Objects.requireNonNull(seeds);
        Objects.requireNonNull(neighbors);Objects.requireNonNull(materials);
        Objects.requireNonNull(forces);Objects.requireNonNull(damage);Objects.requireNonNull(direction);
        if(!Double.isFinite(inputF)||inputF<0)throw new IllegalArgumentException("invalid F");
        if(inputF==0||seeds.isEmpty())return new Result(List.of(),inputF,0,0,inputF,0,0,0,
                "NO_VERIFIED_SEEDS");
        var seen=new HashMap<String,Flow>();
        for(var t:seeds) {
            if(t==null||!ownerKey.equals(t.ownerKey()))throw new IllegalArgumentException("invalid seed owner");
            var old=seen.get(t.localBlock());
            if(old!=null) {
                if(!old.target.fingerprint().equals(t.fingerprint()))
                    throw new IllegalArgumentException("conflicting seed fingerprint");
                continue;
            }
            var material=Objects.requireNonNull(materials.apply(t.fingerprint()),"missing seed material");
            seen.put(t.localBlock(),new Flow(t,0,material.elasticityPercent()));
        }
        if(seen.size()>MAX_NODES_PER_SIDE)throw new IllegalArgumentException("too many contact seeds");
        List<Flow> layer=new ArrayList<>(seen.values());
        layer.sort(Comparator.comparing(n->n.target.localBlock()));
        for(var node:layer)node.incoming=inputF/layer.size();
        var all=new ArrayList<Flow>();
        double assigned=0,dampedF=0,unresolved=0,discardF=0,terminalF=0;
        int terminalNodes=0,maxDepth=0,paidEdges=0,mergedNodes=0,blockedNodes=0;
        boolean capped=false,unknown=false,belowFloor=false;
        while(!layer.isEmpty()) {
            int depth=layer.getFirst().depth;
            maxDepth=Math.max(maxDepth,depth);
            var nextMap=new HashMap<String,Flow>();
            var links=new HashMap<Flow,List<Flow>>();
            var blocked=new HashMap<Flow,String>();
            // Freeze the entire level's reachable graph before calculating Q.
            for(var node:layer) {
                var children=new ArrayList<Flow>();
                links.put(node,children);
                if(node.incoming<MIN_PROPAGATED_F)continue;
                for(var step:DIRECTIONS) {
                    int x,y,z;
                    try {
                        x=Math.addExact(node.x,step[0]);y=Math.addExact(node.y,step[1]);
                        z=Math.addExact(node.z,step[2]);
                    }catch(ArithmeticException overflow){blocked.put(node,"COORDINATE_OVERFLOW");continue;}
                    if(!forwardStep(direction,step))continue;
                    Probe proof=Objects.requireNonNull(neighbors.read(node.target,x,y,z),"missing neighbor proof");
                    if(proof.status()==ProbeStatus.UNKNOWN){blocked.put(node,"UNKNOWN_NEIGHBOR");unknown=true;continue;}
                    if(proof.status()!=ProbeStatus.PRESENT)continue;
                    var t=proof.target();
                    if(!ownerKey.equals(t.ownerKey())||!t.localBlock().equals(x+","+y+","+z))
                        throw new IllegalArgumentException("neighbor owner/coordinates mismatch");
                    var prior=seen.get(t.localBlock());
                    if(prior!=null){
                        if(!prior.target.fingerprint().equals(t.fingerprint()))
                            throw new IllegalArgumentException("changed visited material fingerprint");
                        continue;
                    }
                    var child=nextMap.get(t.localBlock());
                    if(child==null) {
                        if(seen.size()+nextMap.size()>=MAX_NODES_PER_SIDE){
                            capped=true;blocked.put(node,"NODE_WORK_BUDGET");continue;
                        }
                        var material=Objects.requireNonNull(materials.apply(t.fingerprint()),"missing neighbor material");
                        child=new Flow(t,depth+1,material.elasticityPercent());
                        nextMap.put(t.localBlock(),child);
                    } else if(!child.target.fingerprint().equals(t.fingerprint()))
                        throw new IllegalArgumentException("conflicting branch fingerprint");
                    children.add(child);
                }
                children.sort(Comparator.comparing(n->n.target.localBlock()));
            }
            for(var node:layer) {
                if(node.incoming<MIN_PROPAGATED_F){
                    discardF+=node.incoming;
                    node.discarded=node.incoming;
                    node.decision="F_BELOW_ONE";
                    belowFloor=true;
                    if(trace!=null)trace.accept(new Cutoff(node.target,null,depth,node.incoming,"NODE_F_BELOW_ONE"));
                    continue;
                }
                List<Flow> children=links.get(node);
                node.eligibleChildren=children.size();
                boolean unsafe=blocked.containsKey(node);
                boolean terminal=children.isEmpty()&&!unsafe;
                node.localF=terminal?node.incoming:node.incoming/2;
                node.terminalBonus=terminal?node.incoming/2:0;
                assigned+=node.localF;
                if(terminal){terminalF+=node.terminalBonus;terminalNodes++;}
                node.rawQ=forces.candidate(node.localF);
                node.finalQ=node.rawQ;
                all.add(node);
                if(unsafe){
                    double withheld=node.incoming-node.localF;
                    unresolved+=withheld;
                    blockedNodes++;
                    node.decision="SAFETY_"+blocked.get(node);
                    if(trace!=null)trace.accept(new Cutoff(node.target,null,depth,withheld,node.decision));
                    continue;
                }
                if(terminal){node.decision="VERIFIED_TERMINAL";continue;}
                // Elasticity subtracts from the outgoing HALF, never from localF.
                double pending=node.incoming-node.localF;
                double forward=pending*DamageModel.elasticityTransmissionPercent(node.elasticity,damage);
                node.damped=pending-forward;
                dampedF+=node.damped;
                double[] weights=directionWeights(direction,children,node);
                double sent=0;
                for(int i=0;i<children.size();i++) {
                    var child=children.get(i);
                    double part=i==children.size()-1?forward-sent:forward*weights[i];
                    sent+=part;
                    // Never create an F<1 edge. Do not silently reassign it
                    // to the remaining children or invent a physical terminal.
                    if(part<MIN_PROPAGATED_F){
                        discardF+=part;node.discarded+=part;belowFloor=true;
                        if(trace!=null)trace.accept(new Cutoff(node.target,child.target,
                                depth,part,"EDGE_F_BELOW_ONE"));
                        continue;
                    }
                    child.incoming+=part;
                    child.parents.merge(node,part,Double::sum);
                    node.forwarded+=part;node.paidChildren++;paidEdges++;
                    if(trace!=null)trace.accept(new Edge(node.target,child.target,depth,
                            node.incoming,node.localF,forward,weights[i],part));
                }
                node.decision=node.paidChildren>0?"FORWARDED":"F_BELOW_ONE";
            }
            // No unpaid candidate gets an entry or a phantom child Q discount.
            var next=new ArrayList<Flow>();
            for(var child:nextMap.values())if(child.incoming>=MIN_PROPAGATED_F)next.add(child);
            next.sort(Comparator.comparing(n->n.target.localBlock()));
            if(trace!=null)for(var child:next)trace.accept(new Receipt(child.target,
                    child.depth,child.parents.size(),child.incoming,true));
            for(var child:next)if(child.parents.size()>1)mergedNodes++;
            // Only paid child edges may discount their actual parents' pending Q.
            var sum=new HashMap<Flow,Double>();
            var weighted=new HashMap<Flow,Double>();
            for(var child:next) {
                double t=DamageModel.elasticityTransmissionPercent(child.elasticity,damage);
                for(var entry:child.parents.entrySet()) {
                    sum.merge(entry.getKey(),entry.getValue(),Double::sum);
                    weighted.merge(entry.getKey(),entry.getValue()*t,Double::sum);
                }
            }
            for(var entry:sum.entrySet())if(entry.getValue()>0)
                entry.getKey().finalQ*=weighted.get(entry.getKey())/entry.getValue();
            for(var child:next)seen.put(child.target.localBlock(),child);
            layer=next;
        }
        var shares=new ArrayList<Share>(all.size());
        double candidateQ=0,finalQ=0,dampedQ=0,discardQ=0;
        for(var n:all) {
            candidateQ+=n.rawQ;
            double pathDiscount=n.rawQ-n.finalQ;
            dampedQ+=pathDiscount;
            if(n.finalQ<MIN_ISSUED_Q){discardQ+=n.finalQ;n.finalQ=0;}
            finalQ+=n.finalQ;
            shares.add(new Share(n.target,n.localF,n.rawQ,n.finalQ,n.depth,n.elasticity));
            if(trace!=null)trace.accept(new Node(n.target,n.depth,n.incoming,
                    n.localF,n.forwarded,n.damped,n.terminalBonus,n.discarded,
                    n.rawQ,n.finalQ,n.eligibleChildren,n.paidChildren,n.decision));
        }
        double accounted=assigned+dampedF+discardF+unresolved;
        double tolerance=1e-7*Math.max(1,inputF);
        if(!Double.isFinite(accounted)||Math.abs(accounted-inputF)>tolerance||
                !Double.isFinite(finalQ)||Math.abs(candidateQ-finalQ-dampedQ-discardQ)>tolerance||
                candidateQ<0||finalQ<0||discardF<0||unresolved<0)
            throw new IllegalStateException("duperFix F/Q ledger mismatch");
        String end=capped?"NODE_WORK_BUDGET":unknown?"UNVERIFIED_BOUNDARY":
                unresolved>0?"SAFETY_BOUNDARY":belowFloor?"F_BELOW_ONE":"VERIFIED_TERMINAL";
        return new Result(shares,inputF,assigned,dampedF,unresolved,candidateQ,finalQ,dampedQ,
                end,new Audit(discardF,discardQ,terminalF,terminalNodes,maxDepth,
                        paidEdges,mergedNodes,blockedNodes));
    }
    /**
     * spreadFix: split the outgoing F between verified inward (70%) and tangent
     * (30%) children, then normalize within each occupied group. Missing groups
     * surrender their reservation to the remaining verified children. An
     * oblique inward normal is weighted by its exact positive projection, not
     * snapped to an axis. All payments still debit ONE parent's outgoing F.
     */
    private static double[] directionWeights(Orientation direction,List<Flow> children,Flow parent) {
        int size=children.size();
        double[] weights=new double[size];
        if(size==0)return weights;
        if(!direction.verified()) {
            java.util.Arrays.fill(weights,1.0/size);
            return weights;
        }
        FrameMath.Vec n=direction.inward();
        double[] inward=new double[size];
        double totalInward=0;
        int tangentCount=0;
        for(int i=0;i<size;i++) {
            var child=children.get(i);
            double dot=(child.x-parent.x)*n.x()+(child.y-parent.y)*n.y()
                    +(child.z-parent.z)*n.z();
            if(dot>GEOMETRY_EPS) {
                inward[i]=dot;
                totalInward+=dot;
            } else tangentCount++;
        }
        double inwardShare=totalInward==0?0:tangentCount==0?1:0.70;
        double tangentShare=tangentCount==0?0:totalInward==0?1:0.30;
        for(int i=0;i<size;i++)weights[i]=inward[i]>0
                ?inwardShare*inward[i]/totalInward
                :tangentShare/tangentCount;
        return weights;
    }

    /** No backward edge, including at an oblique normal. Perpendicular
     * neighbors are eligible from the very first verified contact cell. */
    private static boolean forwardStep(Orientation direction,int[] step) {
        if(!direction.verified())return true;
        FrameMath.Vec n=direction.inward();
        return n.x()*step[0]+n.y()*step[1]+n.z()*step[2]>=-GEOMETRY_EPS;
    }

    private static void add(Map<String,GameImpactCredit.Target> seen,String owner,GameImpactCredit.Target target) {
        Objects.requireNonNull(target);
        if(!owner.equals(target.ownerKey()))throw new IllegalArgumentException("wrong target owner");
        GameImpactCredit.Target earlier=seen.putIfAbsent(target.localBlock(),target);
        if(earlier!=null && !earlier.fingerprint().equals(target.fingerprint()))
            throw new IllegalArgumentException("conflicting target identity");
    }
}

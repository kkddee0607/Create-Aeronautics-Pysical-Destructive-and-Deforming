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
    public static final int MAX_NODES_PER_SIDE = 128;
    public static final double MIN_REMAINING_F = 1.0 / 256.0;
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
    public record Result(List<Share> shares, double inputF, double assignedF,
                         double dampedF, double unresolvedF, double candidateQ,
                         double finalQ, double dampedQ, String endReason) {
        public Result { shares = List.copyOf(shares); }
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
    public static final int MAX_UNKNOWN_DEPTH=2;
    public static final int MAX_NATIVE_DEPTH=9;
    // Gameplay geometry policy, not a measured material/solver constant.
    // 30-degree forward expansion with a half-block opening. The opening lets
    // a later layer traverse sideways without manufacturing disconnected hits.
    private static final double FORWARD_CONE_SLOPE=0.5773502691896257;
    private static final double CONTACT_RADIUS=0.5;
    private static final double GEOMETRY_EPS=1.0e-10;

    private static final class Flow {
        final GameImpactCredit.Target target;
        final int x,y,z,depth,elasticity;
        final java.util.Map<Flow,Double> parents=new java.util.HashMap<>();
        double incoming,localF,rawQ,finalQ;
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
     * UNKNOWN direction is a deliberately short damage-only fallback. Native
     * inward direction defines a forward-expanding cone in the frozen owner
     * lattice, with no edge that retreats along the collision normal. A Plot
     * may move in world coordinates while its block-local positions stay fixed.
     * Re-sampling a world pose for every BFS layer would mix physics epochs.
     */
    public static Result plan(String ownerKey,List<GameImpactCredit.Target> seeds,double inputF,
                              NeighborReader neighbors,Function<String,MaterialProperties> materials,
                              GameImpactCredit.Settings forces,DamageModel.Settings damage,
                              Orientation direction) {
        Objects.requireNonNull(direction);
        Objects.requireNonNull(ownerKey);Objects.requireNonNull(seeds);
        Objects.requireNonNull(neighbors);Objects.requireNonNull(materials);
        Objects.requireNonNull(forces);Objects.requireNonNull(damage);
        if(!Double.isFinite(inputF)||inputF<0)throw new IllegalArgumentException("invalid F");
        if(inputF==0||seeds.isEmpty())return new Result(List.of(),inputF,0,0,inputF,0,0,0,
                "NO_VERIFIED_SEEDS");
        var seen=new java.util.HashMap<String,Flow>();
        for(var t:seeds) {
            if(t==null||!ownerKey.equals(t.ownerKey()))throw new IllegalArgumentException("invalid seed owner");
            Flow old=seen.get(t.localBlock());
            if(old!=null) {
                if(!old.target.fingerprint().equals(t.fingerprint()))
                    throw new IllegalArgumentException("conflicting seed fingerprint");
                continue;
            }
            var m=Objects.requireNonNull(materials.apply(t.fingerprint()),"missing material");
            seen.put(t.localBlock(),new Flow(t,0,m.elasticityPercent()));
        }
        if(seen.size()>MAX_NODES_PER_SIDE)throw new IllegalArgumentException("too many contact seeds");
        List<Flow> layer=new ArrayList<>(seen.values());
        layer.sort(Comparator.comparing(n->n.target.localBlock()));
        for(var n:layer)n.incoming=inputF/layer.size();
        var all=new ArrayList<Flow>();
        double assigned=0,dampedF=0,unresolved=0;
        boolean capped=false,unknown=false,depthLimited=false,belowFloor=false;
        while(!layer.isEmpty()) {
            int depth=layer.getFirst().depth;
            // Build all next-layer edges BEFORE publishing any source Q.
            var nextMap=new java.util.HashMap<String,Flow>();
            var links=new java.util.HashMap<Flow,List<Flow>>();
            var uncertain=new java.util.HashSet<Flow>();
            boolean depthCap=depth >= (direction.verified()?MAX_NATIVE_DEPTH:MAX_UNKNOWN_DEPTH);
            for(var node:layer) {
                var children=new ArrayList<Flow>();
                if(!depthCap) for(var step:DIRECTIONS) {
                    int x,y,z;
                    try {
                        x=Math.addExact(node.x,step[0]);y=Math.addExact(node.y,step[1]);z=Math.addExact(node.z,step[2]);
                    }catch(ArithmeticException overflow) {uncertain.add(node);continue;}
                    if(!withinField(direction,seeds,x,y,z)
                            || !forwardStep(direction,step))continue;
                    Probe found=Objects.requireNonNull(neighbors.read(node.target,x,y,z),"missing proof");
                    if(found.status()==ProbeStatus.UNKNOWN){uncertain.add(node);continue;}
                    if(found.status()==ProbeStatus.EMPTY)continue;
                    var target=found.target();
                    if(!ownerKey.equals(target.ownerKey())||!target.localBlock().equals(x+","+y+","+z))
                        throw new IllegalArgumentException("neighbor owner/coordinate mismatch");
                    var prior=seen.get(target.localBlock());
                    if(prior!=null) {
                        if(!prior.target.fingerprint().equals(target.fingerprint()))
                            throw new IllegalArgumentException("changed neighbor fingerprint");
                        continue;
                    }
                    var child=nextMap.get(target.localBlock());
                    if(child==null) {
                        if(seen.size()+nextMap.size()>=MAX_NODES_PER_SIDE) {
                            capped=true;uncertain.add(node);continue;
                        }
                        var m=Objects.requireNonNull(materials.apply(target.fingerprint()),"missing neighbor material");
                        child=new Flow(target,depth+1,m.elasticityPercent());
                        nextMap.put(target.localBlock(),child);
                    } else if(!child.target.fingerprint().equals(target.fingerprint()))
                        throw new IllegalArgumentException("conflicting branch fingerprint");
                    children.add(child);
                }
                if(depthCap) {
                    // The search radius is not a physical endpoint. Still
                    // inspect the immediate frontier: if it is truly EMPTY,
                    // grant a real terminal; otherwise keep the remainder
                    // unresolved instead of assigning it to this node.
                    for(var step:DIRECTIONS) {
                        int x,y,z;
                        try {
                            x=Math.addExact(node.x,step[0]);y=Math.addExact(node.y,step[1]);z=Math.addExact(node.z,step[2]);
                        }catch(ArithmeticException overflow){uncertain.add(node);continue;}
                        if(!withinField(direction,seeds,x,y,z)
                                || !forwardStep(direction,step))continue;
                        Probe found=Objects.requireNonNull(neighbors.read(node.target,x,y,z),"missing boundary proof");
                        if(found.status()==ProbeStatus.UNKNOWN){uncertain.add(node);unknown=true;continue;}
                        if(found.status()==ProbeStatus.PRESENT) {
                            var t=found.target();
                            if(!ownerKey.equals(t.ownerKey()) || !t.localBlock().equals(x+","+y+","+z))
                                throw new IllegalArgumentException("boundary owner/coordinate mismatch");
                            var already=seen.get(t.localBlock());
                            if(already!=null && !already.target.fingerprint().equals(t.fingerprint()))
                                throw new IllegalArgumentException("boundary fingerprint changed");
                            if(already==null){uncertain.add(node);depthLimited=true;}
                        }
                    }
                }
                links.put(node,children);
            }
            List<Flow> next=new ArrayList<>(nextMap.values());
            next.sort(Comparator.comparing(n->n.target.localBlock()));
            // Nodes that could have undiscovered edges cannot redirect that
            // unproved budget to whichever neighbors happened to be scanned.
            for(var node:layer) {
                List<Flow> children=links.get(node);
                boolean blocked=uncertain.contains(node);
                boolean terminal=children.isEmpty()&&!blocked;
                double local=terminal?node.incoming:node.incoming/2;
                node.localF=local;
                node.rawQ=forces.candidate(local);
                node.finalQ=node.rawQ;
                assigned+=local;
                all.add(node);
                if(blocked) {
                    unresolved+=node.incoming-local;
                    unknown=true;
                    continue;
                }
                if(children.isEmpty())continue;
                double transmit=DamageModel.elasticityTransmissionPercent(node.elasticity,damage);
                double onward=(node.incoming-local);
                double forward=onward*transmit;
                dampedF+=onward-forward;
                // Skip subthreshold expansion after an intact node has been
                // accounted. This is a gameplay Q floor, NOT a terminal.
                if(forward/children.size()<forces.forceFloor() && depth>=3) {
                    unresolved+=forward;
                    belowFloor=true;
                    continue;
                }
                for(var child:children) {
                    double part=forward/children.size();
                    child.incoming+=part;
                    child.parents.merge(node,part,Double::sum);
                }
            }
            // Applying a child's elasticity to each actual predecessor keeps
            // unrelated branches independent; a join contributes one Q only.
            var sums=new java.util.HashMap<Flow,Double>();
            var weighted=new java.util.HashMap<Flow,Double>();
            for(var child:next) {
                double transmission=DamageModel.elasticityTransmissionPercent(child.elasticity,damage);
                for(var entry:child.parents.entrySet()) {
                    sums.merge(entry.getKey(),entry.getValue(),Double::sum);
                    weighted.merge(entry.getKey(),entry.getValue()*transmission,Double::sum);
                }
            }
            for(var entry:sums.entrySet())
                if(entry.getValue()>0)entry.getKey().finalQ*=weighted.get(entry.getKey())/entry.getValue();
            seen.putAll(nextMap);
            layer=next.stream().filter(n->n.incoming>0).toList();
            if(depthCap)break;
        }
        var shares=new ArrayList<Share>(all.size());
        double candidate=0,finalQ=0;
        for(var n:all) {
            candidate+=n.rawQ;finalQ+=n.finalQ;
            shares.add(new Share(n.target,n.localF,n.rawQ,n.finalQ,n.depth,n.elasticity));
        }
        String end=capped?"UNVERIFIED_BOUNDARY":depthLimited?"DIRECTION_DEPTH_LIMIT"
                :unknown?"UNVERIFIED_BOUNDARY":belowFloor?"BELOW_Q_FLOOR":"VERIFIED_TERMINAL";
        double residual=inputF-assigned-dampedF;
        if(residual<0&&residual>-1e-7*Math.max(1,inputF))residual=0;
        if(!Double.isFinite(residual)||residual<-1e-7*Math.max(1,inputF)||
                Math.abs((assigned+dampedF+residual)-inputF)>1e-7*Math.max(1,inputF)||
                finalQ>candidate+1e-7||finalQ<0||!Double.isFinite(finalQ))
            throw new IllegalStateException("directed F/Q budget mismatch");
        return new Result(shares,inputF,assigned,dampedF,Math.max(0,residual),candidate,
                finalQ,Math.max(0,candidate-finalQ),end);
    }
    /** No backward edge, including at an oblique normal. Perpendicular moves
     * are allowed only where the global cone includes the destination. */
    private static boolean forwardStep(Orientation direction,int[] step) {
        if(!direction.verified())return true;
        FrameMath.Vec n=direction.inward();
        return n.x()*step[0]+n.y()*step[1]+n.z()*step[2]>=-GEOMETRY_EPS;
    }

    /** Multi-source contact cone in the frozen owner-local grid. Do not
     * transform each node using a moving world pose: all signed positions and
     * the validated normal refer to the SAME owner-local collision frame.
     * Position offsets are in block cells; no translation is applied to a
     * direction vector. Graph adjacency is always checked separately. */
    private static boolean withinField(Orientation direction,List<GameImpactCredit.Target> seeds,
                                       int x,int y,int z) {
        if(!direction.verified())return true;
        FrameMath.Vec n=direction.inward();
        for(var seed:seeds) {
            var p=seed.localBlock().split(",",-1);
            double dx=(double)x-Integer.parseInt(p[0]),dy=(double)y-Integer.parseInt(p[1]),
                    dz=(double)z-Integer.parseInt(p[2]);
            double parallel=dx*n.x()+dy*n.y()+dz*n.z();
            if(parallel < -GEOMETRY_EPS)continue;
            double radial2=Math.max(0,dx*dx+dy*dy+dz*dz-parallel*parallel);
            double radius=CONTACT_RADIUS+parallel*FORWARD_CONE_SLOPE;
            if(radial2<=radius*radius+GEOMETRY_EPS)return true;
        }
        return false;
    }

    private static void add(Map<String,GameImpactCredit.Target> seen,String owner,GameImpactCredit.Target target) {
        Objects.requireNonNull(target);
        if(!owner.equals(target.ownerKey()))throw new IllegalArgumentException("wrong target owner");
        GameImpactCredit.Target earlier=seen.putIfAbsent(target.localBlock(),target);
        if(earlier!=null && !earlier.fingerprint().equals(target.fingerprint()))
            throw new IllegalArgumentException("conflicting target identity");
    }
}

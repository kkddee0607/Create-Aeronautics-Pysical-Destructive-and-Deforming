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
 * colFix 0.4.0: server-side, bounded F-first prediction for either owner space.
 * Never invents a neighbor from an AABB, native normal or an unloaded chunk.
 * The six-neighbor graph is a provisional NON-DIRECTIONAL gameplay policy,
 * not a proven physical stress direction or a Sable solver impulse.
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
    private static void add(Map<String,GameImpactCredit.Target> seen,String owner,GameImpactCredit.Target target) {
        Objects.requireNonNull(target);
        if(!owner.equals(target.ownerKey()))throw new IllegalArgumentException("wrong target owner");
        GameImpactCredit.Target earlier=seen.putIfAbsent(target.localBlock(),target);
        if(earlier!=null && !earlier.fingerprint().equals(target.fingerprint()))
            throw new IllegalArgumentException("conflicting target identity");
    }
}

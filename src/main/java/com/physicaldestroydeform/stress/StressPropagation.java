package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import java.util.*;

/** IDS 1.0.8 §§8,24.5. Pure, bounded Q transfer; never mints Credit or calls G(F).
 * Run on the server thread against a loaded-only owner-local graph. No world writes.
 */
public final class StressPropagation {
    public static final String REVISION="PDD-1.0.8/Q-STRESS-2";
    // Both sides of a propagated Grant may project into the same Plot service.
    // This is a source-root bound, NOT an increase to derived node or tick budgets.
    public static final int MAX_ROOTS=2*com.physicaldestroydeform.collision.ForcePropagation.MAX_NODES_PER_SIDE;
    public record Policy(int maxDepth,int maxNodes,int maxNodesPerTick,double minQ,long ttlTicks) {
        public Policy {
            if(maxDepth<1||maxDepth>16||maxNodes<1||maxNodes>4096
                    ||maxNodesPerTick<1||maxNodesPerTick>4096||!Double.isFinite(minQ)
                    ||minQ<1.0/256||ttlTicks<1||ttlTicks>72000)
                throw new IllegalArgumentException("invalid bounded stress policy");
        }
        public static Policy defaults(){return new Policy(4,128,512,1.0/256,1200);}
    }
    /** Owner includes dimension, owner space, UUID and generation, never runtime body ID. */
    public record Cell(String owner,int x,int y,int z) implements Comparable<Cell> {
        public Cell {if(owner==null||owner.isBlank())throw new IllegalArgumentException("missing owner");}
        public Cell offset(int dx,int dy,int dz){
            return new Cell(owner,Math.addExact(x,dx),Math.addExact(y,dy),Math.addExact(z,dz));
        }
        @Override public int compareTo(Cell b){
            int c=owner.compareTo(b.owner);if(c!=0)return c;
            c=Integer.compare(x,b.x);if(c!=0)return c;
            c=Integer.compare(y,b.y);return c!=0?c:Integer.compare(z,b.z);
        }
    }
    public record Node(Cell cell,String fingerprint,String incarnation,MaterialProperties material,String materialRevision) {
        public Node(Cell cell,String fingerprint,String incarnation,MaterialProperties material){this(cell,fingerprint,incarnation,material,"");}
        public Node {
            Objects.requireNonNull(cell);Objects.requireNonNull(material);
            if(fingerprint==null||fingerprint.isBlank()||incarnation==null||incarnation.isBlank()||materialRevision==null||materialRevision.length()>128)
                throw new IllegalArgumentException("unverified stress node");
        }
    }
    public enum State { SOLID, EMPTY, UNAVAILABLE, DENIED }
    public record Probe(State state,Node node) {
        private static final Probe EMPTY=new Probe(State.EMPTY,null),UNAVAILABLE=new Probe(State.UNAVAILABLE,null),
                DENIED=new Probe(State.DENIED,null);
        public Probe {
            Objects.requireNonNull(state);
            if((state==State.SOLID)!=(node!=null))throw new IllegalArgumentException("invalid probe");
        }
        public static Probe solid(Node node){return new Probe(State.SOLID,node);}
        public static Probe empty(){return EMPTY;}
        public static Probe unavailable(){return UNAVAILABLE;}
        public static Probe denied(){return DENIED;}
    }
    @FunctionalInterface public interface Graph { Probe read(Cell cell); }
    /** Caller supplies a validated owner-local normal only. Null means unbiased propagation. */
    public record Direction(double x,double y,double z) {
        public Direction {
            double n=Math.sqrt(x*x+y*y+z*z);
            if(!Double.isFinite(n)||n<1e-12)throw new IllegalArgumentException("invalid direction");
            if(Math.abs(n-1)>1e-12){x/=n;y/=n;z/=n;}
        }
        double weight(int[] d){return Math.max(0,x*d[0]+y*d[1]+z*d[2]);}
    }
    /** q is ONLY the source plan's already self-damped stress reservation. */
    public record Seed(Node node,double q,Direction direction) {
        public Seed {Objects.requireNonNull(node);validQ(q);}
    }
    public record PlannedNode(Node node,int depth,double incomingQ,DamageModel.Plan plan) {}
    public enum Unused { LOOP_OR_LATE_UNUSED, BELOW_MIN_Q, NO_NEIGHBOR, DENIED,
        STALE_NODE, DEPTH_TTL, NODE_CAP_TTL, UNAVAILABLE_TTL, STOPPED }
    public record Ledger(double reservedQ,double absorbedQ,double directQ,double plasticUnusedQ,
                         double unusedQ,double pendingQ,int uniqueNodes,boolean finished,
                         Map<Unused,Double> reasons) {
        public Ledger {reasons=Map.copyOf(reasons);}
        public double accountedQ(){return absorbedQ+directQ+plasticUnusedQ+unusedQ+pendingQ;}
    }
    private record Arrival(Node expected,double q,Direction direction,boolean ambiguous) {
        Arrival merge(Arrival b){
            // Changed identity cannot be merged into a later replacement block.
            if(!expected.equals(b.expected))throw new IllegalStateException("stress snapshot changed");
            boolean conflict=ambiguous||b.ambiguous||!Objects.equals(direction,b.direction);
            return new Arrival(expected,q+b.q,conflict?null:direction,conflict);
        }
    }
    private record Edge(Node node,State state,double weight) {}
    private static final int[][] FACES={{-1,0,0},{0,-1,0},{0,0,-1},{0,0,1},{0,1,0},{1,0,0}};
    private StressPropagation(){}
    private static void validQ(double q){if(!Double.isFinite(q)||q<0)throw new IllegalArgumentException("invalid Q");}

    /** A level barrier prevents an early branch from applying before all same-depth parents merge.
     * Completed nodes may be published as an immutable prefix; persist and validate them before commits.
     */
    public static final class Session {
        private final Policy policy;
        private final DamageModel.Settings damage;
        private final long started;
        private final List<Seed> seeds;
        private final Set<Cell> visited=new HashSet<>();
        private final NavigableMap<Cell,Arrival> frontier=new TreeMap<>(),next=new TreeMap<>();
        private final List<PlannedNode> plans=new ArrayList<>();
        private final EnumMap<Unused,Double> unused=new EnumMap<>(Unused.class);
        private final double reserved;
        private int seedIndex,depth=1,nodes;
        private double absorbed,direct,plastic;
        private long lastTick=Long.MIN_VALUE;
        private int usedThisTick;
        private boolean finished;
        private boolean waiting;
        private final boolean sealBounded;
        private double deferred;
        public Session(List<Seed> input,Policy policy,DamageModel.Settings damage,long tick) {
            this(input,policy,damage,tick,false);
        }
        /** Runtime mode seals executable nodes before immutable caps consume the source TTL. */
        public Session(List<Seed> input,Policy policy,DamageModel.Settings damage,long tick,boolean sealBounded) {
            this.sealBounded=sealBounded;
            this.policy=Objects.requireNonNull(policy);this.damage=Objects.requireNonNull(damage);
            if(tick<0||input.isEmpty()||input.size()>MAX_ROOTS)throw new IllegalArgumentException("invalid stress roots");
            started=tick;
            var sorted=new ArrayList<>(input);sorted.sort(Comparator.comparing(s->s.node.cell));
            double total=0;
            for(Seed s:sorted){
                if(!visited.add(s.node.cell))throw new IllegalArgumentException("duplicate stress seed");
                total+=s.q;
            }
            validQ(total);reserved=total;seeds=List.copyOf(sorted);
        }
        public boolean matchesOrigin(List<Seed> roots,Policy p,DamageModel.Settings d,long tick) {
            // Runtime reservation roots are already canonical; keep the
            // order-independent public check for all other callers.
            if(seeds.equals(roots))return policy.equals(p)&&damage.equals(d)&&started==tick;
            var sorted=new ArrayList<>(roots);sorted.sort(Comparator.comparing(s->s.node.cell));
            return seeds.equals(sorted)&&policy.equals(p)&&damage.equals(d)&&started==tick;
        }
        public int advance(Graph graph,long tick,int allowance) {
            return advance(graph,tick,allowance,true);
        }
        // Runtime cursor verifies at the durable batch boundary instead of
        // rescanning every unprocessed root and frontier for each single probe.
        int advance(Graph graph,long tick,int allowance,boolean verifyEachCall) {
            Objects.requireNonNull(graph);
            if(tick<started||tick<lastTick||allowance<0)throw new IllegalArgumentException("invalid stress clock/budget");
            if(finished)return 0;
            waiting=false;
            if(lastTick!=tick){lastTick=tick;usedThisTick=0;}
            int available=Math.min(allowance,policy.maxNodesPerTick-usedThisTick),used=0;
            boolean expired=tick-started>=policy.ttlTicks;
            // Roots are visited at construction: stress can never return to an original direct target.
            while(seedIndex<seeds.size()&&used<available){
                Seed seed=seeds.get(seedIndex);used++;
                if(seed.q==0){seedIndex++;continue;}
                Probe current=checked(graph,seed.node.cell);
                if(current.state==State.UNAVAILABLE&&!expired){waiting=true;break;}
                if(current.state!=State.SOLID||!current.node.equals(seed.node)){
                    close(current.state==State.UNAVAILABLE?Unused.UNAVAILABLE_TTL:Unused.STALE_NODE,seed.q);
                }else{
                    List<Edge> edges=edges(graph,seed.node,seed.direction);
                    if(edges==null&&!expired){waiting=true;break;}
                    if(edges==null)close(Unused.UNAVAILABLE_TTL,seed.q);
                    else distribute(seed.q,seed.direction,edges,frontier);
                }
                seedIndex++;
            }
            while(seedIndex==seeds.size()&&!frontier.isEmpty()&&used<available){
                var entry=frontier.firstEntry();Arrival a=entry.getValue();used++;
                if(depth>policy.maxDepth||nodes>=policy.maxNodes){
                    if(!expired){
                        if(!sealBounded)break;
                        deferred+=a.q;frontier.pollFirstEntry();continue;
                    }
                    close(depth>policy.maxDepth?Unused.DEPTH_TTL:Unused.NODE_CAP_TTL,a.q);
                    frontier.pollFirstEntry();continue;
                }
                if(a.q<policy.minQ){close(Unused.BELOW_MIN_Q,a.q);frontier.pollFirstEntry();continue;}
                Probe live=checked(graph,entry.getKey());
                if(live.state==State.UNAVAILABLE&&!expired){waiting=true;break;}
                if(live.state!=State.SOLID||!live.node.equals(a.expected)){
                    close(live.state==State.UNAVAILABLE?Unused.UNAVAILABLE_TTL:Unused.STALE_NODE,a.q);
                    frontier.pollFirstEntry();continue;
                }
                List<Edge> edges=edges(graph,a.expected,a.direction);
                if(edges==null&&!expired){waiting=true;break;}
                if(edges==null){close(Unused.UNAVAILABLE_TTL,a.q);frontier.pollFirstEntry();continue;}
                frontier.pollFirstEntry();visited.add(entry.getKey());nodes++;
                DamageModel.Plan p=DamageModel.plan(a.q,a.expected.material,new BlockDamageState(),damage);
                plans.add(new PlannedNode(a.expected,depth,a.q,p));
                absorbed+=p.absorbedQ();direct+=p.directQ();plastic+=p.plasticQ();
                if(p.status()==DamageModel.Status.UNBREAKABLE_UNUSED)close(Unused.DENIED,a.q);
                else distribute(p.stressQ(),a.direction,edges,next);
                // A node already queued at this depth wins over a later-depth arrival.
                // next is filtered at the barrier, including contributions queued before it was visited.
                if(frontier.isEmpty())barrier();
            }
            if(seedIndex==seeds.size()&&frontier.isEmpty()&&!next.isEmpty())barrier();
            finished=seedIndex==seeds.size()&&frontier.isEmpty()&&next.isEmpty();
            usedThisTick+=used;if(verifyEachCall)verify();return used;
        }
        private void barrier(){
            for(var e:next.entrySet()){
                if(visited.contains(e.getKey()))close(Unused.LOOP_OR_LATE_UNUSED,e.getValue().q);
                else frontier.put(e.getKey(),e.getValue());
            }
            next.clear();depth++;
        }
        private Probe checked(Graph graph,Cell cell){
            Probe p=Objects.requireNonNull(graph.read(cell),"missing probe");
            if(p.node!=null&&!p.node.cell.equals(cell))throw new IllegalArgumentException("cross-owner or wrong-cell graph response");
            return p;
        }
        private List<Edge> edges(Graph graph,Node parent,Direction direction){
            List<Edge> out=new ArrayList<>(6);double sum=0;
            for(int[] face:FACES){
                Probe p;
                try{p=checked(graph,parent.cell.offset(face[0],face[1],face[2]));}
                catch(ArithmeticException boundary){p=Probe.denied();}
                // No unloaded cell is guessed to be air or used as a newly discovered block.
                if(p.state==State.UNAVAILABLE)return null;
                if(p.state==State.EMPTY)continue;
                double w=direction==null?1:direction.weight(face);
                out.add(new Edge(p.node,p.state,w));sum+=w;
            }
            if(sum==0&&direction!=null){
                List<Edge> equal=new ArrayList<>(out.size());
                for(Edge e:out)equal.add(new Edge(e.node,e.state,1));
                return equal;
            }
            return out;
        }
        private void distribute(double q,Direction direction,List<Edge> edges,NavigableMap<Cell,Arrival> output){
            if(q==0)return;
            // A small parent/seed contribution may join other parents at the next
            // level. Apply minQ only when that complete level is processed.
            double weights=edges.stream().mapToDouble(Edge::weight).sum();
            if(weights==0){close(Unused.NO_NEIGHBOR,q);return;}
            double remaining=q,remainingWeight=weights;
            for(Edge edge:edges){
                if(edge.weight==0)continue;
                double amount=remainingWeight==edge.weight?remaining:Math.min(remaining,q*(edge.weight/weights));
                remaining-=amount;remainingWeight-=edge.weight;
                if(edge.state==State.DENIED){close(Unused.DENIED,amount);continue;}
                if(visited.contains(edge.node.cell)){close(Unused.LOOP_OR_LATE_UNUSED,amount);continue;}
                // minQ is tested only AFTER same-depth merging, not independently per incoming edge.
                Arrival arrival=new Arrival(edge.node,amount,direction,false);
                Arrival old=output.get(edge.node.cell);
                if(old!=null&&!old.expected.equals(edge.node)){
                    close(Unused.STALE_NODE,old.q+amount);output.remove(edge.node.cell);
                }else output.merge(edge.node.cell,arrival,Arrival::merge);
            }
            if(remaining>0)close(Unused.BELOW_MIN_Q,remaining);
        }
        private void close(Unused why,double q){if(q>0)unused.merge(why,q,Double::sum);}
        public Ledger ledger(){
            double pending=deferred;
            for(int i=seedIndex;i<seeds.size();i++)pending+=seeds.get(i).q;
            for(Arrival a:frontier.values())pending+=a.q;
            for(Arrival a:next.values())pending+=a.q;
            return new Ledger(reserved,absorbed,direct,plastic,
                    unused.values().stream().mapToDouble(Double::doubleValue).sum(),pending,nodes,finished,unused);
        }
        /** Completed nodes already have complete minimum-depth arrivals; future levels cannot alter them. */
        public List<PlannedNode> partialPlans(){return List.copyOf(plans);}
        boolean finished(){return finished;}
        boolean waiting(){return waiting;}
        int plannedNodes(){return plans.size();}
        public List<PlannedNode> plans(){
            if(!finished)throw new IllegalStateException("stress level planning not sealed");
            return List.copyOf(plans);
        }
        public boolean rootsExpanded(){return seedIndex==seeds.size();}
        public boolean boundedSealing(){return sealBounded;}
        public void stop(){
            if(finished)return;
            close(Unused.STOPPED,ledger().pendingQ);deferred=0;seedIndex=seeds.size();frontier.clear();next.clear();finished=true;verify();
        }
        void verify(){
            Ledger l=ledger();
            if(!Double.isFinite(l.accountedQ())||Math.abs(l.reservedQ-l.accountedQ())>Math.max(1e-8,l.reservedQ*1e-10))
                throw new IllegalStateException("stress Q conservation failed");
        }
    }
}

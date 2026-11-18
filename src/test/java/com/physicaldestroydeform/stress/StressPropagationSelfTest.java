package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.*;
import java.util.*;
import static com.physicaldestroydeform.stress.StressPropagation.*;

/** Real Java executable assertions, also called by JUnit; no fake Minecraft classes. */
public final class StressPropagationSelfTest {
    private static final MaterialProperties M=new MaterialProperties(0,1024,0,0,false);
    private static final DamageModel.Settings D=DamageModel.Settings.defaults();
    private static final Policy P=Policy.defaults();
    private static Node node(int x,int y){return new Node(new Cell("WORLD:overworld:1",x,y,0),"stone","rev0",M);}
    private static Map<Cell,Node> graph(Node... nodes){var m=new HashMap<Cell,Node>();for(var n:nodes)m.put(n.cell(),n);return m;}
    private static Graph read(Map<Cell,Node> m){return c->m.containsKey(c)?Probe.solid(m.get(c)):Probe.empty();}
    private static void near(double a,double b){if(!Double.isFinite(a)||!Double.isFinite(b)||Math.abs(a-b)>1e-9)throw new AssertionError(a+" != "+b);}
    private static void check(boolean b){if(!b)throw new AssertionError();}
    private static Session run(List<Seed> seeds,Graph g){var s=new Session(seeds,P,D,0);for(int t=0;t<=1400&&!s.ledger().finished();t++)s.advance(g,t,512);check(s.ledger().finished());near(s.ledger().reservedQ(),s.ledger().accountedQ());return s;}
    public static void main(String[] args){
        diamond();cycle();elasticityOnce();budgetAndTtl();ownerBoundary();stale();minimumAfterMerge();direction();deniedShare();stop();nodeCap();rootIdentity();unbreakable();permutationAndTickSlicing();randomGraphs();invalidInputs();
        subthresholdRootsMerge();subthresholdParentsMerge();
        System.out.println("StressPropagationSelfTest: 18 scenarios passed (includes 200 deterministic random graphs)");
    }
    static void diamond(){
        var a=node(0,0);var b=node(1,0);var c=node(0,1);var d=node(1,1);
        var s=run(List.of(new Seed(a,100,null)),read(graph(a,b,c,d)));
        check(s.plans().size()==3);
        var merged=s.plans().stream().filter(p->p.node().equals(d)).findFirst().orElseThrow();
        near(merged.incomingQ(),12.5); // each parent sends 6.25 to d; other half returns to root
        check(merged.depth()==2);near(s.ledger().directQ(),67.5);
        near(s.ledger().reasons().get(Unused.LOOP_OR_LATE_UNUSED),15.625);
    }
    static void cycle(){
        var a=node(0,0);var b=node(1,0);
        var s=run(List.of(new Seed(a,100,null)),read(graph(a,b)));
        check(s.plans().size()==1);near(s.ledger().directQ(),60);
        near(s.ledger().plasticUnusedQ(),15);near(s.ledger().unusedQ(),25);
    }
    static void elasticityOnce(){
        var a=node(0,0);var b=new Node(node(1,0).cell(),"rubber","rev0",new MaterialProperties(0,1024,0,100,false));
        var s=run(List.of(new Seed(a,100,null)),read(graph(a,b)));
        near(s.ledger().absorbedQ(),85);near(s.ledger().directQ(),9);
        near(s.plans().getFirst().plan().rawDamage(),15.6);
    }
    static void budgetAndTtl(){
        var a=node(0,0);var b=node(1,0);var c=node(2,0);
        var s=new Session(List.of(new Seed(a,100,null)),new Policy(1,1,1,1.0/256,3),D,0);
        var g=read(graph(a,b,c));
        check(s.advance(g,0,512)==1);check(s.advance(g,0,512)==0);
        check(s.advance(g,1,512)==1);check(!s.ledger().finished());
        check(s.advance(g,2,512)==1);check(!s.ledger().finished());
        s.advance(g,3,512);check(s.ledger().finished());near(s.ledger().reasons().get(Unused.DEPTH_TTL),12.5);
        var u=new Session(List.of(new Seed(a,10,null)),P,D,0);
        u.advance(cell->Probe.unavailable(),0,512);near(u.ledger().pendingQ(),10);
        u.advance(cell->Probe.unavailable(),1200,512);near(u.ledger().unusedQ(),10);
    }
    static void ownerBoundary(){
        var a=node(0,0);var other=new Node(new Cell("PLOT:other:1",1,0,0),"stone","rev0",M);
        var s=run(List.of(new Seed(a,100,null)),read(graph(a,other)));check(s.plans().isEmpty());
        try{run(List.of(new Seed(a,100,null)),cell->Probe.solid(other));throw new AssertionError();}
        catch(IllegalArgumentException expected){}
    }
    static void stale(){
        var a=node(0,0);var b=node(1,0);var map=graph(a,b);
        var s=new Session(List.of(new Seed(a,100,null)),P,D,0);
        s.advance(read(map),0,1);map.put(b.cell(),new Node(b.cell(),"stone","rev1",M));
        s.advance(read(map),1,512);check(s.ledger().finished());check(s.plans().isEmpty());near(s.ledger().unusedQ(),100);
    }
    static void minimumAfterMerge(){
        var a=node(0,0);var b=node(1,1);var c=node(1,0);var d=node(0,1);
        var s=run(List.of(new Seed(a,0.004,null),new Seed(b,0.004,null)),read(graph(a,b,c,d)));
        check(s.plans().size()==2);near(s.plans().getFirst().incomingQ(),0.004);
    }
    static void subthresholdRootsMerge(){
        var a=node(0,0);var b=node(2,0);var target=node(1,0);
        var s=run(List.of(new Seed(a,0.003,null),new Seed(b,0.003,null)),read(graph(a,b,target)));
        check(s.plans().size()==1);
        near(s.plans().getFirst().incomingQ(),0.006);
        near(s.ledger().directQ(),0.0036);
        near(s.plans().getFirst().plan().plasticQ(),0.0009);
        near(s.plans().getFirst().plan().stressQ(),0.0015);
        near(s.plans().getFirst().plan().rawDamage(),0.00936);
        check(s.plans().getFirst().plan().fixedIncrement()==2);
        near(s.ledger().accountedQ(),0.006);
    }
    static void subthresholdParentsMerge(){
        var root=node(0,0);var a=node(1,0);var b=node(0,1);var target=node(1,1);
        // Each depth-1 node receives .01: stress=.0025, below minQ. A verified
        // direction points away from the root, so both contributions reach target.
        var s=run(List.of(new Seed(root,0.02,new Direction(1,1,0))),read(graph(root,a,b,target)));
        var merged=s.plans().stream().filter(p->p.node().equals(target)).findFirst().orElseThrow();
        check(merged.depth()==2);near(merged.incomingQ(),0.005);
        near(merged.plan().directQ(),0.003);near(s.ledger().accountedQ(),0.02);
    }
    static void direction(){
        var a=node(0,0);var b=node(1,0);var c=node(-1,0);
        var s=run(List.of(new Seed(a,100,new Direction(1,0,0))),read(graph(a,b,c)));
        check(s.plans().size()==1);check(s.plans().getFirst().node().equals(b));
    }
    static void deniedShare(){
        var a=node(0,0);var b=node(1,0);var c=node(-1,0);var g=read(graph(a,b,c));
        var s=run(List.of(new Seed(a,100,null)),cell->cell.equals(c.cell())?Probe.denied():g.read(cell));
        near(s.plans().getFirst().incomingQ(),50);near(s.ledger().reasons().get(Unused.DENIED),50);
    }
    static void stop(){
        var a=node(0,0);var s=new Session(List.of(new Seed(a,100,null)),P,D,0);s.stop();
        check(s.ledger().finished());near(s.ledger().unusedQ(),100);
    }
    static void nodeCap(){
        var a=node(0,0);var b=node(1,0);var c=node(2,0);var d=node(3,0);
        var s=new Session(List.of(new Seed(a,100,null)),new Policy(4,1,512,1.0/256,2),D,0);
        var g=read(graph(a,b,c,d));s.advance(g,0,512);check(!s.ledger().finished());
        s.advance(g,2,512);check(s.ledger().finished());check(s.plans().size()==1);
        near(s.ledger().reasons().get(Unused.NODE_CAP_TTL),12.5);
        near(s.ledger().accountedQ(),100);
    }
    static void rootIdentity(){
        var a=node(0,0);var newer=new Node(a.cell(),a.fingerprint(),"rev1",M);
        var s=run(List.of(new Seed(a,100,null)),read(graph(newer,node(1,0))));
        check(s.plans().isEmpty());near(s.ledger().reasons().get(Unused.STALE_NODE),100);
    }
    static void unbreakable(){
        var a=node(0,0);var b=new Node(node(1,0).cell(),"bedrock","rev0",new MaterialProperties(255,1024,0,0,true));
        var s=run(List.of(new Seed(a,100,null)),read(graph(a,b,node(2,0))));
        near(s.ledger().directQ(),0);near(s.ledger().unusedQ(),100);check(s.plans().size()==1);
    }
    static void permutationAndTickSlicing(){
        var a=node(0,0);var b=node(1,1);var c=node(1,0);var d=node(0,1);
        var g=read(graph(a,b,c,d));var roots=List.of(new Seed(a,50,null),new Seed(b,100,null));
        var fast=run(roots,g);
        var slow=new Session(List.of(roots.get(1),roots.get(0)),P,D,0);
        for(int t=0;t<100&&!slow.ledger().finished();t++)slow.advance(g,t,1);
        check(slow.ledger().finished());check(fast.plans().equals(slow.plans()));
        check(fast.ledger().equals(slow.ledger()));
    }
    static void randomGraphs(){
        for(int sample=0;sample<200;sample++){
            var random=new Random(sample);var a=node(0,0);var map=graph(a);
            for(int x=-3;x<=3;x++)for(int y=-3;y<=3;y++){
                if((x!=0||y!=0)&&random.nextBoolean()){
                    var n=node(x,y);
                    map.put(n.cell(),new Node(n.cell(),"material"+x+","+y,"rev0",
                        new MaterialProperties(random.nextInt(256),1024,random.nextInt(101),random.nextInt(101),false)));
                }
            }
            var fast=run(List.of(new Seed(a,10000,null)),read(map));
            var slow=new Session(List.of(new Seed(a,10000,null)),P,D,0);
            for(int t=0;t<=1400&&!slow.ledger().finished();t++){
                int used=slow.advance(read(map),t,1+random.nextInt(8));check(used<=8);
                near(slow.ledger().accountedQ(),10000);
            }
            check(slow.ledger().finished());check(fast.plans().equals(slow.plans()));
            near(fast.ledger().directQ(),slow.ledger().directQ());
            check(fast.plans().stream().map(p->p.node().cell()).distinct().count()==fast.plans().size());
        }
    }
    static void invalidInputs(){
        var a=node(0,0);int rejected=0;
        try{new Seed(a,Double.NaN,null);}catch(IllegalArgumentException expected){rejected++;}
        try{new Seed(a,-1,null);}catch(IllegalArgumentException expected){rejected++;}
        try{new Direction(0,0,0);}catch(IllegalArgumentException expected){rejected++;}
        try{new Policy(0,128,512,1.0/256,1200);}catch(IllegalArgumentException expected){rejected++;}
        try{new Session(List.of(new Seed(a,1,null),new Seed(a,2,null)),P,D,0);}catch(IllegalArgumentException expected){rejected++;}
        check(rejected==5);
    }

}

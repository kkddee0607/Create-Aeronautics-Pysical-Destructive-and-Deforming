package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import java.util.*;
import static com.physicaldestroydeform.collision.FrameMath.*;

/** Bounded owner-local observations. Raw point neighborhoods route work only;
 * a new same-direction authority requires independently audited actual faces. */
final class DcrtSpatialFootprint {
    static final int LIMIT=128, WORK_LIMIT=262144;
    private static final double FACE_EPS=1e-5; // existing bilateral surface epsilon
    private static final double POINT_EPS=SableConsumerTargets.EPSILON; // routing only, never gap proof
    private static final Pose WORLD=new Pose(new Vec(0,0,0),new Vec(0,0,0),new Vec(1,1,1),0,0,0,1);
    enum Relation { CONNECTED, DISJOINT, UNKNOWN }
    static final class Work {
        private int used;
        boolean charge(int count){if(count<0||count>WORK_LIMIT-used){used=WORK_LIMIT+1;return false;}used+=count;return true;}
        boolean exhausted(){return used>WORK_LIMIT;}
        Relation relation(Surface surface,Probe probe,Sample sample){
            if(surface==null)return Relation.UNKNOWN;
            if(!charge(2*(surface.a.size()+surface.b.size())+1))return Relation.UNKNOWN;
            return surface.relation(probe,sample);
        }
    }
    record Sample(Vec a,Vec b,Pose pa,Pose pb) {
        static Sample of(GameImpactCredit.Pair pair,Contact c){
            int sa=SableConsumerTargets.sourceSide(pair.a().stableKey(),c,pair.dimension());
            int sb=SableConsumerTargets.sourceSide(pair.b().stableKey(),c,pair.dimension());
            if(sa==0||sb==0||sa==sb)return null;
            var a=SableConsumerTargets.candidate(c,sa==1);var b=SableConsumerTargets.candidate(c,sb==1);
            var pa=pose(c,sa==1);var pb=pose(c,sb==1);
            if(a==null||b==null||pa==null||pb==null||!pa.valid()||!pb.valid()||SableConsumerTargets.cells(a).isEmpty()||SableConsumerTargets.cells(b).isEmpty())return null;
            return new Sample(a,b,pa,pb);
        }
        private static Pose pose(Contact c,boolean a){
            if((a?c.idA():c.idB())==-1)return WORLD;
            var f=a?c.frameA():c.frameB();return f==null?null:f.pose();
        }
    }
    record Face(GameImpactCredit.Target target,BlockPos cell,int axis,int sign,double plane,double u0,double u1,double v0,double v1) {
        static Face of(GameImpactCredit.Target target,BlockPos cell,AABB b,int axis,int sign){
            int u=(axis+1)%3,v=(axis+2)%3;
            double plane=coordinate(cell,axis)+(sign>0?lo(b,axis):hi(b,axis));
            return new Face(target,cell,axis,sign,plane,coordinate(cell,u)+lo(b,u),coordinate(cell,u)+hi(b,u),coordinate(cell,v)+lo(b,v),coordinate(cell,v)+hi(b,v));
        }
        boolean contains(Vec p,Vec shift,double eps){
            int u=(axis+1)%3,v=(axis+2)%3;
            return between(coordinate(p,axis),plane,plane,coordinate(shift,axis),eps)
                    &&between(coordinate(p,u),u0,u1,coordinate(shift,u),eps)
                    &&between(coordinate(p,v),v0,v1,coordinate(shift,v),eps);
        }
        boolean bounded(){
            int u=(axis+1)%3,v=(axis+2)%3;
            return Double.isFinite(plane)&&Double.isFinite(u0)&&Double.isFinite(u1)&&Double.isFinite(v0)&&Double.isFinite(v1)
                    &&plane>=coordinate(cell,axis)-FACE_EPS&&plane<=coordinate(cell,axis)+1+FACE_EPS
                    &&u0>=coordinate(cell,u)-FACE_EPS&&u1<=coordinate(cell,u)+1+FACE_EPS&&u1>u0
                    &&v0>=coordinate(cell,v)-FACE_EPS&&v1<=coordinate(cell,v)+1+FACE_EPS&&v1>v0;
        }
        boolean connected(Face other){
            if(axis!=other.axis||sign!=other.sign||Math.abs(plane-other.plane)>FACE_EPS)return false;
            double u=Math.min(u1,other.u1)-Math.max(u0,other.u0),v=Math.min(v1,other.v1)-Math.max(v0,other.v0);
            return u>=-FACE_EPS&&v>=-FACE_EPS&&(u>FACE_EPS||v>FACE_EPS);
        }
        boolean stillPresent(OwnerResolver.NativeAuditBatch batch){
            var c=batch.inspectCell(target.ownerKey(),cell).result();
            if(!c.verified()||!target.fingerprint().equals(c.key().fingerprint())||!target.localBlock().equals(c.key().x()+","+c.key().y()+","+c.key().z()))return false;
            for(var b:batch.boxes(target.ownerKey(),cell))if(equals(of(target,cell,b,axis,sign)))return true;
            return false;
        }
    }
    private static double lo(AABB b,int a){return a==0?b.minX:a==1?b.minY:b.minZ;}
    private static double hi(AABB b,int a){return a==0?b.maxX:a==1?b.maxY:b.maxZ;}
    private static int coordinate(BlockPos p,int a){return a==0?p.getX():a==1?p.getY():p.getZ();}
    private static double coordinate(Vec p,int a){return a==0?p.x():a==1?p.y():p.z();}
    private static boolean between(double p,double lo,double hi,double shift,double eps){return p>=lo+Math.min(0,shift)-eps&&p<=hi+Math.max(0,shift)+eps;}
    private static final Vec ZERO=new Vec(0,0,0);
    record Surface(List<Face> a,List<Face> b) {
        Surface {a=List.copyOf(a);b=List.copyOf(b);}
        boolean valid(){return !a.isEmpty()&&!b.isEmpty()&&a.size()<=LIMIT&&b.size()<=LIMIT&&a.stream().allMatch(Face::bounded)&&b.stream().allMatch(Face::bounded);}
        boolean touches(Sample s){return contains(a,s.a,ZERO)||contains(b,s.b,ZERO);}
        boolean connected(Surface other){return connected(a,other.a)||connected(b,other.b);}
        private static boolean connected(List<Face> a,List<Face> b){for(var x:a)for(var y:b)if(x.connected(y))return true;return false;}
        private static boolean contains(List<Face> fs,Vec p,Vec delta){for(var f:fs)if(f.contains(p,delta,POINT_EPS))return true;return false;}
        /** Relative translation only. Rotation/scale change cannot establish a
         * disjoint new footprint; exact same-face observations remain continuous. */
        Relation relation(Probe prior,Sample current){
            if(touches(current))return Relation.CONNECTED;
            if(prior==null||prior.uniform==null)return Relation.UNKNOWN;
            var old=prior.uniform;
            try{
                if(!sameLinear(old.pa,old.pb,current.pa,current.pb)||!sameLinear(old.pb,old.pa,current.pb,current.pa))return Relation.UNKNOWN;
                Vec da=relative(current.pa,current.pb,old.b).minus(relative(old.pa,old.pb,old.b));
                Vec db=relative(current.pb,current.pa,old.a).minus(relative(old.pb,old.pa,old.a));
                if(!da.finite()||!db.finite())return Relation.UNKNOWN;
                return contains(a,current.a,da)||contains(b,current.b,db)?Relation.CONNECTED:Relation.DISJOINT;
            }catch(RuntimeException bad){return Relation.UNKNOWN;}
        }
    }
    /** Over-approximate actual connected coplanar collision-box faces. Internal
     * seams can only join components (withhold), never authorize a new region.
     * Null/unavailable/budget-exhausted cell or an open component beyond the
     * existing footprint cap cannot prove disconnection. */
    @FunctionalInterface interface Faces { List<Face> get(Face from,BlockPos pos); }
    static boolean disconnected(List<Face> first,List<Face> second,Faces reader,Work work){
        if(first.isEmpty()||second.isEmpty())return false;
        var visited=new HashSet<Face>(first);var queue=new ArrayDeque<Face>(first);
        while(!queue.isEmpty()){
            var face=queue.removeFirst();
            for(var goal:second){if(!work.charge(1))return false;if(face.connected(goal))return false;}
            if(!face.bounded()||visited.size()>LIMIT)return false;
            int u=(face.axis+1)%3,v=(face.axis+2)%3;
            // Faces live inside their block. Visit only neighboring cells on
            // the same plane, including alternate ownership at a voxel seam.
            for(int i=-1;i<=1;i++)for(int j=-1;j<=1;j++)for(int k=-1;k<=1;k++){
                int[] d=new int[3];d[face.axis]=i;d[u]=j;d[v]=k;
                var pos=face.cell.offset(d[0],d[1],d[2]);
                if(face.plane<coordinate(pos,face.axis)-FACE_EPS||face.plane>coordinate(pos,face.axis)+1+FACE_EPS)continue;
                // All coplanar faces from the same owner are included. The
                // cache key is cell + axis/sign, constant for a component.
                if(!work.charge(1))return false;
                List<Face> neighbors=reader.get(face,pos);
                if(neighbors==null)return false;
                for(var other:neighbors){
                    if(!work.charge(1)||!other.bounded())return false;
                    if(face.connected(other)&&visited.add(other)){if(visited.size()>LIMIT)return false;queue.add(other);}
                }
            }
        }
        return true;
    }
    private static Vec relative(Pose a,Pose b,Vec p){return a.toLocalPoint(b.toWorldPoint(p));}
    private static final List<Vec> BASIS=List.of(new Vec(1,0,0),new Vec(0,1,0),new Vec(0,0,1));
    private static boolean sameLinear(Pose a,Pose b,Pose c,Pose d){
        for(var v:BASIS)
            if(a.toLocalVector(b.toWorldVector(v)).minus(c.toLocalVector(d.toWorldVector(v))).squareLength()>FACE_EPS*FACE_EPS)return false;
        return true;
    }
    record Probe(Set<BlockPos> a,Set<BlockPos> b,Sample uniform) {
        static Probe of(List<Sample> samples){
            var a=new HashSet<BlockPos>();var b=new HashSet<BlockPos>();Sample uniform=null;boolean mixed=false;
            for(var s:samples){if(s==null)return null;a.add(cell(s.a));b.add(cell(s.b));if(a.size()>LIMIT||b.size()>LIMIT)return null;
                if(uniform==null)uniform=s;else if(!uniform.pa.equals(s.pa)||!uniform.pb.equals(s.pb))mixed=true;}
            return new Probe(Set.copyOf(a),Set.copyOf(b),mixed?null:uniform);
        }
        boolean near(Sample s){return near(a,cell(s.a))||near(b,cell(s.b));}
        private static boolean near(Set<BlockPos> cells,BlockPos p){
            for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)if(cells.contains(p.offset(x,y,z)))return true;
            return false;
        }
    }
    private static BlockPos cell(Vec p){return new BlockPos((int)Math.floor(p.x()),(int)Math.floor(p.y()),(int)Math.floor(p.z()));}
    private static boolean near(BlockPos a,BlockPos b){return Math.abs((long)a.getX()-b.getX())<=1&&Math.abs((long)a.getY()-b.getY())<=1&&Math.abs((long)a.getZ()-b.getZ())<=1;}
    private static int root(int[] parent,int i){while(parent[i]!=i){parent[i]=parent[parent[i]];i=parent[i];}return i;}
    /** Provisional witness components, not shape authority. Keep a hash of
     * unique paired cells so a dense manifold does not do quadratic duplicate work. */
    static List<List<Integer>> components(List<Sample> samples){
        if(samples.isEmpty()||samples.size()>DirectionalContactRegions.MAX_WITNESSES)return List.of();
        int n=samples.size(),work=0;int[] parent=new int[n];
        var index=new HashMap<BlockPos,Map<BlockPos,Integer>>();
        for(int i=0;i<n;i++){
            parent[i]=i;var s=samples.get(i);if(s==null)return List.of();var a=cell(s.a);var b=cell(s.b);
            var exact=index.get(a);Integer same=exact==null?null:exact.get(b);
            if(same!=null){parent[i]=root(parent,same);continue;}
            for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){
                var neighbors=index.get(a.offset(x,y,z));if(neighbors==null)continue;
                for(var entry:neighbors.entrySet()){
                    if(++work>WORK_LIMIT)return List.of();
                    if(near(b,entry.getKey()))parent[root(parent,i)]=root(parent,entry.getValue());
                }
            }
            index.computeIfAbsent(a,k->new HashMap<>()).put(b,i);
        }
        var groups=new LinkedHashMap<Integer,List<Integer>>();for(int i=0;i<n;i++)groups.computeIfAbsent(root(parent,i),k->new ArrayList<>()).add(i);
        return List.copyOf(groups.values());
    }
}

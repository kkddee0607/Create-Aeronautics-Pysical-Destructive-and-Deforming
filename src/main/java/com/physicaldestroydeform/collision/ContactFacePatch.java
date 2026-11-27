package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact;
import com.physicaldestroydeform.diagnostics.RogTrace;
import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import java.util.*;
import static com.physicaldestroydeform.collision.BilateralContactSurface.*;

/** Bounded GAME approximation, not a solver manifold. A paired local planar
 * chart is inferred from >=3 non-collinear witnesses in ONE native poll.
 * Both hulls, both actual exterior shapes and a connected component must
 * agree. No world-pose correction, unilateral halo, or extrapolation. */
final class ContactFacePatch {
    private ContactFacePatch() {}
    private static final double EPS=1e-7;
    private static final double FIT=PENETRATION;
    private static final double MIN_TRIANGLE=FIT*FIT;
    record Witness(Contact source,Frame frame) {}
    record Result(List<Match> pairs,Map<Contact,Set<GameImpactCredit.Target>> coveredA,
                  Map<Contact,Set<GameImpactCredit.Target>> coveredB,int work,boolean incomplete) {
        Result {pairs=List.copyOf(pairs);coveredA=Map.copyOf(coveredA);coveredB=Map.copyOf(coveredB);}
    }
    private record Face(Cell cell,double plane,List<Point> polygon) {}
    private record Anchor(Contact source,Point a,Point b,List<Face> fa,List<Face> fb) {}
    private record Group(long poll,ContactBatchStamp stamp,double scalar,FrameMath.Pose a,FrameMath.Pose b,
                         int ax,int as,int bx,int bs,double pa,double pb) {}
    private record Piece(Face a,Face b,List<Point> polygon) {}
    private record FaceKey(int side,BlockPos pos,int axis,int sign) {}
    private static final class Limit extends RuntimeException { @Override public synchronized Throwable fillInStackTrace(){return this;} }
    private static final class Work {
        final int cap;int used;final ShapeReader reader;
        final Map<FaceKey,List<Face>> faces=new HashMap<>();
        Work(int cap,ShapeReader reader){this.cap=cap;this.reader=reader;}
        void charge(){if(used>=cap||reader.budgetExhausted())throw new Limit();used++;}
        List<Face> faces(Frame f,int side,BlockPos pos){
            int ax=axis(f.inward(side)),sgn=sign(f,side);var key=new FaceKey(side,pos,ax,sgn);
            if(faces.containsKey(key))return faces.get(key);
            charge();Cell cell=reader.get(side,pos);
            if(reader.budgetExhausted()||cell==null&&reader.unresolvedCell(side,pos))throw new Limit();
            if(cell==null){faces.put(key,List.of());return List.of();}
            List<AABB> exterior=exteriorFaces(f,reader,cell,side);
            boolean uncertain=reader.takeUncertainExterior();
            if(exterior==null||uncertain||reader.budgetExhausted())throw new Limit();
            var out=new ArrayList<Face>();int u=(ax+1)%3,v=(ax+2)%3;
            for(var box:exterior){
                charge();
                // Candidate enumeration is cell-local. Protruding shapes
                // require the exact path, not silent clipping of their bounds.
                if(lo(box,ax)<-EPS||hi(box,ax)>1+EPS||lo(box,u)<-EPS||hi(box,u)>1+EPS||lo(box,v)<-EPS||hi(box,v)>1+EPS)throw new Limit();
                var poly=faceCorners(box,pos,ax,f.inward(side)).stream().map(p->point(p,ax)).toList();
                out.add(new Face(cell,coord(pos,ax)+lo(box,ax),ccw(poly)));
            }
            var result=List.copyOf(out);faces.put(key,result);return result;
        }
    }
    static Result reconstruct(List<Witness> records,ShapeReader reader,int cap){
        var work=new Work(Math.max(0,cap),reader);
        var groups=new LinkedHashMap<Group,List<Anchor>>();
        var pairs=new LinkedHashSet<Match>();
        var coveredA=new HashMap<Contact,Set<GameImpactCredit.Target>>();var coveredB=new HashMap<Contact,Set<GameImpactCredit.Target>>();
        boolean incomplete=false;
        // Do not join different native polls, different effect poses or different
        // pair-force records. Exact substep identity is unavailable; the remaining
        // congruence checks define the explicitly approximate policy.
        for(var w:records){
            if(w==null||w.source()==null||w.frame()==null||w.source().pollId()<=0||!w.source().runtimeStamp().known())continue;
            try{
                work.charge();var f=w.frame();int a=axis(f.inwardA()),b=axis(f.inwardB());
                var fa=anchors(work,f,0);var fb=anchors(work,f,1);
                if(fa.isEmpty()||fb.isEmpty())continue;
                // Ambiguous plane (not merely ambiguous cell along a seam) is not a patch witness.
                double pa=fa.getFirst().plane(),pb=fb.getFirst().plane();
                if(fa.stream().anyMatch(x->x.plane()!=pa)||fb.stream().anyMatch(x->x.plane()!=pb))continue;
                var key=new Group(w.source().pollId(),w.source().runtimeStamp(),w.source().rawImpactScalar(),f.a(),f.b(),a,sign(f,0),b,sign(f,1),pa,pb);
                groups.computeIfAbsent(key,k->new ArrayList<>()).add(new Anchor(w.source(),point(f.contactA(),a),point(f.contactB(),b),fa,fb));
            }catch(Limit unknown){incomplete=true;if(reader.budgetExhausted()||work.used>=work.cap)break;}
            catch(RuntimeException invalid){incomplete=true;}
        }
        for(var item:groups.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingLong(Group::poll)
                .thenComparingDouble(Group::scalar).thenComparingInt(Group::ax).thenComparingInt(Group::as).thenComparingDouble(Group::pa)
                .thenComparingInt(Group::bx).thenComparingInt(Group::bs).thenComparingDouble(Group::pb))).toList()){
            try{
                var g=item.getKey();var anchors=item.getValue();
                if(anchors.size()<3){trace("PATCH_INSUFFICIENT_ANCHORS",g,anchors.size(),0,0,work.used);continue;}
                var unique=new LinkedHashSet<Anchor>(anchors);anchors=new ArrayList<>(unique);
                anchors.sort(Comparator.comparingDouble((Anchor x)->x.a().x()).thenComparingDouble(x->x.a().y())
                        .thenComparingDouble(x->x.b().x()).thenComparingDouble(x->x.b().y()));
                var ha=hull(anchors.stream().map(Anchor::a).toList());var hb=hull(anchors.stream().map(Anchor::b).toList());
                if(polygonArea(ha)<=MIN_TRIANGLE||polygonArea(hb)<=MIN_TRIANGLE){trace("PATCH_DEGENERATE",g,anchors.size(),0,0,work.used);continue;}
                var map=fit(anchors,g,work);
                if(map==null){trace("PATCH_INCONSISTENT_CORRESPONDENCE",g,anchors.size(),0,0,work.used);continue;}
                var frame=new Frame(g.a(),g.b(),axes(g.ax(),g.as()),axes(g.bx(),g.bs()),vec(g.ax(),g.pa(),ha.getFirst()),vec(g.bx(),g.pb(),hb.getFirst()));
                var pieces=new ArrayList<Piece>();
                // Both hulls are hard boundaries. The paired local chart is a
                // coordinate relation only for patch geometry, not a world pose.
                for(var pos:positions(g.ax(),g.as(),g.pa(),ha,work))for(var af:work.faces(frame,0,pos)){
                    if(af.plane()!=g.pa())continue;
                    work.charge();var ap=clip(af.polygon(),ha);if(polygonArea(ap)<=MIN_AREA)continue;
                    var bp=clip(ccw(ap.stream().map(map::forward).toList()),hb);if(polygonArea(bp)<=MIN_AREA)continue;
                    for(var other:positions(g.bx(),g.bs(),g.pb(),bp,work))for(var bf:work.faces(frame,1,other)){
                        work.charge();if(bf.plane()!=g.pb())continue;
                        var intersection=clip(bp,bf.polygon());if(polygonArea(intersection)<=MIN_AREA)continue;
                        var local=ccw(intersection.stream().map(map::inverse).toList());
                        if(polygonArea(local)<=MIN_AREA)continue;
                        pieces.add(new Piece(af,bf,local));if(pieces.size()>ContactCreditLedger.MAX_REGIONS)throw new Limit();
                    }
                }
                // Every admitted connected component has its OWN 2D witnesses.
                // Two disjoint islands may not be bridged by the global hull.
                var components=components(pieces,work,g.ax());
                var localPairs=new LinkedHashSet<Match>();
                var localA=new HashMap<Contact,Set<GameImpactCredit.Target>>();var localB=new HashMap<Contact,Set<GameImpactCredit.Target>>();
                for(var component:components){
                    var support=new ArrayList<Anchor>();
                    for(var an:anchors){
                        for(int i:component){work.charge();var piece=pieces.get(i);
                            if(!inside(piece.polygon(),an.a(),FIT)||!inside(piece.b().polygon(),an.b(),FIT))continue;
                            if(an.fa().stream().noneMatch(x->x.cell().target().equals(piece.a().cell().target()))||an.fb().stream().noneMatch(x->x.cell().target().equals(piece.b().cell().target())))continue;
                            support.add(an);break;
                        }
                    }
                    if(polygonArea(hull(support.stream().map(Anchor::a).toList()))<=MIN_TRIANGLE||polygonArea(hull(support.stream().map(Anchor::b).toList()))<=MIN_TRIANGLE)continue;
                    var supportA=hull(support.stream().map(Anchor::a).toList());
                    var supportB=ccw(hull(support.stream().map(Anchor::b).toList()).stream().map(map::inverse).toList());
                    var trimmed=new ArrayList<Piece>();
                    for(int i:component){work.charge();var p=pieces.get(i);var poly=clip(clip(p.polygon(),supportA),supportB);
                        if(polygonArea(poly)>MIN_AREA)trimmed.add(new Piece(p.a(),p.b(),poly));}
                    if(trimmed.isEmpty()||components(trimmed,work,g.ax()).size()!=1)continue;
                    var componentA=new HashSet<GameImpactCredit.Target>();var componentB=new HashSet<GameImpactCredit.Target>();
                    for(var p:trimmed){componentA.add(p.a().cell().target());componentB.add(p.b().cell().target());localPairs.add(new Match(p.a().cell().target(),p.b().cell().target()));}
                    for(var an:support){
                        var sa=new HashSet<GameImpactCredit.Target>();var sb=new HashSet<GameImpactCredit.Target>();
                        for(var f:an.fa())if(componentA.contains(f.cell().target()))sa.add(f.cell().target());
                        for(var f:an.fb())if(componentB.contains(f.cell().target()))sb.add(f.cell().target());
                        localA.merge(an.source(),Set.copyOf(sa),ContactFacePatch::union);localB.merge(an.source(),Set.copyOf(sb),ContactFacePatch::union);
                    }
                }
                if((long)pairs.size()+localPairs.size()>ContactCreditLedger.MAX_REGIONS)throw new Limit();
                pairs.addAll(localPairs);localA.forEach((k,v)->coveredA.merge(k,v,ContactFacePatch::union));localB.forEach((k,v)->coveredB.merge(k,v,ContactFacePatch::union));
                trace(localPairs.isEmpty()?"PATCH_NO_CONNECTED_SUPPORT":"PATCH_MODEL_ACCEPTED",g,anchors.size(),polygonArea(ha),localPairs.size(),work.used);
            }catch(Limit unknown){incomplete=true;trace("PATCH_UNKNOWN_OR_CAP",item.getKey(),item.getValue().size(),0,0,work.used);if(work.used>=work.cap||reader.budgetExhausted())break;}
            catch(RuntimeException invalid){incomplete=true;trace("PATCH_INVALID_GEOMETRY",item.getKey(),item.getValue().size(),0,0,work.used);}
        }
        return new Result(new ArrayList<>(pairs),coveredA,coveredB,work.used,incomplete);
    }
    private static void trace(String reason,Group g,int anchors,double area,int pairs,int work){
        if(RogTrace.sample(Stage.R04,reason))RogTrace.detail(Stage.R04,reason,"pollId={} scalar={} planeA={} planeB={} axisA={} axisB={} anchors={} hullArea={} modelPairs={} work={} exactSubstep=false authority=GAME_PATCH",g.poll(),g.scalar(),g.pa(),g.pb(),g.ax(),g.bx(),anchors,area,pairs,work);
    }
    private static Set<GameImpactCredit.Target> union(Set<GameImpactCredit.Target> a,Set<GameImpactCredit.Target> b){var s=new HashSet<>(a);s.addAll(b);return Set.copyOf(s);}
    private static int sign(Frame f,int side){var a=f.inward(side);return a.x()+a.y()+a.z();}
    private static com.physicaldestroydeform.deform.MovePlan.SignedAxes axes(int a,int sign){return new com.physicaldestroydeform.deform.MovePlan.SignedAxes(a==0?sign:0,a==1?sign:0,a==2?sign:0,true,0);}
    private static Point point(FrameMath.Vec v,int axis){return new Point(coordinate(v,(axis+1)%3),coordinate(v,(axis+2)%3));}
    private static FrameMath.Vec vec(int axis,double plane,Point p){double[] a=new double[3];a[axis]=plane;a[(axis+1)%3]=p.x();a[(axis+2)%3]=p.y();return new FrameMath.Vec(a[0],a[1],a[2]);}
    private static List<Face> anchors(Work work,Frame f,int side){
        var out=new ArrayList<Face>();int ax=axis(f.inward(side));var p=f.contact(side);
        for(var pos:SableConsumerTargets.cells(p))for(var face:work.faces(f,side,pos)){
            work.charge();if(Math.abs(face.plane()-coordinate(p,ax))<=FIT&&inside(face.polygon(),point(p,ax),FIT))out.add(face);
        }
        return out;
    }
    private static boolean inside(List<Point> poly,Point p,double margin){
        if(poly.size()<3)return false;
        for(int i=0;i<poly.size();i++){var a=poly.get(i);var b=poly.get((i+1)%poly.size());
            if(cross(a,b,p)<-margin*Math.sqrt(dist2(a,b)))return false;}
        return true;
    }
    private static double dist2(Point a,Point b){double x=a.x()-b.x(),y=a.y()-b.y();return x*x+y*y;}
    private static List<Point> hull(List<Point> input){
        var sorted=input.stream().distinct().sorted(Comparator.comparingDouble(Point::x).thenComparingDouble(Point::y)).toList();
        if(sorted.size()<3)return sorted;
        var lower=new ArrayList<Point>();var upper=new ArrayList<Point>();
        for(var p:sorted){while(lower.size()>1&&cross(lower.get(lower.size()-2),lower.getLast(),p)<=0)lower.removeLast();lower.add(p);}
        for(int i=sorted.size()-1;i>=0;i--){var p=sorted.get(i);while(upper.size()>1&&cross(upper.get(upper.size()-2),upper.getLast(),p)<=0)upper.removeLast();upper.add(p);}
        lower.removeLast();upper.removeLast();lower.addAll(upper);return lower;
    }
    private record Chart(Point a,Point b,double xx,double xy,double yx,double yy,double det){
        Point forward(Point p){double x=p.x()-a.x(),y=p.y()-a.y();return new Point(b.x()+xx*x+xy*y,b.y()+yx*x+yy*y);}
        Point inverse(Point p){double x=p.x()-b.x(),y=p.y()-b.y();return new Point(a.x()+(yy*x-xy*y)/det,a.y()+(-yx*x+xx*y)/det);}
    }
    private static Chart fit(List<Anchor> points,Group g,Work work){
        var o=points.getFirst();Anchor x=o,y=o;double far=0,area=0;
        for(var p:points){work.charge();double d=dist2(o.a(),p.a());if(d>far){far=d;x=p;}}
        for(var p:points){work.charge();double d=Math.abs(cross(o.a(),x.a(),p.a()));if(d>area){area=d;y=p;}}
        if(area<=MIN_TRIANGLE||area/Math.sqrt(far)<=FIT)return null;
        double ax=x.a().x()-o.a().x(),ay=x.a().y()-o.a().y(),cx=y.a().x()-o.a().x(),cy=y.a().y()-o.a().y();
        double bx=x.b().x()-o.b().x(),by=x.b().y()-o.b().y(),dx=y.b().x()-o.b().x(),dy=y.b().y()-o.b().y();
        double d=ax*cy-ay*cx;
        double xx=(bx*cy-dx*ay)/d,xy=(dx*ax-bx*cx)/d,yx=(by*cy-dy*ay)/d,yy=(dy*ax-by*cx)/d,det=xx*yy-xy*yx;
        if(!Double.isFinite(det)||Math.signum(det)!=-g.as()*g.bs())return null;
        // A projected near-parallel rigid face cannot scale or shear freely.
        // The existing .98 face-opposition cosine bounds both singular values.
        double sum=xx*xx+xy*xy+yx*yx+yy*yy,disc=Math.sqrt(Math.max(0,sum*sum-4*det*det));
        if((sum-disc)/2<FACE_OPPOSITION*FACE_OPPOSITION-EPS||(sum+disc)/2>1/(FACE_OPPOSITION*FACE_OPPOSITION)+EPS)return null;
        var map=new Chart(o.a(),o.b(),xx,xy,yx,yy,det);
        for(var p:points){work.charge();if(dist2(map.forward(p.a()),p.b())>FIT*FIT||dist2(map.inverse(p.b()),p.a())>FIT*FIT)return null;}
        return map;
    }
    private static List<BlockPos> positions(int ax,int sign,double plane,List<Point> polygon,Work work){
        if(polygon.size()<3)return List.of();
        double l=polygon.stream().mapToDouble(Point::x).min().orElseThrow(),h=polygon.stream().mapToDouble(Point::x).max().orElseThrow();
        double b=polygon.stream().mapToDouble(Point::y).min().orElseThrow(),t=polygon.stream().mapToDouble(Point::y).max().orElseThrow();
        if(!Double.isFinite(plane)||Math.min(Math.min(l,b),plane)<Integer.MIN_VALUE+3||Math.max(Math.max(h,t),plane)>Integer.MAX_VALUE-3)throw new Limit();
        int u0=(int)Math.floor(l),u1=(int)Math.ceil(h)-1,v0=(int)Math.floor(b),v1=(int)Math.ceil(t)-1;
        long width=(long)u1-u0+1,height=(long)v1-v0+1;if(width<=0||height<=0)return List.of();
        if(width>work.cap||height>work.cap||width*height>work.cap-work.used)throw new Limit();
        // The actual witnessed face plane, no halo or normal extrapolation.
        int normal=(int)Math.floor(Math.nextAfter(plane,sign>0?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY));
        var out=new ArrayList<BlockPos>();for(int u=u0;u<=u1;u++)for(int v=v0;v<=v1;v++){
            work.charge();int[] q=new int[3];q[ax]=normal;q[(ax+1)%3]=u;q[(ax+2)%3]=v;out.add(new BlockPos(q[0],q[1],q[2]));
        }return out;
    }
    private static List<List<Integer>> components(List<Piece> pieces,Work work,int ax){
        var buckets=new HashMap<BlockPos,List<Integer>>();for(int i=0;i<pieces.size();i++)buckets.computeIfAbsent(pieces.get(i).a().cell().allocated(),k->new ArrayList<>()).add(i);
        var seen=new BitSet();var result=new ArrayList<List<Integer>>();
        for(int i=0;i<pieces.size();i++)if(!seen.get(i)){
            var queue=new ArrayDeque<Integer>();var component=new ArrayList<Integer>();queue.add(i);seen.set(i);
            while(!queue.isEmpty()){
                int idx=queue.removeFirst();component.add(idx);var p=pieces.get(idx);var cell=p.a().cell().allocated();
                for(int[] uv:new int[][]{{0,0},{-1,0},{1,0},{0,-1},{0,1}}){
                    int[] xyz={cell.getX(),cell.getY(),cell.getZ()};xyz[(ax+1)%3]+=uv[0];xyz[(ax+2)%3]+=uv[1];
                    for(int j:buckets.getOrDefault(new BlockPos(xyz[0],xyz[1],xyz[2]),List.of()))if(!seen.get(j)){
                        work.charge();if(connected(p.polygon(),pieces.get(j).polygon())){seen.set(j);queue.add(j);}
                    }
                }
            }result.add(component);
        }return result;
    }
    private static boolean connected(List<Point> a,List<Point> b){
        if(polygonArea(clip(a,b))>MIN_AREA)return true;
        // A positive shared edge, not merely a touching vertex.
        for(int i=0;i<a.size();i++)for(int j=0;j<b.size();j++){
            var p=a.get(i);var q=a.get((i+1)%a.size());var r=b.get(j);var s=b.get((j+1)%b.size());
            double len=Math.sqrt(dist2(p,q));if(len<=EPS||Math.abs(cross(p,q,r))>EPS*len||Math.abs(cross(p,q,s))>EPS*len)continue;
            double rx=((r.x()-p.x())*(q.x()-p.x())+(r.y()-p.y())*(q.y()-p.y()))/len;
            double sx=((s.x()-p.x())*(q.x()-p.x())+(s.y()-p.y())*(q.y()-p.y()))/len;
            if(Math.min(len,Math.max(rx,sx))-Math.max(0,Math.min(rx,sx))>EPS)return true;
        }return false;
    }
}

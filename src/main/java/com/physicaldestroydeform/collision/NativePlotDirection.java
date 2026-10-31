package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.physicaldestroydeform.collision.CollisionFrame.*;
import static com.physicaldestroydeform.collision.FrameMath.*;

/** G-MATH-01 Plot-Plot evidence bridge, not solver impulse reconstruction.
 *
 * Sable Rapier 2.0.6 event_handler.rs records each manifold's local_n1/n2
 * and level-collider runtime IDs. The Java postPhysicsTicks effect feed has
 * NO substep index and is force-filtered, so a record cannot be matched to a
 * particular pre-solver substep. Reject unless EVERY recorded pre-solver
 * substep for the same tick and body pair has compatible opposing normals,
 * approach and a conservative translation-only proxy. Never choose the most
 * favorable pre-solver sample, or claim an EXACT_CONTACT measurement.
 */
public final class NativePlotDirection {
    private NativePlotDirection() {}
    // Diagnostic confidence criteria, NOT hidden damage coefficients.
    private static final double MIN_OPPOSITE_COS=0.98;
    private static final double MIN_COMMON_COS=0.98;
    private static final boolean MORE_ROG=Boolean.parseBoolean(System.getProperty("pdd.moreRog.enabled","false"));
    private static final int MAX_NATIVE_EXAMPLES=4;
    private static final int MAX_SUBSTEP_EXAMPLES=4;

    public record Key(String dimension,long tick,int lowId,int highId) {
        static Key of(String dimension,long tick,int a,int b) {
            return new Key(dimension,tick,Math.min(a,b),Math.max(a,b));
        }
    }
    public enum Reject { NO_PRE_SNAPSHOT, BODY_ID_OR_MASS_UNVERIFIED,
        NONFINITE_NORMAL, SIDES_NOT_OPPOSED, CONFLICTING_MANIFOLD_NORMAL }
    private record PairFrame(Snapshot snapshot,Body a,Body b,Vec normalBtoA) {}
    private static final class PairBundle {
        private final List<PairFrame> frames=new ArrayList<>();
        private Vec referenceNormal;
        private Reject reject;
        private long nativeRecordCount;
        private final List<String> nativeExamples=new ArrayList<>();
        private String firstFailure;
        private String firstSuccessfulFrame;
    }
    public static final class Prepared {
        private final Map<Key,PairBundle> pairs;
        private final int accepted,rejected;
        private final Map<Reject,Long> rejectedReasons;
        private Prepared(Map<Key,PairBundle> pairs,int accepted,int rejected,Map<Reject,Long> reasons) {
            this.pairs=Map.copyOf(pairs);this.accepted=accepted;this.rejected=rejected;
            this.rejectedReasons=Map.copyOf(reasons);
        }
        public int acceptedPairs(){return accepted;}
        public int rejectedPairs(){return rejected;}
        public Map<Reject,Long> rejectedReasons(){return rejectedReasons;}
        /** Explains the *body-pair* native gate, never a per-block solver manifold. */
        public String explain(Evidence e) {
            if(e==null||e.hit()==null||e.other()==null||!e.hit().plot()||!e.other().plot())
                return "NOT_PLOT_PLOT";
            if(e.hitBody()==null||e.otherBody()==null
                    ||e.hitBody().runtimeId()<0||e.otherBody().runtimeId()<0)
                return "BODY_RUNTIME_ID_UNAVAILABLE";
            if(e.hitBody().runtimeId()<0||e.otherBody().runtimeId()<0
                    ||e.hitBody().runtimeId()==e.otherBody().runtimeId())
                return "BODY_RUNTIME_ID_UNAVAILABLE_OR_SAME";
            PairBundle pair=pairs.get(Key.of(e.dimension(),e.serverTick(),
                    e.hitBody().runtimeId(),e.otherBody().runtimeId()));
            if(pair==null)return "NO_NATIVE_EFFECT_FOR_BODY_PAIR_TICK";
            if(pair.reject!=null)return pair.reject.name();
            if(pair.frames.isEmpty())return "NO_PRE_SOLVER_FRAMES";
            boolean epochFound=pair.frames.stream().anyMatch(f->f.snapshot().epoch()==e.epoch());
            return epochFound?"OPPOSING_NORMAL_VALIDATED_BODY_PAIR_TICK":"NO_MATCHING_PRE_SOLVER_EPOCH";
        }

        /** One bounded owner-body-pair diagnostic. Effect record ordinal is NOT
         * a Sable manifold ID; native data do not carry a pre-solver substep. */
        public String moreRogNativeEvidence(Evidence e) {
            if(e==null||e.hitBody()==null||e.otherBody()==null)return "NATIVE_BODY_PAIR_UNAVAILABLE";
            if(e.hitBody().runtimeId()<0||e.otherBody().runtimeId()<0
                    ||e.hitBody().runtimeId()==e.otherBody().runtimeId())
                return "BODY_RUNTIME_ID_UNAVAILABLE_OR_SAME";
            PairBundle pair=pairs.get(Key.of(e.dimension(),e.serverTick(),
                    e.hitBody().runtimeId(),e.otherBody().runtimeId()));
            if(pair==null)return "NO_NATIVE_EFFECT_FOR_BODY_PAIR_TICK";
            return "nativeRecordCount="+pair.nativeRecordCount
                    +",shownRecordCount="+pair.nativeExamples.size()
                    +",nativeExamples="+pair.nativeExamples
                    +",firstSuccessfulFrame="+pair.firstSuccessfulFrame
                    +",firstFailure="+pair.firstFailure
                    +",pairGate="+(pair.reject==null?"VALIDATED":pair.reject)
                    +",manifoldId=UNAVAILABLE,substepPerEffect=UNAVAILABLE"
                    +",nativeContactPoints=RAW_UNVERIFIED_NOT_SOLVER_CONTACT";
        }
        /** Diagnose every retained pre-solver snapshot, not just the first
         * candidate callback. A rejected pair has no approved normals, so do
         * not reconstruct an exact contact from its rejected reference. */
        public String moreRogSubsteps(Evidence e,ImpactMath.ProxyPolicy policy) {
            if(e==null||e.hitBody()==null||e.otherBody()==null)return "UNAVAILABLE";
            if(e.hitBody().runtimeId()<0||e.otherBody().runtimeId()<0
                    ||e.hitBody().runtimeId()==e.otherBody().runtimeId())
                return "BODY_RUNTIME_ID_UNAVAILABLE_OR_SAME";
            PairBundle pair=pairs.get(Key.of(e.dimension(),e.serverTick(),
                    e.hitBody().runtimeId(),e.otherBody().runtimeId()));
            if(pair==null)return "NO_NATIVE_EFFECT";
            if(pair.reject!=null)return "NO_VALIDATED_NORMAL gate="+pair.reject
                    +" note=ROTATION_NOT_EVALUABLE_WITH_REJECTED_NORMAL";
            int steps=0,rotationRejects=0,otherRejects=0;
            String firstReject=null;
            List<String> examples=new ArrayList<>();
            for(PairFrame frame:pair.frames) {
                boolean reversed=frame.a().runtimeId()!=e.hitBody().runtimeId();
                Body hitFrame=reversed?frame.b():frame.a();
                Body otherFrame=reversed?frame.a():frame.b();
                Vec normal=reversed?frame.normalBtoA().times(-1):frame.normalBtoA();
                var cloned=new Evidence(frame.snapshot().epoch(),frame.snapshot().serverTick(),
                        e.dimension(),Phase.PRE_SOLVER,e.hit(),e.other(),null,null,null,
                        hitFrame,otherFrame,Quality.DIAGNOSTIC_ONLY,"NATIVE_TICK_PAIR");
                String sample;
                try {
                    var rotation=ImpactLiveAudit.rotationEvidence(cloned,normal);
                    var outcome=ImpactMath.proxy(new ImpactMath.ProxyInput(
                            ImpactLiveAudit.body(e.hit(),hitFrame),
                            ImpactLiveAudit.body(e.other(),otherFrame),normal,true,true,true,
                            rotation.rotationGateRejects()),policy);
                    if(outcome.reason()==ImpactMath.Reason.ROTATION_UNRESOLVED)rotationRejects++;
                    else if(!outcome.approved())otherRejects++;
                    sample="{epoch="+frame.snapshot().epoch()+",normalBtoA="+normal
                            +",candidateReason="+outcome.reason()+","+rotation.explain()+"}";
                }catch(IllegalArgumentException invalid) {
                    otherRejects++;
                    sample="{epoch="+frame.snapshot().epoch()+",diagnostic=UNAVAILABLE}";
                }
                steps++;
                if(examples.size()<MAX_SUBSTEP_EXAMPLES)examples.add(sample);
                if(firstReject==null && (sample.contains("candidateReason=ROTATION_UNRESOLVED")
                        ||sample.contains("candidateReason=SEPARATING_OR_TANGENTIAL")))firstReject=sample;
            }
            return "preSolverSubsteps="+steps+",rotationRejectSubsteps="+rotationRejects
                    +",otherRejectSubsteps="+otherRejects+",substepExamples="+examples
                    +",firstRejectedSubstep="+firstReject
                    +",nativeEffectSubstepMapping=UNAVAILABLE";
        }

        public Vec approvedNormal(Evidence e) {
            if(e==null||e.hitBody()==null||e.otherBody()==null)return null;
            PairBundle pair=pairs.get(Key.of(e.dimension(),e.serverTick(),
                    e.hitBody().runtimeId(),e.otherBody().runtimeId()));
            if(pair==null||pair.reject!=null||pair.frames.isEmpty())return null;
            PairFrame frame=pair.frames.stream().filter(f->f.snapshot().epoch()==e.epoch())
                    .findFirst().orElse(null);
            if(frame==null)return null;
            return frame.a().runtimeId()==e.hitBody().runtimeId()
                    ? frame.normalBtoA():frame.normalBtoA().times(-1);
        }

        public ImpactMath.Result evaluate(Evidence e,ImpactMath.ProxyPolicy policy) {
            if(e==null||e.hit()==null||e.other()==null||!e.hit().plot()||!e.other().plot())
                return ImpactMath.Result.reject(ImpactMath.Reason.OWNER_UNVERIFIED);
            if(e.phase()!=Phase.PRE_SOLVER||e.epoch()<0)
                return ImpactMath.Result.reject(ImpactMath.Reason.STALE_SNAPSHOT);
            if(policy==null||!policy.enabled())
                return ImpactMath.Result.reject(ImpactMath.Reason.POLICY_DISABLED);
            if(!policy.configured())
                return ImpactMath.Result.reject(ImpactMath.Reason.POLICY_UNCONFIGURED);
            Body hit=e.hitBody(), other=e.otherBody();
            if(hit==null||other==null||!hit.valid()||!other.valid()
                    ||hit.runtimeId()<0||other.runtimeId()<0||hit.runtimeId()==other.runtimeId()
                    ||!e.hit().stablePlotId().equals(hit.plotId())
                    ||!e.other().stablePlotId().equals(other.plotId()))
                return ImpactMath.Result.reject(ImpactMath.Reason.MASS_INVALID);
            PairBundle bundle=pairs.get(Key.of(e.dimension(),e.serverTick(),hit.runtimeId(),other.runtimeId()));
            if(bundle==null||bundle.reject!=null||bundle.frames.isEmpty())
                return ImpactMath.Result.reject(ImpactMath.Reason.DIRECTION_UNVERIFIED);

            // All pre-solver substeps in this native-effect tick MUST be audited.
            // The candidate is the MINIMUM budget across them. This prevents
            // a post-solver effect from selecting a favorable unrelated substep.
            ImpactMath.Result minimum=null;
            boolean epochSeen=false;
            for(PairFrame frame:bundle.frames) {
                if(frame.snapshot().epoch()==e.epoch())epochSeen=true;
                boolean reversed=frame.a().runtimeId()!=hit.runtimeId();
                Body hitFrame=reversed?frame.b():frame.a();
                Body otherFrame=reversed?frame.a():frame.b();
                Vec n=reversed?frame.normalBtoA().times(-1):frame.normalBtoA();
                if(!hitFrame.plotId().equals(e.hit().stablePlotId())
                        ||!otherFrame.plotId().equals(e.other().stablePlotId()))
                    return ImpactMath.Result.reject(ImpactMath.Reason.OWNER_UNVERIFIED);
                ImpactMath.Body a=ImpactLiveAudit.body(e.hit(),hitFrame);
                ImpactMath.Body b=ImpactLiveAudit.body(e.other(),otherFrame);
                if(a==null||b==null||!a.valid()||!b.valid())
                    return ImpactMath.Result.reject(ImpactMath.Reason.MASS_INVALID);
                var sameSnapshot=new Evidence(frame.snapshot().epoch(),frame.snapshot().serverTick(),
                        e.dimension(),Phase.PRE_SOLVER,e.hit(),e.other(),null,null,null,
                        hitFrame,otherFrame,Quality.DIAGNOSTIC_ONLY,"NATIVE_TICK_PAIR");
                boolean dominated=ImpactLiveAudit.rotationMayDominate(sameSnapshot,a,b,n);
                var result=ImpactMath.proxy(new ImpactMath.ProxyInput(a,b,n,true,true,true,dominated),policy);
                if(!result.approved())return result;
                if(minimum==null || result.budget()<minimum.budget())minimum=result;
            }
            if(!epochSeen)return ImpactMath.Result.reject(ImpactMath.Reason.STALE_SNAPSHOT);
            return new ImpactMath.Result(ImpactMath.Kind.CONSERVATIVE_PROXY,ImpactMath.Reason.APPROVED,
                    minimum.budget(),minimum.closingSpeed(),minimum.effectiveMass(),
                    "NATIVE_PLOT_PAIR_OPPOSING_NORMAL_ALL_PRE_SUBSTEPS_MIN_GAME_PROXY");
        }
    }

    /** Build once per server tick, so callbacks never search a native list. */
    public static Prepared prepare(List<NativeCollisionTelemetry.Contact> contacts,List<Snapshot> snapshots) {
        Map<String,List<Snapshot>> byTick=new HashMap<>();
        for(Snapshot s:snapshots)if(s!=null && s.phase()==Phase.PRE_SOLVER)
            byTick.computeIfAbsent(stamp(s.dimension(),s.serverTick()),k->new ArrayList<>()).add(s);
        Map<Key,PairBundle> pairs=new HashMap<>();
        for(var c:contacts) {
            if(c==null||c.dimension()==null||c.serverTick()<0||c.idA()<0||c.idB()<0
                    ||c.idA()==c.idB())continue;
            Key key=Key.of(c.dimension(),c.serverTick(),c.idA(),c.idB());
            PairBundle bundle=pairs.computeIfAbsent(key,k->new PairBundle());
            if(MORE_ROG) {
                bundle.nativeRecordCount++;
                if(bundle.nativeExamples.size()<MAX_NATIVE_EXAMPLES)
                    bundle.nativeExamples.add("{effectOrdinalWithinPair="+bundle.nativeRecordCount
                            +",rawBodyOrder="+c.idA()+"/"+c.idB()
                            +",localNormalA=("+c.ax()+","+c.ay()+","+c.az()+")"
                            +",localNormalB=("+c.bx()+","+c.by()+","+c.bz()+")"
                            +",rawPointA_FRAME_UNVERIFIED=("+c.contactAX()+","+c.contactAY()+","+c.contactAZ()+")"
                            +",rawPointB_FRAME_UNVERIFIED=("+c.contactBX()+","+c.contactBY()+","+c.contactBZ()+")"
                            +",rawScalar_NOT_ENERGY="+c.rawImpactScalar()+"}");
            }
            if(bundle.reject!=null)continue;
            List<Snapshot> frames=byTick.get(stamp(c.dimension(),c.serverTick()));
            if(frames==null||frames.isEmpty()) {
                bundle.reject=Reject.NO_PRE_SNAPSHOT;
                if(MORE_ROG && bundle.firstFailure==null)bundle.firstFailure="NO_PRE_SNAPSHOT";
                continue;
            }
            Vec rawA=new Vec(c.ax(),c.ay(),c.az()),rawB=new Vec(c.bx(),c.by(),c.bz());
            if(!unit(rawA)||!unit(rawB)) {
                bundle.reject=Reject.NONFINITE_NORMAL;
                if(MORE_ROG && bundle.firstFailure==null)bundle.firstFailure="NONFINITE_OR_NONUNIT_NORMAL";
                continue;
            }
            for(Snapshot s:frames) {
                Body a=find(s,c.idA()),b=find(s,c.idB());
                if(!s.containerAvailable()||a==null||b==null||a.plotId().equals(b.plotId())||!a.valid()||!b.valid()
                        ||a.impactBody()==null||b.impactBody()==null) {
                    bundle.reject=Reject.BODY_ID_OR_MASS_UNVERIFIED;
                    if(MORE_ROG && bundle.firstFailure==null)bundle.firstFailure="BODY_ID_OR_MASS_UNVERIFIED epoch="+s.epoch();
                    break;
                }
                Vec normalA,normalB;
                try {
                    normalA=a.pose().toWorldNormal(rawA);
                    normalB=b.pose().toWorldNormal(rawB);
                }catch(IllegalArgumentException invalid) {
                    bundle.reject=Reject.NONFINITE_NORMAL;
                    if(MORE_ROG && bundle.firstFailure==null)bundle.firstFailure="NORMAL_TRANSFORM_INVALID epoch="+s.epoch();
                    break;
                }
                // Rapier local_n1 and local_n2 refer to opposing collider normals.
                // Require both independently reconstructed sides to agree.
                double oppositeDot=normalA.dot(normalB);
                if(oppositeDot>-MIN_OPPOSITE_COS) {
                    bundle.reject=Reject.SIDES_NOT_OPPOSED;
                    if(MORE_ROG && bundle.firstFailure==null)
                        bundle.firstFailure="reason=SIDES_NOT_OPPOSED effectOrdinal="+bundle.nativeRecordCount
                            +" epoch="+s.epoch()+" worldA="+normalA+" worldB="+normalB
                            +" opposingDot="+oppositeDot+" threshold=-"+MIN_OPPOSITE_COS;
                    break;
                }
                Vec n=normalA.times(-1); // B -> A, consistent with ImpactMath.
                // Canonicalize solver's arbitrary collider order before comparing
                // other manifold normals; both orientations must not cancel.
                if(c.idA()!=key.lowId()) {n=n.times(-1);Body swap=a;a=b;b=swap;}
                double referenceDot=bundle.referenceNormal==null?Double.NaN:n.dot(bundle.referenceNormal);
                if(bundle.referenceNormal!=null && referenceDot<MIN_COMMON_COS) {
                    bundle.reject=Reject.CONFLICTING_MANIFOLD_NORMAL;
                    if(MORE_ROG && bundle.firstFailure==null)
                        bundle.firstFailure="reason=CONFLICTING_MANIFOLD_NORMAL effectOrdinal="+bundle.nativeRecordCount
                            +" epoch="+s.epoch()+" canonicalBtoA="+n
                            +" referenceCanonicalBtoA="+bundle.referenceNormal
                            +" commonDot="+referenceDot+" threshold="+MIN_COMMON_COS
                            +" worldA="+normalA+" worldB="+normalB+" opposingDot="+oppositeDot;
                    break;
                }
                if(bundle.referenceNormal==null)bundle.referenceNormal=n;
                if(MORE_ROG && bundle.firstSuccessfulFrame==null)
                    bundle.firstSuccessfulFrame="effectOrdinal="+bundle.nativeRecordCount
                        +" epoch="+s.epoch()+" canonicalBtoA="+n+" worldA="+normalA
                        +" worldB="+normalB+" opposingDot="+oppositeDot;
                // Store each substep once even when multiple manifold records
                // independently corroborate the same direction.
                if(bundle.frames.size()<frames.size())bundle.frames.add(new PairFrame(s,a,b,n));
            }
        }
        int accepted=0,rejected=0;
        Map<Reject,Long> reasons=new java.util.EnumMap<>(Reject.class);
        for(var pair:pairs.values()) {
            if(pair.reject!=null||pair.frames.isEmpty()) {
                pair.frames.clear();rejected++;
                reasons.merge(pair.reject==null?Reject.NO_PRE_SNAPSHOT:pair.reject,1L,Long::sum);
            }else accepted++;
        }
        return new Prepared(pairs,accepted,rejected,reasons);
    }
    private static String stamp(String dim,long tick){return dim+"#"+tick;}
    private static boolean unit(Vec v) {
        return v.finite()&&Math.abs(v.squareLength()-1.0)<0.015;
    }
    private static Body find(Snapshot s,int runtimeId) {
        Body result=null;
        for(Body b:s.bodies())if(b.runtimeId()==runtimeId) {
            if(result!=null)return null;
            result=b;
        }
        return result;
    }
}

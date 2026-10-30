package com.physicaldestroydeform.collision;

import static com.physicaldestroydeform.collision.FrameMath.*;

/** G-MATH-01: independent, immutable normal-impact math. No world mutation or solver impulse. */
public final class ImpactMath {
    private ImpactMath() {}
    private static final double EPS = 1e-12;

    public enum Kind { EXACT_CONTACT, CONSERVATIVE_PROXY, UNRESOLVED }
    public enum Reason { APPROVED, NO_APPROVED_BUDGET, OWNER_UNVERIFIED, STALE_SNAPSHOT,
        CONTACT_UNVERIFIED, DIRECTION_UNVERIFIED, MASS_INVALID, KINEMATICS_INVALID,
        ROTATION_UNRESOLVED, POLICY_DISABLED, POLICY_UNCONFIGURED,
        SEPARATING_OR_TANGENTIAL, INVALID_EFFECTIVE_MASS, DUPLICATE_EPISODE }

    /** One normal inverse-inertia tensor in world coordinates; matrix rows are explicit. */
    public record Tensor(double m00,double m01,double m02,
                         double m10,double m11,double m12,
                         double m20,double m21,double m22) {
        public static Tensor zero() { return new Tensor(0,0,0,0,0,0,0,0,0); }
        public boolean finite() {
            return Double.isFinite(m00)&&Double.isFinite(m01)&&Double.isFinite(m02)
                &&Double.isFinite(m10)&&Double.isFinite(m11)&&Double.isFinite(m12)
                &&Double.isFinite(m20)&&Double.isFinite(m21)&&Double.isFinite(m22);
        }
        public Vec multiply(Vec v) {
            return new Vec(m00*v.x()+m01*v.y()+m02*v.z(),
                           m10*v.x()+m11*v.y()+m12*v.z(),
                           m20*v.x()+m21*v.y()+m22*v.z());
        }
        /** A matrix tensor rotated by three world-space unit basis vectors. */
        public Tensor rotate(Vec x, Vec y, Vec z) {
            double[][] source={{m00,m01,m02},{m10,m11,m12},{m20,m21,m22}};
            double[][] b={{x.x(),y.x(),z.x()},{x.y(),y.y(),z.y()},{x.z(),y.z(),z.z()}};
            double[][] transformed=new double[3][3];
            for(int i=0;i<3;i++)for(int j=0;j<3;j++)
                for(int k=0;k<3;k++)for(int l=0;l<3;l++)
                    transformed[i][j]+=b[i][k]*source[k][l]*b[j][l];
            return new Tensor(transformed[0][0],transformed[0][1],transformed[0][2],
                    transformed[1][0],transformed[1][1],transformed[1][2],
                    transformed[2][0],transformed[2][1],transformed[2][2]);
        }
    }

    /** Infinite static world uses isStatic=true, inverse mass and inertia identically zero. */
    public record Body(boolean isStatic, double mass, double inverseMass, Tensor inverseInertiaWorld,
                       Vec centerWorld, Vec linearWorld, Vec angularWorld) {
        public static Body staticWorld() {
            return new Body(true,Double.POSITIVE_INFINITY,0,Tensor.zero(),
                    new Vec(0,0,0),new Vec(0,0,0),new Vec(0,0,0));
        }
        public boolean valid() {
            if(centerWorld==null||!centerWorld.finite()||linearWorld==null||!linearWorld.finite()
                    ||angularWorld==null||!angularWorld.finite()||inverseInertiaWorld==null
                    ||!inverseInertiaWorld.finite())return false;
            if(isStatic)return inverseMass==0;
            if(!Double.isFinite(mass)||mass<=0||!Double.isFinite(inverseMass)||inverseMass<=0)
                return false;
            return Math.abs(inverseMass*mass-1.0)<=1e-4;
        }
        public double inverseNormalMass(Vec point, Vec normal) {
            if(isStatic)return 0;
            Vec lever=point.minus(centerWorld).cross(normal);
            double q=inverseMass+lever.dot(inverseInertiaWorld.multiply(lever));
            return q;
        }
        public Vec contactVelocity(Vec point) {
            return FrameMath.contactVelocity(linearWorld,angularWorld,point,centerWorld);
        }
    }

    /** Both flags must be independently supported by the caller's engine evidence. */
    public record ExactInput(Body a,Body b,Vec contactWorld,Vec normalBtoA,
                             boolean ownersVerified,boolean samePreSolverSnapshot,
                             boolean contactAndNormalValidated) {}
    public record ProxyInput(Body a,Body b,Vec normalBtoA,
                             boolean ownersVerified,boolean samePreSolverSnapshot,
                             boolean independentDirectionValidated,boolean rotationDominated) {}
    /** No hidden policy constant. Defaults are disabled until operator explicitly configures. */
    public record ProxyPolicy(boolean enabled,double factor,double availableStepCap) {
        public static ProxyPolicy disabled() { return new ProxyPolicy(false,Double.NaN,Double.NaN); }
        private static double property(String name) {
            try { return Double.parseDouble(System.getProperty(name,"NaN")); }
            catch(NumberFormatException invalid) { return Double.NaN; }
        }
        public static ProxyPolicy fromSystemProperties() {
            return new ProxyPolicy(Boolean.parseBoolean(System.getProperty("pdd.proxy.enabled","false")),
                    property("pdd.proxy.factor"),property("pdd.proxy.stepCap"));
        }
        public boolean configured() {return Double.isFinite(factor)&&factor>=0&&factor<=1
                    &&Double.isFinite(availableStepCap)&&availableStepCap>0;}
    }
    public record Result(Kind kind, Reason reason, double budget, double closingSpeed,
                         double effectiveMass, String source) {
        public static Result reject(Reason why) {
            return new Result(Kind.UNRESOLVED,why,0,0,0,"NONE");
        }
        public boolean approved() {return reason==Reason.APPROVED && budget>0;}
    }
    private static Vec normal(Vec candidate) {
        if(candidate==null || !candidate.finite())return null;
        double len=Math.sqrt(candidate.squareLength());
        if(!Double.isFinite(len)||len<EPS||Math.abs(len-1)>1e-6)return null;
        return candidate;
    }
    public static Result exact(ExactInput input) {
        if(input==null || !input.ownersVerified())return Result.reject(Reason.OWNER_UNVERIFIED);
        if(!input.samePreSolverSnapshot())return Result.reject(Reason.STALE_SNAPSHOT);
        if(!input.contactAndNormalValidated() || input.contactWorld()==null || !input.contactWorld().finite())
            return Result.reject(Reason.CONTACT_UNVERIFIED);
        Vec n=normal(input.normalBtoA());
        if(n==null)return Result.reject(Reason.DIRECTION_UNVERIFIED);
        Body a=input.a(), b=input.b();
        if(a==null||b==null||!a.valid()||!b.valid()||(a.isStatic()&&b.isStatic()))
            return Result.reject(Reason.MASS_INVALID);
        Vec relative=a.contactVelocity(input.contactWorld()).minus(b.contactVelocity(input.contactWorld()));
        double closing=Math.max(0,-relative.dot(n));
        if(!Double.isFinite(closing))return Result.reject(Reason.KINEMATICS_INVALID);
        if(closing<=EPS)return Result.reject(Reason.SEPARATING_OR_TANGENTIAL);
        double q=a.inverseNormalMass(input.contactWorld(),n)+b.inverseNormalMass(input.contactWorld(),n);
        if(!Double.isFinite(q)||q<=EPS)return Result.reject(Reason.INVALID_EFFECTIVE_MASS);
        double effectiveMass=1.0/q;
        double budget=0.5*effectiveMass*closing*closing;
        if(!Double.isFinite(budget)||budget<=0)return Result.reject(Reason.INVALID_EFFECTIVE_MASS);
        return new Result(Kind.EXACT_CONTACT,Reason.APPROVED,budget,closing,effectiveMass,
                "VALIDATED_CONTACT_NORMAL_PRE_SOLVER");
    }
    /** Prefer exact contact when valid; proxy requires its own independent input gate. */
    public static Result select(ExactInput exact, ProxyInput fallback, ProxyPolicy policy) {
        Result first=exact(exact);
        if(first.approved())return first;
        if(first.reason()!=Reason.CONTACT_UNVERIFIED && first.reason()!=Reason.DIRECTION_UNVERIFIED)
            return first;
        return proxy(fallback,policy);
    }
    public static Result proxy(ProxyInput input, ProxyPolicy policy) {
        if(input==null||!input.ownersVerified())return Result.reject(Reason.OWNER_UNVERIFIED);
        if(!input.samePreSolverSnapshot())return Result.reject(Reason.STALE_SNAPSHOT);
        if(policy==null || !policy.enabled())return Result.reject(Reason.POLICY_DISABLED);
        if(!policy.configured())return Result.reject(Reason.POLICY_UNCONFIGURED);
        if(!input.independentDirectionValidated())return Result.reject(Reason.DIRECTION_UNVERIFIED);
        Vec n=normal(input.normalBtoA());
        if(n==null)return Result.reject(Reason.DIRECTION_UNVERIFIED);
        Body a=input.a(),b=input.b();
        if(a==null||b==null||!a.valid()||!b.valid()||(a.isStatic()&&b.isStatic()))
            return Result.reject(Reason.MASS_INVALID);
        if(input.rotationDominated())return Result.reject(Reason.ROTATION_UNRESOLVED);
        double closing=Math.max(0,-a.linearWorld().minus(b.linearWorld()).dot(n));
        if(!Double.isFinite(closing))return Result.reject(Reason.KINEMATICS_INVALID);
        if(closing<=EPS)return Result.reject(Reason.SEPARATING_OR_TANGENTIAL);
        double reduced=a.isStatic()?b.mass():b.isStatic()?a.mass():
                1.0/(1.0/a.mass()+1.0/b.mass());
        if(!Double.isFinite(reduced)||reduced<=0)return Result.reject(Reason.MASS_INVALID);
        double base=0.5*reduced*closing*closing;
        double budget=Math.min(base*policy.factor(),policy.availableStepCap());
        if(!Double.isFinite(budget)||budget<=0)return Result.reject(Reason.NO_APPROVED_BUDGET);
        return new Result(Kind.CONSERVATIVE_PROXY,Reason.APPROVED,budget,closing,reduced,
                "GAME_PROXY_TRANSLATION_ONLY");
    }
}

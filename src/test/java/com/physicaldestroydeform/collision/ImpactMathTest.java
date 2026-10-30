package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.FrameMath.*;
import static com.physicaldestroydeform.collision.ImpactMath.*;

class ImpactMathTest {
    private static final Vec ZERO=new Vec(0,0,0), X=new Vec(1,0,0);
    private static Body dynamic(double mass,Vec velocity,Vec omega,Tensor inertia) {
        return new Body(false,mass,1/mass,inertia,ZERO,velocity,omega);
    }
    private static Tensor diagonal(double x,double y,double z) {
        return new Tensor(x,0,0,0,y,0,0,0,z);
    }
    private static ExactInput exact(Body a,Body b,Vec point,Vec normal) {
        return new ExactInput(a,b,point,normal,true,true,true);
    }
    @Test void movingAgainstStaticWorld() {
        Result r=ImpactMath.exact(exact(dynamic(2,new Vec(-10,0,0),ZERO,Tensor.zero()),Body.staticWorld(),ZERO,X));
        assertEquals(Kind.EXACT_CONTACT,r.kind());assertEquals(100,r.budget(),1e-10);
        assertEquals(2,r.effectiveMass(),1e-10);assertEquals(10,r.closingSpeed(),1e-10);
    }
    @Test void twoMovingBodiesReducedMass() {
        Result r=ImpactMath.exact(exact(dynamic(2,new Vec(-4,0,0),ZERO,Tensor.zero()),
                dynamic(3,new Vec(2,0,0),ZERO,Tensor.zero()),ZERO,X));
        assertEquals(1.2,r.effectiveMass(),1e-10);assertEquals(21.6,r.budget(),1e-10);
    }
    @Test void rotatingTipContributesSpeedAndInertia() {
        Body a=dynamic(1,ZERO,new Vec(0,0,1),diagonal(1,1,1));
        Result tip=ImpactMath.exact(exact(a,Body.staticWorld(),new Vec(0,1,0),X));
        assertEquals(0.5,tip.effectiveMass(),1e-10);
        assertEquals(1,tip.closingSpeed(),1e-10);
        assertEquals(0.25,tip.budget(),1e-10);
        assertEquals(Reason.SEPARATING_OR_TANGENTIAL,
                ImpactMath.exact(exact(a,Body.staticWorld(),ZERO,X)).reason());
    }
    @Test void separatingOrTangentialIsNotImpact() {
        for(Vec v:new Vec[]{new Vec(4,0,0),new Vec(0,9,0),ZERO})
            assertEquals(Reason.SEPARATING_OR_TANGENTIAL,
                    ImpactMath.exact(exact(dynamic(1,v,ZERO,Tensor.zero()),Body.staticWorld(),ZERO,X)).reason());
    }
    @Test void invalidMassNormalAndInputQualityAreRejected() {
        Body a=dynamic(2,new Vec(-4,0,0),ZERO,Tensor.zero());
        assertEquals(Reason.CONTACT_UNVERIFIED,
                ImpactMath.exact(new ExactInput(a,Body.staticWorld(),ZERO,X,true,true,false)).reason());
        assertEquals(Reason.STALE_SNAPSHOT,
                ImpactMath.exact(new ExactInput(a,Body.staticWorld(),ZERO,X,true,false,true)).reason());
        assertEquals(Reason.DIRECTION_UNVERIFIED,
                ImpactMath.exact(exact(a,Body.staticWorld(),ZERO,new Vec(2,0,0))).reason());
        assertEquals(Reason.DIRECTION_UNVERIFIED,
                ImpactMath.exact(exact(a,Body.staticWorld(),ZERO,new Vec(Double.NaN,0,0))).reason());
        assertEquals(Reason.MASS_INVALID,ImpactMath.exact(exact(
                dynamic(0,new Vec(-1,0,0),ZERO,Tensor.zero()),Body.staticWorld(),ZERO,X)).reason());
        assertEquals(Reason.OWNER_UNVERIFIED,ImpactMath.exact(
                new ExactInput(a,Body.staticWorld(),ZERO,X,false,true,true)).reason());
    }
    @Test void invalidEffectiveMassIsRejected() {
        Body broken=dynamic(1,new Vec(-3,0,0),ZERO,diagonal(0,0,-2));
        // The large negative z inverse inertia makes q<=0 at the lever arm.
        assertEquals(Reason.INVALID_EFFECTIVE_MASS,
                ImpactMath.exact(exact(broken,Body.staticWorld(),new Vec(0,1,0),X)).reason());
    }
    @Test void proxyRequiresExplicitPolicyAndIndependentDirection() {
        Body a=dynamic(2,new Vec(-8,0,0),ZERO,Tensor.zero());
        ProxyInput validated=new ProxyInput(a,Body.staticWorld(),X,true,true,true,false);
        assertEquals(Reason.POLICY_DISABLED,ImpactMath.proxy(validated,ProxyPolicy.disabled()).reason());
        assertEquals(Reason.POLICY_UNCONFIGURED,
                ImpactMath.proxy(validated,new ProxyPolicy(true,Double.NaN,10)).reason());
        assertEquals(Reason.DIRECTION_UNVERIFIED,
                ImpactMath.proxy(new ProxyInput(a,Body.staticWorld(),X,true,true,false,false),
                        new ProxyPolicy(true,.5,10)).reason());
        Result approved=ImpactMath.proxy(validated,new ProxyPolicy(true,.5,10));
        assertEquals(Kind.CONSERVATIVE_PROXY,approved.kind());
        assertEquals(10,approved.budget(),1e-10);assertEquals("GAME_PROXY_TRANSLATION_ONLY",approved.source());
    }
    @Test void proxyRejectsRotationWithoutContactAndSeparating() {
        ProxyPolicy policy=new ProxyPolicy(true,.5,1000);
        Body a=dynamic(2,new Vec(-8,0,0),new Vec(0,0,9),Tensor.zero());
        assertEquals(Reason.ROTATION_UNRESOLVED,
                ImpactMath.proxy(new ProxyInput(a,Body.staticWorld(),X,true,true,true,true),policy).reason());
        assertEquals(Reason.SEPARATING_OR_TANGENTIAL,
                ImpactMath.proxy(new ProxyInput(dynamic(2,new Vec(8,0,0),ZERO,Tensor.zero()),
                        Body.staticWorld(),X,true,true,true,false),policy).reason());
    }
    @Test void neverDoubleCountExactAndProxy() {
        Body a=dynamic(2,new Vec(-4,0,0),ZERO,Tensor.zero());
        ProxyInput proxy=new ProxyInput(a,Body.staticWorld(),X,true,true,true,false);
        Result exact=ImpactMath.select(exact(a,Body.staticWorld(),ZERO,X),proxy,new ProxyPolicy(true,.5,10));
        assertEquals(Kind.EXACT_CONTACT,exact.kind());
        Result fallback=ImpactMath.select(new ExactInput(a,Body.staticWorld(),null,X,true,true,false),
                proxy,new ProxyPolicy(true,.5,10));
        assertEquals(Kind.CONSERVATIVE_PROXY,fallback.kind());
    }
    @Test void inertiaTensorRotation() {
        Tensor rotated=diagonal(1,2,3).rotate(new Vec(0,1,0),new Vec(-1,0,0),new Vec(0,0,1));
        assertEquals(2,rotated.m00(),1e-10);assertEquals(1,rotated.m11(),1e-10);
        assertEquals(3,rotated.m22(),1e-10);
    }
}

package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static com.physicaldestroydeform.collision.GameImpactCredit.*;
import static org.junit.jupiter.api.Assertions.*;

class SecondaryContactPolicyTest {
    private static EvidenceSnapshot active(boolean pending) {
        return new EvidenceSnapshot(Phase.ACTIVE,9,0,pending,0,1,1,8);
    }
    private static Decision decision(Reason reason) {
        double q=reason==Reason.APPROVED?10:0;
        return new Decision(Phase.ACTIVE,reason,100,q,new Allocation(q/2,q/2,0),1,1,8,10,"test");
    }
    @Test void onlyACompletedDuplicateDecisionCanCarryExistingCredit() {
        assertTrue(RealFixRuntimeBridge.eligibleSecondary(decision(Reason.DUPLICATE_EPISODE),active(false),true,false,false));
        for(var reason:Reason.values())if(reason!=Reason.DUPLICATE_EPISODE)
            assertFalse(RealFixRuntimeBridge.eligibleSecondary(decision(reason),active(false),true,false,false));
    }
    @Test void pendingAuthorizationSeparationAndIndependentClosingCannotBecomeSecondary() {
        var duplicate=decision(Reason.DUPLICATE_EPISODE);
        assertFalse(RealFixRuntimeBridge.eligibleSecondary(duplicate,active(true),true,false,false));
        assertFalse(RealFixRuntimeBridge.eligibleSecondary(duplicate,active(false),false,false,false));
        assertFalse(RealFixRuntimeBridge.eligibleSecondary(duplicate,active(false),true,true,false));
        assertFalse(RealFixRuntimeBridge.eligibleSecondary(duplicate,active(false),true,false,true));
    }
}

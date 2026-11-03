package com.physicaldestroydeform.material;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.collision.ContactCreditLedger;
import com.physicaldestroydeform.collision.DirectionEvidence;
import com.physicaldestroydeform.collision.GameImpactCredit;
import com.physicaldestroydeform.state.SparseStateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Consume *issued* target shares without treating reservation as spent or writing World/Plot state. */
public final class MaterialPlanner {
    public record TargetPlan(SparseStateKey key,MaterialProperties material,DamageModel.Plan plan,
                             long materialGeneration,Optional<DirectionEvidence> direction) {
        public TargetPlan {direction=Objects.requireNonNull(direction);}
    }
    public record Report(ContactCreditLedger.CreditId creditId,long materialGeneration,
                         List<TargetPlan> plans,double plannedQ,double missingMaterialQ,
                         double absorbedQ,double directQ,double unusedPlannedQ,
                         long fixedIncrementTotal) {
        public Report {plans=List.copyOf(plans);}
        public double accountedQ(){return absorbedQ+directQ+unusedPlannedQ+missingMaterialQ;}
    }
    private MaterialPlanner() {}
    public static Report plan(ContactCreditLedger.Grant grant, long materialGeneration,
                              Function<String,MaterialProperties> materialLookup,
                              DamageModel.Settings settings) {
        Objects.requireNonNull(grant);Objects.requireNonNull(materialLookup);Objects.requireNonNull(settings);
        if(!grant.credited())throw new IllegalArgumentException("not an issued credit");
        List<TargetPlan> plans=new ArrayList<>();
        double missingQ=0,plannedQ=0,absorbedQ=0,directQ=0,unused=grant.unused();
        long increments=0;
        for(ContactCreditLedger.Share share:concat(grant.a(),grant.b())) {
            GameImpactCredit.Target target=share.target();
            MaterialProperties material=materialLookup.apply(target.fingerprint());
            if(material==null) {missingQ+=share.q();continue;}
            GameImpactCredit.Owner owner=grant.id().pair().a().stableKey().equals(target.ownerKey())
                    ?grant.id().pair().a():grant.id().pair().b();
            SparseStateKey key=SparseStateKey.fromTarget(grant.id().pair().dimension(),owner,target);
            // No committed old damage is available until 0.4.1; preview starts from pristine.
            DamageModel.Plan p=DamageModel.plan(share.q(),material,new BlockDamageState(),settings);
            plans.add(new TargetPlan(key,material,p,materialGeneration,Optional.empty()));
            plannedQ+=share.q();absorbedQ+=p.absorbedQ();directQ+=p.directQ();
            unused+=p.unusedPlannedQ();increments+=p.fixedIncrement();
        }
        return new Report(grant.id(),materialGeneration,plans,plannedQ,missingQ,absorbedQ,directQ,unused,increments);
    }
    private static List<ContactCreditLedger.Share> concat(List<ContactCreditLedger.Share> a,
                                                            List<ContactCreditLedger.Share> b) {
        List<ContactCreditLedger.Share> out=new ArrayList<>(a.size()+b.size());
        out.addAll(a);out.addAll(b);return out;
    }
}

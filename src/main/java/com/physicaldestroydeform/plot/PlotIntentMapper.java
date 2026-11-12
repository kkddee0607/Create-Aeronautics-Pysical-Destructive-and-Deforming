package com.physicaldestroydeform.plot;

import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.collision.ContactCreditLedger;
import com.physicaldestroydeform.collision.GameImpactCredit;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.WorldCommitCore;
import com.physicaldestroydeform.world.WorldIntentMapper;
import java.util.*;
import java.util.function.Function;

/** Project the original immutable Plot shares, never reissue or recompute F/Q. */
public final class PlotIntentMapper {
    public record Projection(List<WorldCommitCore.Intent> plot, double worldQ,
                             double missingMaterialQ,double missingOwnerQ,double rejectedQ) {
        public Projection { plot=List.copyOf(plot); }
    }
    private PlotIntentMapper(){}
    public static Projection project(ContactCreditLedger.Grant grant,long materialGeneration,
                                     Function<String,MaterialProperties> materials,
                                     Function<SparseStateKey,String> incarnation) {
        Objects.requireNonNull(grant); Objects.requireNonNull(materials);Objects.requireNonNull(incarnation);
        if(!grant.credited())throw new IllegalArgumentException("only an issued credit is projectable");
        List<WorldCommitCore.Intent> result=new ArrayList<>();
        Set<SparseStateKey> used=new HashSet<>();
        double world=0,missingMat=0,missingOwner=0,rejected=0;
        List<ContactCreditLedger.Share> shares=new ArrayList<>(grant.a());shares.addAll(grant.b());
        for(var share:shares) {
            var target=share.target();
            var pair=grant.id().pair();
            GameImpactCredit.Owner owner=pair.a().stableKey().equals(target.ownerKey())?pair.a()
                    :pair.b().stableKey().equals(target.ownerKey())?pair.b():null;
            if(owner==null){rejected+=share.q();continue;}
            SparseStateKey real=SparseStateKey.fromTarget(pair.dimension(),owner,target);
            if(!"PLOT".equals(real.ownerSpace())){world+=share.q();continue;}
            if(!used.add(real)){rejected+=share.q();continue;}
            MaterialProperties material=materials.apply(target.fingerprint());
            if(material==null){missingMat+=share.q();continue;}
            String token=incarnation.apply(real);
            if(token==null||token.isBlank()){missingOwner+=share.q();continue;}
            String applyId="PLOT:"+WorldIntentMapper.id(grant,real);
            result.add(new WorldCommitCore.Intent(applyId,PlotKeyCodec.storage(real),token,
                    share.q(),material,materialGeneration,grant.tick(),grant.propagated()));
        }
        double usedQ=result.stream().mapToDouble(WorldCommitCore.Intent::sealedQ).sum();
        double accounted=usedQ+world+missingMat+missingOwner+rejected+grant.unused();
        if(Math.abs(accounted-grant.issuedQ())>Math.max(1.0e-7,grant.issuedQ()*1.0e-7))
            throw new IllegalArgumentException("sealed World+Plot credit conservation failed");
        return new Projection(result,world,missingMat,missingOwner,rejected);
    }
}

package com.physicaldestroydeform.world;

import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.collision.ContactCreditLedger;
import com.physicaldestroydeform.collision.GameImpactCredit;
import com.physicaldestroydeform.state.SparseStateKey;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;

/** Lossless projection of already-sealed F-first Grant shares into World-only commit intents.
 * Never recalculates source F, rescales Q, or invents verified block/incarnation evidence.
 */
public final class WorldIntentMapper {
    public record Projection(List<WorldCommitCore.Intent> world, double plotQ, double missingMaterialQ,
                             double missingIncarnationQ, double rejectedQ) {
        public Projection {world=List.copyOf(world);}
    }
    private WorldIntentMapper(){}
    public static Projection project(ContactCreditLedger.Grant grant,long materialGeneration,
                                     Function<String,MaterialProperties> materials,
                                     Function<SparseStateKey,String> incarnation) {
        Objects.requireNonNull(grant);Objects.requireNonNull(materials);Objects.requireNonNull(incarnation);
        if(!grant.credited())throw new IllegalArgumentException("not an issued credit");
        List<WorldCommitCore.Intent> out=new ArrayList<>();
        double plot=0,missingMat=0,missingInc=0,rejected=0;
        HashSet<SparseStateKey> visited=new HashSet<>();
        for(var share:all(grant)) {
            var target=share.target();
            GameImpactCredit.Owner owner=ownerFor(grant,target);
            if(owner==null){rejected+=share.q();continue;}
            SparseStateKey key=SparseStateKey.fromTarget(grant.id().pair().dimension(),owner,target);
            if(!"WORLD".equals(key.ownerSpace())){plot+=share.q();continue;}
            if(!visited.add(key)){rejected+=share.q();continue;}
            MaterialProperties material=materials.apply(target.fingerprint());
            if(material==null){missingMat+=share.q();continue;}
            String tag=incarnation.apply(key);
            if(tag==null||tag.isBlank()){missingInc+=share.q();continue;}
            out.add(new WorldCommitCore.Intent(id(grant,key),key,tag,share.q(),material,
                    materialGeneration,grant.tick(),grant.propagated()));
        }
        double accounted=out.stream().mapToDouble(WorldCommitCore.Intent::sealedQ).sum()
                +plot+missingMat+missingInc+rejected+grant.unused();
        if(Math.abs(accounted-grant.issuedQ())>Math.max(1.0e-7,grant.issuedQ()*1.0e-7))
            throw new IllegalArgumentException("World + Plot sealed Q conservation failed");
        return new Projection(out,plot,missingMat,missingInc,rejected);
    }
    private static GameImpactCredit.Owner ownerFor(ContactCreditLedger.Grant grant,GameImpactCredit.Target target) {
        var pair=grant.id().pair();
        return pair.a().stableKey().equals(target.ownerKey())?pair.a()
                :pair.b().stableKey().equals(target.ownerKey())?pair.b():null;
    }
    private static List<ContactCreditLedger.Share> all(ContactCreditLedger.Grant g){
        List<ContactCreditLedger.Share> result=new ArrayList<>(g.a().size()+g.b().size());
        result.addAll(g.a());result.addAll(g.b());return result;
    }
    /** Separate source/cause, generation, owner and target fields, no Java hashCode persistence. */
    public static String id(ContactCreditLedger.Grant grant,SparseStateKey key) {
        try {
            var bytes=new ByteArrayOutputStream();
            try(var d=new DataOutputStream(bytes)) {
                var cid=grant.id();var p=cid.pair();
                d.writeUTF("PDD-0.4.1/WORLD/DIRECT");d.writeUTF(grant.source().name());
                d.writeUTF(p.runId());d.writeUTF(p.dimension());
                d.writeUTF(p.a().stableKey());d.writeLong(p.a().generation());
                d.writeUTF(p.b().stableKey());d.writeLong(p.b().generation());
                d.writeLong(cid.episodeGeneration());
                d.writeUTF(key.ownerSpace());d.writeUTF(key.ownerId());d.writeLong(key.ownerGeneration());
                d.writeInt(key.x());d.writeInt(key.y());d.writeInt(key.z());d.writeUTF(key.fingerprint());
            }
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            return HexFormat.of().formatHex(digest);
        }catch(IOException|NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}

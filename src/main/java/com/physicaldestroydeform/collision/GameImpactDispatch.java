package com.physicaldestroydeform.collision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One server-safe caller per Sable reported-effect tick. External Sable adapter
 * resolves runtime body IDs to stable owners, target blocks and geometry proofs.
 * No native record is promoted to a verified block or approach on its own.
 */
public final class GameImpactDispatch {
    public record VerifiedScene(GameImpactCredit.Pair pair,
                                GameImpactCredit.Target targetA,
                                GameImpactCredit.Target targetB,
                                boolean positivelySeparated,
                                boolean verifiedApproach,
                                String method) {
        public VerifiedScene { Objects.requireNonNull(pair); Objects.requireNonNull(method); }
    }
    @FunctionalInterface public interface SceneEvidence {
        /** Return null when body generation or dimension cannot be proven. */
        VerifiedScene resolve(NativeEffectGroups.Group group);
    }
    public record Outcome(NativeEffectGroups.Group observation,
                          GameImpactCredit.Decision budget,
                          String rejectReason) {
        public boolean successful() { return budget != null && budget.credited(); }
        public String logLine() {
            String source = observation.runtimePair().lowId()+":"+observation.runtimePair().highId();
            if(budget==null) return "[PDD-EPISODE] version=0.3.1 source=POST_EFFECT " +
                    "dimension="+observation.dimension()+" tick="+observation.tick()+
                    " runtimeIds="+source+" records="+observation.originalRecordCount()+
                    " peak="+observation.forcePeak()+" quality=OWNER_UNRESOLVED Q=0"+
                    " worldMutation=OFF reason="+rejectReason;
            return "[PDD-EPISODE] version=0.3.1 source=POST_EFFECT " +
                   "dimension="+observation.dimension()+" tick="+observation.tick()+
                   " runtimeIds="+source+" records="+observation.originalRecordCount()+
                   " peak="+budget.peakForce()+" state="+budget.phase()+
                   " reason="+budget.reason()+" Q="+budget.issuedQ()+
                   " allocatedA="+budget.allocation().sideA()+
                   " allocatedB="+budget.allocation().sideB()+
                   " unused="+budget.allocation().unused()+
                   " quality=GAME_POLICY_ONLY worldMutation=OFF";
        }
    }
    private final GameImpactCredit credit;
    public GameImpactDispatch(GameImpactCredit credit) { this.credit=Objects.requireNonNull(credit); }
    public List<Outcome> handle(NativeEffectGroups.Batch batch, SceneEvidence source) {
        Objects.requireNonNull(batch); Objects.requireNonNull(source);
        var out=new ArrayList<Outcome>(batch.groups().size());
        for(var group:batch.groups()) {
            // A malformed/dropped batch cannot supply the complete peak or target
            // evidence. Keep its diagnostic sample but never emit partial credit.
            if(batch.malformedArray() || batch.discardedRecords()>0 || batch.invalidRecords()>0) {
                out.add(new Outcome(group,null,"INCOMPLETE_NATIVE_BATCH"));
                continue;
            }
            final VerifiedScene scene=source.resolve(group);
            if(scene==null) {
                out.add(new Outcome(group,null,"BODY_ID_OR_OWNER_GENERATION_UNVERIFIED"));
                continue;
            }
            // Same source frame with opposing side ordering is canonicalized by SceneEvidence.
            var frame=new GameImpactCredit.Frame(scene.pair(),group.tick(),
                    group.samples(),scene.positivelySeparated(),scene.verifiedApproach(),
                    scene.targetA(),scene.targetB(),group.forcePeak());
            out.add(new Outcome(group,credit.step(frame),"NONE"));
        }
        return List.copyOf(out);
    }
    public GameImpactCredit engine() { return credit; }
}

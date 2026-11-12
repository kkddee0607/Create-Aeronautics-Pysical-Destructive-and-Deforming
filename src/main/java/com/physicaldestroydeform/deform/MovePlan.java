package com.physicaldestroydeform.deform;

import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.state.SparseStateKey;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * 0.5.1 CAP-DEFORM-PLAN: immutable owner-local preflight intent, NOT an executable
 * World/Plot move. No position is invented when signed direction is unverified.
 * The 0.5.x executor must later bind a verified epoch, original BE/state payload,
 * journal, CAS and reverse-ordered movement before claiming an applied move.
 */
public record MovePlan(String creditId,SparseStateKey source,long damageBeforeFixed,
                       long eventNormalDamageFixed,int threshold,long ownerGeneration,long directionEpoch,
                       double k,double sourceF,double reservedQ,
                       List<Step> steps,Status status) {
    public enum Status { BREAK_FIRST, BELOW_YIELD, DIRECTION_UNKNOWN, CANDIDATE_ONLY }
    public record Step(int dx,int dy,int dz) {
        public Step {
            if(dx < -1||dx > 1||dy < -1||dy > 1||dz < -1||dz > 1
                    ||Math.abs(dx)+Math.abs(dy)+Math.abs(dz)!=1)
                throw new IllegalArgumentException("a step must move exactly one signed axis");
        }
    }
    public record SignedAxes(int x,int y,int z,boolean verified,long transformEpoch) {
        public SignedAxes {
            int count=(x!=0?1:0)+(y!=0?1:0)+(z!=0?1:0);
            if(x < -1||x > 1||y < -1||y > 1||z < -1||z > 1||count<1||count>2
                    ||transformEpoch<0)
                throw new IllegalArgumentException("1..2 owner-local signed axes required");
        }
        public List<Step> steps(){
            var list=new java.util.ArrayList<Step>(2);
            if(x!=0)list.add(new Step(x,0,0));
            if(y!=0)list.add(new Step(0,y,0));
            if(z!=0)list.add(new Step(0,0,z));
            return List.copyOf(list);
        }
    }
    public MovePlan {
        Objects.requireNonNull(creditId); Objects.requireNonNull(source);
        if(creditId.isBlank()||threshold<1||threshold>1024||damageBeforeFixed<0
                ||eventNormalDamageFixed<0||ownerGeneration!=source.ownerGeneration()
                ||(status==Status.CANDIDATE_ONLY?directionEpoch<0:directionEpoch!=-1)
                ||!Double.isFinite(sourceF)||sourceF<0||!Double.isFinite(reservedQ)||reservedQ<0)
            throw new IllegalArgumentException("invalid deformation source or damage-equivalent");
        Objects.requireNonNull(steps);steps=List.copyOf(steps);Objects.requireNonNull(status);
    }
    public static double yieldMultiplier(int ductilityPercent){
        if(ductilityPercent<0||ductilityPercent>100)
            throw new IllegalArgumentException("ductility must be 0..100");
        return 1.0-0.75*ductilityPercent/100.0;
    }
    public static MovePlan plan(String creditId,SparseStateKey key,MaterialProperties material,
                                long beforeFixed,long normalIncrementFixed,double sourceF,
                                double reservedQ,SignedAxes provenDirection) {
        Objects.requireNonNull(material);Objects.requireNonNull(key);
        if(beforeFixed<0||normalIncrementFixed<0)throw new IllegalArgumentException("negative fixed damage");
        long remaining=Math.max(0L,material.threshold()*256L-beforeFixed);
        double k=yieldMultiplier(material.ductilityPercent());
        Status status;
        if(remaining==0 || normalIncrementFixed>=remaining)status=Status.BREAK_FIRST;
        else if(normalIncrementFixed<Math.ceil(remaining*k))status=Status.BELOW_YIELD;
        else if(provenDirection==null || !provenDirection.verified())status=Status.DIRECTION_UNKNOWN;
        else status=Status.CANDIDATE_ONLY;
        return new MovePlan(creditId,key,beforeFixed,normalIncrementFixed,material.threshold(),
                key.ownerGeneration(),status==Status.CANDIDATE_ONLY?provenDirection.transformEpoch():-1,
                k,sourceF,reservedQ,
                status==Status.CANDIDATE_ONLY?provenDirection.steps():List.of(),status);
    }
    /**
     * Future atomic mutation contract. Creating this proof-bound envelope does NOT
     * authorize or execute a move. The 0.5.x executor still needs live CAS,
     * path occupancy, BE/glue/kinetic/collider checks, PREPARED WAL and rollback.
     *
     * Snapshot fields are explicit so a caller cannot infer a destination by
     * using World coordinates or silently claim an unknown BE payload as safe.
     */
    public record AtomicMoveSpec(String creditId,SparseStateKey before,SparseStateKey after,
                                 SignedAxes verifiedDirection,long layerOrder,
                                 long expectedOwnerGeneration,
                                 String beforeFingerprint,String destinationFingerprint,
                                 String blockStateSnapshot,byte[] bePayload,
                                 int sparseDamage,int sparseRemainder,
                                 long materialGeneration,String policyRevision,
                                 double reservedQ,String rollbackOrQuarantineProof) {
        public AtomicMoveSpec {
            Objects.requireNonNull(creditId);Objects.requireNonNull(before);Objects.requireNonNull(after);
            Objects.requireNonNull(verifiedDirection);Objects.requireNonNull(beforeFingerprint);
            Objects.requireNonNull(destinationFingerprint);Objects.requireNonNull(blockStateSnapshot);
            Objects.requireNonNull(policyRevision);Objects.requireNonNull(rollbackOrQuarantineProof);
            if(!verifiedDirection.verified()||creditId.isBlank()||layerOrder<0
                    ||!"PLOT".equals(before.ownerSpace())||!"PLOT".equals(after.ownerSpace())
                    ||expectedOwnerGeneration!=before.ownerGeneration()
                    ||expectedOwnerGeneration!=after.ownerGeneration()
                    ||!before.dimension().equals(after.dimension())
                    ||!before.ownerId().equals(after.ownerId())
                    ||!before.fingerprint().equals(beforeFingerprint)
                    ||after.x()!=Math.addExact(before.x(),verifiedDirection.x())
                    ||after.y()!=Math.addExact(before.y(),verifiedDirection.y())
                    ||after.z()!=Math.addExact(before.z(),verifiedDirection.z())
                    ||!after.fingerprint().equals(destinationFingerprint)
                    ||before.equals(after)||blockStateSnapshot.isBlank()
                    ||sparseDamage<0||sparseDamage>1024||sparseRemainder<0||sparseRemainder>255
                    ||materialGeneration<0||policyRevision.isBlank()
                    ||!Double.isFinite(reservedQ)||reservedQ<0||rollbackOrQuarantineProof.isBlank()
                    ||(bePayload!=null&&bePayload.length>8192))
                throw new IllegalArgumentException("unproven owner-local atomic MovePlan");
            bePayload=bePayload==null?null:bePayload.clone();
        }
        @Override public byte[] bePayload(){return bePayload==null?null:bePayload.clone();}
        /** Planned only. No poststate proof, so never return true. */
        public boolean committed(){return false;}
    }
    public AtomicMoveSpec atomicContract(SignedAxes proven,long layerOrder,
                                         String destinationFingerprint,String sourceBlockState,
                                         byte[] bePayload,int sparseDamage,int sparseRemainder,
                                         long materialGeneration,String policyRevision,String rollbackProof) {
        if(status!=Status.CANDIDATE_ONLY||proven==null||!proven.verified()
                ||directionEpoch!=proven.transformEpoch()
                ||!steps.equals(proven.steps()))
            throw new IllegalStateException("only exact verified candidate may form atomic move preflight");
        int dx=0,dy=0,dz=0;
        for(Step step:steps){dx=Math.addExact(dx,step.dx());
            dy=Math.addExact(dy,step.dy());dz=Math.addExact(dz,step.dz());}
        SparseStateKey to=new SparseStateKey(source.dimension(),"PLOT",source.ownerId(),
                source.ownerGeneration(),Math.addExact(source.x(),dx),Math.addExact(source.y(),dy),
                Math.addExact(source.z(),dz),destinationFingerprint);
        return new AtomicMoveSpec(creditId,source,to,proven,layerOrder,ownerGeneration,
                source.fingerprint(),destinationFingerprint,sourceBlockState,bePayload,
                sparseDamage,sparseRemainder,materialGeneration,policyRevision,reservedQ,rollbackProof);
    }
    /** Additional damage after an ACTUAL blocked movement: from reserved Q only. */
    public double blockedQ(DoubleUnaryOperator gameQOfF){
        Objects.requireNonNull(gameQOfF);
        if(status!=Status.CANDIDATE_ONLY)return 0;
        double candidate=gameQOfF.applyAsDouble(sourceF*(1-k));
        if(!Double.isFinite(candidate)||candidate<0)
            throw new IllegalArgumentException("invalid G(F) blocked candidate");
        return Math.min(candidate,reservedQ);
    }
    /** A plan never calls World/Plot mutation or considers itself applied. */
    public boolean applied(){return false;}
}

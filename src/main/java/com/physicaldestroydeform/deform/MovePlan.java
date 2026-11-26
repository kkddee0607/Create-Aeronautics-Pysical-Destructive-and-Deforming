package com.physicaldestroydeform.deform;

import com.physicaldestroydeform.diagnostics.RogTrace;
import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
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
            if(z!=0)list.add(new Step(0,0,z));
            if(y!=0)list.add(new Step(0,y,0));
            return List.copyOf(list);
        }
    }
    /** Original F-first share and target-signed evidence, persisted with the direct intent. */
    public record Evidence(SignedAxes direction,double sourceF,int layer,double blockedCandidateQ) {
        public Evidence(SignedAxes direction,double sourceF,int layer){this(direction,sourceF,layer,-1);}
        public Evidence {Objects.requireNonNull(direction);if(!direction.verified()||!Double.isFinite(sourceF)||sourceF<0||layer<0||!Double.isFinite(blockedCandidateQ)||blockedCandidateQ<0&&blockedCandidateQ!=-1)throw new IllegalArgumentException("invalid deformation evidence");}
        public static void write(java.io.DataOutputStream out,Evidence e)throws java.io.IOException{
            out.writeBoolean(e!=null);if(e==null)return;
            out.writeByte(e.direction.x());out.writeByte(e.direction.y());out.writeByte(e.direction.z());out.writeLong(e.direction.transformEpoch());out.writeDouble(e.sourceF);out.writeInt(e.layer);out.writeDouble(e.blockedCandidateQ);
        }
        public static Evidence read(java.io.DataInputStream in)throws java.io.IOException{return read(in,true);}
        public static Evidence read(java.io.DataInputStream in,boolean sealedCandidate)throws java.io.IOException{
            if(!in.readBoolean())return null;
            var axes=new SignedAxes(in.readByte(),in.readByte(),in.readByte(),true,in.readLong());
            double f=in.readDouble();int layer=in.readInt();
            return new Evidence(axes,f,layer,sealedCandidate?in.readDouble():-1);
        }
    }
    /** Quantization only: the caller must independently establish target-bound evidence. */
    public static SignedAxes quantizeDirection(double x,double y,double z,boolean verified,long epoch) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))
            throw new IllegalArgumentException("non-finite owner-local direction");
        // Rank original magnitudes: normalization can round distinct values into ties.
        double max=Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)));
        if(max==0)throw new IllegalArgumentException("zero owner-local direction");
        double[] component={x,z,y}; // Exact ties: X, Z, Y.
        int first=0;
        for(int i=1;i<3;i++)if(Math.abs(component[i])>Math.abs(component[first]))first=i;
        int second=first==0?1:0;
        for(int i=0;i<3;i++)if(i!=first&&Math.abs(component[i])>Math.abs(component[second]))second=i;
        int[] selected=new int[3];
        selected[first]=component[first]>0?1:-1;
        // Multiplication by two is exact for finite binary inputs unless it overflows;
        // overflow is safely above the finite first magnitude. No half-underflow.
        if(2.0*Math.abs(component[second])>=Math.abs(component[first]))
            selected[second]=component[second]>0?1:-1;
        return new SignedAxes(selected[0],selected[2],selected[1],verified,epoch);
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
        if(RogTrace.sample(Stage.R08,status.name()))RogTrace.detail(Stage.R08,status.name(),"applyId={} key={} beforeFixed={} normalFixed={} threshold={} remainingFixed={} yieldFixed={} K={} ductility={} axes={} sourceF={} reservedQ={}",creditId,key,beforeFixed,normalIncrementFixed,material.threshold(),remaining,Math.ceil(remaining*k),k,material.ductilityPercent(),provenDirection,sourceF,reservedQ);
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
                    ||!List.of("WORLD","PLOT").contains(before.ownerSpace())||!before.ownerSpace().equals(after.ownerSpace())
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
        SparseStateKey to=new SparseStateKey(source.dimension(),source.ownerSpace(),source.ownerId(),
                source.ownerGeneration(),Math.addExact(source.x(),dx),Math.addExact(source.y(),dy),
                Math.addExact(source.z(),dz),destinationFingerprint);
        return new AtomicMoveSpec(creditId,source,to,proven,layerOrder,ownerGeneration,
                source.fingerprint(),destinationFingerprint,sourceBlockState,bePayload,
                sparseDamage,sparseRemainder,materialGeneration,policyRevision,reservedQ,rollbackProof);
    }
    /** Additional damage after an ACTUAL blocked movement: from reserved Q only. */
    public double blockedQ(DoubleUnaryOperator gameQOfF){
        return Math.min(blockedCandidateQ(gameQOfF),reservedQ);
    }
    public double blockedCandidateQ(DoubleUnaryOperator gameQOfF){
        Objects.requireNonNull(gameQOfF);
        if(status!=Status.CANDIDATE_ONLY)return 0;
        double candidate=gameQOfF.applyAsDouble(sourceF*(1-k));
        if(!Double.isFinite(candidate)||candidate<0)
            throw new IllegalArgumentException("invalid G(F) blocked candidate");
        return candidate;
    }
    /** A plan never calls World/Plot mutation or considers itself applied. */
    public boolean applied(){return false;}
}

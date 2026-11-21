package com.physicaldestroydeform.material;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.collision.ContactCreditLedger;
import com.physicaldestroydeform.collision.DirectionEvidence;
import com.physicaldestroydeform.state.SparseDamageState;
import com.physicaldestroydeform.state.SparseStateCodec;
import com.physicaldestroydeform.state.SparseStateKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;

/** Bounded, diagnostic-only, server-thread shadow state. Not a Minecraft SavedData or ApplyReceipt.
 * Never reads or writes a block; previews are NOT queued for later commit. */
public final class PreviewDamageLedger {
    public static final int MAX_ENTRIES=16_384;
    public static final int MAX_CREDITS=8192;
    public enum ResultStatus { ACCEPTED, DUPLICATE, CAPACITY }
    public record Result(ResultStatus status,MaterialPlanner.Report report,int entries,
                         long fixedBefore,long fixedAfter,int updatedTargets) { }
    private final Map<SparseStateKey,SparseDamageState> states=new HashMap<>();
    private final Set<ContactCreditLedger.CreditId> handled=new HashSet<>();
    private long accepted,duplicates,capacityRejected,roundTrips,roundTripErrors;
    private long fixedTotal;
    private boolean changedSinceRestore;
    private boolean codecCheckPending;

    public Result record(ContactCreditLedger.Grant grant,long generation,long tick,
                         Function<String,MaterialProperties> materials,
                         DamageModel.Settings settings,
                         Function<SparseStateKey,Optional<DirectionEvidence>> directions) {
        return recordTargets(grant,generation,tick,t->materials.apply(t.fingerprint()),settings,directions);
    }
    public Result recordTargets(ContactCreditLedger.Grant grant,long generation,long tick,
                         Function<com.physicaldestroydeform.collision.GameImpactCredit.Target,MaterialProperties> materials,
                         DamageModel.Settings settings,Function<SparseStateKey,Optional<DirectionEvidence>> directions) {
        Objects.requireNonNull(grant);
        if(tick<0)throw new IllegalArgumentException("invalid tick");
        if(handled.contains(grant.id())) {
            duplicates++;
            return new Result(ResultStatus.DUPLICATE,null,states.size(),fixedTotal,fixedTotal,0);
        }
        if(handled.size()>=MAX_CREDITS) {
            capacityRejected++;
            return new Result(ResultStatus.CAPACITY,null,states.size(),fixedTotal,fixedTotal,0);
        }
        var report=MaterialPlanner.planTargets(grant,generation,materials,settings,
                key->{
                    var previous=states.get(key);
                    return previous==null?new BlockDamageState():previous.unpack();
                },directions);
        if(Math.abs(report.accountedQ()-grant.issuedQ())>1e-7)
            throw new IllegalArgumentException("preview Q accounting mismatch");
        long before=fixedTotal;
        Map<SparseStateKey,SparseDamageState> staged=new HashMap<>();
        int newKeys=0;
        for(var target:report.plans()) {
            var key=target.key();
            var old=states.get(key);
            int damage=target.plan().plannedDamageUnits();
            int remainder=target.plan().plannedRemainderU8();
            int deformation=old==null?0:old.deformationU8();
            int crack=old==null?0:old.crackVisualU8();
            if(damage==0&&remainder==0&&deformation==0&&crack==0)continue;
            if(old==null&&!staged.containsKey(key)) {
                if(newKeys==MAX_ENTRIES-states.size()) {
                    capacityRejected++;
                    return new Result(ResultStatus.CAPACITY,report,states.size(),before,before,0);
                }
                newKeys++;
            }
            staged.put(key,new SparseDamageState(key,damage,deformation,crack,remainder,generation,tick));
        }
        for(var next:staged.values()) {
            var old=states.put(next.key(),next);
            if(next.equals(old))continue;
            fixedTotal+=fixedDamage(next)-fixedDamage(old);
            changedSinceRestore=true;
            codecCheckPending=true;
        }
        handled.add(grant.id());
        accepted++;
        return new Result(ResultStatus.ACCEPTED,report,states.size(),before,fixedTotal,staged.size());
    }
    private static long fixedDamage(SparseDamageState state) {
        return state==null?0:state.damageU16()*(long)BlockDamageState.FIXED_SCALE+state.remainderU8();
    }
    public long fixedTotal(){return fixedTotal;}
    public int size() {return states.size();}
    public int handledCredits(){return handled.size();}
    public long accepted(){return accepted;}
    public long duplicates(){return duplicates;}
    public long capacityRejected(){return capacityRejected;}
    public long roundTrips(){return roundTrips;}
    public long roundTripErrors(){return roundTripErrors;}
    public SparseDamageState state(SparseStateKey key){return states.get(key);}
    public List<SparseDamageState> snapshot(){return List.copyOf(states.values());}
    public boolean changedSinceRestore(){return changedSinceRestore;}
    /** Attempt the diagnostic check once per snapshot; a failure remains visible in its counter. */
    public OptionalInt verifyPendingRoundTrip(){
        return codecCheckPending?OptionalInt.of(verifyRoundTrip().length):OptionalInt.empty();
    }
    /** Binary schema+CRC in memory, exactly same bytes as the optional restart checkpoint. */
    public byte[] verifyRoundTrip(){
        codecCheckPending=false;
        try {
            byte[] bytes=SparseStateCodec.encode(snapshot());
            var restored=SparseStateCodec.decode(bytes);
            if(restored.size()!=states.size())throw new IllegalStateException("preview roundtrip count mismatch");
            for(var state:restored)
                if(!state.equals(states.get(state.key())))throw new IllegalStateException("preview roundtrip mismatch");
            bytes[12]^=1; // Probe the temporary buffer and restore it before returning it.
            boolean refused=false;
            try { SparseStateCodec.decode(bytes); }
            catch(IllegalArgumentException expected){refused=true;}
            finally {bytes[12]^=1;}
            if(!refused)throw new IllegalStateException("preview corruption was accepted");
            roundTrips++;
            return bytes;
        }catch(RuntimeException invalid){roundTripErrors++;throw invalid;}
    }
    /** Called before replay only: data is diagnostic, never interpreted as authoritative block damage. */
    public void restore(byte[] bytes) {
        var decoded=SparseStateCodec.decode(bytes);
        if(decoded.size()>MAX_ENTRIES)throw new IllegalArgumentException("preview capacity exceeded");
        Map<SparseStateKey,SparseDamageState> candidate=new HashMap<>();
        long restoredTotal=0;
        for(var state:decoded) {
            if(candidate.putIfAbsent(state.key(),state)!=null)
                throw new IllegalArgumentException("duplicate preview key");
            restoredTotal+=fixedDamage(state);
        }
        clear();
        states.putAll(candidate);
        fixedTotal=restoredTotal;
        codecCheckPending=!states.isEmpty();
    }
    public void clear(){
        states.clear();handled.clear();
        fixedTotal=0;
        changedSinceRestore=codecCheckPending=false;
        accepted=duplicates=capacityRejected=roundTrips=roundTripErrors=0;
    }
}

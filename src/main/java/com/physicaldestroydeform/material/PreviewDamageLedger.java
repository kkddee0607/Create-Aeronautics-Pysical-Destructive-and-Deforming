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

    public Result record(ContactCreditLedger.Grant grant,long generation,long tick,
                         Function<String,MaterialProperties> materials,
                         DamageModel.Settings settings,
                         Function<SparseStateKey,Optional<DirectionEvidence>> directions) {
        Objects.requireNonNull(grant);
        if(tick<0)throw new IllegalArgumentException("invalid tick");
        if(handled.contains(grant.id())) {
            duplicates++;
            return new Result(ResultStatus.DUPLICATE,null,states.size(),fixedTotal(),fixedTotal(),0);
        }
        if(handled.size()>=MAX_CREDITS) {
            capacityRejected++;
            return new Result(ResultStatus.CAPACITY,null,states.size(),fixedTotal(),fixedTotal(),0);
        }
        var report=MaterialPlanner.plan(grant,generation,materials,settings,
                key->states.containsKey(key)?states.get(key).unpack():new BlockDamageState(),directions);
        if(Math.abs(report.accountedQ()-grant.issuedQ())>1e-7)
            throw new IllegalArgumentException("preview Q accounting mismatch");
        long before=fixedTotal();
        Map<SparseStateKey,SparseDamageState> staged=new HashMap<>();
        for(var target:report.plans()) {
            var old=states.get(target.key());
            BlockDamageState next=old==null?new BlockDamageState():old.unpack();
            next.setDamage(target.plan().plannedDamageUnits());
            next.setRemainder(target.plan().plannedRemainderU8());
            if(!next.isPristine())staged.put(target.key(),
                    SparseDamageState.from(target.key(),next,generation,tick));
        }
        long newKeys=staged.keySet().stream().filter(key->!states.containsKey(key)).count();
        if(states.size()+newKeys>MAX_ENTRIES) {
            capacityRejected++;
            return new Result(ResultStatus.CAPACITY,report,states.size(),before,before,0);
        }
        states.putAll(staged);
        handled.add(grant.id());
        accepted++;
        return new Result(ResultStatus.ACCEPTED,report,states.size(),before,fixedTotal(),staged.size());
    }
    public long fixedTotal() {long sum=0;for(var s:states.values())sum+=s.unpack().fixedDamage();return sum;}
    public int size() {return states.size();}
    public int handledCredits(){return handled.size();}
    public long accepted(){return accepted;}
    public long duplicates(){return duplicates;}
    public long capacityRejected(){return capacityRejected;}
    public long roundTrips(){return roundTrips;}
    public long roundTripErrors(){return roundTripErrors;}
    public SparseDamageState state(SparseStateKey key){return states.get(key);}
    public List<SparseDamageState> snapshot(){return List.copyOf(states.values());}
    /** Binary schema+CRC in memory, exactly same bytes as the optional restart checkpoint. */
    public byte[] verifyRoundTrip(){
        try {
            byte[] bytes=SparseStateCodec.encode(snapshot());
            var restored=SparseStateCodec.decode(bytes);
            if(restored.size()!=states.size() || !new HashSet<>(restored).equals(new HashSet<>(states.values())))
                throw new IllegalStateException("preview roundtrip mismatch");
            byte[] damaged=bytes.clone();
            damaged[12]^=1; // deterministic CRC veto probe; never modify the live bytes
            boolean refused=false;
            try { SparseStateCodec.decode(damaged); }
            catch(IllegalArgumentException expected){refused=true;}
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
        for(var state:decoded) if(candidate.putIfAbsent(state.key(),state)!=null)
            throw new IllegalArgumentException("duplicate preview key");
        states.clear();states.putAll(candidate);handled.clear();
        accepted=duplicates=capacityRejected=roundTrips=roundTripErrors=0;
    }
    public void clear(){states.clear();handled.clear();accepted=duplicates=capacityRejected=roundTrips=roundTripErrors=0;}
}

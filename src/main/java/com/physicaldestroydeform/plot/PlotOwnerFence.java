package com.physicaldestroydeform.plot;

import com.physicaldestroydeform.state.SparseStateKey;
import java.util.*;

/** Server-thread only: original Plot owner/epoch cannot silently become a new ship. */
public final class PlotOwnerFence {
    public record Owner(String dimension,UUID uuid,long generation) {
        public Owner {
            Objects.requireNonNull(dimension);Objects.requireNonNull(uuid);
            if(dimension.isBlank()||generation<1)throw new IllegalArgumentException("invalid owner epoch");
        }
        public static Owner of(SparseStateKey key){
            if(!"PLOT".equals(key.ownerSpace()))throw new IllegalArgumentException("Plot key required");
            return new Owner(key.dimension(),UUID.fromString(key.ownerId()),key.ownerGeneration());
        }
    }
    /** Physical object instance, stable Plot grid center and runtime ID; not a world position. */
    public record Stamp(int runtimeId,int centerX,int centerY,int centerZ,Object identity) {
        public Stamp {Objects.requireNonNull(identity);}
        public boolean same(Stamp that) {
            return that!=null && runtimeId==that.runtimeId && centerX==that.centerX
                    && centerY==that.centerY && centerZ==that.centerZ && identity==that.identity;
        }
    }
    private record Identity(String dimension,UUID uuid) {}
    private record Center(int x,int y,int z) {
        Center(Stamp stamp){this(stamp.centerX(),stamp.centerY(),stamp.centerZ());}
    }
    private final Map<Owner,Stamp> bound=new HashMap<>();
    private final Map<Owner,Center> suspended=new HashMap<>();
    private final Map<Identity,Owner> owners=new HashMap<>();
    private final Map<Owner,Long> lifecycleRevision=new HashMap<>();
    private final Set<Owner> orphaned=new HashSet<>();
    private final Set<Owner> pendingOrphaned=new HashSet<>();
    public boolean bind(Owner owner,Stamp actual,boolean splitChild) {
        if(orphaned.contains(owner))return false;
        if(splitChild){orphan(owner);return false;}
        // A UUID reused in a different ledger epoch must never inherit previous state.
        Owner other=owners.putIfAbsent(new Identity(owner.dimension(),owner.uuid()),owner);
        if(other!=null&&!other.equals(owner)){orphan(other);orphan(owner);return false;}
        if(suspended.containsKey(owner))return false; // Only explicit Sable add can resume.
        Stamp previous=bound.putIfAbsent(owner,actual);
        if(previous!=null&&!previous.same(actual)){orphan(owner);return false;}
        return true;
    }
    public boolean verify(Owner owner,Stamp actual,boolean splitChild) {
        return !splitChild && !orphaned.contains(owner) && bound.containsKey(owner)
                && bound.get(owner).same(actual);
    }
    /** Only an actual UNLOADED event matching the last bound object may suspend it. */
    public boolean suspend(Owner owner,Stamp previous){
        if(orphaned.contains(owner))return false;
        if(!verify(owner,previous,false)){orphan(owner);return false;}
        suspended.put(owner,new Center(previous));
        bound.remove(owner); // Do not retain an unloaded Sable object and its chunks.
        advanceLifecycle(owner);
        return true;
    }
    /** Caller must prove the subsequent Sable add and current container identity. */
    public boolean resume(Owner owner,Stamp actual,boolean splitParent){
        if(orphaned.contains(owner))return false;
        Center expected=suspended.get(owner);
        if(expected==null)return false;
        if(splitParent||!expected.equals(new Center(actual))){orphan(owner);return false;}
        suspended.remove(owner);
        bound.put(owner,actual);
        advanceLifecycle(owner);
        return true;
    }
    private void advanceLifecycle(Owner owner){
        lifecycleRevision.merge(owner,1L,Math::addExact);
    }
    public long lifecycleRevision(Owner owner){return lifecycleRevision.getOrDefault(owner,0L);}
    public Owner owner(String dimension,UUID uuid){return owners.get(new Identity(dimension,uuid));}
    public Stamp activeStamp(Owner owner){return bound.get(owner);}
    public boolean suspended(Owner owner){return suspended.containsKey(owner);}
    public void orphan(Owner owner){
        owners.putIfAbsent(new Identity(owner.dimension(),owner.uuid()),owner);
        if(orphaned.add(owner)){advanceLifecycle(owner);pendingOrphaned.add(owner);}
        bound.remove(owner);
        suspended.remove(owner);
    }
    public void orphanAncestors(String dimension,UUID original) {
        Owner owner=owner(dimension,original);
        if(owner!=null)orphan(owner);
    }
    public boolean orphaned(Owner owner){return orphaned.contains(owner);}
    public Set<Owner> orphanedSnapshot(){return Set.copyOf(orphaned);}
    public Set<Owner> drainNewOrphans(){
        Set<Owner> result=Set.copyOf(pendingOrphaned);pendingOrphaned.clear();return result;
    }
    public Set<Owner> boundSnapshot(){
        Set<Owner> result=new HashSet<>(bound.keySet());result.addAll(suspended.keySet());return result;
    }
    public Set<Owner> activeSnapshot(){return Set.copyOf(bound.keySet());}
}

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
    private final Map<Owner,Stamp> bound=new HashMap<>();
    private final Set<Owner> orphaned=new HashSet<>();
    public boolean bind(Owner owner,Stamp actual,boolean splitChild) {
        if(orphaned.contains(owner))return false;
        if(splitChild){orphaned.add(owner);return false;}
        Stamp previous=bound.putIfAbsent(owner,actual);
        if(previous!=null&&!previous.same(actual)){orphaned.add(owner);return false;}
        // A UUID reused in a different ledger epoch must never inherit previous state.
        for(Owner other:bound.keySet()) {
            if(!other.equals(owner) && other.dimension().equals(owner.dimension())
                    && other.uuid().equals(owner.uuid())){
                orphaned.add(other);orphaned.add(owner);return false;
            }
        }
        return true;
    }
    public boolean verify(Owner owner,Stamp actual,boolean splitChild) {
        return !splitChild && !orphaned.contains(owner) && bound.containsKey(owner)
                && bound.get(owner).same(actual);
    }
    public void orphan(Owner owner){orphaned.add(owner);}
    public void orphanAncestors(String dimension,UUID original) {
        for(Owner owner:bound.keySet())
            if(owner.dimension().equals(dimension)&&owner.uuid().equals(original))orphaned.add(owner);
    }
    public boolean orphaned(Owner owner){return orphaned.contains(owner);}
    public Set<Owner> orphanedSnapshot(){return Set.copyOf(orphaned);}
    public Set<Owner> boundSnapshot(){return Set.copyOf(bound.keySet());}
}

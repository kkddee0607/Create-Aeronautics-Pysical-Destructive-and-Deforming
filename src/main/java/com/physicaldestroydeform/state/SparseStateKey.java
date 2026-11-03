package com.physicaldestroydeform.state;

import com.physicaldestroydeform.collision.GameImpactCredit;
import java.util.Objects;
import java.util.UUID;

/** Stable owner-local identity; never use a runtime body ID or Java hash as a storage key. */
public record SparseStateKey(String dimension, String ownerSpace, String ownerId, long ownerGeneration,
                             int x, int y, int z, String fingerprint) implements Comparable<SparseStateKey> {
    public SparseStateKey {
        require("dimension",dimension,256);
        require("ownerId",ownerId,256);
        require("fingerprint",fingerprint,2048);
        if(ownerGeneration<=0)throw new IllegalArgumentException("invalid owner generation");
        if("WORLD".equals(ownerSpace)) {
            if(!ownerId.equals(dimension))throw new IllegalArgumentException("WORLD must use same dimension");
        } else if("PLOT".equals(ownerSpace)) {
            UUID.fromString(ownerId);
        } else throw new IllegalArgumentException("unknown owner space");
    }
    public static SparseStateKey fromTarget(String dimension,GameImpactCredit.Owner owner,
                                            GameImpactCredit.Target target) {
        Objects.requireNonNull(owner);Objects.requireNonNull(target);
        if(!owner.stableKey().equals(target.ownerKey()))throw new IllegalArgumentException("owner/target mismatch");
        String[] coords=target.localBlock().split(",",-1);
        if(coords.length!=3)throw new IllegalArgumentException("invalid owner-local position");
        int index=owner.stableKey().indexOf(':');
        if(index<=0)throw new IllegalArgumentException("invalid owner key");
        return new SparseStateKey(dimension,owner.stableKey().substring(0,index),
                owner.stableKey().substring(index+1),owner.generation(),
                Integer.parseInt(coords[0]),Integer.parseInt(coords[1]),Integer.parseInt(coords[2]),
                target.fingerprint());
    }
    private static void require(String name,String s,int max) {
        if(s==null||s.isBlank()||s.length()>max||s.indexOf('\0')>=0)
            throw new IllegalArgumentException("invalid "+name);
    }
    @Override public int compareTo(SparseStateKey b) {
        int c=dimension.compareTo(b.dimension);if(c!=0)return c;
        c=ownerSpace.compareTo(b.ownerSpace);if(c!=0)return c;
        c=ownerId.compareTo(b.ownerId);if(c!=0)return c;
        c=Long.compare(ownerGeneration,b.ownerGeneration);if(c!=0)return c;
        c=Integer.compare(x,b.x);if(c!=0)return c;
        c=Integer.compare(y,b.y);if(c!=0)return c;
        c=Integer.compare(z,b.z);if(c!=0)return c;
        return fingerprint.compareTo(b.fingerprint);
    }
}

package com.physicaldestroydeform.plot;

import com.physicaldestroydeform.state.SparseStateKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * A separate Plot-only WorldPersistentJournal namespace. The 0.5.0 journal
 * indexes positions by (dimension,x,y,z), not owner UUID: storing original Plot
 * keys there would alias two independent ships at the same relative position.
 * This reversible, domain-separated storage key lets the proven v2 WAL/receipt
 * executor be reused without touching World persistence or its on-disk schema.
 * The virtual WORLD key is NEVER passed to Minecraft or Sable as a location.
 */
public final class PlotKeyCodec {
    private static final String PREFIX="pdd.plot:";
    private PlotKeyCodec(){}
    public static SparseStateKey storage(SparseStateKey real) {
        if(real==null || !"PLOT".equals(real.ownerSpace()))
            throw new IllegalArgumentException("only verified Plot owner-local keys are supported");
        String encoded=Base64.getUrlEncoder().withoutPadding()
                .encodeToString(real.dimension().getBytes(StandardCharsets.UTF_8));
        String domain=PREFIX+encoded+":"+real.ownerId()+":"+real.ownerGeneration();
        return new SparseStateKey(domain,"WORLD",domain,1,real.x(),real.y(),real.z(),real.fingerprint());
    }
    public static SparseStateKey real(SparseStateKey stored) {
        if(stored==null || !"WORLD".equals(stored.ownerSpace())
                || !stored.dimension().equals(stored.ownerId())
                || !stored.dimension().startsWith(PREFIX))
            throw new IllegalArgumentException("not a Plot storage key");
        String[] parts=stored.dimension().substring(PREFIX.length()).split(":",-1);
        if(parts.length!=3)throw new IllegalArgumentException("invalid Plot storage namespace");
        String dim;
        try {dim=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("invalid dimension encoding",invalid);}
        UUID id=UUID.fromString(parts[1]);
        if(!id.toString().equals(parts[1]))throw new IllegalArgumentException("noncanonical plot UUID");
        long generation=Long.parseLong(parts[2]);
        SparseStateKey real=new SparseStateKey(dim,"PLOT",id.toString(),generation,
                stored.x(),stored.y(),stored.z(),stored.fingerprint());
        if(!storage(real).equals(stored))throw new IllegalArgumentException("noncanonical Plot storage key");
        return real;
    }
    public static boolean isPlotStorage(SparseStateKey key) {
        return key!=null && "WORLD".equals(key.ownerSpace())
                && key.dimension().startsWith(PREFIX);
    }
}

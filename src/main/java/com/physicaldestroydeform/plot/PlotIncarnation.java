package com.physicaldestroydeform.plot;

import com.physicaldestroydeform.state.SparseStateKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Persistently comparable block/Plot incarnation: UUID, epoch and center stay owner-local.
 * Runtime body integer and object reference are intentionally NOT persisted.
 */
public final class PlotIncarnation {
    private PlotIncarnation(){}
    public static String token(SparseStateKey real,int centerX,int centerY,int centerZ,long revision){
        if(real==null||!"PLOT".equals(real.ownerSpace())||revision<0)
            throw new IllegalArgumentException("invalid physical Plot incarnation");
        try {
            MessageDigest sha=MessageDigest.getInstance("SHA-256");
            String identity="PDD:PLOT:0.5.1|"+real.dimension()+"|"+real.ownerId()+"|"
                    +real.ownerGeneration()+"|"+real.x()+","+real.y()+","+real.z()
                    +"|CENTER:"+centerX+","+centerY+","+centerZ
                    +"|FP:"+real.fingerprint();
            return revision+"-"+HexFormat.of().formatHex(sha.digest(identity.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}

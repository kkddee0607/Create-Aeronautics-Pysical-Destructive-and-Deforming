package com.physicaldestroydeform.collision;

import net.minecraft.server.level.ServerLevel;
import java.util.HashSet;
import java.util.List;

/** Actual server/level instance and runtime tick, distinct from native game time.
 * Instance identity is process-local and never written to disk. */
public record ContactBatchStamp(Object serverIdentity,Object levelIdentity,String dimension,
                                long captureServerTick,long sourceGameTick) {
    public static final ContactBatchStamp UNKNOWN=new ContactBatchStamp(null,null,null,-1,-1);
    public enum Status { CURRENT, UNKNOWN_STAMP, SERVER_MISMATCH, DIMENSION_MISMATCH,
        LEVEL_MISMATCH, SERVER_TICK_MISMATCH, NATIVE_MISSING, NATIVE_CONTRADICTORY,
        PRE_MISSING, PRE_DUPLICATE, PRE_CONTRADICTORY }

    public static ContactBatchStamp capture(ServerLevel level) {
        if(level==null||level.getServer()==null)return UNKNOWN;
        return new ContactBatchStamp(level.getServer(),level,level.dimension().location().toString(),
                level.getServer().getTickCount(),level.getGameTime());
    }
    public boolean known(){return serverIdentity!=null&&levelIdentity!=null&&dimension!=null
            &&!dimension.isBlank()&&captureServerTick>=0&&sourceGameTick>=0;}
    /** Runtime freshness is not a restriction on dimension game-time advancement. */
    public Status statusAgainst(ContactBatchStamp live){
        if(!known()||live==null||!live.known())return Status.UNKNOWN_STAMP;
        if(serverIdentity!=live.serverIdentity)return Status.SERVER_MISMATCH;
        if(!dimension.equals(live.dimension))return Status.DIMENSION_MISMATCH;
        if(levelIdentity!=live.levelIdentity)return Status.LEVEL_MISMATCH;
        if(captureServerTick!=live.captureServerTick)return Status.SERVER_TICK_MISMATCH;
        return Status.CURRENT;
    }
    /** Native effects and PRE frames require exact source game-time equality too. */
    public boolean sameCapture(ContactBatchStamp other){
        return statusAgainst(other)==Status.CURRENT&&sourceGameTick==other.sourceGameTick;
    }
    public static Status nativeStatus(ContactBatchStamp source,List<ContactBatchStamp> stamps){
        if(source==null||!source.known())return Status.UNKNOWN_STAMP;
        if(stamps==null||stamps.isEmpty())return Status.NATIVE_MISSING;
        for(var stamp:stamps)if(!source.sameCapture(stamp))return Status.NATIVE_CONTRADICTORY;
        return Status.CURRENT;
    }
    public static Status preStatus(ContactBatchStamp source,List<CollisionFrame.Snapshot> frames){
        if(source==null||!source.known())return Status.UNKNOWN_STAMP;
        if(frames==null)return Status.PRE_MISSING;
        var epochs=new HashSet<Long>();
        for(var frame:frames){
            if(frame==null||frame.phase()!=CollisionFrame.Phase.PRE_SOLVER||!source.dimension.equals(frame.dimension()))continue;
            if(!source.sameCapture(frame.runtimeStamp())||source.sourceGameTick!=frame.serverTick()||frame.epoch()<0)
                return Status.PRE_CONTRADICTORY;
            if(!epochs.add(frame.epoch()))return Status.PRE_DUPLICATE;
        }
        return epochs.isEmpty()?Status.PRE_MISSING:Status.CURRENT;
    }
}

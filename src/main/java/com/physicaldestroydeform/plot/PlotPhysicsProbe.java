package com.physicaldestroydeform.plot;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Read-only Sable lifecycle evidence; never recalculate mass or fabricate a native COM. */
public final class PlotPhysicsProbe {
    private static final Logger LOG=LoggerFactory.getLogger(PlotPhysicsProbe.class);
    private static final int MAX_WATCHES=128,MAX_BOOT_BODIES=32;
    private static volatile String SESSION_ID=UUID.randomUUID().toString();
    private static final Map<String,Watch> WATCHES=new ConcurrentHashMap<>();
    private static final Set<String> BOOT_SAMPLED=ConcurrentHashMap.newKeySet();
    private record Watch(String dimension,UUID owner,int bodyId,String target) { }
    private PlotPhysicsProbe() { }
    private static String key(String dimension,int bodyId){return dimension+"#"+bodyId;}
    private static String vector(Vector3dc value){
        return value==null?"NULL":value.x()+","+value.y()+","+value.z();
    }
    private static boolean finite(Vector3dc v){
        return v!=null && Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z());
    }
    private static void snapshot(String phase,String dim,ServerSubLevel sub,String target){
        try {
            MassData merged=sub.getMassTracker();
            MassData self=sub.getSelfMassTracker();
            Vector3dc selfCom=self==null?null:self.getCenterOfMass();
            Vector3dc mergedCom=merged==null?null:merged.getCenterOfMass();
            double selfMass=self==null?Double.NaN:self.getMass();
            double mergedMass=merged==null?Double.NaN:merged.getMass();
            boolean valid=Double.isFinite(selfMass)&&selfMass>0&&finite(selfCom)
                    &&Double.isFinite(mergedMass)&&mergedMass>0&&finite(mergedCom);
            // Successful read proves only Java-side mass data, not a native Rapier upload.
            if(!valid || Boolean.getBoolean("pdd.tracePhysicsLifecycle"))
                LOG.warn("[PDD-PHYSICS-LIFECYCLE] sessionId={} phase={} dim={} owner={} runtimeBodyId={} " +
                        "removed={} target={} selfMass={} mergedMass={} selfCOM={} mergedCOM={} " +
                        "javaMassValid={} nativeComUpload=UNVERIFIED colliderSync=UNVERIFIED",
                        SESSION_ID,phase,dim,sub.getUniqueId(),sub.getRuntimeId(),sub.isRemoved(),target,
                        selfMass,mergedMass,vector(selfCom),vector(mergedCom),valid);
        }catch(RuntimeException|LinkageError unavailable){
            LOG.warn("[PDD-PHYSICS-LIFECYCLE] sessionId={} phase={} dim={} runtimeBodyId={} " +
                    "target={} status=READ_FAILED exception={}",SESSION_ID,phase,dim,sub.getRuntimeId(),
                    target,unavailable.getClass().getSimpleName());
        }
    }
    public static void beforeBreak(ServerSubLevel sub,String dimension,String target){
        if(WATCHES.size()<MAX_WATCHES)
            WATCHES.put(key(dimension,sub.getRuntimeId()),new Watch(dimension,sub.getUniqueId(),
                    sub.getRuntimeId(),target));
        snapshot("BEFORE_BREAK",dimension,sub,target);
    }
    public static void afterBreak(ServerSubLevel sub,String dimension,String target,boolean apiResult){
        snapshot(apiResult?"AFTER_BREAK_API_TRUE":"AFTER_BREAK_API_FALSE",dimension,sub,target);
    }
    /** HEAD of Sable prePhysicsTicks: reads existing Java mass only, before native tick. */
    public static void beforeNativeTick(ServerLevel level,Int2ObjectMap<ServerSubLevel> active){
        String dim=level.dimension().location().toString();
        if(!BOOT_SAMPLED.contains(dim)&&!active.isEmpty()){
            BOOT_SAMPLED.add(dim);
            int sampled=0;
            for(ServerSubLevel sub:active.values()) {
                if(sub!=null&&!sub.isRemoved())snapshot("FIRST_ACTIVE_PRE_NATIVE_TICK",dim,sub,"BOOT");
                if(++sampled>=MAX_BOOT_BODIES)break;
            }
            LOG.info("[PDD-PHYSICS-LIFECYCLE] sessionId={} phase=BOOT_AUDIT dim={} sampled={} " +
                    "nativeComUpload=UNVERIFIED",SESSION_ID,dim,sampled);
        }
        if(WATCHES.isEmpty())return;
        for(var entry:WATCHES.entrySet()){
            Watch w=entry.getValue();
            if(!w.dimension().equals(dim)||!WATCHES.remove(entry.getKey(),w))continue;
            ServerSubLevel sub=active.get(w.bodyId());
            if(sub==null || sub.isRemoved() || !w.owner().equals(sub.getUniqueId())){
                LOG.warn("[PDD-PHYSICS-LIFECYCLE] sessionId={} phase=PRE_NATIVE_TICK dim={} owner={} " +
                        "runtimeBodyId={} target={} state=REMOVED_OR_REPLACED nativeComUpload=UNVERIFIED",
                        SESSION_ID,dim,w.owner(),w.bodyId(),w.target());
            }else snapshot("PRE_NATIVE_TICK",dim,sub,w.target());
        }
    }
    public static String sessionId(){return SESSION_ID;}
    public static void clear(){WATCHES.clear();BOOT_SAMPLED.clear();SESSION_ID=UUID.randomUUID().toString();}
}

package com.physicaldestroydeform.material;

import com.physicaldestroydeform.collision.GameImpactCredit;
import com.physicaldestroydeform.plot.PlotKeyCodec;
import com.physicaldestroydeform.state.SparseStateKey;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.LoggerFactory;
import java.lang.ref.WeakReference;
import java.util.*;

/** Per-credit immutable-on-first-read material cache; no state retained across events. */
public final class TargetMaterials {
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger(TargetMaterials.class);
    private static final boolean TRACE=Boolean.parseBoolean(System.getProperty("pdd.copycat.trace","true"));
    private static final int TRACE_CAP=Math.max(0,Math.min(128,Integer.getInteger("pdd.copycat.trace.maxPerTick",16)));
    private static WeakReference<MinecraftServer> traceServer=new WeakReference<>(null);
    private static long traceTick=-1;private static int traces;
    private final MinecraftServer server;private final String dimension;private final MaterialRegistry.Snapshot snapshot;
    private final Map<GameImpactCredit.Target,Optional<CopycatMaterial.Resolved>> cache=new HashMap<>();
    private com.physicaldestroydeform.collision.TargetDirections directions;
    public void setDirections(com.physicaldestroydeform.collision.TargetDirections value){directions=value;}
    public com.physicaldestroydeform.deform.MovePlan.Evidence direction(GameImpactCredit.Target target){return directions==null?null:directions.evidence(target);}
    public TargetMaterials(MinecraftServer server,String dimension,MaterialRegistry.Snapshot snapshot){this.server=server;this.dimension=dimension;this.snapshot=snapshot;}
    public CopycatMaterial.Resolved resolve(GameImpactCredit.Target target){
        return cache.computeIfAbsent(target,t->Optional.ofNullable(read(server,dimension,t.ownerKey(),t.localBlock(),t.fingerprint(),snapshot))).orElse(null);
    }
    public com.physicaldestroydeform.MaterialProperties material(GameImpactCredit.Target target){var m=resolve(target);return m==null?null:m.material();}
    public static CopycatMaterial.Resolved read(MinecraftServer server,SparseStateKey key,MaterialRegistry.Snapshot snapshot){
        if(PlotKeyCodec.isPlotStorage(key))key=PlotKeyCodec.real(key);
        return read(server,key.dimension(),key.ownerSpace()+":"+key.ownerId(),key.x()+","+key.y()+","+key.z(),key.fingerprint(),snapshot);
    }
    private static CopycatMaterial.Resolved read(MinecraftServer server,String dimension,String owner,String local,String fp,MaterialRegistry.Snapshot snapshot){
        if(!CopycatMaterial.isCopycat(fp)){
            var material=snapshot.resolveFingerprint(fp);return material==null?null:new CopycatMaterial.Resolved(material,"",false);
        }
        if(!server.isSameThread())return null;
        try{
            var xyz=local.split(",",-1);if(xyz.length!=3)return reject(server,dimension,owner,local,fp,snapshot,"INVALID_POSITION");
            BlockPos pos=new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]));
            for(var level:server.getAllLevels())if(level.dimension().location().toString().equals(dimension)){
                var container=SubLevelContainer.getContainer(level);if(container==null)return reject(server,dimension,owner,local,fp,snapshot,"OWNER_CONTAINER_MISSING");
                if(owner.equals("WORLD:"+dimension)){
                    ChunkPos chunk=new ChunkPos(pos);
                    if(container.inBounds(chunk)||container.getPlot(chunk)!=null||!level.isInWorldBounds(pos)||level.getChunkSource().getChunkNow(chunk.x,chunk.z)==null)return reject(server,dimension,owner,local,fp,snapshot,"OWNER_OR_CHUNK_UNAVAILABLE");
                    return capture(server,dimension,owner,local,fp,snapshot,level,pos,level.getBlockState(pos));
                }
                if(!owner.startsWith("PLOT:"))return reject(server,dimension,owner,local,fp,snapshot,"OWNER_MISMATCH");
                var id=UUID.fromString(owner.substring(5));var raw=container.getSubLevel(id);
                if(!(raw instanceof ServerSubLevel sub)||sub.isRemoved()||sub.getLevel()!=level||!id.equals(sub.getUniqueId())||sub.getPlot()==null)return reject(server,dimension,owner,local,fp,snapshot,"PLOT_REMOVED_OR_UNAVAILABLE");
                var plot=sub.getPlot();var center=plot.getCenterBlock();
                var allocated=new BlockPos(Math.addExact(pos.getX(),center.getX()),Math.addExact(pos.getY(),center.getY()),Math.addExact(pos.getZ(),center.getZ()));
                var chunk=new ChunkPos(allocated);var accessor=plot.getEmbeddedLevelAccessor();
                if(container.getPlot(chunk)!=plot||accessor==null||!accessor.hasChunk(chunk.x-plot.getCenterChunk().x,chunk.z-plot.getCenterChunk().z))return reject(server,dimension,owner,local,fp,snapshot,"PLOT_OWNER_OR_CHUNK_UNAVAILABLE");
                return capture(server,dimension,owner,local,fp,snapshot,accessor,pos,accessor.getBlockState(pos));
            }
        }catch(RuntimeException|LinkageError invalid){return reject(server,dimension,owner,local,fp,snapshot,"OWNER_"+invalid.getClass().getSimpleName());}
        return reject(server,dimension,owner,local,fp,snapshot,"DIMENSION_MISSING");
    }
    private static CopycatMaterial.Resolved capture(MinecraftServer server,String dimension,String owner,String local,String fp,MaterialRegistry.Snapshot snapshot,BlockGetter level,BlockPos pos,BlockState state){
        if(state==null||!fp.equals(CopycatMaterial.fingerprint(state)))return reject(server,dimension,owner,local,fp,snapshot,"STATE_MISMATCH");
        var capture=CopycatMaterial.captureDetailed(snapshot,level,pos,state);
        trace(server,dimension,owner,local,fp,snapshot,capture);
        return capture.resolved();
    }
    private static CopycatMaterial.Resolved reject(MinecraftServer server,String dimension,String owner,String local,String fp,MaterialRegistry.Snapshot snapshot,String reason){
        trace(server,dimension,owner,local,fp,snapshot,new CopycatMaterial.Capture(null,reason,"",0));return null;
    }
    private static void trace(MinecraftServer server,String dimension,String owner,String local,String fp,MaterialRegistry.Snapshot snapshot,CopycatMaterial.Capture capture){
        if(!TRACE||TRACE_CAP==0||!LOG.isInfoEnabled())return;
        long tick=server.getTickCount();
        if(traceServer.get()!=server||tick!=traceTick){traceServer=new WeakReference<>(server);traceTick=tick;traces=0;}
        if(traces>=TRACE_CAP)return;traces++;
        var r=capture.resolved();var m=r==null?null:r.material();
        LOG.info("[PDD-COPYCAT] phase=SNAPSHOT tick={} dimension={} owner={} local={} state={} generation={} status={} reason={} slots={} k={} H={} T={} D={} E={} revision={} safeDecorativeBE={}",
                tick,dimension,owner,local,fp,snapshot.generation(),r==null?"REJECT":"RESOLVED",capture.reason(),capture.slots(),capture.k(),
                m==null?null:m.hardness(),m==null?null:m.threshold(),m==null?null:m.ductilityPercent(),m==null?null:m.elasticityPercent(),r==null?"":r.revision(),r!=null&&r.safeDecorativeBE());
    }
}

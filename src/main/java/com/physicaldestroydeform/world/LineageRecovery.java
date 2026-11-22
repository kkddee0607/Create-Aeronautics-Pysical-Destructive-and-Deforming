package com.physicaldestroydeform.world;

import com.physicaldestroydeform.material.CopycatMaterial;
import com.physicaldestroydeform.plot.PlotCommitService;
import com.physicaldestroydeform.plot.PlotIncarnation;
import com.physicaldestroydeform.plot.PlotKeyCodec;
import com.physicaldestroydeform.plot.PlotOwnerFence;
import com.physicaldestroydeform.state.SparseStateKey;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.io.IOException;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Startup-only collection and bounded loaded-only resumption of authenticated
 * native poststates. Never runs Sable's setters, never replays V1 quarantine. */
public final class LineageRecovery {
    private static final Logger LOG=LoggerFactory.getLogger(LineageRecovery.class);
    private static final int BATCH=8;
    static final long MAX_AGE=WorldCommitCore.MAX_DEFER_TICKS;
    private static WorldCommitService world;
    private static PlotCommitService plot;
    private static final Deque<Entry> pending=new ArrayDeque<>();
    private record Entry(LineageJournal.Sealed sealed,WorldPersistentJournal source,WorldPersistentJournal destination,int attempts){}
    private record Cell(String dim,int x,int y,int z){Cell(SparseStateKey k){this(k.dimension(),k.x(),k.y(),k.z());}}
    private record Leg(WorldPersistentJournal journal,WorldReceiptJournal.Receipt receipt){}
    private record Live(BlockGetter reader,BlockPos local,String incarnation){}
    private LineageRecovery(){}
    public static void clear(){pending.clear();world=null;plot=null;}
    public static void start(WorldCommitService w,PlotCommitService p,MinecraftServer server){
        clear();world=w;plot=p;
        if(w==null||p==null||!server.isSameThread())return;
        var a=w.lineageJournal();var b=p.lineageJournal();
        long now=server.overworld().getGameTime();
        try{
            Set<String> ids=new HashSet<>();
            for(var journal:List.of(a,b))for(var r:journal.uncertain())
                if(LineageJournal.isProofMarker(r))ids.add(r.id().substring(0,r.id().lastIndexOf(':')));
            List<Entry> entries=new ArrayList<>();
            Map<Cell,Integer> counts=new HashMap<>();
            for(String id:ids){
                var outs=legs(a,b,id+":OUT");var ins=legs(a,b,id+":IN");
                if(outs.size()!=1||ins.size()!=1){
                    for(var leg:outs)reject(leg,now);for(var leg:ins)reject(leg,now);
                    LOG.warn("[PDD-TRANSFER] phase=RECOVERY id={} result=QUARANTINED reason=PAIR_MISSING_OR_DUPLICATE",id);
                    continue;
                }
                var out=outs.getFirst();var in=ins.getFirst();
                var sealed=LineageJournal.decode(out.receipt(),in.receipt());
                if(sealed==null||out.journal()!=journal(out.receipt().key())||in.journal()!=journal(in.receipt().key())){
                    reject(out,now);reject(in,now);
                    LOG.warn("[PDD-TRANSFER] phase=RECOVERY id={} result=QUARANTINED reason=PAIR_INVALID",id);
                    continue;
                }
                var e=new Entry(sealed,out.journal(),in.journal(),0);entries.add(e);
                var t=sealed.transfer();
                watch(t.source().key());watch(t.destination());
                counts.merge(new Cell(t.source().key()),1,Integer::sum);
                counts.merge(new Cell(t.destination()),1,Integer::sum);
            }
            for(var e:entries){
                var t=e.sealed().transfer();
                if(counts.get(new Cell(t.source().key()))!=1||counts.get(new Cell(t.destination()))!=1)
                    quarantine(e,now,"RECOVERY_CELL_ALIAS");
                else pending.addLast(e);
            }
            LOG.info("[PDD-TRANSFER] phase=RECOVERY_START proofPairs={}",pending.size());
            tick(server);
        }catch(IOException|RuntimeException|LinkageError failure){halt(failure);}
    }
    private static List<Leg> legs(WorldPersistentJournal a,WorldPersistentJournal b,String id){
        var result=new ArrayList<Leg>(2);
        for(var j:List.of(a,b)){var r=j.receipt(id);if(r!=null)result.add(new Leg(j,r));}
        return result;
    }
    private static WorldPersistentJournal journal(SparseStateKey key){
        return PlotKeyCodec.isPlotStorage(key)?plot.lineageJournal():world.lineageJournal();
    }
    private static void watch(SparseStateKey key){
        if(PlotKeyCodec.isPlotStorage(key))plot.watchRecoveredLineage(key);else world.watchRecoveredLineage(key);
    }
    private static void release(SparseStateKey key,String id,boolean success){
        if(PlotKeyCodec.isPlotStorage(key))plot.finishLineage(key,id,success);else world.finishLineage(key,id,success);
    }
    private static boolean conflict(SparseStateKey key){
        return PlotKeyCodec.isPlotStorage(key)?plot.lineageRecoveryConflict(key):world.lineageRecoveryConflict(key);
    }
    private static void reject(Leg leg,long tick)throws IOException{
        var r=leg.receipt();
        LineageJournal.quarantine(leg.journal(),r.id(),tick);
        if(leg.journal()==journal(r.key()))release(r.key(),r.id(),false);
    }
    private static void quarantine(Entry e,long tick,String reason)throws IOException{
        var t=e.sealed().transfer();
        LineageJournal.quarantine(e.source(),t.outId(),tick);
        LineageJournal.quarantine(e.destination(),t.inId(),tick);
        release(t.source().key(),t.outId(),false);release(t.destination(),t.inId(),false);
        LOG.warn("[PDD-TRANSFER] phase=RECOVERY id={} result=QUARANTINED reason={}",t.id(),reason);
    }
    private static void halt(Throwable failure){
        var cause=new IOException("LINEAGE_RECOVERY_HALTED",failure);
        if(world!=null)world.lineageFault(cause);
        if(plot!=null)plot.lineageFault(cause);
        clear();
    }
    static boolean expired(long now,long proofTick){return now<proofTick||now-proofTick>MAX_AGE;}
    public static void tick(MinecraftServer server){
        if(world==null||plot==null||pending.isEmpty()||!server.isSameThread())return;
        if(world.lineageFaulted()||plot.faulted()){clear();return;}
        long now=server.overworld().getGameTime();
        long started=System.nanoTime();
        for(int i=0,n=Math.min(BATCH,pending.size());i<n;i++){
            if(i>0&&System.nanoTime()-started>=2_000_000L)break;
            var e=pending.removeFirst();var s=e.sealed();var t=s.transfer();
            try{
                if(expired(now,s.proofTick())||e.attempts()>=MAX_AGE){quarantine(e,now,"RECOVERY_EXPIRED_OR_CLOCK_ROLLBACK");continue;}
                if(conflict(t.source().key())||conflict(t.destination())){quarantine(e,now,"RECOVERY_INDEPENDENT_FENCE");continue;}
                // Fast CAS check catches a replacement even when unloaded.
                e.source().checkLineageSource(t);e.destination().checkLineageDestination(t);
                var proof=probe(server,s);
                if(proof==null){pending.addLast(new Entry(s,e.source(),e.destination(),e.attempts()+1));continue;}
                LineageJournal.resume(s,e.source(),e.destination(),proof,now);
                release(t.source().key(),t.outId(),true);release(t.destination(),t.inId(),true);
                LOG.info("[PDD-TRANSFER] phase=RECOVERY id={} result=APPLIED damage={} remainder={} deformation={}",
                        t.id(),t.source().damage(),t.source().remainder(),t.source().deformation());
            }catch(IOException failure){
                if(failure.getMessage()==null||!failure.getMessage().startsWith("LINEAGE_")){halt(failure);return;}
                try{quarantine(e,now,failure.getMessage());}
                catch(IOException|RuntimeException unsafe){halt(unsafe);return;}
            }catch(RuntimeException|LinkageError failure){halt(failure);return;}
        }
    }
    private static SparseStateKey real(SparseStateKey key){return PlotKeyCodec.isPlotStorage(key)?PlotKeyCodec.real(key):key;}
    private static BlockPos pos(SparseStateKey k){return new BlockPos(k.x(),k.y(),k.z());}
    private static ServerLevel level(MinecraftServer server,String dim){
        for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(dim))return l;
        return null;
    }
    /** A null return is a load miss, not proof of absence. */
    private static Live read(ServerLevel level,SubLevelContainer container,SparseStateKey k,long revision)throws IOException{
        BlockPos local=pos(k);
        if("WORLD".equals(k.ownerSpace())){
            var chunk=new ChunkPos(local);
            if(container.inBounds(chunk)||container.getPlot(chunk)!=null||!level.isInWorldBounds(local))
                throw new IOException("LINEAGE_RECOVERY_WORLD_OWNER_CHANGED");
            if(level.getChunkSource().getChunkNow(chunk.x,chunk.z)==null)return null;
            return new Live(level,local,revision+"-"+LineageJournal.digest(k.fingerprint()));
        }
        var sub=container.getSubLevel(UUID.fromString(k.ownerId()));
        if(!(sub instanceof ServerSubLevel owner)||owner.isRemoved())return null;
        if(!PlotOwnerFence.Owner.of(k).equals(plot.resolveLineageOwner(owner)))return null;
        var nativePlot=owner.getPlot();var center=nativePlot.getCenterBlock();
        var allocated=local.offset(center);var chunk=new ChunkPos(allocated);
        if(container.getPlot(chunk)!=nativePlot)throw new IOException("LINEAGE_RECOVERY_PLOT_CELL_CHANGED");
        var reader=nativePlot.getEmbeddedLevelAccessor();
        if(reader==null||!reader.hasChunk(chunk.x-nativePlot.getCenterChunk().x,chunk.z-nativePlot.getCenterChunk().z))return null;
        return new Live(reader,local,PlotIncarnation.token(k,center.getX(),center.getY(),center.getZ(),revision));
    }
    private static LineageJournal.RecoveryProof probe(MinecraftServer server,LineageJournal.Sealed s)throws IOException{
        var t=s.transfer();var old=real(t.source().key());var target=real(t.destination());
        var level=level(server,old.dimension());if(level==null)return null;
        var container=SubLevelContainer.getContainer(level);if(container==null)return null;
        String sourceToken;
        var before=read(level,container,old,t.fromRevision());
        if(before==null){
            if(!s.sourceRemoved()||!"PLOT".equals(old.ownerSpace())
                    ||container.getSubLevel(UUID.fromString(old.ownerId()))!=null)return null;
            var center=new BlockPos(s.sourceCenterX(),s.sourceCenterY(),s.sourceCenterZ());
            if(!NativeLineageTransfer.emptyPlotSlot(container,center))return null;
            sourceToken=PlotIncarnation.token(old,center.getX(),center.getY(),center.getZ(),t.fromRevision());
        }else{
            if(!before.reader().getBlockState(before.local()).isAir()||before.reader().getBlockEntity(before.local())!=null)
                throw new IOException("LINEAGE_RECOVERY_SOURCE_NOT_CLEARED");
            sourceToken=before.incarnation();
        }
        var next=read(level,container,target,LineageJournal.revision(journal(t.destination()),t.destination()));
        if(next==null)return null;
        var state=next.reader().getBlockState(next.local());
        if(!target.fingerprint().equals(CopycatMaterial.fingerprint(state)))
            throw new IOException("LINEAGE_RECOVERY_DESTINATION_FINGERPRINT");
        String digest=NativeLineageTransfer.payloadDigest(NativeLineageTransfer.nbt(next.reader(),next.local(),level,state));
        return new LineageJournal.RecoveryProof(true,true,sourceToken,next.incarnation(),digest);
    }
}

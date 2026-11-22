package com.physicaldestroydeform.world;

import com.physicaldestroydeform.material.CopycatMaterial;
import com.physicaldestroydeform.plot.PlotCommitService;
import com.physicaldestroydeform.plot.PlotKeyCodec;
import com.physicaldestroydeform.plot.PlotOwnerFence;
import com.physicaldestroydeform.state.SparseStateKey;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.io.IOException;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Observes Sable 2.0.6 moveBlocks, never moves a Minecraft block itself. */
public final class NativeLineageTransfer {
    private static final Logger LOG=LoggerFactory.getLogger(NativeLineageTransfer.class);
    public static final int DEFAULT_CAP=4096;
    private static final ThreadLocal<Deque<Context>> ACTIVE=ThreadLocal.withInitial(ArrayDeque::new);
    private static Context active(){return ACTIVE.get().peek();}
    private NativeLineageTransfer(){}
    private record Physical(ServerLevel level,BlockPos allocated){}
    private record OwnerPair(ServerSubLevel from,ServerSubLevel to){}
    private record Location(Physical physical,ServerSubLevel plot,PlotOwnerFence.Owner owner,
                            BlockPos local,BlockPos center){
        SparseStateKey key(BlockState state){
            String dim=physical.level().dimension().location().toString();
            String fp=CopycatMaterial.fingerprint(state);
            if(plot==null)return new SparseStateKey(dim,"WORLD",dim,1,local.getX(),local.getY(),local.getZ(),fp);
            return PlotKeyCodec.storage(new SparseStateKey(dim,"PLOT",owner.uuid().toString(),owner.generation(),
                    local.getX(),local.getY(),local.getZ(),fp));
        }
        BlockGetter loaded(){
            var level=physical.level();var chunk=new ChunkPos(physical.allocated());
            var container=SubLevelContainer.getContainer(level);
            if(container==null)return null;
            if(plot==null)return !container.inBounds(chunk)&&container.getPlot(chunk)==null
                    &&level.isInWorldBounds(local)&&level.getChunkSource().getChunkNow(chunk.x,chunk.z)!=null?level:null;
            if(plot.isRemoved()||container.getSubLevel(owner.uuid())!=plot||container.getPlot(chunk)!=plot.getPlot()
                    ||!center.equals(plot.getPlot().getCenterBlock()))return null;
            var accessor=plot.getPlot().getEmbeddedLevelAccessor();
            return accessor!=null&&accessor.hasChunk(chunk.x-plot.getPlot().getCenterChunk().x,
                    chunk.z-plot.getPlot().getCenterChunk().z)?accessor:null;
        }
    }
    private static final class Witness {
        final Location from,to;
        final BlockState oldState,newState;
        CompoundTag nbt;
        boolean payloadCaptured;
        final LineageJournal.Transfer transfer;
        boolean sourceCleared,destinationWritten,invalid;
        Witness(Location from,Location to,BlockState oldState,BlockState newState,boolean expectsBE,LineageJournal.Transfer t){
            this.from=from;this.to=to;this.oldState=oldState;this.newState=newState;this.payloadCaptured=!expectsBE;this.transfer=t;
        }
        void source(BlockState before,BlockState after){
            if(sourceCleared||!oldState.equals(before)||!after.isAir())invalid=true;
            else sourceCleared=true;
        }
        void destination(BlockState before,BlockState after){
            if(destinationWritten||!before.isAir()||!newState.equals(after))invalid=true;
            else destinationWritten=true;
        }
        boolean proved(){
            if(invalid||!sourceCleared||!destinationWritten)return false;
            BlockGetter old=from.loaded(),restored=to.loaded();
            if(restored==null||!newState.equals(restored.getBlockState(to.local())))return false;
            if(old==null){
                if(from.plot()==null||!from.plot().isRemoved())return false;
            }else if(!old.getBlockState(from.local()).isAir())return false;
            if(transfer==null)return true; // topology sample, not a damage transfer
            return payloadCaptured&&Objects.equals(nbt,nbt(restored,to.local(),to.physical().level(),newState));
        }
    }
    private static final class Context {
        final WorldCommitService world;final PlotCommitService plot;final long tick;
        final ServerLevel level;final int cap;final AssemblyTransform transform;final Iterable<BlockPos> blocks;
        BlockPos pendingFrom,pendingTo;
        Witness pendingWitness;
        final Map<ServerSubLevel,PlotOwnerFence.Owner> owners=new IdentityHashMap<>();
        final Map<Physical,Witness> sources=new HashMap<>(),destinations=new HashMap<>();
        final Set<OwnerPair> sampled=new HashSet<>();
        int prepared,overCap,unresolved;boolean unsafe,disabled;
        Context(WorldCommitService world,PlotCommitService plot,ServerLevel level,int cap,AssemblyTransform transform,Iterable<BlockPos> blocks){
            this.world=world;this.plot=plot;this.level=level;this.tick=level.getServer().getTickCount();this.cap=cap;
            this.transform=transform;this.blocks=blocks;
        }
        boolean matches(ServerLevel level,AssemblyTransform transform,Iterable<BlockPos> blocks){
            return this.level==level&&this.transform==transform&&this.blocks==blocks;
        }
        WorldPersistentJournal journal(Location l){return l.plot()==null?world.lineageJournal():plot.lineageJournal();}
        boolean watch(Location l,SparseStateKey k){return l.plot()==null?world.watchLineage(k):plot.watchLineage(k);}
        void release(Location l,SparseStateKey k,String marker,boolean success){
            if(l.plot()==null)world.finishLineage(k,marker,success);else plot.finishLineage(k,marker,success);
        }
        String incarnation(Location l,SparseStateKey k){
            return l.plot()==null?world.incarnation(k.dimension(),l.local(),k.fingerprint()):plot.incarnation(PlotKeyCodec.real(k),l.center());
        }
        void fault(Exception cause){unsafe=true;world.lineageFault(cause);plot.lineageFault(cause);}
        void quarantine(Location source,WorldReceiptJournal.State before,String reason){
            if(before==null)return;
            unresolved++;
            try{
                String marker=LineageJournal.quarantineSource(journal(source),before,tick,reason);
                if(source.plot()==null)world.recordLineageQuarantine(marker);else plot.recordLineageQuarantine(marker);
            }catch(IOException|RuntimeException unsafe){fault(unsafe);}
        }
        Location locate(ServerLevel level,BlockPos allocated){
            var container=SubLevelContainer.getContainer(level);
            if(container==null)return null;
            var chunk=new ChunkPos(allocated);
            var nativePlot=container.getPlot(chunk);
            var physical=new Physical(level,allocated.immutable());
            if(nativePlot==null)return container.inBounds(chunk)?null:new Location(physical,null,null,allocated.immutable(),null);
            if(!(nativePlot.getSubLevel() instanceof ServerSubLevel sub)||sub.isRemoved())return null;
            var owner=owners.computeIfAbsent(sub,plot::resolveLineageOwner);
            if(owner==null)return null;
            var center=nativePlot.getCenterBlock();
            return new Location(physical,sub,owner,allocated.subtract(center),center.immutable());
        }
    }
    private static CompoundTag nbt(BlockGetter reader,BlockPos local,ServerLevel level,BlockState state){
        var be=reader.getBlockEntity(local);
        if(be==null){
            if(state.hasBlockEntity())throw new IllegalStateException("LINEAGE_BE_UNAVAILABLE");
            return null;
        }
        var data=be.saveWithFullMetadata(level.registryAccess()).copy();
        data.remove("x");data.remove("y");data.remove("z");return data;
    }
    /** The native position and state transform calls below are each evaluated
     * exactly once by the mixin; no additional iterable scan or block rotation. */
    public static void begin(WorldCommitService world,PlotCommitService plot,ServerLevel level,
                             AssemblyTransform transform,Iterable<BlockPos> blocks){
        if(!level.getServer().isSameThread())return;
        int cap=Math.max(1,Math.min(DEFAULT_CAP,Integer.getInteger("pdd.splitTransferCap",DEFAULT_CAP)));
        Context context=new Context(world,plot,level,cap,transform,blocks);
        context.disabled=world==null||plot==null;
        if(transform.getLevel()!=level)LOG.warn("[PDD-LINEAGE] phase=REJECT reason=CROSS_DIMENSION");
        ACTIVE.get().push(context);
    }
    public static void mapping(BlockPos from,BlockPos to){
        Context c=active();if(c==null||c.disabled)return;
        c.pendingFrom=from.immutable();c.pendingTo=to.immutable();c.pendingWitness=null;
    }
    public static void states(BlockState old,BlockState rotated){
        Context c=active();
        if(c==null||c.disabled||c.pendingFrom==null||c.pendingTo==null)return;
        var pos=c.pendingFrom;var dest=c.pendingTo;c.pendingFrom=null;c.pendingTo=null;
        try{
                var a=c.locate(c.level,pos);
                if(a==null){
                    try{c.unresolved+=c.plot.quarantineUnresolvedLineage(c.level,pos);}
                    catch(IOException|RuntimeException unsafe){c.fault(unsafe);}
                    return;
                }
                if(old.isAir())return;
                var before=a.key(old);
                var state=c.journal(a).get(before);
                if(state==null)for(var stale:c.journal(a).statesAtPosition(before))
                    c.quarantine(a,stale,"SOURCE_FINGERPRINT_MISMATCH");
                BlockGetter source=a.loaded();
                if(source==null||!old.equals(source.getBlockState(a.local()))){
                    c.quarantine(a,state,"SOURCE_UNAVAILABLE");return;
                }
                if(c.transform.getLevel()!=c.level){c.quarantine(a,state,"CROSS_DIMENSION");return;}
                var b=c.locate(c.level,dest);
                if(b==null){c.quarantine(a,state,"DESTINATION_UNAVAILABLE");return;}
                if(a.physical().equals(b.physical()))return;
                if(Objects.equals(a.owner(),b.owner())){
                    // PDD's own owner-local deformation has its separate
                    // MoveTransaction journal; do not infer an unmapped native move.
                    c.quarantine(a,state,"NATIVE_SAME_OWNER_RELOCATION");return;
                }
                var target=b.key(rotated);
                boolean sample=a.plot()!=null&&b.plot()!=null&&c.sampled.size()<c.cap
                        &&!c.sampled.contains(new OwnerPair(a.plot(),b.plot()));
                if(state==null&&!sample)return;
                var priorSource=c.sources.get(a.physical());var priorTarget=c.destinations.get(b.physical());
                if(priorSource!=null||priorTarget!=null){
                    if(priorSource!=null)priorSource.invalid=true;
                    if(priorTarget!=null)priorTarget.invalid=true;
                    if(priorSource==null||priorSource.transfer==null)c.quarantine(a,state,"CONFLICTING_NATIVE_MAPPING");
                    return;
                }
                if(state!=null&&c.prepared>=c.cap){
                    c.overCap++;c.quarantine(a,state,"TRANSFER_CAP");state=null;
                }
                if(state==null&&!sample)return;
                LineageJournal.Transfer t=null;boolean expectsBE=false;
                if(state!=null&&!state.incarnation().equals(c.incarnation(a,before))){
                    c.quarantine(a,state,"STALE_SOURCE_INCARNATION");state=null;
                }
                if(state!=null&&c.journal(b).hasPosition(target.dimension(),target.x(),target.y(),target.z())){
                    c.quarantine(a,state,"DESTINATION_SPARSE_OCCUPIED");state=null;
                }
                if(state!=null){
                    // The actual native serialization is captured by the redirect;
                    // merely determine whether missing payload would be unsafe.
                    try{expectsBE=old.hasBlockEntity()||source.getBlockEntity(a.local())!=null;}
                    catch(RuntimeException|LinkageError missing){c.quarantine(a,state,"BE_PAYLOAD_UNAVAILABLE");state=null;}
                }
                if(state!=null){
                    if(c.watch(a,before)){
                        if(c.watch(b,target)){
                            String id=UUID.randomUUID().toString();
                            try{
                                t=LineageJournal.prepare(id,c.journal(a),c.journal(b),state,target,c.tick);
                                c.prepared++;
                            }catch(IOException|RuntimeException failed){
                                c.release(a,before,LineageJournal.PREFIX+id+":OUT",false);
                                c.release(b,target,LineageJournal.PREFIX+id+":IN",false);
                                c.fault(failed);
                            }
                        }else{
                            c.quarantine(a,state,"DESTINATION_FENCED");
                            c.release(a,before,"",true);
                        }
                    }else c.quarantine(a,state,"SOURCE_FENCED");
                }
                if(t==null&&!sample)return;
                if(sample)c.sampled.add(new OwnerPair(a.plot(),b.plot()));
                var witness=new Witness(a,b,old,rotated,expectsBE,t);
                c.sources.put(a.physical(),witness);c.destinations.put(b.physical(),witness);
                c.pendingWitness=witness;
        }catch(RuntimeException|LinkageError invalid){
            LOG.error("[PDD-LINEAGE] phase=CAPTURE action=QUARANTINE",invalid);
            c.fault(new IllegalStateException("LINEAGE_CAPTURE_FAILED",invalid));
        }
    }
    /** Capture precisely the tag returned by the original native save. Do
     * not change it: Sable subsequently rebases its x/y/z and loads it. */
    public static void payload(CompoundTag original){
        Context c=active();if(c==null||c.disabled||c.pendingWitness==null)return;
        var witness=c.pendingWitness;if(witness.transfer==null)return;
        try{
            var copy=original.copy();copy.remove("x");copy.remove("y");copy.remove("z");
            witness.nbt=copy;witness.payloadCaptured=true;
        }catch(RuntimeException unsafe){witness.invalid=true;}
    }
    /** Called after the actual successful LevelChunk#setBlockState return. */
    public static void observe(Level level,BlockPos pos,BlockState before,BlockState after){
        var c=active();if(c==null||c.disabled||!(level instanceof ServerLevel server))return;
        var k=new Physical(server,pos);
        var a=c.sources.get(k);if(a!=null)a.source(before,after);
        var b=c.destinations.get(k);if(b!=null)b.destination(before,after);
    }
    public static void finish(ServerLevel level,AssemblyTransform transform,Iterable<BlockPos> positions){
        var stack=ACTIVE.get();
        if(stack.stream().noneMatch(c->c.matches(level,transform,positions)))return;
        while(!stack.peek().matches(level,transform,positions))complete(stack.pop(),false);
        complete(stack.pop(),true);
        if(stack.isEmpty())ACTIVE.remove();
    }
    public static void expire(){
        var stack=ACTIVE.get();
        while(!stack.isEmpty())complete(stack.pop(),false);
        ACTIVE.remove();
    }
    private static void complete(Context c,boolean normalReturn){
        if(c.disabled)return;
        Set<OwnerPair> proved=new HashSet<>();
        for(var w:c.sources.values()){
            boolean success=false;
            try{
                boolean verified=normalReturn&&!c.unsafe&&w.proved();
                if(verified&&w.from.plot()!=null&&w.to.plot()!=null)proved.add(new OwnerPair(w.from.plot(),w.to.plot()));
                if(w.transfer!=null&&verified){
                    LineageJournal.finish(w.transfer,c.journal(w.from),c.journal(w.to),
                            c.incarnation(w.to,w.transfer.destination()),c.tick);
                    success=true;
                }
            }catch(IOException|RuntimeException|LinkageError failure){
                LOG.warn("[PDD-TRANSFER] action=QUARANTINE reason={}",failure.toString());
            }finally{
                var t=w.transfer;
                if(t!=null){
                    if(!success)try{
                        LineageJournal.quarantine(c.journal(w.from),t.outId(),c.tick);
                        LineageJournal.quarantine(c.journal(w.to),t.inId(),c.tick);
                    }catch(IOException|RuntimeException unsafe){c.fault(unsafe);}
                    try{
                        c.release(w.from,t.source().key(),t.outId(),success);
                        c.release(w.to,t.destination(),t.inId(),success);
                    }catch(RuntimeException unsafe){c.fault(unsafe);}
                    LOG.info("[PDD-TRANSFER] id={} result={} from={} to={} damage={} remainder={} deformation={}",
                            t.id(),success?"APPLIED":"QUARANTINED",t.source().key(),t.destination(),
                            t.source().damage(),t.source().remainder(),t.source().deformation());
                }
            }
        }
        if(!c.unsafe)for(var pair:proved)try{c.plot.confirmLineage(pair.from(),pair.to());}
            catch(RuntimeException unsafe){c.fault(unsafe);}
        if(c.prepared>0||c.unresolved>0)LOG.info("[PDD-LINEAGE] phase=RETURN prepared={} unresolved={} capUnresolved={} owners={} nativeReturn={}",
                c.prepared,c.unresolved,c.overCap,proved.size(),normalReturn);
    }
}

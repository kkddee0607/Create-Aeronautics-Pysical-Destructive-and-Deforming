package com.physicaldestroydeform.world;

import com.mojang.authlib.GameProfile;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.deform.MovePlan;
import com.physicaldestroydeform.deform.MoveSafety;
import com.physicaldestroydeform.deform.MoveTransactionExecutor;
import com.physicaldestroydeform.deform.MoveTransactionExecutor.Gate;
import com.physicaldestroydeform.deform.MoveTransactionExecutor.Readback;
import com.physicaldestroydeform.deform.MoveTransactionExecutor.Settlement;
import com.physicaldestroydeform.deform.MoveTransactionJournal.BeforeImage;
import com.physicaldestroydeform.material.CopycatMovePayload;
import com.physicaldestroydeform.material.MaterialRegistry;
import com.physicaldestroydeform.material.TargetMaterials;
import com.physicaldestroydeform.state.SparseStateKey;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** NeoForge 1.21.1 loaded-chunk World adapter. No chunk generation or Plot mutation. */
public final class NeoForgeWorldGateway implements WorldCommitCore.WorldGateway,MoveTransactionExecutor.Gateway {
    private static final GameProfile ACTOR=new GameProfile(
            UUID.nameUUIDFromBytes("PDD:WORLD_COMMIT:0.4.1".getBytes(StandardCharsets.UTF_8)),
            "[PDD] Automation");
    private final MinecraftServer server;
    private final WorldCommitService lifecycle;
    private static final Logger LOG=LoggerFactory.getLogger(NeoForgeWorldGateway.class);
    private static final boolean TRACE=Boolean.getBoolean("pdd.world.verbose")||Boolean.getBoolean("pdd.deform.verbose");
    // Exact synchronous setter transition, consumed once by the replacement tracker.
    private SparseStateKey ownMoveCell;
    private BlockState ownMoveOld,ownMoveNext;
    private static final int MOVE_FLAGS=Block.UPDATE_ALL|Block.UPDATE_MOVE_BY_PISTON;
    public NeoForgeWorldGateway(MinecraftServer server,WorldCommitService lifecycle) {
        this.server=server;this.lifecycle=lifecycle;
    }
    public void clearPermissionCache(){ /* No shared authorization cache across intents. */ }
    @Override public boolean isServerThread(){return server.isSameThread();}
    @Override public boolean materialMatches(WorldCommitCore.Intent intent){
        var snapshot=com.physicaldestroydeform.material.MaterialRegistry.snapshot();
        if(snapshot.generation()!=intent.materialGeneration())return false;
        var material=com.physicaldestroydeform.material.TargetMaterials.read(server,intent.key(),snapshot);
        return material!=null&&intent.material().equals(material.material())&&intent.materialRevision().equals(material.revision());
    }
    private ServerLevel dimension(String dim){
        for(ServerLevel level:server.getAllLevels())
            if(level.dimension().location().toString().equals(dim))return level;
        return null;
    }
    @Override public WorldCommitCore.Observation readIfLoaded(SparseStateKey key) {
        if(!isServerThread() || !"WORLD".equals(key.ownerSpace()))return null;
        ServerLevel level=dimension(key.dimension());
        if(level==null)return null;
        BlockPos pos=new BlockPos(key.x(),key.y(),key.z());
        if(!level.isInWorldBounds(pos) || level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null)
            return null;
        try {
            var chunk=new ChunkPos(pos);
            var container=SubLevelContainer.getContainer(level);
            // A Plot storage cell must never be treated as static World, even at matching XYZ.
            if(container==null || container.getPlot(chunk)!=null || container.inBounds(chunk))return null;
            BlockState state=level.getBlockState(pos);
            String fp=BuiltInRegistries.BLOCK.getKey(state.getBlock())+"|"+state;
            // A just-destroyed block is AIR even if a mod has not yet cleared
            // its old block-entity cache. Never quarantine an otherwise
            // confirmed vanilla destroy purely for a stale BE reference.
            boolean blockEntity=!state.isAir() && (state.hasBlockEntity() || level.getBlockEntity(pos)!=null);
            if(blockEntity&&com.physicaldestroydeform.material.CopycatMaterial.isCopycat(fp)){
                var resolved=com.physicaldestroydeform.material.CopycatMaterial.capture(com.physicaldestroydeform.material.MaterialRegistry.snapshot(),level,pos,state);
                if(resolved!=null&&resolved.safeDecorativeBE())blockEntity=false;
            }
            // No block-ID allow-list: slime, netherite and registered mod blocks can
            // participate. Preserve BE/linked-block safety and intrinsic immunity.
            boolean unsafe=isLinkedMultiblock(state);
            boolean unbreakable=state.getDestroySpeed(level,pos)<0;
            return new WorldCommitCore.Observation(key.dimension(),fp,1,lifecycle.incarnation(key.dimension(),pos,fp),
                    true,state.isAir(),blockEntity,unsafe,unbreakable,false);
        } catch(RuntimeException unsafe){return null;}
    }
    /** Vanilla linked multi-cell structures need an atomic owner-specific adapter. */
    private static boolean isLinkedMultiblock(BlockState state) {
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.getBlock() instanceof BedBlock
                || state.getBlock() instanceof DoorBlock;
    }
    @Override public WorldCommitCore.Permission canDamage(SparseStateKey key,WorldCommitCore.Observation expected) {
        if(!isServerThread()||lifecycle.isQuarantined(key))return WorldCommitCore.Permission.UNKNOWN;
        var seen=readIfLoaded(key);
        if(seen==null || !expected.equals(seen) || seen.isAir()
                || seen.hasBlockEntity() || seen.unsafeMultiblock() || seen.unbreakable())
            return WorldCommitCore.Permission.DENY;
        try {
            ServerLevel level=dimension(key.dimension());
            BlockPos pos=new BlockPos(key.x(),key.y(),key.z());
            var player=FakePlayerFactory.get(level,ACTOR);
            if(!level.mayInteract(player,pos) || !player.mayBuild())return WorldCommitCore.Permission.DENY;
            // A damage-only plan does not break a block. Dispatching a BreakEvent
            // here would trigger unrelated protection, drop and audit handlers even
            // when the block remains intact. Reserve BreakEvent for real breaks.
            return WorldCommitCore.Permission.ALLOW;
        }catch(RuntimeException|LinkageError unsafe){return WorldCommitCore.Permission.UNKNOWN;}
    }
    @Override public String rejectRecoveredReason(WorldCommitCore.Intent intent){
        return lifecycle.isQuarantined(intent.key())?"RECOVERY_CELL_QUARANTINED":null;
    }
    @Override public WorldCommitCore.Permission canBreak(SparseStateKey key,WorldCommitCore.Observation expected) {
        if(!isServerThread())return WorldCommitCore.Permission.UNKNOWN;
        var observed=readIfLoaded(key);
        if(observed==null || !expected.equals(observed) || observed.isAir()
                || observed.hasBlockEntity() || observed.unsafeMultiblock() || observed.unbreakable())
            return WorldCommitCore.Permission.DENY;
        try {
            ServerLevel level=dimension(key.dimension());
            BlockPos pos=new BlockPos(key.x(),key.y(),key.z());
            var player=FakePlayerFactory.get(level,ACTOR);
            if(!level.mayInteract(player,pos) || !player.mayBuild())return WorldCommitCore.Permission.DENY;
            // The approved break is committed through ServerLevel.destroyBlock.
            // A single explicit BreakEvent lets claim/protection mods veto first.
            BlockEvent.BreakEvent veto=new BlockEvent.BreakEvent(level,pos,level.getBlockState(pos),player);
            NeoForge.EVENT_BUS.post(veto);
            return veto.isCanceled()?WorldCommitCore.Permission.DENY:WorldCommitCore.Permission.ALLOW;
        }catch(RuntimeException|LinkageError unsafe){return WorldCommitCore.Permission.UNKNOWN;}
    }
    @Override public boolean breakVanillaOnce(SparseStateKey key) {
        if(!isServerThread())return false;
        ServerLevel level=dimension(key.dimension());
        if(level==null || readIfLoaded(key)==null)return false;
        BlockPos pos=new BlockPos(key.x(),key.y(),key.z());
        // Level#destroyBlock performs drops itself; NEVER call Block.dropResources manually.
        lifecycle.beginOwnBreak(key);
        try {return level.destroyBlock(pos,true);}
        finally {lifecycle.endOwnBreak();}
    }

    public MoveTransactionExecutor.Gateway movementGateway(){return this;}
    @Override public boolean serverThread(){return isServerThread();}
    private static BlockPos pos(SparseStateKey key){return new BlockPos(key.x(),key.y(),key.z());}
    private static String fp(BlockState state){return BuiltInRegistries.BLOCK.getKey(state.getBlock())+"|"+state;}
    private static SparseStateKey at(SparseStateKey owner,BlockPos p,String fingerprint){
        return new SparseStateKey(owner.dimension(),owner.ownerSpace(),owner.ownerId(),owner.ownerGeneration(),
                p.getX(),p.getY(),p.getZ(),fingerprint);
    }
    private static boolean sameCell(SparseStateKey a,SparseStateKey b){
        return a.dimension().equals(b.dimension())&&a.ownerSpace().equals(b.ownerSpace())
                &&a.ownerId().equals(b.ownerId())&&a.ownerGeneration()==b.ownerGeneration()
                &&a.x()==b.x()&&a.y()==b.y()&&a.z()==b.z();
    }
    private Gate worldCell(ServerLevel level,BlockPos pos){
        if(!level.isInWorldBounds(pos)||!level.getWorldBorder().isWithinBounds(pos))return Gate.BLOCKED;
        if(!MoveSafety.loaded(level,pos))return Gate.DEFERRED;
        var container=SubLevelContainer.getContainer(level);
        ChunkPos chunk=new ChunkPos(pos);
        if(container==null)return Gate.UNKNOWN;
        return container.inBounds(chunk)||container.getPlot(chunk)!=null?Gate.BLOCKED:Gate.APPROVED;
    }
    private Gate worldConnections(ServerLevel level,BlockPos pos,BlockState state){
        for(Direction side:Direction.values()){
            Gate gate=worldCell(level,pos.relative(side));if(gate!=Gate.APPROVED)return gate;
        }
        return MoveSafety.checkConnections(level,pos,state);
    }
    /** A Sable broad-phase overlap is not proof of a physical obstruction. Suppress
     * movement without spending blocked Q when a collider-free route cannot be proved. */
    private static boolean clearOfSublevelBounds(ServerLevel level,AABB area){
        var container=SubLevelContainer.getContainer(level);
        if(container==null)return false;
        return !container.queryIntersecting(new BoundingBox3d(area.deflate(1.0e-7))).iterator().hasNext();
    }
    /** Prepare from the actual block and post-direct sparse state. No setter or claim event. */
    public BeforeImage planMove(WorldCommitCore.Intent intent,MovePlan plan,WorldReceiptJournal.State sparse,
                                DamageModel.Settings settings,long gameTick){
        if(!isServerThread()||plan.status()!=MovePlan.Status.CANDIDATE_ONLY||!plan.source().equals(intent.key()))return null;
        try {
            SparseStateKey key=intent.key();ServerLevel level=dimension(key.dimension());BlockPos from=pos(key);
            if(level==null||worldCell(level,from)!=Gate.APPROVED||!materialMatches(intent))return null;
            BlockState source=level.getBlockState(from);
            if(!key.fingerprint().equals(fp(source))||source.isAir()
                    ||!lifecycle.incarnation(key.dimension(),from,key.fingerprint()).equals(intent.expectedIncarnation())
                    ||sparse!=null&&(!sparse.key().equals(key)||!sparse.incarnation().equals(intent.expectedIncarnation())))return null;
            BlockPos to=from;
            for(var step:plan.steps())to=new BlockPos(Math.addExact(to.getX(),step.dx()),Math.addExact(to.getY(),step.dy()),Math.addExact(to.getZ(),step.dz()));
            MoveSafety.path(from,to); // Require signed, bounded geometry even if endpoint is blocked.
            Gate endpoint=worldCell(level,to);
            if(endpoint==Gate.UNKNOWN)return null;
            String destFp=endpoint==Gate.BLOCKED?"PDD:WORLD_BOUNDARY":endpoint==Gate.DEFERRED?"PDD:UNLOADED":fp(level.getBlockState(to));
            SparseStateKey oldDest=at(key,to,destFp),newDest=at(key,to,key.fingerprint());
            long srcRev=lifecycle.moveRevision(key),dstRev=lifecycle.moveRevision(oldDest);
            String token=lifecycle.incarnation(key.dimension(),to,key.fingerprint());
            String nextToken=Math.addExact(dstRev,1)+token.substring(token.indexOf('-'));
            WorldReceiptJournal.State next=new WorldReceiptJournal.State(newDest,nextToken,
                    sparse==null?0:sparse.damage(),sparse==null?0:sparse.remainder(),
                    intent.materialGeneration(),server.getTickCount(),Math.min(255,(sparse==null?0:sparse.deformation())+1));
            String payload=source.hasBlockEntity()?CopycatMovePayload.capture(level,from,source,level.registryAccess()):null;
            byte[] bytes=payload==null?new byte[0]:payload.getBytes(StandardCharsets.UTF_8);
            double candidate=Math.max(0,intent.deformationEvidence().blockedCandidateQ()),applied=Math.min(candidate,plan.reservedQ());
            long expiry=gameTick>Long.MAX_VALUE-WorldCommitCore.MAX_DEFER_TICKS?Long.MAX_VALUE:gameTick+WorldCommitCore.MAX_DEFER_TICKS;
            return new BeforeImage(intent.applyId()+"/move",intent.applyId(),key,oldDest,sparse,next,srcRev,dstRev,
                    gameTick,expiry,plan.directionEpoch(),plan.reservedQ(),intent.material(),intent.materialRevision(),bytes,
                    candidate,applied,DamageModel.blockedFixedIncrement(applied,intent.material(),settings));
        }catch(RuntimeException|LinkageError unsafe){if(TRACE)LOG.warn("[PDD-MOVE] phase=WORLD_PLAN reason=UNSAFE_OR_UNKNOWN source={}",intent.key(),unsafe);return null;}
    }
    @Override public boolean acquire(BeforeImage b){return lifecycle.acquireMove(b);}
    @Override public void release(BeforeImage b){lifecycle.releaseMove(b);}
    @Override public void quarantine(BeforeImage b,String reason){lifecycle.quarantineMove(b,reason);}
    @Override public Settlement settleBlocked(BeforeImage b){return lifecycle.settleBlockedMove(b);}

    private String payload(BeforeImage b){byte[] bytes=b.bePayload();return bytes==null||bytes.length==0?null:new String(bytes,StandardCharsets.UTF_8);}
    private boolean sourceMatches(ServerLevel level,BeforeImage b){
        BlockPos from=pos(b.source());
        if(worldCell(level,from)!=Gate.APPROVED)return false;
        BlockState source=level.getBlockState(from);
        if(!b.source().fingerprint().equals(fp(source)))return false;
        String payload=payload(b);
        if(payload==null)return !source.hasBlockEntity()&&level.getBlockEntity(from)==null;
        return CopycatMovePayload.matches(level,from,source,level.registryAccess(),payload);
    }
    private boolean evidenceMatches(BeforeImage b){
        var snapshot=MaterialRegistry.snapshot();
        if(snapshot.generation()!=b.destinationAfter().materialGeneration())return false;
        var material=TargetMaterials.read(server,b.source(),snapshot);
        return material!=null&&b.material().equals(material.material())&&b.materialRevision().equals(material.revision());
    }
    private boolean revisionsMatch(BeforeImage b){
        return !lifecycle.moveInvalidated(b)&&lifecycle.moveRevision(b.source())==b.sourceRevision()
                &&lifecycle.moveRevision(b.destinationBefore())==b.destinationRevision();
    }
    private boolean beforeMatches(ServerLevel level,BeforeImage b){
        BlockPos to=pos(b.destinationBefore());
        return revisionsMatch(b)&&sourceMatches(level,b)&&worldCell(level,to)==Gate.APPROVED
                &&b.destinationBefore().fingerprint().equals(fp(level.getBlockState(to)))
                &&level.getBlockEntity(to)==null;
    }
    private Gate reason(BeforeImage b,Gate gate,String reason){
        if(TRACE)LOG.info("[PDD-MOVE] phase=WORLD_VALIDATE id={} from={} to={} gate={} reason={}",
                b.id(),pos(b.source()),pos(b.destinationBefore()),gate,reason);
        return gate;
    }
    @Override public Gate validate(BeforeImage b){
        if(!isServerThread()||!revisionsMatch(b))return reason(b,Gate.UNKNOWN,"REVISION_CHANGED");
        ServerLevel level=dimension(b.source().dimension());
        if(level==null)return Gate.DEFERRED;
        try {
            BlockPos from=pos(b.source()),to=pos(b.destinationBefore());
            Gate owner=worldCell(level,from);if(owner!=Gate.APPROVED)return reason(b,owner==Gate.BLOCKED?Gate.UNKNOWN:owner,"SOURCE_OWNER");
            BlockState source=level.getBlockState(from);
            if(!sourceMatches(level,b)){
                if(b.source().fingerprint().equals(fp(source))&&payload(b)==null&&source.hasBlockEntity())return reason(b,Gate.BLOCKED,"UNSUPPORTED_BE");
                return reason(b,Gate.UNKNOWN,"SOURCE_OR_BE_CAS");
            }
            if(!evidenceMatches(b))return reason(b,Gate.UNKNOWN,"MATERIAL_GENERATION_OR_REVISION");
            if(source.isAir()||source.getDestroySpeed(level,from)<0||isLinkedMultiblock(source))return reason(b,Gate.BLOCKED,"SOURCE_IMMUNE_OR_LINKED");
            var player=FakePlayerFactory.get(level,ACTOR);
            if(!player.mayBuild()||!level.mayInteract(player,from))return reason(b,Gate.BLOCKED,"SOURCE_PROTECTION");
            Gate sourceSafety=worldConnections(level,from,source);
            if(sourceSafety!=Gate.APPROVED)return reason(b,sourceSafety,"SOURCE_CONNECTION");
            for(BlockPos step:MoveSafety.path(from,to)) {
                Gate pathOwner=worldCell(level,step);if(pathOwner!=Gate.APPROVED)return reason(b,pathOwner,"PATH_OWNER_OR_LOAD");
                BlockState path=level.getBlockState(step);
                if(step.equals(to)&&!b.destinationBefore().fingerprint().equals(fp(path)))return reason(b,Gate.UNKNOWN,"DESTINATION_CAS");
                // No silent overwrite of fluids, entity payloads or collision shapes.
                if(!MoveSafety.clearPathCell(level,step,path))return reason(b,Gate.BLOCKED,"PATH_OCCUPIED");
                if(!level.mayInteract(player,step))return reason(b,Gate.BLOCKED,"PATH_PROTECTION");
                AABB box=new AABB(step.getX(),step.getY(),step.getZ(),step.getX()+1,step.getY()+1,step.getZ()+1);
                if(!level.getEntitiesOfClass(Entity.class,box).isEmpty())return reason(b,Gate.BLOCKED,"PATH_ENTITY");
                if(!clearOfSublevelBounds(level,box))return reason(b,Gate.UNKNOWN,"SUBLEVEL_BOUNDS_OVERLAP");
                Gate safe=worldConnections(level,step,Blocks.AIR.defaultBlockState());
                if(safe!=Gate.APPROVED)return reason(b,safe,"PATH_CONNECTION");
            }
            if(!source.canSurvive(level,to))return reason(b,Gate.BLOCKED,"DESTINATION_SURVIVAL");
            return reason(b,Gate.APPROVED,"BEFORE_IMAGES_AND_PATH_CONFIRMED");
        }catch(RuntimeException|LinkageError unsafe){return reason(b,Gate.UNKNOWN,"PREFLIGHT_EXCEPTION");}
    }

    /** Other synchronous edits, even to a fenced cell, are not ours. */
    public boolean ownsMoveMutation(SparseStateKey key,BlockState old,BlockState next){
        if(ownMoveCell==null||!sameCell(ownMoveCell,key)||!ownMoveOld.equals(old)||!ownMoveNext.equals(next))return false;
        ownMoveCell=null;ownMoveOld=null;ownMoveNext=null;return true;
    }
    public boolean ownsMoveMutation(SparseStateKey key){return ownMoveCell!=null&&sameCell(ownMoveCell,key);}
    private boolean set(ServerLevel level,SparseStateKey cell,BlockState old,BlockState next){
        if(!loadedExact(level,cell,old))return false;
        ownMoveCell=cell;ownMoveOld=old;ownMoveNext=next;
        try {boolean result=level.setBlock(pos(cell),next,MOVE_FLAGS);return result&&ownMoveCell==null;}
        finally {ownMoveCell=null;ownMoveOld=null;ownMoveNext=null;}
    }
    private boolean loadedExact(ServerLevel level,SparseStateKey cell,BlockState expected){
        BlockPos p=pos(cell);return worldCell(level,p)==Gate.APPROVED&&expected.equals(level.getBlockState(p));
    }
    private boolean destinationMatches(ServerLevel level,BeforeImage b){
        BlockPos to=pos(b.destinationBefore());if(worldCell(level,to)!=Gate.APPROVED)return false;
        BlockState state=level.getBlockState(to);if(!b.destinationAfter().key().fingerprint().equals(fp(state)))return false;
        String payload=payload(b);
        if(payload==null)return !state.hasBlockEntity()&&level.getBlockEntity(to)==null;
        return CopycatMovePayload.matches(level,to,state,level.registryAccess(),payload);
    }
    private boolean postMatches(ServerLevel level,BeforeImage b){
        BlockPos from=pos(b.source());
        return revisionsMatch(b)&&worldCell(level,from)==Gate.APPROVED&&level.getBlockState(from).isAir()
                &&level.getBlockEntity(from)==null&&destinationMatches(level,b);
    }
    private Gate stagedPathGate(ServerLevel level,BeforeImage b,BlockState source){
        BlockPos from=pos(b.source()),to=pos(b.destinationBefore());
        var player=FakePlayerFactory.get(level,ACTOR);
        if(!player.mayBuild()||!level.mayInteract(player,from)||!source.canSurvive(level,to))return Gate.BLOCKED;
        Gate sourceGate=worldConnections(level,from,source);if(sourceGate!=Gate.APPROVED)return sourceGate;
        for(BlockPos step:MoveSafety.path(from,to)){
            Gate owner=worldCell(level,step);if(owner!=Gate.APPROVED)return owner;
            if(!level.mayInteract(player,step))return Gate.BLOCKED;
            BlockState state=level.getBlockState(step);
            if(!step.equals(to)&&!MoveSafety.clearPathCell(level,step,state))return Gate.BLOCKED;
            Gate connected=worldConnections(level,step,step.equals(to)?state:Blocks.AIR.defaultBlockState());if(connected!=Gate.APPROVED)return connected;
            AABB area=new AABB(step.getX(),step.getY(),step.getZ(),step.getX()+1,step.getY()+1,step.getZ()+1);
            if(!level.getEntitiesOfClass(Entity.class,area).isEmpty())return Gate.BLOCKED;
            if(!clearOfSublevelBounds(level,area))return Gate.UNKNOWN;
        }
        return Gate.APPROVED;
    }
    private Readback undoDestination(ServerLevel level,BeforeImage b,BlockState original){
        // Roll back only our proven staged copy; never erase an unrelated replacement.
        if(beforeMatches(level,b))return Readback.RESTORED_BEFORE_IMAGE;
        if(!revisionsMatch(b)||!sourceMatches(level,b)||!destinationMatches(level,b))return Readback.UNCERTAIN;
        BlockState now=level.getBlockState(pos(b.destinationBefore()));
        if(!set(level,b.destinationBefore(),now,original))return Readback.UNCERTAIN;
        return beforeMatches(level,b)?Readback.RESTORED_BEFORE_IMAGE:Readback.UNCERTAIN;
    }
    @Override public Readback mutateOnce(BeforeImage b){
        if(!isServerThread())return Readback.UNCERTAIN;
        ServerLevel level=dimension(b.source().dimension());if(level==null)return Readback.UNCERTAIN;
        BlockState original=null;
        try {
            if(!beforeMatches(level,b))return Readback.UNCERTAIN;
            BlockPos from=pos(b.source()),to=pos(b.destinationBefore());
            BlockState source=level.getBlockState(from);original=level.getBlockState(to);
            var player=FakePlayerFactory.get(level,ACTOR);
            var veto=new BlockEvent.BreakEvent(level,from,source,player);NeoForge.EVENT_BUS.post(veto);
            if(veto.isCanceled())return beforeMatches(level,b)?Readback.RESTORED_BEFORE_IMAGE:Readback.UNCERTAIN;
            Gate revalidated=validate(b);
            if(revalidated!=Gate.APPROVED)return revalidated==Gate.BLOCKED&&beforeMatches(level,b)?Readback.RESTORED_BEFORE_IMAGE:Readback.UNCERTAIN;
            BlockSnapshot snapshot=BlockSnapshot.create(level.dimension(),level,to,MOVE_FLAGS);
            if(!set(level,b.destinationBefore(),original,source))return undoDestination(level,b,original);
            String payload=payload(b);
            if(payload!=null&&!CopycatMovePayload.restore(level,to,source,level.registryAccess(),payload))return undoDestination(level,b,original);
            if(!revisionsMatch(b)||!sourceMatches(level,b)||!destinationMatches(level,b))return Readback.UNCERTAIN;
            // NeoForge obtains the placed block from the live snapshot for a fake player.
            // The source is still present; use it as the actual placement support.
            var place=new BlockEvent.EntityPlaceEvent(snapshot,source,player);NeoForge.EVENT_BUS.post(place);
            if(place.isCanceled())return undoDestination(level,b,original);
            if(!revisionsMatch(b)||!sourceMatches(level,b)||!destinationMatches(level,b))return Readback.UNCERTAIN;
            Gate staged=evidenceMatches(b)?stagedPathGate(level,b,source):Gate.UNKNOWN;
            if(staged!=Gate.APPROVED){
                Readback rollback=undoDestination(level,b,original);
                return staged==Gate.BLOCKED?rollback:Readback.UNCERTAIN; // no reserved-Q debit on unpublished proof
            }
            if(!set(level,b.source(),source,Blocks.AIR.defaultBlockState()))return undoDestination(level,b,original);
            return postMatches(level,b)?Readback.CONFIRMED:Readback.UNCERTAIN;
        }catch(RuntimeException|LinkageError unsafe){
            if(TRACE)LOG.warn("[PDD-MOVE] phase=WORLD_MUTATE reason=SETTER_OR_EVENT_EXCEPTION id={}",b.id(),unsafe);
            return original==null?Readback.UNCERTAIN:undoDestination(level,b,original);
        }
    }
    @Override public Readback readback(BeforeImage b){
        if(!isServerThread())return Readback.UNCERTAIN;
        ServerLevel level=dimension(b.source().dimension());if(level==null)return Readback.WAITING;
        try{return postMatches(level,b)?Readback.CONFIRMED:beforeMatches(level,b)?Readback.RESTORED_BEFORE_IMAGE:Readback.UNCERTAIN;}
        catch(RuntimeException|LinkageError unsafe){return Readback.UNCERTAIN;}
    }
}

package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.material.CopycatMaterial;
import com.physicaldestroydeform.material.MaterialRegistry;
import com.physicaldestroydeform.material.TargetMaterials;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.WorldCommitCore;
import com.physicaldestroydeform.world.WorldCommitService;
import com.physicaldestroydeform.plot.PlotCommitService;
import com.physicaldestroydeform.plot.PlotKeyCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import static com.physicaldestroydeform.stress.StressPropagation.*;
import static com.physicaldestroydeform.stress.StressReservationJournal.*;

/** One server-wide Q-stress executor. Runs only after native observation, before safe commit.
 * Source origins, disjoint partitions, frozen graph and published outputs survive restart.
 * Frozen graph replay recalculates only the same reserved Q; stable ApplyIds deduplicate outputs.
 */
public final class StressRuntime {
    private static final int MAX_WORK=512,QUANTUM=16,LARGE_QUANTUM=64,MAX_ORIGINS_PER_TICK=64,MAX_LOGS=Math.max(0,Math.min(128,
            Integer.getInteger("pdd.stress.trace.maxPerTick",8)));
    private static final int MAX_ARCHIVES_PER_TICK=4;
    private static final boolean TRACE=Boolean.parseBoolean(System.getProperty("pdd.stress.trace","true"));
    private static final class BudgetEnd extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace(){return this;}
    }
    private static final BudgetEnd BUDGET_END=new BudgetEnd();
    /** Only immutable registry-backed materials may share work. Every loaded
     * cell still gets its own owner, state, protection and incarnation checks.
     * Copycats remain positional, even within this tick. */
    static final class StaticMaterialLookup {
        private MaterialRegistry.Snapshot snapshot;
        private final Map<String,Optional<CopycatMaterial.Resolved>> values=new HashMap<>();
        void beginTick(){snapshot=null;values.clear();}
        CopycatMaterial.Resolved resolve(MaterialRegistry.Snapshot current,String fingerprint,
                java.util.function.Supplier<CopycatMaterial.Resolved> read){
            if(CopycatMaterial.isCopycat(fingerprint))return read.get();
            if(snapshot!=current){values.clear();snapshot=current;}
            return values.computeIfAbsent(fingerprint,k->Optional.ofNullable(read.get())).orElse(null);
        }
    }
    /** Entries are immutable and replaced only after durable publication.
     * Index that exact entry, never the speculative graph or the live world. */
    static final class PublishedCells {
        private Entry indexed;
        private final Set<Cell> cells=new HashSet<>();
        boolean contains(Entry entry,Cell cell){
            if(indexed!=entry){
                if(indexed!=null&&indexed.reservation()==entry.reservation()
                        &&indexed.holdsOrigins()==entry.holdsOrigins()
                        &&indexed.phase()==entry.phase()&&indexed.plans().equals(entry.plans())){
                    indexed=entry;return cells.contains(cell);
                }
                cells.clear();
                if(entry.phase()!=Phase.RESERVED){
                    if(!"ROOTS_PENDING".equals(entry.reason()))
                        for(var seed:entry.reservation().seeds())cells.add(seed.node().cell());
                    for(var plan:entry.plans())cells.add(plan.node().cell());
                }
                indexed=entry;
            }
            return cells.contains(cell);
        }
    }
    private static final class Job {
        final String id;
        final Reservation reservation;
        final List<WorldCommitCore.Intent> orderedOrigins;
        final boolean plotOrigins;
        final boolean clockRolledBack;
        Session session;
        final StressProbeCursor probes=new StressProbeCursor();
        final Map<Cell,Probe> snapshot=new TreeMap<>();
        final Set<Cell> watched=new HashSet<>();
        final Set<String> observedPlotOwners=new HashSet<>();
        final PublishedCells published=new PublishedCells();
        List<Map.Entry<Cell,Probe>> validation;
        int validationIndex,deliveryIndex,originIndex;
        long pressureBytes=-1,pressureLogical=-1,pressureRetryAt;
        boolean invalidated,finished,originsRecovered;
        long lastQueuePressureLog=Long.MIN_VALUE;
        Job(Entry entry){
            id=entry.reservation().creditId();reservation=entry.reservation();
            orderedOrigins=com.physicaldestroydeform.deform.MoveRuntime.reverseOrder(reservation.origins());
            plotOrigins=!orderedOrigins.isEmpty()&&PlotKeyCodec.isPlotStorage(orderedOrigins.getFirst().key());
            clockRolledBack=entry.clockRolledBack();
            if(entry.phase()!=Phase.CLOSED&&!clockRolledBack)snapshot.putAll(entry.snapshot());
            // A final capped plan only waits for residual expiry/handoff, never replans.
            finished=entry.phase()!=Phase.RESERVED&&entry.ledger().finished();
        }
    }
    private final MinecraftServer server;
    private final Logger log;
    private final StressReservationJournal journal;
    private final WorldCommitService world;
    private final PlotCommitService plot;
    private final Policy policy;
    private final int workCap;
    private final Map<String,Job> jobs=new LinkedHashMap<>();
    private final Map<String,String> originJobs=new HashMap<>();
    private final Set<String> rollbackOutputs=new HashSet<>();
    private final Deque<String> order=new ArrayDeque<>();
    private final Deque<String> worldOriginOrder=new ArrayDeque<>(),plotOriginOrder=new ArrayDeque<>();
    private final Map<Cell,Set<String>> watches=new HashMap<>();
    private final StaticMaterialLookup materialLookup=new StaticMaterialLookup();
    private boolean faulted,liveClockRollback;
    private long lastTick=-1;
    private int used,shown,activeSessions,originsThisTick;
    private int archivesThisTick,rollbackJobsRemaining;
    private long archiveStartedNanos;
    private long snapshotWritesAvoided,sealWritesAvoided,seals,handoffs,traceSuppressed,capacityRetries,archiveConflicts,rollbackJobsReconciled;

    public StressRuntime(MinecraftServer server,WorldCommitService world,PlotCommitService plot,Logger log)throws IOException {
        this.server=Objects.requireNonNull(server);this.world=world;this.plot=plot;this.log=Objects.requireNonNull(log);
        policy=configuredPolicy();workCap=policy.maxNodesPerTick();
        Path path=server.getWorldPath(LevelResource.ROOT).resolve("data/pdd-stress-0.6.0");
        journal=new StressReservationJournal(path);
        long startupGameTick=server.overworld().getGameTime();
        Set<String> activeOutputs=new HashSet<>();
        for(var entry:journal.entries().stream().sorted(Comparator.comparingLong(e->e.reservation().createdGameTick())).toList()){
            var id=entry.reservation().creditId();
            if(entry.reservation().createdGameTick()>startupGameTick&&!entry.clockRolledBack()){
                journal.closeForClockRollback(id);
                entry=journal.get(id);
            }
            if(entry.clockRolledBack())rollbackJobsRemaining++;
            for(var plan:entry.plans()){
                String apply=applyId(id,plan.node().cell());
                activeOutputs.add(apply);
                if(entry.clockRolledBack())rollbackOutputs.add(apply);
            }
            for(var origin:entry.reservation().origins()){
                String prior=originJobs.putIfAbsent(origin.applyId(),entry.reservation().creditId());
                if(prior!=null&&!prior.equals(entry.reservation().creditId()))throw new IOException("stress source origin reserved twice");
            }
            // CLOSED still has source origins. Repair a crash after reservation/handoff but before direct submit.
            add(entry);
        }
        // Old archives dropped creation time while retaining DEFERRED commit
        // ownership. The production TTL is constant and lets us recover it
        // from the original deadline for IDs with no full active record.
        java.util.function.Predicate<WorldCommitCore.Intent> archived=intent->journal.isArchivedHandoff(intent.applyId(),activeOutputs);
        long ttl=Policy.defaults().ttlTicks();
        int archivedPending=(world==null?0:world.cancelRecoveredArchivedStress(startupGameTick,ttl,archived))
                +(plot==null?0:plot.cancelRecoveredArchivedStress(startupGameTick,ttl,archived));
        if(world!=null)world.attachStress(this);
        if(plot!=null)plot.attachStress(this);
        if(rollbackJobsRemaining>0||archivedPending>0)
            log.warn("[PDD-STRESS-LEDGER] phase=ROLLBACK_RECOVERY startupGameTick={} markedReservations={} cancelledArchivedPending={} reason=STRESS_CLOCK_ROLLBACK_UNUSED QReissue=false",
                    startupGameTick,rollbackJobsRemaining,archivedPending);
        log.info("[PDD-STRESS-LEDGER] phase=START records={} depth={} nodes={} globalWork={} minQ={} ttl={} activeRecords={} archivedRecords={} activeBytes={} recovery=FROZEN_GRAPH_SAME_Q_STABLE_APPLY_ID",
                journal.size(),policy.maxDepth(),policy.maxNodes(),workCap,policy.minQ(),policy.ttlTicks(),journal.activeCount(),journal.archivedCount(),journal.activeBytes());
    }
    private static Policy configuredPolicy(){
        Policy d=Policy.defaults();
        try{return new Policy(Integer.getInteger("pdd.stressDepth",d.maxDepth()),
                Integer.getInteger("pdd.stressMaxNodes",d.maxNodes()),
                Math.min(MAX_WORK,Integer.getInteger("pdd.stressMaxNodesPerTick",d.maxNodesPerTick())),
                Double.parseDouble(System.getProperty("pdd.stressMinQ",Double.toString(d.minQ()))),d.ttlTicks());}
        catch(RuntimeException invalid){return d;}
    }
    private void add(Entry entry){
        Job job=new Job(entry);jobs.put(job.id,job);order.addLast(job.id);
        if(job.orderedOrigins.isEmpty())job.originsRecovered=true;
        else if(!job.clockRolledBack)originOrder(job).addLast(job.id);
        if(entry.phase()!=Phase.CLOSED&&!job.clockRolledBack){
            for(var cell:job.snapshot.keySet())watch(job,cell);
            if(!job.reservation.origins().isEmpty())
                for(var seed:job.reservation.seeds())watch(job,seed.node().cell());
        }
    }
    /** Called once per projected owner-side Grant, BEFORE any original direct intent is queued. */
    public boolean reserve(List<WorldCommitCore.Intent> origins,MaterialRegistry.Snapshot materials,DamageModel.Settings settings)throws IOException {
        requireThread();if(origins.isEmpty())return true;
        if(faulted)throw new IOException("stress runtime faulted");
        var ordered=new ArrayList<>(origins);ordered.sort(Comparator.comparing(WorldCommitCore.Intent::applyId));
        String id=originId(ordered);
        Entry prior=journal.get(id);
        if(prior!=null){
            if(!prior.reservation().origins().equals(ordered)
                    ||prior.reservation().materialGeneration()!=materials.generation()
                    ||!prior.reservation().settings().equals(settings))
                throw new IOException("stress source ID reused with changed payload");
            return !prior.clockRolledBack();
        }
        // A changed projection of the same Grant cannot reserve one of its origins again.
        for(var origin:ordered){String owner=journal.sourceOwner(origin.applyId());
            if(owner!=null&&!owner.equals(id))throw new IOException("stress source origin already belongs to another reservation");}
        List<Seed> seeds=new ArrayList<>();
        for(var origin:ordered){
            var plan=DamageModel.plan(origin.sealedQ(),origin.material(),new BlockDamageState(),settings);
            var axes=origin.deformationEvidence()==null?null:origin.deformationEvidence().direction();
            seeds.add(new Seed(new Node(StressCommitHandoff.cellFor(origin.key()),origin.key().fingerprint(),
                    origin.expectedIncarnation(),origin.material(),origin.materialRevision()),plan.stressQ(),
                    axes==null?null:new Direction(axes.x(),axes.y(),axes.z())));
        }
        // Original full Q and its immutable settings persist beside stress; plastic stays separate.
        long gameTick=server.overworld().getGameTime();
        long age=Math.max(0,(long)server.getTickCount()-ordered.getFirst().eventTick());
        var reservation=new Reservation(id,materials.generation(),Math.max(0,gameTick-age),policy,settings,seeds,ordered);
        if(journal.isArchived(id)){
            if(!journal.matchesArchivedRetry(reservation))throw new IOException("archived stress source reused with changed payload");
            return false; // never resubmit a completed direct intent
        }
        journal.reserve(reservation);
        for(var origin:ordered)originJobs.put(origin.applyId(),id);
        add(journal.get(id));
        if(TRACE&&ordered.size()>8192)log.info("[PDD-STRESS-LEDGER] phase=LARGE_RESERVED id={} roots={} Q={} deadline={} activeBytes={} decodedBytes={} QReissue=false",
                id,ordered.size(),reservation.reservedQ(),deadline(reservation),journal.activeBytes(),journal.activeLogicalBytes());
        return true;
    }
    private static String originId(List<WorldCommitCore.Intent> ordered){
        try{
            var digest=MessageDigest.getInstance("SHA-256");
            for(var origin:ordered){digest.update(origin.applyId().getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
            return "SOURCE-STRESS:"+HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    /** Legacy 0.5 pending IDs have no source reservation and are never retroactively stressed. */
    public boolean holds(WorldCommitCore.Intent intent){
        String id=originJobs.get(intent.applyId());if(id==null)return false;
        Entry entry=journal.get(id);
        return entry!=null&&entry.phase()!=Phase.CLOSED&&(faulted||entry.holdsOrigins());
    }
    public boolean holdsForClockRollback(WorldCommitCore.Intent intent){
        if(rollbackJobsRemaining==0&&!liveClockRollback)return false;
        String id=intent.applyId();
        if(liveClockRollback&&(isDerivedApplyId(id)||journal.sourceOwner(id)!=null))return true;
        if(rollbackOutputs.contains(id))return true;
        Job job=jobs.get(originJobs.get(id));
        return job!=null&&job.clockRolledBack;
    }
    public boolean faulted(){return faulted;}
    private void charge(){if(used>=workCap)throw BUDGET_END;used++;}
    private void requireThread(){if(!server.isSameThread())throw new IllegalStateException("stress requires server thread");}
    private void watch(Job job,Cell cell){
        if(!job.watched.add(cell))return;
        var owners=watches.computeIfAbsent(cell,k->new HashSet<>());
        boolean first=owners.isEmpty();owners.add(job.id);
        if(!first)return; // underlying adapters register a cell, not an individual job
        SparseStateKey key=StressCommitHandoff.keyFor(cell,"PDD:STRESS_WATCH");
        if(PlotKeyCodec.isPlotStorage(key)){if(plot!=null)plot.watchStress(key,true);}
        else if(world!=null)world.watchStress(key,true);
    }
    /** Called after actual replacement, including same BlockState reinstall. Own writes are still visible to other jobs. */
    public void replaced(SparseStateKey key){
        Cell cell=StressCommitHandoff.cellFor(key);
        for(String id:watches.getOrDefault(cell,Set.of())){
            Job job=jobs.get(id);if(job==null)continue;
            Entry entry=journal.get(id);
            if(!job.published.contains(entry,cell))job.invalidated=true;
        }
    }
    private Probe live(Job job,Cell cell){
        charge();watch(job,cell);
        var key=StressCommitHandoff.keyFor(cell,"PDD:STRESS_PROBE");
        WorldCommitCore.Observation observation;
        if(PlotKeyCodec.isPlotStorage(key)){
            observation=plot==null?null:plot.readStress(key);
            // A crash before the first direct receipt can leave no restored
            // Plot fence. Only a saved root's exact incarnation may bind it.
            if(observation==null&&plot!=null&&!job.observedPlotOwners.contains(cell.owner())){
                var roots=job.reservation.seeds();
                int lo=0,hi=roots.size()-1,ownerRoot=-1;
                while(lo<=hi){
                    int mid=(lo+hi)>>>1;var root=roots.get(mid).node();
                    int comparison=root.cell().compareTo(cell);
                    if(comparison==0){ownerRoot=mid;break;}
                    if(root.cell().owner().equals(cell.owner()))ownerRoot=mid;
                    if(comparison<0)lo=mid+1;else hi=mid-1;
                }
                if(ownerRoot>=0){
                    var root=roots.get(ownerRoot).node();
                    var rootKey=StressCommitHandoff.keyFor(root.cell(),"PDD:STRESS_PROBE");
                    var recovered=plot.readRecoveredStress(rootKey,root.incarnation());
                    if(recovered!=null){
                        job.observedPlotOwners.add(cell.owner());
                        if(root.cell().equals(cell))observation=recovered;
                        else observation=plot.readStress(key);
                    }
                }
            }
            if(observation!=null)job.observedPlotOwners.add(cell.owner());
        }
        else observation=world==null?null:world.readStress(key);
        if(observation==null||!observation.loaded())return Probe.unavailable();
        if(observation.isAir())return Probe.empty();
        if(observation.hasBlockEntity()||observation.unsafeMultiblock()||observation.unbreakable()
                ||observation.unsafePhysicsPhase()||observation.ownerGeneration()!=key.ownerGeneration())return Probe.denied();
        var materials=MaterialRegistry.snapshot();
        if(materials.generation()!=job.reservation.materialGeneration())return Probe.denied();
        // Owner/coordinates were already decoded and canonicalized above;
        // only the verified live fingerprint changes for material resolution.
        var materialKey=new SparseStateKey(key.dimension(),key.ownerSpace(),key.ownerId(),key.ownerGeneration(),
                key.x(),key.y(),key.z(),observation.fingerprint());
        var resolved=materialLookup.resolve(materials,observation.fingerprint(),
                ()->TargetMaterials.read(server,materialKey,materials));
        if(resolved==null||resolved.material().unbreakable())return Probe.denied();
        return Probe.solid(new Node(cell,observation.fingerprint(),observation.incarnation(),resolved.material(),resolved.revision()));
    }
    private Probe frozen(Job job,Cell cell){
        Probe old=job.snapshot.get(cell);
        if(old!=null&&old.state()!=State.UNAVAILABLE){charge();return old;}
        Probe next=live(job,cell);job.snapshot.put(cell,next);return next;
    }
    public void tick(){
        requireThread();long tick=server.overworld().getGameTime();
        if(faulted||tick==lastTick)return;
        if(tick<lastTick){faulted=true;liveClockRollback=true;log.error("[PDD-STRESS-LEDGER] phase=HALT reason=GAME_CLOCK_ROLLBACK");return;}
        lastTick=tick;used=0;shown=0;archivesThisTick=0;originsThisTick=0;materialLookup.beginTick();
        try{
            int turns=order.size();
            while(turns-->0&&!order.isEmpty()&&used<workCap){
                String id=order.removeFirst();Job job=jobs.get(id);if(job==null)continue;
                if(job.pressureBytes==journal.activeBytes()&&job.pressureLogical==journal.activeLogicalBytes()&&tick<job.pressureRetryAt){order.addLast(id);continue;}
                job.pressureBytes=-1;
                try{step(job,tick);}catch(BudgetEnd exhausted){/* exact shared allowance; preserve cursors */}
                catch(CapacityException pressure){
                    // Quota pressure is not a corrupt save. Retry the exact job,
                    // keep its immutable prefix, and allow other jobs to drain.
                    long created=job.reservation.createdGameTick(),ttl=job.reservation.policy().ttlTicks();
                    long deadline=created>Long.MAX_VALUE-ttl?Long.MAX_VALUE:created+ttl;
                    job.pressureBytes=journal.activeBytes();job.pressureLogical=journal.activeLogicalBytes();job.pressureRetryAt=Math.min(deadline,tick>Long.MAX_VALUE-20?Long.MAX_VALUE:tick+20);
                    if(++capacityRetries==1||capacityRetries%256==0)
                        log.warn("[PDD-STRESS-LEDGER] phase=PRESSURE id={} reason={} roots={} snapshot={} activeBytes={} decodedBytes={} activeRecords={} retries={} QReissue=false",
                                id,pressure.getMessage(),job.reservation.seeds().size(),job.snapshot.size(),journal.activeBytes(),journal.activeLogicalBytes(),journal.activeCount(),capacityRetries);
                }
                if(jobs.containsKey(id))order.addLast(id);
            }
            if(server.getTickCount()%100==0)log.info("[PDD-STRESS-LEDGER] phase=SUMMARY jobs={} work={} cap={} records={} fault=false seals={} handoffs={} unchangedSnapshotWritesAvoided={} unchangedSealWritesAvoided={} traceSuppressed={} activeRecords={} archivedRecords={} activeBytes={} decodedBytes={} worldOriginJobs={} plotOriginJobs={} pressureRetries={} archiveConflicts={} rollbackJobsReconciled={}",
                    jobs.size(),used,workCap,journal.size(),seals,handoffs,snapshotWritesAvoided,sealWritesAvoided,traceSuppressed,journal.activeCount(),journal.archivedCount(),journal.activeBytes(),journal.activeLogicalBytes(),worldOriginOrder.size(),plotOriginOrder.size(),capacityRetries,archiveConflicts,rollbackJobsReconciled);
        }catch(IOException|RuntimeException failure){
            faulted=true;log.error("[PDD-STRESS-LEDGER] phase=HALT reason=SAVE_OR_PLAN_FAILURE QReissue=false",failure);
        }
    }
    private boolean showTrace(){
        if(!TRACE||!log.isInfoEnabled()||shown>=MAX_LOGS){traceSuppressed++;return false;}
        shown++;return true;
    }
    private void step(Job job,long tick)throws IOException {
        Entry entry=journal.get(job.id);var r=job.reservation;
        if(job.clockRolledBack){cancelRolledBackJob(job,entry);return;}
        if(entry.phase()==Phase.CLOSED){
            if(!deliverOrigins(job))return;
            // A v4 CLOSED record claims handoff, not successful damage. Verify
            // every output without reissuing any missing receipt after a restore.
            while(job.deliveryIndex<entry.plans().size()){
                charge();var intent=derivedIntent(job,entry.plans().get(job.deliveryIndex));
                long ttl=r.policy().ttlTicks(),deadline=r.createdGameTick()>Long.MAX_VALUE-ttl?Long.MAX_VALUE:r.createdGameTick()+ttl;
                boolean durable=PlotKeyCodec.isPlotStorage(intent.key())?plot!=null&&plot.hasStressReceipt(intent,r.settings(),deadline)
                        :world!=null&&world.hasStressReceipt(intent,r.settings(),deadline);
                if(!durable){if(++archiveConflicts==1||archiveConflicts%256==0)
                    log.error("[PDD-STRESS-LEDGER] phase=ARCHIVE_WAIT id={} applyId={} reason=MISSING_OR_MISMATCHED_RECEIPT QReissue=false",job.id,intent.applyId());return;}
                job.deliveryIndex++;
            }
            archive(job);return;
        }
        boolean expired=tick<r.createdGameTick()||tick-r.createdGameTick()>=r.policy().ttlTicks();
        // Expiry is a durable transition, not a recurring per-tick charge. Repeating
        // it would consume a cap=1 allowance forever and starve delivery/acknowledgment.
        if(expired&&(entry.phase()==Phase.RESERVED||!entry.ledger().finished()||entry.ledger().pendingQ()!=0)){
            charge();journal.closeResidual(job.id,"ORIGINAL_TTL_UNUSED");job.finished=true;
            entry=journal.get(job.id);
        }else if(MaterialRegistry.generation()!=r.materialGeneration()&&(!job.finished||entry.ledger()!=null&&entry.ledger().pendingQ()!=0)){
            charge();journal.closeResidual(job.id,"MATERIAL_GENERATION_CHANGED_UNUSED");job.finished=true;
            entry=journal.get(job.id);
        }else if(job.invalidated&&!job.finished){
            charge();journal.deferResidual(job.id,"SNAPSHOT_REPLACED_DEFERRED_BOUNDED");job.finished=true;
            entry=journal.get(job.id);
        }
        boolean originsAttempted=!entry.holdsOrigins();
        if(originsAttempted)deliverOrigins(job);
        if(!job.finished){
            if(job.session==null){
                if(activeSessions>=StressScheduler.MAX_JOBS)return;
                charge();job.session=new Session(r.seeds(),r.policy(),r.settings(),r.createdGameTick(),true);activeSessions++;
            }
            if(job.validation==null){
                int quantum=r.seeds().size()>256?LARGE_QUANTUM:QUANTUM;
                try{
                    for(int i=0;i<quantum&&used<workCap;i++){
                        job.probes.advance(job.session,cell->frozen(job,cell),tick,this::charge);
                        if(job.session.finished()||job.session.waiting())break;
                    }
                }catch(BudgetEnd exhausted){/* persist the frozen partial operation too */}
                job.session.verify();
                // Before the last root, no derived plan can exist. Persist its
                // frozen observations, but do not repeatedly CAS the entire
                // growing graph. Validate once at the root publication barrier.
                if(!job.session.rootsExpanded()){
                    journal.snapshot(job.id,job.snapshot);
                    return;
                }
                if(!entry.holdsOrigins()&&!job.session.finished()&&!job.session.waiting()&&r.seeds().size()>256){
                    // Let a large graph finish a bounded planning slice before
                    // revalidating its full prefix again. Direct roots already
                    // own their original durable partition.
                    journal.snapshot(job.id,job.snapshot);
                    return;
                }
                // Persist observations before they may influence a later durable output.
                // Retain the validation cursor even when quota pressure prevents
                // this snapshot write. No output is published until sealProgress
                // durably writes the same frozen graph and exact plan together.
                // A published ordinary cell is already consumed; the mutation
                // watcher handles replacements and the commit has its own CAS.
                // Positional Copycat revisions still receive a live read.
                job.validation=new ArrayList<>();
                for(var probe:job.snapshot.entrySet()){
                    var saved=probe.getValue().node();
                    if(!job.published.contains(entry,probe.getKey())||saved!=null&&!saved.materialRevision().isEmpty())
                        job.validation.add(Map.entry(probe.getKey(),probe.getValue()));
                }
                job.validationIndex=0;
                if(entry.phase()==Phase.RESERVED){
                    journal.snapshot(job.id,job.snapshot);
                    if(journal.get(job.id)==entry)snapshotWritesAvoided++;
                }
            }
            while(job.validationIndex<job.validation.size()&&used<workCap){
                var probe=job.validation.get(job.validationIndex);
                if(job.published.contains(entry,probe.getKey())){
                    // Already-paid nodes may have been removed by their own commit.
                    // For a surviving same-incarnation Copycat, however, a material edit
                    // must not drive additional propagation from the frozen graph.
                    var saved=probe.getValue().node();
                    if(saved!=null&&!saved.materialRevision().isEmpty()){
                        var now=live(job,probe.getKey());
                        if(now.state()==State.UNAVAILABLE)return;
                        var current=now.node();
                        if(now.state()==State.DENIED||current!=null&&current.incarnation().equals(saved.incarnation())&&!current.materialRevision().equals(saved.materialRevision())){
                            job.invalidated=true;return;
                        }
                    }else charge();
                    job.validationIndex++;continue;
                }
                // Unloaded neighbors remain retries under the original deadline, never assumed AIR.
                if(probe.getValue().state()==State.UNAVAILABLE){charge();job.validationIndex++;continue;}
                Probe actual=live(job,probe.getKey());
                if(actual.state()==State.UNAVAILABLE)return;
                if(!actual.equals(probe.getValue())){job.invalidated=true;return;}
                job.validationIndex++;
            }
            if(job.validationIndex<job.validation.size())return;
            if(job.invalidated)return;
            // Recovery recomputes from saved graph; do not publish an incomplete old prefix.
            if(job.session.plannedNodes()>=entry.plans().size()){
                charge();var beforeSeal=journal.get(job.id);
                journal.sealProgress(job.id,job.session,job.snapshot);
                var sealed=journal.get(job.id);job.finished=job.session.finished();
                if(sealed==beforeSeal)sealWritesAvoided++;
                else{
                    seals++;
                    if(showTrace())log.info("[PDD-STRESS-LEDGER] phase=SEALED id={} Q={} absorbed={} direct={} plasticReservedUnused={} unused={} deferred={} nodes={} deadline={}",
                            job.id,sealed.ledger().reservedQ(),sealed.ledger().absorbedQ(),sealed.ledger().directQ(),sealed.ledger().plasticUnusedQ(),sealed.ledger().unusedQ(),sealed.ledger().pendingQ(),sealed.plans().size(),deadline(r));
                }
                entry=sealed;
            }
            job.validation=null;
        }
        if(job.finished&&job.session!=null){job.session=null;activeSessions--;}
        entry=journal.get(job.id);
        if(entry.holdsOrigins()||(!originsAttempted&&!deliverOrigins(job))||!job.originsRecovered)return;
        int handoffs=0;
        while(job.deliveryIndex<entry.plans().size()&&handoffs++<QUANTUM&&used<workCap){
            charge();var plan=entry.plans().get(job.deliveryIndex);
            var intent=derivedIntent(job,plan);var key=intent.key();
            boolean accepted=PlotKeyCodec.isPlotStorage(key)?plot!=null&&plot.offerStress(entry,plan,intent,job.deliveryIndex)
                    :world!=null&&world.offerStress(entry,plan,intent,job.deliveryIndex);
            if(!accepted)break;
            job.deliveryIndex++;this.handoffs++;
            if(showTrace())log.info("[PDD-STRESS-APPLY] phase=DURABLE_HANDOFF id={} origin={} owner={} position={},{},{} depth={} incomingQ={} fixedDamage={} status=QUEUED_OR_TERMINAL_NOT_ASSUMED_APPLIED",
                    intent.applyId(),job.id,key.dimension(),key.x(),key.y(),key.z(),plan.depth(),plan.incomingQ(),plan.plan().fixedIncrement());
        }
        if(entry.phase()==Phase.SEALED&&entry.ledger().finished()&&entry.ledger().pendingQ()==0&&job.deliveryIndex>=entry.plans().size()){
            archive(job);
        }
    }
    private void cancelRolledBackJob(Job job,Entry entry)throws IOException {
        var r=job.reservation;
        int batch=0;
        while(job.originIndex<job.orderedOrigins.size()&&used<workCap
                &&originsThisTick<MAX_ORIGINS_PER_TICK&&batch++<MAX_ORIGINS_PER_TICK/2){
            charge();originsThisTick++;
            var origin=job.orderedOrigins.get(job.originIndex);
            boolean cancelled=PlotKeyCodec.isPlotStorage(origin.key())
                    ?plot!=null&&plot.cancelStressOriginOnRollback(origin,r.settings(),deadline(r))
                    :world!=null&&world.cancelStressOriginOnRollback(origin,r.settings(),deadline(r));
            if(!cancelled)return;
            job.originIndex++;
        }
        if(job.originIndex<job.orderedOrigins.size())return;
        job.originsRecovered=true;
        batch=0;
        while(job.deliveryIndex<entry.plans().size()&&used<workCap&&batch++<QUANTUM){
            charge();var plan=entry.plans().get(job.deliveryIndex);
            var intent=derivedIntent(job,plan);
            boolean cancelled=PlotKeyCodec.isPlotStorage(intent.key())
                    ?plot!=null&&plot.cancelStressOutputOnRollback(entry,plan,intent,job.deliveryIndex)
                    :world!=null&&world.cancelStressOutputOnRollback(entry,plan,intent,job.deliveryIndex);
            if(!cancelled)return;
            job.deliveryIndex++;
        }
        if(job.deliveryIndex>=entry.plans().size())archive(job);
    }
    private Deque<String> originOrder(Job job){return job.plotOrigins?plotOriginOrder:worldOriginOrder;}
    private static long deadline(Reservation r){
        return StressCommitHandoff.deadline(r.createdGameTick(),r.policy().ttlTicks());
    }
    /** The reservation is the durable input spool. No source is dropped merely
     * because the direct receipt queue is temporarily full. Preserve each
     * grant's reverse-layer order and do not overtake older same-domain jobs. */
    private boolean deliverOrigins(Job job)throws IOException{
        if(job.originsRecovered)return true;
        var pending=originOrder(job);
        if(!job.id.equals(pending.peekFirst()))return false;
        var r=job.reservation;
        int batch=0;
        while(job.originIndex<job.orderedOrigins.size()&&used<workCap
                &&originsThisTick<MAX_ORIGINS_PER_TICK&&batch++<MAX_ORIGINS_PER_TICK/2){
            charge();originsThisTick++;
            var origin=job.orderedOrigins.get(job.originIndex);
            boolean accepted=job.plotOrigins
                    ?plot!=null&&plot.recoverStressOrigin(origin,r.settings(),r.createdGameTick(),r.policy().ttlTicks())
                    :world!=null&&world.recoverStressOrigin(origin,r.settings(),r.createdGameTick(),r.policy().ttlTicks());
            if(!accepted){
                if(job.lastQueuePressureLog==Long.MIN_VALUE||lastTick-job.lastQueuePressureLog>=100){
                    job.lastQueuePressureLog=lastTick;
                    if(showTrace())log.info("[PDD-STRESS-LEDGER] phase=ORIGIN_WAIT id={} applyId={} owner={} delivered={} total={} deadline={} gameTick={} QReissue=false",
                            job.id,origin.applyId(),job.plotOrigins?"PLOT":"WORLD",job.originIndex,job.orderedOrigins.size(),deadline(r),lastTick);
                }
                return false;
            }
            job.originIndex++;
        }
        if(job.originIndex<job.orderedOrigins.size())return false;
        job.originsRecovered=true;pending.removeFirst();return true;
    }
    private void archive(Job job)throws IOException {
        // Retirement is not a damage/mutation operation; delaying it does not
        // change any Q or original TTL. Bound migration fsync bursts per tick.
        if(archivesThisTick>=MAX_ARCHIVES_PER_TICK||archivesThisTick>0&&System.nanoTime()-archiveStartedNanos>=2_000_000L)return;
        charge();if(archivesThisTick++==0)archiveStartedNanos=System.nanoTime();
        Entry entry=journal.get(job.id);
        journal.compactHandoff(job.id);
        if(job.clockRolledBack){
            for(var plan:entry.plans())rollbackOutputs.remove(applyId(job.id,plan.node().cell()));
            rollbackJobsRemaining--;
            rollbackJobsReconciled++;
        }
        remove(job);
    }
    private static WorldCommitCore.Intent derivedIntent(Job job,PlannedNode plan){
        var r=job.reservation;var key=StressCommitHandoff.keyFor(plan.node().cell(),plan.node().fingerprint());
        long event=r.origins().isEmpty()?r.createdGameTick():r.origins().getFirst().eventTick();
        return new WorldCommitCore.Intent(applyId(job.id,plan.node().cell()),key,plan.node().incarnation(),
                plan.incomingQ(),plan.node().material(),r.materialGeneration(),event,false,plan.node().materialRevision());
    }
    private void remove(Job job){
        jobs.remove(job.id);if(job.session!=null)activeSessions--;
        if(!job.originsRecovered&&!job.clockRolledBack)originOrder(job).remove(job.id);
        for(var origin:job.reservation.origins())originJobs.remove(origin.applyId(),job.id);
        for(var cell:job.watched){
            Set<String> owners=watches.get(cell);if(owners==null)continue;owners.remove(job.id);
            if(owners.isEmpty()){
                watches.remove(cell);var key=StressCommitHandoff.keyFor(cell,"PDD:STRESS_RELEASE");
                if(PlotKeyCodec.isPlotStorage(key)){if(plot!=null)plot.watchStress(key,false);}
                else if(world!=null)world.watchStress(key,false);
            }
        }
    }
    public void stop(){
        for(var job:new ArrayList<>(jobs.values()))remove(job);
        order.clear();originJobs.clear();rollbackOutputs.clear();
    }
}

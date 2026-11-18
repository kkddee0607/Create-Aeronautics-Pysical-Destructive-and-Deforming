package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.material.MaterialRegistry;
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
    private static final int MAX_WORK=512,QUANTUM=16,MAX_LOGS=8;
    private static final boolean TRACE=Boolean.parseBoolean(System.getProperty("pdd.stress.trace","true"));
    private static final class BudgetEnd extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace(){return this;}
    }
    private static final BudgetEnd BUDGET_END=new BudgetEnd();
    private static final class Job {
        final String id;
        final Reservation reservation;
        final List<WorldCommitCore.Intent> orderedOrigins;
        Session session;
        final StressProbeCursor probes=new StressProbeCursor();
        final Map<Cell,Probe> snapshot=new TreeMap<>();
        final Set<Cell> watched=new HashSet<>();
        List<Map.Entry<Cell,Probe>> validation;
        int validationIndex,deliveryIndex,originIndex;
        boolean invalidated,finished,originsRecovered;
        Job(Entry entry){
            id=entry.reservation().creditId();reservation=entry.reservation();
            orderedOrigins=com.physicaldestroydeform.deform.MoveRuntime.reverseOrder(reservation.origins());
            snapshot.putAll(entry.snapshot());
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
    private final Deque<String> order=new ArrayDeque<>();
    private final Map<Cell,Set<String>> watches=new HashMap<>();
    private boolean faulted;
    private long lastTick=-1;
    private int used,shown,activeSessions;

    public StressRuntime(MinecraftServer server,WorldCommitService world,PlotCommitService plot,Logger log)throws IOException {
        this.server=Objects.requireNonNull(server);this.world=world;this.plot=plot;this.log=Objects.requireNonNull(log);
        policy=configuredPolicy();workCap=policy.maxNodesPerTick();
        Path path=server.getWorldPath(LevelResource.ROOT).resolve("data/pdd-stress-0.6.0");
        journal=new StressReservationJournal(path);
        for(var entry:journal.entries()){
            for(var origin:entry.reservation().origins()){
                String prior=originJobs.putIfAbsent(origin.applyId(),entry.reservation().creditId());
                if(prior!=null&&!prior.equals(entry.reservation().creditId()))throw new IOException("stress source origin reserved twice");
            }
            // CLOSED still has source origins. Repair a crash after reservation/handoff but before direct submit.
            if(entry.phase()!=Phase.CLOSED||!entry.reservation().origins().isEmpty())add(entry);
        }
        if(world!=null)world.attachStress(this);
        if(plot!=null)plot.attachStress(this);
        log.info("[PDD-STRESS-LEDGER] phase=START records={} depth={} nodes={} globalWork={} minQ={} ttl={} recovery=FROZEN_GRAPH_SAME_Q_STABLE_APPLY_ID",
                journal.size(),policy.maxDepth(),policy.maxNodes(),workCap,policy.minQ(),policy.ttlTicks());
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
        for(var cell:job.snapshot.keySet())watch(job,cell);
        for(var origin:job.reservation.origins())watch(job,StressCommitHandoff.cellFor(origin.key()));
    }
    /** Called once per projected owner-side Grant, BEFORE any original direct intent is queued. */
    public void reserve(List<WorldCommitCore.Intent> origins,MaterialRegistry.Snapshot materials,DamageModel.Settings settings)throws IOException {
        requireThread();if(faulted)throw new IOException("stress runtime faulted");
        if(origins.isEmpty())return;
        var ordered=new ArrayList<>(origins);ordered.sort(Comparator.comparing(WorldCommitCore.Intent::applyId));
        String id=originId(ordered);
        Entry prior=journal.get(id);
        if(prior!=null){
            if(!prior.reservation().origins().equals(ordered)
                    ||prior.reservation().materialGeneration()!=materials.generation()
                    ||!prior.reservation().settings().equals(settings))
                throw new IOException("stress source ID reused with changed payload");
            return;
        }
        // A changed projection of the same Grant cannot reserve one of its origins again.
        for(var origin:ordered)if(originJobs.containsKey(origin.applyId()))
            throw new IOException("stress source origin already belongs to another reservation");
        List<Seed> seeds=new ArrayList<>();
        for(var origin:ordered){
            var plan=DamageModel.plan(origin.sealedQ(),origin.material(),new BlockDamageState(),settings);
            var axes=origin.deformationEvidence()==null?null:origin.deformationEvidence().direction();
            seeds.add(new Seed(new Node(StressCommitHandoff.cellFor(origin.key()),origin.key().fingerprint(),
                    origin.expectedIncarnation(),origin.material(),origin.materialRevision()),plan.stressQ(),
                    axes==null?null:new Direction(axes.x(),axes.y(),axes.z())));
        }
        // Original full Q and its immutable settings persist beside stress; plastic stays separate.
        var reservation=new Reservation(id,materials.generation(),server.overworld().getGameTime(),policy,settings,seeds,ordered);
        journal.reserve(reservation);
        for(var origin:ordered)originJobs.put(origin.applyId(),id);
        add(journal.get(id));
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
        if(faulted)return true;
        Entry entry=journal.get(id);
        return entry.holdsOrigins();
    }
    public boolean faulted(){return faulted;}
    private void charge(){if(used>=workCap)throw BUDGET_END;used++;}
    private void requireThread(){if(!server.isSameThread())throw new IllegalStateException("stress requires server thread");}
    private void watch(Job job,Cell cell){
        if(!job.watched.add(cell))return;
        watches.computeIfAbsent(cell,k->new HashSet<>()).add(job.id);
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
            if(!publishedCell(entry,cell))job.invalidated=true;
        }
    }
    private boolean publishedCell(Entry entry,Cell cell){
        if(entry.phase()==Phase.RESERVED)return false;
        if(!"ROOTS_PENDING".equals(entry.reason()))
            for(var seed:entry.reservation().seeds())if(seed.node().cell().equals(cell))return true;
        for(var plan:entry.plans())if(plan.node().cell().equals(cell))return true;
        return false;
    }
    private Probe live(Job job,Cell cell){
        charge();watch(job,cell);
        var key=StressCommitHandoff.keyFor(cell,"PDD:STRESS_PROBE");
        WorldCommitCore.Observation observation;
        if(PlotKeyCodec.isPlotStorage(key))observation=plot==null?null:plot.readStress(key);
        else observation=world==null?null:world.readStress(key);
        if(observation==null||!observation.loaded())return Probe.unavailable();
        if(observation.isAir())return Probe.empty();
        if(observation.hasBlockEntity()||observation.unsafeMultiblock()||observation.unbreakable()
                ||observation.unsafePhysicsPhase()||observation.ownerGeneration()!=key.ownerGeneration())return Probe.denied();
        var materials=MaterialRegistry.snapshot();
        if(materials.generation()!=job.reservation.materialGeneration())return Probe.denied();
        var resolved=com.physicaldestroydeform.material.TargetMaterials.read(server,
                StressCommitHandoff.keyFor(cell,observation.fingerprint()),materials);
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
        if(tick<lastTick){faulted=true;log.error("[PDD-STRESS-LEDGER] phase=HALT reason=GAME_CLOCK_ROLLBACK");return;}
        lastTick=tick;used=0;shown=0;
        try{
            int turns=order.size();
            while(turns-->0&&!order.isEmpty()&&used<workCap){
                String id=order.removeFirst();Job job=jobs.get(id);if(job==null)continue;
                try{step(job,tick);}catch(BudgetEnd exhausted){/* exact shared allowance; preserve cursors */}
                if(jobs.containsKey(id))order.addLast(id);
            }
            if(server.getTickCount()%100==0)log.info("[PDD-STRESS-LEDGER] phase=SUMMARY jobs={} work={} cap={} records={} fault=false",jobs.size(),used,workCap,journal.size());
        }catch(IOException|RuntimeException failure){
            faulted=true;log.error("[PDD-STRESS-LEDGER] phase=HALT reason=SAVE_OR_PLAN_FAILURE QReissue=false",failure);
        }
    }
    private void step(Job job,long tick)throws IOException {
        Entry entry=journal.get(job.id);var r=job.reservation;
        if(!job.originsRecovered){
            while(job.originIndex<job.orderedOrigins.size()){
                charge();var origin=job.orderedOrigins.get(job.originIndex);
                boolean accepted=PlotKeyCodec.isPlotStorage(origin.key())
                        ?plot!=null&&plot.recoverStressOrigin(origin,r.settings(),r.createdGameTick(),r.policy().ttlTicks())
                        :world!=null&&world.recoverStressOrigin(origin,r.settings(),r.createdGameTick(),r.policy().ttlTicks());
                if(!accepted)return;job.originIndex++;
            }
            job.originsRecovered=true;
        }
        if(entry.phase()==Phase.CLOSED){remove(job);return;}
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
        if(!job.finished){
            if(job.session==null){
                if(activeSessions>=StressScheduler.MAX_JOBS)return;
                charge();job.session=new Session(r.seeds(),r.policy(),r.settings(),r.createdGameTick(),true);activeSessions++;
            }
            if(job.validation==null){
                for(int i=0;i<QUANTUM&&used<workCap;i++){
                    job.probes.advance(job.session,cell->frozen(job,cell),tick,this::charge);
                    if(job.session.ledger().finished())break;
                }
                // Persist observations before they may influence a later durable output.
                journal.snapshot(job.id,job.snapshot);
                job.validation=new ArrayList<>(job.snapshot.entrySet());job.validationIndex=0;
            }
            while(job.validationIndex<job.validation.size()&&used<workCap){
                var probe=job.validation.get(job.validationIndex);
                if(publishedCell(entry,probe.getKey())){
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
            if(job.session.partialPlans().size()>=entry.plans().size()){
                charge();journal.sealProgress(job.id,job.session,job.snapshot);
                entry=journal.get(job.id);job.finished=job.session.ledger().finished();
                if(TRACE&&shown++<MAX_LOGS)log.info("[PDD-STRESS-LEDGER] phase=SEALED id={} Q={} absorbed={} direct={} plasticReservedUnused={} unused={} deferred={} nodes={} deadline={}",
                        job.id,entry.ledger().reservedQ(),entry.ledger().absorbedQ(),entry.ledger().directQ(),entry.ledger().plasticUnusedQ(),entry.ledger().unusedQ(),entry.ledger().pendingQ(),entry.plans().size(),r.createdGameTick()+r.policy().ttlTicks());
            }
            job.validation=null;
        }
        if(job.finished&&job.session!=null){job.session=null;activeSessions--;}
        entry=journal.get(job.id);
        int handoffs=0;
        while(job.deliveryIndex<entry.plans().size()&&handoffs++<QUANTUM&&used<workCap){
            charge();var plan=entry.plans().get(job.deliveryIndex);
            var key=StressCommitHandoff.keyFor(plan.node().cell(),plan.node().fingerprint());
            long event=r.origins().isEmpty()?r.createdGameTick():r.origins().getFirst().eventTick();
            var intent=new WorldCommitCore.Intent(applyId(job.id,plan.node().cell()),key,plan.node().incarnation(),
                    plan.incomingQ(),plan.node().material(),r.materialGeneration(),event,false,plan.node().materialRevision());
            boolean accepted=PlotKeyCodec.isPlotStorage(key)?plot!=null&&plot.offerStress(entry,plan,intent)
                    :world!=null&&world.offerStress(entry,plan,intent);
            if(!accepted)break;
            job.deliveryIndex++;
            if(TRACE&&shown++<MAX_LOGS)log.info("[PDD-STRESS-APPLY] phase=DURABLE_HANDOFF id={} origin={} owner={} position={},{},{} depth={} incomingQ={} fixedDamage={} status=QUEUED_OR_TERMINAL_NOT_ASSUMED_APPLIED",
                    intent.applyId(),job.id,key.dimension(),key.x(),key.y(),key.z(),plan.depth(),plan.incomingQ(),plan.plan().fixedIncrement());
        }
        if(entry.phase()==Phase.SEALED&&entry.ledger().finished()&&entry.ledger().pendingQ()==0&&job.deliveryIndex>=entry.plans().size()){
            charge();journal.acknowledgeHandoff(job.id);remove(job);
        }
    }
    private void remove(Job job){
        jobs.remove(job.id);if(job.session!=null)activeSessions--;
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
        order.clear();originJobs.clear();
    }
}

package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.physicaldestroydeform.stress.StressPropagation.*;
import static com.physicaldestroydeform.stress.StressReservationJournal.*;

public final class StressDurabilitySelfTest {
    private static final MaterialProperties M=new MaterialProperties(0,1024,0,0,false);
    private static final DamageModel.Settings D=DamageModel.Settings.defaults();
    private static void check(boolean b){if(!b)throw new AssertionError();}
    private static Node node(int x){return new Node(new Cell("WORLD:overworld:1",x,0,0),"stone","rev0",M);}
    private static Reservation reservation(String id){return new Reservation(id,7,100,Policy.defaults(),D,List.of(new Seed(node(0),100,new Direction(1,2,3))));}
    private static Graph graph(){return c->c.x()>=0&&c.x()<=1&&c.y()==0&&c.z()==0?Probe.solid(node(c.x())):Probe.empty();}
    public static void main(String[] args)throws Exception{
        roundTrip();duplicateAndChangedCredit();sealedHandoff();corruption();cancel();clock();origin();fairBudget();schedulerCapacity();schedulerFailure();
        excessiveRecords();
        System.out.println("StressDurabilitySelfTest: 11 scenarios passed");
    }
    static void roundTrip()throws Exception{
        Path path=Files.createTempDirectory("pdd-stress-reserve-");
        var j=new StressReservationJournal(path);var r=reservation("credit-1");check(j.reserve(r));
        var restored=new StressReservationJournal(path);check(restored.get(r.creditId()).reservation().equals(r));
        var uninterrupted=r.resumePlanning(100);var resumed=restored.get(r.creditId()).reservation().resumePlanning(101);
        uninterrupted.advance(graph(),101,512);resumed.advance(graph(),101,512);
        check(uninterrupted.plans().equals(resumed.plans()));check(uninterrupted.ledger().equals(resumed.ledger()));
    }
    static void duplicateAndChangedCredit()throws Exception{
        var j=new StressReservationJournal(Files.createTempDirectory("pdd-stress-duplicate-"));var r=reservation("same");
        check(j.reserve(r));check(!j.reserve(r));
        try{j.reserve(new Reservation(r.creditId(),8,r.createdGameTick(),r.policy(),r.settings(),r.seeds()));throw new AssertionError();}
        catch(IllegalArgumentException expected){}
        check(j.size()==1);
    }
    static void sealedHandoff()throws Exception{
        Path path=Files.createTempDirectory("pdd-stress-sealed-");var j=new StressReservationJournal(path);var r=reservation("seal");j.reserve(r);
        var s=r.resumePlanning(100);s.advance(graph(),100,512);j.seal(r.creditId(),s);
        var reboot=new StressReservationJournal(path);check(reboot.get(r.creditId()).phase()==Phase.SEALED);
        check(reboot.get(r.creditId()).plans().equals(s.plans()));check(reboot.get(r.creditId()).ledger().equals(s.ledger()));
        var id=applyId(r.creditId(),s.plans().getFirst().node().cell());
        check(id.equals(applyId(r.creditId(),reboot.get(r.creditId()).plans().getFirst().node().cell())));
        reboot.acknowledgeHandoff(r.creditId());check(new StressReservationJournal(path).get(r.creditId()).phase()==Phase.CLOSED);
        check(!reboot.reserve(r));check(reboot.unfinished().isEmpty());
    }
    static void corruption()throws Exception{
        var r=reservation("crc");byte[] bytes=encode(new Entry(r,Phase.RESERVED,List.of(),null,"RESERVED"));
        for(int pos:new int[]{0,4,bytes.length/2,bytes.length-1}){
            byte[] bad=bytes.clone();bad[pos]^=1;
            try{decode(bad);throw new AssertionError();}catch(IOException expected){}
        }
        try{decode(Arrays.copyOf(bytes,bytes.length-1));throw new AssertionError();}catch(IOException expected){}
    }
    static void excessiveRecords()throws Exception{
        Path path=Files.createTempDirectory("pdd-stress-directory-cap-");
        try {
            // Empty files deliberately make the first payload invalid. The count
            // cap must reject before attempting to decode any of those payloads.
            for(int i=0;i<=MAX_RECORDS;i++)Files.createFile(path.resolve(i+".psr"));
            try{new StressReservationJournal(path);throw new AssertionError("accepted excessive journal");}
            catch(IOException expected){check(expected.getMessage().equals("stress record count cap exceeded"));}
        }finally{
            try(var files=Files.list(path)){for(var p:files.toList())Files.delete(p);}
            Files.delete(path);
        }
    }
    static void cancel()throws Exception{
        Path path=Files.createTempDirectory("pdd-stress-cancel-");var j=new StressReservationJournal(path);var r=reservation("cancel");j.reserve(r);
        j.cancelUnplanned(r.creditId(),"OWNER_CHANGED_UNUSED");var e=new StressReservationJournal(path).get(r.creditId());
        check(e.phase()==Phase.CLOSED&&e.plans().isEmpty());check(e.ledger().unusedQ()==100);
        try{var s=r.resumePlanning(100);s.advance(graph(),100,512);j.seal(r.creditId(),s);throw new AssertionError();}catch(IllegalStateException expected){}
    }
    static void clock(){
        var r=reservation("clock");try{r.resumePlanning(99);throw new AssertionError();}catch(IllegalStateException expected){}
        var s=r.resumePlanning(1300);s.advance(c->Probe.unavailable(),1300,512);check(s.ledger().finished());check(s.ledger().unusedQ()==100);
    }
    static void origin()throws Exception{
        var j=new StressReservationJournal(Files.createTempDirectory("pdd-stress-origin-"));var r=reservation("origin");j.reserve(r);
        var unrelated=new Session(r.seeds(),r.policy(),r.settings(),101);unrelated.advance(graph(),101,512);
        try{j.seal(r.creditId(),unrelated);throw new AssertionError();}catch(IllegalArgumentException expected){}
    }
    static void fairBudget(){
        var scheduler=new StressScheduler();
        for(int i=0;i<80;i++){
            List<Seed> roots=new ArrayList<>();for(int k=0;k<40;k++)roots.add(new Seed(node(k),1,null));
            check(scheduler.submit("job"+i,new Session(roots,Policy.defaults(),D,100),c->c.y()==0&&c.z()==0&&c.x()>=0&&c.x()<40?Probe.solid(node(c.x())):Probe.empty())==StressScheduler.Admission.ACCEPTED);
        }
        // Every owner gets a turn; 80 independent jobs share the same 512 budget.
        for(long tick=100;tick<110;tick++){
            int used=scheduler.tick(tick);check(used<=512);check(scheduler.usedThisTick()<=512);
            scheduler.tick(tick);check(scheduler.usedThisTick()<=512);
        }
        check(scheduler.completed().size()==80);check(scheduler.queued()==0);
    }
    static void schedulerCapacity(){
        var scheduler=new StressScheduler();var r=reservation("capacity");
        for(int i=0;i<128;i++)check(scheduler.submit("id"+i,r.resumePlanning(100),c->Probe.unavailable())==StressScheduler.Admission.ACCEPTED);
        check(scheduler.submit("id0",r.resumePlanning(100),graph())==StressScheduler.Admission.DUPLICATE);
        check(scheduler.submit("overflow",r.resumePlanning(100),graph())==StressScheduler.Admission.CAPACITY);
        scheduler.tick(1300);check(scheduler.completed().size()==128);
        scheduler.acknowledge("id0");check(scheduler.submit("new",r.resumePlanning(1300),graph())==StressScheduler.Admission.ACCEPTED);
    }
    static void schedulerFailure(){
        var scheduler=new StressScheduler();var r=reservation("failure");
        scheduler.submit("bad",r.resumePlanning(100),c->{throw new IllegalStateException("read failure");});
        scheduler.submit("good",r.resumePlanning(100),graph());scheduler.tick(100);
        check(scheduler.completed().size()==2);check(scheduler.completed().get(0).failure()!=null);
        check(scheduler.completed().get(1).failure()==null);
    }
}

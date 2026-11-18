package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.physicaldestroydeform.stress.StressPropagation.*;
import static com.physicaldestroydeform.stress.StressReservationJournal.*;

public final class StressCoordinatorSelfTest {
    private static final MaterialProperties M=new MaterialProperties(0,1024,0,0,false);
    private static final DamageModel.Settings D=DamageModel.Settings.defaults();
    private static void check(boolean b){if(!b)throw new AssertionError();}
    private static Node node(int x){return new Node(new Cell("WORLD:overworld:1",x,0,0),"stone","rev0",M);}
    private static Reservation reserve(String id){return new Reservation(id,1,100,Policy.defaults(),D,List.of(new Seed(node(0),100,null)));}
    /** Real in-memory fake BACKEND, not fake Minecraft classes. Tests durable protocol only. */
    private static class Backend implements StressWorkCoordinator.Backend {
        final Map<String,PlannedNode> durable=new HashMap<>();int accepted,calls,graphCalls;boolean valid=true,blocked;
        @Override public Graph graph(Reservation r){graphCalls++;return c->c.y()==0&&c.z()==0&&c.x()>=0&&c.x()<=2?Probe.solid(node(c.x())):Probe.empty();}
        @Override public boolean validate(Reservation r,List<PlannedNode> p){return valid;}
        @Override public boolean enqueue(String id,Reservation r,PlannedNode p)throws IOException{
            calls++;if(blocked)return false;
            if(durable.putIfAbsent(id,p)==null)accepted++;return true;
        }
    }
    public static void main(String[] args)throws Exception{
        normalHandoff();replayAfterPartialHandoff();unsealedRestart();validationVeto();backpressure();failedSink();duplicateTick();
        System.out.println("StressCoordinatorSelfTest: 7 scenarios passed");
    }
    static void normalHandoff()throws Exception{
        Path path=Files.createTempDirectory("pdd-stress-coordinator-");var j=new StressReservationJournal(path);var b=new Backend();var c=new StressWorkCoordinator(j,b);
        check(c.submit(reserve("normal")));check(!c.submit(reserve("normal")));
        for(long t=100;t<110;t++)c.tick(t);
        check(b.accepted==2);check(c.queued()==0);check(j.get("normal").phase()==Phase.CLOSED);
        check(j.get("normal").ledger().reservedQ()==100);
    }
    static void replayAfterPartialHandoff()throws Exception{
        Path path=Files.createTempDirectory("pdd-stress-redelivery-");var j=new StressReservationJournal(path);var b=new Backend();var c=new StressWorkCoordinator(j,b);
        c.submit(reserve("partial"));c.tick(100);check(b.accepted==1);check(j.get("partial").phase()==Phase.SEALED);
        var reboot=new StressWorkCoordinator(new StressReservationJournal(path),b);
        for(long t=101;t<110;t++)reboot.tick(t);
        check(b.accepted==2);check(b.calls==3);check(b.graphCalls==1); // SEALED is never recalculated
        check(new StressReservationJournal(path).get("partial").phase()==Phase.CLOSED);
    }
    static void unsealedRestart()throws Exception{
        Path path=Files.createTempDirectory("pdd-stress-unsealed-");var j=new StressReservationJournal(path);var r=reserve("unsealed");j.reserve(r);
        var b=new Backend();var c=new StressWorkCoordinator(new StressReservationJournal(path),b);
        for(long t=101;t<110;t++)c.tick(t);
        check(b.accepted==2);check(j.get("unsealed").reservation().reservedQ()==100);
        check(new StressReservationJournal(path).get("unsealed").reservation().createdGameTick()==100);
    }
    static void validationVeto()throws Exception{
        var j=new StressReservationJournal(Files.createTempDirectory("pdd-stress-veto-"));var b=new Backend();b.valid=false;
        var c=new StressWorkCoordinator(j,b);c.submit(reserve("veto"));c.tick(100);
        check(b.calls==0);check(j.get("veto").ledger().unusedQ()==100);check(j.get("veto").phase()==Phase.CLOSED);
    }
    static void backpressure()throws Exception{
        var j=new StressReservationJournal(Files.createTempDirectory("pdd-stress-pressure-"));var b=new Backend();b.blocked=true;
        var c=new StressWorkCoordinator(j,b);c.submit(reserve("pressure"));c.tick(100);c.tick(101);
        check(j.get("pressure").phase()==Phase.SEALED);check(b.accepted==0);
        b.blocked=false;for(long t=102;t<110;t++)c.tick(t);
        check(b.accepted==2);check(j.get("pressure").phase()==Phase.CLOSED);
    }
    static void failedSink()throws Exception{
        Path path=Files.createTempDirectory("pdd-stress-io-");var j=new StressReservationJournal(path);
        var b=new Backend(){@Override public boolean enqueue(String id,Reservation r,PlannedNode p)throws IOException{throw new IOException("disk unavailable");}};
        var c=new StressWorkCoordinator(j,b);c.submit(reserve("io"));
        try{c.tick(100);throw new AssertionError();}catch(IOException expected){}
        check(c.faulted());check(j.get("io").phase()==Phase.SEALED);
        var recovered=new Backend();var reboot=new StressWorkCoordinator(new StressReservationJournal(path),recovered);
        for(long t=101;t<110;t++)reboot.tick(t);check(recovered.accepted==2);
    }
    static void duplicateTick()throws Exception{
        var j=new StressReservationJournal(Files.createTempDirectory("pdd-stress-clock-"));var b=new Backend();var c=new StressWorkCoordinator(j,b);
        c.submit(reserve("tick"));c.tick(100);int before=b.calls;
        var result=c.tick(100);check(b.calls==before);check(result.plannedOperations()==0);
    }
}

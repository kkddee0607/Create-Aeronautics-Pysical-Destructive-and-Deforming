package com.physicaldestroydeform.diagnostics;

import java.util.*;
import java.lang.ref.WeakReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Read-only diagnostic counters. No game objects, queues, I/O or authority retained.
 * Server trace is owned by the server thread; client packet trace has an independent budget. */
public final class RogTrace {
    public enum Stage { R01,R02,R03,R04,R05,R06,R07,R08,R09,R10,R11,R12,R13 }
    private static final Logger LOG=LoggerFactory.getLogger(RogTrace.class);
    private static final boolean ENABLED=LogSelection.any();
    private static final boolean CLIENT_ENABLED=ENABLED&&LogSelection.enabled(LogSelection.Channel.NETWORK);
    private static final int CAP=Math.max(0,Math.min(1024,Integer.getInteger("pdd.rog.maxPerTick",64)));
    private static final int INTERVAL=Math.max(20,Math.min(1200,Integer.getInteger("pdd.rog.summaryTicks",100)));
    private static final ThreadLocal<State> SERVER=new ThreadLocal<>();
    private static final int MAX_REASONS=64;
    private static final int CHAR_CAP=Math.max(0,Math.min(1_048_576,Integer.getInteger("pdd.rog.maxCharsPerTick",32768)));
    private static final int LINE_CAP=8192;
    private static final int SEED_LIMIT=Math.max(0,Math.min(4096,Integer.getInteger("pdd.rog.seedLimit",128)));
    private static final String TRACE_JOB=System.getProperty("pdd.rog.traceJob","");
    private static final long TRACE_EPISODE=Long.getLong("pdd.rog.traceEpisode",-1L);
    private RogTrace(){}

    private record HoldKey(String dimension,String owner,long generation) {}
    private static final class Hold {
        final String reason;final long since;long checks=1;
        Hold(String reason,long since){this.reason=reason;this.since=since;}
        String reason(){return reason;}long since(){return since;}
    }
    static final class Count {
        long seen,selected;
        long last=Long.MIN_VALUE;
        int inWindow;
    }
    /** Package-visible pure accounting, tested without Minecraft. */
    static final class State {
        final String session;
        final int cap,perStage,repeat,aggregateReserve;
        final long interval;
        final EnumMap<Stage,Map<String,Count>> counts=new EnumMap<>(Stage.class);
        final LinkedHashMap<HoldKey,Hold> holds=new LinkedHashMap<>(16,.75f,true);
        final int[] stageLines=new int[Stage.values().length];
        long tick=Long.MIN_VALUE,lastSummary=Long.MIN_VALUE;
        int lines,characters,legacyLines;
        final int[] legacyByChannel=new int[LogSelection.Channel.values().length];
        long loggingFailures,emittedLines,truncatedLines,characterSuppressed,legacySuppressed;
        Object dimension,runtimePair,pair;
        long nativeTick=-1,episode=-1;
        String stressJob;
        State(String session,int cap,int perStage,int repeat,long interval){this(session,cap,perStage,repeat,interval,0);}
        State(String session,int cap,int perStage,int repeat,long interval,int aggregateReserve){
            this.session=session;this.cap=cap;this.perStage=perStage;this.repeat=repeat;this.interval=interval;
            this.aggregateReserve=Math.max(0,Math.min(cap,aggregateReserve));
            for(var s:Stage.values())counts.put(s,new LinkedHashMap<>());
        }
        void begin(long next){if(tick!=next){tick=next;lines=0;characters=0;legacyLines=0;Arrays.fill(legacyByChannel,0);Arrays.fill(stageLines,0);clearContext();}}
        void clearContext(){dimension=null;runtimePair=null;pair=null;nativeTick=-1;episode=-1;stressJob=null;}
        private Count count(Stage s,String reason){
            var map=counts.get(s);
            var c=map.get(reason);
            if(c==null){
                // Reserve one slot for unknown/dynamic reason overflow; never retain unbounded input.
                String key=reason!=null&&reason.length()<=96&&map.size()<MAX_REASONS-1?reason:"OTHER_REASONS";
                c=map.computeIfAbsent(key,k->new Count());
            }
            c.seen++;return c;
        }
        boolean event(Stage s,String reason){
            var c=count(s,reason);
            if(lines>=cap)return false;
            c.selected++;lines++;return true;
        }
        boolean evidence(Stage s,String reason){
            var c=count(s,reason);
            if(lines>=cap-aggregateReserve||characters>=CHAR_CAP-CHAR_CAP/4)return false;
            c.selected++;lines++;return true;
        }
        boolean sample(Stage s,String reason){
            var c=count(s,reason);int stage=s.ordinal();
            if(lines>=cap-aggregateReserve||stageLines[stage]>=perStage||characters>=CHAR_CAP-CHAR_CAP/4)return false;
            if(c.last!=tick){
                if(repeat==1&&c.last!=Long.MIN_VALUE&&tick>=c.last&&tick-c.last<interval)return false;
                c.inWindow=0;
            }
            if(c.inWindow>=repeat)return false;
            c.last=tick;c.inWindow++;c.selected++;lines++;stageLines[stage]++;return true;
        }
        boolean legacy(LogSelection.Channel channel){
            int max=cap/4,perChannel=Math.max(1,max/3),i=channel.ordinal();
            if(legacyLines>=max||legacyByChannel[i]>=perChannel){legacySuppressed++;return false;}
            legacyLines++;legacyByChannel[i]++;return true;
        }
        boolean due(){return lastSummary==Long.MIN_VALUE||tick<lastSummary||tick-lastSummary>=interval;}
        long seen(Stage s,String reason){var c=counts.get(s).get(reason);return c==null?0:c.seen;}
        long selected(Stage s,String reason){var c=counts.get(s).get(reason);return c==null?0:c.selected;}
        String summary(Stage s){
            var out=new StringJoiner(",","{","}");
            counts.get(s).forEach((r,c)->out.add(r+":"+c.seen+"/"+c.selected+"/"+(c.seen-c.selected)));
            return out.toString();
        }
    }
    public static void start(){
        if(!ENABLED)return;
        try{
            var s=new State(UUID.randomUUID().toString(),CAP,Math.min(CAP,Math.min(32,Math.max(1,CAP/Stage.values().length))),1,INTERVAL,CAP==0?0:Math.max(1,CAP/4));
            SERVER.set(s);s.begin(0);
            if(LogSelection.enabled(LogSelection.Channel.PERF))LOG.info("[PDD-ROG-R13] phase=START session={} cap={} aggregateReserve={} perStage={} repeat={} summaryTicks={} mode={} charCap={} seedLimit={} traceEpisode={} authority=NONE",s.session,CAP,s.aggregateReserve,s.perStage,s.repeat,INTERVAL,DiagnosticMode.current(),CHAR_CAP,SEED_LIMIT,TRACE_EPISODE);
        }catch(RuntimeException|LinkageError ignored){SERVER.remove();}
    }
    public static void begin(long tick){if(ENABLED){var s=SERVER.get();if(s!=null)s.begin(tick);}}
    public static void context(Object dimension,long nativeTick,Object runtimePair,Object pair){
        if(!ENABLED)return;var s=SERVER.get();if(s!=null){s.dimension=dimension;s.nativeTick=nativeTick;s.runtimePair=runtimePair;s.pair=pair;s.episode=-1;}
    }
    public static void episode(long episode){if(ENABLED){var s=SERVER.get();if(s!=null)s.episode=episode;}}
    public static void clearContext(){if(ENABLED){var s=SERVER.get();if(s!=null)s.clearContext();}}
    public static void plotGate(String dimension,String owner,long generation,String reason,long tick,Object pending,boolean forceLive){
        if(!ENABLED||!LogSelection.enabled(LogSelection.Channel.COMMIT))return;
        try{
            var s=SERVER.get();if(s==null)return;
            var key=new HoldKey(dimension,owner,generation);
            var old=s.holds.get(key);
            if(reason==null){
                if(old==null)return;s.holds.remove(key);
                if(s.sample(Stage.R09,"HOLD_CLEARED"))emit(s,Stage.R09,"HOLD_CLEARED","owner={} previous={} since={} durationTicks={} observedTick={} forceLive={} holdChecks={}",key,old.reason(),old.since(),Math.max(0,tick-old.since()),tick,forceLive,old.checks);
            }else if(old==null||!reason.equals(old.reason())){
                if(old==null&&s.holds.size()>=256){s.holds.pollFirstEntry();if(s.sample(Stage.R09,"HOLD_TRACKING_EVICTED"))emit(s,Stage.R09,"HOLD_TRACKING_EVICTED","limit=256 durationForEvictedOwner=UNKNOWN");}
                s.holds.put(key,new Hold(reason,tick));
                if(s.sample(Stage.R09,"HOLD_TRANSITION"))emit(s,Stage.R09,"HOLD_TRANSITION","owner={} previous={} new={} previousSince={} previousDuration={} observedTick={} pending={} forceLive={}",key,old==null?"UNTRACKED":old.reason(),reason,old==null?-1:old.since(),old==null?-1:Math.max(0,tick-old.since()),tick,pending,forceLive);
            }else old.checks++;
        }catch(RuntimeException|LinkageError ignored){}
    }
    /** One compact aggregate per already bounded authorization audit or credit.
     * Never use for per-cell or per-edge loops. */
    public static boolean enabled(){return ENABLED&&SERVER.get()!=null;}
    /** Ordinary legacy INFO is separate from the protected contact/ledger evidence budget. */
    public static boolean legacySlot(LogSelection.Channel channel){
        if(!LogSelection.enabled(channel))return false;
        var s=SERVER.get();return s==null||s.legacy(channel);
    }
    public static void stressJob(String id){if(ENABLED){var s=SERVER.get();if(s!=null)s.stressJob=id;}}
    public static boolean eventSlot(Stage stage,String reason){
        if(!ENABLED||!LogSelection.reason(stage,reason))return false;
        try{var s=SERVER.get();return s!=null&&s.event(stage,reason)&&LOG.isInfoEnabled();}
        catch(RuntimeException|LinkageError ignored){return false;}
    }
    public static boolean evidenceSlot(Stage stage,String reason){
        if(!ENABLED||!LogSelection.reason(stage,reason))return false;
        try{var s=SERVER.get();return s!=null&&s.evidence(stage,reason)&&LOG.isInfoEnabled();}
        catch(RuntimeException|LinkageError ignored){return false;}
    }
    public static void event(Stage stage,String reason,String format,Object... args){
        if(eventSlot(stage,reason))detail(stage,reason,format,args);
    }
    /** Only controls detailed evidence output, never physics or ledger state. */
    public static boolean traceSelected(){
        if(!ENABLED)return false;
        var s=SERVER.get();return s!=null&&(TRACE_EPISODE<0||s.episode==TRACE_EPISODE);
    }
    public static boolean stageEnabled(Stage stage){return enabled()&&LogSelection.stage(stage);}
    public static boolean rawSelected(){return traceSelected()&&(DiagnosticMode.trace()||TRACE_EPISODE>=0)&&stageEnabled(Stage.R01);}
    public static int seedLimit(){return SEED_LIMIT;}
    public static boolean sample(Stage stage,String reason){
        if(!ENABLED||!LogSelection.reason(stage,reason))return false;
        try{var s=SERVER.get();if(s==null)return false;
            boolean selectedJob=stage==Stage.R11&&!TRACE_JOB.isBlank()&&TRACE_JOB.equals(s.stressJob);
            if((!selectedJob&&!DiagnosticMode.trace()&&(TRACE_EPISODE<0||s.episode!=TRACE_EPISODE))||stage==Stage.R11&&!TRACE_JOB.isBlank()&&!selectedJob||s.lines>=Math.max(0,(s.cap-s.aggregateReserve)/2)||s.characters>=CHAR_CAP/2){s.count(stage,reason);return false;}
            return s.sample(stage,reason)&&LOG.isInfoEnabled();}
        catch(RuntimeException|LinkageError ignored){return false;}
    }
    public static boolean periodic(Stage stage,String reason){
        if(!ENABLED||!LogSelection.reason(stage,reason))return false;
        try{var s=SERVER.get();return s!=null&&s.sample(stage,reason)&&LOG.isInfoEnabled();}
        catch(RuntimeException|LinkageError ignored){return false;}
    }
    public static void detail(Stage stage,String reason,String format,Object... args){
        try{var s=SERVER.get();if(s==null||!LogSelection.reason(stage,reason))return;emit(s,stage,reason,format,args);}
        catch(RuntimeException|LinkageError ignored){/* diagnostic failure never changes physics */}
    }
    private static void emit(State s,Stage stage,String reason,String format,Object... args){
        try{
            Object[] values=new Object[7+args.length];values[0]=s.session;values[1]=s.tick;values[2]=s.dimension;values[3]=s.nativeTick;values[4]=s.runtimePair;values[5]=s.pair;values[6]=s.episode;
            System.arraycopy(args,0,values,7,args.length);
            // SLF4J is already a dependency. Bound serialized diagnostic text as
            // well as line slots; summaries disclose every suppressed/truncated line.
            if(s.characters>=CHAR_CAP){s.characterSuppressed++;return;}
            String message=org.slf4j.helpers.MessageFormatter.arrayFormat(
                    "[PDD-ROG-"+stage+"] reason="+reason+" session={} windowTick={} dim={} nativeTick={} runtimePair={} pair={} episode={} "+format,values).getMessage();
            int available=Math.min(LINE_CAP,CHAR_CAP-s.characters);
            if(message.length()>available){
                String suffix=" truncated=true";
                if(available<256){s.characterSuppressed++;return;}
                message=message.substring(0,available-suffix.length())+suffix;s.truncatedLines++;
            }
            s.characters+=message.length();LOG.info("{}",message);s.emittedLines++;
        }catch(RuntimeException|LinkageError ignored){s.loggingFailures++;}
    }
    private static void report(State s,String phase){
        try{
            for(var stage:Stage.values())if(!s.counts.get(stage).isEmpty())
                LOG.info("[PDD-ROG-{}] phase={} session={} windowTick={} counts=seen/selected/suppressed {} loggingFailures={} authority=NONE",stage,phase,s.session,s.tick,s.summary(stage),s.loggingFailures);
            if(LogSelection.enabled(LogSelection.Channel.PERF)){
            long seen=0,selected=0;
            for(var map:s.counts.values())for(var count:map.values()){seen+=count.seen;selected+=count.selected;}
            LOG.info("[PDD-ROG-R13] reason=DIAGNOSTIC_COVERAGE_SUMMARY phase={} session={} windowTick={} seen={} selected={} suppressed={} emitted={} characterSuppressed={} truncatedLines={} loggingFailures={} legacySuppressed={} authority=NONE",phase,s.session,s.tick,seen,selected,seen-selected,s.emittedLines,s.characterSuppressed,s.truncatedLines,s.loggingFailures,s.legacySuppressed);
            }
            s.lastSummary=s.tick;
        }catch(RuntimeException|LinkageError ignored){s.loggingFailures++;}
    }
    public static void endTick(){if(ENABLED){var s=SERVER.get();if(s!=null){s.clearContext();if(s.due())report(s,"SUMMARY");}}}
    public static void stop(){if(ENABLED){var s=SERVER.get();try{if(s!=null)report(s,"STOP");}finally{SERVER.remove();}}}

    // Separate wall-time window for client packets, even on a dedicated server JVM with no client classes.
    // Fixed state, never retains a level or packet. Sequence is observation order, not wire order.
    private static State client;
    private static long clientSequence;
    private static WeakReference<Object> clientLevel=new WeakReference<>(null);
    private static boolean clientLevelKnown;
    public static boolean clientEnabled(){return CLIENT_ENABLED;}
    public static synchronized void client(String reason,String format,Object... args){
        if(!clientEnabled())return;
        try{
            long window=System.nanoTime()/1_000_000_000L;
            if(client==null)client=new State("CLIENT-"+UUID.randomUUID(),Math.min(CAP,32),Math.min(CAP,32),DiagnosticMode.debug()?8:1,10);
            client.begin(window);
            clientSequence++;
            if(client.sample(Stage.R12,reason)){
                Object[] fields=new Object[args.length+1];fields[0]=clientSequence;System.arraycopy(args,0,fields,1,args.length);
                emit(client,Stage.R12,reason,"observationSequence={} "+format,fields);
            }
            if(client.due())report(client,"CLIENT_SUMMARY");
        }catch(RuntimeException|LinkageError ignored){}
    }
    public static synchronized void clientLevel(Object level){
        if(!clientEnabled())return;
        if(clientLevel.get()!=level){
            // First snapshot can follow tracking packets: keep that prefix.
            if(clientLevelKnown)clientReset();
            clientLevel=new WeakReference<>(level);clientLevelKnown=true;
        }
    }
    public static synchronized void clientReset(){if(client!=null){report(client,"CLIENT_STOP");client=null;clientSequence=0;}clientLevel.clear();clientLevelKnown=false;}
}

package com.physicaldestroydeform.diagnostics;

import java.util.EnumSet;
import java.util.Locale;

/** Startup-only presentation policy. No effect on simulation or safety errors. */
public final class LogSelection {
    public enum Channel { NATIVE, CONTACT, FORCE, DAMAGE, STRESS, DEFORM, COMMIT, COPYCAT, LINEAGE, STORAGE, NETWORK, PERF }
    private static final EnumSet<Channel> ACTIVE=configured();
    private LogSelection(){}
    static EnumSet<Channel> parse(String raw){
        var set=EnumSet.noneOf(Channel.class);
        if(raw==null)return set;
        for(String token:raw.split(",")){
            String value=token.trim().toUpperCase(Locale.ROOT);
            if(value.equals("OFF"))return EnumSet.noneOf(Channel.class);
            if(value.equals("ALL")){set.addAll(EnumSet.allOf(Channel.class));continue;}
            try{set.add(Channel.valueOf(value));}catch(IllegalArgumentException ignored){}
        }
        return set;
    }
    private static EnumSet<Channel> configured(){
        return configured(System.getProperty("pdd.logs"),Boolean::getBoolean);
    }
    static EnumSet<Channel> configured(String raw,java.util.function.Predicate<String> flag){
        if(raw!=null)return parse(raw);
        if(flag.test("pdd.rog.enabled"))return EnumSet.allOf(Channel.class);
        var result=EnumSet.noneOf(Channel.class);
        if(flag.test("pdd.f.trace.enabled"))result.add(Channel.FORCE);
        if(flag.test("pdd.verf.enabled")){result.add(Channel.CONTACT);result.add(Channel.NATIVE);}
        if(flag.test("pdd.deform.trace")||flag.test("pdd.deform.verbose"))result.add(Channel.DEFORM);
        if(flag.test("pdd.stress.trace"))result.add(Channel.STRESS);
        if(flag.test("pdd.copycat.trace"))result.add(Channel.COPYCAT);
        if(flag.test("pdd.persistence.traceRecovery"))result.add(Channel.STORAGE);
        if(flag.test("pdd.world.verbose"))result.add(Channel.COMMIT);
        if(flag.test("pdd.traceSublevelPackets"))result.add(Channel.NETWORK);
        return result;
    }
    public static boolean enabled(Channel channel){return ACTIVE.contains(channel);}
    public static boolean any(){return !ACTIVE.isEmpty();}
    public static String summary(){return ACTIVE.toString();}
    static boolean reason(RogTrace.Stage stage,String reason){return enabled(channelFor(stage,reason));}
    static Channel channelFor(RogTrace.Stage stage,String reason){
        if(stage==RogTrace.Stage.R07)return reason!=null&&reason.startsWith("COPYCAT_")?Channel.COPYCAT:Channel.DAMAGE;
        if(stage==RogTrace.Stage.R09||stage==RogTrace.Stage.R10){
            if(reason!=null&&reason.startsWith("LINEAGE_"))return Channel.LINEAGE;
            if(reason!=null&&(reason.startsWith("RECOVERY_")||reason.startsWith("WAL_")))return Channel.STORAGE;
        }
        return defaultChannel(stage);
    }
    public static boolean stage(RogTrace.Stage stage){return enabled(defaultChannel(stage))||stage==RogTrace.Stage.R07&&enabled(Channel.COPYCAT)
            ||(stage==RogTrace.Stage.R09||stage==RogTrace.Stage.R10)&&(enabled(Channel.LINEAGE)||enabled(Channel.STORAGE));}
    private static Channel defaultChannel(RogTrace.Stage stage){return switch(stage){
        case R01,R03 -> Channel.NATIVE;case R02,R04,R05 -> Channel.CONTACT;
        case R06 -> Channel.FORCE;case R07 -> Channel.DAMAGE;case R08 -> Channel.DEFORM;
        case R09,R10 -> Channel.COMMIT;case R11 -> Channel.STRESS;case R12 -> Channel.NETWORK;case R13 -> Channel.PERF;
    };}
}

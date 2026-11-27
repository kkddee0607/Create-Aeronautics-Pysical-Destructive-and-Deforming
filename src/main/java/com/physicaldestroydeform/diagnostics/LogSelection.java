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
        String raw=System.getProperty("pdd.logs");
        if(raw!=null)return parse(raw);
        if(Boolean.getBoolean("pdd.rog.enabled"))return EnumSet.allOf(Channel.class);
        var result=EnumSet.noneOf(Channel.class);
        if(Boolean.getBoolean("pdd.f.trace.enabled"))result.add(Channel.FORCE);
        if(Boolean.getBoolean("pdd.verf.enabled")){result.add(Channel.CONTACT);result.add(Channel.NATIVE);}
        if(Boolean.getBoolean("pdd.deform.trace")||Boolean.getBoolean("pdd.deform.verbose"))result.add(Channel.DEFORM);
        if(Boolean.getBoolean("pdd.stress.trace"))result.add(Channel.STRESS);
        if(Boolean.getBoolean("pdd.copycat.trace"))result.add(Channel.COPYCAT);
        if(Boolean.getBoolean("pdd.persistence.traceRecovery"))result.add(Channel.STORAGE);
        if(Boolean.getBoolean("pdd.world.verbose"))result.add(Channel.COMMIT);
        if(Boolean.getBoolean("pdd.traceSublevelPackets"))result.add(Channel.NETWORK);
        return result;
    }
    public static boolean enabled(Channel channel){return ACTIVE.contains(channel);}
    public static boolean any(){return !ACTIVE.isEmpty();}
    public static String summary(){return ACTIVE.toString();}
    public static boolean stage(RogTrace.Stage stage){return enabled(switch(stage){
        case R01,R03 -> Channel.NATIVE;case R02,R04,R05 -> Channel.CONTACT;
        case R06 -> Channel.FORCE;case R07 -> Channel.DAMAGE;case R08 -> Channel.DEFORM;
        case R09,R10 -> Channel.COMMIT;case R11 -> Channel.STRESS;case R12 -> Channel.NETWORK;case R13 -> Channel.PERF;
    });}
}

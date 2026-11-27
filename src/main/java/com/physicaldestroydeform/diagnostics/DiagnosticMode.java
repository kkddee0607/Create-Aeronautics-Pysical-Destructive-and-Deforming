package com.physicaldestroydeform.diagnostics;

import java.util.Locale;

/** Presentation only: never use this setting to authorize mutations or credit. */
public enum DiagnosticMode {
    NORMAL, DEBUG, TRACE;

    private static final DiagnosticMode MODE=parse(System.getProperty("pdd.diagnostics.mode"));
    public static DiagnosticMode current(){return MODE;}
    public static boolean debug(){return LogSelection.any()&&(MODE!=NORMAL||Boolean.getBoolean("pdd.diagnostics.fullTrace"));}
    public static boolean trace(){return LogSelection.any()&&(MODE==TRACE||Boolean.getBoolean("pdd.diagnostics.fullTrace"));}
    static DiagnosticMode parse(String value){
        if(value==null)return NORMAL;
        try{return valueOf(value.trim().toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException invalid){return NORMAL;}
    }
}

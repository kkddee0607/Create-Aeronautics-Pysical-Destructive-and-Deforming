package com.physicaldestroydeform.plot;

/** Pure timing policy; a tick number alone is not evidence that Sable ran. */
public final class PlotBreakPacing {
    private PlotBreakPacing() {}
    public record Attempt(long tick,boolean confirmed) {}

    public static int spacing(int configured) {
        return Math.max(1,Math.min(40,configured));
    }

    public static String holdReason(long tick,long completedHeatMapTick,Attempt previousAttempt,int spacing) {
        if(tick<0 || completedHeatMapTick!=tick) return "SABLE_HEATMAP_TICK_UNCONFIRMED";
        // Preserve the safeFix gap after an uncertain native mutation; never
        // treat API acknowledgement alone as evidence of a confirmed break.
        if(previousAttempt!=null && (tick<=previousAttempt.tick()
                || tick-previousAttempt.tick()<Math.max(spacing,previousAttempt.confirmed()?1:3)))
            return "PLOT_BREAK_SPACING";
        return null;
    }
}

package com.physicaldestroydeform.diagnostics;

/** Log-only sampling; never affects queue iteration, numerical budgets or mutation. */
public final class TraceBudget {
    private TraceBudget() {}
    public static int cap(int normal, int detailed) {
        return Boolean.getBoolean("pdd.diagnostics.fullTrace") ? detailed : normal;
    }
}

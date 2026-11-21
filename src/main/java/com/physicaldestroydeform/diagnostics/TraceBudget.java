package com.physicaldestroydeform.diagnostics;

import java.util.List;

/** Log-only sampling; never affects queue iteration, numerical budgets or mutation. */
public final class TraceBudget {
    private TraceBudget() {}
    public static int cap(int normal, int detailed) {
        return Boolean.getBoolean("pdd.diagnostics.fullTrace") ? detailed : normal;
    }
    public static <T> List<T> sample(List<T> values,int limit){
        return values.subList(0,Math.min(values.size(),Math.max(0,limit)));
    }
}

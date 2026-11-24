package com.physicaldestroydeform.collision;

import java.util.*;

/** Diagnostic only: retain the largest unresolved budgets, never authorize F/Q. */
final class ContactBudgetTop {
    static final int MAX_SAMPLES=4;
    record Sample(String credit,double inputF,double unresolvedF,String reasonA,String reasonB,
                  boolean nativeCap,String regionStatus) {
        double ratio(){return inputF>0?unresolvedF/inputF:0;}
    }
    private final PriorityQueue<Sample> largest=new PriorityQueue<>(Comparator.comparingDouble(Sample::unresolvedF));
    void consider(Sample sample){
        if(!(Double.isFinite(sample.inputF())&&sample.inputF()>0
                &&Double.isFinite(sample.unresolvedF())&&sample.unresolvedF()>0))return;
        if(sample.ratio()<0.10 && sample.unresolvedF()<10_000.0)return;
        if(largest.size()<MAX_SAMPLES)largest.add(sample);
        else if(sample.unresolvedF()>largest.peek().unresolvedF()){
            largest.remove();largest.add(sample);
        }
    }
    List<Sample> samples(){
        var result=new ArrayList<>(largest);
        result.sort(Comparator.comparingDouble(Sample::unresolvedF).reversed());
        return List.copyOf(result);
    }
}

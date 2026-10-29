package com.physicaldestroydeform.diagnostics;

import java.util.concurrent.atomic.LongAdder;

/** A bounded amount of evidence, rather than unbounded per-impact INFO logs. */
public final class CollisionAcceptanceMetrics implements RawObservationConsumer {
    private volatile RawObservationConsumer downstream = ignored -> {};
    public void downstream(RawObservationConsumer consumer) { downstream = java.util.Objects.requireNonNull(consumer); }
    private final LongAdder ordinary = new LongAdder();
    private final LongAdder original = new LongAdder();
    private final LongAdder nativeCallbacks = new LongAdder();
    private final LongAdder directCallbacks = new LongAdder();
    private final LongAdder stone = new LongAdder();
    private final LongAdder wood = new LongAdder();
    private final LongAdder fragile = new LongAdder();
    private final LongAdder belt = new LongAdder();
    private final LongAdder tnt = new LongAdder();

    @Override public void accept(RawObservation v) {
        if (v.hadOriginal()) original.increment(); else ordinary.increment();
        if (RawObservation.SOURCE_NATIVE_CALLBACK.equals(v.source())) nativeCallbacks.increment();
        else if (RawObservation.SOURCE_DIRECT_CALLBACK.equals(v.source())) directCallbacks.increment();
        if (v.blockId().contains("stone") || v.blockId().contains("cobblestone")) stone.increment();
        if (v.blockId().contains("planks") || v.blockId().contains("log")) wood.increment();
        if (v.callbackType().contains("Fragile")) fragile.increment();
        if (v.callbackType().contains("Belt")) belt.increment();
        if (v.callbackType().contains("Explosive") || v.blockId().endsWith(":tnt")) tnt.increment();
        downstream.accept(v);
    }
    public Snapshot snapshot() {
        return new Snapshot(ordinary.sum(), original.sum(), nativeCallbacks.sum(), directCallbacks.sum(),
                stone.sum(), wood.sum(), fragile.sum(), belt.sum(), tnt.sum());
    }
    public record Snapshot(long ordinary, long original, long nativeCallbacks, long directCallbacks,
                           long stone, long wood, long fragile, long belt, long tnt) {}
}

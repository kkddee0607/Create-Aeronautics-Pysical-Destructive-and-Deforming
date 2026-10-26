package com.physicaldestroydeform.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Bounded, single-threaded synthetic diagnostic model retained from 0.2.0. */
public final class ObservationBuffer {
    private final int capacity;
    private final List<CollisionObservation> observations;
    private long droppedCount;
    public ObservationBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        observations = new ArrayList<>(Math.min(capacity, 256));
    }
    public boolean record(CollisionObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (observations.size() >= capacity) { droppedCount++; return false; }
        observations.add(observation); return true;
    }
    public List<CollisionObservation> snapshot() { return Collections.unmodifiableList(new ArrayList<>(observations)); }
    public int size() { return observations.size(); }
    public int capacity() { return capacity; }
    public long droppedCount() { return droppedCount; }
    public void drain() { observations.clear(); droppedCount = 0; }
}

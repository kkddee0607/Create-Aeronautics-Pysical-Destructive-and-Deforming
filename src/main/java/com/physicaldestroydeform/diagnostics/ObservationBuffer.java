package com.physicaldestroydeform.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, API-independent buffer for diagnostics captured by a future verified adapter.
 *
 * <p>The buffer performs no world access and never chooses or alters a Sable CollisionResult.
 * When capacity is reached, new observations are rejected and counted instead of growing
 * memory without bound or silently evicting evidence.</p>
 */
public final class ObservationBuffer {
    private final int capacity;
    private final List<CollisionObservation> observations;
    private long droppedCount;

    public ObservationBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.observations = new ArrayList<>(Math.min(capacity, 256));
    }

    public boolean record(CollisionObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (observations.size() >= capacity) {
            droppedCount++;
            return false;
        }
        observations.add(observation);
        return true;
    }

    public List<CollisionObservation> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(observations));
    }

    public int size() { return observations.size(); }
    public int capacity() { return capacity; }
    public long droppedCount() { return droppedCount; }

    public void drain() {
        observations.clear();
        droppedCount = 0;
    }
}

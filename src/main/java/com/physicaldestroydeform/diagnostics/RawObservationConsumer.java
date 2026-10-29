package com.physicaldestroydeform.diagnostics;

/** Stable 0.2.13 input port. Consumers may not mutate the physics callback result. */
@FunctionalInterface
public interface RawObservationConsumer {
    void accept(RawObservation observation);
}

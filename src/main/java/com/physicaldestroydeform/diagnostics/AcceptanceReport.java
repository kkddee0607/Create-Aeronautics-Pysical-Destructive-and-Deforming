package com.physicaldestroydeform.diagnostics;

import org.slf4j.Logger;

/** One structured summary per interval; no log line per collision in normal operation. */
public final class AcceptanceReport {
    private final CollisionAcceptanceMetrics counts = new CollisionAcceptanceMetrics();
    private long previousTotal;
    private long previousDrop;
    private long previousErrors;
    public AcceptanceReport() { RawObservationDispatcher.setConsumer(counts); }
    public CollisionAcceptanceMetrics counts() { return counts; }
    public void log(Logger logger) {
        var s = counts.snapshot();
        long total = SableContactQueue.total();
        long dropped = SableContactQueue.dropped();
        long errors = SableContactQueue.captureErrors();
        logger.info("[PDD-G-COL-01] rawDelta={} rawTotal={} rawDroppedDelta={} rawDroppedTotal={} captureErrorsDelta={} " +
                "original={} ordinary={} nativeCallbacks={} directCallbacks={} stone={} wood={} fragile={} belt={} tnt={} " +
                "wrappedWithOriginal={} wrappedNull={} queueSize={} status=RUNTIME_EVIDENCE_ONLY",
                total-previousTotal, total, dropped-previousDrop, dropped, errors-previousErrors,
                s.original(), s.ordinary(), s.nativeCallbacks(), s.directCallbacks(),
                s.stone(), s.wood(), s.fragile(), s.belt(), s.tnt(),
                SableContactQueue.wrappedOriginal(), SableContactQueue.wrappedNull(), SableContactQueue.size());
        previousTotal = total; previousDrop = dropped; previousErrors = errors;
    }
    public void close() { RawObservationDispatcher.reset(); }
}

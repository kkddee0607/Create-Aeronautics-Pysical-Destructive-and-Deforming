package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.world.WorldCommitCore;
import com.physicaldestroydeform.world.WorldPersistentJournal;
import com.physicaldestroydeform.world.WorldReceiptJournal;
import com.physicaldestroydeform.state.SparseStateKey;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.physicaldestroydeform.stress.StressPropagation.*;
import static com.physicaldestroydeform.stress.StressReservationJournal.*;
import static com.physicaldestroydeform.stress.StressRuntimeProtocolTest.*;
import static org.junit.jupiter.api.Assertions.*;

class StressClockRollbackTest {
    @TempDir Path path;

    @Test void savedFutureReservationStaysCancelledAfterClockCatchesUp() throws Exception {
        var journal = new StressReservationJournal(path);
        var reserved = reservation("reserved", Policy.defaults());
        journal.reserve(reserved);
        journal.closeForClockRollback(reserved.creditId());
        var saved = new StressReservationJournal(path).get(reserved.creditId());
        assertTrue(saved.clockRolledBack());
        assertEquals(Phase.CLOSED, saved.phase());
        assertEquals(reserved, saved.reservation());
        assertEquals(100, saved.ledger().unusedQ(), 1e-12);
        assertEquals(0, saved.ledger().pendingQ(), 1e-12);
    }

    @Test void sealedOutputAndOriginAreAbortedUnderTheirOriginalIds() throws Exception {
        var source = new WorldCommitCore.Intent("direct-original", key(0), "rev0", 400, MATERIAL, 1, 90, true);
        var r = new Reservation("future", 1, 100, new Policy(1,128,512,1.0/256,1200), SETTINGS,
                List.of(new Seed(node(0),100,null)), List.of(source));
        var stressPath = path.resolve("stress");
        var journal = new StressReservationJournal(stressPath);
        journal.reserve(r);
        var session = new Session(r.seeds(), r.policy(), r.settings(), 100, true);
        session.advance(line(3), 100, 512);
        journal.sealProgress(r.creditId(), session, Map.of());
        var plan = session.plans().getFirst();
        var derived = intent(r, plan);
        var commit = commitJournal();
        var original = new WorldCommitCore(commit, SETTINGS, 64);
        original.submitSealed(source, SETTINGS, 100, 1300);
        assertThrows(IllegalArgumentException.class,
                () -> StressCommitHandoff.offer(original, journal.get(r.creditId()), plan, derived, 99));

        journal.closeForClockRollback(r.creditId());
        var reopened = new StressReservationJournal(stressPath);
        var rolledBack = reopened.get(r.creditId());
        assertTrue(rolledBack.clockRolledBack());
        assertEquals(r, rolledBack.reservation());
        assertEquals(List.of(plan), rolledBack.plans());
        assertEquals(60, rolledBack.ledger().directQ(), 1e-12);
        assertEquals(15, rolledBack.ledger().plasticUnusedQ(), 1e-12);
        assertEquals(25, rolledBack.ledger().unusedQ(), 1e-12);
        assertEquals(100, rolledBack.ledger().accountedQ(), 1e-12);

        var restoredCommit = commitJournal();
        var core = new WorldCommitCore(restoredCommit, SETTINGS, 64);
        core.setRollbackHold(i -> i.applyId().equals(source.applyId()));
        var valid = new WorldCommitCore.Intent("independent", key(40), "rev0", 1, MATERIAL, 1, 4, false);
        core.submitSealed(valid, SETTINGS, 99, 1299);
        var reads = new ArrayList<SparseStateKey>();
        var results = core.tickAtGameTime(gateway(reads), 4, 101);
        assertEquals(List.of(valid.key()), reads);
        assertEquals("STRESS_CLOCK_ROLLBACK_HOLD", results.getFirst().reason());

        core.abortSealedOnRollback(source, SETTINGS, 1300, 4);
        core.abortSealedOnRollback(source, SETTINGS, 1300, 5);
        assertTrue(StressCommitHandoff.cancelOnRollback(core, rolledBack, plan, derived, 5, 0));
        assertThrows(IllegalArgumentException.class,
                () -> StressCommitHandoff.offer(core, rolledBack, plan, derived, 101));
        reopened.acknowledgeHandoff(r.creditId());
        assertTrue(reopened.get(r.creditId()).clockRolledBack());
        reopened.compactHandoff(r.creditId());
        assertTrue(new StressReservationJournal(stressPath).isArchived(r.creditId()));
        var savedCommit = commitJournal();
        for (var i : List.of(source, derived)) {
            var receipt = savedCommit.receipt(i.applyId());
            assertEquals(WorldReceiptJournal.Status.ABORTED, receipt.status());
            assertEquals("STRESS_CLOCK_ROLLBACK_UNUSED", receipt.reason());
            assertNull(receipt.pending());
        }
        assertEquals(0, savedCommit.size());
        assertEquals(1, new WorldCommitCore(savedCommit, SETTINGS, 64).queued());
    }

    @Test void closedMarkerPreservesPreparedAndRejectsMismatchedPendingDeadline() throws Exception {
        var journal = new StressReservationJournal(path.resolve("stress"));
        var r = reservation("closed", Policy.defaults());
        journal.reserve(r);
        journal.cancelUnplanned(r.creditId(), "ORIGINAL_TTL_UNUSED");
        var before = journal.get(r.creditId());
        journal.closeForClockRollback(r.creditId());
        var after = journal.get(r.creditId());
        assertTrue(after.clockRolledBack());
        assertEquals(before.phase(), after.phase());
        assertEquals(before.ledger(), after.ledger());

        var commit = commitJournal();
        var core = new WorldCommitCore(commit, SETTINGS, 64);
        for (var status : List.of(WorldReceiptJournal.Status.PREPARED, WorldReceiptJournal.Status.APPLIED,
                WorldReceiptJournal.Status.ABORTED, WorldReceiptJournal.Status.PARTIAL_QUARANTINED)) {
            var settled = new WorldCommitCore.Intent("settled-" + status, key(0), "rev0", 400, MATERIAL, 1, 90, true);
            var receipt = new WorldReceiptJournal.Receipt(settled.applyId(), settled.key(), status, "EXISTING", 90);
            commit.transition(receipt, null);
            core.abortSealedOnRollback(settled, SETTINGS, 1300, 4);
            assertEquals(receipt, commit.receipt(settled.applyId()));
        }

        var mismatch = new WorldCommitCore.Intent("different-deadline", key(1), "rev0", 400, MATERIAL, 1, 90, true);
        core.submitSealed(mismatch, SETTINGS, 100, 1301);
        assertThrows(IOException.class, () -> core.abortSealedOnRollback(mismatch, SETTINGS, 1300, 4));
        assertEquals(WorldReceiptJournal.Status.DEFERRED, commit.receipt(mismatch.applyId()).status());
        assertEquals(1301, commit.receipt(mismatch.applyId()).pending().expiresGameTick());
    }

    @Test void archivedPendingUsesBootClockAndOriginalProductionTtl() throws Exception {
        var stress = new StressReservationJournal(path.resolve("stress"));
        var commit = commitJournal();
        var written = new WorldCommitCore(commit, SETTINGS, 64);
        var future = publishArchivedOutput(stress, written, "future-output", 100);
        var valid = publishArchivedOutput(stress, written, "valid-output", 99);
        var source = new WorldCommitCore.Intent("archived-source", key(0), "rev0", 400, MATERIAL, 1, 90, true);
        var sourceReservation = new Reservation("future-source", 1, 100, Policy.defaults(), SETTINGS,
                List.of(new Seed(node(0),100,null)), List.of(source));
        stress.reserve(sourceReservation);
        written.submitSealed(source, SETTINGS, 100, 1300);
        stress.cancelUnplanned(sourceReservation.creditId(), "ORIGINAL_TTL_UNUSED");
        stress.compactHandoff(sourceReservation.creditId());
        var unrelated = new WorldCommitCore.Intent("legacy", key(3), "rev0", 1, MATERIAL, 1, 90, false);
        written.submitSealed(unrelated, SETTINGS, 100, 1300);

        var active = new Reservation("longer-active", 1, 99, new Policy(4,128,512,1.0/256,1800), SETTINGS,
                List.of(new Seed(node(0),100,null)));
        var activeOutput = publishOutput(stress, written, active);
        var restored = commitJournal();
        var restoredStress = new StressReservationJournal(path.resolve("stress"));
        var core = new WorldCommitCore(restored, SETTINGS, 64);
        assertEquals(2, core.abortRecoveredFutureStress(99, 1200, 0,
                i -> restoredStress.isArchivedHandoff(i.applyId(), Set.of(activeOutput.applyId()))));
        for (var item : List.of(future, source))
            assertEquals(WorldReceiptJournal.Status.ABORTED, restored.receipt(item.applyId()).status());
        for (var item : List.of(valid, unrelated, activeOutput))
            assertEquals(WorldReceiptJournal.Status.DEFERRED, restored.receipt(item.applyId()).status());
        assertEquals(3, new WorldCommitCore(commitJournal(), SETTINGS, 64).queued());
    }

    private static WorldCommitCore.Intent publishArchivedOutput(StressReservationJournal stress, WorldCommitCore core,
            String name, long created) throws IOException {
        var r = new Reservation(name, 1, created, Policy.defaults(), SETTINGS, List.of(new Seed(node(0),100,null)));
        var derived = publishOutput(stress, core, r);
        stress.acknowledgeHandoff(r.creditId());
        stress.compactHandoff(r.creditId());
        return derived;
    }

    private static WorldCommitCore.Intent publishOutput(StressReservationJournal stress, WorldCommitCore core,
            Reservation r) throws IOException {
        stress.reserve(r);
        var session = new Session(r.seeds(), r.policy(), r.settings(), r.createdGameTick(), true);
        session.advance(line(1), r.createdGameTick(), 512);
        stress.sealProgress(r.creditId(), session, Map.of());
        var plan = session.plans().getFirst();
        var derived = intent(r, plan);
        assertTrue(StressCommitHandoff.offer(core, stress.get(r.creditId()), plan, derived, r.createdGameTick()));
        return derived;
    }

    private WorldPersistentJournal commitJournal() throws IOException {
        return new WorldPersistentJournal(path.resolve("commit"), path.resolve("legacy"));
    }

    private static WorldCommitCore.WorldGateway gateway(List<SparseStateKey> reads) {
        return new WorldCommitCore.WorldGateway() {
            @Override public boolean isServerThread() { return true; }
            @Override public WorldCommitCore.Observation readIfLoaded(SparseStateKey key) {
                reads.add(key);
                return null;
            }
            @Override public WorldCommitCore.Permission canDamage(SparseStateKey key, WorldCommitCore.Observation observation) { throw new AssertionError(); }
            @Override public WorldCommitCore.Permission canBreak(SparseStateKey key, WorldCommitCore.Observation observation) { throw new AssertionError(); }
            @Override public boolean breakVanillaOnce(SparseStateKey key) { throw new AssertionError(); }
        };
    }
}

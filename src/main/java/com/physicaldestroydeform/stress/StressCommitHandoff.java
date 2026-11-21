package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.WorldCommitCore;
import com.physicaldestroydeform.world.WorldReceiptJournal;
import java.io.*;
import java.util.Base64;
import java.util.Objects;
import static com.physicaldestroydeform.stress.StressPropagation.*;

/** Durable bridge for an ALREADY SEALED stress output. Does not discover owners,
 * create reservations, issue Q, or mutate blocks. Runtime adapters must register
 * their existing watchers/bindings before handing an intent to this boundary.
 */
public final class StressCommitHandoff {
    private StressCommitHandoff() {}

    /** Plot adapters pass their existing PlotKeyCodec virtual World key here.
     * Encode fields, not delimiter-concatenated arbitrary dimension strings.
     */
    public static Cell cellFor(SparseStateKey key) {
        Objects.requireNonNull(key);
        try {
            var bytes=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(bytes)){
                out.writeUTF(key.dimension());out.writeUTF(key.ownerSpace());
                out.writeUTF(key.ownerId());out.writeLong(key.ownerGeneration());
            }
            return new Cell("PDD-OWNER-1:"+Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(bytes.toByteArray()),key.x(),key.y(),key.z());
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }

    /** Decode only our canonical owner encoding; caller still validates the real owner gateway. */
    public static SparseStateKey keyFor(Cell cell,String fingerprint) {
        if(!cell.owner().startsWith("PDD-OWNER-1:"))throw new IllegalArgumentException("unknown stress owner");
        try(var in=new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(cell.owner().substring(12))))){
            var key=new SparseStateKey(in.readUTF(),in.readUTF(),in.readUTF(),in.readLong(),cell.x(),cell.y(),cell.z(),fingerprint);
            if(in.available()!=0||!cellFor(key).equals(cell))throw new IllegalArgumentException("noncanonical stress owner");
            return key;
        }catch(IOException|IllegalArgumentException invalid){throw new IllegalArgumentException("invalid stress owner",invalid);}
    }

    /** Durable ownership proof only: no world read, rebinding, enqueue or replay.
     * DEFERRED must have its original payload; PREPARED is non-reissuable and is
     * independently quarantined by the commit journal's recovery protocol.
     * Terminal receipts retain the stable ID and target, not their old payload. */
    public static boolean receiptMatches(WorldCommitCore.Intent expected,WorldReceiptJournal.Receipt receipt){
        return receipt!=null&&receipt.id().equals(expected.applyId())&&receipt.key().equals(expected.key())
                &&(receipt.status()!=WorldReceiptJournal.Status.DEFERRED
                ||receipt.pending()!=null&&receipt.pending().intent().equals(expected));
    }
    public static boolean receiptMatches(WorldCommitCore.Intent expected,DamageModel.Settings settings,
                                         WorldReceiptJournal.Receipt receipt){
        return receiptMatches(expected,receipt)&&(receipt.pending()==null||receipt.pending().settings().equals(settings));
    }
    public static boolean receiptMatches(WorldCommitCore.Intent expected,DamageModel.Settings settings,long deadline,
                                         WorldReceiptJournal.Receipt receipt){
        return receiptMatches(expected,settings,receipt)&&(receipt.pending()==null||receipt.pending().expiresGameTick()==deadline);
    }
    /** Missing or legacy payload-less records are not proof. Conflicting identities
     * must fail closed rather than being silently considered an accepted duplicate. */
    public static boolean verifyReceipt(WorldCommitCore.Intent expected,DamageModel.Settings settings,
                                        WorldReceiptJournal.Receipt receipt)throws IOException{
        if(receipt==null)return false;
        if(!receipt.id().equals(expected.applyId())||!receipt.key().equals(expected.key()))
            throw new IOException("stress ApplyId aliases another target");
        if(receipt.pending()!=null&&(!receipt.pending().intent().equals(expected)||!receipt.pending().settings().equals(settings)))
            throw new IOException("stress ApplyId aliases another payload or settings");
        return receiptMatches(expected,settings,receipt);
    }

    /** True means queued durably or already terminal, NEVER successful damage.
     * original.eventTick remains the source server tick, including after reboot;
     * the authoritative saved deadline uses the persistent game clock separately.
     */
    public static boolean offer(WorldCommitCore core,StressReservationJournal.Entry sealed,
                                PlannedNode plan,WorldCommitCore.Intent original,long gameTick)throws IOException {
        Objects.requireNonNull(sealed);Objects.requireNonNull(plan);
        return offer(core,sealed,plan,original,gameTick,sealed.plans().indexOf(plan));
    }
    /** Runtime already holds the durable delivery cursor. Verify the exact
     * immutable list element in O(1), not a full contains() for every plan. */
    public static boolean offer(WorldCommitCore core,StressReservationJournal.Entry sealed,
                                PlannedNode plan,WorldCommitCore.Intent original,long gameTick,int index)throws IOException {
        Objects.requireNonNull(core);validate(sealed,plan,original,index);
        if(sealed.clockRolledBack()||gameTick<sealed.reservation().createdGameTick())
            throw new IllegalArgumentException("stress game clock rollback");
        long expires=deadline(sealed.reservation().createdGameTick(),sealed.reservation().policy().ttlTicks());
        var result=core.trySubmitSealed(original,sealed.reservation().settings(),gameTick,expires);
        if(core.ioFault())throw new IOException("stress commit journal fault: "+result.reason());
        var receipt=result.receipt();
        if(!verifyReceipt(original,sealed.reservation().settings(),receipt))return false;
        if(receipt.pending()!=null&&receipt.pending().expiresGameTick()!=expires)
            throw new IOException("stress ApplyId aliases another deadline");
        return true;
    }

    public static boolean cancelOnRollback(WorldCommitCore core,StressReservationJournal.Entry sealed,
                                           PlannedNode plan,WorldCommitCore.Intent original,long tick,int index)throws IOException {
        Objects.requireNonNull(core);validate(sealed,plan,original,index);
        if(!sealed.clockRolledBack())throw new IllegalArgumentException("clock rollback was not saved");
        var r=sealed.reservation();
        core.abortSealedOnRollback(original,r.settings(),deadline(r.createdGameTick(),r.policy().ttlTicks()),tick);
        return true;
    }

    public static long deadline(long created,long ttl){return created>Long.MAX_VALUE-ttl?Long.MAX_VALUE:created+ttl;}

    private static void validate(StressReservationJournal.Entry sealed,PlannedNode plan,
                                 WorldCommitCore.Intent original,int index) {
        Objects.requireNonNull(sealed);
        Objects.requireNonNull(plan);Objects.requireNonNull(original);
        if(sealed.phase()==StressReservationJournal.Phase.RESERVED || index<0||index>=sealed.plans().size()
                ||!sealed.plans().get(index).equals(plan))
            throw new IllegalArgumentException("stress output is not in the sealed reservation");
        var reservation=sealed.reservation();
        Node n=plan.node();
        if(!original.applyId().equals(StressReservationJournal.applyId(reservation.creditId(),n.cell()))
                || !cellFor(original.key()).equals(n.cell())
                || !original.key().fingerprint().equals(n.fingerprint())
                || !original.expectedIncarnation().equals(n.incarnation())
                || !original.material().equals(n.material())
                || !original.materialRevision().equals(n.materialRevision())
                || original.materialGeneration()!=reservation.materialGeneration()
                || Double.compare(original.sealedQ(),plan.incomingQ())!=0
                || original.fFirstPreDamped()
                || !DamageModel.plan(plan.incomingQ(),n.material(),new BlockDamageState(),reservation.settings()).equals(plan.plan()))
            throw new IllegalArgumentException("stress handoff changed sealed target or Q");
    }
}

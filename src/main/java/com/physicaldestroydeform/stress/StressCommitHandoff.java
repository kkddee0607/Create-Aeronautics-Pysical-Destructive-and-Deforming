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

    /** True means queued durably or already terminal, NEVER successful damage.
     * original.eventTick remains the source server tick, including after reboot;
     * the authoritative saved deadline uses the persistent game clock separately.
     */
    public static boolean offer(WorldCommitCore core,StressReservationJournal.Entry sealed,
                                PlannedNode plan,WorldCommitCore.Intent original,long gameTick)throws IOException {
        Objects.requireNonNull(core);Objects.requireNonNull(sealed);
        Objects.requireNonNull(plan);Objects.requireNonNull(original);
        if(sealed.phase()==StressReservationJournal.Phase.RESERVED || !sealed.plans().contains(plan))
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
        long created=reservation.createdGameTick(),ttl=reservation.policy().ttlTicks();
        if(gameTick<created)throw new IllegalArgumentException("stress game clock rollback");
        long expires=created>Long.MAX_VALUE-ttl?Long.MAX_VALUE:created+ttl;
        var result=core.submitSealed(original,reservation.settings(),gameTick,expires);
        if(core.ioFault())throw new IOException("stress commit journal fault: "+result.reason());
        var receipt=result.receipt();
        if(receipt==null)return false;
        if(!receipt.key().equals(original.key()))throw new IOException("stress ApplyId aliases another target");
        if(receipt.pending()!=null&&!receipt.pending().intent().equals(original))throw new IOException("stress ApplyId aliases another payload");
        return receipt.status()!=WorldReceiptJournal.Status.DEFERRED || receipt.pending()!=null;
    }
}

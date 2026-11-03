package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import net.minecraft.server.MinecraftServer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.HashMap;

/**
 * Bounded contact-region target audit for IDS §21.4/§22.4. Audit each source
 * record's contact frame independently, then deduplicate actual block keys.
 * The record index is NOT a stable native contact/manifold identifier.
 */
public final class SableContactRegions {
    private SableContactRegions() {}
    public static final int MAX_CHECKS_PER_SIDE=32;
    public enum Status { PROVED, PARTIAL, NO_VERIFIED_REGION, TOO_BROAD, SKIPPED_DUPLICATE }
    public record Check(List<GameImpactCredit.Target> targets,int inspected,boolean incomplete,
                        String method,String detail) {
        public Check(List<GameImpactCredit.Target> targets,int inspected,boolean incomplete,String method) {
            this(targets,inspected,incomplete,method,"NONE");
        }
        public Check(List<GameImpactCredit.Target> targets,int inspected) {
            this(targets,inspected,false,"TEST");
        }
        public Check(List<GameImpactCredit.Target> targets,int inspected,boolean incomplete) {
            this(targets,inspected,incomplete,"UNVERIFIED");
        }
        public Check {
            targets=List.copyOf(targets);
            if(inspected<0 || inspected>MAX_CHECKS_PER_SIDE)throw new IllegalArgumentException("unbounded contact audit");
        }
    }
    @FunctionalInterface public interface Inspector {
        Check verify(NativeCollisionTelemetry.Contact record,String owner,int remainingChecks);
    }
    /** Why an otherwise valid native event could not establish a target set. */
    public enum AuditFailure {
        NONE, UNCORROBORATED, SIDE_A_INCOMPLETE, SIDE_B_INCOMPLETE,
        SIDE_A_AND_B_INCOMPLETE, CHECK_BUDGET_A, CHECK_BUDGET_B,
        OWNER_MISMATCH_A, OWNER_MISMATCH_B, TARGET_LIMIT_A, TARGET_LIMIT_B,
        REGION_LIMIT, NO_VERIFIED_REGION
    }
    public record Trace(AuditFailure failure,int sourceIndex,int processedRecords,
                        int remainingA,int remainingB,int requestedA,int requestedB,
                        int verifiedTargetsA,int verifiedTargetsB,int regionCount,
                        int rejectedContacts,String detail) {
        public Trace {Objects.requireNonNull(failure);detail=detail==null?"NONE":detail;}
    }
    public record Result(List<ContactCreditLedger.Region> regions,Status status,
                         int checksA,int checksB,int rejected,String methodA,String methodB,
                         Trace trace) {
        public Result {regions=List.copyOf(regions);Objects.requireNonNull(trace);}
        public Result(List<ContactCreditLedger.Region> regions,Status status,
                      int checksA,int checksB,int rejected,String methodA,String methodB) {
            this(regions,status,checksA,checksB,rejected,methodA,methodB,
                    new Trace(AuditFailure.NONE,-1,0,MAX_CHECKS_PER_SIDE-checksA,
                            MAX_CHECKS_PER_SIDE-checksB,0,0,0,0,regions.size(),rejected,"NONE"));
        }
        public boolean verified() {return status==Status.PROVED || status==Status.PARTIAL;}
        public GameImpactCredit.Target firstA() {
            return regions.stream().map(ContactCreditLedger.Region::a).filter(Objects::nonNull).findFirst().orElse(null);
        }
        public GameImpactCredit.Target firstB() {
            return regions.stream().map(ContactCreditLedger.Region::b).filter(Objects::nonNull).findFirst().orElse(null);
        }
    }
    /** No shape check is required to advance an already exhausted ACTIVE episode.
     * This is NOT a negative or rejected target audit. */
    public static Result skippedDuplicate() {
        return new Result(List.of(),Status.SKIPPED_DUPLICATE,0,0,0,
                "NOT_AUDITED_DUPLICATE","NOT_AUDITED_DUPLICATE",
                new Trace(AuditFailure.NONE,-1,0,MAX_CHECKS_PER_SIDE,
                        MAX_CHECKS_PER_SIDE,0,0,0,0,0,0,"AUDIT_SKIPPED_NO_Q_AUTHORIZATION"));
    }
    /** Runtime inspector: Sable-consumer local R2 shape cells; independent R3
     * only when R2 finds no block. Multiple real contact cells are preserved. */
    public static Result audit(MinecraftServer server,String dimension,GameImpactCredit.Pair pair,
                               List<NativeCollisionTelemetry.Contact> records) {
        // Repeated identical native points share shape audits, not credit.
        var cache=new HashMap<AuditKey,Check>();
        var nativeBatch=new OwnerResolver.NativeAuditBatch(server,dimension);
        Result collected=collect(pair,records,(contact,owner,remainingChecks)-> {
            int sourceSide=SableConsumerTargets.sourceSide(owner,contact,dimension);
            if(sourceSide==0)return new Check(List.of(),0,false,"UNVERIFIED","OWNER_FRAME_MISMATCH");
            boolean onA=sourceSide==1;
            var point=SableConsumerTargets.candidate(contact,onA);
            if(point==null)return new Check(List.of(),0,false,"UNVERIFIED","NATIVE_POINT_OR_FRAME_MISSING");
            var key=new AuditKey(owner,point,onA?contact.frameA():contact.frameB());
            var prior=cache.get(key);
            if(prior!=null)return new Check(prior.targets(),0,false,prior.method(),prior.detail());
            var positions=SableConsumerTargets.cells(point);
            var found=new LinkedHashSet<GameImpactCredit.Target>();
            int checked=0,reused=0;
            for(var pos:positions){
                // Only a *new owner/cell* consumes a block lookup budget.
                // Every source point still passes the actual cached shape check.
                if(!nativeBatch.cached(owner,pos) && checked>=remainingChecks)
                    return new Check(List.of(),checked,true,"UNVERIFIED",
                            "R2_UNIQUE_CELL_BUDGET requested="+positions.size()
                            +" examinedNew="+checked+" remaining="+remainingChecks);
                var sampled=nativeBatch.inspect(owner,pos,point);
                if(sampled.newCell())checked++;else reused++;
                var evidence=sampled.result();
                if(evidence.verified()) {
                    var targetKey=evidence.key();
                    found.add(new GameImpactCredit.Target(owner,targetKey.x()+","+targetKey.y()+","+targetKey.z(),targetKey.fingerprint()));
                }
            }
            Check result;
            if(!found.isEmpty())result=new Check(new ArrayList<>(found),checked,false,"R2_CONTACT_REGION",
                    "R2_SHAPE_VERIFIED reusedCells="+reused);
            else {
                // Sable native contacts are body-local. Projecting the other
                // witness through last-post poses silently mixes substeps.
                // R3 remains diagnostic-only until per-contact poses exist.
                result=new Check(List.of(),checked,false,"UNVERIFIED",
                        "R2_NO_SHAPE_R3_CROSS_PHASE_UNCALIBRATED");
            }
            cache.put(key,result);
            return result;
        });
        var t=collected.trace();
        var stats=nativeBatch.stats();
        var t2=new Trace(t.failure(),t.sourceIndex(),t.processedRecords(),
                t.remainingA(),t.remainingB(),t.requestedA(),t.requestedB(),
                t.verifiedTargetsA(),t.verifiedTargetsB(),t.regionCount(),t.rejectedContacts(),
                t.detail()+" auditedUniqueOwnerCells="+stats.newCells()
                        +" reusedCellReads="+stats.reusedCells()
                        +" shapePointTests="+stats.pointTests()
                        +" testedShapeBoxes="+stats.testedBoxes()
                        +" cellBudgetPerSide="+MAX_CHECKS_PER_SIDE);
        return new Result(collected.regions(),collected.status(),collected.checksA(),collected.checksB(),
                collected.rejected(),collected.methodA(),collected.methodB(),t2);
    }
    private record AuditKey(String owner, FrameMath.Vec point,
                            NativeCollisionTelemetry.OwnerFrame frame) {}
    /** Testable no-world-evidence policy: never approve a side from a guessed owner. */
    public static Result collect(GameImpactCredit.Pair pair,
                                 List<NativeCollisionTelemetry.Contact> records,Inspector inspector) {
        Objects.requireNonNull(pair);Objects.requireNonNull(records);Objects.requireNonNull(inspector);
        var regions=new LinkedHashSet<ContactCreditLedger.Region>();
        var targetsA=new LinkedHashSet<GameImpactCredit.Target>();
        var targetsB=new LinkedHashSet<GameImpactCredit.Target>();
        int checkedA=0,checkedB=0,skipped=0,processed=0,uncorroborated=0,emptyChecks=0;
        boolean exceededA=false,exceededB=false,regionCompacted=false;
        boolean incompleteEverA=false,incompleteEverB=false;
        String incompleteDetailA="NONE",incompleteDetailB="NONE";
        int overflowIndex=-1,overflowCountA=0,overflowCountB=0;
        String firstFrameMismatch="NONE";
        int witnessSeparated=0,worldProjectionUnavailable=0;
        String firstEmptyA="NONE",firstEmptyB="NONE";
        String methodA="UNVERIFIED",methodB="UNVERIFIED";
        for(var record:records) {
            int index=processed++;
            // Independent Rapier witness points need not coincide. A source
            // record is rejected only for invalid native fields, not distance.
            if(!SableConsumerTargets.sourceRecordValid(record)
                    || !pair.dimension().equals(record.dimension())) {
                skipped++;uncorroborated++;continue;
            }
            var residue=SableConsumerTargets.frameResidual(record);
            if("WITNESS_SEPARATED_GT_0.25".equals(residue.reason()))witnessSeparated++;
            if("EFFECT_POSE_UNAVAILABLE".equals(residue.reason()))worldProjectionUnavailable++;
            if(firstFrameMismatch.equals("NONE") && "WITNESS_SEPARATED_GT_0.25".equals(residue.reason())) {
                firstFrameMismatch="nativeRecord="+index+" frameReason="+residue.reason()
                        +" rawA=("+record.contactAX()+","+record.contactAY()+","+record.contactAZ()+")"
                        +" rawB=("+record.contactBX()+","+record.contactBY()+","+record.contactBZ()+")"
                        +" nativeFrameA="+record.frameA()+" nativeFrameB="+record.frameB()
                        +" worldA="+residue.a()+" worldB="+residue.b()
                        +" worldDelta="+residue.delta()+" distance="+residue.distance();
            }
            int remainA=MAX_CHECKS_PER_SIDE-checkedA,remainB=MAX_CHECKS_PER_SIDE-checkedB;
            Check a=exceededA?new Check(List.of(),0,false,"UNVERIFIED","TARGET_LIMIT_A_SIDE_QUARANTINED")
                    :SableConsumerTargets.sourceSide(pair.a().stableKey(),record,record.dimension())==0
                            ?new Check(List.of(),0,false,"UNVERIFIED","OWNER_FRAME_MISMATCH_A")
                            :inspector.verify(record,pair.a().stableKey(),remainA);
            Check b=exceededB?new Check(List.of(),0,false,"UNVERIFIED","TARGET_LIMIT_B_SIDE_QUARANTINED")
                    :SableConsumerTargets.sourceSide(pair.b().stableKey(),record,record.dimension())==0
                            ?new Check(List.of(),0,false,"UNVERIFIED","OWNER_FRAME_MISMATCH_B")
                            :inspector.verify(record,pair.b().stableKey(),remainB);
            int askedA=a==null?0:a.inspected(),askedB=b==null?0:b.inspected();
            // Source validity is checked above. If the local audit cannot
            // complete for one owner, quarantine ONLY that owner; the other
            // owner's confirmed cells still belong to the same source event.
            boolean incompleteA=a==null||a.incomplete();
            boolean incompleteB=b==null||b.incomplete();
            if(incompleteA) {
                incompleteEverA=true;
                if(incompleteDetailA.equals("NONE"))incompleteDetailA=a==null?"NULL":a.detail();
                if(!exceededA){exceededA=true;overflowIndex=index;}
            }
            if(incompleteB) {
                incompleteEverB=true;
                if(incompleteDetailB.equals("NONE"))incompleteDetailB=b==null?"NULL":b.detail();
                if(!exceededB){exceededB=true;overflowIndex=index;}
            }
            checkedA+=askedA; checkedB+=askedB;
            if(incompleteA || incompleteB) {
                if(incompleteA) a=new Check(List.of(),0,false,"UNVERIFIED","SIDE_A_INCOMPLETE");
                if(incompleteB) b=new Check(List.of(),0,false,"UNVERIFIED","SIDE_B_INCOMPLETE");
            }
            if(checkedA>MAX_CHECKS_PER_SIDE || checkedB>MAX_CHECKS_PER_SIDE)
                return traced(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED",
                        checkedA>MAX_CHECKS_PER_SIDE?AuditFailure.CHECK_BUDGET_A:AuditFailure.CHECK_BUDGET_B,
                        index,processed,remainA,remainB,askedA,askedB,targetsA.size(),targetsB.size(),regions.size(),
                        "inspector_claim_over_budget");
            if(a.targets().isEmpty()&&b.targets().isEmpty())emptyChecks++;
            if(a.targets().isEmpty() && firstEmptyA.equals("NONE"))firstEmptyA=a.detail();
            if(b.targets().isEmpty() && firstEmptyB.equals("NONE"))firstEmptyB=b.detail();
            if(!a.targets().isEmpty())methodA=mergeMethod(methodA,a.method());
            if(!b.targets().isEmpty())methodB=mergeMethod(methodB,b.method());
            for(var target:a.targets()) {
                if(!target.ownerKey().equals(pair.a().stableKey()))
                    return traced(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                            "UNVERIFIED","UNVERIFIED",AuditFailure.OWNER_MISMATCH_A,
                            index,processed,remainA,remainB,askedA,askedB,targetsA.size(),targetsB.size(),regions.size(),
                            "target_owner="+target.ownerKey());
                targetsA.add(target);
            }
            for(var target:b.targets()) {
                if(!target.ownerKey().equals(pair.b().stableKey()))
                    return traced(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                            "UNVERIFIED","UNVERIFIED",AuditFailure.OWNER_MISMATCH_B,
                            index,processed,remainA,remainB,askedA,askedB,targetsA.size(),targetsB.size(),regions.size(),
                            "target_owner="+target.ownerKey());
                targetsB.add(target);
            }
            // If one side exceeds the bounded target budget, NEVER concentrate its Q on
            // an arbitrary subset. Quarantine that entire side, continue
            // auditing the opposite side, and leave the bad side's Q unused.
            if(targetsA.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE && !exceededA) {
                exceededA=true;overflowIndex=index;overflowCountA=targetsA.size();
            }
            if(targetsB.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE && !exceededB) {
                exceededB=true;overflowIndex=index;overflowCountB=targetsB.size();
            }
            if(exceededA&&exceededB)
                return traced(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED",
                        incompleteEverA&&incompleteEverB ? AuditFailure.SIDE_A_AND_B_INCOMPLETE :
                                incompleteEverA ? AuditFailure.SIDE_A_INCOMPLETE :
                                incompleteEverB ? AuditFailure.SIDE_B_INCOMPLETE : AuditFailure.TARGET_LIMIT_A,
                        index,processed,remainA,remainB,askedA,askedB,targetsA.size(),targetsB.size(),regions.size(),
                        "both_sides_unverifiable capacity="+ContactCreditLedger.MAX_TARGETS_PER_SIDE
                                +" incompleteA="+incompleteDetailA+" incompleteB="+incompleteDetailB);
            if(!exceededA && !exceededB && !regionCompacted) {
                if(a.targets().size()==1 && b.targets().size()==1)
                    regions.add(new ContactCreditLedger.Region(a.targets().get(0),b.targets().get(0)));
                else {
                    for(var target:a.targets())regions.add(new ContactCreditLedger.Region(target,null));
                    for(var target:b.targets())regions.add(new ContactCreditLedger.Region(null,target));
                }
            }
            if(a.targets().isEmpty()&&b.targets().isEmpty())skipped++;
            if(regions.size()>ContactCreditLedger.MAX_REGIONS) {
                // Avoid inventing cross-side pairs or losing independently
                // verified block identities. Per-side singleton regions are
                // sufficient for the ONE common Q budget; <=32 per owner.
                regions.clear();
                regionCompacted=true;
            }
        }
        if(exceededA || exceededB || regionCompacted) {
            regions.clear();
            if(!exceededA)for(var target:targetsA)regions.add(new ContactCreditLedger.Region(target,null));
            if(!exceededB)for(var target:targetsB)regions.add(new ContactCreditLedger.Region(null,target));
            if(exceededA)methodA="UNVERIFIED";
            if(exceededB)methodB="UNVERIFIED";
        }
        AuditFailure failure=exceededA?(incompleteEverA?AuditFailure.SIDE_A_INCOMPLETE:AuditFailure.TARGET_LIMIT_A):
                exceededB?(incompleteEverB?AuditFailure.SIDE_B_INCOMPLETE:AuditFailure.TARGET_LIMIT_B):
                regions.isEmpty()?(uncorroborated==records.size()&& !records.isEmpty()?
                        AuditFailure.UNCORROBORATED:AuditFailure.NO_VERIFIED_REGION):AuditFailure.NONE;
        // A valid one-sided Q reservation is PARTIAL, even if every record
        // was inspected. Never describe a missing source owner as PROVED.
        boolean hasA=regions.stream().anyMatch(r->r.a()!=null);
        boolean hasB=regions.stream().anyMatch(r->r.b()!=null);
        Status status=regions.isEmpty()?Status.NO_VERIFIED_REGION:
                skipped==0 && !exceededA && !exceededB && hasA && hasB
                        ?Status.PROVED:Status.PARTIAL;
        return traced(new ArrayList<>(regions),status,checkedA,checkedB,skipped,
                regions.isEmpty()?"UNVERIFIED":methodA,regions.isEmpty()?"UNVERIFIED":methodB,
                failure,overflowIndex>=0?overflowIndex:-1,processed,MAX_CHECKS_PER_SIDE-checkedA,MAX_CHECKS_PER_SIDE-checkedB,
                0,0,targetsA.size(),targetsB.size(),regions.size(),
                "invalidSourceRecords="+uncorroborated+" witnessSeparated="+witnessSeparated+
                " worldProjectionUnavailable="+worldProjectionUnavailable+
                " noShapeContactRecords="+emptyChecks+
                " firstEmptyA="+firstEmptyA+" firstEmptyB="+firstEmptyB+
                " incompleteA="+incompleteDetailA+" incompleteB="+incompleteDetailB+
                " overflowA="+exceededA+" overflowB="+exceededB+
                " overflowCountA="+overflowCountA+" overflowCountB="+overflowCountB+
                " compactedRegions="+regionCompacted+
                " firstFrameMismatch={"+firstFrameMismatch+"}");
    }
    private static Result traced(List<ContactCreditLedger.Region> regions,Status status,
                                 int checkedA,int checkedB,int skipped,String methodA,String methodB,
                                 AuditFailure reason,int recordIndex,int processed,int remainingA,int remainingB,
                                 int askedA,int askedB,int targetsA,int targetsB,int regionCount,String detail) {
        return new Result(regions,status,checkedA,checkedB,skipped,methodA,methodB,
                new Trace(reason,recordIndex,processed,remainingA,remainingB,
                        askedA,askedB,targetsA,targetsB,regionCount,skipped,detail));
    }
    private static String mergeMethod(String existing,String next) {
        if(existing.equals("UNVERIFIED"))return next;
        return existing.equals(next)?existing:"R2_R3_MIXED_REGION";
    }
}

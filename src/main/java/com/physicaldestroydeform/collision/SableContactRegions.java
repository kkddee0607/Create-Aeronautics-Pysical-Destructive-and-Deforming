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
    public enum Status { PROVED, PARTIAL, NO_VERIFIED_REGION, TOO_BROAD }
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
            if(inspected<0 || inspected>16)throw new IllegalArgumentException("unbounded contact audit");
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
    /** Runtime inspector: Sable-consumer local R2 shape cells; independent R3
     * only when R2 finds no block. Multiple real contact cells are preserved. */
    public static Result audit(MinecraftServer server,String dimension,GameImpactCredit.Pair pair,
                               List<NativeCollisionTelemetry.Contact> records) {
        // Repeated identical native points share shape audits, not credit.
        var cache=new HashMap<AuditKey,Check>();
        return collect(pair,records,(contact,owner,remainingChecks)-> {
            boolean onA=matches(contact.idA(),contact.frameA(),owner,dimension);
            boolean onB=matches(contact.idB(),contact.frameB(),owner,dimension);
            if(onA==onB)return new Check(List.of(),0,false,"UNVERIFIED","OWNER_FRAME_MISMATCH");
            var point=SableConsumerTargets.candidate(contact,onA);
            if(point==null)return new Check(List.of(),0,false,"UNVERIFIED","NATIVE_POINT_OR_FRAME_MISSING");
            var key=new AuditKey(owner,point,onA?contact.frameA():contact.frameB());
            var prior=cache.get(key);
            if(prior!=null)return new Check(prior.targets(),0,false,prior.method(),prior.detail());
            var positions=SableConsumerTargets.cells(point);
            if(positions.size()>remainingChecks)
                return new Check(List.of(),0,true,"UNVERIFIED",
                        "R2_CELL_BUDGET planned="+positions.size()+" remaining="+remainingChecks);
            var found=new LinkedHashSet<GameImpactCredit.Target>();
            int checked=0;
            for(var pos:positions){
                var evidence=OwnerResolver.auditNative(server,dimension,owner,pos,point);
                checked++;
                if(evidence.verified()) {
                    var targetKey=evidence.key();
                    found.add(new GameImpactCredit.Target(owner,targetKey.x()+","+targetKey.y()+","+targetKey.z(),targetKey.fingerprint()));
                }
            }
            Check result;
            if(!found.isEmpty())result=new Check(new ArrayList<>(found),checked,false,"R2_CONTACT_REGION","R2_SHAPE_VERIFIED");
            else {
                if(remainingChecks-checked<SableConsumerTargets.MAX_CANDIDATES)
                    return new Check(List.of(),checked,true,"UNVERIFIED",
                            "R3_RESERVE_BUDGET remaining="+(remainingChecks-checked)
                            +" needed="+SableConsumerTargets.MAX_CANDIDATES);
                var cross=SableConsumerTargets.crossProject(server,dimension,owner,List.of(contact));
                result=new Check(cross.verified()?List.of(cross.target()):List.of(),
                        Math.min(16,checked+cross.checked()),false,
                        cross.verified()?"R3_CROSS_SIDE_REGION":"UNVERIFIED",
                        cross.verified()?"R3_SHAPE_VERIFIED":"R2_R3_NO_SHAPE:"+cross.reason());
            }
            cache.put(key,result);
            return result;
        });
    }
    private record AuditKey(String owner, FrameMath.Vec point,
                            NativeCollisionTelemetry.OwnerFrame frame) {}
    private static boolean matches(int id,NativeCollisionTelemetry.OwnerFrame frame,
                                   String owner,String dimension) {
        return id==-1?owner.equals("WORLD:"+dimension)
                :frame!=null&&owner.equals("PLOT:"+frame.uuid());
    }
    /** Testable no-world-evidence policy: never approve a side from a guessed owner. */
    public static Result collect(GameImpactCredit.Pair pair,
                                 List<NativeCollisionTelemetry.Contact> records,Inspector inspector) {
        Objects.requireNonNull(pair);Objects.requireNonNull(records);Objects.requireNonNull(inspector);
        var regions=new LinkedHashSet<ContactCreditLedger.Region>();
        var targetsA=new LinkedHashSet<GameImpactCredit.Target>();
        var targetsB=new LinkedHashSet<GameImpactCredit.Target>();
        int checkedA=0,checkedB=0,skipped=0,processed=0,uncorroborated=0,emptyChecks=0;
        String firstEmptyA="NONE",firstEmptyB="NONE";
        String methodA="UNVERIFIED",methodB="UNVERIFIED";
        for(var record:records) {
            int index=processed++;
            if(record==null || !SableConsumerTargets.corroboratedContact(record)) {
                skipped++;uncorroborated++;continue;
            }
            int remainA=MAX_CHECKS_PER_SIDE-checkedA,remainB=MAX_CHECKS_PER_SIDE-checkedB;
            Check a=inspector.verify(record,pair.a().stableKey(),remainA);
            Check b=inspector.verify(record,pair.b().stableKey(),remainB);
            int askedA=a==null?0:a.inspected(),askedB=b==null?0:b.inspected();
            if(a==null||b==null || a.incomplete() || b.incomplete()) {
                AuditFailure why=a==null||a.incomplete()?
                        (b==null||b.incomplete()?AuditFailure.SIDE_A_AND_B_INCOMPLETE:AuditFailure.SIDE_A_INCOMPLETE)
                        :AuditFailure.SIDE_B_INCOMPLETE;
                return traced(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED",why,index,processed,remainA,remainB,
                        askedA,askedB,targetsA.size(),targetsB.size(),regions.size(),
                        "a="+(a==null?"NULL":a.detail())+" b="+(b==null?"NULL":b.detail()));
            }
            checkedA+=askedA; checkedB+=askedB;
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
            if(targetsA.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE
                    ||targetsB.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE)
                return traced(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED",
                        targetsA.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE?
                                AuditFailure.TARGET_LIMIT_A:AuditFailure.TARGET_LIMIT_B,
                        index,processed,remainA,remainB,askedA,askedB,targetsA.size(),targetsB.size(),regions.size(),
                        "unique_target_limit="+ContactCreditLedger.MAX_TARGETS_PER_SIDE);
            if(a.targets().size()==1 && b.targets().size()==1)
                regions.add(new ContactCreditLedger.Region(a.targets().get(0),b.targets().get(0)));
            else {
                for(var target:a.targets())regions.add(new ContactCreditLedger.Region(target,null));
                for(var target:b.targets())regions.add(new ContactCreditLedger.Region(null,target));
            }
            if(a.targets().isEmpty()&&b.targets().isEmpty())skipped++;
            if(regions.size()>ContactCreditLedger.MAX_REGIONS)
                return traced(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED",AuditFailure.REGION_LIMIT,index,processed,
                        remainA,remainB,askedA,askedB,targetsA.size(),targetsB.size(),regions.size(),
                        "region_limit="+ContactCreditLedger.MAX_REGIONS);
        }
        AuditFailure failure=regions.isEmpty()?
                uncorroborated==records.size()&& !records.isEmpty()?
                        AuditFailure.UNCORROBORATED:AuditFailure.NO_VERIFIED_REGION:AuditFailure.NONE;
        Status status=regions.isEmpty()?Status.NO_VERIFIED_REGION:
                skipped==0?Status.PROVED:Status.PARTIAL;
        return traced(new ArrayList<>(regions),status,checkedA,checkedB,skipped,
                regions.isEmpty()?"UNVERIFIED":methodA,regions.isEmpty()?"UNVERIFIED":methodB,
                failure,-1,processed,MAX_CHECKS_PER_SIDE-checkedA,MAX_CHECKS_PER_SIDE-checkedB,
                0,0,targetsA.size(),targetsB.size(),regions.size(),
                "uncorroborated="+uncorroborated+" noShapeContactRecords="+emptyChecks+
                " firstEmptyA="+firstEmptyA+" firstEmptyB="+firstEmptyB);
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

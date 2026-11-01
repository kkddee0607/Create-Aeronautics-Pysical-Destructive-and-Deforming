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
    public record Check(List<GameImpactCredit.Target> targets,int inspected,boolean incomplete,String method) {
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
    public record Result(List<ContactCreditLedger.Region> regions,Status status,
                         int checksA,int checksB,int rejected,String methodA,String methodB) {
        public Result {regions=List.copyOf(regions);}
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
            if(onA==onB)return new Check(List.of(),0);
            var point=SableConsumerTargets.candidate(contact,onA);
            if(point==null)return new Check(List.of(),0);
            var key=new AuditKey(owner,point,onA?contact.frameA():contact.frameB());
            var prior=cache.get(key);
            if(prior!=null)return new Check(prior.targets(),0);
            var positions=SableConsumerTargets.cells(point);
            if(positions.size()>remainingChecks)
                return new Check(List.of(),0,true);
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
            if(!found.isEmpty())result=new Check(new ArrayList<>(found),checked,false,"R2_CONTACT_REGION");
            else {
                if(remainingChecks-checked<SableConsumerTargets.MAX_CANDIDATES)
                    return new Check(List.of(),checked,true);
                var cross=SableConsumerTargets.crossProject(server,dimension,owner,List.of(contact));
                result=new Check(cross.verified()?List.of(cross.target()):List.of(),
                        Math.min(16,checked+cross.checked()),false,
                        cross.verified()?"R3_CROSS_SIDE_REGION":"UNVERIFIED");
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
        int checkedA=0,checkedB=0,skipped=0;
        String methodA="UNVERIFIED",methodB="UNVERIFIED";
        for(var record:records) {
            if(record==null || !SableConsumerTargets.corroboratedContact(record)) {skipped++;continue;}
            Check a=inspector.verify(record,pair.a().stableKey(),MAX_CHECKS_PER_SIDE-checkedA);
            Check b=inspector.verify(record,pair.b().stableKey(),MAX_CHECKS_PER_SIDE-checkedB);
            if(a==null||b==null || a.incomplete() || b.incomplete())
                return new Result(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED");
            checkedA+=a.inspected(); checkedB+=b.inspected();
            if(checkedA>MAX_CHECKS_PER_SIDE || checkedB>MAX_CHECKS_PER_SIDE)
                return new Result(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED");
            if(!a.targets().isEmpty())methodA=mergeMethod(methodA,a.method());
            if(!b.targets().isEmpty())methodB=mergeMethod(methodB,b.method());
            for(var target:a.targets()) {
                if(!target.ownerKey().equals(pair.a().stableKey()))
                    return new Result(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED");
                targetsA.add(target);
            }
            for(var target:b.targets()) {
                if(!target.ownerKey().equals(pair.b().stableKey()))
                    return new Result(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED");
                targetsB.add(target);
            }
            if(targetsA.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE
                    ||targetsB.size()>ContactCreditLedger.MAX_TARGETS_PER_SIDE)
                return new Result(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED");
            // Only a single uniquely matched block on EACH side establishes a
            // paired contact region; otherwise keep separately verified sides.
            if(a.targets().size()==1 && b.targets().size()==1)
                regions.add(new ContactCreditLedger.Region(a.targets().get(0),b.targets().get(0)));
            else {
                for(var target:a.targets())regions.add(new ContactCreditLedger.Region(target,null));
                for(var target:b.targets())regions.add(new ContactCreditLedger.Region(null,target));
            }
            if(a.targets().isEmpty()&&b.targets().isEmpty())skipped++;
            if(regions.size()>ContactCreditLedger.MAX_REGIONS)
                return new Result(List.of(),Status.TOO_BROAD,checkedA,checkedB,skipped,
                        "UNVERIFIED","UNVERIFIED");
        }
        if(regions.isEmpty())return new Result(List.of(),Status.NO_VERIFIED_REGION,checkedA,checkedB,skipped,
                "UNVERIFIED","UNVERIFIED");
        return new Result(new ArrayList<>(regions),skipped==0?Status.PROVED:Status.PARTIAL,
                checkedA,checkedB,skipped,methodA,methodB);
    }
    private static String mergeMethod(String existing,String next) {
        if(existing.equals("UNVERIFIED"))return next;
        return existing.equals(next)?existing:"R2_R3_MIXED_REGION";
    }
}

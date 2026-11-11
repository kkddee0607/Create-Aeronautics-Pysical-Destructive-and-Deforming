package com.physicaldestroydeform;

import java.util.Objects;

/** IDS 1.0.6 §24.1 game-Q planning only. No block mutation or committed damage. */
public final class DamageModel {
    public static final String ALGORITHM_REVISION = "PDD-IDS-1.0.7/0.5.0moreFix-SELF-ELASTIC-90";
    public static final double DIRECT_SHARE = 0.60;
    public static final double PLASTIC_SHARE = 0.15;
    public static final double STRESS_SHARE = 0.25;
    private DamageModel() {}

    public record Settings(double minHardnessFactor, double hardnessExponent,
                           double minElasticFactor, double elasticExponent,
                           double damageGain, double deformationGain) {
        public Settings {
            if (!Double.isFinite(minHardnessFactor) || minHardnessFactor<=0 || minHardnessFactor>1
                    || !Double.isFinite(hardnessExponent) || hardnessExponent<=0
                    || !Double.isFinite(minElasticFactor) || minElasticFactor<=0 || minElasticFactor>1
                    || !Double.isFinite(elasticExponent) || elasticExponent<=0
                    || !Double.isFinite(damageGain) || damageGain<=0
                    || !Double.isFinite(deformationGain) || deformationGain<=0)
                throw new IllegalArgumentException("invalid PDD material damage policy");
        }
        public static Settings defaults() {return new Settings(0.08,1.5,0.15,1.5,2.6,1);}
        /** Read once at bridge construction. Invalid operator input preserves the calibration default. */
        public static Settings fromSystemProperties() {
            Settings d=defaults();
            String text=System.getProperty("pdd.material.damageGain");
            if(text==null)return d;
            try {
                double value=Double.parseDouble(text);
                return Double.isFinite(value)&&value>0
                        ?new Settings(d.minHardnessFactor(),d.hardnessExponent(),
                                d.minElasticFactor(),d.elasticExponent(),value,d.deformationGain()) : d;
            }catch(NumberFormatException malformed){return d;}
        }
    }
    public enum Status { PLANNED_NOT_APPLIED, UNBREAKABLE_UNUSED, ZERO_Q }
    public record Plan(Status status, double incomingQ, double absorbedQ, double activeQ,
                       double directQ, double plasticQ, double stressQ, double unusedPlannedQ,
                       double rawDamage, long fixedIncrement, int plannedDamageU8,
                       int plannedRemainderU8, int plannedPlasticIncrement,
                       boolean thresholdReached, String algorithmRevision) {
        /** Legacy accessor plannedDamageU8 is retained for test/API compatibility,
         * but its numeric domain is now 0..1024 (U16 on disk). */
        public int plannedDamageUnits() {return plannedDamageU8;}
        public double accountedQ() {return absorbedQ+directQ+unusedPlannedQ;}
    }

    public static double hardnessFactor(MaterialProperties material, Settings cfg) {
        Objects.requireNonNull(material); Objects.requireNonNull(cfg);
        return cfg.minHardnessFactor() + (1-cfg.minHardnessFactor())
                * Math.pow(1-material.hardnessNormalized(),cfg.hardnessExponent());
    }
    public static double elasticityTransmission(MaterialProperties material, Settings cfg) {
        Objects.requireNonNull(material);
        return elasticityTransmissionPercent(material.elasticityPercent(),cfg);
    }
    /** Direct percent lookup for the bounded graph. Avoid per-edge temporary
     * material allocation during World/Plot stress planning. */
    public static double elasticityTransmissionPercent(int percent,Settings cfg) {
        Objects.requireNonNull(cfg);
        if(percent<0 || percent>100)throw new IllegalArgumentException("invalid E percent");
        return cfg.minElasticFactor()+(1-cfg.minElasticFactor())
                * Math.pow(1-percent/100.0,cfg.elasticExponent());
    }
    /** Only the block's own final DAMAGE is capped by this retention curve.
     * The already-accounted local Q and parent/child F/Q propagation are unchanged.
     * At 100% elasticity, damage is at most 10% of the E=0 baseline, not
     * 90% of the previously damped damage. An existing stronger reduction wins.
     */
    public static double ownDamageRetentionPercent(int percent, Settings cfg) {
        double propagationRetention=elasticityTransmissionPercent(percent,cfg);
        double maxSelfRetention=1.0-0.90*(percent/100.0);
        return Math.min(propagationRetention,maxSelfRetention);
    }
    /** Apply only the EXTRA self-damage reduction: local elasticity is already
     * accounted for once in activeQ. Never reapply this factor to Q or stress.
     */
    private static double extraOwnDamageFactor(MaterialProperties material,Settings cfg) {
        double existing=elasticityTransmission(material,cfg);
        return ownDamageRetentionPercent(material.elasticityPercent(),cfg)/existing;
    }

    /** Immutable prediction. Plastic/stress are *reserved then unused*, not added to direct. */
    public static Plan plan(double incomingQ, MaterialProperties material, BlockDamageState previous,
                            Settings cfg) {
        return planInternal(incomingQ,material,previous,cfg,true);
    }
    /** F propagation damped this target's Q only by DOWNSTREAM elastic nodes.
     * Its OWN elasticity still absorbs part of its own Q exactly once, which
     * does not double-count the neighbor's separate upstream effect. */
    public static Plan planPreDamped(double incomingQ,MaterialProperties material,
                                     BlockDamageState previous,Settings cfg) {
        return planInternal(incomingQ,material,previous,cfg,true);
    }
    private static Plan planInternal(double incomingQ,MaterialProperties material,
                                     BlockDamageState previous,Settings cfg,boolean localElasticity) {
        if (!Double.isFinite(incomingQ) || incomingQ<0) throw new IllegalArgumentException("invalid source Q");
        Objects.requireNonNull(material); Objects.requireNonNull(previous); Objects.requireNonNull(cfg);
        if (incomingQ==0 || material.unbreakable()) {
            return new Plan(material.unbreakable()?Status.UNBREAKABLE_UNUSED:Status.ZERO_Q,
                    incomingQ,0,0,0,0,0,incomingQ,0,0,previous.damage(),previous.remainder(),0,
                    false,ALGORITHM_REVISION);
        }
        double active=incomingQ*(localElasticity?elasticityTransmission(material,cfg):1);
        double absorbed=incomingQ-active;
        double direct=active*DIRECT_SHARE;
        double plastic=active*PLASTIC_SHARE;
        double stress=Math.max(0,active-direct-plastic);
        double rawDamage=direct*cfg.damageGain()*hardnessFactor(material,cfg)
                *extraOwnDamageFactor(material,cfg);
        if (!Double.isFinite(rawDamage) || rawDamage<0)
            throw new IllegalArgumentException("invalid calculated damage");
        long increment=(long)Math.floor(Math.min((double)BlockDamageState.MAX_FIXED_VALUE,rawDamage*BlockDamageState.FIXED_SCALE));
        BlockDamageState projected=previous.copy();
        projected.addFixedDamage(increment);
        int cap=Math.max(0,material.plasticCapacity()-previous.deformation());
        int plasticIncrement=(int)Math.min(cap,Math.floor(Math.max(0,plastic-material.yieldThreshold())
                * cfg.deformationGain()));
        return new Plan(Status.PLANNED_NOT_APPLIED,incomingQ,absorbed,active,direct,plastic,stress,
                plastic+stress,rawDamage,increment,projected.damage(),projected.remainder(),
                plasticIncrement,projected.damage()>=material.threshold(),ALGORITHM_REVISION);
    }

    /** Pure helper retained for earlier code. This is direct hardness resistance, not a new source Q. */
    public static double effectiveLocalDamage(double directQ, MaterialProperties material) {
        if (!Double.isFinite(directQ) || directQ<=0) return 0;
        return directQ*hardnessFactor(material,Settings.defaults());
    }
    /** E at a *new* neighbor's admission only; never apply twice to the same node. */
    public static double neighborStress(double incoming, MaterialProperties newNode) {
        if (!Double.isFinite(incoming) || incoming<=0) return 0;
        return incoming*elasticityTransmission(newNode,Settings.defaults());
    }
    public static double baseDamageFromEnergy(ImpactSample impact, double energyPerDamageUnit) {
        if (impact==null || !Double.isFinite(energyPerDamageUnit) || energyPerDamageUnit<=0)
            throw new IllegalArgumentException("invalid energy input");
        return impact.hasUsableImpact()?impact.impactEnergy()/energyPerDamageUnit:0;
    }
    /** No per-impact ceil: sub-unit damage is carried by BlockDamageState.remainder. */
    public static int quantizeDamage(double damage) {
        if (!Double.isFinite(damage) || damage<=0) return 0;
        return (int)Math.min(BlockDamageState.MAX_VALUE,Math.floor(damage));
    }
    public static int damageIncrement(double effectiveDamage,double damageScale) {
        if (!Double.isFinite(effectiveDamage) || effectiveDamage<=0 ||
                !Double.isFinite(damageScale) || damageScale<=0) return 0;
        return quantizeDamage(effectiveDamage*damageScale);
    }
}

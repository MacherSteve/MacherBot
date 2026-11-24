package de.pvptrainer.progress;

/** Per-round mutable collector whose consumeSnapshot() resets atomically to prevent double counting. */
public final class RoundMetricsCollector {
    private int meleeHits;
    private int crits;
    private int chargeSamples;
    private int chargeSumPct;
    private float damageDealt;
    private int shieldStuns;
    private int shieldBreaks;
    private int playerBlocks;
    private int botPops;
    private int playerPops;
    private int crystalsPlaced;
    private int crystalsDetonated;
    private int anchorsDetonated;
    private int anchorPops;
    private int obsidianPlaced;
    private int maceSmashes;
    private int stunSlams;
    private double maxFallMeters;
    private double sumFallMeters;
    private double maxSmashDamage;
    private double maxWindMeters;
    private int windChargesUsed;
    private int pearlCatches;
    private int websPlaced;
    private int lavaPlaced;
    private int waterDrains;
    private int healsEaten;

    public void reset() {
        meleeHits = crits = chargeSamples = chargeSumPct = 0;
        damageDealt = 0.0F;
        shieldStuns = shieldBreaks = playerBlocks = 0;
        botPops = playerPops = crystalsPlaced = crystalsDetonated = anchorsDetonated = anchorPops = obsidianPlaced = 0;
        maceSmashes = stunSlams = windChargesUsed = pearlCatches = 0;
        maxFallMeters = sumFallMeters = maxSmashDamage = maxWindMeters = 0.0;
        websPlaced = lavaPlaced = waterDrains = healsEaten = 0;
    }

    public void recordMeleeHit(float charge) {
        meleeHits++;
        if (Float.isFinite(charge) && charge >= 0.0F) {
            chargeSamples++;
            chargeSumPct += Math.clamp(Math.round(charge * 100.0F), 0, 100);
        }
    }

    public void recordCrit() { crits++; }
    public void recordDamageDealt(float hp) { if (Float.isFinite(hp) && hp > 0.0F) damageDealt += hp; }
    public void recordShieldStun() { shieldStuns++; }
    public void recordShieldBreak() { shieldBreaks++; }
    public void recordPlayerBlock() { playerBlocks++; }
    public void recordBotPop() { botPops++; }
    public void recordPlayerPop() { playerPops++; }
    public void recordCrystalPlaced() { crystalsPlaced++; }
    public void recordCrystalDetonated() { crystalsDetonated++; }
    public void recordAnchorDetonated() { anchorsDetonated++; }
    public void recordAnchorPop() { anchorPops++; }
    public void recordObsidianPlaced() { obsidianPlaced++; }

    public void recordMaceSmash(double fallMeters, float damageHp) {
        maceSmashes++;
        double clampedFall = Double.isFinite(fallMeters) ? Math.max(0.0, fallMeters) : 0.0;
        sumFallMeters += clampedFall;
        if (clampedFall > maxFallMeters) maxFallMeters = clampedFall;
        if (Float.isFinite(damageHp) && damageHp > maxSmashDamage) maxSmashDamage = damageHp;
    }

    public void recordStunSlam() { stunSlams++; }
    public void recordWindHeight(double meters) { if (Double.isFinite(meters) && meters > maxWindMeters) maxWindMeters = meters; }
    public void recordWindCharge() { windChargesUsed++; }
    public void recordPearlCatch() { pearlCatches++; }
    public void recordWebPlaced() { websPlaced++; }
    public void recordLavaPlaced() { lavaPlaced++; }
    public void recordWaterDrain() { waterDrains++; }
    public void recordHealEaten() { healsEaten++; }

    public boolean hasActivity() {
        return peekSnapshot().hasActivity();
    }

    public RoundMetrics peekSnapshot() {
        return new RoundMetrics(
            meleeHits, crits, chargeSamples, chargeSumPct, damageDealt, shieldStuns, shieldBreaks, playerBlocks,
            botPops, playerPops, crystalsPlaced, crystalsDetonated, anchorsDetonated, anchorPops, obsidianPlaced,
            maceSmashes, stunSlams, maxFallMeters, sumFallMeters, maxSmashDamage, maxWindMeters, windChargesUsed, pearlCatches,
            websPlaced, lavaPlaced, waterDrains, healsEaten
        );
    }

    public RoundMetrics consumeSnapshot() {
        RoundMetrics snap = peekSnapshot();
        reset();
        return snap;
    }
}

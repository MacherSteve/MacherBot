package de.pvptrainer.progress;

/** Real, concrete PvP action counters recorded once per server event during a round. */
public record RoundMetrics(
    // Melee & Combat
    int meleeHits,
    int crits,
    int chargeSamples,
    int chargeSumPct,
    float damageDealt,
    int shieldStuns,
    int shieldBreaks,
    int playerBlocks,
    // Totems, Crystal & Anchor
    int botPops,
    int playerPops,
    int crystalsPlaced,
    int crystalsDetonated,
    int anchorsDetonated,
    int anchorPops,
    int obsidianPlaced,
    // Mace & Movement / Height
    int maceSmashes,
    int stunSlams,
    double maxFallMeters,
    double sumFallMeters,
    double maxSmashDamage,
    double maxWindMeters,
    int windChargesUsed,
    int pearlCatches,
    // UHC & Utility
    int websPlaced,
    int lavaPlaced,
    int waterDrains,
    int healsEaten
) {
    public static final RoundMetrics EMPTY = new RoundMetrics(
        0, 0, 0, 0, 0.0F, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0,
        0, 0, 0.0, 0.0, 0.0, 0.0, 0, 0,
        0, 0, 0, 0
    );

    public RoundMetrics {
        meleeHits = Math.max(0, meleeHits);
        crits = Math.max(0, crits);
        chargeSamples = Math.max(0, chargeSamples);
        chargeSumPct = Math.max(0, chargeSumPct);
        damageDealt = Float.isFinite(damageDealt) ? Math.max(0.0F, damageDealt) : 0.0F;
        shieldStuns = Math.max(0, shieldStuns);
        shieldBreaks = Math.max(0, shieldBreaks);
        playerBlocks = Math.max(0, playerBlocks);
        botPops = Math.max(0, botPops);
        playerPops = Math.max(0, playerPops);
        crystalsPlaced = Math.max(0, crystalsPlaced);
        crystalsDetonated = Math.max(0, crystalsDetonated);
        anchorsDetonated = Math.max(0, anchorsDetonated);
        anchorPops = Math.max(0, anchorPops);
        obsidianPlaced = Math.max(0, obsidianPlaced);
        maceSmashes = Math.max(0, maceSmashes);
        stunSlams = Math.max(0, stunSlams);
        maxFallMeters = Double.isFinite(maxFallMeters) ? Math.max(0.0, maxFallMeters) : 0.0;
        sumFallMeters = Double.isFinite(sumFallMeters) ? Math.max(0.0, sumFallMeters) : 0.0;
        maxSmashDamage = Double.isFinite(maxSmashDamage) ? Math.max(0.0, maxSmashDamage) : 0.0;
        maxWindMeters = Double.isFinite(maxWindMeters) ? Math.max(0.0, maxWindMeters) : 0.0;
        windChargesUsed = Math.max(0, windChargesUsed);
        pearlCatches = Math.max(0, pearlCatches);
        websPlaced = Math.max(0, websPlaced);
        lavaPlaced = Math.max(0, lavaPlaced);
        waterDrains = Math.max(0, waterDrains);
        healsEaten = Math.max(0, healsEaten);
    }

    public boolean hasActivity() {
        return meleeHits > 0 || crits > 0 || damageDealt > 0.0F || shieldStuns > 0 || shieldBreaks > 0 || playerBlocks > 0
            || botPops > 0 || playerPops > 0 || crystalsPlaced > 0 || crystalsDetonated > 0
            || anchorsDetonated > 0 || anchorPops > 0 || obsidianPlaced > 0
            || maceSmashes > 0 || stunSlams > 0 || maxWindMeters > 0.0 || windChargesUsed > 0 || pearlCatches > 0
            || websPlaced > 0 || lavaPlaced > 0 || waterDrains > 0 || healsEaten > 0;
    }

    public RoundMetrics add(RoundMetrics other) {
        if (other == null || !other.hasActivity()) return this;
        if (!this.hasActivity()) return other;
        return new RoundMetrics(
            meleeHits + other.meleeHits,
            crits + other.crits,
            chargeSamples + other.chargeSamples,
            chargeSumPct + other.chargeSumPct,
            damageDealt + other.damageDealt,
            shieldStuns + other.shieldStuns,
            shieldBreaks + other.shieldBreaks,
            playerBlocks + other.playerBlocks,
            botPops + other.botPops,
            playerPops + other.playerPops,
            crystalsPlaced + other.crystalsPlaced,
            crystalsDetonated + other.crystalsDetonated,
            anchorsDetonated + other.anchorsDetonated,
            anchorPops + other.anchorPops,
            obsidianPlaced + other.obsidianPlaced,
            maceSmashes + other.maceSmashes,
            stunSlams + other.stunSlams,
            Math.max(maxFallMeters, other.maxFallMeters),
            sumFallMeters + other.sumFallMeters,
            Math.max(maxSmashDamage, other.maxSmashDamage),
            Math.max(maxWindMeters, other.maxWindMeters),
            windChargesUsed + other.windChargesUsed,
            pearlCatches + other.pearlCatches,
            websPlaced + other.websPlaced,
            lavaPlaced + other.lavaPlaced,
            waterDrains + other.waterDrains,
            healsEaten + other.healsEaten
        );
    }

    public double avgFallMeters() {
        return maceSmashes > 0 ? sumFallMeters / maceSmashes : 0.0;
    }

    public double crystalsPerPop() {
        if (botPops <= 0) return 0.0;
        int crystals = Math.max(crystalsDetonated, crystalsPlaced);
        return crystals > 0 ? (double) crystals / botPops : 0.0;
    }

    public double critRatePct() {
        return meleeHits > 0 ? Math.min(100.0, 100.0 * crits / meleeHits) : 0.0;
    }

    public double avgChargePct() {
        return chargeSamples > 0 ? (double) chargeSumPct / chargeSamples : 0.0;
    }

    public double damageHearts() {
        return damageDealt / 2.0;
    }

    public double maxSmashHearts() {
        return maxSmashDamage / 2.0;
    }
}

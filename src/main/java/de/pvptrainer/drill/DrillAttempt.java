package de.pvptrainer.drill;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Bewertungszustand eines einzelnen Drill-Versuchs; delegiert Spezial-Drills an dedizierte Tracker. */
public final class DrillAttempt {
    private static final int NEVER = -100000;

    private final DrillOptions options;
    private final Drill drill;
    private final Deque<String> events = new ArrayDeque<>();
    private final double control;
    private final int variant;
    private final TotemDefenseTracker defense = new TotemDefenseTracker();
    private final UhcUtilityTracker uhc = new UhcUtilityTracker();
    private final SlamWindowTracker slam = new SlamWindowTracker();

    private int tick;
    private int started = NEVER;
    private int swordAt = NEVER;
    private int lastCrystal = NEVER;
    private int lastOwnerHit = NEVER;
    private int anchorAt = NEVER;
    private int launched = NEVER;
    private int caught = NEVER;
    private int jumpAt = NEVER;
    private int firstSmash = NEVER;
    private int flightEnded = NEVER;
    private int swordHits;
    private int crystalHits;
    private int explosions;
    private boolean airborneTarget;
    private boolean burstLifted;
    private boolean swappedArmor;
    private Vec3 startOwner;
    private float damageOnOwner;
    private boolean validAnchorOpening;
    private boolean wasGliding;
    private boolean hadAir;
    private boolean covered;
    private boolean unscored;
    private boolean done;
    private boolean won;
    private boolean touched;
    private double anchorSelfDamage;
    private double anchorDamage;
    private double anchorOwnerHealth;
    private double measuredControl = -1.0;
    private Vec3 firstCrystalSide;
    private Vec3 ownerKnockback;
    private Vec3 botHitOrigin;
    private int displacementAt = NEVER;
    private String message;
    private String lastTimingFault;
    private int airSwordAt = NEVER;
    private boolean airSwordStartedWindow;
    private boolean openingLanded;

    public DrillAttempt(DrillOptions options, double control, int variant) {
        this.options = options;
        this.drill = options.drill();
        this.control = control;
        this.variant = variant;
        this.message = options.hints() ? this.drill.hint() : this.drill.label();
    }

    public float doubleClickDisable(long sequence, float axeSeconds) {
        return slam.doubleClickDisable(drill, done, tick, sequence, axeSeconds);
    }

    void timingFault(String fault) {
        if (!done && !fault.equals(lastTimingFault)) {
            lastTimingFault = fault;
            step(fault);
        }
    }

    public String timingHint() {
        return lastTimingFault;
    }

    void clearTimingFault() {
        lastTimingFault = null;
    }

    public boolean done() {
        return done;
    }

    boolean touched() {
        return touched;
    }

    public boolean won() {
        return won;
    }

    public boolean unscored() {
        return unscored;
    }

    public int duration() {
        return tick;
    }

    public double control() {
        return measuredControl;
    }

    public String progress() {
        return message;
    }

    public List<String> timeline() {
        return List.copyOf(events);
    }

    static String decimal(double value) {
        return String.format(Locale.GERMANY, "%.1f", value);
    }

    public void event(String text) {
        if (!done) {
            touched = true;
            if (started == NEVER) {
                started = tick;
            }
            if (events.size() == 28) {
                events.removeFirst();
            }
            events.addLast("T+" + tick + "  " + text);
        }
    }

    void step(String text) {
        if (!done) {
            event(text);
            message = text;
        }
    }

    public void finish(boolean success, String reason) {
        if (!done) {
            if (!success && lastTimingFault == null && drill == Drill.STUN_SLAM && !reason.contains("Mace") && !reason.contains("Axt")) {
                reason = reason + " · " + slam.slamFailure();
            }
            if (!success && lastTimingFault != null && !reason.contains(lastTimingFault)) {
                reason = lastTimingFault + " · " + reason;
            }
            event(reason);
            message = reason;
            done = true;
            won = success;
        }
    }

    private boolean recent(int eventTick) {
        return tick - eventTick <= options.windowTicks();
    }

    public void tick(int currentTick, ServerPlayer owner, LivingEntity bot) {
        this.tick = currentTick;
        if (done) return;

        if (startOwner == null) {
            startOwner = owner.position();
        }
        uhc.initStartingFoodIfNeeded(drill, owner);

        if (swordAt != NEVER && isSwordCrystalSequence()) {
            tickSwordCrystalSequence(bot);
            if (done) return;
        }

        if (firstSmash != NEVER && owner.getDeltaMovement().y > 0.1) {
            burstLifted = true;
        }

        if (wasGliding && !owner.isFallFlying()) {
            flightEnded = tick;
            event("Gleitflug beendet");
            swappedArmor = !owner.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
            if (swappedArmor) {
                event("Elytra abgelegt");
            }
        }
        if (flightEnded != NEVER && !swappedArmor && !owner.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            swappedArmor = true;
            event("Elytra abgelegt");
        }
        wasGliding = owner.isFallFlying();

        if (!owner.onGround()) {
            hadAir = true;
        }
        if (owner.onGround() && hadAir) {
            if (firstSmash != NEVER || caught != NEVER || flightEnded != NEVER || launched != NEVER) {
                event("Bodenkontakt");
            }
            hadAir = false;
            firstSmash = caught = flightEnded = launched = NEVER;
        }

        if (displacementAt != NEVER && tick - displacementAt >= 4) {
            Vec3 delta = bot.position().subtract(botHitOrigin);
            event("Bot-Versatz " + decimal(delta.horizontalDistance()) + " Bl. horizontal, " + decimal(delta.y) + " Bl. vertikal");
            displacementAt = NEVER;
        }

        settleAnchor(owner, tick);
        if (drill == Drill.JUMP_RESET && ownerKnockback != null && tick - lastOwnerHit >= 6) {
            double moved = owner.position().subtract(ownerKnockback).horizontalDistance();
            boolean timedJump = Math.abs(jumpAt - lastOwnerHit) <= 2;
            if (!timedJump) {
                measuredControl = moved;
                finish(false, "Kontrollversuch ohne passenden Sprung: " + decimal(moved) + " Bl.");
            } else {
                String ref = control >= 0.0 ? " · Kontrolle " + decimal(control) + " Bl." : " · Kontrolle ohne Sprung noch offen";
                finish(true, "Sprung " + (jumpAt - lastOwnerHit) + " Ticks · Versatz " + decimal(moved) + " Bl." + ref);
            }
        }

        if (drill == Drill.HEAD_WEB) {
            BlockPos head = BlockPos.containing(bot.getEyePosition());
            checkHeadTrap(head, bot.level().getBlockState(head).getBlock());
        } else if (drill == Drill.OBI_TRAP) {
            checkObsidianTrap(bot.level(), bot.blockPosition());
        }

        if (drill == Drill.STUN_WEB) {
            if (slam.tickStunWeb(this, tick, bot)) return;
            uhc.checkStunWebCatch(this, tick, slam.stunUntil(), bot);
        }

        if (drill == Drill.WEB_DRAIN) {
            uhc.tickWebDrain(this, bot);
        }
        if (drill == Drill.RE_LAVA) {
            uhc.tickReLava(this, tick, bot);
        }
        if (drill == Drill.DOUBLE_HAND) {
            defense.tickDoubleHand(this, tick, owner);
        }

        if (drill.series() && !drill.centeredMaceDrill() && drill != Drill.PERFECT_WIND_CHARGE
            && drill != Drill.RETOTEM && drill != Drill.STUN_SLAM && tick >= 1200) {
            finish(false, "Zeit abgelaufen");
        }
    }

    private void tickSwordCrystalSequence(LivingEntity bot) {
        if (bot.onGround() && (drill.requiresPopChain() && airborneTarget || !(bot.getDeltaMovement().y > 0.05))) {
            if (airborneTarget && !openingLanded) {
                openingLanded = true;
                if (drill.requiresPopChain()) {
                    finish(false, "Der Bot ist vor dem letzten Pop gelandet.");
                    return;
                }
                if (crystalHits == 0) {
                    timingFault("Kristall 1 zu spät: Ziel nach " + (tick - swordAt) + " Ticks gelandet");
                } else if (needsAirSword()) {
                    timingFault("Nahkampftreffer 2 zu spät: Ziel " + (tick - lastCrystal) + " Ticks nach Kristall 1 gelandet");
                } else if (options.airborneFollowups() && (drill != Drill.TAP_ANCHOR || crystalHits < 2)) {
                    timingFault("Kristall " + (crystalHits + 1) + " zu spät: Ziel bereits gelandet");
                }
            }
        } else {
            airborneTarget = true;
        }

        int elapsed = tick - (crystalHits == 0 ? swordAt : lastCrystal);
        int maxWindow = crystalHits == 0 ? (drill == Drill.HIT_CRYSTAL ? 10 : 9) : options.windowTicks();
        if (elapsed > maxWindow && !done) {
            String nextStep = needsAirSword()
                ? "Nahkampftreffer 2"
                : (drill == Drill.TAP_ANCHOR && crystalHits == 2 ? "Anker" : "Kristall " + (crystalHits + 1));
            finish(false, lastTimingFault != null ? lastTimingFault : nextStep + " zu spät (" + elapsed + " Ticks, Fenster bis " + maxWindow + ")");
        }
    }

    public void settleAnchor(ServerPlayer owner, int currentTick) {
        if (!done && anchorAt != NEVER && currentTick > anchorAt) {
            this.tick = currentTick;
            double selfLoss = Math.max(anchorSelfDamage, anchorOwnerHealth - health(owner));
            event("Anker: Bot " + decimal(anchorDamage) + " LP · selbst " + decimal(selfLoss) + " LP");
            if (!(anchorDamage > 0.0)
                || (drill != Drill.TAP_ANCHOR || !validAnchorOpening)
                    && (drill != Drill.SAFE_ANCHOR || !covered || !(anchorDamage > selfLoss))) {
                if (drill == Drill.SAFE_ANCHOR || drill == Drill.TAP_ANCHOR) {
                    finish(false, "Anker: Deckung, Eröffnung oder Schadensvorteil fehlte");
                }
            } else {
                finish(true, "Ankerfolge gelungen");
            }
            anchorAt = NEVER;
        }
    }

    public static boolean inWeb(LivingEntity entity) {
        return UhcUtilityTracker.inWeb(entity);
    }

    public static float health(LivingEntity entity) {
        return entity.getHealth() + entity.getAbsorptionAmount();
    }

    public int reaction() {
        return options.reactionTicks() + (options.variableTiming() ? variant % 5 * 2 : 0);
    }

    public int time() {
        return tick;
    }

    // --- UHC Utility & Traps ---

    public boolean allowRescue() {
        return uhc.allowRescue(drill, done, tick, reaction());
    }

    void checkHeadTrap(BlockPos head, Block block) {
        uhc.checkHeadTrap(this, drill, head, block);
    }

    public void checkObsidianTrap(Level level, BlockPos feet) {
        uhc.checkObsidianTrap(this, level, feet);
    }

    public void trapBlockPlaced(BlockPos pos) {
        uhc.trapBlockPlaced(drill, done, pos);
    }

    public void lavaPlaced(BlockPos pos) {
        uhc.lavaPlaced(this, drill, tick, pos);
    }

    public void webPlaced(BlockPos pos) {
        uhc.webPlaced(this, drill, tick, slam.stunUntil(), pos);
    }

    public void waterPlaced(BlockPos pos) {
        uhc.waterPlaced(this, tick, pos);
    }

    public BlockPos rescueSource() {
        return uhc.rescueSource();
    }

    public int rescueTick() {
        return uhc.rescueTick();
    }

    public void waterDrawn(BlockPos pos, LivingEntity bot) {
        uhc.waterDrawn(this, drill, tick, pos, bot);
    }

    public void ate(ServerPlayer player, ItemStack stack) {
        uhc.ate(this, drill, damageOnOwner, player, stack);
    }

    // --- Totem Defense ---

    public boolean readyForDefense(ServerPlayer player) {
        return defense.readyForDefense(drill, player);
    }

    public int playerPops() {
        return defense.playerPops();
    }

    public void startRefill(int totalSupply) {
        this.message = defense.startRefill(totalSupply);
    }

    public String refillProgress() {
        return defense.refillProgress();
    }

    public void playerPop(int remainingSupply) {
        defense.playerPop(this, remainingSupply);
    }

    public void playerPop() {
        defense.playerPop(this);
    }

    public void defenseUnavailable(String reason) {
        if (!done) {
            unscored = drill == Drill.RETOTEM || drill == Drill.WIND_STUN_SLAM;
            finish(false, reason);
        }
    }

    public void defenseBlast(boolean mainHand, boolean offHand, boolean popped, float damage, boolean survived) {
        defenseBlast(mainHand, offHand, popped, damage, survived, true);
    }

    public void defenseBlast(boolean mainHand, boolean offHand, boolean popped, float damage, boolean survived, boolean finalBlast) {
        defense.defenseBlast(this, drill, tick, mainHand, offHand, popped, damage, survived, finalBlast);
    }

    // --- Movement & Sword ---

    public void botPop() {
        step("Bot-Totem ausgelöst");
    }

    public void announceSwing() {
        message = "Schlag in 3 Ticks";
    }

    public void jump() {
        jumpAt = tick;
        if (drill == Drill.JUMP_RESET || drill == Drill.PUNISH_CRIT) {
            event("Eigener Sprung");
        }
    }

    public void glideStarted() {
        if (drill == Drill.ELYTRA_SMASH && !done) {
            wasGliding = true;
            flightEnded = NEVER;
            swappedArmor = false;
            hadAir = true;
            event("Gleitflug begonnen");
        }
    }

    public void startWebDrop() {
        hadAir = true;
        step("Luftstart über dem Kopfnetz");
    }

    public void launch() {
        launched = tick;
        event("Windladung hebt Spieler an");
    }

    public void catchPearl() {
        caught = tick;
        step("Pearl Catch · Luft-Teleport");
    }

    public void playerHit(ServerPlayer owner, float damage) {
        damageOnOwner += damage;
        event("Gegentreffer " + decimal(damage) + " LP");
        if (drill == Drill.SPACING) {
            finish(false, "Bot landete den Gegentreffer");
        }
        if (drill == Drill.HIT_SELECT && swordHits > 0) {
            finish(false, "Gegentreffer vor dem Anschluss");
        }
        if (drill != Drill.JUMP_RESET || ownerKnockback == null) {
            lastOwnerHit = tick;
            ownerKnockback = owner.position();
        }
    }

    public boolean needsAirSword() {
        return drill == Drill.TRIPLE_TAP && crystalHits == 1 && airSwordAt == NEVER;
    }

    void swordHit(ServerPlayer owner, LivingEntity bot, float damage, float charge) {
        swordContact(owner, bot, damage, charge, !bot.onGround() || bot.getDeltaMovement().y > 0.02, true, true);
    }

    public void swordContact(ServerPlayer owner, LivingEntity bot, float damage, float charge, boolean airborne, boolean dealtDamage, boolean hurtWindowOpen) {
        if (done) return;
        if (drill.requiresPopChain() && swordAt != NEVER && airborneTarget && bot.onGround()) {
            finish(false, "Der Bot ist vor dem letzten Pop gelandet.");
            return;
        }
        if (needsAirSword()) {
            if (airborne && recent(lastCrystal) && !openingLanded) {
                airSwordAt = tick;
                airSwordStartedWindow = dealtDamage && hurtWindowOpen;
                clearTimingFault();
                int gap = tick - lastCrystal;
                step("Nahkampftreffer 2 in der Luft · "
                    + (owner.getMainHandItem().is(Items.TOTEM_OF_UNDYING) ? "Totem · " : "Schwert · ")
                    + gap + (gap == 1 ? " Tick" : " Ticks") + " nach Kristall 1 · "
                    + (dealtDamage ? decimal(damage) + " LP" : "Kontakt im Trefferschutz"));
            } else {
                timingFault("Nahkampftreffer 2 zu spät: Ziel bereits gelandet oder Kombofenster vorbei");
            }
            return;
        }
        if (!dealtDamage) {
            if (drill == Drill.TRIPLE_TAP && swordAt != NEVER && crystalHits == 0) {
                timingFault("Nahkampftreffer 2 zu früh: Zuerst Kristall 1 zünden");
            }
            return;
        }
        boolean withTotem = owner.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        if (isSwordCrystalSequence() && withTotem && (!owner.isSprinting() || charge <= 0.9F)) {
            timingFault("Eröffnung mit Totem: ansprinten und über 90 % aufladen oder das Schwert nehmen");
            return;
        }
        event((withTotem ? "Totem-Sprinttreffer " : "Schwertkontakt ") + Math.round(charge * 100.0F) + " % · " + decimal(damage) + " LP");
        if (isSwordCrystalSequence()) {
            clearTimingFault();
        }
        swordAt = tick;
        airborneTarget = airborne;
        openingLanded = false;
        crystalHits = 0;
        airSwordAt = lastCrystal = NEVER;
        airSwordStartedWindow = false;
        firstCrystalSide = null;
        swordHits++;
        if (drill == Drill.SPACING) {
            int targetCombo = options.comboLength();
            String status = swordHits + "/" + targetCombo + " Treffer vor dem ersten Gegentreffer";
            if (swordHits >= targetCombo) finish(true, status);
            else step(status);
        }
        if (drill == Drill.HIT_SELECT) {
            if (lastOwnerHit == NEVER) {
                finish(false, "Erst den Bot treffen lassen");
            } else if (swordHits == 1 && (tick - lastOwnerHit > 8 || owner.onGround())) {
                finish(false, "Konter außerhalb des Rückstoßes");
            } else if (swordHits >= 2 && owner.onGround() && recent(lastOwnerHit)) {
                finish(true, "Konter im Rückstoß und Anschluss nach Landung");
            }
        }
    }

    public void critical() {
        event("Kritischer Treffer");
        if (drill == Drill.PUNISH_CRIT && recent(lastOwnerHit) && jumpAt < lastOwnerHit && swordAt == tick) {
            finish(true, "Kritischer Konter aus gegnerischem Rückstoß");
        }
    }

    // --- Shield & Mace Slam ---

    public void shieldBlocked(float blocked) {
        event("Schild blockt " + decimal(blocked));
    }

    public void shieldBreak() {
        step("Schild durch Verschleiß gebrochen");
        if (drill == Drill.SHIELD_DRAIN) {
            finish(true, "Schild vollständig aufgebraucht");
        }
    }

    public boolean shieldWindowOpen() {
        return slam.shieldWindowOpen(tick);
    }

    public void shieldStun(int durationTicks) {
        uhc.clearWebPlacement();
        slam.shieldStun(this, drill, tick, durationTicks);
    }

    public void axeContact(boolean targetBlocking, int blockDelayRemaining) {
        slam.axeContact(this, tick, targetBlocking, blockDelayRemaining);
    }

    public void maceContact(double fallDistance, boolean onGround, boolean isSmash) {
        slam.maceContact(this, tick, fallDistance, onGround, isSmash);
    }

    String slamFailure() {
        return slam.slamFailure();
    }

    public void slamWallImpact() {
        slam.slamWallImpact(done, tick);
    }

    public void observeSlamLanding(ServerPlayer player) {
        slam.observeSlamLanding(this, tick, player);
    }

    public void slamLanded() {
        slam.slamLanded(this, tick);
    }

    public void smash(ServerPlayer owner, boolean targetBlocking) {
        event("Smash · Fallstrecke " + decimal(owner.fallDistance) + " Bl.");
        switch (drill) {
            case SMASH -> {
                if (recent(launched)) finish(true, "Smash nach eigener Windladung");
            }
            case WIND_CHAIN -> {
                if (recent(firstSmash) && burstLifted) {
                    finish(true, "Zwei Smashes nach Windstoß ohne Bodenkontakt");
                } else if (owner.getMainHandItem().getEnchantments().keySet().stream().anyMatch(e -> e.is(Enchantments.WIND_BURST))) {
                    firstSmash = tick;
                    burstLifted = false;
                    step("Wind-Burst-Smash · Folgetreffer offen");
                } else {
                    step("Smash mit einer Mace ohne Wind Burst");
                }
            }
            case STUN_SLAM -> {
                if (!targetBlocking && slam.shieldWindowOpen(tick)) {
                    finish(true, "Smash im offenen Schildfenster · " + SlamWindowTracker.ticksText(tick - slam.axeStunAt()) + " nach der Axt");
                } else {
                    maceContact(owner.fallDistance, owner.onGround(), true);
                }
            }
            case BACKSTAB -> {
                if (targetBlocking) finish(true, "Smash am aktiven Schild vorbei");
            }
            case PEARL_CATCH -> {
                if (recent(caught)) finish(true, "Smash nach Luft-Teleport");
            }
            case ELYTRA_SMASH -> {
                if (recent(flightEnded) || wasGliding && !owner.isFallFlying()) finish(true, "Smash nach Gleitflug");
            }
            default -> {
            }
        }
    }

    public void observeSecondarySlam(int roundTick, ServerPlayer player) {
        tick = roundTick;
        observeSlamLanding(player);
    }

    public boolean unusedSlamWindowExpired() {
        return slam.unusedSlamWindowExpired(tick);
    }

    // --- Crystal & Anchor ---

    public void anchor(ServerPlayer owner, BlockPos pos, boolean hasCover) {
        validAnchorOpening = crystalHits >= 2 && recent(lastCrystal);
        anchorAt = tick;
        anchorOwnerHealth = health(owner);
        anchorDamage = anchorSelfDamage = 0.0;
        covered = hasCover;
        event("Geladener Anker bei " + pos.toShortString() + " gezündet");
    }

    public void anchorSelfHit(float damage) {
        if (anchorAt == tick) anchorSelfDamage += damage;
    }

    public void anchorHit(float damage) {
        if (anchorAt == tick) anchorDamage += damage;
    }

    public void crystalPlaced(BlockPos pos) {
        event("Kristall auf " + pos.toShortString());
    }

    private boolean isSwordCrystalSequence() {
        return drill == Drill.HIT_CRYSTAL || drill == Drill.D_TAP || drill == Drill.TRIPLE_TAP || drill == Drill.TAP_ANCHOR;
    }

    public void crystalBlast(ServerPlayer owner, LivingEntity bot, Vec3 origin, Vec3 botPos, float botDamage, float selfDamage, boolean hurtWindowOpen) {
        crystalBlast(owner, bot, origin, botPos, botDamage, selfDamage, hurtWindowOpen, !bot.onGround() || bot.getDeltaMovement().y > 0.02);
    }

    public void crystalBlast(ServerPlayer owner, LivingEntity bot, Vec3 origin, Vec3 botPos, float botDamage, float selfDamage, boolean hurtWindowOpen, boolean airborne) {
        crystalBlast(owner, bot, origin, botPos, botDamage, selfDamage, hurtWindowOpen, airborne, Math.max(0, bot.invulnerableTime - 10));
    }

    public void crystalBlast(ServerPlayer owner, LivingEntity bot, Vec3 origin, Vec3 botPos, float botDamage, float selfDamage, boolean hurtWindowOpen, boolean airborne, int remainingHurtTicks) {
        crystalBlast(owner, bot, origin, botPos, botDamage, selfDamage, hurtWindowOpen, airborne, remainingHurtTicks, false);
    }

    public void crystalBlast(
        ServerPlayer owner,
        LivingEntity bot,
        Vec3 origin,
        Vec3 botPos,
        float botDamage,
        float selfDamage,
        boolean hurtWindowOpen,
        boolean airborne,
        int remainingHurtTicks,
        boolean poppedByThisBlast
    ) {
        if (done) return;
        explosions++;
        event("Kristall " + explosions + ": Ziel " + decimal(botDamage) + " LP · selbst " + decimal(selfDamage) + " LP");
        botHitOrigin = botPos;
        displacementAt = tick;
        if (drill == Drill.LEDGE_DASH
            && botDamage > 0.0F
            && startOwner != null
            && owner.getY() + 0.5 < startOwner.y
            && owner.getY() + 0.5 < bot.getY()) {
            finish(true, "Kristalltreffer von der tieferen Ebene");
        }

        if (!isSwordCrystalSequence()) return;
        boolean firstCrystal = crystalHits == 0;
        String label = "Kristall " + (crystalHits + 1);
        int gap = tick - (firstCrystal ? swordAt : lastCrystal);
        if (swordAt == NEVER) {
            timingFault(label + " zu früh: Nahkampftreffer 1 fehlt");
        } else if (needsAirSword()) {
            timingFault("Kristall 2 zu früh: Nahkampftreffer 2 in der Luft fehlt");
        } else if (gap > options.windowTicks()) {
            timingFault(label + " zu spät: " + gap + " Ticks seit dem letzten Treffer");
        } else if (firstCrystal && !(drill == Drill.HIT_CRYSTAL ? gap <= 10 : gap < 10 && !hurtWindowOpen)) {
            timingFault("Erster Kristall zu spät nach dem Nahkampftreffer (" + gap + " Ticks)");
        } else if (!(airborne && !openingLanded || !firstCrystal && !options.airborneFollowups())) {
            if (drill.requiresPopChain()) {
                finish(false, label + " traf nach dem Bodenkontakt.");
            } else {
                timingFault(label + " zu spät: Ziel bereits gelandet");
            }
        } else {
            boolean airSwordWindow = drill == Drill.TRIPLE_TAP && crystalHits == 1 && airSwordStartedWindow && tick - airSwordAt < 10;
            if (!firstCrystal && !hurtWindowOpen && !airSwordWindow) {
                String waitHint = remainingHurtTicks > 0 ? " · noch " + remainingHurtTicks + " Ticks warten" : " · Schadensfenster geschlossen";
                timingFault(label + " zu früh nach " + gap + " Ticks" + waitHint);
            } else if (drill.requiresPopChain() && !poppedByThisBlast) {
                finish(false, label + " hat kein Bot-Totem ausgelöst.");
            } else if (botDamage <= 0.0F && !poppedByThisBlast) {
                timingFault(label + " ohne wirksamen Treffer · Deckung und Abstand prüfen");
            } else {
                Vec3 side = new Vec3(origin.x - botPos.x, 0.0, origin.z - botPos.z);
                if (!firstCrystal && options.pingPong() && firstCrystalSide != null && firstCrystalSide.dot(side) >= 0.0) {
                    step("Kristall traf · Seitenwechsel fehlt");
                } else {
                    clearTimingFault();
                    firstCrystalSide = side;
                    crystalHits++;
                    lastCrystal = tick;
                    step(crystalHits + " wirksame Kristalltreffer");
                    if (drill == Drill.HIT_CRYSTAL
                        || drill == Drill.D_TAP && crystalHits == 2
                        || drill == Drill.TRIPLE_TAP && crystalHits == 3) {
                        finish(true, "Kristallfolge gelungen · " + crystalHits + " Treffer");
                    }
                }
            }
        }
    }
}

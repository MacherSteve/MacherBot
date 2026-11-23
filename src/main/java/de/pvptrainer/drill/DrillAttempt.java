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
    private int anchorPops;
    private int firstAnchorPopAt = NEVER;
    private boolean anchorPopped;
    private boolean maceCounterBlocked;
    private int maceBlockedAt = NEVER;
    private boolean cornerWebbed;
    private boolean cornerShieldBroken;
    private int cornerCrystalPops;
    private int lastCornerCrystalAt = NEVER;
    private boolean selfWebbed;
    private int selfWebPops;
    private int lastSelfWebPopAt = NEVER;
    private boolean hadGlide;
    private int pendingSlamLaunchAt = NEVER;
    private double pendingSlamStartY;
    private int pendingSlamAxeGap;
    private int lastPlayerShieldBlock = NEVER;
    private boolean pendingShieldReset;
    private int shieldCounters;
    private int autoHitLandedAt = NEVER;
    private int momentumResetAt = NEVER;
    private int dunkCritAt = NEVER;
    private boolean dunkWebCaught;
    private boolean dunkLavaCaught;
    private int antiDrainPushedAt = NEVER;
    private int playerWaterAt = NEVER;
    private BlockPos playerWaterPos;
    private int healPunishCrits;
    private boolean botHealing;
    private int pcritBreakFirst = NEVER;
    private boolean critThisTick;

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
            if (!success && lastTimingFault == null && (drill == Drill.STUN_SLAM || drill == Drill.ELY_STUN_SLAM) && !reason.contains("Mace") && !reason.contains("Axt")) {
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

        settleStunSlamLaunch(currentTick, owner);
        if (done) return;

        slam.observeFall(currentTick, owner.fallDistance);
        if (startOwner == null) {
            startOwner = owner.position();
        }
        uhc.initStartingFoodIfNeeded(drill, owner);

        if (swordAt != NEVER && isSwordCrystalSequence()) {
            tickSwordCrystalSequence(bot);
            if (done) return;
        }
        if (drill == Drill.DOUBLE_ANCHOR && anchorPops == 1 && tick - firstAnchorPopAt > options.windowTicks()) {
            finish(false, "Anker 2 zu spät (" + (tick - firstAnchorPopAt) + " Ticks, Fenster bis " + options.windowTicks() + ")");
            return;
        }
        if (drill == Drill.CORNER_WEB_DTAP) {
            if (!cornerWebbed && inWeb(bot)) {
                cornerWebbed = true;
                clearTimingFault();
                step("Bot im Corner-Web · Schild mit der Axt brechen");
            }
            if (cornerShieldBroken && cornerCrystalPops > 0 && tick - lastCornerCrystalAt > options.windowTicks()) {
                finish(false, "Kristall 2 zu spät (" + (tick - lastCornerCrystalAt) + " Ticks, Fenster bis " + options.windowTicks() + ")");
                return;
            }
            if (cornerShieldBroken && !slam.shieldWindowOpen(tick)) {
                finish(false, "Das Schild ist wieder aktiv · D-Tap nicht rechtzeitig abgeschlossen.");
                return;
            }
        }
        if (drill == Drill.SELF_WEB_CRYSTAL) {
            if (!selfWebbed && inWeb(bot)) {
                selfWebbed = true;
                clearTimingFault();
                step("Bot steht im Cobweb · 2 Boden-Crystal-Pops landen");
            }
            if (selfWebPops > 0 && tick - lastSelfWebPopAt > options.windowTicks()) {
                finish(false, "Boden-Crystal 2 zu spät (" + (tick - lastSelfWebPopAt) + " Ticks, Fenster bis " + options.windowTicks() + ")");
                return;
            }
            if (selfWebPops > 0 && !inWeb(bot)) {
                finish(false, "Der Bot hat das Cobweb vor dem zweiten Pop verlassen.");
                return;
            }
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
            if (pendingSlamLaunchAt == NEVER) {
                hadGlide = false;
            }
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
        } else if (drill.isObiTrap()) {
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
        if (drill == Drill.MOMENTUM_BREAK) {
            if (autoHitLandedAt != NEVER && momentumResetAt == NEVER && tick - autoHitLandedAt > 4) {
                finish(false, "Auto-Hit ohne Jump-Reset genommen · beim Auto-Hit springen");
                return;
            }
            if (slam.axeStunAt() != NEVER && !slam.shieldWindowOpen(tick)) {
                finish(false, "Schildfenster abgelaufen · schneller aufs Schwert wechseln");
                return;
            }
        }
        if (drill == Drill.WEB_DUNK && dunkCritAt != NEVER) {
            tickWebDunk(bot);
            if (done) return;
        }
        if (drill == Drill.ANTI_DRAIN) {
            if (playerWaterAt != NEVER && !inWeb(owner)) {
                if (antiDrainPushedAt != NEVER) {
                    finish(true, "Anti-Drain gelungen · Bot weggeschlagen & sicher aus dem Cobweb entkommen");
                } else {
                    finish(true, "Anti-Drain gelungen · sicher außer Reichweite gewatert & entkommen");
                }
                return;
            }
        }
        if (drill == Drill.DOUBLE_HAND) {
            defense.tickDoubleHand(this, tick, owner);
        }

        if (drill.series() && !drill.centeredMaceDrill() && drill != Drill.PERFECT_WIND_CHARGE
            && drill != Drill.RETOTEM && drill != Drill.STUN_SLAM && drill != Drill.ELY_STUN_SLAM && tick >= 1200) {
            finish(false, "Zeit abgelaufen");
        }
    }

    private void tickSwordCrystalSequence(LivingEntity bot) {
        if (bot.onGround() && !(bot.getDeltaMovement().y > 0.05)) {
            if (airborneTarget && !openingLanded) {
                openingLanded = true;
                if (drill.requiresAirPopChain()) {
                    finish(false, "Der Bot ist vor dem letzten Pop gelandet.");
                    return;
                }
                if (crystalHits == 0) {
                    timingFault("Kristall 1 zu spät: Ziel nach " + (tick - swordAt) + " Ticks gelandet");
                } else if (needsAirSword()) {
                    timingFault((drill == Drill.SHIELD_TRIPLE_TAP ? "Axt-Schildbruch" : "Nahkampftreffer 2")
                        + " zu spät: Ziel " + (tick - lastCrystal) + " Ticks nach Kristall 1 gelandet");
                } else if (options.airborneFollowups() && (drill != Drill.TAP_ANCHOR || crystalHits < 2)) {
                    timingFault("Kristall " + (crystalHits + 1) + " zu spät: Ziel bereits gelandet");
                }
            }
        } else {
            airborneTarget = true;
        }

        int elapsed = tick - (crystalHits == 0 ? swordAt : lastCrystal);
        int maxWindow = crystalHits == 0
            ? (drill == Drill.D_TAP || drill == Drill.TRIPLE_TAP
                ? Math.max(14, options.windowTicks())
                : (drill == Drill.HIT_CRYSTAL ? 10 : 12))
            : options.windowTicks();
        if (elapsed > maxWindow && !done) {
            String nextStep = needsAirSword()
                ? (drill == Drill.SHIELD_TRIPLE_TAP ? "Axt-Schildbruch" : "Nahkampftreffer 2")
                : (drill == Drill.TAP_ANCHOR && crystalHits == 2 ? "Anker" : "Kristall " + (crystalHits + 1));
            finish(false, lastTimingFault != null ? lastTimingFault : nextStep + " zu spät (" + elapsed + " Ticks, Fenster bis " + maxWindow + ")");
        }
    }

    public void settleAnchor(ServerPlayer owner, int currentTick) {
        if (!done && anchorAt != NEVER && currentTick > anchorAt) {
            this.tick = currentTick;
            double selfLoss = Math.max(anchorSelfDamage, anchorOwnerHealth - (owner == null ? 0.0 : health(owner)));
            event("Anker: Bot " + decimal(anchorDamage) + " LP · selbst " + decimal(selfLoss) + " LP" + (anchorPopped ? " · Pop" : ""));
            if (drill == Drill.DOUBLE_ANCHOR) {
                if (anchorPopped) {
                    if (anchorPops == 0) {
                        anchorPops = 1;
                        firstAnchorPopAt = currentTick;
                        clearTimingFault();
                        step("Anker-Pop 1/2 · Zweiten Anker zünden");
                    } else if (currentTick - firstAnchorPopAt <= options.windowTicks()) {
                        anchorPops = 2;
                        finish(true, "Double Anchor gelungen · 2 Anker-Pops (" + (currentTick - firstAnchorPopAt) + " Ticks)");
                    } else {
                        finish(false, "Anker 2 zu spät (" + (currentTick - firstAnchorPopAt) + " Ticks, Fenster bis " + options.windowTicks() + ")");
                    }
                } else if (anchorPops >= 1) {
                    finish(false, "Anker 2 hat kein Bot-Totem ausgelöst.");
                } else {
                    timingFault("Anker 1 hat kein Bot-Totem ausgelöst (" + decimal(anchorDamage) + " LP)");
                }
            } else if (!(anchorDamage > 0.0)
                || (drill != Drill.TAP_ANCHOR || !validAnchorOpening)
                    && (drill != Drill.SAFE_ANCHOR || !covered || !(anchorDamage > selfLoss))) {
                if (drill == Drill.SAFE_ANCHOR || drill == Drill.TAP_ANCHOR) {
                    finish(false, "Anker: Deckung, Eröffnung oder Schadensvorteil fehlte");
                }
            } else {
                finish(true, "Ankerfolge gelungen");
            }
            anchorAt = NEVER;
            anchorPopped = false;
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
        if (drill == Drill.WEB_DUNK && dunkCritAt == NEVER) {
            timingFault("Zuerst einen kritischen Treffer landen");
            return;
        }
        uhc.lavaPlaced(this, drill, tick, pos);
    }

    public void webPlaced(BlockPos pos) {
        if (drill == Drill.WEB_DUNK && dunkCritAt == NEVER) {
            timingFault("Zuerst einen kritischen Treffer landen");
            return;
        }
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
        if (drill == Drill.JUMP_RESET || drill == Drill.PUNISH_CRIT || drill == Drill.MOMENTUM_BREAK) {
            event("Eigener Sprung");
        }
        if (drill == Drill.MOMENTUM_BREAK && autoHitLandedAt != NEVER && Math.abs(tick - autoHitLandedAt) <= 2 && momentumResetAt == NEVER) {
            momentumResetAt = tick;
            clearTimingFault();
            step("Jump-Reset auf Auto-Hit · jetzt Schwert-Anschluss landen!");
        }
    }

    public void glideStarted() {
        if ((drill == Drill.ELYTRA_SMASH || drill == Drill.ELY_STUN_SLAM) && !done) {
            wasGliding = true;
            hadGlide = true;
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
        if (drill == Drill.HEAL && options.cleanHeal() && owner != null && owner.isUsingItem() && UhcUtilityTracker.heals(owner.getUseItem())) {
            finish(false, "Treffer während des Essens · erst Abstand schaffen (Sauberer Heal)");
            return;
        }
        if (drill == Drill.SPACING) {
            finish(false, "Bot landete den Gegentreffer");
        }
        if (drill == Drill.HIT_SELECT && swordHits > 0) {
            finish(false, "Gegentreffer vor dem Anschluss");
        }
        if (drill == Drill.PCRIT_BREAK && pcritBreakFirst != NEVER) {
            finish(false, "Gegentreffer vor dem Anschluss nach Sprint-KB-Break");
        }
        if (drill == Drill.SHIELD_TIMING) {
            pendingShieldReset = true;
            lastPlayerShieldBlock = NEVER;
            step("Zwischentreffer kassiert · erst Schild halten bis der Bot dein Schild trifft");
        }
        if (drill == Drill.MOMENTUM_BREAK && slam.axeStunAt() != NEVER && autoHitLandedAt == NEVER) {
            autoHitLandedAt = tick;
            if (Math.abs(jumpAt - tick) <= 2) {
                momentumResetAt = tick;
                clearTimingFault();
                step("Jump-Reset auf Auto-Hit · jetzt Schwert-Anschluss landen!");
            } else {
                step("Bot Auto-Hit · jetzt Jump-Reset & Schwert!");
            }
        }
        if (drill != Drill.JUMP_RESET || ownerKnockback == null) {
            lastOwnerHit = tick;
            ownerKnockback = owner == null ? Vec3.ZERO : owner.position();
        }
    }

    public void playerShieldBlocked() {
        if (done || drill != Drill.SHIELD_TIMING) return;
        lastPlayerShieldBlock = tick;
        pendingShieldReset = false;
        clearTimingFault();
        if (swordAt != NEVER && tick - swordAt <= 4) {
            step("Insta-Shield nach " + (tick - swordAt) + " Ticks · jetzt kontern!");
        } else {
            step("Schildblock sauber · jetzt kontern!");
        }
    }

    public void axeNotAllowedInShieldTiming() {
        if (!done && drill == Drill.SHIELD_TIMING) {
            timingFault("Nur Schwert & Schild – ohne Axt üben!");
        }
    }

    public void playerWaterPlaced(BlockPos pos) {
        if (done) return;
        playerWaterAt = tick;
        playerWaterPos = pos == null ? null : pos.immutable();
        event("Spieler setzt Wasser");
    }

    public BlockPos playerWaterPos() {
        return playerWaterPos;
    }

    public int playerWaterAt() {
        return playerWaterAt;
    }

    public boolean antiDrainPushed() {
        return antiDrainPushedAt != NEVER;
    }

    public void antiDrainDrained() {
        if (!done && drill == Drill.ANTI_DRAIN) {
            finish(false, "Wasser vom Bot gedraint · im Cobweb erst wegschlagen, dann 180° hinter dir watern!");
        }
    }

    public void botStartedHealing() {
        if (!done && drill == Drill.HEAL_PUNISH && !botHealing) {
            botHealing = true;
            step("Bot isst Goldapfel · mit 2 Crits bestrafen!");
        }
    }

    public void botHealFinished() {
        if (!done && drill == Drill.HEAL_PUNISH) {
            finish(false, "Bot hat ungestört gegappt (" + healPunishCrits + "/2 Crits) · aggressiver bestrafen");
        }
    }

    public int healPunishCrits() {
        return healPunishCrits;
    }

    private void tickWebDunk(LivingEntity bot) {
        if (tick - dunkCritAt > options.windowTicks()) {
            finish(false, "Dunk-Fenster nach Crit abgelaufen (" + options.windowTicks() + " Ticks)");
            return;
        }
        boolean webCaught = uhc.lastWebAt() >= dunkCritAt && bot != null && inWeb(bot);
        boolean lavaCaught = uhc.lastLavaAt() >= dunkCritAt && bot != null && UhcUtilityTracker.touchesLava(bot);
        if (webCaught) dunkWebCaught = true;
        if (lavaCaught) dunkLavaCaught = true;
        switch (options.dunkType()) {
            case WEB -> {
                if (dunkWebCaught) finish(true, "Web Dunk nach Crit gelungen (" + (tick - dunkCritAt) + " Ticks)");
            }
            case LAVA -> {
                if (dunkLavaCaught) finish(true, "Lava Dunk nach Crit gelungen (" + (tick - dunkCritAt) + " Ticks)");
            }
            case WEB_LAVA -> {
                if (dunkWebCaught && dunkLavaCaught) {
                    finish(true, "Web + Lava Dunk gelungen (" + (tick - dunkCritAt) + " Ticks)");
                } else if (dunkWebCaught) {
                    step("Bot im Cobweb · jetzt Lava darüber setzen!");
                }
            }
        }
    }

    public boolean needsAirSword() {
        return drill == Drill.SHIELD_TRIPLE_TAP && crystalHits == 1 && airSwordAt == NEVER;
    }

    public boolean acceptsAirComboTap() {
        return (drill == Drill.D_TAP || drill == Drill.TRIPLE_TAP || drill == Drill.SHIELD_TRIPLE_TAP || drill == Drill.MACE_COUNTER_DTAP)
            && crystalHits > 0 && !openingLanded;
    }

    public void confirmSelfWeb() {
        if (drill == Drill.SELF_WEB_CRYSTAL && !selfWebbed) {
            selfWebbed = true;
            clearTimingFault();
            step("Bot hat sich eingewebt · 2 Boden-Crystal-Pops landen");
        }
    }

    public boolean isSelfWebbed() {
        return selfWebbed;
    }

    public boolean maceCounterBlocked() {
        return maceCounterBlocked;
    }

    public void maceCounterBlock(int currentTick) {
        this.tick = currentTick;
        if (done || drill != Drill.MACE_COUNTER_DTAP) return;
        maceCounterBlocked = true;
        maceBlockedAt = currentTick;
        clearTimingFault();
        step("Mace geblockt · Jetzt D-Tapen (2 Kristall-Pops)!");
    }

    public void maceCounterFailed(int currentTick) {
        this.tick = currentTick;
        if (done || drill != Drill.MACE_COUNTER_DTAP) return;
        finish(false, "Mace-Smash nicht mit dem Schild geblockt");
    }

    public void confirmCornerWeb(boolean webbed) {
        if (drill == Drill.CORNER_WEB_DTAP && webbed && !cornerWebbed) {
            cornerWebbed = true;
            clearTimingFault();
            step("Bot im Corner-Web · Schild mit der Axt brechen");
        }
    }

    public boolean isCornerWebbed() {
        return cornerWebbed;
    }

    public boolean isCornerShieldBroken() {
        return cornerShieldBroken && slam.shieldWindowOpen(tick);
    }

    public int crystalHits() {
        return crystalHits;
    }

    void swordHit(ServerPlayer owner, LivingEntity bot, float damage, float charge) {
        swordContact(owner, bot, damage, charge, !bot.onGround() || bot.getDeltaMovement().y > 0.02, true, true);
    }

    public void swordContact(ServerPlayer owner, LivingEntity bot, float damage, float charge, boolean airborne, boolean dealtDamage, boolean hurtWindowOpen) {
        if (done) return;
        if (drill == Drill.MACE_COUNTER_DTAP && !maceCounterBlocked) {
            timingFault("Zuerst den Mace-Smash mit dem Schild blocken");
            return;
        }
        if (drill.requiresAirPopChain() && swordAt != NEVER && airborneTarget && bot != null && bot.onGround() && !(bot.getDeltaMovement().y > 0.05)) {
            finish(false, "Der Bot ist vor dem letzten Pop gelandet.");
            return;
        }
        if (needsAirSword()) {
            timingFault("Schild nach Pop aktiv · in der Luft mit der Axt brechen");
            return;
        }
        if ((drill == Drill.D_TAP || drill == Drill.TRIPLE_TAP || drill == Drill.SHIELD_TRIPLE_TAP || drill == Drill.MACE_COUNTER_DTAP)
            && crystalHits > 0 && airborne && recent(lastCrystal) && !openingLanded) {
            airSwordAt = tick;
            airSwordStartedWindow = dealtDamage && hurtWindowOpen;
            clearTimingFault();
            int gap = tick - lastCrystal;
            step("Nahkampftreffer " + (crystalHits + 1) + " in der Luft · "
                + (owner != null && owner.getMainHandItem().is(Items.TOTEM_OF_UNDYING) ? "Totem · " : "Schwert · ")
                + gap + (gap == 1 ? " Tick" : " Ticks") + " nach Kristall " + crystalHits + " · "
                + (dealtDamage ? decimal(damage) + " LP" : "Kontakt im Trefferschutz"));
            return;
        }
        if (!dealtDamage) {
            if ((drill == Drill.TRIPLE_TAP || drill == Drill.SHIELD_TRIPLE_TAP) && swordAt != NEVER && crystalHits == 0) {
                timingFault((drill == Drill.SHIELD_TRIPLE_TAP ? "Axt-Schildbruch" : "Nahkampftreffer 2") + " zu früh: Zuerst Kristall 1 zünden");
            }
            return;
        }
        boolean withTotem = owner != null && owner.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        if (isSwordCrystalSequence() && drill != Drill.D_TAP && drill != Drill.TRIPLE_TAP && withTotem && (!owner.isSprinting() || charge <= 0.9F)) {
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
        if (drill == Drill.UPPERCUT) {
            boolean fromLower = owner != null && bot != null && owner.getY() < bot.getY() - 0.15;
            boolean inKnockback = lastOwnerHit != NEVER && tick - lastOwnerHit <= 12;
            if (fromLower) {
                finish(true, "Uppercut aus tieferer Position gelungen");
            } else if (inKnockback) {
                finish(true, "Uppercut-Konter im Rückstoß gelungen (" + (tick - lastOwnerHit) + " Ticks)");
            } else if (lastOwnerHit == NEVER) {
                finish(false, "Erst den Rückstoß annehmen oder von tieferer Position unterhalb des Bots treffen");
            } else {
                finish(false, "Uppercut außerhalb des Rückstoßfensters oder nicht unterhalb des Bots");
            }
        }
        if (drill == Drill.SHIELD_TIMING) {
            if (pendingShieldReset) {
                finish(false, "Nach Zwischentreffer nicht panisch schlagen · erst Schild halten bis der Bot dein Schild trifft");
            } else if (lastPlayerShieldBlock != NEVER && tick - lastPlayerShieldBlock <= 14) {
                shieldCounters++;
                lastPlayerShieldBlock = NEVER;
                if (shieldCounters >= 3) {
                    finish(true, "Shield Timing gelungen · 3 saubere Schild-Konter");
                } else {
                    step("Sauberer Schild-Konter " + shieldCounters + "/3");
                }
            } else {
                step("Treffer gelandet · jetzt Schild-Timing gegen den Bot halten");
            }
        }
        if (drill == Drill.MOMENTUM_BREAK) {
            if (slam.axeStunAt() == NEVER) {
                timingFault("Zuerst das Schild des Bots mit der Axt brechen");
            } else if (!slam.shieldWindowOpen(tick)) {
                finish(false, "Schildfenster abgelaufen · schneller aufs Schwert wechseln");
            } else if (owner != null && !owner.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS)) {
                timingFault("Direkt nach dem Stun aufs Schwert (1) wechseln");
            } else if (momentumResetAt != NEVER || (autoHitLandedAt != NEVER && Math.abs(jumpAt - autoHitLandedAt) <= 2)) {
                finish(true, "Momentum Break gelungen · Axt-Stun + Jump-Reset + Schwert-Anschluss");
            } else if (autoHitLandedAt != NEVER) {
                finish(false, "Jump-Reset auf den Bot-Auto-Hit fehlte");
            } else {
                step("Schwert-Anschluss vor Auto-Hit · beim Auto-Hit Jump-Reset ausführen");
            }
        }
        if (drill == Drill.ANTI_DRAIN) {
            antiDrainPushedAt = tick;
            clearTimingFault();
            step("Bot weggeschlagen · jetzt 180° hinter dir watern!");
        }
        if (drill == Drill.PCRIT_BREAK) {
            if (lastOwnerHit == NEVER) {
                finish(false, "Erst den Bot-Treffer annehmen und die Serie dann brechen");
            } else if (pcritBreakFirst == NEVER) {
                boolean sprintKb = (owner == null || owner.isSprinting()) && charge > 0.848F;
                if (sprintKb && tick - lastOwnerHit <= 16) {
                    pcritBreakFirst = tick;
                    clearTimingFault();
                    step("Sprint-KB-Break 1/2 · jetzt Anschluss landen");
                } else {
                    timingFault("Kein Sprint-KB-Treffer · kurz Abstand schaffen und voll ansprinten (>85 %)");
                }
            } else if (tick - pcritBreakFirst <= options.windowTicks()) {
                finish(true, "P-Crit Break gelungen · Serie per Sprint-KB gebrochen");
            } else {
                finish(false, "Anschluss nach Sprint-KB-Break zu spät");
            }
        }
    }

    public void critical() {
        event("Kritischer Treffer");
        if (drill == Drill.PUNISH_CRIT && recent(lastOwnerHit) && jumpAt < lastOwnerHit && swordAt == tick) {
            finish(true, "Kritischer Konter aus gegnerischem Rückstoß");
        }
        if (drill == Drill.WEB_DUNK && !done) {
            dunkCritAt = tick;
            dunkWebCaught = false;
            dunkLavaCaught = false;
            clearTimingFault();
            step("Crit gelandet · jetzt " + switch (options.dunkType()) {
                case WEB -> "Cobweb";
                case LAVA -> "Lava";
                case WEB_LAVA -> "Cobweb + Lava";
            } + " platzieren!");
        }
        if (drill == Drill.HEAL_PUNISH && !done) {
            healPunishCrits++;
            clearTimingFault();
            if (healPunishCrits >= 2) {
                finish(true, "Heal-Punish gelungen · " + healPunishCrits + " Crits gegen den Heal");
            } else {
                step("Heal-Punish Crit " + healPunishCrits + "/2");
            }
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
        if (drill == Drill.SHIELD_TRIPLE_TAP && crystalHits == 1 && airSwordAt == NEVER && !openingLanded) {
            if (recent(lastCrystal)) {
                airSwordAt = tick;
                airSwordStartedWindow = false;
                clearTimingFault();
                int gap = tick - lastCrystal;
                step("Schild in der Luft gebrochen · " + gap + (gap == 1 ? " Tick" : " Ticks") + " nach Kristall 1 · noch 2 Kristalle");
            } else {
                timingFault("Axt-Schildbruch zu spät: Kombofenster vorbei");
            }
        } else if (drill == Drill.CORNER_WEB_DTAP) {
            if (cornerWebbed) {
                cornerShieldBroken = true;
                cornerCrystalPops = 0;
                lastCornerCrystalAt = tick;
                clearTimingFault();
                step("Schild gebrochen · Jetzt D-Tapen (0/2 Pops)");
            } else {
                timingFault("Zuerst den Bot in der Ecke einwebben");
            }
        }
    }

    public boolean recentlyFell() {
        return slam.recentlyFell(tick);
    }

    public void observeFall(int currentTick, double fallDistance) {
        this.tick = currentTick;
        slam.observeFall(currentTick, fallDistance);
    }

    public boolean awaitingSlamLaunch() {
        return pendingSlamLaunchAt != NEVER && !done;
    }

    public boolean beginStunSlamLaunch(ServerPlayer owner, boolean targetBlocking) {
        if (owner == null) return false;
        boolean hasWindBurst = owner.getMainHandItem().getEnchantments().keySet().stream().anyMatch(e -> e.is(Enchantments.WIND_BURST));
        return beginStunSlamLaunch(owner.fallDistance, owner.getY(), owner.onGround(), targetBlocking, hasWindBurst);
    }

    public boolean beginStunSlamLaunch(double fallDistance, double startY, boolean onGround, boolean targetBlocking, boolean hasWindBurst) {
        if (done || (drill != Drill.STUN_SLAM && drill != Drill.ELY_STUN_SLAM)) return false;
        if (targetBlocking || !slam.shieldWindowOpen(tick)) {
            maceContact(fallDistance, onGround, fallDistance > 1.5);
            return false;
        }
        if (fallDistance <= 1.5) {
            maceContact(fallDistance, onGround, false);
            return false;
        }
        if (drill == Drill.ELY_STUN_SLAM && !hadGlide && !wasGliding && flightEnded == NEVER) {
            timingFault("Ely-Stun-Slam: zuerst den Elytra-Gleitflug starten und beenden");
            return false;
        }
        if (!hasWindBurst) {
            timingFault("Voller Stun Slam braucht Wind Burst · nutze die Wind-Burst-Mace für den Auftrieb");
            return false;
        }
        pendingSlamLaunchAt = tick;
        pendingSlamStartY = startY;
        pendingSlamAxeGap = Math.max(0, tick - slam.axeStunAt());
        clearTimingFault();
        event("Mace-Smash · Fallstrecke " + decimal(fallDistance) + " Bl. · prüfe Wind-Burst-Auftrieb");
        return true;
    }

    public void settleStunSlamLaunch(int currentTick, ServerPlayer owner) {
        if (owner == null) return;
        settleStunSlamLaunch(currentTick, owner.getDeltaMovement().y, owner.getY());
    }

    public void settleStunSlamLaunch(int currentTick, double velocityY, double currentY) {
        if (done || pendingSlamLaunchAt == NEVER) return;
        this.tick = currentTick;
        if (velocityY > 0.25 || currentY > pendingSlamStartY + 0.45) {
            pendingSlamLaunchAt = NEVER;
            String prefix = drill == Drill.ELY_STUN_SLAM ? "Ely-Stun-Slam" : "Stun Slam";
            finish(true, prefix + " + Wind Burst gelungen · " + SlamWindowTracker.ticksText(pendingSlamAxeGap) + " nach der Axt");
        } else if (currentTick - pendingSlamLaunchAt > 5) {
            pendingSlamLaunchAt = NEVER;
            timingFault("Kein Wind-Burst-Auftrieb · Spieler nicht wieder in die Luft katapultiert");
        }
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
        anchorPopped = false;
        anchorOwnerHealth = owner == null ? 0.0 : health(owner);
        anchorDamage = anchorSelfDamage = 0.0;
        covered = hasCover;
        event("Geladener Anker bei " + pos.toShortString() + " gezündet");
    }

    public void anchorPop() {
        if (!done && anchorAt != NEVER) {
            anchorPopped = true;
        }
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
        return drill == Drill.HIT_CRYSTAL
            || drill == Drill.D_TAP
            || drill == Drill.TRIPLE_TAP
            || drill == Drill.SHIELD_TRIPLE_TAP
            || drill == Drill.TAP_ANCHOR
            || drill == Drill.MACE_COUNTER_DTAP;
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

        if (drill == Drill.CORNER_WEB_DTAP) {
            handleCornerWebCrystal(bot, hurtWindowOpen, remainingHurtTicks, poppedByThisBlast);
            return;
        }
        if (drill == Drill.SELF_WEB_CRYSTAL) {
            handleSelfWebCrystal(bot, origin, botPos, botDamage, hurtWindowOpen, remainingHurtTicks, poppedByThisBlast);
            return;
        }
        if (!isSwordCrystalSequence()) return;
        if (drill == Drill.MACE_COUNTER_DTAP) {
            if (!maceCounterBlocked) {
                timingFault("Zuerst den Mace-Smash mit dem Schild blocken");
                return;
            }
            // If the bot is still airborne right after the player blocked its mace smash, allow direct Crystal 1 -> Crystal 2.
            if (swordAt == NEVER && airborne && tick - maceBlockedAt <= 14) {
                swordAt = tick;
                airborneTarget = true;
                openingLanded = false;
            }
        }
        boolean firstCrystal = crystalHits == 0;
        boolean relaxedPopChain = drill == Drill.D_TAP || drill == Drill.TRIPLE_TAP;
        if (relaxedPopChain && firstCrystal && swordAt == NEVER && poppedByThisBlast) {
            swordAt = tick;
            airborneTarget = airborne || bot == null || !bot.onGround() || bot.getDeltaMovement().y > 0.02;
            openingLanded = false;
        }
        String label = "Kristall " + (crystalHits + 1);
        int gap = tick - (firstCrystal ? swordAt : lastCrystal);
        boolean liftedByOpeningPop = firstCrystal && relaxedPopChain && poppedByThisBlast
            && (airborne || bot == null || !bot.onGround() || bot.getDeltaMovement().y > 0.02);
        boolean firstGapAllowed = relaxedPopChain && poppedByThisBlast
            ? gap <= Math.max(14, options.windowTicks())
            : (drill == Drill.HIT_CRYSTAL || drill == Drill.MACE_COUNTER_DTAP && swordAt == tick
                ? gap <= 10
                : gap < 10 && !hurtWindowOpen);
        if (swordAt == NEVER) {
            timingFault(label + " zu früh: Nahkampftreffer 1 fehlt");
        } else if (needsAirSword()) {
            timingFault("Kristall 2 zu früh: Schild nach Pop zuerst mit der Axt brechen");
        } else if (gap > options.windowTicks()) {
            timingFault(label + " zu spät: " + gap + " Ticks seit dem letzten Treffer");
        } else if (firstCrystal && !firstGapAllowed) {
            timingFault("Erster Kristall zu spät nach dem Nahkampftreffer (" + gap + " Ticks)");
        } else if (!(airborne && !openingLanded || liftedByOpeningPop || !firstCrystal && !options.airborneFollowups())) {
            if (drill.requiresAirPopChain()) {
                finish(false, label + " traf nach dem Bodenkontakt.");
            } else {
                timingFault(label + " zu spät: Ziel bereits gelandet");
            }
        } else {
            boolean airSwordWindow = (drill == Drill.TRIPLE_TAP || drill == Drill.SHIELD_TRIPLE_TAP || drill == Drill.D_TAP)
                && crystalHits >= 1
                && airSwordStartedWindow && airSwordAt >= lastCrystal && tick - airSwordAt < 10;
            if (!firstCrystal && !hurtWindowOpen && !airSwordWindow && !(relaxedPopChain && poppedByThisBlast)) {
                String waitHint = remainingHurtTicks > 0 ? " · noch " + remainingHurtTicks + " Ticks warten" : " · Schadensfenster geschlossen";
                timingFault(label + " zu früh nach " + gap + " Ticks" + waitHint);
            } else if (drill.requiresAirPopChain() && !poppedByThisBlast) {
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
                    if (liftedByOpeningPop) {
                        airborneTarget = true;
                    }
                    if (drill == Drill.SHIELD_TRIPLE_TAP && crystalHits == 1) {
                        step("Pop 1 · Bot hebt Schild! Mit der Axt brechen");
                    } else {
                        step(crystalHits + " wirksame Kristalltreffer");
                    }
                    if (drill == Drill.HIT_CRYSTAL
                        || (drill == Drill.D_TAP || drill == Drill.MACE_COUNTER_DTAP) && crystalHits == 2
                        || (drill == Drill.TRIPLE_TAP || drill == Drill.SHIELD_TRIPLE_TAP) && crystalHits == 3) {
                        String doneText = switch (drill) {
                            case MACE_COUNTER_DTAP -> "Mace geblockt + D-Tap gelungen · 2 Pops";
                            case SHIELD_TRIPLE_TAP -> "Shield-Triple-Tap gelungen · 3 Pops";
                            case D_TAP -> "D-Tap gelungen · 2 Pops";
                            case TRIPLE_TAP -> "Triple Tap gelungen · 3 Pops";
                            default -> "Kristallfolge gelungen · " + crystalHits + " Treffer";
                        };
                        finish(true, doneText);
                    }
                }
            }
        }
    }

    private void handleSelfWebCrystal(
        LivingEntity bot,
        Vec3 origin,
        Vec3 botPos,
        float botDamage,
        boolean hurtWindowOpen,
        int remainingHurtTicks,
        boolean poppedByThisBlast
    ) {
        boolean webbed = bot != null ? inWeb(bot) : selfWebbed;
        if (webbed && !selfWebbed) {
            selfWebbed = true;
        }
        if (!webbed) {
            if (selfWebPops > 0) {
                finish(false, "Der Bot steht nicht mehr im Cobweb.");
            } else {
                timingFault("Warte kurz, bis der Bot im Cobweb steht");
            }
            return;
        }
        boolean floorLevel = origin == null || botPos == null || origin.y <= botPos.y + 0.5;
        if (!floorLevel) {
            timingFault("Boden-Crystal nötig: Obsidian in den Boden auf Fußhöhe des Bots setzen");
            return;
        }
        if (selfWebPops > 0 && !hurtWindowOpen && !poppedByThisBlast) {
            String waitHint = remainingHurtTicks > 0 ? " · noch " + remainingHurtTicks + " Ticks warten" : " · Schadensfenster geschlossen";
            timingFault("Boden-Crystal 2 zu früh" + waitHint);
            return;
        }
        if (!poppedByThisBlast) {
            if (selfWebPops > 0) {
                finish(false, "Boden-Crystal 2 hat kein Bot-Totem ausgelöst.");
            } else {
                step("Boden-Crystal traf (" + decimal(botDamage) + " LP) · Pop 1 noch offen");
            }
            return;
        }
        selfWebPops++;
        lastSelfWebPopAt = tick;
        clearTimingFault();
        if (selfWebPops >= 2) {
            finish(true, "Self-Web Boden-Crystal gelungen · 2 Pops");
        } else {
            step("Boden-Crystal Pop 1/2 · Zweiten Pop landen");
        }
    }

    private void handleCornerWebCrystal(LivingEntity bot, boolean hurtWindowOpen, int remainingHurtTicks, boolean poppedByThisBlast) {
        if (bot != null && inWeb(bot)) {
            confirmCornerWeb(true);
        }
        if (!cornerWebbed) {
            timingFault("Zuerst den Bot in der Ecke mit einem Cobweb fangen");
            return;
        }
        if (!cornerShieldBroken || !slam.shieldWindowOpen(tick)) {
            timingFault("Zuerst das Schild des Bots mit der Axt brechen");
            return;
        }
        if (bot != null && !inWeb(bot)) {
            timingFault("Der Bot steht nicht mehr im Cobweb");
            return;
        }
        if (cornerCrystalPops > 0 && !hurtWindowOpen) {
            String waitHint = remainingHurtTicks > 0 ? " · noch " + remainingHurtTicks + " Ticks warten" : " · Schadensfenster geschlossen";
            timingFault("Kristall " + (cornerCrystalPops + 1) + " zu früh" + waitHint);
            return;
        }
        if (!poppedByThisBlast) {
            finish(false, "Kristall " + (cornerCrystalPops + 1) + " hat kein Bot-Totem ausgelöst.");
            return;
        }
        cornerCrystalPops++;
        lastCornerCrystalAt = tick;
        clearTimingFault();
        if (cornerCrystalPops >= 2) {
            finish(true, "Corner Web, Shield Break + D-Tap gelungen · 2 Pops");
        } else {
            step("Corner-Web D-Tap · 1/2 Pops");
        }
    }
}

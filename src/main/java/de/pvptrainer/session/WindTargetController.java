package de.pvptrainer.session;

import de.pvptrainer.analytics.BotTelemetry;
import de.pvptrainer.combat.BotEquipment;
import de.pvptrainer.combat.TrainingTactics;
import de.pvptrainer.drill.DrillAttempt;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.drill.WindPearlCatch;
import de.pvptrainer.kits.TrainingSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Steuert Flugzyklus, Perlen-Catch, Schild-Timing und Plattform des Bots im Wind-Stun-Slam-Drill. */
final class WindTargetController {
    private enum WindThrow { LIFT, PEARL, CATCH }

    private static final int WIND_ITEM_SWITCH_TICKS = 2;
    private static final int MIN_APEX_CATCH_DELAY = 5;

    private final WindPearlCatch windCatch = new WindPearlCatch();
    private WindCharge targetCharge;
    private WindThrow windThrow;
    private int windEquipAt;
    private boolean waitingForWindApex;
    private boolean windLiftAirborne;
    private int nextWindLaunch = 1;
    private BlockPos windPlatform;
    private double windBaseY;
    private int windThrowAt;
    private int windEquipShieldAt;
    private int windGuardAt;
    private boolean airborneAxeStun;
    private boolean scoredWindFlight;

    void setBaseY(double baseY) {
        this.windBaseY = baseY;
    }

    void placeAirLockPlatform(ServerLevel arena, BlockPos belowBot) {
        this.windPlatform = belowBot;
        arena.setBlockAndUpdate(belowBot, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
    }

    int guardReadyAt() {
        return windGuardAt;
    }

    boolean isAirborne(LivingEntity bot) {
        double base = windPlatform == null ? windBaseY : windPlatform.getY() + 1;
        return !bot.onGround() && bot.getY() > base + 0.15;
    }

    boolean isAirborneAxeStun() {
        return airborneAxeStun;
    }

    void markAirborneAxeStun() {
        this.airborneAxeStun = true;
    }

    void clearAirborneAxeStun() {
        this.airborneAxeStun = false;
    }

    boolean isScoredWindFlight() {
        return scoredWindFlight;
    }

    void markScoredWindFlight() {
        this.scoredWindFlight = true;
    }

    void tick(
        int roundTick,
        boolean waitingForStart,
        ServerLevel arena,
        LivingEntity bot,
        ServerPlayer owner,
        BotEquipment equipment,
        DrillOptions options,
        TrainingSettings settings,
        DrillAttempt attempt,
        Runnable onMissedLanding
    ) {
        if (!bot.onGround()) nextWindLaunch = roundTick + 12;
        windCatch.tick();
        if (waitingForWindApex) {
            if (!bot.onGround()) {
                windLiftAirborne = true;
                // Two ticks to switch items. Throw only once the upward movement actually ends.
                if (bot.getDeltaMovement().y <= 0.16) {
                    waitingForWindApex = false;
                    windEquipShieldAt = 0;
                    windThrow = WindThrow.PEARL;
                    equipWindThrow(roundTick, bot, equipment, attempt);
                    return;
                }
            } else if (windLiftAirborne || roundTick >= nextWindLaunch) {
                waitingForWindApex = false;
            }
        }
        if (windEquipAt != 0) {
            if (roundTick >= windEquipAt) {
                windEquipAt = 0;
                equipWindThrow(roundTick, bot, equipment, attempt);
            }
            return;
        }
        if (windEquipShieldAt != 0) {
            if (roundTick >= windEquipShieldAt) {
                equipment.equipWeapon(bot, Items.SHIELD);
                // Let equipment tracking reach the client before sending the using-item flag.
                windGuardAt = roundTick + 3;
                windEquipShieldAt = 0;
            }
            return;
        }
        if (windThrowAt != 0) {
            if (roundTick < windThrowAt) return;
            windThrowAt = 0;
            switch (windThrow) {
                case LIFT -> {
                    if (bot.onGround() && throwWindCharge(arena, bot, equipment)
                        && options.windFlight() == DrillOptions.WindFlight.CHARGE_AND_CATCH) {
                        waitingForWindApex = true;
                        windLiftAirborne = false;
                    }
                }
                case PEARL -> {
                    boolean combined = options.windFlight() == DrillOptions.WindFlight.CHARGE_AND_CATCH;
                    if (combined && !bot.onGround() && bot.getDeltaMovement().y > 0) {
                        windThrowAt = roundTick + 1;
                        return;
                    }
                    if ((!combined || !bot.onGround()) && windCatch.throwPearl(arena, bot, equipment)) {
                        if (!combined) scoredWindFlight = false;
                        windThrow = WindThrow.CATCH;
                        int throwDelay = combined ? MIN_APEX_CATCH_DELAY : 4;
                        windEquipAt = roundTick + throwDelay - WIND_ITEM_SWITCH_TICKS;
                        return;
                    }
                }
                case CATCH -> {
                    if (!windCatch.throwCharge(arena, bot, equipment, options.windFlight() == DrillOptions.WindFlight.CHARGE_AND_CATCH)) {
                        windThrowAt = roundTick + 1;
                        return;
                    }
                }
            }
            windEquipShieldAt = roundTick + 3;
            return;
        }
        if (!bot.onGround() || windCatch.inFlight() || waitingForWindApex) return;
        if (airborneAxeStun) {
            attempt.finish(false, "Der Bot ist nach deinem Axt-Stun vor dem Smash gelandet.");
            onMissedLanding.run();
        }
        if (!waitingForStart && options.slamScene() == DrillOptions.SlamScene.FREE && settings.counterAttacks()
            && roundTick == nextWindLaunch - 5 && TrainingTactics.withinReach(bot, owner) && equipment.has(bot, Items.MACE)) {
            bot.stopUsingItem();
            equipment.equipWeapon(bot, Items.MACE);
            TrainingTactics.strike(arena, bot, owner, bot.damageSources().mobAttack(bot), 0);
            windEquipShieldAt = roundTick + 2;
            return;
        }
        if (roundTick < nextWindLaunch) return;
        nextWindLaunch = roundTick + 12;
        if (!equipment.has(bot, Items.WIND_CHARGE) || !equipment.has(bot, Items.SHIELD)) {
            attempt.defenseUnavailable("Bot-Kit aufgebraucht · Windladung und Schild werden benötigt.");
            return;
        }
        windThrow = options.windFlight() == DrillOptions.WindFlight.CATCH ? WindThrow.PEARL : WindThrow.LIFT;
        equipWindThrow(roundTick, bot, equipment, attempt);
    }

    private void equipWindThrow(int roundTick, LivingEntity bot, BotEquipment equipment, DrillAttempt attempt) {
        var item = windThrow == WindThrow.PEARL ? Items.ENDER_PEARL : Items.WIND_CHARGE;
        bot.stopUsingItem();
        if (equipment.equipWeapon(bot, item)) {
            windThrowAt = roundTick + WIND_ITEM_SWITCH_TICKS;
        } else {
            attempt.defenseUnavailable("Bot-Kit aufgebraucht · "
                + (item == Items.ENDER_PEARL ? "Enderperle fehlt." : "Windladung fehlt."));
            windEquipShieldAt = roundTick + 1;
        }
    }

    private boolean throwWindCharge(ServerLevel arena, LivingEntity bot, BotEquipment equipment) {
        var charge = new WindCharge(arena, bot.getX(), bot.getY() + 0.5, bot.getZ(), new Vec3(0, -1, 0));
        charge.setOwner(bot);
        charge.shoot(0, -1, 0, 1.5F, 0);
        if (arena.addFreshEntity(charge)) {
            targetCharge = charge;
            // A new flight resets scoring. The shield still observes its vanilla axe cooldown.
            scoredWindFlight = false;
            bot.jumpFromGround();
            bot.swing(InteractionHand.MAIN_HAND, true);
            BotTelemetry.action(arena, bot, "WIND");
            equipment.takeOne(bot, Items.WIND_CHARGE);
            return true;
        }
        return false;
    }

    void clear(ServerLevel arena) {
        if (targetCharge != null) targetCharge.discard();
        targetCharge = null;
        windCatch.clear();
        windEquipAt = 0;
        waitingForWindApex = false;
        windLiftAirborne = false;
        nextWindLaunch = 1;
        if (windPlatform != null && arena != null && arena.getBlockState(windPlatform).is(Blocks.POLISHED_BLACKSTONE_BRICKS)) {
            arena.removeBlock(windPlatform, false);
        }
        windPlatform = null;
        windThrowAt = 0;
        windEquipShieldAt = 0;
        windGuardAt = 0;
        airborneAxeStun = false;
        scoredWindFlight = false;
    }
}

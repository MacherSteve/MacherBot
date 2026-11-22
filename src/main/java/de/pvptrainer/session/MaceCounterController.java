package de.pvptrainer.session;

import de.pvptrainer.analytics.BotTelemetry;
import de.pvptrainer.combat.BotEquipment;
import de.pvptrainer.combat.TrainingTactics;
import de.pvptrainer.drill.DrillAttempt;
import de.pvptrainer.drill.DrillOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.Vec3;

/** Steuert den Wind-Charge-Mace-Sprung des Bots im HugoSMP-Drill MACE_COUNTER_DTAP. */
final class MaceCounterController {
    private WindCharge activeCharge;
    private boolean inJumpAttack;
    private boolean smashResolved;
    private int nextLaunchAt = 5;
    private int jumpStartedAt;
    private int equipMaceAt;

    void reset(DrillOptions options) {
        clear();
        smashResolved = false;
        inJumpAttack = false;
        nextLaunchAt = Math.clamp(options.reactionTicks() / 2, 2, 10);
        jumpStartedAt = 0;
        equipMaceAt = 0;
    }

    boolean controlsMovement() {
        return !smashResolved;
    }

    void tick(
        int roundTick,
        ServerLevel arena,
        LivingEntity bot,
        ServerPlayer owner,
        BotEquipment equipment,
        DrillAttempt attempt
    ) {
        if (smashResolved || attempt.done()) return;

        Vec3 delta = owner.position().subtract(bot.position());
        Vec3 horizontal = new Vec3(delta.x, 0.0, delta.z);
        double dist = horizontal.length();
        Vec3 toward = dist > 1.0e-4 ? horizontal.scale(1.0 / dist) : Vec3.ZERO;

        if (inJumpAttack) {
            if (equipMaceAt > 0 && roundTick >= equipMaceAt) {
                equipment.equipWeapon(bot, Items.MACE);
                equipMaceAt = 0;
            }
            // Steer cleanly toward a landing spot ~1.8 blocks in front of the player so the bot never overshoots behind their shield.
            if (dist > 2.3) {
                moveToward(bot, toward, dist > 3.2);
            } else {
                bot.zza = 0.0F;
                bot.xxa = 0.0F;
                bot.setSprinting(false);
                Vec3 vel = bot.getDeltaMovement();
                double towardSpeed = vel.x * toward.x + vel.z * toward.z;
                if (towardSpeed > 0.08) {
                    bot.setDeltaMovement(vel.x * 0.65, vel.y, vel.z * 0.65);
                    bot.hurtMarked = true;
                }
            }

            boolean inMeleeReach = TrainingTactics.withinReach(bot, owner)
                && owner.getBoundingBox().distanceToSqr(bot.getEyePosition()) <= Math.pow(TrainingTactics.meleeReach(bot), 2);
            if (roundTick > jumpStartedAt + 4 && bot.getDeltaMovement().y < 0.0 && MaceItem.canSmashAttack(bot) && inMeleeReach) {
                equipment.equipWeapon(bot, Items.MACE);
                boolean ownerBlocking = owner.isBlocking();
                float hpBefore = DrillAttempt.health(owner);
                int popsBefore = attempt.playerPops();
                DamageSource maceSource = bot.damageSources().mace(bot);
                float bonus = Items.MACE.getAttackDamageBonus(owner, 6.0F, maceSource);
                boolean hurt = TrainingTactics.strike(arena, bot, owner, maceSource, bonus);
                float hpLoss = Math.max(0.0F, hpBefore - DrillAttempt.health(owner));
                boolean popped = attempt.playerPops() > popsBefore;

                inJumpAttack = false;
                smashResolved = true;
                bot.zza = 0.0F;
                bot.xxa = 0.0F;
                bot.setSprinting(false);

                if (ownerBlocking && hpLoss <= 0.01F && !popped) {
                    attempt.maceCounterBlock(roundTick);
                } else if (hurt || hpLoss > 0.01F || popped) {
                    bot.resetFallDistance();
                    bot.playSound(SoundEvents.MACE_SMASH_GROUND);
                    arena.sendParticles(ParticleTypes.CRIT, owner.getX(), owner.getY(0.6), owner.getZ(), 10, 0.3, 0.3, 0.3, 0.12);
                    attempt.maceCounterFailed(roundTick);
                } else {
                    // Edge case where hit did not register (e.g. obstacle); retry jump.
                    smashResolved = false;
                    nextLaunchAt = roundTick + 8;
                }
                return;
            }

            if (bot.onGround() && roundTick > jumpStartedAt + 6) {
                inJumpAttack = false;
                nextLaunchAt = roundTick + 10;
            }
            return;
        }

        // Approach on the ground until comfortably in wind-charge jump range.
        if (dist > 7.2) {
            moveToward(bot, toward, true);
            return;
        }
        bot.zza = 0.0F;
        bot.xxa = 0.0F;
        bot.setSprinting(false);

        if (!bot.onGround() || roundTick < nextLaunchAt) return;
        if (!equipment.has(bot, Items.WIND_CHARGE) || !equipment.has(bot, Items.MACE)) {
            attempt.defenseUnavailable("Bot-Kit braucht Windladung und Mace.");
            return;
        }
        if (launchWindCharge(arena, bot, equipment, toward, dist, roundTick)) {
            inJumpAttack = true;
            jumpStartedAt = roundTick;
            equipMaceAt = roundTick + 2;
        } else {
            nextLaunchAt = roundTick + 5;
        }
    }

    private boolean launchWindCharge(
        ServerLevel arena,
        LivingEntity bot,
        BotEquipment equipment,
        Vec3 toward,
        double dist,
        int roundTick
    ) {
        if (!equipment.equipWeapon(bot, Items.WIND_CHARGE)) return false;
        Vec3 origin = bot.position().add(-toward.x * 0.45, 0.35, -toward.z * 0.45);
        WindCharge charge = new WindCharge(arena, origin.x, origin.y, origin.z, new Vec3(0.0, -1.0, 0.0));
        charge.setOwner(bot);
        charge.shoot(0.0, -1.0, 0.0, 1.5F, 0.0F);
        if (!arena.addFreshEntity(charge)) return false;

        activeCharge = charge;
        bot.jumpFromGround();
        double forwardBoost = Math.clamp((dist - 1.5) * 0.055, 0.08, 0.28);
        Vec3 current = bot.getDeltaMovement();
        bot.setDeltaMovement(toward.x * forwardBoost, Math.max(current.y, 0.42), toward.z * forwardBoost);
        bot.hurtMarked = true;
        bot.swing(InteractionHand.MAIN_HAND, true);
        BotTelemetry.action(arena, bot, "WIND");
        equipment.takeOne(bot, Items.WIND_CHARGE);
        nextLaunchAt = roundTick + 24;
        return true;
    }

    private static void moveToward(LivingEntity bot, Vec3 toward, boolean sprint) {
        if (toward.lengthSqr() < 1.0e-6) {
            bot.zza = 0.0F;
            bot.xxa = 0.0F;
            bot.setSprinting(false);
            return;
        }
        double yaw = Math.toRadians(bot.getYRot());
        bot.zza = (float) (-toward.x * Math.sin(yaw) + toward.z * Math.cos(yaw));
        bot.xxa = (float) (toward.x * Math.cos(yaw) + toward.z * Math.sin(yaw));
        bot.setSprinting(sprint && bot.zza > 0.5F && !bot.isUsingItem());
        bot.setJumping(bot.horizontalCollision);
    }

    void clear() {
        if (activeCharge != null && !activeCharge.isRemoved()) {
            activeCharge.discard();
        }
        activeCharge = null;
        inJumpAttack = false;
        smashResolved = false;
    }
}

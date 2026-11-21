package de.pvptrainer.combat;
import de.pvptrainer.analytics.BotTelemetry;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.session.RoundPing;
import de.pvptrainer.kits.TrainingSettings;

import java.util.ArrayDeque;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** State belongs to one free sword duel and is discarded with its opponent. */
public final class SwordDuel {
    private final DuelOpening opening;
    SwordDuel(DuelOpening opening) { this.opening = opening; }
    private enum Exchange { PRESSURE, FALLING_HIT, STEP_OUT, SPRINT_IN }
    private Exchange exchange = Exchange.PRESSURE;
    private Vec3 lastSeen;
    private Vec3 velocity = Vec3.ZERO;
    private int seenTick = -100, contactTick = -1, trackingReady, lastSwing = -100;
    private long nextSwing;
    private double flankSide = 1.0;
    private double weaponPeriod;
    private int jumpedAt = -100;
    private boolean seekingCrit;
    private final ArrayDeque<Vec3> observations = new ArrayDeque<>();
    private AABB expectedTarget;
    private int whiffReadAt = -1, counterWindowEnds, sprintAfterWhiffUntil;
    private int shieldSeen, swingSeen, recentThreat = -100;
    private enum ComboResponse { NONE, BACK_OUT, HIT_SELECT }
    private ComboResponse response = ComboResponse.NONE;
    private int incomingAt = -100, incomingInterval = 13, unansweredHits;
    private int nextDefense, defenseEnds, predictedHit, selectAfter = -1;
    private boolean resetJumped;

    public void incomingMelee(int tick) {
        if (tick == incomingAt) return;
        int interval = tick - incomingAt;
        if (interval <= 28) {
            unansweredHits++;
            incomingInterval = Math.clamp(interval, 8, 24);
        } else unansweredHits = 1;
        incomingAt = tick;
        if (response == ComboResponse.HIT_SELECT) selectAfter = tick;
    }

    public void steer(LivingEntity bot, ServerPlayer player, TrainingSettings settings, DrillOptions options, int tick) {
        DuelSkill skill = DuelSkill.of(settings);
        Vec3 toward = horizontal(RoundPing.position(player).subtract(bot.position())).normalize();
        boolean visible = bot.hasLineOfSight(player) && bot.getLookAngle().horizontal().dot(toward) >= -.20;
        if (visible) {
            observations.addLast(RoundPing.position(player));
            if (observations.size() >= skill.readTicks()) observe(bot, observations.removeFirst(), skill, tick);
        } else observations.clear();
        boolean blocked = visible && RoundPing.shield(player, player.isBlocking() && player.getLookAngle().horizontal().dot(toward) < 0);
        shieldSeen = blocked ? shieldSeen + 1 : 0;
        swingSeen = visible && RoundPing.swing(player) ? swingSeen + 1 : 0;
        if (swingSeen == Math.min(skill.readTicks(), 6)) recentThreat = tick;
        expectedTarget = null;
        if (visible && lastSeen != null && seenTick == tick) {
            Vec3 expected = lastSeen.add(velocity.scale(skill.lead() * skill.readTicks()));
            expectedTarget = RoundPing.box(player).move(expected.subtract(RoundPing.position(player)));
        }
        // Contact is the fighter's estimate, not a signal from the opponent's true hitbox.
        double judgedReach = TrainingTactics.meleeReach(bot) + .15 * (1 - skill.lead());
        boolean contact = expectedTarget != null && expectedTarget.distanceToSqr(bot.getEyePosition()) <= judgedReach * judgedReach;
        if (!contact) contactTick = -1;
        else if (contactTick < 0) contactTick = tick;

        if (lastSeen == null || tick - seenTick > 12) {
            move(bot, Vec3.ZERO, false);
            if (!visible && options.gaze() == DrillOptions.Gaze.TRACK) {
                bot.setYRot(bot.getYRot() + (float)flankSide * Math.min(6, options.turnRate()));
                bot.setYHeadRot(bot.getYRot());
                bot.setYBodyRot(bot.getYRot());
            }
            return;
        }
        Vec3 direction = horizontal(lastSeen.subtract(bot.position()));
        double distance = direction.length();
        Vec3 forward = direction.normalize(), side = new Vec3(forward.z, 0, -forward.x);
        double retreat = velocity.dot(forward), lateral = velocity.dot(side);
        if (visible && options.gaze() == DrillOptions.Gaze.TRACK) {
            float yaw = bot.getYRot(), pitch = bot.getXRot();
            // Aim slightly ahead of a crossing player, within the current hitbox at ordinary sprint speed.
            Vec3 aim = lastSeen.add(0, player.getBbHeight() * .50, 0).add(velocity.scale(skill.lead() * skill.readTicks()));
            bot.lookAt(Anchor.EYES, aim);
            float turn = Math.min(options.turnRate(), skill.turn());
            bot.setYRot(Mth.approachDegrees(yaw, bot.getYRot(), turn));
            bot.setXRot(Mth.approachDegrees(pitch, bot.getXRot(), turn));
        }
        bot.setYHeadRot(bot.getYRot());
        bot.setYBodyRot(bot.getYRot());
        if (settings.movement() == TrainingSettings.Movement.STILL) {
            move(bot, Vec3.ZERO, false);
            return;
        }

        // A receding opponent opens a sprint lane. A lateral dodge can be cut off from the side.
        if (!visible) {
            whiffReadAt = -1;
            sprintAfterWhiffUntil = 0;
            seekingCrit = false;
            exchange = Exchange.PRESSURE;
            move(bot, distance > 1.7 ? forward : Vec3.ZERO, false);
            return;
        }
        if (!options.holdShield() && escapeCombo(bot, player, skill, tick, forward, distance)) return;
        // A visible swing from just outside reach commits the opponent's weapon. Re-enter while it recharges.
        // The observer sees the swing, the held weapon and their own absence of knockback.
        if (swingSeen == 1 && distance > 3.42 && distance < 4.85 && bot.hurtTime == 0
                && player.getLookAngle().horizontal().dot(forward) < -.45
                && (player.getMainHandItem().is(ItemTags.SWORDS) || player.getMainHandItem().is(ItemTags.AXES))) {
            double speed = player.getMainHandItem().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                    ItemAttributeModifiers.EMPTY).compute(Attributes.ATTACK_SPEED, 4, EquipmentSlot.MAINHAND);
            whiffReadAt = tick + skill.readTicks();
            counterWindowEnds = tick + (int)Math.ceil(20 / Math.max(.1, speed)) - 2;
        }
        if (whiffReadAt >= 0 && tick >= whiffReadAt) {
            whiffReadAt = -1;
            double travelTicks = Math.max(0, distance - (options.botReach() - .1)) / .26;
            if (bot.hurtTime == 0 && Math.max(nextSwing, tick + travelTicks + skill.switchTicks()) <= counterWindowEnds
                    && retreat < .10 && !options.holdShield()) {
                opening.commit();
                seekingCrit = false;
                exchange = Exchange.SPRINT_IN;
                sprintAfterWhiffUntil = counterWindowEnds;
                BotTelemetry.action((ServerLevel)bot.level(), bot, "SWORD_REENTRY");
            }
        }
        if (sprintAfterWhiffUntil > tick && bot.hurtTime == 0 && distance > 1.55 && safeStep(bot, forward)) {
            move(bot, forward, true);
            return;
        }
        Vec3 entryStep = opening.steer(bot, skill, options, settings.movement(), tick, forward, distance,
                velocity, player.getLookAngle().horizontal());
        if (entryStep != null) {
            move(bot, entryStep, false);
            return;
        }
        if (opening.sprintIn(tick) && distance > 1.65 && safeStep(bot, forward)) {
            move(bot, forward, true);
            return;
        }
        boolean chase = distance > options.botReach() + .1 || retreat > 0.075 && distance > Math.min(1.9, options.botReach());
        if (velocity.lengthSqr() > 0.09 * 0.09) exchange = Exchange.PRESSURE;
        if (exchange == Exchange.STEP_OUT) {
            double opponentReach = Math.clamp(player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE), 2, 4);
            double resetDistance = opponentReach + (bot.getBbWidth() + player.getBbWidth()) * 0.5 + 0.55;
            Vec3 back = forward.scale(-1);
            if (distance < resetDistance && tick - lastSwing < 20 && safeStep(bot, back)) {
                move(bot, back, false);
                return;
            }
            exchange = Exchange.SPRINT_IN;
        }
        double spacing = Math.min(1.65 + .78 * skill.lead(), options.botReach() - .22);
        double advance = chase ? 1 : Math.clamp((distance - spacing) * 1.45, -0.7, 0.85);
        double across = Math.clamp(lateral * (1.1 + 2.3 * skill.lead()), -0.88, 0.88);
        if (!chase && settings.movement() == TrainingSettings.Movement.STRAFE && Math.abs(lateral) < 0.075) {
            // Flank on the side away from the opponent's visible aim.
            double facingSide = player.getViewVector(1).dot(side);
            if (Math.abs(facingSide) > 0.25) flankSide = -Math.signum(facingSide);
            across = (.23 + .29 * skill.lead()) * flankSide;
        }
        if (exchange == Exchange.SPRINT_IN && distance >= 1.55) {
            move(bot, forward, true);
        } else {
            move(bot, forward.scale(advance).add(side.scale(across)), chase && tick > lastSwing + 1);
        }
        if (chase || bot.onGround() && tick > jumpedAt + 2) seekingCrit = false;
        // A nearby opponent crossing our front or coming back into us can be met with a falling hit.
        // We commit the jump before the sword is ready and can abandon it if the opponent pulls away.
        boolean crossing = Math.abs(lateral) > 0.10 && Math.abs(lateral) > Math.max(0, retreat);
        boolean comingIn = retreat < -0.10;
        if (!chase && (crossing || comingIn || exchange == Exchange.FALLING_HIT) && distance >= 1.1 && distance < 3.0 && bot.onGround()
                && tick >= nextSwing - 3 && tick >= skill.opening(options) - 3 && tick > jumpedAt + 12
                && (skill.lead() >= .60 || exchange == Exchange.FALLING_HIT) && !options.holdShield()
                && canUseCritical(bot) && bot.level().noBlockCollision(bot, bot.getBoundingBox().expandTowards(0, 1.28, 0))) {
            if (bot.isUsingItem() && bot.getUseItem().is(Items.SHIELD)) bot.stopUsingItem();
            if (!bot.isUsingItem()) {
                bot.setSprinting(false);
                bot.jumpFromGround();
                jumpedAt = tick;
                seekingCrit = true;
            }
        }
    }

    private boolean escapeCombo(LivingEntity bot, ServerPlayer player, DuelSkill skill, int tick,
                                Vec3 forward, double distance) {
        Vec3 back = forward.scale(-1);
        if (response == ComboResponse.NONE && unansweredHits >= 2 && tick >= nextDefense
                && tick >= incomingAt + skill.switchTicks() && tick <= incomingAt + 12) {
            boolean canReset = canUseCritical(bot) && bot.level().noBlockCollision(bot, bot.getBoundingBox().expandTowards(0, 1.25, 0));
            // Knockback can already have lifted us when the combo is recognised.
            Vec3 nextFeet = bot.position().add(back.scale(.7));
            boolean canBackOut = bot.level().noBlockCollision(bot, bot.getBoundingBox().move(back.scale(.7)).deflate(.025))
                    && bot.level().clip(new ClipContext(nextFeet.add(0,.12,0), nextFeet.add(0,-3,0),
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot)).getType() == HitResult.Type.BLOCK;
            if (!canReset && !canBackOut) return false;
            response = canBackOut && (!canReset || bot.getRandom().nextDouble() < .55)
                    ? ComboResponse.BACK_OUT : ComboResponse.HIT_SELECT;
            predictedHit = incomingAt + incomingInterval;
            defenseEnds = response == ComboResponse.BACK_OUT ? tick + 12 : predictedHit + 4;
            nextDefense = tick + 22 + skill.switchTicks();
            selectAfter = -1;
            resetJumped = seekingCrit = false;
            unansweredHits = 0;
            opening.commit();
            exchange = Exchange.PRESSURE;
            if (bot.isUsingItem() && bot.getUseItem().is(Items.SHIELD)) bot.stopUsingItem();
            BotTelemetry.action((ServerLevel)bot.level(), bot, response == ComboResponse.BACK_OUT ? "SWORD_BACK_OUT" : "SWORD_HIT_SELECT");
        }
        if (response == ComboResponse.NONE) return false;
        double theirReach = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        if (tick > defenseEnds || distance > theirReach + .65 || selectAfter >= 0 && tick >= selectAfter + skill.switchTicks()) {
            response = ComboResponse.NONE;
            exchange = Exchange.SPRINT_IN;
            sprintAfterWhiffUntil = tick + 4;
            return false;
        }
        if (response == ComboResponse.BACK_OUT) {
            // Preserve external knockback. These are backwards movement inputs only.
            if (bot.onGround() && !safeStep(bot, back)) {
                response = ComboResponse.NONE;
                return false;
            }
            move(bot, back, false);
        } else {
            move(bot, distance > TrainingTactics.meleeReach(bot) ? forward : Vec3.ZERO, false);
            // Estimate the next swing from two real hits. Never jump again in the air or erase velocity.
            if (!resetJumped && tick >= predictedHit - 1 && tick <= predictedHit + 1 && bot.onGround()
                    && bot.level().noBlockCollision(bot, bot.getBoundingBox().expandTowards(0, 1.25, 0))) {
                bot.jumpFromGround();
                resetJumped = true;
                BotTelemetry.action((ServerLevel)bot.level(), bot, "SWORD_JUMP_RESET");
            }
        }
        return true;
    }

    private void observe(LivingEntity bot, Vec3 current, DuelSkill skill, int tick) {
        if (lastSeen != null && seenTick == tick - 1 && current.distanceToSqr(lastSeen) <= 1.5 * 1.5) {
            Vec3 sample = horizontal(current.subtract(lastSeen));
            Vec3 axis = horizontal(current.subtract(bot.position())).normalize();
            Vec3 across = new Vec3(axis.z, 0, -axis.x);
            double previousSide = velocity.dot(across), newSide = sample.dot(across);
            if (Math.abs(newSide) > 0.095 && (Math.abs(previousSide) < 0.045 || newSide * previousSide < -0.008)) {
                trackingReady = tick + skill.switchTicks();
            }
            velocity = velocity.scale(0.35).add(sample.scale(0.65));
        } else velocity = Vec3.ZERO;
        lastSeen = current;
        seenTick = tick;
    }

    private static Vec3 horizontal(Vec3 vector) { return new Vec3(vector.x, 0, vector.z); }

    private static boolean safeStep(LivingEntity bot, Vec3 direction) {
        var next = bot.getBoundingBox().move(direction.scale(0.7)).deflate(0.025);
        return bot.level().noBlockCollision(bot, next) && !bot.level().noBlockCollision(bot, next.move(0, -0.35, 0));
    }

    private Exchange nextExchange(LivingEntity bot, ServerPlayer opponent, TrainingSettings settings, DuelSkill skill, int tick) {
        if (settings.movement() == TrainingSettings.Movement.STILL
                || velocity.lengthSqr() > 0.09 * 0.09) return Exchange.PRESSURE;
        // If their swing has just missed, take the inside line. If it is about to land,
        // break contact; otherwise alternate among pressure, a reset and the falling hit.
        boolean threatened = tick - recentThreat <= 6;
        double readiness = skill.lead();
        if (threatened && bot.getRandom().nextDouble() < readiness) {
            return opponent.swinging ? Exchange.STEP_OUT : Exchange.PRESSURE;
        }
        double roll = bot.getRandom().nextDouble();
        if (roll < .06 + .27 * readiness) return Exchange.STEP_OUT;
        if (roll < .15 + .48 * readiness) return Exchange.FALLING_HIT;
        return Exchange.PRESSURE;
    }

    private static void move(LivingEntity bot, Vec3 desired, boolean sprint) {
        Vec3 step = desired.lengthSqr() > 1 ? desired.normalize() : desired;
        double yaw = Math.toRadians(bot.getYRot());
        bot.zza = (float)(-step.x * Math.sin(yaw) + step.z * Math.cos(yaw));
        bot.xxa = (float)(step.x * Math.cos(yaw) + step.z * Math.sin(yaw));
        bot.setSprinting(sprint && bot.zza > 0.5 && !bot.isUsingItem());
        bot.setJumping(bot.horizontalCollision && step.lengthSqr() > 0.1);
    }

    void attack(ServerLevel level, LivingEntity bot, ServerPlayer player, TrainingSettings settings,
                DrillOptions options, BotEquipment equipment, int tick) {
        // Respect the player's continuous blocking and fixed-gaze practice settings.
        DuelSkill skill = DuelSkill.of(settings);
        if (options.holdShield() || tick < skill.opening(options) || opening.repositioning() || response != ComboResponse.NONE) return;
        int reacquire = Math.max(1, skill.switchTicks() / 2);
        float charge = weaponPeriod > 0 ? (float)Math.min(1, (tick - lastSwing + 0.5) / weaponPeriod) : 1;
        boolean escaping = contactTick >= 0 && skill.lead() >= .8 && bot.isSprinting()
                && tick < nextSwing && charge >= 0.82F && leavingReach(bot, player, nextSwing - tick);
        boolean ready = exchange != Exchange.STEP_OUT && contactTick >= 0 && tick >= contactTick + reacquire
                && tick >= trackingReady && (tick >= nextSwing || escaping);
        if (!ready) {
            if (!seekingCrit && !bot.isSprinting() && contactTick >= 0 && nextSwing - tick > 5 && !bot.isUsingItem() && equipment.equipShield(bot))
                bot.startUsingItem(InteractionHand.OFF_HAND);
            else if (bot.isUsingItem() && bot.getUseItem().is(Items.SHIELD) && (contactTick < 0 || nextSwing - tick <= 2))
                bot.stopUsingItem();
            return;
        }
        boolean falling = !bot.onGround() && bot.fallDistance > 0 && canUseCritical(bot) && !bot.isSprinting();
        if (seekingCrit && !falling && tick < jumpedAt + 11) return;
        // Recognising a missed swing is only useful if the follow-up lands inside real reach.
        // A novice may click slightly early; even an expert can still miss a moving target from a stale read.
        double uncertainty = 1 - skill.lead();
        double reach = TrainingTactics.meleeReach(bot);
        if (expectedTarget == null || !aimOnBox(level,bot,expectedTarget.inflate(.09 * uncertainty),reach + .15 * uncertainty)) return;
        boolean onTarget = aimOnBox(level, bot, RoundPing.box(player), reach);
        if (bot.isUsingItem() && bot.getUseItem().is(Items.SHIELD)) bot.stopUsingItem();
        if (bot.isUsingItem()) return;
        Vec3 towardBot = horizontal(bot.position().subtract(RoundPing.position(player))).normalize();
        boolean blockingUs = player.getItemBlockingWith() != null && player.getViewVector(1).dot(towardBot) > 0;
        if (!blockingUs || shieldSeen < skill.readTicks() || !equipment.equipAxe(bot)) {
            if (!equipment.equipSword(bot)) equipment.equipAxe(bot);
        }
        var weapon = bot.getMainHandItem();
        var modifiers = weapon.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double speed = modifiers.compute(Attributes.ATTACK_SPEED, 4.0, EquipmentSlot.MAINHAND);
        if (!(speed > 0)) return;
        // Mannequins do not have the player's attack ticker. Derive full recharge from the equipped weapon.
        weaponPeriod = 20.0 / speed;
        int recharge = Math.max(1, (int)Math.ceil(weaponPeriod));
        charge = lastSwing < 0 ? 1 : (float)Math.clamp((tick - lastSwing + 0.5) / weaponPeriod, 0, 1);
        if (lastSwing >= 0 && !escaping && charge < 1) {
            nextSwing = (long)lastSwing + recharge;
            return;
        }
        int pause = skill.swordPause() + bot.getRandom().nextInt(3 + skill.switchTicks()/2);
        var damage = bot.damageSources().mobAttack(bot);
        Vec3 beforeHit = player.getDeltaMovement();
        boolean sprintHit = bot.isSprinting() && charge > 0.9F;
        boolean critical = falling && charge > 0.9F;
        if (!onTarget) {
            // This click saw a plausible opening, but the opponent has already left the swing.
            bot.swing(InteractionHand.MAIN_HAND, true);
            BotTelemetry.action(level,bot,"WHIFF");
        } else if (TrainingTactics.strikeCharged(level, bot, player, damage, 0, charge, critical)) {
            unansweredHits = 0;
            float knockback = EnchantmentHelper.modifyKnockback(level, weapon, player, damage, 0);
            bot.causeExtraKnockback(player, knockback * 0.5F + (sprintHit ? 0.5F : 0), beforeHit, damage, 0, true);
            if (critical) {
                bot.playSound(SoundEvents.PLAYER_ATTACK_CRIT);
                level.sendParticles(ParticleTypes.CRIT, player.getX(), player.getY(0.6), player.getZ(), 8, 0.25, 0.3, 0.25, 0.08);
            } else if (sprintHit) bot.playSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK);
        }
        seekingCrit = false;
        sprintAfterWhiffUntil = 0;
        exchange = nextExchange(bot, player, settings, skill, tick);
        bot.setSprinting(false);
        lastSwing = tick;
        nextSwing = (long)tick + recharge + pause;
    }

    private boolean leavingReach(LivingEntity bot, ServerPlayer player, long ticksUntilReady) {
        Vec3 direction = horizontal(RoundPing.position(player).subtract(bot.position())).normalize();
        if (velocity.dot(direction) < 0.09) return false;
        double ahead = Math.min(ticksUntilReady, 3);
        Vec3 theirTravel = velocity.scale(ahead), ourTravel = horizontal(bot.getDeltaMovement()).scale(ahead);
        double reach = TrainingTactics.meleeReach(bot);
        return RoundPing.box(player).move(theirTravel).distanceToSqr(bot.getEyePosition().add(ourTravel)) > reach * reach;
    }

    private static boolean canUseCritical(LivingEntity bot) {
        return !bot.onClimbable() && !bot.isInWater() && !bot.isPassenger() && !bot.hasEffect(MobEffects.BLINDNESS);
    }

    private static boolean aimOnBox(ServerLevel level, LivingEntity bot, AABB box, double reach) {
        Vec3 eye = bot.getEyePosition();
        if (box.contains(eye)) return true;
        var impact = box.clip(eye, eye.add(bot.getViewVector(1).scale(reach)));
        if (impact.isEmpty()) return false;
        return level.clip(new ClipContext(eye, impact.get(), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, bot)).getType() == HitResult.Type.MISS;
    }
}

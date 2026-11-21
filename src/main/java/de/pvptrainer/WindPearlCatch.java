package de.pvptrainer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class WindPearlCatch {
    private ThrownEnderpearl pearl;
    private WindCharge charge;

    boolean throwPearl(ServerLevel level, LivingEntity bot, BotEquipment kit) {
        var shot = new ThrownEnderpearl(level, bot, bot.getMainHandItem().copyWithCount(1)) {
            @Override protected void onHit(HitResult hit) {
                boolean caught = hit instanceof EntityHitResult entity && entity.getEntity() == charge;
                super.onHit(hit);
                if (caught && bot.isAlive()) {
                    bot.setOnGround(false);
                    BotTelemetry.action(level, bot, "PEARL_CATCH");
                }
            }

            @Override public boolean shouldBeSaved() { return false; }
        };
        shot.shoot(0, 1, 0, 1.5F, 0);
        if (!level.addFreshEntity(shot)) return false;
        pearl = shot;
        kit.takeOne(bot, Items.ENDER_PEARL);
        bot.swing(InteractionHand.MAIN_HAND, true);
        float pitch = .4F / (level.getRandom().nextFloat() * .4F + .8F);
        level.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.ENDER_PEARL_THROW, SoundSource.NEUTRAL, .5F, pitch);
        BotTelemetry.action(level, bot, "PEARL");
        return true;
    }

    boolean throwCharge(ServerLevel level, LivingEntity bot, BotEquipment kit, boolean afterApex) {
        if (!inFlight()) return true;
        Vec3 eye = bot.getEyePosition();
        Vec3 aim = intercept(eye).subtract(eye).normalize();
        var shot = new WindCharge(level, eye.x, eye.y, eye.z, aim);
        if (afterApex && pearl.tickCount < 14 && !crossesPearl(shot, aim.scale(1.5))) return false;
        shot.setOwner(bot);
        shot.shoot(aim.x, aim.y, aim.z, 1.5F, 0);
        if (!level.addFreshEntity(shot)) return true;
        charge = shot;
        kit.takeOne(bot, Items.WIND_CHARGE);
        bot.swing(InteractionHand.MAIN_HAND, true);
        BotTelemetry.action(level, bot, "WIND");
        return true;
    }

    // The pearl tests its movement segment against the charge's current box once per tick.
    // Two continuous trajectories can cross without either projectile registering a hit.
    private boolean crossesPearl(WindCharge shot, Vec3 step) {
        var box = shot.getBoundingBox().inflate(net.minecraft.world.entity.projectile.ProjectileUtil.DEFAULT_ENTITY_HIT_RESULT_MARGIN);
        Vec3 pearlPos = pearl.position(), velocity = pearl.getDeltaMovement();
        for (int tick = pearl.tickCount; tick <= 40; tick++) {
            velocity = velocity.add(0, -.03, 0).scale(.99);
            Vec3 next = pearlPos.add(velocity);
            if (box.clip(pearlPos, next).isPresent()) return true;
            pearlPos = next;
            box = box.move(step);
        }
        return false;
    }

    private Vec3 intercept(Vec3 origin) {
        // The pearl slows and falls. A charge travels 1.5 blocks per tick, even when the bot is strafing.
        Vec3 point = pearl.position();
        Vec3 velocity = pearl.getDeltaMovement();
        for (int ticks = 1; ticks <= 40; ticks++) {
            velocity = velocity.add(0, -.03, 0).scale(.99);
            point = point.add(velocity);
            double reach = 1.5 * ticks;
            if (origin.distanceToSqr(point) <= reach * reach) break;
        }
        return point;
    }

    boolean inFlight() { return pearl != null && !pearl.isRemoved(); }

    void tick() {
        if (pearl != null && (pearl.isRemoved() || pearl.tickCount > 40)) clear();
    }

    void clear() {
        if (pearl != null) pearl.discard();
        if (charge != null) charge.discard();
        pearl = null;
        charge = null;
    }
}

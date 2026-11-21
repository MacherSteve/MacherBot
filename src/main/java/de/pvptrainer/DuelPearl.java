package de.pvptrainer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** One real ender pearl at a time. Aim at walkable, visible ground in the training world. */
final class DuelPearl {
    private ThrownEnderpearl flying;
    private int readyAt;

    boolean throwAt(ServerLevel level, LivingEntity bot, BotEquipment kit, Vec3 landing, int tick) {
        if (tick < readyAt || flying != null && !flying.isRemoved() || bot.isUsingItem()
                || !TrainingArena.isArena(level) || !kit.has(bot,Items.ENDER_PEARL) || bot.getHealth() <= 6) return false;
        var column=BlockPos.containing(landing);
        if (!level.isLoaded(column) || !level.getWorldBorder().isWithinBounds(column)) return false;
        var floor=level.clip(new ClipContext(landing.add(0,1.0,0),landing.add(0,-2,0),
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,bot));
        if (floor.getType()!=HitResult.Type.BLOCK || floor.getDirection()!=net.minecraft.core.Direction.UP) return false;
        Vec3 point=floor.getLocation().add(0,.035,0);
        double distance=point.subtract(bot.position()).horizontalDistance();
        if (distance<3.0 || distance>18 || !level.getFluidState(BlockPos.containing(point)).isEmpty()
                || !level.noCollision(bot,bot.getBoundingBox().move(point.subtract(bot.position())))) return false;
        Vec3 eye=bot.getEyePosition();
        if (level.clip(new ClipContext(eye,point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,bot)).getType()!=HitResult.Type.MISS) return false;
        if (!kit.equipWeapon(bot,Items.ENDER_PEARL)) return false;
        var projectile=new ThrownEnderpearl(level,bot,bot.getMainHandItem().copyWithCount(1)){
            @Override protected void onHit(HitResult hit){
                Vec3 before=bot.position();
                super.onHit(hit);
                // Vanilla applies the pearl cost automatically only to ServerPlayer.
                if (bot.isAlive() && bot.level()==level && before.distanceToSqr(bot.position())>.01)
                    bot.hurtServer(level,bot.damageSources().enderPearl(),5);
            }
            @Override public boolean shouldBeSaved(){return false;}
        };
        double flight=distance/1.47;
        Vec3 aim=point.subtract(projectile.position()).add(0,.015*flight*(flight-1),0);
        projectile.shoot(aim.x,aim.y,aim.z,1.5F,0);
        if (!level.addFreshEntity(projectile)) return false;
        kit.takeOne(bot,Items.ENDER_PEARL);
        bot.swing(InteractionHand.MAIN_HAND,true);
        level.playSound(null,bot.blockPosition(),SoundEvents.ENDER_PEARL_THROW,SoundSource.PLAYERS,.6F,1);
        BotTelemetry.action(level,bot,"PEARL");
        flying=projectile;
        readyAt=tick+20;
        return true;
    }

    boolean inFlight(){return flying!=null && !flying.isRemoved();}

    void clear(){if(flying!=null)flying.discard();flying=null;}
}

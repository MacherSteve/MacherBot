package de.pvptrainer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.pvptrainer.session.ArenaSignals;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ThrownEnderpearl.class)
abstract class PearlMixin {
    @Unique
    private static boolean pvptrainer$airCatch(ServerPlayer owner, HitResult hit, Vec3 destination) {
        return hit instanceof EntityHitResult entity && entity.getEntity() instanceof WindCharge charge
            && charge.getOwner() == owner && owner.level().noCollision(owner,
                owner.getBoundingBox().move(destination.subtract(owner.position())).move(0, -.3, 0));
    }

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void pvptrainer$filterTrainingPearl(HitResult hit, CallbackInfo info) {
        var pearl = (ThrownEnderpearl)(Object)this;
        if (pearl.getOwner() instanceof ServerPlayer player && pearl.level() == player.level()
            && ArenaSignals.pearlCatchOnly(player) && !pvptrainer$airCatch(player, hit, pearl.oldPosition())) {
            pearl.discard();
            info.cancel();
        }
    }

    @WrapOperation(method = "onHit", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerPlayer;teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer pvptrainer$catch(ServerPlayer owner, TeleportTransition transition, Operation<ServerPlayer> original, HitResult hit) {
        var teleported = original.call(owner, transition);
        if (teleported != null && pvptrainer$airCatch(teleported, hit, teleported.position())) {
            ArenaSignals.caughtPearl(teleported);
        }
        return teleported;
    }
}

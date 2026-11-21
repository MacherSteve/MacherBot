package de.pvptrainer.hooks;

import de.pvptrainer.ArenaSignals;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WindCharge.class)
abstract class WindLaunchMixin {
   @Unique
   private double pvptrainer$beforeY;

   @Inject(method = "explode", at = @At("HEAD"))
   private void pvptrainer$before(Vec3 var1, CallbackInfo var2) {
      WindCharge var3 = (WindCharge)(Object)this;
      Entity var5;
      if ((var5 = var3.getOwner()) instanceof ServerPlayer) {
         ServerPlayer var4 = (ServerPlayer)var5;
         this.pvptrainer$beforeY = var4.getDeltaMovement().y;
      }
   }

   @Inject(method = "explode", at = @At("RETURN"))
   private void pvptrainer$launch(Vec3 var1, CallbackInfo var2) {
      WindCharge var3 = (WindCharge)(Object)this;
      Entity var5;
      if ((var5 = var3.getOwner()) instanceof ServerPlayer) {
         ServerPlayer var4 = (ServerPlayer)var5;
         if (var4.getDeltaMovement().y > this.pvptrainer$beforeY + 0.04 && var4.getDeltaMovement().y > 0.08) {
            ArenaSignals.windLaunch(var4);
         }
      }
   }
}

package de.pvptrainer.mixin;

import de.pvptrainer.arena.TrainingArena;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class ArenaPortalMixin {
   @Inject(method = "canUsePortal", at = @At("HEAD"), cancellable = true)
   private void pvptrainer$stayInArena(boolean var1, CallbackInfoReturnable<Boolean> var2) {
      Entity var3 = (Entity)(Object)this;
      Level var5;
      if ((var5 = var3.level()) instanceof ServerLevel) {
         ServerLevel var4 = (ServerLevel)var5;
         if (TrainingArena.isArena(var4)) {
            var2.setReturnValue(false);
         }
      }
   }
}

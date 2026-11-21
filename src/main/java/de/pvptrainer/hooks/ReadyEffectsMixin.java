package de.pvptrainer.hooks;

import de.pvptrainer.ReadyGate;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class ReadyEffectsMixin {
   @Inject(method = "tickEffects", at = @At("HEAD"), cancellable = true)
   private void pvptrainer$pauseEffects(CallbackInfo var1) {
      if (ReadyGate.holding((LivingEntity)(Object)this)) {
         var1.cancel();
      }
   }
}

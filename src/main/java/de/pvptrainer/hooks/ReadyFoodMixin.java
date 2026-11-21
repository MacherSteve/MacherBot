package de.pvptrainer.hooks;

import de.pvptrainer.ReadyGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FoodData.class)
abstract class ReadyFoodMixin {
   @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
   private void pvptrainer$pauseFood(ServerPlayer var1, CallbackInfo var2) {
      if (ReadyGate.holding(var1)) {
         var2.cancel();
      }
   }
}

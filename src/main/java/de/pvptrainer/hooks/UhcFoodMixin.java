package de.pvptrainer.hooks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.pvptrainer.TrainingArena;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FoodData.class)
abstract class UhcFoodMixin {
   @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isHurt()Z"))
   private boolean pvptrainer$regenerate(ServerPlayer var1, Operation<Boolean> var2) {
      return !TrainingArena.suppressRegeneration(var1) && (Boolean)var2.call(new Object[]{var1});
   }
}

package de.pvptrainer.hooks;

import de.pvptrainer.ArenaSignals;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Consumable.class)
abstract class MealMixin {
   @Inject(method = "onConsume", at = @At("HEAD"))
   private void pvptrainer$meal(Level var1, LivingEntity var2, ItemStack var3, CallbackInfoReturnable<ItemStack> var4) {
      if (var2 instanceof ServerPlayer var5) {
         ArenaSignals.ate(var5, var3);
      }
   }
}

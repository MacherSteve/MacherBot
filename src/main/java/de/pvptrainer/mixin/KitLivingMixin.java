package de.pvptrainer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.pvptrainer.drill.DrillEvents;
import de.pvptrainer.kits.TrainingKit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DeathProtection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class KitLivingMixin {
   @Inject(method = "startUsingItem", at = @At("RETURN"))
   private void pvptrainer$startUse(InteractionHand var1, CallbackInfo var2) {
      if ((Object)this instanceof ServerPlayer var3 && var3.isUsingItem()) {
         TrainingKit.used(var3);
      }
   }

   @WrapOperation(
      method = "hurtServer",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/entity/LivingEntity;checkTotemDeathProtection(Lnet/minecraft/world/damagesource/DamageSource;)Z"
      )
   )
   private boolean pvptrainer$resolveProtection(LivingEntity var1, DamageSource var2, Operation<Boolean> var3) {
      return var3.call(new Object[]{var1, var2}) ? true : var1 instanceof ServerPlayer var4 && DrillEvents.protectAfterTotemCheck(var4, var2);
   }

   @WrapOperation(
      method = "checkTotemDeathProtection",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/item/component/DeathProtection;applyEffects(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)V"
      )
   )
   private void pvptrainer$recordPop(DeathProtection var1, ItemStack var2, LivingEntity var3, Operation<Void> var4) {
      var4.call(new Object[]{var1, var2, var3});
      if (var3 instanceof ServerPlayer var5 && var5.isAlive()) {
         TrainingKit.popped(var5, var2);
      }
   }
}

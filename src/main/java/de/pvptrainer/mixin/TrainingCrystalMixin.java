package de.pvptrainer.mixin;

import de.pvptrainer.combat.TrainingCrystal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EndCrystal.class)
abstract class TrainingCrystalMixin {
   @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
   private void pvptrainer$handleTrainingHit(ServerLevel var1, DamageSource var2, float var3, CallbackInfoReturnable<Boolean> var4) {
      if ((Object)this instanceof TrainingCrystal var5) {
         var4.setReturnValue(var5.hitInTraining(var2));
      }
   }
}

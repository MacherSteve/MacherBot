package de.pvptrainer.mixin;

import de.pvptrainer.kits.TrainingKit;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancements.class)
abstract class KitAdvancementMixin {
   @Shadow
   private ServerPlayer player;

   @Inject(method = "award", at = @At("HEAD"), cancellable = true)
   private void pvptrainer$protectProgress(AdvancementHolder var1, String var2, CallbackInfoReturnable<Boolean> var3) {
      if (this.player != null && TrainingKit.active(this.player)) {
         var3.setReturnValue(false);
      }
   }
}

package de.pvptrainer.mixin;

import de.pvptrainer.TrainingKit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResult.Success;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
abstract class KitUseMixin {
   @Shadow
   @Final
   protected ServerPlayer player;
   @Unique
   private boolean pvptrainer$minedWithItem;

   @Inject(method = {"useItem", "useItemOn"}, at = @At("RETURN"))
   private void pvptrainer$acceptedUse(CallbackInfoReturnable<InteractionResult> var1) {
      InteractionResult var3;
      if ((var3 = (InteractionResult)var1.getReturnValue()) instanceof Success) {
         Success var2 = (Success)var3;
         if (var2.wasItemInteraction()) {
            TrainingKit.used(this.player);
         }
      }
   }

   @Inject(method = "destroyBlock", at = @At("HEAD"))
   private void pvptrainer$beforeMining(BlockPos var1, CallbackInfoReturnable<Boolean> var2) {
      this.pvptrainer$minedWithItem = !this.player.getMainHandItem().isEmpty();
   }

   @Inject(method = "destroyBlock", at = @At("RETURN"))
   private void pvptrainer$blockRemoved(BlockPos var1, CallbackInfoReturnable<Boolean> var2) {
      if ((Boolean)var2.getReturnValue() && this.pvptrainer$minedWithItem) {
         TrainingKit.used(this.player);
      }
   }
}

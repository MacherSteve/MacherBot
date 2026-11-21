package de.pvptrainer.mixin;

import de.pvptrainer.TrainingKit;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class KitInventoryMixin {
   @Shadow
   public ServerPlayer player;

   @Inject(method = "handleContainerClick", at = @At("RETURN"))
   private void pvptrainer$sort(ServerboundContainerClickPacket var1, CallbackInfo var2) {
      TrainingKit.sorted(this.player);
   }

   @Inject(method = "handlePlayerAction", at = @At("RETURN"))
   private void pvptrainer$swapHands(ServerboundPlayerActionPacket var1, CallbackInfo var2) {
      if (var1.getAction() == Action.SWAP_ITEM_WITH_OFFHAND) {
         TrainingKit.sorted(this.player);
      }
   }

   @Inject(
      method = "handleInteract",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/advancements/triggers/PlayerInteractTrigger;trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/Entity;)V"
      )
   )
   private void pvptrainer$successfulEntityInteraction(ServerboundInteractPacket var1, CallbackInfo var2) {
      TrainingKit.used(this.player);
   }
}

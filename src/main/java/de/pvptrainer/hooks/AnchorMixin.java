package de.pvptrainer.hooks;

import de.pvptrainer.ArenaSignals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RespawnAnchorBlock.class)
abstract class AnchorMixin {
   @Inject(
      method = "useWithoutItem",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/block/RespawnAnchorBlock;explode(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V"
      )
   )
   private void pvptrainer$anchor(BlockState var1, Level var2, BlockPos var3, Player var4, BlockHitResult var5, CallbackInfoReturnable<InteractionResult> var6) {
      if (var4 instanceof ServerPlayer var7) {
         ArenaSignals.anchor(var7, var3);
      }
   }
}

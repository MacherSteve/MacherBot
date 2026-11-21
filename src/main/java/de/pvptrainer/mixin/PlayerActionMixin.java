package de.pvptrainer.mixin;

import de.pvptrainer.session.ArenaSignals;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class PlayerActionMixin {
   @Inject(method = "checkFallDamage", at = @At("TAIL"))
   private void pvptrainer$landed(double dy, boolean onGround, net.minecraft.world.level.block.state.BlockState state,
                                  net.minecraft.core.BlockPos pos, CallbackInfo info) {
      if (onGround) ArenaSignals.landed((ServerPlayer)(Object)this);
   }

   @Inject(method = "jumpFromGround", at = @At("RETURN"))
   private void pvptrainer$jump(CallbackInfo var1) {
      ArenaSignals.jump((ServerPlayer)(Object)this);
   }

   @Inject(method = "crit", at = @At("HEAD"))
   private void pvptrainer$critical(Entity var1, CallbackInfo var2) {
      ArenaSignals.crit((ServerPlayer)(Object)this, var1);
   }
}

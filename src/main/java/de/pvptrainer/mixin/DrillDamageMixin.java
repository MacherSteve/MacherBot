package de.pvptrainer.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import de.pvptrainer.ArenaSignals;
import de.pvptrainer.DrillEvents;
import de.pvptrainer.TrainingArena;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class DrillDamageMixin {
   @Unique
   private float pvptrainer$beforeHit;

   @ModifyExpressionValue(
      method = "hurtServer",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getDifficulty()Lnet/minecraft/world/Difficulty;")
   )
   private Difficulty pvptrainer$arenaDamageDifficulty(Difficulty var1, ServerLevel var2) {
      return var1 == Difficulty.PEACEFUL && TrainingArena.isArena(var2) ? Difficulty.NORMAL : var1;
   }

   @Inject(method = "startFallFlying", at = @At("RETURN"))
   private void pvptrainer$confirmedGlide(CallbackInfo var1) {
      if ((Object)this instanceof ServerPlayer var2 && var2.isFallFlying()) {
         ArenaSignals.glide(var2);
      }
   }

   @Inject(method = "actuallyHurt", at = @At("HEAD"))
   private void pvptrainer$measureBefore(ServerLevel var1, DamageSource var2, float var3, CallbackInfo var4) {
      if ((Object)this instanceof ServerPlayer var5) {
         this.pvptrainer$beforeHit = var5.getHealth() + var5.getAbsorptionAmount();
      }
   }

   @Inject(method = "actuallyHurt", at = @At("RETURN"))
   private void pvptrainer$measureAfter(ServerLevel var1, DamageSource var2, float var3, CallbackInfo var4) {
      if ((Object)this instanceof ServerPlayer var5) {
         DrillEvents.actualPlayerDamage(var5, var2, this.pvptrainer$beforeHit - var5.getHealth() - var5.getAbsorptionAmount());
      }
   }
}

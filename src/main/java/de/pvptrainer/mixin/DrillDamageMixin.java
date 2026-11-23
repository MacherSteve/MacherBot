package de.pvptrainer.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import de.pvptrainer.session.ArenaSignals;
import de.pvptrainer.session.TrainingResources;
import de.pvptrainer.drill.DrillEvents;
import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.arena.TrainingTarget;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageScaling;
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
   private float pvptrainer$hpBefore;
   @Unique
   private float pvptrainer$absBefore;

   @ModifyExpressionValue(
      method = "hurtServer",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/damagesource/DamageSource;scalesWithDifficulty()Z")
   )
   private boolean pvptrainer$botAttacksLikePlayer(boolean scales, ServerLevel level, DamageSource source) {
      return scales
         && !(TrainingArena.isArena(level)
            && source.type().scaling() == DamageScaling.WHEN_CAUSED_BY_LIVING_NON_PLAYER
            && TrainingTarget.matches(source.getEntity()));
   }

   @ModifyExpressionValue(
      method = "hurtServer",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getDifficulty()Lnet/minecraft/world/Difficulty;")
   )
   private Difficulty pvptrainer$arenaDamageDifficulty(Difficulty var1, ServerLevel var2) {
      return TrainingArena.isArena(var2) ? TrainingArena.resolveDamageDifficulty(var2) : var1;
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
         this.pvptrainer$hpBefore = var5.getHealth();
         this.pvptrainer$absBefore = var5.getAbsorptionAmount();
      }
   }

   @Inject(method = "actuallyHurt", at = @At("RETURN"))
   private void pvptrainer$measureAfter(ServerLevel var1, DamageSource var2, float var3, CallbackInfo var4) {
      if ((Object)this instanceof ServerPlayer var5) {
         float dealt = Math.max(0.0F, (this.pvptrainer$hpBefore + this.pvptrainer$absBefore) - (var5.getHealth() + var5.getAbsorptionAmount()));
         if (TrainingResources.zeroDamageFor(var5)) {
            var5.setHealth(this.pvptrainer$hpBefore);
            var5.setAbsorptionAmount(this.pvptrainer$absBefore);
         }
         DrillEvents.actualPlayerDamage(var5, var2, dealt);
      }
   }
}

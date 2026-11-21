package de.pvptrainer;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;

public final class DrillEvents {
   static void register() {
      AttackEntityCallback.EVENT.register((AttackEntityCallback)(var0, var1, var2, var3, var4) -> {
         if (var0 instanceof ServerPlayer var5) {
            TrainingSession var6 = TrainerClient.session(var5.level().getServer());
            if (var6 != null) {
               var6.attackCharge(var5, var3, var5.getAttackStrengthScale(0.5F));
            }
         }

         return InteractionResult.PASS;
      });
   }

   public static boolean protectAfterTotemCheck(ServerPlayer var0, DamageSource var1) {
      TrainingSession var2 = TrainerClient.session(var0.level().getServer());
      return var2 != null && var2.preventTrainingDeath(var0, var1);
   }

   public static void actualPlayerDamage(ServerPlayer var0, DamageSource var1, float var2) {
      if (!(var2 <= 0.0F) && TrainingArena.isArena(var0.level())) {
         TrainingSession var3 = TrainerClient.session(var0.level().getServer());
         if (var3 != null) {
            var3.playerHurt(var0, var1, var2);
         }
      }
   }
}

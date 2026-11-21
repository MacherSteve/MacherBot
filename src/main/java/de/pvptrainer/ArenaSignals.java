package de.pvptrainer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

public final class ArenaSignals {
   private static TrainingSession active(ServerPlayer var0) {
      return TrainingArena.isArena(var0.level()) ? TrainerClient.session(var0.level().getServer()) : null;
   }

   public static void landed(ServerPlayer player) {
      webMovement(player, true);
   }

   public static void webMovement(ServerPlayer player, boolean grounded) {
      TrainingSession session = active(player);
      if (session != null && session.owns(player)) session.observeWebGround(grounded);
   }

   public static void jump(ServerPlayer var0) {
      TrainingSession var1 = active(var0);
      if (var1 != null) {
         var1.playerJump(var0);
      }
   }

   public static void crit(ServerPlayer var0, Entity var1) {
      TrainingSession var2 = active(var0);
      if (var2 != null) {
         var2.playerCrit(var0, var1);
      }
   }

   public static void ate(ServerPlayer var0, ItemStack var1) {
      TrainingSession var2 = active(var0);
      if (var2 != null) {
         var2.playerAte(var0, var1);
      }
   }

   public static boolean pearlCatchOnly(ServerPlayer player) {
      TrainingSession session = active(player);
      return session != null && session.pearlCatchOnly(player);
   }

   public static void caughtPearl(ServerPlayer var0) {
      TrainingSession var1 = active(var0);
      if (var1 != null) {
         var1.pearlCatch(var0);
      }
   }

   public static void glide(ServerPlayer var0) {
      TrainingSession var1 = active(var0);
      if (var1 != null) {
         var1.playerGlided(var0);
      }
   }

   public static void windLaunch(ServerPlayer var0) {
      TrainingSession var1 = active(var0);
      if (var1 != null) {
         var1.playerLaunched(var0);
      }
   }

   public static void anchor(ServerPlayer var0, BlockPos var1) {
      TrainingSession var2 = active(var0);
      if (var2 != null) {
         var2.anchorTriggered(var0, var1);
      }
   }

   public static void lava(ServerPlayer var0, BlockPos var1) {
      TrainingSession var2 = active(var0);
      if (var2 != null) {
         var2.lavaPlaced(var0, var1);
      }
   }

   public static void web(ServerPlayer var0, BlockPos var1) {
      TrainingSession var2 = active(var0);
      if (var2 != null) {
         var2.webPlaced(var0, var1);
      }
   }

   public static void water(ServerPlayer var0, BlockPos var1) {
      TrainingSession var2 = active(var0);
      if (var2 != null) {
         var2.waterDrawn(var0, var1);
      }
   }
}

package de.pvptrainer.arena;
import de.pvptrainer.compat.McCompat;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.TrainerClient;
import de.pvptrainer.session.TrainingSession;

import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AllowDamage;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public final class ReadyGate {
   private static long visibleToken;
   private static long clickedToken;
   private static boolean sent;
   private static boolean consumeJump;

   private ReadyGate() {
   }

   public static void register() {
      ClientTickEvents.START_CLIENT_TICK.register(ReadyGate::readJump);
      AttackEntityCallback.EVENT.register((AttackEntityCallback)(player, level, hand, target, hit) -> {
         if (TrainingTarget.matches(target)) {
            if (level.isClientSide() && TrainerClient.status().waitingForStart()) return InteractionResult.PASS;
            if (player instanceof ServerPlayer owner) {
               var session = TrainerClient.session(owner.level().getServer());
               if (session != null && session.owns(owner)
                     && session.status().waitingForStart()
                     && McCompat.withinAttackRange(owner, target.getBoundingBox(), 0)) {
                  session.releaseStart(owner, session.status().startToken());
               }
            }
         }
         return permission(player);
      });
      AttackBlockCallback.EVENT.register((AttackBlockCallback)(var0, var1, var2, var3, var4) -> permission(var0));
      UseEntityCallback.EVENT.register((UseEntityCallback)(var0, var1, var2, var3, var4) -> permission(var0));
      UseItemCallback.EVENT.register((UseItemCallback)(var0, var1, var2) -> permission(var0));
      UseBlockCallback.EVENT.register((UseBlockCallback)(var0, var1, var2, var3) -> permission(var0));
      ServerLivingEntityEvents.ALLOW_DAMAGE.register((AllowDamage)(var0, var1, var2) -> !holding(var0) && !betweenRounds(var0));
   }

   private static boolean betweenRounds(Entity entity) {
      if (entity.level() instanceof ServerLevel level && TrainingArena.isArena(level)) {
         TrainingSession session = TrainerClient.session(level.getServer());
         return session != null && session.betweenRounds(entity);
      }
      return false;
   }

   private static InteractionResult permission(Entity var0) {
      if (!holding(var0)) return InteractionResult.PASS;
      if (var0.level().isClientSide() && clickedToken != 0 && clickedToken == token()) return InteractionResult.PASS;
      return InteractionResult.FAIL;
   }

   public static boolean holding(Entity var0) {
      if (!TrainingArena.isArena(var0.level())) {
         return false;
      } else {
         Level var2;
         if ((var2 = var0.level()) instanceof ServerLevel) {
            ServerLevel var1 = (ServerLevel)var2;
            TrainingSession var3 = TrainerClient.session(var1.getServer());
            return var3 != null && var3.holding(var0);
         } else {
            return TrainerClient.status().waitingForStart();
         }
      }
   }

   public static long token() {
      Minecraft var0 = Minecraft.getInstance();
      return var0.player != null && TrainingArena.isArena(var0.player.level()) ? TrainerClient.status().startToken() : 0L;
   }

   public static void click(com.mojang.blaze3d.platform.InputConstants.Key key) {
      Minecraft client = Minecraft.getInstance();
      if (McCompat.screen(client) != null || McCompat.overlay(client) != null
            || !(McCompat.keyMatches(client.options.keyAttack, key) || McCompat.keyMatches(client.options.keyUse, key))) return;
      long current = token();
      if (current == 0 || current == clickedToken) return;
      clickedToken = current;
      // Queue the release before vanilla sends this click's attack or use packet.
      press(current);
   }

   private static void readJump(Minecraft var0) {
      long current = token();
      if (current == 0) clickedToken = 0;
      var options = var0.options;
      if (!options.keyJump.isDown()) consumeJump = false;
      if (current == 0 || McCompat.screen(var0) != null || McCompat.overlay(var0) != null) {
         visibleToken = 0;
         return;
      }
      if (visibleToken != current) {
         visibleToken = current;
         sent = false;
         // Discard queued clicks from the previous screen. Held movement still starts the round.
         for (KeyMapping mapping : new KeyMapping[]{options.keyUp,options.keyDown,options.keyLeft,options.keyRight,options.keyJump,options.keyShift}) {
            while (mapping.consumeClick()) {}
         }
      }
      if (sent) return;
      for (KeyMapping mapping : new KeyMapping[]{options.keyUp,options.keyDown,options.keyLeft,options.keyRight,options.keyJump,options.keyShift}) {
         if (mapping.isDown() || mapping.consumeClick()) {
            sent = true;
            consumeJump = mapping == options.keyJump && mapping.isDown();
            press(current);
            break;
         }
      }
   }

   public static boolean consumingJump() {
      return consumeJump;
   }

   static void press(long var0) {
      Minecraft var2 = Minecraft.getInstance();
      IntegratedServer var3 = var2.getSingleplayerServer();
      if (var2.player != null && var3 != null && var0 != 0L) {
         UUID var4 = var2.player.getUUID();
         int slot = var2.player.getInventory().getSelectedSlot();
         var3.execute(() -> {
            TrainingSession var4x = TrainerClient.session(var3);
            ServerPlayer var5 = var3.getPlayerList().getPlayer(var4);
            if (var4x != null && var5 != null && var4x.status().startToken() == var0) {
               var5.getInventory().setSelectedSlot(slot);
               var4x.releaseStart(var5, var0);
            }
         });
      }
   }
}

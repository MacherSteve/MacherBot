package de.pvptrainer;
import de.pvptrainer.drill.DrillEvents;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.arena.ReadyGate;
import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.combat.TrainingCrystalItem;
import de.pvptrainer.ui.TrainingInventoryHint;
import de.pvptrainer.kits.TrainingKit;
import de.pvptrainer.kits.TrainingProfiles;
import de.pvptrainer.ui.TrainingScreen;
import de.pvptrainer.session.TrainingSession;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.session.TrainingStatus;

import com.mojang.logging.LogUtils;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStarted;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopping;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndTick;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping.Category;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class TrainerClient implements ClientModInitializer {
   private static final AtomicReference<TrainerClient.RoundRequest> pending = new AtomicReference<>();
   private static volatile TrainerClient.HostedWorld world;
   private static KeyMapping restartKey;
   private static TrainingProfiles profiles;
   private static String feedback = "";
   private static volatile boolean locked;

   public void onInitializeClient() {
      profiles = new TrainingProfiles(FabricLoader.getInstance().getConfigDir().resolve("pvptrainer.json"));
      Category var1 = Category.register(Identifier.fromNamespaceAndPath("pvptrainer", "training"));
      KeyMapping var2 = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.pvptrainer.menu", 75, var1));
      restartKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.pvptrainer.restart", 74, var1));
      TrainingInventoryHint.register();
      ReadyGate.register();
      TrainingKit.register();
      TrainingArena.register();
      DrillEvents.register();
      TrainingCrystalItem.register();
      ServerLifecycleEvents.SERVER_STARTED.register((ServerStarted)var0 -> world = new TrainerClient.HostedWorld(var0, new TrainingSession()));
      ServerTickEvents.END_SERVER_TICK.register((EndTick)var0 -> {
         TrainerClient.HostedWorld var1x = world;
         if (var1x != null && var1x.server() == var0) {
            var1x.session().tick(var0);
            applyRequest(var1x);
            ServerPlayer var2x = var0.getSingleplayerProfile() == null ? null : var0.getPlayerList().getPlayer(var0.getSingleplayerProfile().id());
            if (var2x != null) {
               TrainingKit.nameNewItems(var2x);
            }

            locked = var2x != null && TrainingKit.layoutLocked(var2x);
         }
      });
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)var0 -> {
         TrainerClient.HostedWorld var1x = world;
         if (var1x != null && var1x.server() == var0) {
            var1x.session().cleanup(var0);
            world = null;
            pending.set(null);
            locked = false;
         }
      });
      ClientTickEvents.END_CLIENT_TICK.register((net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick)var1x -> {
         while (restartKey.consumeClick()) {
            if (var1x.player != null && var1x.gui.screen() == null) {
               restart();
            }
         }

         while (var2.consumeClick()) {
            if (var1x.player != null && var1x.gui.screen() == null) {
               feedback = "";
               var1x.gui.setScreen(new TrainingScreen());
            }
         }
      });
   }

   public static TrainingSettings selected() {
      return profiles.selected();
   }

   public static TrainingSettings settings(TrainingSettings.Mode var0) {
      return profiles.get(var0);
   }

   public static void save(TrainingSettings var0) {
      profiles.save(var0);
   }

   public static DrillOptions options(TrainingSettings.Mode var0) {
      return profiles.options(var0);
   }

   public static void saveSetup(TrainingSettings var0, DrillOptions var1) {
      profiles.saveSetup(var0, var1);
   }

   public static void saveOptions(TrainingSettings.Mode var0, DrillOptions var1) {
      profiles.saveOptions(var0, var1);
   }

   public static String feedback() {
      return feedback;
   }

   public static boolean layoutLocked() {
      return locked;
   }

   public static TrainingSession session(MinecraftServer var0) {
      TrainerClient.HostedWorld var1 = world;
      return var1 != null && var1.server() == var0 ? var1.session() : null;
   }

   public static boolean available() {
      Minecraft var0 = Minecraft.getInstance();
      TrainerClient.HostedWorld var1 = world;
      return var1 != null && var0.player != null && var0.getSingleplayerServer() == var1.server();
   }

   public static TrainingStatus status() {
      TrainerClient.HostedWorld var0 = world;
      return var0 == null ? TrainingStatus.idle() : var0.session().status();
   }

   public static String seriesKey(TrainingSettings.Mode var0, String var1) {
      TrainerClient.HostedWorld var2 = world;
      return var2 == null ? "" : var2.session().previewKey(settings(var0), options(var0), var1);
   }

   public static void start(TrainingSettings var0) {
      save(var0);
      request(TrainerClient.Action.START, var0, options(var0.mode()));
   }

   public static Component restartKeyLabel() {
      return restartKey.getTranslatedKeyMessage();
   }

   public static boolean isRestartKey(KeyEvent var0) {
      return restartKey != null && restartKey.matches(var0);
   }

   public static void restart() {
      request(TrainerClient.Action.RESTART, null, null);
   }

   public static void stop() {
      request(TrainerClient.Action.STOP, null, null);
   }

   private static void request(TrainerClient.Action var0, TrainingSettings var1, DrillOptions var2) {
      Minecraft var3 = Minecraft.getInstance();
      TrainerClient.HostedWorld var4 = world;
      if (var4 != null && var3.player != null && var3.getSingleplayerServer() == var4.server()) {
         feedback = "";
         pending.set(new TrainerClient.RoundRequest(var4, var3.player.getUUID(), var0, var1, var2));
         Screen var6;
         if ((var6 = var3.gui.screen()) instanceof TrainingScreen) {
            TrainingScreen var5 = (TrainingScreen)var6;
            var5.onClose();
         }
      }
   }

   private static void applyRequest(TrainerClient.HostedWorld var0) {
      TrainerClient.RoundRequest var1 = pending.get();
      if (var1 != null && var1.world() == var0) {
         MinecraftServer var2 = var0.server();
         ServerPlayer var3 = var2.getPlayerList().getPlayer(var1.owner());
         if (var3 != null && !var3.isRemoved() && var3.connection.isAcceptingMessages()) {
            if (var1.action() == TrainerClient.Action.STOP || (!var3.isChangingDimension() && var3.connection.hasClientLoaded() && var3.isAlive())) {
               if (pending.compareAndSet(var1, null)) {
                     try {
                        switch (var1.action()) {
                           case START:
                              var0.session().start(var2, var1.owner(), var1.settings(), var1.options());
                              break;
                           case RESTART:
                              var0.session().restart(var2, var3);
                              break;
                           case STOP:
                              var0.session().stop(var2);
                        }
                     } catch (RuntimeException var6) {
                        String var5 = var6.getMessage() == null ? "Aktion fehlgeschlagen." : var6.getMessage();
                        LogUtils.getLogger().error("MacherBot", var6);
                        var3.sendSystemMessage(Component.literal(var5));
                        Minecraft.getInstance().execute(() -> {
                           if (world == var0) {
                              feedback = var5;
                           }
                        });
                     }
                  }
               }
            }
         }
      }

   private enum Action {
      START,
      RESTART,
      STOP;
   }

   private record HostedWorld(MinecraftServer server, TrainingSession session) {
   }

   private record RoundRequest(TrainerClient.HostedWorld world, UUID owner, TrainerClient.Action action, TrainingSettings settings, DrillOptions options) {
   }
}

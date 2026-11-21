package de.pvptrainer;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AfterDamage;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AfterDeath;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Disconnect;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Join;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TrainingKit {
   private static final Logger LOG = LoggerFactory.getLogger("pvptrainer-kits");
   private static final Map<UUID, TrainingKit.Active> ACTIVE = new ConcurrentHashMap<>();

   static void register() {
      ServerPlayConnectionEvents.JOIN.register((Join)(var0, var1, var2) -> recover(var0.player));
      ServerPlayConnectionEvents.DISCONNECT.register((Disconnect)(var0, var1) -> ACTIVE.remove(var0.player.getUUID()));
      ServerLivingEntityEvents.AFTER_DAMAGE.register((AfterDamage)(var0, var1, var2, var3, var4) -> landed(var1));
      ServerLivingEntityEvents.AFTER_DEATH.register((AfterDeath)(var0, var1) -> landed(var1));
   }

   private static void landed(DamageSource var0) {
      Entity var2;
      if ((var2 = var0.getDirectEntity()) instanceof ServerPlayer) {
         ServerPlayer var1 = (ServerPlayer)var2;
         if (!var1.getMainHandItem().isEmpty()) {
            used(var1);
         }
      }
   }

   static String startingFingerprint(ServerPlayer var0) {
      TrainingKit.Active var1 = ACTIVE.get(var0.getUUID());
      if (var1 == null) {
         throw new IllegalStateException("Spieler-Kit fehlt.");
      } else {
         return KitLibrary.fingerprint(var0.registryAccess(), var1.kit);
      }
   }

   static KitLoadout botLoadout(ServerPlayer var0, TrainingSettings.Mode var1) {
      return KitLibrary.selected(var0.registryAccess(), var1, true);
   }

   private static Path journal(ServerPlayer var0) {
      return var0.level().getServer().getWorldPath(LevelResource.ROOT).resolve("pvptrainer/inventory").resolve(var0.getUUID() + ".json");
   }

   static void recover(ServerPlayer var0) {
      if (!TrainingArena.hasReturnPoint(var0)) {
         if (Files.isRegularFile(journal(var0)) && !active(var0)) {
            try {
               end(var0);
            } catch (RuntimeException var2) {
               LOG.error("Wiederherstellung des Trainingsinventars fehlgeschlagen", var2);
               var0.sendSystemMessage(Component.literal("Trainingsinventar konnte nicht wiederhergestellt werden. Die Sicherung bleibt erhalten."));
            }
         }
      }
   }

   static void begin(ServerPlayer var0, TrainingSettings.Mode var1) {
      begin(var0, var1, KitLibrary.personalized(var0.registryAccess(), var1, KitLibrary.selected(var0.registryAccess(), var1, false).id()));
   }

   static void begin(ServerPlayer var0, TrainingSettings.Mode var1, KitLoadout var2) {
      if (var0.containerMenu != var0.inventoryMenu) {
         throw new IllegalStateException("Schließe zuerst den geöffneten Behälter.");
      }

      List<ItemStack> var3 = var2.slots();
      MacherItems.namePlayerKit(var3);

      for (ItemStack var4 : var3) {
         if (var4.is(Items.END_CRYSTAL)) {
            CustomData.update(DataComponents.CUSTOM_DATA, var4, var0x -> var0x.putBoolean("pvptrainer:training_crystal", true));
         }
      }

      Path var8 = journal(var0);
      if (!active(var0)) {
         if (Files.isRegularFile(var8)) {
            end(var0);
         }

         if (Files.exists(var8)) {
            throw new IllegalStateException("Die Inventarsicherung konnte noch nicht abgeschlossen werden.");
         }

         KitLibrary.write(var8, backup(var0));
      }

      TrainingKit.Active var9 = new TrainingKit.Active(var0.level().getServer(), var1, var2, var3);
      ACTIVE.put(var0.getUUID(), var9);
      clearTemporarySlots(var0);
      var0.getInventory().clearContent();

      for (int var6 = 0; var6 < var3.size(); var6++) {
         var0.getInventory().setItem(var6, ((ItemStack)var3.get(var6)).copy());
      }

      int var10 = 0;
      if (var1 == TrainingSettings.Mode.CRYSTAL && var0.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
         for (int var7 = 0; var7 < 9; var7++) {
            if (var0.getInventory().getItem(var7).is(Items.TOTEM_OF_UNDYING)) {
               var10 = var7;
               break;
            }
         }
      }

      var0.getInventory().setSelectedSlot(var10);
      var0.removeAllEffects();
      var2.effects().forEach(var0::addEffect);
      sync(var0);
   }

   static void nameNewItems(ServerPlayer var0) {
      if (active(var0) && TrainingArena.isArena(var0.level())) {
         Inventory var1 = var0.getInventory();

         for (int var2 = 0; var2 < var1.getContainerSize(); var2++) {
            ItemStack var3 = var1.getItem(var2);
            if (!var3.isEmpty() && !var3.has(DataComponents.CUSTOM_NAME)) {
               MacherItems.name(var3);
            }
         }
      }
   }

   private static JsonObject backup(ServerPlayer var0) {
      RegistryAccess var1 = var0.registryAccess();
      ArrayList var2 = new ArrayList();

      for (int var3 = 0; var3 < var0.getInventory().getContainerSize(); var3++) {
         var2.add(var0.getInventory().getItem(var3).copy());
      }

      JsonObject var4 = new JsonObject();
      var4.addProperty("schema", 1);
      var4.addProperty("owner", var0.getUUID().toString());
      var4.addProperty("selected", var0.getInventory().getSelectedSlot());
      var4.add("inventory", KitLibrary.encode(var1, ItemStack.OPTIONAL_CODEC.listOf(), var2));
      var4.add("effects", KitLibrary.encode(var1, MobEffectInstance.CODEC.listOf(), List.copyOf(var0.getActiveEffects())));
      var4.add("cursor", KitLibrary.encode(var1, ItemStack.OPTIONAL_CODEC, var0.inventoryMenu.getCarried()));
      var4.add("craft", KitLibrary.encode(var1, ItemStack.OPTIONAL_CODEC.listOf(), var0.inventoryMenu.getCraftSlots().getItems()));
      return var4;
   }

   static TrainingKit.SavedInventory readBackup(ServerPlayer var0) {
      Path var1 = journal(var0);
      if (!Files.isRegularFile(var1)) {
         if (active(var0)) {
            throw new IllegalStateException("Sicherung des Weltinventars fehlt.");
         } else {
            return null;
         }
      } else {
         JsonObject var2 = KitLibrary.read(var1);
         if (var0.getUUID().toString().equals(var2.get("owner").getAsString()) && var2.get("schema").getAsInt() == 1) {
            RegistryAccess var3 = var0.registryAccess();
            List var4 = KitLibrary.decode(var3, ItemStack.OPTIONAL_CODEC.listOf(), var2.get("inventory"));
            List var5 = KitLibrary.decode(var3, MobEffectInstance.CODEC.listOf(), var2.get("effects"));
            List var6 = KitLibrary.decode(var3, ItemStack.OPTIONAL_CODEC.listOf(), var2.get("craft"));
            ItemStack var7 = KitLibrary.decode(var3, ItemStack.OPTIONAL_CODEC, var2.get("cursor"));
            Inventory var8 = var0.getInventory();
            int var9 = var2.get("selected").getAsInt();
            if (var4.size() == var8.getContainerSize() && var6.size() == var0.inventoryMenu.getCraftSlots().getContainerSize() && var9 >= 0 && var9 < 9) {
               return new TrainingKit.SavedInventory(var4, var9, var6, var7, var5);
            } else {
               throw new IllegalStateException("Unvollständige Inventarsicherung.");
            }
         } else {
            throw new IllegalStateException("Ungültige Inventarsicherung.");
         }
      }
   }

   static void completeRestore(ServerPlayer var0) throws IOException {
      ACTIVE.remove(var0.getUUID());
      Files.deleteIfExists(journal(var0));
   }

   static void end(ServerPlayer var0) {
      TrainingKit.SavedInventory var1 = readBackup(var0);
      if (var1 != null) {
         var1.restore(var0);

         try {
            if (!ArenaRecoverySave.persist(var0.level().getServer(), var0)) {
               throw new IOException("Spielstand nicht geschrieben.");
            }

            completeRestore(var0);
         } catch (IOException var3) {
            throw new IllegalStateException("Das Weltinventar wurde wiederhergestellt, die Sicherung bleibt erhalten.", var3);
         }
      }
   }

   private static void sync(ServerPlayer var0) {
      var0.getInventory().setChanged();
      var0.inventoryMenu.broadcastFullState();
      var0.connection.send(new ClientboundSetHeldSlotPacket(var0.getInventory().getSelectedSlot()));
   }

   private static void clearTemporarySlots(ServerPlayer var0) {
      var0.stopUsingItem();
      var0.inventoryMenu.setCarried(ItemStack.EMPTY);
      var0.inventoryMenu.getCraftSlots().clearContent();
   }

   public static boolean active(ServerPlayer var0) {
      TrainingKit.Active var1 = ACTIVE.get(var0.getUUID());
      return var1 != null && var1.server == var0.level().getServer();
   }

   static boolean layoutLocked(ServerPlayer var0) {
      TrainingKit.Active var1 = ACTIVE.get(var0.getUUID());
      return var1 != null && var1.locked;
   }

   public static void used(ServerPlayer var0) {
      TrainingKit.Active var1 = ACTIVE.get(var0.getUUID());
      if (var1 != null && var1.server == var0.level().getServer()) {
         var1.locked = true;
      }
   }

   static int usableTotems(ItemStack var0) {
      return var0.is(Items.TOTEM_OF_UNDYING) && var0.has(DataComponents.DEATH_PROTECTION) ? var0.getCount() : 0;
   }

   static int refillSupply(ServerPlayer var0) {
      Inventory var1 = var0.getInventory();
      int var2 = usableTotems(var0.containerMenu.getCarried());

      for (int var3 = 0; var3 < var1.getContainerSize(); var3++) {
         var2 += usableTotems(var1.getItem(var3));
      }

      for (ItemStack var5 : var0.inventoryMenu.getCraftSlots().getItems()) {
         var2 += usableTotems(var5);
      }

      return var2;
   }

   public static void popped(ServerPlayer var0, ItemStack var1) {
      used(var0);
      TrainingSession var2 = TrainerClient.session(var0.level().getServer());
      if (var2 != null) {
         var2.playerPopped(var0, var1);
      }
   }

   public static void sorted(ServerPlayer var0) {
      TrainingKit.Active var1 = ACTIVE.get(var0.getUUID());
      if (var1 != null
         && !var1.locked
         && var0.containerMenu == var0.inventoryMenu
         && var0.inventoryMenu.getCarried().isEmpty()
         && var0.inventoryMenu.getCraftSlots().isEmpty()) {
         List var2 = projectLayout(var1.runtime, var1.kit.slots(), ArenaRound.layoutForSorting(var0, var1.runtime), true);
         if (var2 != null) {
            try {
               if (KitLibrary.remember(var0.registryAccess(), var1.mode, var1.kit, var2)) {
                  TrainingSession var3 = TrainerClient.session(var1.server);
                  if (var3 != null) {
                     KitLoadout var4 = new KitLoadout(var1.kit.id(), var1.kit.name(), var1.kit.source(), var2, var1.kit.effects());
                     var3.layoutUpdated(var0, var4);
                  }
               }
            } catch (RuntimeException var5) {
               LOG.error("Trainingslayout konnte nicht gespeichert werden", var5);
               var0.sendSystemMessage(Component.literal("Die Kit-Sortierung konnte nicht gespeichert werden."));
            }
         }
      }
   }

   static boolean sameContents(List<ItemStack> var0, List<ItemStack> var1) {
      return projectLayout(var0, var0, var1, false) != null;
   }

   private static List<ItemStack> projectLayout(List<ItemStack> var0, List<ItemStack> var1, List<ItemStack> var2, boolean var3) {
      if (var2.size() != var0.size()) {
         return null;
      }

      int[] var4 = var0.stream().mapToInt(ItemStack::getCount).toArray();
      List var5 = KitLibrary.emptySlots();

      for (int var6 = 0; var6 < var2.size(); var6++) {
         ItemStack var7 = (ItemStack)var2.get(var6);
         if (!var7.isEmpty()) {
            int var8 = var7.getCount();

            for (int var9 = 0; var9 < var0.size() && var8 > 0; var9++) {
               if (var4[var9] != 0 && matches((ItemStack)var0.get(var9), var7, var3)) {
                  int var10 = Math.min(var4[var9], var8);
                  if (((ItemStack)var5.get(var6)).isEmpty()) {
                     var5.set(var6, ((ItemStack)var1.get(var9)).copyWithCount(var7.getCount()));
                  } else if (!ItemStack.isSameItemSameComponents((ItemStack)var5.get(var6), (ItemStack)var1.get(var9))) {
                     return null;
                  }

                  var8 -= var10;
                  var4[var9] -= var10;
               }
            }

            if (var8 > 0) {
               return null;
            }
         }
      }

      return Arrays.stream(var4).anyMatch(var0x -> var0x != 0) ? null : var5;
   }

   private static boolean matches(ItemStack var0, ItemStack var1, boolean var2) {
      if (ItemStack.isSameItemSameComponents(var0, var1)) {
         return true;
      } else if (var2 && var1.isDamageableItem() && var1.is(var0.getItem()) && var1.getDamageValue() >= var0.getDamageValue()) {
         ItemStack var3 = var1.copy();
         var3.setDamageValue(var0.getDamageValue());
         return ItemStack.isSameItemSameComponents(var0, var3);
      } else {
         return false;
      }
   }

   private static final class Active {
      final MinecraftServer server;
      final KitLoadout kit;
      final TrainingSettings.Mode mode;
      final List<ItemStack> runtime;
      boolean locked;

      Active(MinecraftServer var1, TrainingSettings.Mode var2, KitLoadout var3, List<ItemStack> var4) {
         this.server = var1;
         this.mode = var2;
         this.kit = var3;
         this.runtime = var4;
      }
   }

   record SavedInventory(List<ItemStack> items, int selected, List<ItemStack> craft, ItemStack cursor, List<MobEffectInstance> effects) {
      void restore(ServerPlayer var1) {
         if (var1.containerMenu != var1.inventoryMenu) {
            var1.closeContainer();
         }

         var1.stopUsingItem();
         Inventory var2 = var1.getInventory();

         for (int var3 = 0; var3 < this.items.size(); var3++) {
            var2.setItem(var3, this.items.get(var3));
         }

         var2.setSelectedSlot(this.selected);

         for (int var4 = 0; var4 < this.craft.size(); var4++) {
            var1.inventoryMenu.getCraftSlots().setItem(var4, this.craft.get(var4));
         }

         var1.inventoryMenu.setCarried(this.cursor);
         var1.removeAllEffects();
         this.effects.forEach(var1::addEffect);
         TrainingKit.sync(var1);
      }
   }
}

package de.pvptrainer;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AfterExtract;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AfterInit;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

final class TrainingInventoryHint {
   static void register() {
      ScreenEvents.AFTER_INIT.register((AfterInit)(var0, var1, var2, var3) -> {
         if (var1 instanceof InventoryScreen || var1 instanceof CreativeModeInventoryScreen) {
            ScreenEvents.afterExtract(var1).register((AfterExtract)(var1x, var2x, var3x, var4, var5) -> {
               if (TrainerClient.available() && TrainerClient.status().active()) {
                  boolean var6 = TrainerClient.layoutLocked();
                  String var7 = var6 ? "Startlayout für diesen Versuch fixiert" : "Sortieren wird gespeichert";
                  int var8 = var0.font.width(var7) + 14;
                  int var9 = (var1x.width - var8) / 2;
                  var2x.fill(var9, 2, var9 + var8, 15, -15001317);
                  var2x.fill(var9, 2, var9 + 2, 15, var6 ? -3819081 : -31624);
                  var2x.text(var0.font, var7, var9 + 7, 5, -593428, false);
               }
            });
         }
      });
   }
}

package de.pvptrainer;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

final class MacherItems {
   private MacherItems() {
   }

   static boolean isHealingHead(ItemStack var0) {
      if (!var0.is(Items.PLAYER_HEAD)) {
         return false;
      }

      Consumable var1 = (Consumable)var0.get(DataComponents.CONSUMABLE);
      return var1 == null
         ? false
         : var1.onConsumeEffects()
            .stream()
            .filter(ApplyStatusEffectsConsumeEffect.class::isInstance)
            .map(ApplyStatusEffectsConsumeEffect.class::cast)
            .flatMap(var0x -> var0x.effects().stream())
            .anyMatch(var0x -> var0x.getEffect().equals(MobEffects.REGENERATION) || var0x.getEffect().equals(MobEffects.INSTANT_HEALTH));
   }

   static Component label(ItemStack var0) {
      String var10000;
      label95: {
         String var2;
         label98: {
            String var1 = BuiltInRegistries.ITEM.getKey(var0.getItem()).getPath();
            var2 = var1.substring(var1.lastIndexOf(95) + 1);
            String var4 = var2;
            switch (var2.hashCode()) {
               case -1220934547:
                  if (var4.equals("helmet")) {
                     break label98;
                  }
                  break;
               case -903145309:
                  if (var4.equals("shovel")) {
                     break label98;
                  }
                  break;
               case -578028723:
                  if (var4.equals("pickaxe")) {
                     break label98;
                  }
                  break;
               case 97038:
                  if (var4.equals("axe")) {
                     break label98;
                  }
                  break;
               case 103486:
                  if (var4.equals("hoe")) {
                     break label98;
                  }
                  break;
               case 93922241:
                  if (var4.equals("boots")) {
                     break label98;
                  }
                  break;
               case 109860349:
                  if (var4.equals("sword")) {
                     break label98;
                  }
                  break;
               case 1069952181:
                  if (var4.equals("chestplate")) {
                     break label98;
                  }
                  break;
               case 1735676010:
                  if (var4.equals("leggings")) {
                     break label98;
                  }
            }

            String var5 = var1;
            switch (var1.hashCode()) {
               case -1606277878:
                  if (var5.equals("totem_of_undying")) {
                     var10000 = "totem";
                     break label95;
                  }
                  break;
               case 556765310:
                  if (var5.equals("player_head")) {
                     var10000 = isHealingHead(var0) ? "golden_head" : var1;
                     break label95;
                  }
                  break;
               case 1803855826:
                  if (var5.equals("end_crystal")) {
                     var10000 = "crystal";
                     break label95;
                  }
            }

            var10000 = var1;
            break label95;
         }

         var10000 = var2;
      }

      String var3 = var10000;
      StringBuilder var9 = new StringBuilder("Macher");
      String[] var8;
      int var7 = (var8 = var3.split("_")).length;

      for (int var6 = 0; var6 < var7; var6++) {
         String var10 = var8[var6];
         if (!var10.isEmpty()) {
            var9.append(Character.toUpperCase(var10.charAt(0))).append(var10.substring(1));
         }
      }

      MutableComponent var11 = Component.literal(var9.toString()).withStyle(var0x -> var0x.withItalic(false));
      if (var0.is(Items.MACE)) {
         for (Holder var12 : var0.getEnchantments().keySet()) {
            if (var12.is(Enchantments.BREACH) || var12.is(Enchantments.DENSITY)) {
               var11.append(" · ").append(Enchantment.getFullname(var12, var0.getEnchantments().getLevel(var12)));
               break;
            }
         }
      } else if (var0.has(DataComponents.POTION_CONTENTS)) {
         PotionContents var13 = (PotionContents)var0.get(DataComponents.POTION_CONTENTS);
         var13.potion().ifPresent(var2x -> {
            ItemStack var3x = PotionContents.createItemStack(var0.getItem(), var2x);
            var11.append(" · ").append(var3x.getItemName());
         });
      }

      return var11;
   }

   static ItemStack name(ItemStack var0) {
      if (!var0.isEmpty()) {
         var0.set(DataComponents.CUSTOM_NAME, label(var0));
      }

      return var0;
   }

   static void namePlayerKit(List<ItemStack> var0) {
      ArrayList var1 = new ArrayList();

      for (ItemStack var2 : var0) {
         if (!var2.isEmpty()) {
            if (var2.has(DataComponents.CUSTOM_NAME)) {
               int var4 = 0;

               while (var4 < var1.size() && !ItemStack.isSameItemSameComponents(var2, (ItemStack)var1.get(var4))) {
                  var4++;
               }

               if (var4 == var1.size()) {
                  var1.add(var2.copyWithCount(1));
               }

               int var5 = var4 + 1;
               CustomData.update(DataComponents.CUSTOM_DATA, var2, var1x -> var1x.putInt("pvptrainer:kit_name_group", var5));
            }

            name(var2);
         }
      }
   }
}

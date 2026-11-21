package de.pvptrainer;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

record KitLoadout(String id, String name, String source, List<ItemStack> slots, List<MobEffectInstance> effects) {
   static final String PRESET_SOURCE = "PvPPrac · 19.11.2026";

   KitLoadout(String id, String name, String source, List<ItemStack> slots, List<MobEffectInstance> effects) {
      if (slots.size() != 41) {
         throw new IllegalArgumentException("Ein Kit benötigt genau 41 Plätze.");
      }

      slots = slots.stream().map(ItemStack::copy).toList();
      effects = effects.stream().map(MobEffectInstance::new).toList();
      this.id = id;
      this.name = name;
      this.source = source;
      this.slots = slots;
      this.effects = effects;
   }

   public List<ItemStack> slots() {
      return new ArrayList<>(this.slots.stream().map(ItemStack::copy).toList());
   }

   public List<MobEffectInstance> effects() {
      return this.effects.stream().<MobEffectInstance>map(MobEffectInstance::new).toList();
   }

   ItemStack stack(int var1) {
      return this.slots.get(var1).copy();
   }

   boolean preset() {
      return this.id != null && this.id.startsWith("pvpprac-");
   }

   String sourceLabel() {
      if (this.preset()) {
         return "Standardkit · 19.11.2026";
      } else {
         return this.source.equals("Eigene Kopie · PvPPrac · 19.11.2026") ? "Eigene Kopie · Standardkit" : this.source;
      }
   }
}

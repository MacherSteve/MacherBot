package de.pvptrainer.combat;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.kits.KitLoadout;
import de.pvptrainer.kits.MacherItems;
import de.pvptrainer.kits.TrainingKit;
import de.pvptrainer.kits.TrainingSettings;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

public final class BotEquipment {
   private final List<ItemStack> stock = new ArrayList<>();
   private final DrillOptions options;
   final boolean consumeSupplies;
   private final boolean consumeTotems;
   private final ItemStack totemTemplate;
   private final boolean doubleHand;
   private final boolean practiceTotem;
   public final InteractionHand shieldHand;
   private ItemStack replacementShield = ItemStack.EMPTY;
   private boolean postPopShieldActive;
   private final int[] refillAt = new int[InteractionHand.values().length];
   private int tick;

   public BotEquipment(LivingEntity var1, KitLoadout var2, DrillOptions var3, TrainingSettings.Mode mode) {
      this.options = var3;
      consumeSupplies = var3.itemConsumption(mode);
      consumeTotems = var3.consumeTotems(mode);
      var items = var2.slots();
      boolean replaceStartingTotem = !mode.allowsBotTotems() && items.get(0).is(Items.TOTEM_OF_UNDYING);
      if (!mode.allowsBotTotems())
         items.replaceAll(stack -> stack.is(Items.TOTEM_OF_UNDYING) ? ItemStack.EMPTY : stack);
      var totems = items.stream().filter(stack -> TrainingKit.usableTotems(stack) > 0).toList();
      practiceTotem = !totems.isEmpty() && var3.drill() != Drill.DUEL && var3.totems() == DrillOptions.Totems.UNLIMITED;
      shieldHand = mode == TrainingSettings.Mode.MACE
         || mode == TrainingSettings.Mode.HUGOSMP
         || var3.drill() == Drill.SHIELD_TRIPLE_TAP
         ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
      totemTemplate = totems.isEmpty() ? ItemStack.EMPTY : totems.getFirst().copyWithCount(1);
      doubleHand = (mode == TrainingSettings.Mode.CRYSTAL || mode == TrainingSettings.Mode.HUGOSMP)
         && !var3.holdShield()
         && var3.drill() != Drill.SHIELD_TRIPLE_TAP
         && var3.drill() != Drill.MACE_COUNTER_DTAP
         && totems.stream().mapToInt(ItemStack::getCount).sum() >= 2;

      for (int var4 = 0; var4 < 36; var4++) {
         this.stock.add(MacherItems.name(items.get(var4)));
      }

      var1.setItemSlot(EquipmentSlot.FEET, MacherItems.name(items.get(36)));
      var1.setItemSlot(EquipmentSlot.LEGS, MacherItems.name(items.get(37)));
      var1.setItemSlot(EquipmentSlot.CHEST, MacherItems.name(items.get(38)));
      var1.setItemSlot(EquipmentSlot.HEAD, MacherItems.name(items.get(39)));
      if (requiresProtectionOnlyArmor(var3.drill())) {
         enforceProtectionOnly(var1, this.stock);
      }
      var1.setItemInHand(InteractionHand.MAIN_HAND, this.stock.set(0, ItemStack.EMPTY));
      var1.setItemInHand(InteractionHand.OFF_HAND, MacherItems.name(items.get(40)));
      if (replaceStartingTotem && !equipSword(var1)) equipAxe(var1);
      if (!doubleHand && var3.totems() != DrillOptions.Totems.OFF && var1.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) {
         stash(var1.getMainHandItem());
         var1.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
         if (!equipSword(var1) && !equipWeapon(var1, Items.MACE)) equipAxe(var1);
      }
      var2.effects().forEach(var1::addEffect);
      if (var3.holdShield()) {
         if (!var1.getItemInHand(shieldHand).is(Items.SHIELD)) {
            ItemStack var5 = this.take(var1, Items.SHIELD);
            if (!var5.isEmpty()) {
               this.stash(var1.getItemInHand(shieldHand));
               var1.setItemInHand(shieldHand, var5);
            }
         }

         ItemStack var7 = var1.getItemInHand(shieldHand);
         if (var7.is(Items.SHIELD) && var3.shieldDurability() >= 0) {
            var7.setDamageValue(Math.max(0, var7.getMaxDamage() - var3.shieldDurability()));
         }

         if (var7.is(Items.SHIELD)) {
            this.replacementShield = var7.copy();
            this.replacementShield.setDamageValue(0);
         }
      }

      if (var3.drill() == Drill.ANTI_DRAIN && !has(var1, Items.BUCKET)) {
         ItemStack spareWater = take(var1, Items.WATER_BUCKET);
         if (!spareWater.isEmpty()) {
            stash(MacherItems.name(new ItemStack(Items.BUCKET)));
         }
      }

      guard(var1);
   }

   public static boolean requiresProtectionOnlyArmor(Drill drill) {
      return drill == Drill.D_TAP || drill == Drill.TRIPLE_TAP || drill == Drill.SHIELD_TRIPLE_TAP
         || drill == Drill.CORNER_HIT_CRYSTAL;
   }

   private static void enforceProtectionOnly(LivingEntity bot, List<ItemStack> stock) {
      var lookupOpt = bot.registryAccess().lookup(Registries.ENCHANTMENT);
      if (lookupOpt.isEmpty()) return;
      var lookup = lookupOpt.get();
      var protOpt = lookup.get(Enchantments.PROTECTION);
      if (protOpt.isEmpty()) return;
      Holder<Enchantment> prot = protOpt.get();
      List<Holder<Enchantment>> nonProt = new ArrayList<>();
      for (var key : List.of(Enchantments.BLAST_PROTECTION, Enchantments.FIRE_PROTECTION, Enchantments.PROJECTILE_PROTECTION)) {
         lookup.get(key).ifPresent(nonProt::add);
      }
      if (nonProt.isEmpty()) return;
      for (EquipmentSlot slot : List.of(EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)) {
         normalizeProtectionStack(bot.getItemBySlot(slot), prot, nonProt);
      }
      for (ItemStack spare : stock) {
         normalizeProtectionStack(spare, prot, nonProt);
      }
   }

   private static void normalizeProtectionStack(ItemStack stack, Holder<Enchantment> prot, List<Holder<Enchantment>> nonProt) {
      if (stack.isEmpty()) return;
      EnchantmentHelper.updateEnchantments(stack, mutable -> {
         boolean replaced = false;
         for (var other : nonProt) {
            if (mutable.getLevel(other) > 0) {
               mutable.set(other, 0);
               replaced = true;
            }
         }
         if (replaced) {
            mutable.set(prot, Math.max(4, mutable.getLevel(prot)));
         }
      });
   }

   public static String missingRequirement(KitLoadout kit, DrillOptions options) {
      var slots = kit.slots();
      if ((options.drill() == Drill.WIND_STUN_SLAM || options.drill() == Drill.MACE_COUNTER_DTAP)
            && slots.stream().noneMatch(stack -> stack.is(Items.WIND_CHARGE))) {
         return "Für diese Übung braucht dein Bot-Kit Windladungen.";
      }
      if (options.drill() == Drill.MACE_COUNTER_DTAP && slots.stream().noneMatch(stack -> stack.is(Items.MACE))) {
         return "Für den Mace-Konter braucht dein Bot-Kit eine Mace.";
      }
      if (options.drill() == Drill.WIND_STUN_SLAM && options.windFlight() != DrillOptions.WindFlight.CHARGE
            && slots.stream().noneMatch(stack -> stack.is(Items.ENDER_PEARL))) {
         return "Für den Pearl Catch braucht dein Bot-Kit Enderperlen.";
      }
      if ((options.holdShield() || options.drill() == Drill.SHIELD_TRIPLE_TAP)
            && slots.stream().noneMatch(stack -> stack.is(Items.SHIELD))) {
         return options.drill() == Drill.SHIELD_TRIPLE_TAP
            ? "Für Shield-Triple-Tap braucht dein Bot-Kit einen Schild."
            : "Für dauerhaftes Blocken braucht dein Bot-Kit einen Schild.";
      }
      if ((options.drill().requiresPopChain() || options.drill() == Drill.TOTEM_POPS || options.drill() == Drill.CORNER_HIT_CRYSTAL)
            && slots.stream().noneMatch(stack -> TrainingKit.usableTotems(stack) > 0)) {
         return "Für diese Übung braucht dein Bot-Kit mindestens ein Totem.";
      }
      return null;
   }

   void stash(ItemStack var1) {
      if (!var1.isEmpty()) {
         this.stock.removeIf(ItemStack::isEmpty);
         this.stock.add(var1);
      }
   }

   ItemStack take(LivingEntity var1, Item var2) {
      InteractionHand[] var6;
      int var5 = (var6 = InteractionHand.values()).length;

      for (int var4 = 0; var4 < var5; var4++) {
         InteractionHand var3 = var6[var4];
         ItemStack var7 = var1.getItemInHand(var3);
         if (var7.is(var2)) {
            return var7.split(1);
         }
      }

      for (ItemStack var8 : this.stock) {
         if (var8.is(var2)) {
            return var8.split(1);
         }
      }

      return ItemStack.EMPTY;
   }

   private ItemStack reserveTotem() {
      for (ItemStack var2 : this.stock) {
         if (TrainingKit.usableTotems(var2) > 0) {
            return var2.split(1);
         }
      }

      return (this.options.totems() == DrillOptions.Totems.UNLIMITED || !consumeTotems)
         ? MacherItems.name(totemTemplate.copy()) : ItemStack.EMPTY;
   }

   public void popped(LivingEntity var1, InteractionHand var2, int var3) {
      if (this.options.totems() != DrillOptions.Totems.OFF) {
         this.tick = var3;
         this.refillAt[var2.ordinal()] = var3 + this.options.refillTicks();
         guard(var1);
      } else if (!consumeTotems && !totemTemplate.isEmpty()) {
         // Preserve the kit stock while leaving the popped hand empty with Auto-Totem disabled.
         stash(totemTemplate.copy());
      }
   }

   private void readyTotem(LivingEntity bot, InteractionHand hand) {
      if (tick < refillAt[hand.ordinal()] || bot.getItemInHand(hand).is(Items.TOTEM_OF_UNDYING)) return;
      ItemStack reserve = reserveTotem();
      if (!reserve.isEmpty()) {
         stash(bot.getItemInHand(hand));
         bot.setItemInHand(hand, reserve);
      }
   }

   void guard(LivingEntity bot) {
      if (totemTemplate.isEmpty() || options.totems() == DrillOptions.Totems.OFF) return;
      if (practiceTotem && (shieldHand == InteractionHand.MAIN_HAND || !options.holdShield())
            && !(bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.OFF_HAND)) readyTotem(bot, InteractionHand.OFF_HAND);
      if (bot.isUsingItem() && !bot.isBlocking()) return;
      if (!options.holdShield() && (doubleHand || bot.getOffhandItem().isEmpty())
            && !(bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.OFF_HAND)) {
         readyTotem(bot, InteractionHand.OFF_HAND);
      }
      if (doubleHand) readyTotem(bot, InteractionHand.MAIN_HAND);
   }

   public void protectPassiveTarget(LivingEntity bot) {
      if (!practiceTotem || options.drill() != Drill.PASSIVE
            || TrainingKit.usableTotems(bot.getOffhandItem()) > 0 || TrainingKit.usableTotems(bot.getMainHandItem()) > 0) return;
      // In unlimited passive practice, two damaging hits can arrive before the scheduled refill.
      // Leave the shield available in its configured hand and let vanilla process the real totem.
      InteractionHand hand = shieldHand == InteractionHand.OFF_HAND && options.holdShield()
         ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
      stash(bot.getItemInHand(hand));
      bot.setItemInHand(hand, reserveTotem());
   }

   public boolean isPostPopShieldActive() {
      return this.postPopShieldActive;
   }

   public boolean activatePostPopShield(LivingEntity bot) {
      this.postPopShieldActive = true;
      if (!bot.getItemInHand(shieldHand).is(Items.SHIELD)) {
         ItemStack shield = this.take(bot, Items.SHIELD);
         if (!shield.isEmpty()) {
            this.stash(bot.getItemInHand(shieldHand));
            bot.setItemInHand(shieldHand, shield);
         }
      }
      ItemStack held = bot.getItemInHand(shieldHand);
      if (held.is(Items.SHIELD)) {
         this.replacementShield = held.copy();
         this.replacementShield.setDamageValue(0);
         return true;
      }
      return false;
   }

   public void tick(LivingEntity var1, int var2, int var3) {
      tick = var2;
      guard(var1);
      if ((this.options.holdShield() || this.postPopShieldActive) && var2 >= var3 && !var1.isUsingItem()) {
         if (var1.getItemInHand(shieldHand).is(Items.SHIELD)) {
            var1.startUsingItem(shieldHand);
         }
      }
   }

   public void broken(LivingEntity var1) {
      if (this.options.replaceShield() && !this.replacementShield.isEmpty()) {
         var1.setItemInHand(shieldHand, this.replacementShield.copy());
      }
   }

   private ItemStack takeStack(LivingEntity var1, Item var2) {
      ItemStack var3 = var1.getOffhandItem();
      if (var3.is(var2)) {
         var1.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
         return var3;
      }

      for (int var4 = 0; var4 < this.stock.size(); var4++) {
         if (this.stock.get(var4).is(var2)) {
            return this.stock.set(var4, ItemStack.EMPTY);
         }
      }

      return ItemStack.EMPTY;
   }

   boolean equipComboItem(LivingEntity var1) {
      if (var1.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) {
         return true;
      }

      for (int var2 = 0; var2 < this.stock.size(); var2++) {
         if (this.stock.get(var2).is(Items.TOTEM_OF_UNDYING)) {
            ItemStack var3 = this.stock.set(var2, ItemStack.EMPTY);
            this.stash(var1.getMainHandItem());
            var1.setItemInHand(InteractionHand.MAIN_HAND, var3);
            return true;
         }
      }

      return this.equipSword(var1);
   }

   boolean equipSword(LivingEntity var1) {
      if (var1.getMainHandItem().is(ItemTags.SWORDS)) {
         return true;
      }
      if (var1.getOffhandItem().is(ItemTags.SWORDS)) return this.equipWeapon(var1, var1.getOffhandItem().getItem());

      for (ItemStack var2 : this.stock) {
         if (var2.is(ItemTags.SWORDS)) {
            return this.equipWeapon(var1, var2.getItem());
         }
      }

      return false;
   }

   boolean equipAxe(LivingEntity bot) {
      if (bot.getMainHandItem().is(ItemTags.AXES)) return true;
      if (bot.getOffhandItem().is(ItemTags.AXES)) return equipWeapon(bot, bot.getOffhandItem().getItem());
      for (ItemStack stack : stock) {
         if (stack.is(ItemTags.AXES)) return equipWeapon(bot, stack.getItem());
      }
      return false;
   }

   boolean equipShield(LivingEntity bot) {
      if (bot.getOffhandItem().is(Items.SHIELD)) return true;
      if ((!doubleHand || options.totems() == DrillOptions.Totems.UNLIMITED) && bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
         return swapTotemForShield(bot);
      }
      if (bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.OFF_HAND
            || options.totems() != DrillOptions.Totems.OFF && bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return false;
      if (bot.isUsingItem() && bot.getMainHandItem().is(Items.SHIELD)) bot.stopUsingItem();
      ItemStack shield = take(bot, Items.SHIELD);
      if (shield.isEmpty()) return false;
      stash(bot.getOffhandItem());
      bot.setItemInHand(InteractionHand.OFF_HAND, shield);
      return true;
   }

   boolean equipRangedShield(LivingEntity bot) {
      if (shieldHand == InteractionHand.MAIN_HAND) return equipWeapon(bot, Items.SHIELD);
      return bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING) ? swapTotemForShield(bot) : equipShield(bot);
   }

   private boolean swapTotemForShield(LivingEntity bot) {
      if (bot.isUsingItem()) return false;
      ItemStack shield = take(bot, Items.SHIELD);
      if (shield.isEmpty()) return false;
      ItemStack oldOffhand = bot.getOffhandItem();
      if (!doubleHand || bot.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) {
         stash(oldOffhand);
      } else {
         stash(bot.getMainHandItem());
         bot.setItemInHand(InteractionHand.MAIN_HAND, oldOffhand);
      }
      bot.setItemInHand(InteractionHand.OFF_HAND, shield);
      return true;
   }

   void restoreRangedTotem(LivingEntity bot) {
      if(bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING))return;
      var reserve=takeStack(bot,Items.TOTEM_OF_UNDYING);
      if(reserve.isEmpty() && bot.getMainHandItem().is(Items.TOTEM_OF_UNDYING)){
         reserve=bot.getMainHandItem();bot.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
      }
      if(!reserve.isEmpty()){
         stash(bot.getOffhandItem());
         bot.setItemInHand(InteractionHand.OFF_HAND,reserve);
      }
   }

   public boolean has(LivingEntity bot, Item item) {
      return bot.getMainHandItem().is(item) || bot.getOffhandItem().is(item)
         || stock.stream().anyMatch(stack -> stack.is(item));
   }

   public boolean equipWeapon(LivingEntity var1, Item var2) {
      if (var1.getMainHandItem().is(var2)) {
         return true;
      }

      ItemStack var3 = this.takeStack(var1, var2);
      if (var3.isEmpty()) {
         return false;
      }

      this.stash(var1.getMainHandItem());
      var1.setItemInHand(InteractionHand.MAIN_HAND, var3);
      return true;
   }

   boolean equipMatching(LivingEntity bot, java.util.function.Predicate<ItemStack> match) {
      if (!bot.getMainHandItem().isEmpty() && match.test(bot.getMainHandItem())) return true;
      ItemStack selected = bot.getOffhandItem();
      if (!selected.isEmpty() && match.test(selected)) {
         bot.setItemInHand(InteractionHand.OFF_HAND, bot.getMainHandItem());
         bot.setItemInHand(InteractionHand.MAIN_HAND, selected);
         return true;
      }
      for (int slot = 0; slot < stock.size(); slot++) {
         selected = stock.get(slot);
         if (!selected.isEmpty() && match.test(selected)) {
            stock.set(slot, bot.getMainHandItem());
            bot.setItemInHand(InteractionHand.MAIN_HAND, selected);
            return true;
         }
      }
      return false;
   }

   boolean equipApple(LivingEntity var1) {
      if (this.options.holdShield() || var1.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
         return false;
      }

      if (var1.getOffhandItem().is(Items.GOLDEN_APPLE)) {
         return true;
      }

      ItemStack var2 = this.take(var1, Items.GOLDEN_APPLE);
      if (var2.isEmpty()) {
         return false;
      }

      this.stash(var1.getOffhandItem());
      var1.setItemInHand(InteractionHand.OFF_HAND, var2);
      return true;
   }

   public boolean takeOne(LivingEntity var1, Item var2) {
      return consumeSupplies ? !this.take(var1, var2).isEmpty() : has(var1, var2);
   }

   ItemStack ammunition(LivingEntity bot, Item item) {
      if (consumeSupplies) return take(bot, item);
      for (InteractionHand hand : InteractionHand.values()) {
         ItemStack held = bot.getItemInHand(hand);
         if (held.is(item)) return held.copyWithCount(1);
      }
      for (ItemStack stack : stock) {
         if (stack.is(item)) return stack.copyWithCount(1);
      }
      return ItemStack.EMPTY;
   }
}

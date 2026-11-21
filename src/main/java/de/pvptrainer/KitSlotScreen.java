package de.pvptrainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

final class KitSlotScreen extends TrainerPanel {
   private final KitEditorScreen editor;
   private final int slot;
   private ItemStack working;
   private String count;
   private String durability;
   private String error = "";
   private int page;
   private int numbersY;
   private int enchantY;
   private int pages;
   private boolean properties, showEnchantments, showCount, showDurability, canEnchant;
   private final Map<Holder<Enchantment>, AbstractWidget> enchantmentControls = new LinkedHashMap<>();

   KitSlotScreen(KitEditorScreen var1, int var2) {
      super(var1, KitEditorScreen.slotName(var2), var1.mode);
      this.editor = var1;
      this.slot = var2;
      this.reset(var1.slots.get(var2).copy());
   }

   private void reset(ItemStack var1) {
      this.working = var1;
      this.count = Integer.toString(this.working.isEmpty() ? 1 : this.working.getCount());
      this.durability = Integer.toString(this.working.getMaxDamage() - this.working.getDamageValue());
      this.page = 0;
      this.properties = false;
      this.error = "";
   }

   void choose(ItemStack var1) {
      ItemStack var2 = var1.copy();
      if (!this.working.isEmpty() && !var2.isEmpty() && KitEditorScreen.armorSlot(this.slot) != null) {
         for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(this.working).entrySet()) {
            var enchantment = entry.getKey();
            if (enchantment.value().isSupportedItem(var2)) {
               enchant(var2, enchantment, entry.getIntValue());
            }
         }
      }

      this.reset(var2);
   }

   void changePotion(Holder<Potion> var1) {
      setPotionType(this.working, var1);
      this.error = "";
   }

   static void setPotionType(ItemStack var0, Holder<Potion> var1) {
      if (!KitItemSearch.hasPotionTypes(var0.getItem())) {
         throw new IllegalArgumentException("Dieses Item hat keinen Tranktyp.");
      }

      PotionContents var2 = (PotionContents)var0.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
      var0.set(DataComponents.POTION_CONTENTS, var2.withPotion(var1));
   }

   static void setFlightDuration(ItemStack rocket, int duration) {
      if (!rocket.is(net.minecraft.world.item.Items.FIREWORK_ROCKET) || duration < 1 || duration > 3) {
         throw new IllegalArgumentException("Ungültige Rakete oder Flugdauer.");
      }
      var fireworks = rocket.get(DataComponents.FIREWORKS);
      rocket.set(DataComponents.FIREWORKS, new net.minecraft.world.item.component.Fireworks(duration,
         fireworks == null ? List.of() : fireworks.explosions()));
   }

   static void enchant(ItemStack var0, Holder<Enchantment> var1, int var2) {
      EnchantmentHelper.updateEnchantments(var0, var2x -> {
         if (var2 > 0) {
            var2x.removeIf(var1xx -> !var1xx.equals(var1) && !Enchantment.areCompatible(var1, var1xx));
         }

         var2x.set(var1, var2);
      });
   }

   @Override
   void build() {
      this.subtitle = "Item bearbeiten";
      String var1 = this.working.isEmpty() ? "Leerer Platz" : MacherItems.label(this.working).getString();
      this.tile(
         this.x,
         this.y,
         this.inner,
         27,
         var1,
         "Gegenstand wählen  ›",
         this.working,
         TrainerPanel.Style.SELECTED,
         () -> this.open(new KitItemSearch(this, this.slot))
      );
      var supported = this.minecraft
         .level
         .registryAccess()
         .lookupOrThrow(Registries.ENCHANTMENT)
         .listElements()
         .filter(holder -> !this.working.isEmpty() && holder.value().isSupportedItem(this.working))
         .sorted(Comparator.comparing(holder -> holder.value().description().getString()))
         .toList();
      canEnchant = !supported.isEmpty();
      showEnchantments = canEnchant && !properties;
      int bodyY = y + 27 + gap;
      if (canEnchant) {
         action(col(0,2),bodyY,cw(0,2),18,"Verzauberungen",showEnchantments ? Style.SELECTED : Style.NORMAL,
            () -> { properties=false; refresh(); });
         action(col(1,2),bodyY,cw(1,2),18,"Gegenstand",properties ? Style.SELECTED : Style.NORMAL,
            () -> { properties=true; refresh(); });
         bodyY += 18 + gap;
      }
      showCount = !showEnchantments && !working.isEmpty() && working.getMaxStackSize() > 1;
      showDurability = !showEnchantments && working.isDamageableItem();
      numbersY = bodyY;
      if (!showEnchantments && !working.isEmpty() && KitItemSearch.hasPotionTypes(working.getItem())) {
         String potion = working.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).potion()
            .map(holder -> KitItemSearch.potionLabel(working.getItem(),holder)).orElse("Eigene Effekte");
         action(x,numbersY,inner,20,"Tranktyp · " + potion,Style.NORMAL,
            () -> open(new KitItemSearch(this,slot,working.getItem())));
         numbersY += 20 + gap;
      }
      if (!showEnchantments && working.is(net.minecraft.world.item.Items.FIREWORK_ROCKET)) {
         int duration = working.getOrDefault(DataComponents.FIREWORKS, new net.minecraft.world.item.component.Fireworks(1, List.of())).flightDuration();
         addRenderableWidget(new SettingSlider(x, numbersY, inner, 20, "Flugdauer", 3, Math.clamp(duration, 1, 3) - 1,
            step -> Integer.toString(step + 1), step -> setFlightDuration(working, step + 1)));
         numbersY += 20 + gap;
      }
      int fieldWidth = showCount && showDurability ? cw(0,2) : inner;
      if (showCount) numeric(x,numbersY + 11,fieldWidth,"Menge",count, text -> count=text);
      if (showDurability) numeric(showCount ? col(1,2) : x,numbersY + 11,fieldWidth,"Resthaltbarkeit",durability,text -> durability=text);
      enchantY = bodyY;
      int var4 = Math.max(1, Math.min(6, (bottom - enchantY - 34) / 23));
      int var5 = 2 * var4;
      this.pages = Math.max(1, (supported.size() + var5 - 1) / var5);
      this.page = Math.min(this.page, this.pages - 1);

      enchantmentControls.clear();
      for (int cell = 0; showEnchantments && cell < var5 && page * var5 + cell < supported.size(); cell++) {
         var holder = supported.get(page * var5 + cell);
         String name = holder.value().description().getString();
         int maxLevel = holder.value().getMaxLevel();
         int current = EnchantmentHelper.getEnchantmentsForCrafting(working).getLevel(holder);
         int col = cell % 2;
         int left = col(col, 2);
         int top = enchantY + 11 + cell / 2 * 23;
         int width = cw(col, 2);
         AbstractWidget widget;
         if (maxLevel > 1) {
            widget = new SettingSlider(left, top, width, 20, name, Math.max(maxLevel, current) + 1,
               current, level -> level == 0 ? "Aus" : Integer.toString(level), level -> changeEnchantment(holder, level));
         } else {
            widget = new Button(left,top,width,20,Component.literal(name),button -> {
               int level = EnchantmentHelper.getEnchantmentsForCrafting(working).getLevel(holder);
               changeEnchantment(holder, level == 0 ? 1 : 0);
            }, java.util.function.Supplier::get) {
               @Override protected void extractContents(GuiGraphicsExtractor graphics, int mx, int my, float partial) {
                  TrainerPanel.drawButton(graphics,this,Style.NORMAL,shade(),accent());
                  boolean enabled = EnchantmentHelper.getEnchantmentsForCrafting(working).getLevel(holder) > 0;
                  String state = enabled ? "Ein" : "Aus";
                  int size = font.width(state);
                  line(graphics,name,getX()+5,getY()+5,getWidth()-size-18,TEXT);
                  graphics.text(font,state,getRight()-size-5,getY()+5,enabled ? accent() : MUTED,false);
               }
            };
         }
         widget.setTooltipDelay(Duration.ofMillis(320));
         enchantmentControls.put(holder, addRenderableWidget(widget));
      }
      syncEnchantmentControls();

      if (showEnchantments && this.pages > 1) {
         this.action(this.x, this.bottom - 19, 27, 19, "‹", TrainerPanel.Style.NORMAL, () -> {
            this.page--;
            this.refresh();
         }).active = this.page > 0;
         this.action(this.x + 29, this.bottom - 19, 27, 19, "›", TrainerPanel.Style.NORMAL, () -> {
            this.page++;
            this.refresh();
         }).active = this.page + 1 < this.pages;
      }

      this.action(
         this.right - Math.min(144, this.inner / 2), this.bottom - 19, Math.min(144, this.inner / 2), 19, "Übernehmen", TrainerPanel.Style.PRIMARY, this::apply
      );
   }

   private void changeEnchantment(Holder<Enchantment> holder, int level) {
      enchant(working, holder, level);
      syncEnchantmentControls();
   }

   private void syncEnchantmentControls() {
      var applied = EnchantmentHelper.getEnchantmentsForCrafting(working);
      enchantmentControls.forEach((holder, widget) -> {
         int level = applied.getLevel(holder);
         int max = holder.value().getMaxLevel();
         String hint;
         if (widget instanceof SettingSlider slider) {
            slider.showIndex(level);
            hint = "Stufe von 1 bis " + Math.max(max, level) + " wählen. Ganz links entfernen.";
         } else {
            String name = holder.value().description().getString();
            widget.setMessage(Component.literal(name + (level > 0 ? " · Ein" : " · Aus")));
            hint = level > 0 ? "Verzauberung entfernen." : "Verzauberung aktivieren.";
         }
         List<String> conflicts = new ArrayList<>();
         for (var other : applied.keySet()) {
            if (!holder.equals(other) && !Enchantment.areCompatible(holder, other)) {
               conflicts.add(other.value().description().getString());
            }
         }
         if (!conflicts.isEmpty()) hint += " Ersetzt " + String.join(", ", conflicts) + ".";
         widget.setTooltip(Tooltip.create(Component.literal(hint)));
      });
   }

   private EditBox numeric(int var1, int var2, int var3, String var4, String var5, Consumer<String> var6) {
      EditBox var7 = this.field(var1, var2, var3, 17, var4, var5);
      var7.setMaxLength(6);
      var7.setResponder(var6);
      return var7;
   }

   private void apply() {
      try {
         if (!this.working.isEmpty()) {
            int var1 = Integer.parseInt(this.count);
            if (var1 < 1 || var1 > this.working.getMaxStackSize()) {
               this.error = "Menge: 1 bis " + this.working.getMaxStackSize();
               return;
            }

            if (this.working.isDamageableItem()) {
               int var2 = Integer.parseInt(this.durability);
               if (var2 < 1 || var2 > this.working.getMaxDamage()) {
                  this.error = "Resthaltbarkeit: 1 bis " + this.working.getMaxDamage();
                  return;
               }

               this.working.setDamageValue(this.working.getMaxDamage() - var2);
            }

            this.working.setCount(var1);
         }

         this.editor.replace(this.slot, this.working.copy());
         this.onClose();
      } catch (NumberFormatException var3) {
         this.error = "Gib eine ganze Zahl ein.";
      }
   }

   @Override
   void content(GuiGraphicsExtractor var1) {
      if (showCount) line(var1,"Menge",x,numbersY,inner,MUTED);
      if (showDurability) line(var1,"Resthaltbarkeit",showCount ? col(1,2) : x,numbersY,inner,MUTED);
      if (showEnchantments) {
         int active = EnchantmentHelper.getEnchantmentsForCrafting(working).size();
         String label = active + " aktiv" + (pages > 1 ? "  ·  Seite " + (page+1) + " / " + pages : "");
         line(var1,label,x,enchantY,inner,MUTED);
      }
      if (!this.error.isBlank()) {
         this.line(var1, this.error, this.x, this.bottom - 31, this.inner, -17766);
      }
   }
}

package de.pvptrainer.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

final class KitItemSearch extends TrainerPanel {
   private final KitSlotScreen slotScreen;
   private final int slot;
   private final Item potionItem;
   private final List<KitItemSearch.Choice> catalog = new ArrayList<>();
   private final List<AbstractWidget> resultWidgets = new ArrayList<>();
   private List<KitItemSearch.Choice> matches = List.of();
   private String query = "";
   private int page;
   private int pages;

   KitItemSearch(KitSlotScreen var1, int var2) {
      this(var1, var2, null);
   }

   KitItemSearch(KitSlotScreen var1, int var2, Item var3) {
      super(var1, var3 == null ? "Gegenstand wählen" : "Tranktyp wählen", var1.mode);
      this.slotScreen = var1;
      this.slot = var2;
      this.potionItem = var3;
   }

   static boolean hasPotionTypes(Item var0) {
      return var0 == Items.POTION || var0 == Items.SPLASH_POTION || var0 == Items.LINGERING_POTION || var0 == Items.TIPPED_ARROW;
   }

   static String potionLabel(Item var0, Holder<Potion> var1) {
      String var2 = PotionContents.createItemStack(var0, var1).getItemName().getString();
      if (((Potion)var1.value()).getEffects().isEmpty()) {
         return var2;
      }

      MobEffectInstance var3 = (MobEffectInstance)((Potion)var1.value()).getEffects().getFirst();
      if (var3.getAmplifier() > 0) {
         var2 = var2 + " " + (var3.getAmplifier() + 1);
      }

      if (!((MobEffect)var3.getEffect().value()).isInstantaneous()) {
         int var4 = var3.getDuration() / 20;
         var2 = var2 + " (Basis " + String.format(Locale.ROOT, "%d:%02d", var4 / 60, var4 % 60) + ")";
      }

      return var2;
   }

   @Override
   void build() {
      this.subtitle = KitEditorScreen.slotName(this.slot);
      if (this.catalog.isEmpty()) {
         for (Item var1 : BuiltInRegistries.ITEM) {
            if ((this.potionItem == null || var1 == this.potionItem) && var1 != Items.AIR && var1.isEnabled(this.minecraft.level.enabledFeatures())) {
               ItemStack var3 = var1.getDefaultInstance();
               if (KitEditorScreen.accepts(this.slot, var3)) {
                  String var4 = BuiltInRegistries.ITEM.getKey(var1).toString();
                  if (hasPotionTypes(var1)) {
                     this.minecraft
                        .level
                        .registryAccess()
                        .lookupOrThrow(Registries.POTION)
                        .listElements()
                        .filter(var1x -> ((Potion)var1x.value()).isEnabled(this.minecraft.level.enabledFeatures()))
                        .forEach(var3x -> {
                           ItemStack var4x = PotionContents.createItemStack(var1, var3x);
                           this.candidate(var4x, potionLabel(var1, var3x), var4 + " · " + var3x.key().identifier());
                        });
                  } else if (var1 == Items.FIREWORK_ROCKET) {
                     for (int duration = 1; duration <= 3; duration++) {
                        ItemStack rocket = var3.copy();
                        KitSlotScreen.setFlightDuration(rocket, duration);
                        this.candidate(rocket, rocket.getHoverName().getString() + " · Flugdauer " + duration, var4);
                     }
                  } else {
                     this.candidate(var3, var3.getHoverName().getString(), var4);
                  }
               }
            }
         }

         this.catalog.sort(Comparator.comparing(KitItemSearch.Choice::name).thenComparing(KitItemSearch.Choice::id));
      }

      this.resultWidgets.clear();
      EditBox var5 = this.field(this.x, this.y, this.inner, 20, "Name oder Item-ID suchen", this.query);
      var5.setResponder(var1x -> {
         this.query = var1x;
         this.page = 0;
         this.filter();
      });
      this.setInitialFocus(var5);
      this.filter();
   }

   private void candidate(ItemStack var1, String var2, String var3) {
      this.catalog.add(new KitItemSearch.Choice(var1, var2, var3, (var2 + " " + var3).toLowerCase(Locale.ROOT)));
   }

   private void filter() {
      String var1 = this.query.strip().toLowerCase(Locale.ROOT);
      this.matches = var1.isEmpty() ? this.catalog : this.catalog.stream().filter(var1x -> var1x.searchable().contains(var1)).toList();
      this.showResults();
   }

   private void showResults() {
      this.resultWidgets.forEach(this::removeWidget);
      this.resultWidgets.clear();
      int var2 = this.y + 26;
      int var3 = Math.max(2, (this.bottom - 22 - var2) / (23 + this.gap));
      int var4 = 2 * var3;
      this.pages = Math.max(1, (this.matches.size() + var4 - 1) / var4);
      this.page = Math.min(this.page, this.pages - 1);

      for (int var5 = 0; var5 < var4 && this.page * var4 + var5 < this.matches.size(); var5++) {
         KitItemSearch.Choice var6 = this.matches.get(this.page * var4 + var5);
         int var7 = var5 % 2;
         TrainerPanel.Action var8 = this.tile(
            this.col(var7, 2), var2 + var5 / 2 * (23 + this.gap), this.cw(var7, 2), 23, var6.name(), "", var6.stack(), TrainerPanel.Style.NORMAL, () -> {
               if (this.potionItem == null) {
                  this.slotScreen.choose(var6.stack());
               } else {
                  this.slotScreen.changePotion((Holder<Potion>)((PotionContents)var6.stack().get(DataComponents.POTION_CONTENTS)).potion().orElseThrow());
               }

               this.onClose();
            }
         );
         var8.tip(var6.name() + " · " + var6.id());
         this.resultWidgets.add(var8);
      }

      TrainerPanel.Action var9 = this.action(this.x, this.bottom - 18, 33, 18, "‹", TrainerPanel.Style.NORMAL, () -> {
         this.page--;
         this.showResults();
      });
      TrainerPanel.Action var10 = this.action(this.right - 33, this.bottom - 18, 33, 18, "›", TrainerPanel.Style.NORMAL, () -> {
         this.page++;
         this.showResults();
      });
      var9.active = this.page > 0;
      var10.active = this.page + 1 < this.pages;
      this.resultWidgets.add(var9);
      this.resultWidgets.add(var10);
   }

   @Override
   void content(GuiGraphicsExtractor var1) {
      this.line(var1, this.matches.size() + " Treffer · " + (this.page + 1) + "/" + this.pages, this.x + 40, this.bottom - 13, this.inner - 80, MUTED);
      if (this.matches.isEmpty()) {
         this.line(var1, "Kein passender Gegenstand gefunden.", this.x, this.y + 30, this.inner, TEXT);
      }
   }

   private record Choice(ItemStack stack, String name, String id, String searchable) {
   }
}

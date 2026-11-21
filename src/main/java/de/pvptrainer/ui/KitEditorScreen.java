package de.pvptrainer.ui;
import de.pvptrainer.kits.KitLibrary;
import de.pvptrainer.kits.KitLoadout;
import de.pvptrainer.kits.MacherItems;
import de.pvptrainer.kits.TrainingSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;

final class KitEditorScreen extends TrainerPanel {
   private final boolean bot;
   private String id;
   private final String origin;
   private final List<MobEffectInstance> effects;
   private final List<ItemStack> original;
   final List<ItemStack> slots;
   private String name;
   private int selected;
   private int sourceSlot = -1;
   private boolean dirty;
   private boolean discard;
   private int dragOrigin = -1;
   private String message = "";
   private int gearY;
   private int inventoryY;
   private int hotbarY;
   private int sideX;
   private int cell;

   KitEditorScreen(KitShelfScreen var1, TrainingSettings.Mode var2, boolean var3, String var4, String var5, KitLoadout var6, KitLoadout var7) {
      super(var1, var3 ? "Bot-Kit bearbeiten" : "Kit bearbeiten", var2);
      this.bot = var3;
      this.id = var4;
      this.name = var5;
      this.origin = var6 == null ? "Eigenes Kit" : (var6.preset() ? "Eigene Kopie · " + var6.source() : var6.source());
      this.slots = var6 == null ? KitLibrary.emptySlots() : new ArrayList<>(var6.slots());
      this.original = var7 == null ? KitLibrary.emptySlots() : var7.slots();
      this.effects = var6 == null ? new ArrayList<>() : new ArrayList<>(var6.effects());
      this.selected = var3 ? 39 : 0;
      this.dirty = var4 == null;
   }

   static EquipmentSlot armorSlot(int var0) {
      return switch (var0) {
         case 36 -> EquipmentSlot.FEET;
         case 37 -> EquipmentSlot.LEGS;
         case 38 -> EquipmentSlot.CHEST;
         case 39 -> EquipmentSlot.HEAD;
         default -> null;
      };
   }

   static boolean accepts(int var0, ItemStack var1) {
      EquipmentSlot var2 = armorSlot(var0);
      if (var2 != null && !var1.isEmpty()) {
         Equippable var3 = (Equippable)var1.get(DataComponents.EQUIPPABLE);
         return var3 != null && var3.slot() == var2 && var1.getCount() == 1;
      } else {
         return true;
      }
   }

   static String slotName(int var0) {
      return switch (var0) {
         case 36 -> "Schuhe";
         case 37 -> "Hose";
         case 38 -> "Brust";
         case 39 -> "Helm";
         case 40 -> "Nebenhand";
         default -> var0 < 9 ? "Hotbar " + (var0 + 1) : "Inventar " + (var0 - 8);
      };
   }

   void replace(int var1, ItemStack var2) {
      if (!accepts(var1, var2)) {
         throw new IllegalArgumentException("Unpassender Ausrüstungsplatz.");
      }

      this.slots.set(var1, var2);
      this.dirty = true;
      this.discard = false;
      this.message = "";
   }

   @Override
   void build() {
      this.subtitle = this.mode.label();
      int effectsWidth = Math.min(132, this.inner / 3);
      EditBox var1 = this.field(this.x, this.y, this.inner - effectsWidth - gap, 19, "Kit-Name", this.name);
      this.action(right - effectsWidth, y, effectsWidth, 19, "Effekte (" + effects.size() + ")", Style.NORMAL,
         () -> open(new KitEffectsScreen(this, bot, effects, updated -> {
            effects.clear();
            effects.addAll(updated);
            dirty = true;
            discard = false;
         })));
      var1.setResponder(var1x -> {
         this.name = var1x;
         this.dirty = true;
         this.discard = false;
      });
      this.cell = Math.min(25, Math.min((this.inner - 99) / 9, (this.bottom - this.y - 90) / 5));
      this.cell = Math.max(17, this.cell);
      this.gearY = this.y + 33;
      this.inventoryY = this.gearY + this.cell + 14;
      this.hotbarY = this.inventoryY + 3 * this.cell + 4;
      this.sideX = this.x + 9 * this.cell + this.gap + 2;

      for (int var2 = 0; var2 < 4; var2++) {
         this.slot(this.x + var2 * this.cell, this.gearY, 39 - var2);
      }

      this.slot(this.x + 5 * this.cell, this.gearY, 40);

      for (int var6 = 0; var6 < 27; var6++) {
         this.slot(this.x + var6 % 9 * this.cell, this.inventoryY + var6 / 9 * this.cell, 9 + var6);
      }

      for (int var7 = 0; var7 < 9; var7++) {
         this.slot(this.x + var7 * this.cell, this.hotbarY, var7);
      }

      int var8 = this.right - this.sideX;
      int var3 = Math.max(16, Math.min(22, (this.bottom - 43 - this.gearY - 24) / 5));
      int var4 = this.gearY + 23;
      this.action(this.sideX, var4, var8, var3, "Item bearbeiten", TrainerPanel.Style.SELECTED, () -> this.open(new KitSlotScreen(this, this.selected)));
      this.action(
         this.sideX, var4 + var3 + this.gap, var8, var3, this.sourceSlot >= 0 ? "Tausch abbrechen" : "Plätze tauschen", TrainerPanel.Style.NORMAL, () -> {
            this.sourceSlot = this.sourceSlot >= 0 ? -1 : this.selected;
            this.message = this.sourceSlot < 0 ? "" : "Zielplatz für den Tausch wählen.";
            this.refresh();
         }
      );
      this.action(this.sideX, var4 + 2 * (var3 + this.gap), var8, var3, "Platz leeren", TrainerPanel.Style.NORMAL, () -> {
         this.replace(this.selected, ItemStack.EMPTY);
         this.refresh();
      }).active = !this.slots.get(this.selected).isEmpty();
      this.action(this.sideX, var4 + 3 * (var3 + this.gap), var8, var3, "Platz zurücksetzen", TrainerPanel.Style.NORMAL, () -> {
         this.replace(this.selected, this.original.get(this.selected).copy());
         this.refresh();
      });
      if (this.bot && this.mode == TrainingSettings.Mode.CRYSTAL) {
         this.action(
            this.sideX,
            var4 + 4 * (var3 + this.gap),
            var8,
            var3,
            "Schutz-Preset",
            TrainerPanel.Style.NORMAL,
            () -> this.open(new KitEditorScreen.ArmorVariants(this))
         );
      }

      byte var5 = 19;
      if (this.discard) {
         this.action(this.col(0, 2), this.bottom - var5, this.cw(0, 2), var5, "Weiter bearbeiten", TrainerPanel.Style.NORMAL, () -> {
            this.discard = false;
            this.message = "";
            this.refresh();
         });
         this.action(this.col(1, 2), this.bottom - var5, this.cw(1, 2), var5, "Verwerfen", TrainerPanel.Style.SELECTED, () -> super.onClose());
      } else {
         this.action(this.col(0, 2), this.bottom - var5, this.cw(0, 2), var5, "Zurück", TrainerPanel.Style.NORMAL, this::onClose);
         this.action(this.col(1, 2), this.bottom - var5, this.cw(1, 2), var5, "Speichern & auswählen", TrainerPanel.Style.PRIMARY, this::save);
      }
   }

   @Override
   void content(GuiGraphicsExtractor var1) {
      this.line(var1, "Rüstung", this.x, this.gearY - 10, 4 * this.cell, -3819081);
      this.line(var1, "Nebenhand", this.x + 5 * this.cell, this.gearY - 10, 4 * this.cell, -3819081);
      this.line(var1, "Inventar", this.x, this.inventoryY - 10, 9 * this.cell, -3819081);
      this.line(var1, slotName(this.selected), this.sideX, this.gearY, this.right - this.sideX, -3819081);
      ItemStack var2 = this.slots.get(this.selected);
      this.line(var1, var2.isEmpty() ? "Leer" : MacherItems.label(var2).getString(), this.sideX, this.gearY + 11, this.right - this.sideX, -593428);
      if (!this.message.isEmpty()) {
         this.line(var1, this.message, this.x, this.bottom - 31, this.inner, -3819081);
      }
   }

   private void slot(int var1, int var2, final int var3) {
      final ItemStack var4 = MacherItems.name(this.slots.get(var3).copy());
      final String var5 = slotName(var3) + " · " + (var4.isEmpty() ? "Leer" : MacherItems.label(var4).getString() + " × " + var4.getCount());
      Button var6 = new Button(var1, var2, this.cell, this.cell, Component.literal(var5), var2x -> this.pick(var3), Supplier::get) {
         protected void extractContents(GuiGraphicsExtractor var1, int var2x, int var3x, float var4x) {
            int var5x = this.isHoveredOrFocused()
               ? -593428
               : (var3 != KitEditorScreen.this.selected && var3 != KitEditorScreen.this.sourceSlot ? -12107967 : KitEditorScreen.this.accent());
            var1.fill(
               this.getX(), this.getY(), this.getRight(), this.getBottom(), var3 == KitEditorScreen.this.selected ? KitEditorScreen.this.shade() : -14014678
            );
            var1.outline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), var5x);
            int var6x = this.getX() + (this.getWidth() - 16) / 2;
            int var7 = this.getY() + (this.getHeight() - 16) / 2;
            if (!var4.isEmpty()) {
               var1.item(var4, var6x, var7);
               var1.itemDecorations(KitEditorScreen.this.font, var4, var6x, var7);
            } else if (var3 < 9) {
               var1.text(KitEditorScreen.this.font, Integer.toString(var3 + 1), var6x + 5, var7 + 4, -6976630, false);
            }

            if (this.isHoveredOrFocused()) {
               if (var4.isEmpty()) {
                  var1.setTooltipForNextFrame(Component.literal(var5), var2x, var3x);
               } else {
                  var1.setTooltipForNextFrame(KitEditorScreen.this.font, var4, var2x, var3x);
               }
            }
         }
      };
      this.addRenderableWidget(var6);
   }

   private int slotAt(double var1, double var3) {
      int var5 = (int)Math.floor((var1 - this.x) / this.cell);
      if (var5 < 0 || var5 >= 9) {
         return -1;
      } else if (var3 >= this.gearY && var3 < this.gearY + this.cell) {
         return var5 < 4 ? 39 - var5 : (var5 == 5 ? 40 : -1);
      } else if (var3 >= this.inventoryY && var3 < this.inventoryY + 3 * this.cell) {
         return 9 + 9 * (int)((var3 - this.inventoryY) / this.cell) + var5;
      } else {
         return var3 >= this.hotbarY && var3 < this.hotbarY + this.cell ? var5 : -1;
      }
   }

   public boolean mouseClicked(MouseButtonEvent var1, boolean var2) {
      int var3 = this.slotAt(var1.x(), var1.y());
      this.dragOrigin = var1.button() == 0 && this.sourceSlot < 0 ? var3 : -1;
      if (var3 < 0 || (!var2 || var1.button() != 0) && var1.button() != 1) {
         return super.mouseClicked(var1, var2);
      }

      this.selected = var3;
      this.open(new KitSlotScreen(this, var3));
      return true;
   }

   public boolean mouseReleased(MouseButtonEvent var1) {
      int var2 = this.slotAt(var1.x(), var1.y());
      if (var1.button() == 0 && this.dragOrigin >= 0 && var2 >= 0 && var2 != this.dragOrigin) {
         this.sourceSlot = this.dragOrigin;
         this.dragOrigin = -1;
         this.pick(var2);
         return true;
      } else {
         this.dragOrigin = -1;
         return super.mouseReleased(var1);
      }
   }

   public boolean keyPressed(KeyEvent var1) {
      if (!(this.getFocused() instanceof EditBox) && var1.key() >= 49 && var1.key() <= 57) {
         this.sourceSlot = this.selected;
         this.pick(var1.key() - 49);
         return true;
      } else {
         return super.keyPressed(var1);
      }
   }

   private void pick(int var1) {
      if (this.sourceSlot >= 0 && this.sourceSlot != var1) {
         ItemStack var2 = this.slots.get(this.sourceSlot);
         ItemStack var3 = this.slots.get(var1);
         if (accepts(var1, var2) && accepts(this.sourceSlot, var3)) {
            this.replace(var1, var2);
            this.replace(this.sourceSlot, var3);
            this.sourceSlot = -1;
            this.selected = var1;
         } else {
            this.message = "Diese Gegenstände passen nicht in die gewählten Plätze.";
         }
      } else {
         this.selected = var1;
         this.sourceSlot = -1;
         this.message = "";
      }

      this.refresh();
   }

   private void save() {
      try {
         KitLoadout var1 = KitLibrary.save(this.minecraft.level.registryAccess(), this.mode, this.id, this.name, this.slots, this.effects, this.origin);
         this.id = var1.id();
         KitLibrary.select(this.mode, this.bot, this.id);
         this.dirty = false;
         super.onClose();
      } catch (RuntimeException var2) {
         this.message = var2.getMessage() == null ? "Speichern fehlgeschlagen." : var2.getMessage();
         this.refresh();
      }
   }

   @Override
   public void onClose() {
      if (this.dirty && !this.discard) {
         this.discard = true;
         this.message = "Ungespeicherte Änderungen verwerfen?";
         this.refresh();
      } else {
         super.onClose();
      }
   }

   private static final class ArmorVariants extends TrainerPanel {
      private final KitEditorScreen editor;

      ArmorVariants(KitEditorScreen var1) {
         super(var1, "Schutz-Preset", var1.mode);
         this.editor = var1;
      }

      @Override
      void build() {
         this.subtitle = "Crystal";
         String[] var1 = new String[]{"Viermal Schutz IV", "Explosionsschutz IV auf Hose", "Explosionsschutz IV auf Hose und Schuhen"};

         for (int var2 = 0; var2 < var1.length; var2++) {
            int var3 = var2;
            this.tile(
               this.x,
               this.y + var2 * 37,
               this.inner,
               32,
               var1[var2],
               "Auf vorhandene Rüstung anwenden",
               icon(this.mode),
               TrainerPanel.Style.NORMAL,
               () -> {
                  for (int var2x = 36; var2x <= 39; var2x++) {
                     ItemStack var3x = this.editor.slots.get(var2x).copy();
                     if (!var3x.isEmpty()) {
                        boolean var4 = var3 > 0 && var2x == 37 || var3 == 2 && var2x == 36;
                        Reference var5 = this.minecraft
                           .level
                           .registryAccess()
                           .lookupOrThrow(Registries.ENCHANTMENT)
                           .getOrThrow(var4 ? Enchantments.BLAST_PROTECTION : Enchantments.PROTECTION);
                        if (((Enchantment)var5.value()).isSupportedItem(var3x)) {
                           KitSlotScreen.enchant(var3x, var5, 4);
                           this.editor.replace(var2x, var3x);
                        }
                     }
                  }

                  this.onClose();
               }
            );
         }
      }
   }
}

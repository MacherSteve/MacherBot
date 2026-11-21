package de.pvptrainer.ui;
import de.pvptrainer.kits.TrainingSettings;

import java.time.Duration;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

abstract class TrainerPanel extends Screen {
   private static final Identifier BOT_SKIN = Identifier.fromNamespaceAndPath("pvptrainer", "textures/entity/macher_steve.png");
   static final int PANEL = -15001317;
   static final int CELL = -14014678;
   static final int LINE = -12107967;
   static final int HOVER = -12963533;
   static final int TEXT = -593428;
   static final int MUTED = -3819081;
   static final int OFF = -6976630;
   static final int EMBER = -31624;
   static final int CRIMSON = -6539974;
   static final int SELECTED = -12834516;
   final Screen parent;
   TrainingSettings.Mode mode;
   int left;
   int top;
   int panelWidth;
   int panelHeight;
   int x;
   int y;
   int inner;
   int bottom;
   int right;
   int gap;
   int pad;
   String subtitle = "";

   TrainerPanel(Screen var1, String var2, TrainingSettings.Mode var3) {
      super(Component.literal(var2));
      this.parent = var1;
      this.mode = var3;
   }

   protected final void init() {
      this.panelWidth = Math.min(this.preferredWidth(), this.width - 8);
      this.panelHeight = Math.min(this.preferredHeight(), this.height - 8);
      this.left = (this.width - this.panelWidth) / 2;
      this.top = (this.height - this.panelHeight) / 2;
      this.pad = this.panelWidth >= 380 && this.panelHeight >= 246 ? 12 : 7;
      this.gap = this.pad == 7 ? 3 : 5;
      this.x = this.left + this.pad;
      this.right = this.left + this.panelWidth - this.pad;
      this.inner = this.right - this.x;
      this.y = this.top + (this.pad == 7 ? 27 : 33);
      this.bottom = this.top + this.panelHeight - this.pad;
      this.build();
      TrainerPanel.Action var1 = this.action(this.right - 19, this.top + 5, 19, 16, this.parent == null ? "×" : "‹", TrainerPanel.Style.NORMAL, this::onClose);
      var1.tip(this.parent == null ? "Menü schließen (Esc)" : "Zurück (Esc)");
   }

   int preferredWidth() {
      return 530;
   }

   int preferredHeight() {
      return 304;
   }

   abstract void build();

   void content(GuiGraphicsExtractor var1) {
   }

   final void refresh() {
      this.rebuildWidgets();
   }

   final void open(Screen var1) {
      this.minecraft.gui.setScreen(var1);
   }

   public void onClose() {
      this.minecraft.gui.setScreen(this.parent);
   }

   public boolean isInGameUi() {
      return true;
   }

   int accent() {
      return -31624;
   }

   int shade() {
      return -12834516;
   }

   static ItemStack icon(TrainingSettings.Mode var0) {
      return new ItemStack(switch (var0) {
         case CRYSTAL -> Items.END_CRYSTAL;
         case MACE -> Items.MACE;
         case SWORD -> Items.DIAMOND_SWORD;
         case UHC -> Items.GOLDEN_APPLE;
      });
   }

   int col(int var1, int var2) {
      return this.x + var1 * (this.inner + this.gap) / var2;
   }

   int cw(int var1, int var2) {
      return this.col(var1 + 1, var2) - this.col(var1, var2) - this.gap;
   }

   String fit(String var1, int var2) {
      if (var2 <= 0) {
         return "";
      } else {
         return this.font.width(var1) <= var2 ? var1 : this.font.plainSubstrByWidth(var1, Math.max(0, var2 - this.font.width("…"))) + "…";
      }
   }

   void line(GuiGraphicsExtractor var1, String var2, int var3, int var4, int var5, int var6) {
      var1.text(this.font, this.fit(var2, var5), var3, var4, var6, false);
   }

   TrainerPanel.Action action(int var1, int var2, int var3, int var4, String var5, TrainerPanel.Style var6, Runnable var7) {
      return (TrainerPanel.Action)this.addRenderableWidget(new TrainerPanel.Action(var1, var2, var3, var4, var5, "", ItemStack.EMPTY, var6, var7));
   }

   TrainerPanel.Action tile(int var1, int var2, int var3, int var4, String var5, String var6, ItemStack var7, TrainerPanel.Style var8, Runnable var9) {
      TrainerPanel.Action var10 = (TrainerPanel.Action)this.addRenderableWidget(new TrainerPanel.Action(var1, var2, var3, var4, var5, var6, var7, var8, var9));
      var10.tip(var5 + (var6.isEmpty() ? "" : " · " + var6));
      return var10;
   }

   EditBox field(int var1, int var2, int var3, int var4, String var5, String var6) {
      EditBox var7 = new EditBox(this.font, var1, var2, var3, var4, Component.literal(var5));
      var7.setHint(Component.literal(var5));
      var7.setMaxLength(70);
      var7.setValue(var6);
      return (EditBox)this.addRenderableWidget(var7);
   }

   public void extractRenderState(GuiGraphicsExtractor var1, int var2, int var3, float var4) {
      var1.fill(this.left + 2, this.top + 3, this.left + this.panelWidth + 2, this.top + this.panelHeight + 3, 1946157056);
      var1.fill(this.left, this.top, this.left + this.panelWidth, this.top + this.panelHeight, -15001317);
      var1.outline(this.left, this.top, this.panelWidth, this.panelHeight, -12107967);
      var1.fill(this.left + 1, this.top + 1, this.left + 3, this.top + (this.pad == 7 ? 21 : 26), -6539974);
      int var5 = this.top + (this.pad == 7 ? 9 : 12);
      MutableComponent var6 = this.getTitle().copy().withStyle(ChatFormatting.BOLD);
      int var7 = this.x;
      if (this.parent == null) {
         PlayerFaceExtractor.extractRenderState(var1, BOT_SKIN, this.x, var5 - 4, 16, true, false, -1);
         var7 += 22;
      }

      var1.text(this.font, var6, var7, var5, -593428, false);
      int var8 = var7 + this.font.width(var6) + 10;
      if (this.right - 24 - var8 > 28) {
         this.line(var1, this.subtitle, var8, var5, this.right - 24 - var8, -3819081);
      }

      this.content(var1);
      super.extractRenderState(var1, var2, var3, var4);
   }

   static void drawButton(GuiGraphicsExtractor graphics, net.minecraft.client.gui.components.AbstractWidget widget,
                         Style style, int selectedColor, int accentColor) {
      boolean hover = widget.active && widget.isHoveredOrFocused();
      boolean primary = widget.active && style == Style.PRIMARY;
      int color = primary ? (hover ? -5028796 : CRIMSON) : (hover ? HOVER : (style == Style.SELECTED ? selectedColor : CELL));
      graphics.fill(widget.getX(), widget.getY(), widget.getRight(), widget.getBottom(), color);
      if (hover) {
         graphics.outline(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(), TEXT);
      } else if (style == Style.SELECTED && widget.active) {
         graphics.fill(widget.getX(), widget.getBottom()-2, widget.getRight(), widget.getBottom(), accentColor);
      } else if (!primary) {
         graphics.fill(widget.getX(), widget.getBottom()-1, widget.getRight(), widget.getBottom(), LINE);
      }
   }

   class Action extends Button {
      private final String title;
      private final String detail;
      private final ItemStack icon;
      private final TrainerPanel.Style style;

      Action(int var2, int var3, int var4, int var5, String var6, String var7, ItemStack var8, TrainerPanel.Style var9, Runnable var10) {
         super(var2, var3, var4, var5, Component.literal(var7.isEmpty() ? var6 : var6 + " · " + var7), var2x -> var10.run(), DEFAULT_NARRATION);
         this.title = var6;
         this.detail = var7;
         this.icon = var8;
         this.style = var9;
      }

      void tip(String var1) {
         this.setTooltip(Tooltip.create(Component.literal(var1)));
         this.setTooltipDelay(Duration.ofMillis(320L));
      }

      protected void extractContents(GuiGraphicsExtractor var1, int var2, int var3, float var4) {
         drawButton(var1,this,this.style,TrainerPanel.this.shade(),TrainerPanel.this.accent());

         int var8 = this.active ? -593428 : -6976630;
         int var9 = this.getY() + this.getHeight() / 2;
         boolean var10 = !this.detail.isEmpty() && this.getHeight() >= 25;
         if (this.icon.isEmpty() && this.detail.isEmpty()) {
            String var13 = TrainerPanel.this.fit(this.title, this.getWidth() - 5);
            var1.text(TrainerPanel.this.font, var13, this.getX() + (this.getWidth() - TrainerPanel.this.font.width(var13)) / 2, var9 - 4, var8, false);
         } else {
            int var11 = this.getX() + 5;
            if (!this.icon.isEmpty() && this.getWidth() >= 44) {
               var1.item(this.icon, var11, var9 - 8);
               var11 += 20;
            }

            int var12 = this.getRight() - var11 - 4;
            if (var10) {
               TrainerPanel.this.line(var1, this.title, var11, var9 - 10, var12, -3819081);
               TrainerPanel.this.line(var1, this.detail, var11, var9 + 1, var12, var8);
            } else {
               TrainerPanel.this.line(var1, this.detail.isEmpty() ? this.title : this.title + " · " + this.detail, var11, var9 - 4, var12, var8);
            }
         }
      }
   }

   enum Style {
      NORMAL,
      SELECTED,
      PRIMARY;
   }
}

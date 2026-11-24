package de.pvptrainer.ui;

import de.pvptrainer.kits.TrainingSettings;

import java.time.Duration;
import java.util.function.IntConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

abstract class TrainerPanel extends Screen {
   private static final Identifier BOT_SKIN = Identifier.fromNamespaceAndPath("pvptrainer", "textures/entity/macher_steve.png");

   // Apple Obsidian Dark-Mode palette (deep dark surfaces, neutral gray contours)
   static final int PANEL = 0xF70C0C10;
   static final int TRACK = 0xFF07070A;
   static final int SURFACE = 0xFF121217;
   static final int CELL = 0xFF18181F;
   static final int HOVER = 0xFF22222B;
   static final int SEGMENT_ACTIVE = 0xFF262630;
   static final int LINE = 0xFF262630;
   static final int BORDER = 0xFF343440;
   static final int BORDER_HOVER = 0xFF525262;
   static final int BORDER_SELECTED = 0xFF747486;
   static final int ACCENT_LINE = 0xFFC8C8D4;
   static final int TEXT = 0xFFF5F5F7;
   static final int MUTED = 0xFF98989F;
   static final int OFF = 0xFF636368;
   static final int EMBER = 0xFFC8C8D4;
   static final int CRIMSON = 0xFFD43B44;
   static final int CRIMSON_HOVER = 0xFFE54D56;
   static final int SELECTED = 0xFF25252F;
   static final int APPLE_GREEN = 0xFF34C759;

   // Quick-Modifier semantic colors (active state; inactive is always OFF)
   static final int ACCENT_AMBER = 0xFFFFB84D;
   static final int ACCENT_GREEN = 0xFF4CD964;
   static final int ACCENT_SKY = 0xFF5AC8FA;
   static final int ACCENT_CYAN = 0xFF32D7D7;
   static final int ACCENT_CORAL = 0xFFFF7A6B;
   static final int ACCENT_VIOLET = 0xFFBF8BFF;
   static final int ACCENT_GOLD = 0xFFFFD60A;

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

   TrainerPanel(Screen parent, String title, TrainingSettings.Mode mode) {
      super(Component.literal(title));
      this.parent = parent;
      this.mode = mode;
   }

   protected final void init() {
      this.panelWidth = Math.min(this.preferredWidth(), this.width - 8);
      this.panelHeight = Math.min(this.preferredHeight(), this.height - 8);
      this.left = (this.width - this.panelWidth) / 2;
      this.top = (this.height - this.panelHeight) / 2;
      this.pad = this.panelWidth >= 420 && this.panelHeight >= 256 ? 12 : 8;
      this.gap = this.pad <= 8 ? 4 : 5;
      this.x = this.left + this.pad;
      this.right = this.left + this.panelWidth - this.pad;
      this.inner = this.right - this.x;
      this.y = this.top + (this.pad <= 8 ? 27 : 33);
      this.bottom = this.top + this.panelHeight - this.pad;
      this.build();
      if (this.showDefaultCloseButton()) {
         TrainerPanel.Action close = this.action(this.right - 20, this.top + 6, 20, 17,
            this.parent == null ? "×" : "‹", TrainerPanel.Style.GHOST, this::onClose);
         close.tip(this.parent == null ? "Menü schließen (Esc)" : "Zurück (Esc)");
      }
   }

   boolean showDefaultCloseButton() {
      return true;
   }

   int preferredWidth() {
      return 590;
   }

   int preferredHeight() {
      return 334;
   }

   abstract void build();

   void content(GuiGraphicsExtractor graphics) {
   }

   final void refresh() {
      this.rebuildWidgets();
   }

   final void open(Screen screen) {
      de.pvptrainer.compat.McCompat.setScreen(this.minecraft, screen);
   }

   public void onClose() {
      de.pvptrainer.compat.McCompat.setScreen(this.minecraft, this.parent);
   }

   public boolean isInGameUi() {
      return true;
   }

   int headerRightMargin() {
      return 26;
   }

   int accent() {
      return EMBER;
   }

   int shade() {
      return SELECTED;
   }

   static ItemStack icon(TrainingSettings.Mode mode) {
      return new ItemStack(switch (mode) {
         case CRYSTAL -> Items.END_CRYSTAL;
         case MACE -> Items.MACE;
         case SWORD -> Items.DIAMOND_SWORD;
         case UHC -> Items.GOLDEN_APPLE;
         case HUGOSMP -> Items.WIND_CHARGE;
      });
   }

   int col(int index, int count) {
      return this.x + index * (this.inner + this.gap) / count;
   }

   int cw(int index, int count) {
      return this.col(index + 1, count) - this.col(index, count) - this.gap;
   }

   static int gridCol(int startX, int totalW, int gap, int index, int count) {
      return startX + index * (totalW + gap) / count;
   }

   static int gridW(int startX, int totalW, int gap, int index, int count) {
      return gridCol(startX, totalW, gap, index + 1, count) - gridCol(startX, totalW, gap, index, count) - gap;
   }

   String fit(String text, int maxWidth) {
      if (maxWidth <= 0) {
         return "";
      }
      return this.font.width(text) <= maxWidth
         ? text
         : this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - this.font.width("…"))) + "…";
   }

   void line(GuiGraphicsExtractor graphics, String text, int x, int y, int maxWidth, int color) {
      graphics.text(this.font, this.fit(text, maxWidth), x, y, color, false);
   }

   TrainerPanel.Action action(int x, int y, int w, int h, String label, TrainerPanel.Style style, Runnable onPress) {
      return (TrainerPanel.Action)this.addRenderableWidget(new TrainerPanel.Action(x, y, w, h, label, "", ItemStack.EMPTY, style, onPress));
   }

   TrainerPanel.Action tile(int x, int y, int w, int h, String title, String detail, ItemStack icon, TrainerPanel.Style style, Runnable onPress) {
      TrainerPanel.Action widget = (TrainerPanel.Action)this.addRenderableWidget(new TrainerPanel.Action(x, y, w, h, title, detail, icon, style, onPress));
      widget.tip(title + (detail.isEmpty() ? "" : " · " + detail));
      return widget;
   }

   IconChip iconChip(int x, int y, int w, int h, Identifier icon, String label, boolean activeState, int activeColor,
                     Runnable onLeft, Runnable onRight, IntConsumer onScroll) {
      return this.addRenderableWidget(new IconChip(x, y, w, h, icon, label, activeState, activeColor, onLeft, onRight, onScroll));
   }

   EditBox field(int x, int y, int w, int h, String hint, String value) {
      EditBox box = new EditBox(this.font, x, y, w, h, Component.literal(hint));
      box.setHint(Component.literal(hint));
      box.setMaxLength(70);
      box.setValue(value);
      return (EditBox)this.addRenderableWidget(box);
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      // Soft drop shadow + Apple dark-glass rounded panel
      fillRounded2(graphics, this.left + 2, this.top + 4, this.panelWidth, this.panelHeight, 0x55000000);
      fillRounded2(graphics, this.left, this.top, this.panelWidth, this.panelHeight, PANEL);
      outlineRounded2(graphics, this.left, this.top, this.panelWidth, this.panelHeight, BORDER);

      int headerY = this.top + (this.pad <= 8 ? 9 : 11);
      int titleX = this.x;
      if (this.parent == null) {
         PlayerFaceExtractor.extractRenderState(graphics, BOT_SKIN, this.x, headerY - 3, 14, true, false, -1);
         titleX += 19;
      }

      MutableComponent heading = this.getTitle().copy().withStyle(ChatFormatting.BOLD);
      graphics.text(this.font, heading, titleX, headerY, TEXT, false);
      int subX = titleX + this.font.width(heading) + 8;
      int rightLimit = this.right - this.headerRightMargin();
      if (!this.subtitle.isEmpty() && rightLimit - subX > 24) {
         this.line(graphics, this.subtitle, subX, headerY, rightLimit - subX, MUTED);
      }

      // Subtle Apple hairline separator below header
      graphics.fill(this.x, this.y - (this.pad <= 8 ? 4 : 5), this.right, this.y - (this.pad <= 8 ? 3 : 4), LINE);

      this.content(graphics);
      super.extractRenderState(graphics, mouseX, mouseY, delta);
   }

   // --- Clean rounded Apple drawing primitives ---

   static void fillRounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
      if (w <= 2 || h <= 2) {
         g.fill(x, y, x + w, y + h, color);
         return;
      }
      g.fill(x + 1, y, x + w - 1, y + h, color);
      g.fill(x, y + 1, x + 1, y + h - 1, color);
      g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
   }

   static void outlineRounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
      if (w <= 2 || h <= 2) {
         g.outline(x, y, w, h, color);
         return;
      }
      g.fill(x + 1, y, x + w - 1, y + 1, color);
      g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
      g.fill(x, y + 1, x + 1, y + h - 1, color);
      g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
   }

   static void fillRounded2(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
      if (w <= 4 || h <= 4) {
         fillRounded(g, x, y, w, h, color);
         return;
      }
      g.fill(x + 2, y, x + w - 2, y + h, color);
      g.fill(x + 1, y + 1, x + 2, y + h - 1, color);
      g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, color);
      g.fill(x, y + 2, x + 1, y + h - 2, color);
      g.fill(x + w - 1, y + 2, x + w, y + h - 2, color);
   }

   static void outlineRounded2(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
      if (w <= 4 || h <= 4) {
         outlineRounded(g, x, y, w, h, color);
         return;
      }
      g.fill(x + 2, y, x + w - 2, y + 1, color);
      g.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
      g.fill(x, y + 2, x + 1, y + h - 2, color);
      g.fill(x + w - 1, y + 2, x + w, y + h - 2, color);
      // 4 single corner connector pixels
      g.fill(x + 1, y + 1, x + 2, y + 2, color);
      g.fill(x + w - 2, y + 1, x + w - 1, y + 2, color);
      g.fill(x + 1, y + h - 2, x + 2, y + h - 1, color);
      g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color);
   }

   static void drawToggleSwitch(GuiGraphicsExtractor g, int x, int y, boolean on, boolean hovered) {
      int w = 22;
      int h = 12;
      int bg = on ? (hovered ? 0xFF42D668 : APPLE_GREEN) : (hovered ? 0xFF4A4A55 : 0xFF393942);
      fillRounded2(g, x, y, w, h, bg);
      int knobX = on ? x + w - 10 : x + 2;
      fillRounded(g, knobX, y + 2, 8, 8, TEXT);
   }

   static void drawScrollbar(GuiGraphicsExtractor g, int barX, int topY, int height, int scroll, int maxScroll, int contentHeight) {
      drawScrollbar(g, barX, topY, height, scroll, maxScroll, contentHeight, false);
   }

   static void drawScrollbar(GuiGraphicsExtractor g, int barX, int topY, int height, int scroll, int maxScroll, int contentHeight, boolean highlighted) {
      if (maxScroll <= 0 || height < 16) return;
      fillRounded(g, barX, topY, 3, height, 0xFF16161D);
      int thumbH = Math.max(18, (int)Math.round((double)height * height / Math.max(height + 1, contentHeight)));
      int thumbY = topY + (int)Math.round((double)(height - thumbH) * scroll / maxScroll);
      fillRounded(g, barX, thumbY, 3, thumbH, highlighted ? BORDER_SELECTED : BORDER_HOVER);
   }

   static int withAlpha(int rgb, int alpha) {
      return ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
   }

   static void drawButton(GuiGraphicsExtractor graphics, AbstractWidget widget,
                          Style style, int selectedColor, int accentColor) {
      int x = widget.getX();
      int y = widget.getY();
      int w = widget.getWidth();
      int h = widget.getHeight();
      boolean hover = widget.active && widget.isHoveredOrFocused();
      if (style == Style.GHOST) {
         if (hover) {
            fillRounded(graphics, x, y, w, h, HOVER);
         }
         return;
      }
      if (style == Style.SEGMENT) {
         if (hover) {
            fillRounded(graphics, x, y, w, h, 0xFF1D1D25);
         }
         return;
      }
      if (style == Style.SEGMENT_SELECTED) {
         fillRounded(graphics, x, y, w, h, hover ? 0xFF2E2E3A : SEGMENT_ACTIVE);
         outlineRounded(graphics, x, y, w, h, BORDER_HOVER);
         return;
      }
      boolean primary = widget.active && style == Style.PRIMARY;
      if (primary) {
         fillRounded2(graphics, x, y, w, h, hover ? CRIMSON_HOVER : CRIMSON);
         outlineRounded2(graphics, x, y, w, h, 0x26FFFFFF);
         return;
      }
      int fill = hover ? HOVER : (style == Style.SELECTED ? selectedColor : CELL);
      fillRounded(graphics, x, y, w, h, fill);
      if (style == Style.SELECTED && widget.active) {
         outlineRounded(graphics, x, y, w, h, hover ? 0xFF8A8A9C : BORDER_SELECTED);
      } else if (hover) {
         outlineRounded(graphics, x, y, w, h, BORDER_HOVER);
      } else {
         outlineRounded(graphics, x, y, w, h, LINE);
      }
   }

   class Action extends Button {
      private final String title;
      private final String detail;
      private final ItemStack icon;
      private final TrainerPanel.Style style;

      Action(int x, int y, int w, int h, String title, String detail, ItemStack icon, TrainerPanel.Style style, Runnable onPress) {
         super(x, y, w, h, Component.literal(detail.isEmpty() ? title : title + " · " + detail), btn -> onPress.run(), DEFAULT_NARRATION);
         this.title = title;
         this.detail = detail;
         this.icon = icon;
         this.style = style;
      }

      void tip(String text) {
         this.setTooltip(Tooltip.create(Component.literal(text)));
         this.setTooltipDelay(Duration.ofMillis(260L));
      }

      @Override
      protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
         drawButton(graphics, this, this.style, TrainerPanel.this.shade(), TrainerPanel.this.accent());

         int textColor = this.active
            ? (this.style == Style.GHOST || this.style == Style.SEGMENT ? (this.isHoveredOrFocused() ? TEXT : MUTED) : TEXT)
            : OFF;
         int centerY = this.getY() + this.getHeight() / 2;
         boolean twoLine = !this.detail.isEmpty() && this.getHeight() >= 25;
         if (this.icon.isEmpty() && this.detail.isEmpty()) {
            String fitted = TrainerPanel.this.fit(this.title, this.getWidth() - 6);
            graphics.text(TrainerPanel.this.font, fitted, this.getX() + (this.getWidth() - TrainerPanel.this.font.width(fitted)) / 2, centerY - 4, textColor, false);
         } else if (!this.icon.isEmpty() && this.detail.isEmpty() && (this.style == Style.SEGMENT || this.style == Style.SEGMENT_SELECTED)) {
            String fitted = TrainerPanel.this.fit(this.title, this.getWidth() - 24);
            int contentW = (this.getWidth() >= 40 ? 18 : 0) + (fitted.isEmpty() ? 0 : TrainerPanel.this.font.width(fitted));
            int startX = this.getX() + Math.max(4, (this.getWidth() - contentW) / 2);
            if (this.getWidth() >= 40) {
               var pose = graphics.pose();
               pose.pushMatrix();
               pose.translate(startX, centerY - 6);
               pose.scale(0.75f);
               graphics.item(this.icon, 0, 0);
               pose.popMatrix();
               startX += 15;
            }
            if (!fitted.isEmpty()) {
               graphics.text(TrainerPanel.this.font, fitted, startX, centerY - 4, textColor, false);
            }
         } else {
            int startX = this.getX() + 6;
            if (!this.icon.isEmpty() && this.getWidth() >= 44) {
               graphics.item(this.icon, startX, centerY - 8);
               startX += 20;
            }

            int availW = this.getRight() - startX - 5;
            if (twoLine) {
               TrainerPanel.this.line(graphics, this.title, startX, centerY - 10, availW, MUTED);
               TrainerPanel.this.line(graphics, this.detail, startX, centerY + 1, availW, textColor);
            } else {
               TrainerPanel.this.line(graphics, this.detail.isEmpty() ? this.title : this.title + " · " + this.detail, startX, centerY - 4, availW, textColor);
            }
         }
      }
   }

   /** Minimalist Apple-style icon button/chip: gray when inactive, tinted in its accent color when active. */
   final class IconChip extends Button {
      private final Identifier icon;
      private final String label;
      private final boolean activeState;
      private final int activeColor;
      private final Runnable onRight;
      private final IntConsumer onScroll;

      IconChip(int x, int y, int w, int h, Identifier icon, String label, boolean activeState, int activeColor,
               Runnable onLeft, Runnable onRight, IntConsumer onScroll) {
         super(x, y, w, h, Component.literal(label), btn -> onLeft.run(), DEFAULT_NARRATION);
         this.icon = icon;
         this.label = label;
         this.activeState = activeState;
         this.activeColor = activeColor;
         this.onRight = onRight;
         this.onScroll = onScroll;
      }

      IconChip tip(String text) {
         this.setTooltip(Tooltip.create(Component.literal(text)));
         this.setTooltipDelay(Duration.ofMillis(220L));
         return this;
      }

      @Override
      public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
         if (this.active && this.visible && this.isMouseOver(event.x(), event.y())) {
            if (event.button() == 1 && this.onRight != null) {
               this.playDownSound(TrainerPanel.this.minecraft.getSoundManager());
               this.onRight.run();
               return true;
            }
         }
         return super.mouseClicked(event, twice);
      }

      @Override
      public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
         if (this.active && this.visible && this.onScroll != null && this.isMouseOver(mouseX, mouseY) && scrollY != 0) {
            this.onScroll.accept(scrollY > 0 ? 1 : -1);
            return true;
         }
         return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
      }

      @Override
      protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
         boolean hover = this.active && this.isHoveredOrFocused();
         int x = this.getX();
         int y = this.getY();
         int w = this.getWidth();
         int h = this.getHeight();
         if (this.activeState) {
            fillRounded(graphics, x, y, w, h, hover ? HOVER : SELECTED);
            outlineRounded(graphics, x, y, w, h, hover ? 0xFF8A8A9C : BORDER_SELECTED);
         } else {
            fillRounded(graphics, x, y, w, h, hover ? HOVER : CELL);
            outlineRounded(graphics, x, y, w, h, hover ? BORDER_HOVER : LINE);
         }

         int iconTint = !this.active ? 0xFF48484D : (this.activeState ? this.activeColor : (hover ? MUTED : OFF));
         int textTint = !this.active ? 0xFF48484D : (this.activeState ? TEXT : (hover ? TEXT : MUTED));
         boolean showLabel = !this.label.isEmpty() && w >= 28;
         int contentW = 12 + (showLabel ? 3 + TrainerPanel.this.font.width(this.label) : 0);
         int startX = x + Math.max(3, (w - contentW) / 2);
         int iconY = y + (h - 12) / 2;
         UiIcons.draw(graphics, this.icon, startX, iconY, iconTint);
         if (showLabel) {
            String fitted = TrainerPanel.this.fit(this.label, x + w - (startX + 15) - 2);
            graphics.text(TrainerPanel.this.font, fitted, startX + 15, y + (h - 8) / 2, textTint, false);
         }
      }
   }

   enum Style {
      NORMAL,
      SELECTED,
      PRIMARY,
      GHOST,
      SEGMENT,
      SEGMENT_SELECTED;
   }
}

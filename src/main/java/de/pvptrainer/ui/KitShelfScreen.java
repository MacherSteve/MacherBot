package de.pvptrainer.ui;

import de.pvptrainer.kits.KitLibrary;
import de.pvptrainer.kits.KitLoadout;
import de.pvptrainer.kits.TrainingSettings;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Unified Apple-style Kits screen combining Standard Mode Kit and Custom Kits with smooth scrolling. */
final class KitShelfScreen extends TrainerPanel {
   private boolean bot;
   private String message = "";

   private int segY;
   private int segH;
   private int modeSectionY;
   private int customLabelY;
   private int listTop;
   private int listBottom;
   private int scrollOffset;
   private int contentHeight;
   private int maxScroll;
   private boolean draggingScrollbar;
   private int dragStartY;
   private int dragStartScroll;

   private final List<CustomKitRow> rows = new ArrayList<>();

   KitShelfScreen(TrainingScreen parent, TrainingSettings.Mode mode, boolean bot) {
      super(parent, "Kits", mode);
      this.bot = bot;
   }

   KitShelfScreen(TrainingScreen parent, TrainingSettings.Mode mode, boolean bot, boolean ignoredOwn) {
      this(parent, mode, bot);
   }

   @Override
   void build() {
      if (this.minecraft.level == null || this.minecraft.player == null) return;
      this.subtitle = this.mode.label() + " · " + (this.bot ? "Bot-Kit" : "Spieler-Kit");
      RegistryAccess registry = this.minecraft.level.registryAccess();

      // Segmented Control: Dein Kit | Bot-Kit
      this.segY = this.y;
      this.segH = 22;
      int segX = this.x + 2;
      int segW = this.inner - 4;
      for (int i = 0; i < 2; i++) {
         boolean targetBot = i == 1;
         this.action(gridCol(segX, segW, 2, i, 2), this.segY + 2, gridW(segX, segW, 2, i, 2), this.segH - 4,
            targetBot ? "Bot-Kit" : "Dein Kit", targetBot == this.bot ? Style.SEGMENT_SELECTED : Style.SEGMENT, () -> {
               if (this.bot != targetBot) {
                  this.bot = targetBot;
                  this.scrollOffset = 0;
                  this.message = "";
                  this.refresh();
               }
            });
      }

      KitLoadout selected = KitLibrary.selected(registry, this.mode, this.bot);
      KitLoadout modeKit = KitLibrary.modeKit(registry, this.mode, this.bot);
      boolean modeActive = modeKit.id().equals(selected.id());
      boolean modeModified = !modeKit.preset();

      // --- Section 1: Standard Mode Kit (pinned cleanly at top) ---
      this.modeSectionY = this.segY + this.segH + 8;
      int cardY = this.modeSectionY + 11;
      int cardH = 30;
      int btnEditW = 68;
      int btnCopyW = 62;
      int btnResetW = this.bot ? (modeModified ? 56 : 0) : 78;
      int actionsW = btnEditW + btnCopyW + btnResetW + (btnResetW > 0 ? 8 : 4);

      this.tile(this.x, cardY, this.inner - actionsW - 4, cardH,
         modeKit.name(), modeActive ? "Ausgerüstet" : "Ausrüsten", icon(this.mode),
         modeActive ? Style.SELECTED : Style.NORMAL,
         () -> this.run(() -> KitLibrary.select(this.mode, this.bot, modeKit.id()), "Modus-Kit ausgerüstet."));

      int bx = this.right - actionsW;
      this.action(bx, cardY, btnEditW, cardH, "Bearbeiten", Style.NORMAL, () -> {
         String id = KitLibrary.modeKitId(this.mode, this.bot);
         this.edit(id, modeKit.name(), this.currentLayout(registry, modeKit), KitLibrary.builtin(registry, this.mode));
      });
      bx += btnEditW + 4;
      this.action(bx, cardY, btnCopyW, cardH, "Kopieren", Style.NORMAL, () -> this.copy(registry, modeKit))
         .tip("Als eigenes Kit kopieren");
      if (btnResetW > 0) {
         bx += btnCopyW + 4;
         if (modeModified) {
            this.action(bx, cardY, btnResetW, cardH, "Standard", Style.NORMAL, () -> this.confirm(
               "Modus-Kit zurücksetzen?", modeKit.name(), "Deine Änderungen an diesem Modus-Kit werden verworfen.",
               "Zurücksetzen", () -> this.run(() -> KitLibrary.resetModeKit(this.mode, this.bot), "Standardkit wiederhergestellt.")))
               .tip("Modus-Kit auf Standard zurücksetzen");
         } else if (!this.bot) {
            this.action(bx, cardY, btnResetW, cardH, "Layout ↺", Style.NORMAL, () -> this.confirm(
               "Hotbar-Layout zurücksetzen?", selected.name(), "Gespeicherte Positionsänderungen für dieses Kit werden entfernt.",
               "Zurücksetzen", () -> this.run(() -> KitLibrary.resetLayout(this.mode, selected.id()), "Kit-Anordnung wiederhergestellt.")))
               .tip("Gespeicherte Hotbar-Sortierung zurücksetzen");
         }
      }

      // --- Section 2: Custom Kits (smooth scissor-clipped scroll list) ---
      this.customLabelY = cardY + cardH + 10;
      this.listTop = this.customLabelY + 12;
      int footerH = 21;
      int statusSpace = 14;
      this.listBottom = this.bottom - footerH - statusSpace - 4;

      List<KitLoadout> customKits = KitLibrary.list(registry, this.mode).stream().filter(k -> !k.preset()).toList();
      this.layoutCustomRows(customKits, selected);

      // Footer Actions
      int footY = this.bottom - footerH;
      this.action(this.col(0, 3), footY, this.cw(0, 3), footerH, "Neues Kit", Style.PRIMARY,
         () -> this.edit(null, "Mein Kit", null, null));
      this.action(this.col(1, 3), footY, this.cw(1, 3), footerH, "Aus Inventar", Style.NORMAL, () -> {
         KitLoadout captured = KitLibrary.capture(this.minecraft.player);
         var server = this.minecraft.getCurrentServer();
         String place = server == null ? "Eigene Welt" : server.ip;
         if (place.length() > 80) place = place.substring(0, 80);
         KitLoadout kit = new KitLoadout(null, captured.name(), "Inventar · " + place + " · " + LocalDate.now(), captured.slots(), captured.effects());
         this.edit(null, "Mein Kit", kit, kit);
      });
      this.action(this.col(2, 3), footY, this.cw(2, 3), footerH, "Aktives duplizieren", Style.NORMAL,
         () -> this.copy(registry, selected));
   }

   private void layoutCustomRows(List<KitLoadout> customKits, KitLoadout selected) {
      this.rows.clear();
      int rowH = 26;
      int rowGap = 4;
      this.contentHeight = customKits.isEmpty() ? 0 : customKits.size() * rowH + Math.max(0, customKits.size() - 1) * rowGap;
      int viewH = Math.max(24, this.listBottom - this.listTop);
      this.maxScroll = Math.max(0, this.contentHeight - viewH);
      this.scrollOffset = Math.clamp(this.scrollOffset, 0, this.maxScroll);

      int availW = this.maxScroll > 0 ? this.inner - 7 : this.inner;
      int editW = Math.min(76, availW / 5);
      int delW = Math.min(62, availW / 6);
      int mainW = availW - editW - delW - 8;

      for (int i = 0; i < customKits.size(); i++) {
         KitLoadout kit = customKits.get(i);
         boolean inUse = selected.id().equals(kit.id());
         int ry = i * (rowH + rowGap);
         this.rows.add(new CustomKitRow(kit, inUse, this.x, ry, mainW, editW, delW, rowH));
      }
   }

   private KitLoadout currentLayout(RegistryAccess registry, KitLoadout kit) {
      return this.bot ? kit : KitLibrary.personalized(registry, this.mode, kit.id());
   }

   private void copy(RegistryAccess registry, KitLoadout kit) {
      this.edit(null, kit.name() + " · Kopie", this.currentLayout(registry, kit), kit);
   }

   private void edit(String id, String name, KitLoadout current, KitLoadout source) {
      this.open(new KitEditorScreen(this, this.mode, this.bot, id, name, current, source));
   }

   private void confirm(String title, String name, String detail, String actionName, Runnable confirm) {
      this.open(new TrainerPanel(this, title, this.mode) {
         @Override int preferredWidth() { return 410; }
         @Override int preferredHeight() { return 138; }
         @Override void build() {
            this.action(this.col(0, 2), this.bottom - 21, this.cw(0, 2), 21, "Abbrechen", Style.NORMAL, this::onClose);
            this.action(this.col(1, 2), this.bottom - 21, this.cw(1, 2), 21, actionName, Style.PRIMARY, () -> {
               this.onClose();
               confirm.run();
            });
         }
         @Override void content(GuiGraphicsExtractor graphics) {
            this.line(graphics, name, this.x, this.y + 2, this.inner, TEXT);
            graphics.textWithWordWrap(this.font, Component.literal(detail), this.x, this.y + 19, this.inner, MUTED);
         }
      });
   }

   private void run(Runnable change, String success) {
      try {
         change.run();
         this.message = success;
      } catch (RuntimeException ex) {
         this.message = ex.getMessage() == null ? "Speichern fehlgeschlagen." : ex.getMessage();
      }
      this.refresh();
   }

   @Override
   void content(GuiGraphicsExtractor graphics) {
      fillRounded2(graphics, this.x, this.segY, this.inner, this.segH, TRACK);
      outlineRounded2(graphics, this.x, this.segY, this.inner, this.segH, LINE);

      graphics.text(this.font, "MODUS-KIT (STANDARD)", this.x + 1, this.modeSectionY, MUTED, false);
      graphics.text(this.font, "EIGENE KITS · " + this.rows.size(), this.x + 1, this.customLabelY, MUTED, false);

      double mx = this.minecraft.mouseHandler.xpos() * this.width / Math.max(1, this.minecraft.getWindow().getScreenWidth());
      double my = this.minecraft.mouseHandler.ypos() * this.height / Math.max(1, this.minecraft.getWindow().getScreenHeight());
      boolean inView = mx >= this.x && mx <= this.right && my >= this.listTop && my <= this.listBottom;

      graphics.enableScissor(this.x - 1, this.listTop, this.right + 1, this.listBottom);
      if (this.rows.isEmpty()) {
         fillRounded(graphics, this.x, this.listTop, this.inner, 36, SURFACE);
         outlineRounded(graphics, this.x, this.listTop, this.inner, 36, LINE);
         this.line(graphics, "Noch keine eigenen Kits für " + this.mode.label() + ".", this.x + 10, this.listTop + 8, this.inner - 20, TEXT);
         this.line(graphics, "Erstelle unten ein neues Kit oder übernimm direkt dein Inventar.", this.x + 10, this.listTop + 21, this.inner - 20, MUTED);
      } else {
         ItemStack bookIcon = new ItemStack(Items.WRITABLE_BOOK);
         for (CustomKitRow row : this.rows) {
            int ry = this.listTop + row.y - this.scrollOffset;
            if (ry + row.h < this.listTop || ry > this.listBottom) continue;

            boolean hoverMain = inView && mx >= row.x && mx < row.x + row.mainW && my >= ry && my < ry + row.h;
            int editX = row.x + row.mainW + 4;
            int delX = editX + row.editW + 4;
            boolean hoverEdit = inView && mx >= editX && mx < editX + row.editW && my >= ry && my < ry + row.h;
            boolean hoverDel = inView && mx >= delX && mx < delX + row.delW && my >= ry && my < ry + row.h;

            // Main kit row card
            fillRounded(graphics, row.x, ry, row.mainW, row.h, row.inUse ? (hoverMain ? 0xFF2E2E3A : SELECTED) : (hoverMain ? HOVER : CELL));
            outlineRounded(graphics, row.x, ry, row.mainW, row.h, row.inUse ? BORDER_SELECTED : (hoverMain ? BORDER_HOVER : LINE));
            graphics.item(bookIcon, row.x + 6, ry + (row.h - 16) / 2);
            this.line(graphics, row.kit.name(), row.x + 26, ry + 4, row.mainW - 34, TEXT);
            this.line(graphics, row.inUse ? "Ausgerüstet" : "Klicken zum Ausrüsten", row.x + 26, ry + 15, row.mainW - 34, row.inUse ? APPLE_GREEN : MUTED);

            // Edit button
            fillRounded(graphics, editX, ry, row.editW, row.h, hoverEdit ? HOVER : CELL);
            outlineRounded(graphics, editX, ry, row.editW, row.h, hoverEdit ? BORDER_HOVER : LINE);
            String editLbl = this.fit("Bearbeiten", row.editW - 6);
            graphics.text(this.font, editLbl, editX + (row.editW - this.font.width(editLbl)) / 2, ry + (row.h - 8) / 2, TEXT, false);

            // Delete button
            fillRounded(graphics, delX, ry, row.delW, row.h, hoverDel ? HOVER : CELL);
            outlineRounded(graphics, delX, ry, row.delW, row.h, hoverDel ? BORDER_HOVER : LINE);
            String delLbl = this.fit("Löschen", row.delW - 6);
            graphics.text(this.font, delLbl, delX + (row.delW - this.font.width(delLbl)) / 2, ry + (row.h - 8) / 2, hoverDel ? TEXT : MUTED, false);
         }
      }
      graphics.disableScissor();

      if (this.maxScroll > 0) {
         boolean hoverBar = this.draggingScrollbar || (mx >= this.right - 7 && mx <= this.right + 1 && my >= this.listTop && my <= this.listBottom);
         drawScrollbar(graphics, this.right - 3, this.listTop, this.listBottom - this.listTop,
            this.scrollOffset, this.maxScroll, this.contentHeight, hoverBar);
      }

      String status = this.message.isEmpty() ? KitLibrary.notice() : this.message;
      if (!status.isBlank()) {
         this.line(graphics, status, this.x, this.bottom - 33, this.inner, MUTED);
      }
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
      double mx = event.x();
      double my = event.y();
      if (event.button() == 0 && mx >= this.x && mx <= this.right + 1 && my >= this.listTop && my <= this.listBottom) {
         if (this.maxScroll > 0 && mx >= this.right - 7) {
            this.draggingScrollbar = true;
            this.dragStartY = (int)my;
            this.dragStartScroll = this.scrollOffset;
            return true;
         }
         RegistryAccess registry = this.minecraft.level.registryAccess();
         for (CustomKitRow row : this.rows) {
            int ry = this.listTop + row.y - this.scrollOffset;
            if (my < ry || my >= ry + row.h) continue;
            int editX = row.x + row.mainW + 4;
            int delX = editX + row.editW + 4;
            if (mx >= row.x && mx < row.x + row.mainW) {
               this.playClick();
               this.run(() -> KitLibrary.select(this.mode, this.bot, row.kit.id()), row.kit.name() + " ausgerüstet.");
               return true;
            }
            if (mx >= editX && mx < editX + row.editW) {
               this.playClick();
               this.edit(row.kit.id(), row.kit.name(), this.currentLayout(registry, row.kit), row.kit);
               return true;
            }
            if (mx >= delX && mx < delX + row.delW) {
               this.playClick();
               this.confirm("Kit löschen?", row.kit.name(), "Die aktive Auswahl fällt auf das jeweilige Modus-Kit zurück.",
                  "Löschen", () -> this.run(() -> KitLibrary.delete(row.kit.id()), "Kit gelöscht."));
               return true;
            }
         }
      }
      return super.mouseClicked(event, twice);
   }

   @Override
   public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
      if (this.draggingScrollbar && this.maxScroll > 0) {
         int viewH = Math.max(16, this.listBottom - this.listTop);
         int thumbH = Math.max(18, (int)Math.round((double)viewH * viewH / Math.max(viewH + 1, this.contentHeight)));
         int trackRange = Math.max(1, viewH - thumbH);
         int deltaPx = (int)event.y() - this.dragStartY;
         this.scrollOffset = Math.clamp(this.dragStartScroll + (int)Math.round((double)deltaPx * this.maxScroll / trackRange), 0, this.maxScroll);
         return true;
      }
      return super.mouseDragged(event, dx, dy);
   }

   @Override
   public boolean mouseReleased(MouseButtonEvent event) {
      if (this.draggingScrollbar) {
         this.draggingScrollbar = false;
         return true;
      }
      return super.mouseReleased(event);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.maxScroll > 0 && mouseX >= this.x && mouseX <= this.right && mouseY >= this.listTop && mouseY <= this.listBottom) {
         int next = Math.clamp(this.scrollOffset - (int)Math.round(scrollY * 22.0), 0, this.maxScroll);
         if (next != this.scrollOffset) {
            this.scrollOffset = next;
            return true;
         }
      }
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   private void playClick() {
      this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
   }

   private record CustomKitRow(KitLoadout kit, boolean inUse, int x, int y, int mainW, int editW, int delW, int h) {}
}

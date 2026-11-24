package de.pvptrainer.ui;

import de.pvptrainer.TrainerClient;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.kits.TrainingSettings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** In-Game PvP-Wissensdatenbank mit Beispielen, Timings, Ablaeufen und Direktstart in den passenden Drill. */
final class KnowledgeGuideScreen extends TrainerPanel {
   private KnowledgeGuide.Category category;
   private String selectedId;
   private int listPage;
   private int detailScroll;
   private int detailX;
   private int detailY;
   private int detailW;
   private int detailH;
   private int maxScroll;
   private KnowledgeGuide.Entry activeEntry;
   private List<StyledLine> detailLines = List.of();

   KnowledgeGuideScreen(Screen parent, TrainingSettings.Mode mode, Drill initialDrill) {
      super(parent, "Technik-Guide", mode);
      KnowledgeGuide.Entry initial = KnowledgeGuide.forDrill(initialDrill, mode);
      if (initial != null) {
         this.category = initial.category();
         this.selectedId = initial.id();
      } else {
         this.category = KnowledgeGuide.Category.BASICS;
         this.selectedId = "";
      }
   }

   @Override
   void build() {
      KnowledgeGuide.Category[] categories = KnowledgeGuide.Category.values();
      int tabH = 22;
      int segX = this.x + 2;
      int segW = this.inner - 4;
      for (int i = 0; i < categories.length; i++) {
         KnowledgeGuide.Category cat = categories[i];
         this.action(
            gridCol(segX, segW, 2, i, categories.length),
            this.y + 2,
            gridW(segX, segW, 2, i, categories.length),
            tabH - 4,
            cat.label(),
            cat == this.category ? Style.SEGMENT_SELECTED : Style.SEGMENT,
            () -> {
               this.category = cat;
               this.listPage = 0;
               this.detailScroll = 0;
               List<KnowledgeGuide.Entry> inCat = KnowledgeGuide.byCategory(cat);
               this.selectedId = inCat.isEmpty() ? "" : inCat.getFirst().id();
               this.refresh();
            }
         ).tip(cat.label() + " · " + KnowledgeGuide.byCategory(cat).size() + " Techniken");
      }

      List<KnowledgeGuide.Entry> entries = KnowledgeGuide.byCategory(this.category);
      this.activeEntry = entries.stream()
         .filter(e -> e.id().equals(this.selectedId))
         .findFirst()
         .orElse(entries.isEmpty() ? null : entries.getFirst());
      if (this.activeEntry != null) {
         this.selectedId = this.activeEntry.id();
         int activeIdx = entries.indexOf(this.activeEntry);
         this.subtitle = this.category.label() + " · " + (activeIdx + 1) + "/" + entries.size();
      } else {
         this.subtitle = this.category.label();
      }

      int contentY = this.y + tabH + this.gap;
      int footerH = 20;
      int contentBottom = this.bottom - footerH - this.gap;
      int contentH = Math.max(80, contentBottom - contentY);

      int listW = Math.max(150, (this.inner - this.gap) * 38 / 100);
      this.detailX = this.x + listW + this.gap;
      this.detailY = contentY;
      this.detailW = this.right - this.detailX;
      this.detailH = contentH;

      int rowH = 24;
      int perPage = Math.max(1, (contentH + this.gap) / (rowH + this.gap));
      int totalPages = Math.max(1, (entries.size() + perPage - 1) / perPage);
      if (this.activeEntry != null && entries.indexOf(this.activeEntry) >= 0) {
         int targetPage = entries.indexOf(this.activeEntry) / perPage;
         if (this.listPage >= totalPages) {
            this.listPage = targetPage;
         }
      }
      this.listPage = Math.clamp(this.listPage, 0, totalPages - 1);

      int start = this.listPage * perPage;
      for (int i = 0; i < perPage && start + i < entries.size(); i++) {
         KnowledgeGuide.Entry entry = entries.get(start + i);
         boolean selected = entry == this.activeEntry;
         this.tile(
            this.x,
            contentY + i * (rowH + this.gap),
            listW,
            rowH,
            entry.title(),
            "",
            KnowledgeGuide.iconStack(entry.icon()),
            selected ? Style.SELECTED : Style.NORMAL,
            () -> {
               this.selectedId = entry.id();
               this.detailScroll = 0;
               this.refresh();
            }
         ).tip(entry.title() + "\n" + entry.timing());
      }

      // Left footer: list pagination (when needed) or Back button
      if (totalPages > 1) {
         int navW = 28;
         this.action(this.x, this.bottom - footerH, navW, footerH, "‹", Style.NORMAL, () -> {
            this.listPage--;
            this.refresh();
         }).active = this.listPage > 0;
         this.action(this.x + listW - navW, this.bottom - footerH, navW, footerH, "›", Style.NORMAL, () -> {
            this.listPage++;
            this.refresh();
         }).active = this.listPage + 1 < totalPages;
         this.action(
            this.x + navW + this.gap,
            this.bottom - footerH,
            listW - 2 * navW - 2 * this.gap,
            footerH,
            "Seite " + (this.listPage + 1) + "/" + totalPages,
            Style.NORMAL,
            this::onClose
         ).tip("Zurück zum Hauptmenü");
      } else {
         this.action(this.x, this.bottom - footerH, listW, footerH, "Zurück", Style.NORMAL, this::onClose);
      }

      // Build wrapped detail lines for right pane
      this.detailLines = buildDetailLines(this.activeEntry, this.detailW - 18);
      int visibleRows = Math.max(1, (this.detailH - 34) / 10);
      this.maxScroll = Math.max(0, this.detailLines.size() - visibleRows);
      this.detailScroll = Math.clamp(this.detailScroll, 0, this.maxScroll);

      // Right footer: scroll arrows (if long) + "Jetzt üben ▶" primary CTA!
      int scrollControlsW = this.maxScroll > 0 ? 48 + this.gap : 0;
      if (this.maxScroll > 0) {
         this.action(this.detailX, this.bottom - footerH, 22, footerH, "▲", Style.NORMAL, () -> {
            this.detailScroll = Math.max(0, this.detailScroll - 4);
            this.refresh();
         }).active = this.detailScroll > 0;
         this.action(this.detailX + 24, this.bottom - footerH, 22, footerH, "▼", Style.NORMAL, () -> {
            this.detailScroll = Math.min(this.maxScroll, this.detailScroll + 4);
            this.refresh();
         }).active = this.detailScroll < this.maxScroll;
      }

      int ctaX = this.detailX + scrollControlsW;
      int ctaW = this.right - ctaX;
      if (this.activeEntry != null && this.activeEntry.drill() != null && this.activeEntry.mode() != null) {
         TrainingSettings.Mode targetMode = this.activeEntry.mode();
         Drill targetDrill = this.activeEntry.drill();
         this.action(
            ctaX,
            this.bottom - footerH,
            ctaW,
            footerH,
            "Jetzt üben ▶  " + targetDrill.label(),
            Style.PRIMARY,
            () -> {
               DrillOptions sticky = TrainerClient.options(this.mode).carryCrossModeModifiersTo(DrillOptions.forDrill(targetDrill));
               TrainerClient.saveSetup(
                  TrainerClient.settings(targetMode).forDrill(targetDrill),
                  sticky
               );
               this.minecraft.gui.setScreen(new TrainingScreen());
            }
         ).tip("Wechselt direkt zu " + targetMode.label() + " · " + targetDrill.label());
      } else {
         this.action(ctaX, this.bottom - footerH, ctaW, footerH, "Fertig", Style.NORMAL, this::onClose);
      }
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.maxScroll > 0
         && mouseX >= this.detailX && mouseX <= this.detailX + this.detailW
         && mouseY >= this.detailY && mouseY <= this.detailY + this.detailH) {
         int next = Math.clamp(this.detailScroll + (scrollY < 0 ? 2 : -2), 0, this.maxScroll);
         if (next != this.detailScroll) {
            this.detailScroll = next;
            this.refresh();
            return true;
         }
      }
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   private List<StyledLine> buildDetailLines(KnowledgeGuide.Entry entry, int maxWidth) {
      if (entry == null || maxWidth < 40) {
         return List.of();
      }
      List<StyledLine> lines = new ArrayList<>();
      if (!entry.timing().isEmpty()) {
         addWrapped(lines, entry.timing(), maxWidth, EMBER);
         lines.add(StyledLine.BLANK);
      }
      addSection(lines, "Mechanik", entry.summary(), maxWidth);
      addSection(lines, "Praxis-Beispiel", entry.example(), maxWidth);
      if (!entry.steps().isEmpty()) {
         lines.add(new StyledLine(Component.literal("Ablauf (Schritt für Schritt)").withStyle(ChatFormatting.BOLD).getVisualOrderText(), TEXT));
         for (int i = 0; i < entry.steps().size(); i++) {
            addWrapped(lines, (i + 1) + ". " + entry.steps().get(i), maxWidth, MUTED);
         }
         lines.add(StyledLine.BLANK);
      }
      if (!entry.mistakes().isEmpty()) {
         addSection(lines, "Typische Fehler & Konter", entry.mistakes(), maxWidth);
      }
      return lines;
   }

   private void addSection(List<StyledLine> lines, String heading, String body, int maxWidth) {
      if (body == null || body.isEmpty()) return;
      lines.add(new StyledLine(Component.literal(heading).withStyle(ChatFormatting.BOLD).getVisualOrderText(), TEXT));
      addWrapped(lines, body, maxWidth, MUTED);
      lines.add(StyledLine.BLANK);
   }

   private void addWrapped(List<StyledLine> lines, String text, int maxWidth, int color) {
      for (FormattedCharSequence seq : this.font.split(Component.literal(text), maxWidth)) {
         lines.add(new StyledLine(seq, color));
      }
   }

   @Override
   void content(GuiGraphicsExtractor graphics) {
      fillRounded2(graphics, this.x, this.y, this.inner, 22, TRACK);
      outlineRounded2(graphics, this.x, this.y, this.inner, 22, LINE);
      fillRounded2(graphics, this.detailX, this.detailY, this.detailW, this.detailH, SURFACE);
      outlineRounded2(graphics, this.detailX, this.detailY, this.detailW, this.detailH, BORDER);
      graphics.fill(this.detailX + 1, this.detailY + 5, this.detailX + 3, this.detailY + this.detailH - 5, BORDER_SELECTED);

      if (this.activeEntry == null) {
         this.line(graphics, "Kein Eintrag verfügbar.", this.detailX + 10, this.detailY + 10, this.detailW - 20, MUTED);
         return;
      }

      int headerY = this.detailY + 7;
      graphics.item(KnowledgeGuide.iconStack(this.activeEntry.icon()), this.detailX + 8, headerY - 2);
      var titleComp = Component.literal(this.activeEntry.title()).withStyle(ChatFormatting.BOLD);
      graphics.text(this.font, titleComp, this.detailX + 28, headerY + 2, TEXT, false);
      graphics.fill(this.detailX + 8, this.detailY + 25, this.detailX + this.detailW - 8, this.detailY + 26, LINE);

      int textY = this.detailY + 30;
      int maxY = this.detailY + this.detailH - 9;
      for (int i = this.detailScroll; i < this.detailLines.size(); i++) {
         if (textY > maxY) break;
         StyledLine line = this.detailLines.get(i);
         if (line.text() != null) {
            graphics.text(this.font, line.text(), this.detailX + 9, textY, line.color(), false);
         }
         textY += line.text() == null ? 5 : 10;
      }

      if (this.maxScroll > 0) {
         int trackTop = this.detailY + 30;
         int trackH = Math.max(16, this.detailH - 36);
         drawScrollbar(graphics, this.detailX + this.detailW - 5, trackTop, trackH,
            this.detailScroll, this.maxScroll, this.detailLines.size() * 10, false);
      }
   }

   private record StyledLine(FormattedCharSequence text, int color) {
      static final StyledLine BLANK = new StyledLine(null, 0);
   }
}

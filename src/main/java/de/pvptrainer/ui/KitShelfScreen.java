package de.pvptrainer.ui;
import de.pvptrainer.kits.KitLibrary;
import de.pvptrainer.kits.KitLoadout;
import de.pvptrainer.kits.TrainingSettings;

import java.time.LocalDate;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class KitShelfScreen extends TrainerPanel {
   private final boolean own;
   private boolean bot;
   private int page, pages = 1, listTop;
   private boolean empty;
   private String message = "";

   KitShelfScreen(TrainingScreen parent, TrainingSettings.Mode mode, boolean bot) {
      this(parent, mode, bot, true);
   }

   KitShelfScreen(TrainingScreen parent, TrainingSettings.Mode mode, boolean bot, boolean own) {
      super(parent, own ? "Eigene Kits" : "Modus-Kits", mode);
      this.bot = bot;
      this.own = own;
   }

   @Override void build() {
      if (minecraft.level == null || minecraft.player == null) return;
      subtitle = own ? "Auswahl für " + mode.label() : mode.label();
      RegistryAccess registry = minecraft.level.registryAccess();
      for (int i = 0; i < 2; i++) {
         boolean targetBot = i == 1;
         action(col(i, 2), y, cw(i, 2), 21, targetBot ? "Bot-Kit" : "Dein Kit",
            targetBot == bot ? Style.SELECTED : Style.NORMAL, () -> {
               bot = targetBot;
               page = 0;
               message = "";
               refresh();
            });
      }
      listTop = y + 21 + gap;
      KitLoadout selected = KitLibrary.selected(registry, mode, bot);
      if (own) buildOwn(registry, selected);
      else buildMode(registry, selected);
   }

   private void buildOwn(RegistryAccess registry, KitLoadout selected) {
      List<KitLoadout> kits = KitLibrary.list(registry, mode).stream().filter(kit -> !kit.preset()).toList();
      int rowHeight = 26;
      int perPage = Math.max(1, (bottom - listTop - 43) / (rowHeight + gap));
      pages = Math.max(1, (kits.size() + perPage - 1) / perPage);
      page = Math.min(page, pages - 1);
      empty = kits.isEmpty();
      int editW = Math.min(85, inner / 5);
      int delW = Math.min(65, inner / 6);
      for (int i = 0; i < perPage && page * perPage + i < kits.size(); i++) {
         KitLoadout kit = kits.get(page * perPage + i);
         boolean inUse = selected.id().equals(kit.id());
         int yy = listTop + i * (rowHeight + gap);
         tile(x, yy, inner - editW - delW - 2 * gap, rowHeight, kit.name(),
            inUse ? "Ausgerüstet" : "Ausrüsten", new ItemStack(Items.WRITABLE_BOOK),
            inUse ? Style.SELECTED : Style.NORMAL, () -> run(() -> KitLibrary.select(mode, bot, kit.id()), kit.name() + " ausgerüstet."));
         action(right - delW - gap - editW, yy, editW, rowHeight, "Bearbeiten", Style.NORMAL,
            () -> edit(kit.id(), kit.name(), currentLayout(registry, kit), kit));
         action(right - delW, yy, delW, rowHeight, "Löschen", Style.NORMAL, () -> confirm(
            "Kit löschen?", kit.name(), "Die aktive Auswahl fällt auf das jeweilige Modus-Kit zurück.",
            "Löschen", () -> run(() -> KitLibrary.delete(kit.id()), "Kit gelöscht.")));
      }
      int actionsY = bottom - 40;
      action(col(0,3), actionsY, cw(0,3), 19, "Neues Kit", Style.PRIMARY, () -> edit(null, "Mein Kit", null, null));
      action(col(1,3), actionsY, cw(1,3), 19, "Aus Inventar", Style.NORMAL, () -> {
         KitLoadout captured = KitLibrary.capture(minecraft.player);
         var server = minecraft.getCurrentServer();
         String place = server == null ? "Eigene Welt" : server.ip;
         if (place.length() > 80) place = place.substring(0,80);
         KitLoadout kit = new KitLoadout(null, captured.name(), "Inventar · " + place + " · " + LocalDate.now(), captured.slots(), captured.effects());
         edit(null, "Mein Kit", kit, kit);
      });
      action(col(2,3), actionsY, cw(2,3), 19, "Duplizieren", Style.NORMAL,
         () -> copy(registry, selected)).active = kits.stream().anyMatch(kit -> kit.id().equals(selected.id()));
      if (pages > 1) {
         action(x, bottom - 18, 28, 18, "‹", Style.NORMAL, () -> { page--; message=""; refresh(); }).active = page > 0;
         action(right - 28, bottom - 18, 28, 18, "›", Style.NORMAL, () -> { page++; message=""; refresh(); }).active = page + 1 < pages;
      }
   }

   private void buildMode(RegistryAccess registry, KitLoadout selected) {
      KitLoadout kit = KitLibrary.modeKit(registry, mode, bot);
      boolean active = kit.id().equals(selected.id());
      boolean modified = !kit.preset();
      int yy = listTop;
      tile(x, yy, inner, 35, kit.name(), active ? "Ausgerüstet" : "Modus-Kit ausrüsten", icon(mode),
         active ? Style.SELECTED : Style.NORMAL, () -> run(() -> KitLibrary.select(mode,bot,kit.id()), "Modus-Kit ausgerüstet."));
      yy += 35 + gap;
      action(col(0,2), yy, cw(0,2), 22, "Modus-Kit bearbeiten", Style.NORMAL, () -> {
         String id = KitLibrary.modeKitId(mode, bot);
         edit(id, kit.name(), currentLayout(registry, kit), KitLibrary.builtin(registry,mode));
      });
      action(col(1,2), yy, cw(1,2), 22, "Als eigenes Kit kopieren", Style.NORMAL, () -> copy(registry,kit));
      yy += 22 + gap;
      action(col(0,2), yy, cw(0,2), 21, "Auf Standard zurücksetzen", Style.NORMAL, () -> confirm(
         "Modus-Kit zurücksetzen?", kit.name(), "Deine Änderungen an diesem Modus-Kit werden verworfen.",
         "Zurücksetzen", () -> run(() -> KitLibrary.resetModeKit(mode,bot),"Standardkit wiederhergestellt."))).active = modified;
      if (!bot) {
         action(col(1,2), yy, cw(1,2), 21, "Hotbar-Layout zurücksetzen", Style.NORMAL, () -> confirm(
            "Anordnung zurücksetzen?", selected.name(), "Gespeicherte Positionsänderungen für dieses Kit werden entfernt.",
            "Zurücksetzen", () -> run(() -> KitLibrary.resetLayout(mode,selected.id()),"Kit-Anordnung wiederhergestellt.")));
      }
   }

   private KitLoadout currentLayout(RegistryAccess registry, KitLoadout kit) {
      return bot ? kit : KitLibrary.personalized(registry,mode,kit.id());
   }

   private void copy(RegistryAccess registry, KitLoadout kit) {
      edit(null, kit.name() + " · Kopie", currentLayout(registry, kit), kit);
   }

   private void edit(String id, String name, KitLoadout current, KitLoadout source) {
      open(new KitEditorScreen(this,mode,bot,id,name,current,source));
   }

   private void confirm(String title, String name, String detail, String actionName, Runnable confirm) {
      open(new TrainerPanel(this,title,mode) {
         @Override int preferredWidth() { return 410; }
         @Override int preferredHeight() { return 133; }
         @Override void build() {
            action(col(0,2), bottom-21, cw(0,2),21,"Abbrechen",Style.NORMAL,this::onClose);
            action(col(1,2), bottom-21, cw(1,2),21,actionName,Style.PRIMARY,() -> {
               onClose();
               confirm.run();
            });
         }
         @Override void content(GuiGraphicsExtractor graphics) {
            line(graphics,name,x,y+2,inner,TEXT);
            graphics.textWithWordWrap(font, net.minecraft.network.chat.Component.literal(detail),x,y+19,inner,MUTED);
         }
      });
   }

   private void run(Runnable change, String success) {
      try { change.run(); message = success; }
      catch (RuntimeException ex) { message = ex.getMessage() == null ? "Speichern fehlgeschlagen." : ex.getMessage(); }
      refresh();
   }

   @Override void content(GuiGraphicsExtractor graphics) {
      if (empty && own) line(graphics,"Noch keine eigenen Kits",x,listTop + 8,inner,MUTED);
      String status = message.isEmpty() ? KitLibrary.notice() : message;
      if (own && pages > 1 && status.isBlank()) status = "Seite " + (page + 1) + " / " + pages;
      int inset = own && pages > 1 ? 33 : 0;
      if (!status.isBlank()) line(graphics,status,x+inset,bottom-13,inner-2*inset,MUTED);
   }
}

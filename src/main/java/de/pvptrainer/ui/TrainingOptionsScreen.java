package de.pvptrainer.ui;

import de.pvptrainer.TrainerClient;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.progress.HudPreferences;
import de.pvptrainer.progress.TrainingProgress;
import de.pvptrainer.progress.UpdateChecker;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/** Page-free Apple-style settings screen with 2 segmented scroll tabs and collapsible Advanced tuning. */
final class TrainingOptionsScreen extends TrainerPanel {
   private enum Tab {
      DRILL_BOT("Übung & Bot"),
      ARENA_HUD("Arena & Anzeige");

      final String label;
      Tab(String label) { this.label = label; }
   }

   private Tab tab = Tab.DRILL_BOT;
   private boolean showAdvanced;
   private int scrollOffset;
   private int contentHeight;
   private int maxScroll;
   private int tabBarY;
   private int tabBarH;
   private int viewTop;
   private int viewBottom;
   private int headerRightReserved = 26;

   private boolean draggingScrollbar;
   private int dragStartY;
   private int dragStartScroll;
   private ControlItem draggingSlider;

   private final List<SectionHeader> headers = new ArrayList<>();
   private final List<ControlItem> items = new ArrayList<>();
   private CollapsibleBar advancedBar;

   TrainingOptionsScreen(TrainingScreen parent, TrainingSettings.Mode mode) {
      super(parent, "Einstellungen", mode);
   }

   @Override
   int headerRightMargin() {
      return this.headerRightReserved;
   }

   @Override
   void build() {
      DrillOptions options = TrainerClient.options(this.mode);
      Drill drill = options.drill();
      this.subtitle = this.mode.label() + " · " + drill.label();

      // Header Reset button before ‹ back arrow
      int resetW = 72;
      int resetX = this.right - 24 - resetW;
      this.iconChip(resetX, this.top + 6, resetW, 17, UiIcons.RESET, "Standard", false, EMBER, () -> {
         TrainerClient.saveSetup(
            TrainingSettings.defaults(this.mode).forDrill(drill),
            DrillOptions.forDrill(drill)
         );
         this.refresh();
      }, null, null).tip("Setzt alle Optionen dieser Übung auf Standard zurück");
      this.headerRightReserved = this.right - resetX + 8;

      // 2-Tab Apple Segmented Control
      this.tabBarY = this.y;
      this.tabBarH = 22;
      Tab[] tabs = Tab.values();
      int segX = this.x + 2;
      int segW = this.inner - 4;
      for (int i = 0; i < tabs.length; i++) {
         Tab t = tabs[i];
         this.action(gridCol(segX, segW, 2, i, tabs.length), this.tabBarY + 2, gridW(segX, segW, 2, i, tabs.length), this.tabBarH - 4,
            t.label, t == this.tab ? Style.SEGMENT_SELECTED : Style.SEGMENT, () -> {
               if (this.tab != t) {
                  this.tab = t;
                  this.scrollOffset = 0;
                  this.refresh();
               }
            });
      }

      int footerH = 21;
      this.viewTop = this.tabBarY + this.tabBarH + 8;
      this.viewBottom = this.bottom - footerH - 8;

      this.action(this.x, this.bottom - footerH, this.inner, footerH, "Fertig", Style.NORMAL, this::onClose);

      this.buildScrollContent();
   }

   private void buildScrollContent() {
      this.headers.clear();
      this.items.clear();
      this.advancedBar = null;

      TrainingSettings settings = TrainerClient.settings(this.mode);
      DrillOptions options = TrainerClient.options(this.mode);
      Drill drill = options.drill();

      // First pass with full inner width to see if scrollbar is needed, then re-lay out cleanly if scrollable
      this.populateLayout(settings, options, drill, this.inner);
      int viewH = Math.max(40, this.viewBottom - this.viewTop);
      this.maxScroll = Math.max(0, this.contentHeight - viewH);
      if (this.maxScroll > 0) {
         this.populateLayout(settings, options, drill, this.inner - 7);
         this.maxScroll = Math.max(0, this.contentHeight - viewH);
      }
      this.scrollOffset = Math.clamp(this.scrollOffset, 0, this.maxScroll);
   }

   private void populateLayout(TrainingSettings settings, DrillOptions options, Drill drill, int availW) {
      this.headers.clear();
      this.items.clear();
      this.advancedBar = null;

      int cy = 0;
      int rowH = 24;
      int sliderH = 26;
      int rowGap = 4;
      int sectionGap = 10;

      if (this.tab == Tab.DRILL_BOT) {
         // --- Section 1: Übungs-Setup (drill-specific controls) ---
         List<ControlSpec> setupSpecs = new ArrayList<>();
         boolean hasSlamScenes = drill.slamOptions();

         if (this.mode == TrainingSettings.Mode.MACE) {
            if (drill != Drill.BODY_PEARL) {
               setupSpecs.add(toggleField(options, DrillOptions.Field.PEARL_CATCH_ONLY));
            }
            if (!drill.centeredMaceDrill() && drill != Drill.BODY_PEARL) {
               setupSpecs.add(toggle("Arenamitte fixieren", settings.maceCenterLocked(),
                  "Hält den Bot bei Mace-Übungen in der Arenamitte.",
                  () -> TrainerClient.save(TrainerClient.settings(this.mode).withMaceCenterLocked(!settings.maceCenterLocked()))));
            }
         }

         for (DrillOptions.Field field : setupFieldsFor(drill)) {
            if (field.visible(options, this.mode) && usefulForExercise(field, drill)) {
               setupSpecs.add(fieldSpec(options, field));
            }
         }

         if (hasSlamScenes || !setupSpecs.isEmpty()) {
            String rightMeta = hasSlamScenes
               ? "Du " + options.playerElevation() + " Bl. · Bot " + options.botElevation() + " Bl."
               : "";
            this.headers.add(new SectionHeader("ÜBUNGS-SETUP", rightMeta, cy));
            cy += 12;

            if (hasSlamScenes) {
               DrillOptions.SlamScene[] scenes = DrillOptions.SlamScene.values();
               for (int i = 0; i < scenes.length; i++) {
                  DrillOptions.SlamScene scene = scenes[i];
                  int col = i % 2;
                  int row = i / 2;
                  int bx = gridCol(this.x, availW, 4, col, 2);
                  int bw = gridW(this.x, availW, 4, col, 2);
                  int by = cy + row * (20 + rowGap);
                  String label = drill == Drill.WIND_STUN_SLAM ? switch (scene) {
                     case FREE -> "Standard";
                     case GROUND_LOCK -> "Bodenstart";
                     case AIR_LOCK -> "Erhöhte Basis";
                     case PAIRED_FALL -> "Fall aus Zielhöhe";
                  } : options.selectSlamScene(scene).value(DrillOptions.Field.SLAM_SCENE);
                  this.items.add(ControlItem.choiceCard(bx, by, bw, 20, label, scene == options.slamScene(),
                     sceneHint(drill, settings, scene), () -> {
                        TrainerClient.saveOptions(this.mode, TrainerClient.options(this.mode).selectSlamScene(scene));
                        this.refresh();
                     }));
               }
               cy += 2 * (20 + rowGap) + 2;
            }

            cy = this.addGridSpecs(setupSpecs, cy, availW, rowH, sliderH, rowGap);
            cy += sectionGap;
         }

         // --- Section 2: Bot & Kampf (core 4–6 settings, NO Difficulty!) ---
         List<ControlSpec> botSpecs = new ArrayList<>();
         boolean lockedScene = options.slamScene() != DrillOptions.SlamScene.FREE
            || drill == Drill.LOW_WEB_SMASH || drill == Drill.PERFECT_WIND_CHARGE;

         if (drill.totemDefense()) {
            boolean strafe = settings.movement() == TrainingSettings.Movement.STRAFE;
            botSpecs.add(choice("Bewegung", strafe ? "Seitwärts" : "Herantreten",
               "Bewegungsmuster des Bots während der Runde.",
               () -> TrainerClient.save(TrainerClient.settings(this.mode).withMovement(
                  strafe ? TrainingSettings.Movement.FOLLOW : TrainingSettings.Movement.STRAFE))));
         } else if (!lockedScene && !(drill == Drill.WIND_STUN_SLAM ? options.windCenter() : settings.maceCenterLocked())) {
            botSpecs.add(choice("Bewegung", settings.movement().label(),
               "Bewegungsmuster des Bots (Stillstehen, Verfolgen oder Seitwärts).",
               () -> TrainerClient.save(TrainerClient.settings(this.mode).withMovement(
                  nextEnum(TrainingSettings.Movement.values(), settings.movement())))));
         }

         botSpecs.add(slider("Bot-Leben", 6, Math.max(0, Math.min(5, settings.maxHealth() / 10 - 1)),
            step -> (step + 1) * 5 + " Herzen",
            "Maximale Lebenspunkte des Bots.",
            step -> TrainerClient.save(TrainerClient.settings(this.mode).withMaxHealth((step + 1) * 10))));

         if (!lockedScene && drill != Drill.PASSIVE && !drill.needsSwordAttack() && !drill.totemDefense()) {
            botSpecs.add(toggle("Gegenangriffe", settings.counterAttacks(),
               "Erlaubt dem Bot aktive Gegenangriffe während der Übung.",
               () -> TrainerClient.save(TrainerClient.settings(this.mode).withCounterAttacks(!settings.counterAttacks()))));
         }

         botSpecs.add(toggle("Auto-Respawn", settings.autoRespawn(),
            "Startet den Bot nach einem Sieg automatisch wieder neu.",
            () -> TrainerClient.save(TrainerClient.settings(this.mode).withAutoRespawn(!settings.autoRespawn()))));

         if (DrillOptions.Field.TOTEMS.visible(options, this.mode)) {
            botSpecs.add(fieldSpec(options, DrillOptions.Field.TOTEMS));
         }
         if (DrillOptions.Field.SHIELD.visible(options, this.mode)) {
            botSpecs.add(toggleField(options, DrillOptions.Field.SHIELD));
         }

         this.headers.add(new SectionHeader("BOT & KAMPF", "", cy));
         cy += 12;
         cy = this.addGridSpecs(botSpecs, cy, availW, rowH, sliderH, rowGap);
         cy += sectionGap;

         // --- Section 3: Collapsible "Erweitert" (Profi fine-tuning) ---
         List<ControlSpec> advSpecs = new ArrayList<>();
         int modifiedAdvanced = 0;
         for (DrillOptions.Field field : ADVANCED_FIELDS) {
            if (field.visible(options, this.mode) && usefulForExercise(field, drill)) {
               advSpecs.add(fieldSpec(options, field));
               if (options.overrides().containsKey(field)) {
                  modifiedAdvanced++;
               }
            }
         }

         if (!advSpecs.isEmpty()) {
            String title = modifiedAdvanced > 0
               ? "Erweitert  ·  " + modifiedAdvanced + " angepasst"
               : "Erweitert  (Abstand, Winkel, Blick, Schild-Timing, Reichweite)";
            this.advancedBar = new CollapsibleBar(this.x, cy, availW, 22, title, modifiedAdvanced > 0);
            cy += 22 + rowGap;
            if (this.showAdvanced) {
               cy = this.addGridSpecs(advSpecs, cy, availW, rowH, sliderH, rowGap);
            }
         }
      } else {
         // === Tab 2: Arena & Anzeige ===
         // Section 1: Arena & Tempo (no Arena-Difficulty!)
         List<ControlSpec> arenaSpecs = new ArrayList<>();
         arenaSpecs.add(fieldSpec(options, DrillOptions.Field.TICK_RATE));
         arenaSpecs.add(fieldSpec(options, DrillOptions.Field.ARENA_SIZE));
         arenaSpecs.add(fieldSpec(options, DrillOptions.Field.ZERO_DAMAGE));
         arenaSpecs.add(fieldSpec(options, DrillOptions.Field.PLAYER_PING));
         arenaSpecs.add(fieldSpec(options, DrillOptions.Field.BOT_PING));

         this.headers.add(new SectionHeader("ARENA & TEMPO", "Arena-Schwierigkeit: immer Schwer", cy));
         cy += 12;
         cy = this.addGridSpecs(arenaSpecs, cy, availW, rowH, sliderH, rowGap);
         cy += sectionGap;

         // Section 2: Kampf- & Verbrauchsregeln
         List<ControlSpec> ruleSpecs = new ArrayList<>();
         ruleSpecs.add(toggle("Item-Verbrauch", options.itemConsumption(this.mode),
            tooltipFor(DrillOptions.Field.ITEM_CONSUMPTION, this.mode),
            () -> TrainerClient.saveOptions(this.mode, TrainerClient.options(this.mode).next(DrillOptions.Field.ITEM_CONSUMPTION, this.mode))));
         ruleSpecs.add(toggle("Rüstungsverschleiß", options.armorWear(),
            tooltipFor(DrillOptions.Field.ARMOR_WEAR, this.mode),
            () -> TrainerClient.saveOptions(this.mode, TrainerClient.options(this.mode).withIndex(DrillOptions.Field.ARMOR_WEAR, options.armorWear() ? 1 : 0))));
         ruleSpecs.add(toggle("Fallschaden", options.fallDamage(),
            tooltipFor(DrillOptions.Field.FALL_DAMAGE, this.mode),
            () -> TrainerClient.saveOptions(this.mode, TrainerClient.options(this.mode).withIndex(DrillOptions.Field.FALL_DAMAGE, options.fallDamage() ? 1 : 0))));

         this.headers.add(new SectionHeader("REGELN & VERBRAUCH", "", cy));
         cy += 12;
         cy = this.addGridSpecs(ruleSpecs, cy, availW, rowH, sliderH, rowGap);
         cy += sectionGap;

         // Section 3: Anzeige & HUD (global across all modes)
         List<ControlSpec> hudSpecs = new ArrayList<>();
         hudSpecs.add(toggle("Übungshinweise", HudPreferences.hints(),
            "Übungstipps und Einblendungen oben rechts für alle Modi und Welten.",
            () -> {
               HudPreferences.toggleHints();
               if (!HudPreferences.hints()) TrainingProgress.hideResultToast();
            }));
         hudSpecs.add(toggle("Bot-Lebensanzeige", HudPreferences.botHealth(),
            "Zeigt Lebenspunkte (und Goldherzen nach einem Pop) über dem Kopf des Bots.",
            HudPreferences::toggleBotHealth));
         hudSpecs.add(toggle("Reach-Indikator", HudPreferences.reachIndicator(),
            "Zeigt eine feine Markierung am Fadenkreuz, sobald der Bot in echter 3-Block-Reichweite ist.",
            HudPreferences::toggleReachIndicator));
         hudSpecs.add(toggle("Update-Hinweise", HudPreferences.updateCheck(),
            "Prüft beim Spielstart einmalig auf neue MacherBot-Releases (ohne automatischen Download).",
            () -> {
               HudPreferences.toggleUpdateCheck();
               if (HudPreferences.updateCheck()) UpdateChecker.checkAsync();
               else UpdateChecker.clear();
            }));

         this.headers.add(new SectionHeader("ANZEIGE & HUD", "Gilt global für alle Modi", cy));
         cy += 12;
         cy = this.addGridSpecs(hudSpecs, cy, availW, rowH, sliderH, rowGap);
      }

      this.contentHeight = cy + 4;
   }

   private int addGridSpecs(List<ControlSpec> specs, int startY, int availW, int rowH, int sliderH, int gap) {
      int cy = startY;
      for (int i = 0; i < specs.size(); i += 2) {
         ControlSpec left = specs.get(i);
         ControlSpec right = i + 1 < specs.size() ? specs.get(i + 1) : null;
         int h = (left.kind == Kind.SLIDER || (right != null && right.kind == Kind.SLIDER)) ? sliderH : rowH;
         this.items.add(left.instantiate(gridCol(this.x, availW, 4, 0, 2), cy, gridW(this.x, availW, 4, 0, 2), h));
         if (right != null) {
            this.items.add(right.instantiate(gridCol(this.x, availW, 4, 1, 2), cy, gridW(this.x, availW, 4, 1, 2), h));
         }
         cy += h + gap;
      }
      return cy;
   }

   private ControlSpec fieldSpec(DrillOptions options, DrillOptions.Field field) {
      String label = (this.mode == TrainingSettings.Mode.MACE || this.mode == TrainingSettings.Mode.HUGOSMP)
         && field == DrillOptions.Field.REPLACE ? "Schild bei Verschleiß" : field.label();
      String tip = tooltipFor(field, this.mode);
      if (isToggleField(field)) {
         return toggleField(options, field);
      }
      if (isNumeric(field)) {
         return slider(label, field.size(), options.sliderStep(field),
            step -> TrainerClient.options(this.mode).withIndex(field, field.indexForSliderStep(step)).value(field),
            tip,
            step -> TrainerClient.saveOptions(this.mode, TrainerClient.options(this.mode).withIndex(field, field.indexForSliderStep(step))));
      }
      return choice(label, options.value(field, this.mode), tip, () ->
         TrainerClient.saveOptions(this.mode, TrainerClient.options(this.mode).next(field, this.mode)));
   }

   private ControlSpec toggleField(DrillOptions options, DrillOptions.Field field) {
      boolean on = switch (field) {
         case SHIELD -> options.holdShield();
         case WIND_CENTER -> options.windCenter();
         case PEARL_CATCH_ONLY -> options.pearlCatchOnly();
         case BOT_JUMP -> options.botJump();
         case BOT_AUTO_HIT -> options.botAutoHit();
         case CLEAN_HEAL -> options.cleanHeal();
         case BODY_PEARL_SMASH -> options.bodyPearlSmash();
         case CATCH_NEEDS_SMASH -> options.catchNeedsSmash();
         default -> false;
      };
      return toggle(field.label(), on, tooltipFor(field, this.mode), () ->
         TrainerClient.saveOptions(this.mode, TrainerClient.options(this.mode).next(field, this.mode)));
   }

   private ControlSpec toggle(String label, boolean on, String tip, Runnable action) {
      return new ControlSpec(Kind.TOGGLE, label, "", on, tip, 0, 0, null, null, () -> {
         action.run();
         this.refresh();
      });
   }

   private ControlSpec choice(String label, String value, String tip, Runnable action) {
      return new ControlSpec(Kind.CHOICE, label, value, false, tip, 0, 0, null, null, () -> {
         action.run();
         this.refresh();
      });
   }

   private ControlSpec slider(String label, int steps, int initial, IntFunction<String> format, String tip, IntConsumer apply) {
      return new ControlSpec(Kind.SLIDER, label, "", false, tip, steps, initial, format, apply, null);
   }

   @Override
   void content(GuiGraphicsExtractor graphics) {
      // Recessed Segmented Control track for the 2 tabs
      fillRounded2(graphics, this.x, this.tabBarY, this.inner, this.tabBarH, TRACK);
      outlineRounded2(graphics, this.x, this.tabBarY, this.inner, this.tabBarH, LINE);

      double mx = this.minecraft.mouseHandler.xpos() * this.width / Math.max(1, this.minecraft.getWindow().getScreenWidth());
      double my = this.minecraft.mouseHandler.ypos() * this.height / Math.max(1, this.minecraft.getWindow().getScreenHeight());
      boolean inView = mx >= this.x && mx <= this.right && my >= this.viewTop && my <= this.viewBottom;

      graphics.enableScissor(this.x - 1, this.viewTop, this.right + 1, this.viewBottom);

      for (SectionHeader header : this.headers) {
         int hy = this.viewTop + header.y - this.scrollOffset;
         if (hy + 10 < this.viewTop || hy > this.viewBottom) continue;
         graphics.text(this.font, header.title, this.x + 1, hy, MUTED, false);
         if (!header.rightText.isEmpty()) {
            int rw = this.font.width(header.rightText);
            int rightEdge = this.maxScroll > 0 ? this.right - 7 : this.right;
            graphics.text(this.font, header.rightText, rightEdge - rw, hy, OFF, false);
         }
      }

      if (this.advancedBar != null) {
         int by = this.viewTop + this.advancedBar.y - this.scrollOffset;
         if (by + this.advancedBar.h >= this.viewTop && by <= this.viewBottom) {
            boolean hovered = inView && mx >= this.advancedBar.x && mx < this.advancedBar.x + this.advancedBar.w
               && my >= by && my < by + this.advancedBar.h;
            fillRounded(graphics, this.advancedBar.x, by, this.advancedBar.w, this.advancedBar.h, hovered ? HOVER : SURFACE);
            outlineRounded(graphics, this.advancedBar.x, by, this.advancedBar.w, this.advancedBar.h,
               this.advancedBar.hasModified ? BORDER_SELECTED : (hovered ? BORDER_HOVER : LINE));
            UiIcons.draw(graphics, this.showAdvanced ? UiIcons.CHEVRON_DOWN : UiIcons.CHEVRON_RIGHT,
               this.advancedBar.x + 6, by + (this.advancedBar.h - 12) / 2,
               this.advancedBar.hasModified ? TEXT : MUTED);
            this.line(graphics, this.advancedBar.title, this.advancedBar.x + 22, by + (this.advancedBar.h - 8) / 2,
               this.advancedBar.w - 28, this.advancedBar.hasModified ? TEXT : MUTED);
         }
      }

      for (ControlItem item : this.items) {
         int iy = this.viewTop + item.y - this.scrollOffset;
         if (iy + item.h < this.viewTop || iy > this.viewBottom) continue;
         boolean hovered = (this.draggingSlider == item)
            || (this.draggingSlider == null && inView && mx >= item.x && mx < item.x + item.w && my >= iy && my < iy + item.h);

         switch (item.kind) {
            case TOGGLE -> {
               fillRounded(graphics, item.x, iy, item.w, item.h, hovered ? HOVER : CELL);
               outlineRounded(graphics, item.x, iy, item.w, item.h, hovered ? BORDER_HOVER : LINE);
               this.line(graphics, item.label, item.x + 8, iy + (item.h - 8) / 2, item.w - 40, TEXT);
               drawToggleSwitch(graphics, item.x + item.w - 29, iy + (item.h - 12) / 2, item.checked, hovered);
            }
            case CHOICE -> {
               fillRounded(graphics, item.x, iy, item.w, item.h, hovered ? HOVER : CELL);
               outlineRounded(graphics, item.x, iy, item.w, item.h, hovered ? BORDER_HOVER : LINE);
               String valText = item.value + " ▸";
               int valW = Math.min(item.w / 2, this.font.width(valText));
               this.line(graphics, item.label, item.x + 8, iy + (item.h - 8) / 2, item.w - valW - 18, TEXT);
               this.line(graphics, valText, item.x + item.w - valW - 7, iy + (item.h - 8) / 2, valW + 2, hovered ? TEXT : MUTED);
            }
            case CARD -> {
               fillRounded(graphics, item.x, iy, item.w, item.h, item.checked ? SELECTED : (hovered ? HOVER : CELL));
               outlineRounded(graphics, item.x, iy, item.w, item.h, item.checked ? BORDER_SELECTED : (hovered ? BORDER_HOVER : LINE));
               String fitted = this.fit(item.label, item.w - 10);
               graphics.text(this.font, fitted, item.x + (item.w - this.font.width(fitted)) / 2, iy + (item.h - 8) / 2, TEXT, false);
            }
            case SLIDER -> {
               double progress = item.steps <= 1 ? 0.0 : (double)item.step / (item.steps - 1);
               SettingSlider.drawAppleSlider(graphics, item.x, iy, item.w, item.h,
                  item.label, item.format.apply(item.step), progress, true, hovered);
            }
         }
      }

      graphics.disableScissor();

      if (this.maxScroll > 0) {
         boolean hoverBar = this.draggingScrollbar || (mx >= this.right - 7 && mx <= this.right + 1 && my >= this.viewTop && my <= this.viewBottom);
         drawScrollbar(graphics, this.right - 3, this.viewTop, this.viewBottom - this.viewTop,
            this.scrollOffset, this.maxScroll, this.contentHeight, hoverBar);
      }
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      super.extractRenderState(graphics, mouseX, mouseY, delta);
      if (this.draggingSlider == null && mouseX >= this.x && mouseX <= this.right && mouseY >= this.viewTop && mouseY <= this.viewBottom) {
         for (ControlItem item : this.items) {
            int iy = this.viewTop + item.y - this.scrollOffset;
            if (mouseX >= item.x && mouseX < item.x + item.w && mouseY >= iy && mouseY < iy + item.h) {
               if (item.tooltip != null && !item.tooltip.isEmpty()) {
                  graphics.setTooltipForNextFrame(Component.literal(item.tooltip), mouseX, mouseY);
               }
               break;
            }
         }
      }
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
      double mx = event.x();
      double my = event.y();
      if (event.button() == 0 && mx >= this.x && mx <= this.right + 1 && my >= this.viewTop && my <= this.viewBottom) {
         // Scrollbar grab
         if (this.maxScroll > 0 && mx >= this.right - 7) {
            this.draggingScrollbar = true;
            this.dragStartY = (int)my;
            this.dragStartScroll = this.scrollOffset;
            return true;
         }

         // Collapsible Advanced bar
         if (this.advancedBar != null) {
            int by = this.viewTop + this.advancedBar.y - this.scrollOffset;
            if (mx >= this.advancedBar.x && mx < this.advancedBar.x + this.advancedBar.w && my >= by && my < by + this.advancedBar.h) {
               this.playClick();
               this.showAdvanced = !this.showAdvanced;
               this.buildScrollContent();
               return true;
            }
         }

         // Control items
         for (ControlItem item : this.items) {
            int iy = this.viewTop + item.y - this.scrollOffset;
            if (mx >= item.x && mx < item.x + item.w && my >= iy && my < iy + item.h) {
               if (item.kind == Kind.SLIDER) {
                  this.draggingSlider = item;
                  this.updateSliderFromMouse(item, mx);
               } else {
                  this.playClick();
                  item.onClick.run();
               }
               return true;
            }
         }
      }
      return super.mouseClicked(event, twice);
   }

   @Override
   public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
      if (this.draggingSlider != null) {
         this.updateSliderFromMouse(this.draggingSlider, event.x());
         return true;
      }
      if (this.draggingScrollbar && this.maxScroll > 0) {
         int viewH = Math.max(16, this.viewBottom - this.viewTop);
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
      if (this.draggingSlider != null) {
         this.draggingSlider = null;
         // Re-read any dependent visibility (e.g. Target/Start height readout in section header)
         this.buildScrollContent();
         return true;
      }
      if (this.draggingScrollbar) {
         this.draggingScrollbar = false;
         return true;
      }
      return super.mouseReleased(event);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.maxScroll > 0 && mouseX >= this.x && mouseX <= this.right && mouseY >= this.viewTop && mouseY <= this.viewBottom) {
         int next = Math.clamp(this.scrollOffset - (int)Math.round(scrollY * 24.0), 0, this.maxScroll);
         if (next != this.scrollOffset) {
            this.scrollOffset = next;
            return true;
         }
      }
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   private void updateSliderFromMouse(ControlItem item, double mouseX) {
      int trackLeft = item.x + 7;
      int trackW = Math.max(12, item.w - 14);
      double norm = Math.clamp((mouseX - trackLeft) / trackW, 0.0, 1.0);
      int nextStep = (int)Math.round(norm * Math.max(1, item.steps - 1));
      if (nextStep != item.step) {
         item.step = nextStep;
         item.onSlide.accept(nextStep);
      }
   }

   private void playClick() {
      this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
   }

   // --- Field categorization & metadata ---

   private static final List<DrillOptions.Field> ADVANCED_FIELDS = List.of(
      DrillOptions.Field.DISTANCE,
      DrillOptions.Field.ANGLE,
      DrillOptions.Field.SIDE_OFFSET,
      DrillOptions.Field.START_HEALTH,
      DrillOptions.Field.REACTION,
      DrillOptions.Field.VARIABLE,
      DrillOptions.Field.WINDOW,
      DrillOptions.Field.GAZE,
      DrillOptions.Field.TURN_RATE,
      DrillOptions.Field.BOT_REACH,
      DrillOptions.Field.REFILL,
      DrillOptions.Field.RESET_POP,
      DrillOptions.Field.REPLACE,
      DrillOptions.Field.DURABILITY,
      DrillOptions.Field.REBLOCK,
      DrillOptions.Field.SHIELD_BREAK_TICKS
   );

   private static List<DrillOptions.Field> setupFieldsFor(Drill drill) {
      return switch (drill) {
         case HIT_CRYSTAL, CORNER_HIT_CRYSTAL, D_TAP, TRIPLE_TAP, SHIELD_TRIPLE_TAP, SELF_WEB_CRYSTAL, TAP_ANCHOR, MACE_COUNTER_DTAP, CORNER_WEB_DTAP -> List.of(
            DrillOptions.Field.BASE, DrillOptions.Field.CRYSTAL_AIR, DrillOptions.Field.TERRAIN, DrillOptions.Field.PING_PONG
         );
         case DOUBLE_ANCHOR -> List.of(DrillOptions.Field.TERRAIN);
         case LEDGE_DASH -> List.of(DrillOptions.Field.LEDGE, DrillOptions.Field.BASE);
         case SAFE_ANCHOR, HEAL -> List.of(DrillOptions.Field.COVER, DrillOptions.Field.CLEAN_HEAL);
         case WEB_DUNK -> List.of(DrillOptions.Field.DUNK_TYPE);
         case STUN_WEB -> List.of(DrillOptions.Field.RANDOM_BLOCK_POS, DrillOptions.Field.BOT_JUMP, DrillOptions.Field.BOT_AUTO_HIT);
         case RETOTEM, DOUBLE_HAND -> List.of(DrillOptions.Field.BURST_PATTERN);
         case SMASH, WIND_CHAIN, BACKSTAB, PEARL_CATCH, ELYTRA_SMASH, ELY_STUN_SLAM, LOW_WEB_SMASH -> List.of(DrillOptions.Field.START_HEIGHT);
         case BODY_PEARL -> List.of(DrillOptions.Field.TARGET_HEIGHT, DrillOptions.Field.BODY_PEARL_SMASH);
         case PEARL_CATCH_GUIDE -> List.of(DrillOptions.Field.CATCH_NEEDS_SMASH, DrillOptions.Field.START_HEIGHT);
         case STUN_SLAM -> List.of(DrillOptions.Field.SLAM_POSITION, DrillOptions.Field.TARGET_HEIGHT, DrillOptions.Field.HEIGHT_OFFSET, DrillOptions.Field.START_HEIGHT);
         case WIND_STUN_SLAM -> List.of(DrillOptions.Field.WIND_CENTER, DrillOptions.Field.WIND_FLIGHT, DrillOptions.Field.SLAM_POSITION, DrillOptions.Field.TARGET_HEIGHT, DrillOptions.Field.HEIGHT_OFFSET, DrillOptions.Field.START_HEIGHT);
         case SPACING, S_TAP -> List.of(DrillOptions.Field.COMBO_LENGTH);
         case DUEL -> List.of(DrillOptions.Field.BASE);
         default -> List.of();
      };
   }

   private static boolean isToggleField(DrillOptions.Field field) {
      return switch (field) {
         case SHIELD, WIND_CENTER, PEARL_CATCH_ONLY, BOT_JUMP, BOT_AUTO_HIT, CLEAN_HEAL, BODY_PEARL_SMASH, CATCH_NEEDS_SMASH -> true;
         default -> false;
      };
   }

   private static boolean isNumeric(DrillOptions.Field field) {
      return switch (field) {
         case TURN_RATE, REFILL, DURABILITY, DISTANCE, ANGLE, REACTION, WINDOW, LEDGE, START_HEIGHT, SIDE_OFFSET,
              START_HEALTH, TARGET_HEIGHT, COMBO_LENGTH, HEIGHT_OFFSET, SHIELD_BREAK_TICKS, BOT_REACH, TICK_RATE,
              PLAYER_PING, BOT_PING -> true;
         default -> false;
      };
   }

   private static boolean usefulForExercise(DrillOptions.Field field, Drill drill) {
      return switch (field) {
         case VARIABLE -> drill.needsSwordAttack() || drill.totemDefense() || drill == Drill.WEB_DRAIN || drill == Drill.RE_LAVA;
         case WINDOW -> switch (drill) {
            case HIT_CRYSTAL, CORNER_HIT_CRYSTAL, D_TAP, TRIPLE_TAP, SHIELD_TRIPLE_TAP, SELF_WEB_CRYSTAL, DOUBLE_ANCHOR,
                 MACE_COUNTER_DTAP, CORNER_WEB_DTAP, TAP_ANCHOR, SMASH, WIND_CHAIN, PEARL_CATCH, PEARL_CATCH_GUIDE, ELYTRA_SMASH,
                 HIT_SELECT, PUNISH_CRIT, MOMENTUM_BREAK, WEB_DUNK, PCRIT_BREAK -> true;
            default -> false;
         };
         case SLAM_SCENE, ARENA_DIFFICULTY -> false;
         case BURST_PATTERN -> drill.totemDefense();
         default -> true;
      };
   }

   private static String sceneHint(Drill drill, TrainingSettings settings, DrillOptions.SlamScene scene) {
      if (drill == Drill.WIND_STUN_SLAM) {
         return switch (scene) {
            case FREE, GROUND_LOCK -> "Der Bot springt vom Arenaboden. Du bestimmst deinen Fallstart.";
            case AIR_LOCK -> "Der Bot springt von einer erhöhten Plattform. Stelle deine Höhe relativ zur Basis ein.";
            case PAIRED_FALL -> "Der Bot fällt sofort aus der Zielhöhe und springt vom Boden weiter. Dein Fall beginnt bei Bewegung.";
         };
      }
      return switch (scene) {
         case FREE -> settings.maceCenterLocked()
            ? "Der Bot bleibt durch die gewählte Fixierung in der Arenamitte."
            : "Bewegung und Rückstoß des Bots gelten wie im Duell.";
         case GROUND_LOCK -> "Das Bodenziel bleibt am Platz. Bewege dich, um den Fall zu starten.";
         case AIR_LOCK -> "Das Ziel schwebt. Wähle Startposition und Zielhöhe. Bewegen oder klicken startet den Versuch.";
         case PAIRED_FALL -> settings.maceCenterLocked()
            ? "Beim gemeinsamen Fall sinkt der Bot senkrecht über der Arenamitte."
            : "Ihr schwebt in eurer Startposition. Bewege dich für den gemeinsamen Fall.";
      };
   }

   private static String tooltipFor(DrillOptions.Field field, TrainingSettings.Mode mode) {
      return switch (field) {
         case SHIELD_BREAK_TICKS -> "Zeit bis zur nächsten wirksamen Deckung nach Axt-Stun oder Schildersatz. 20 Ticks = 1 Sekunde.";
         case PLAYER_PING, BOT_PING -> "Zusätzliche Hin- und Rücklaufzeit. 0 ms ist das normale lokale Spiel.";
         case TICK_RATE -> "Gilt vom Start bis zum Ende des Versuchs für die gesamte lokale Welt. 20 TPS = normales Tempo, 10 TPS = halbes Tempo.";
         case BOT_REACH -> "Obergrenze für Nahkampftreffer des Bots, gemessen von den Augen bis zu deiner Trefferbox.";
         case RANDOM_BLOCK_POS -> "Startet den Bot beim Stun-Web zufällig an Blockkanten oder Ecken, um das Lesen der Flugbahn zu üben.";
         case BOT_AUTO_HIT -> "Bot kontert nach Schildbruch sofort mit einem Auto-Hit – ideal zum Üben des Web Stun Dunks.";
         case CLEAN_HEAL -> "Erzwingt 0 Gegentreffer während des Essens (wie im HT1-UHC-Coaching).";
         case DUNK_TYPE -> "Wählt das Ziel-Secondary nach dem Crit: Cobweb, Lava oder Cobweb + Lava.";
         case ZERO_DAMAGE -> "Kein LP-Verlust für dich oder beide Seiten. Rückstoß, Schildblock, Axt-Stun, Cobwebs und Effekte bleiben aktiv.";
         case PEARL_CATCH_ONLY -> "Nur eine in der Luft mit deiner eigenen Windladung gefangene Perle teleportiert dich.";
         case BODY_PEARL_SMASH -> "Verlangt nach der erfolgreichen Body Pearl zusätzlich einen Mace-Smash vor der Landung.";
         case CATCH_NEEDS_SMASH -> "Verlangt nach dem Pearl Catch zusätzlich einen Mace-Smash vor der Landung.";
         case TOTEMS -> "Unbegrenzt schützt das Übungsziel auch ohne Kit-Totem. Im freien Duell gilt der Kit-Vorrat als Voraussetzung.";
         case WIND_FLIGHT -> "Kombiniert startet der Bot mit einer Windladung vom Boden und fängt im selben Flug seine Enderperle.";
         case ITEM_CONSUMPTION -> (mode == TrainingSettings.Mode.MACE || mode == TrainingSettings.Mode.CRYSTAL || mode == TrainingSettings.Mode.HUGOSMP)
            ? "Gilt für beide Kits. Totems werden bei jedem Pop verbraucht. Auto-Totem regelt das Nachfüllen beim Bot."
            : "Regelt den Verbrauch von Vorräten wie Pfeilen, Netzen und Heilitems für dich und den Bot.";
         case ARENA_SIZE -> "Standard-Forge-Arena oder deutlich größere Arena mit sanften Hügeln und 80 Blöcken Tiefe.";
         case BOT_JUMP -> "Lässt den Bot beim Stun-Web dauerhaft springen.";
         case ARMOR_WEAR -> "Bestimmt, ob die getragene Rüstung von dir und dem Bot Haltbarkeit verliert.";
         case FALL_DAMAGE -> "Bestimmt den Landeschaden für dich und den Bot. Die für einen Mace-Smash gemessene Fallstrecke bleibt erhalten.";
         default -> "";
      };
   }

   private static <T extends Enum<T>> T nextEnum(T[] values, T current) {
      return values[(current.ordinal() + 1) % values.length];
   }

   private enum Kind { TOGGLE, CHOICE, CARD, SLIDER }

   private record SectionHeader(String title, String rightText, int y) {}
   private record CollapsibleBar(int x, int y, int w, int h, String title, boolean hasModified) {}

   private record ControlSpec(Kind kind, String label, String value, boolean checked, String tooltip,
                              int steps, int initial, IntFunction<String> format, IntConsumer apply, Runnable onClick) {
      ControlItem instantiate(int x, int y, int w, int h) {
         return new ControlItem(kind, x, y, w, h, label, value, checked, tooltip, steps, initial, format, apply, onClick);
      }
   }

   private static final class ControlItem {
      final Kind kind;
      final int x, y, w, h;
      final String label;
      final String value;
      final boolean checked;
      final String tooltip;
      final int steps;
      int step;
      final IntFunction<String> format;
      final IntConsumer onSlide;
      final Runnable onClick;

      ControlItem(Kind kind, int x, int y, int w, int h, String label, String value, boolean checked,
                  String tooltip, int steps, int step, IntFunction<String> format, IntConsumer onSlide, Runnable onClick) {
         this.kind = kind;
         this.x = x; this.y = y; this.w = w; this.h = h;
         this.label = label;
         this.value = value;
         this.checked = checked;
         this.tooltip = tooltip;
         this.steps = steps;
         this.step = step;
         this.format = format;
         this.onSlide = onSlide;
         this.onClick = onClick;
      }

      static ControlItem choiceCard(int x, int y, int w, int h, String label, boolean selected, String tip, Runnable onClick) {
         return new ControlItem(Kind.CARD, x, y, w, h, label, "", selected, tip, 0, 0, null, null, onClick);
      }
   }
}

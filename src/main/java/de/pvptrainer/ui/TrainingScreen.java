package de.pvptrainer.ui;

import de.pvptrainer.TrainerClient;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.kits.KitLibrary;
import de.pvptrainer.kits.KitLoadout;
import de.pvptrainer.kits.TrainingPreset;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.progress.DrillHistory;
import de.pvptrainer.progress.UpdateChecker;
import de.pvptrainer.session.TrainingStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

public final class TrainingScreen extends TrainerPanel {
   private static final int[] TPS_STEPS = {20, 15, 10, 5};
   private static final int[] PING_STEPS = {0, 30, 60, 100};

   private boolean running;
   private boolean available;
   private boolean waiting;
   private DrillHistory.Result shownFailure;
   private UpdateChecker.UpdateInfo shownUpdate;
   private int headerRightReserved;

   // Aimlabs Exercise Browser viewport & scroll state
   private int modeBarY;
   private int modeBarH;
   private int viewTop;
   private int viewBottom;
   private int dockTop;
   private int dockH;
   private int scrollOffset;
   private int contentHeight;
   private int maxScroll;
   private boolean draggingScrollbar;
   private int dragStartY;
   private int dragStartScroll;

   private final List<DrillTile> drillTiles = new ArrayList<>();
   private final List<SectionLabel> sectionLabels = new ArrayList<>();
   private Drill lastClickedDrill;
   private long lastClickMillis;

   // Cached dock coordinates for rendering text & failure hit-testing
   private int dockTitleX;
   private int dockTitleY;
   private int dockTitleMaxW;
   private int failureX;
   private int failureY;
   private int failureW;
   private int failureH;

   public TrainingScreen() {
      super(null, "MacherBot", TrainerClient.selected().mode());
   }

   @Override
   int preferredWidth() {
      return 590;
   }

   @Override
   int preferredHeight() {
      return 334;
   }

   @Override
   int headerRightMargin() {
      return this.headerRightReserved;
   }

   @Override
   public boolean keyPressed(KeyEvent event) {
      if (TrainerClient.available() && TrainerClient.isRestartKey(event)) {
         TrainerClient.restart();
         return true;
      }
      return super.keyPressed(event);
   }

   @Override
   void build() {
      TrainingStatus status = TrainerClient.status();
      this.running = status.active();
      this.waiting = status.waitingForStart();
      this.available = TrainerClient.available();
      DrillOptions options = TrainerClient.options(this.mode);
      Drill selectedDrill = options.drill();
      this.shownFailure = this.latestFailure(status);
      this.shownUpdate = UpdateChecker.available();

      boolean compact = this.panelWidth < 510 || this.panelHeight < 292;

      // Calm live subtitle in header
      if (!this.available) {
         this.subtitle = "Vorschau";
      } else if (!TrainerClient.feedback().isEmpty()) {
         this.subtitle = TrainerClient.feedback();
      } else if (this.running && this.waiting) {
         this.subtitle = "● Startbereit";
      } else if (this.running && status.drill() == Drill.RETOTEM && !status.progress().isEmpty()) {
         this.subtitle = "● " + status.progress();
      } else if (this.running) {
         this.subtitle = "● Runde " + status.rounds() + " · " + status.hits() + " Treffer · "
            + (int)Math.ceil(status.health()) + "/" + (int)status.maxHealth() + " LP";
      } else {
         this.subtitle = "● Bereit";
      }

      // --- Apple-style Header Toolbar (right to left before ×) ---
      int cursorRight = this.right - 24; // leaves room for default × button (20px + 4px gap)
      int headerBtnY = this.top + 6;
      int headerBtnH = 17;

      // Settings Gear Icon
      int gearW = 22;
      cursorRight -= gearW;
      IconChip gearBtn = this.iconChip(cursorRight, headerBtnY, gearW, headerBtnH, UiIcons.GEAR, "", false, EMBER,
         () -> this.open(new TrainingOptionsScreen(this, this.mode)), null, null)
         .tip("Einstellungen · Übung, Bot, Arena & Anzeige");
      gearBtn.setMessage(Component.literal("Einstellungen"));

      cursorRight -= 8; // visual hairline divider space

      // Flat Header Navigation: Statistik · Guide · Kits
      int statsW = this.font.width("Statistik") + (compact ? 8 : 12);
      cursorRight -= statsW;
      this.action(cursorRight, headerBtnY, statsW, headerBtnH, "Statistik", Style.GHOST,
         () -> this.open(new ProgressScreen(this, this.mode)))
         .tip("Verlauf, Rundenlog & Bot-Analyse");

      cursorRight -= 2;
      int guideW = this.font.width("Guide") + (compact ? 8 : 12);
      cursorRight -= guideW;
      Action guideLink = this.action(cursorRight, headerBtnY, guideW, headerBtnH, "Guide", Style.GHOST,
         () -> this.open(new KnowledgeGuideScreen(this, this.mode, selectedDrill)));
      guideLink.setMessage(Component.literal("Technik-Guide"));
      guideLink.tip("Technik-Guide · Timings, Abläufe & Konter");

      cursorRight -= 2;
      int kitsW = this.font.width("Kits") + (compact ? 8 : 12);
      cursorRight -= kitsW;
      Action kitsLink = this.action(cursorRight, headerBtnY, kitsW, headerBtnH, "Kits", Style.GHOST,
         () -> this.open(new KitShelfScreen(this, this.mode, false)));
      kitsLink.active = this.minecraft.level != null;
      kitsLink.tip("Standard-Modus-Kit & eigene Kits verwalten");

      if (this.shownUpdate != null) {
         UpdateChecker.UpdateInfo update = this.shownUpdate;
         String label = "↑ " + update.latestVersion();
         int updW = Math.min(72, this.font.width(label) + 10);
         cursorRight -= updW + 4;
         Action updateBtn = this.action(cursorRight, headerBtnY, updW, headerBtnH, label, Style.SELECTED,
            () -> ConfirmLinkScreen.confirmLinkNow(this, update.downloadUri(), true));
         updateBtn.setMessage(Component.literal("Update " + update.latestVersion()));
         String tip = "Neue MacherBot-Version " + update.latestVersion() + " verfügbar (installiert: " + update.currentVersion() + ")";
         if (!update.summary().isEmpty()) tip += "\n" + update.summary();
         tip += "\nKlicken zum Öffnen der Download-Seite.";
         updateBtn.tip(tip);
      }

      this.headerRightReserved = this.right - cursorRight + 8;

      // --- Row 1: Apple Segmented Mode Control (all 5 PvP modes) ---
      this.modeBarY = this.y;
      this.modeBarH = compact ? 22 : 24;
      TrainingSettings.Mode[] modes = TrainingSettings.Mode.values();
      int segInnerX = this.x + 2;
      int segInnerW = this.inner - 4;
      int segY = this.modeBarY + 2;
      int segH = this.modeBarH - 4;
      for (int i = 0; i < modes.length; i++) {
         TrainingSettings.Mode targetMode = modes[i];
         int sx = gridCol(segInnerX, segInnerW, 2, i, modes.length);
         int sw = gridW(segInnerX, segInnerW, 2, i, modes.length);
         String modeLabel = this.panelWidth < 430 ? "" : targetMode.label();
         this.tile(sx, segY, sw, segH, modeLabel, "", icon(targetMode),
            targetMode == this.mode ? Style.SEGMENT_SELECTED : Style.SEGMENT,
            () -> this.switchMode(targetMode))
            .tip(targetMode.label() + (targetMode == this.mode ? " · aktiv" : " wählen"));
      }

      // --- Row 2: Aimlabs Exercise Browser geometry ---
      this.dockH = compact ? 76 : 82;
      this.dockTop = this.bottom - this.dockH;
      this.viewTop = this.modeBarY + this.modeBarH + (compact ? 6 : 8);
      this.viewBottom = this.dockTop - (compact ? 5 : 7);
      this.layoutDrillGrid(compact);

      // --- Row 3: Elevated Apple Launch Dock ---
      this.buildLaunchDock(options, selectedDrill, status, compact);
   }

   private void switchMode(TrainingSettings.Mode nextMode) {
      if (nextMode == this.mode) return;
      DrillOptions currentOpts = TrainerClient.options(this.mode);
      DrillOptions targetOpts = currentOpts.carryCrossModeModifiersTo(TrainerClient.options(nextMode));
      TrainingSettings targetSettings = TrainerClient.settings(nextMode).withDifficulty(TrainingSettings.Difficulty.HARD);
      this.mode = nextMode;
      this.scrollOffset = 0;
      this.lastClickedDrill = null;
      TrainerClient.saveSetup(targetSettings, targetOpts);
      this.refresh();
   }

   private void selectDrill(Drill drill, boolean launch) {
      DrillOptions current = TrainerClient.options(this.mode);
      boolean changed = drill != current.drill();
      if (changed) {
         DrillOptions next = current.carryModifiersTo(DrillOptions.forDrill(drill), this.mode);
         TrainingSettings nextSettings = TrainerClient.settings(this.mode).forDrill(drill);
         TrainerClient.saveSetup(nextSettings, next);
         this.refresh();
      }
      if (launch && this.available) {
         TrainerClient.start(TrainerClient.settings(this.mode));
      }
   }

   private void layoutDrillGrid(boolean compact) {
      this.drillTiles.clear();
      this.sectionLabels.clear();

      List<Drill> freeDrills = List.of(Drill.DUEL, Drill.PASSIVE, Drill.TOTEM_POPS, Drill.SHIELD)
         .stream().filter(d -> d.supports(this.mode)).toList();
      List<Drill> techDrills = Drill.forMode(this.mode).stream()
         .filter(d -> !freeDrills.contains(d)).toList();

      int labelH = 11;
      int freeCardH = compact ? 22 : 24;
      int techCardH = compact ? 21 : 23;
      int cardGap = compact ? 3 : 4;
      int techCols = this.panelWidth < 430 ? 2 : 3;
      int techRows = (techDrills.size() + techCols - 1) / techCols;

      int totalH = labelH + freeCardH + (compact ? 7 : 9)
         + labelH + (techRows > 0 ? techRows * techCardH + Math.max(0, techRows - 1) * cardGap : 0) + 2;

      int viewH = Math.max(40, this.viewBottom - this.viewTop);
      this.contentHeight = totalH;
      this.maxScroll = Math.max(0, totalH - viewH);
      this.scrollOffset = Math.clamp(this.scrollOffset, 0, this.maxScroll);

      int gridW = this.maxScroll > 0 ? this.inner - 7 : this.inner;
      int cy = 0;

      // Section 1: Freie Modi (always at the top)
      this.sectionLabels.add(new SectionLabel("FREIE MODI", cy));
      cy += labelH;
      int freeCols = Math.max(1, freeDrills.size());
      for (int i = 0; i < freeDrills.size(); i++) {
         Drill d = freeDrills.get(i);
         int cx = gridCol(this.x, gridW, cardGap, i, freeCols);
         int cw = gridW(this.x, gridW, cardGap, i, freeCols);
         this.drillTiles.add(new DrillTile(d, cx, cy, cw, freeCardH, true));
      }
      cy += freeCardH + (compact ? 7 : 9);

      // Section 2: Technikübungen (clean Aimlabs card grid)
      this.sectionLabels.add(new SectionLabel("TECHNIKÜBUNGEN · " + techDrills.size(), cy));
      cy += labelH;
      for (int i = 0; i < techDrills.size(); i++) {
         Drill d = techDrills.get(i);
         int col = i % techCols;
         int row = i / techCols;
         int cx = gridCol(this.x, gridW, cardGap, col, techCols);
         int cw = gridW(this.x, gridW, cardGap, col, techCols);
         int tileY = cy + row * (techCardH + cardGap);
         this.drillTiles.add(new DrillTile(d, cx, tileY, cw, techCardH, false));
      }
   }

   private void buildLaunchDock(DrillOptions options, Drill drill, TrainingStatus status, boolean compact) {
      int dockPad = compact ? 6 : 8;
      int dx = this.x + dockPad;
      int dRight = this.right - dockPad;
      int dInner = dRight - dx;

      // --- Dock Line 1: Title + guide hint + [?] Guide icon ---
      int line1Y = this.dockTop + (compact ? 5 : 6);
      int helpSize = 16;
      this.iconChip(dRight - helpSize, line1Y - 1, helpSize, helpSize, UiIcons.HELP, "", false, EMBER,
         () -> this.open(new KnowledgeGuideScreen(this, this.mode, drill)), null, null)
         .tip("Technik-Guide zu " + drill.label() + " öffnen");

      this.dockTitleX = dx;
      this.dockTitleY = line1Y + 2;
      this.dockTitleMaxW = dInner - helpSize - 6;

      // Optional clickable failure note replaces extra space on line 1 right before [?]
      this.failureW = 0;
      if (this.shownFailure != null) {
         String shortFail = "⚠ " + this.fit(this.shownFailure.reason(), Math.min(180, dInner / 2));
         this.failureW = Math.min(dInner / 2, this.font.width(shortFail) + 10);
         this.failureX = dRight - helpSize - 5 - this.failureW;
         this.failureY = line1Y - 1;
         this.failureH = 15;
         this.dockTitleMaxW = Math.max(60, this.failureX - dx - 6);
      }

      // --- Dock Line 2: Context Setup (Variants / Duel Tier / Flight) + Active Kit Pill ---
      int line2Y = this.dockTop + (compact ? 24 : 26);
      int line2H = compact ? 18 : 19;

      String kitLabel = "Kit: " + this.activeKitName();
      int kitPillW = Math.min(compact ? 112 : 142, Math.max(68, this.font.width(kitLabel) + 18));
      Action kitPill = this.action(dRight - kitPillW, line2Y, kitPillW, line2H, this.fit(kitLabel, kitPillW - 10) + " ▸", Style.NORMAL,
         () -> this.open(new KitShelfScreen(this, this.mode, false)));
      kitPill.active = this.minecraft.level != null;
      kitPill.tip("Aktives Spieler-Kit: " + this.activeKitName() + "\nKlicken zum Wechseln oder Bearbeiten.");

      int setupMaxW = dInner - kitPillW - 6;
      this.buildSetupStrip(options, drill, dx, line2Y, setupMaxW, line2H, compact);

      // --- Dock Line 3: 6 Sticky Quick-Modifier Icons (left) + Primary Start / Stop CTA (right) ---
      int line3Y = this.dockTop + this.dockH - (compact ? 26 : 28);
      int line3H = compact ? 21 : 22;

      int stopW = this.running ? (compact ? 64 : 74) : 0;
      int startW = compact ? 112 : 136;
      int ctaRight = dRight;

      if (this.running) {
         this.action(ctaRight - stopW, line3Y, stopW, line3H, "Beenden", Style.NORMAL, TrainerClient::stop)
            .tip("Training beenden und in deine Welt zurückkehren");
         ctaRight -= stopW + 4;
      }

      boolean repeat = this.matchesLastConfiguration(status);
      Action startBtn = this.action(ctaRight - startW, line3Y, startW, line3H,
         repeat ? "▶ Wiederholen" : "▶ Starten", Style.PRIMARY,
         () -> TrainerClient.start(TrainerClient.settings(this.mode)));
      startBtn.active = this.available;
      startBtn.tip((repeat ? "Aktuelle Konfiguration wiederholen" : "Training starten")
         + " · Doppelklick auf Übung startet direkt\nNeustart im Spiel: " + TrainerClient.restartKeyLabel().getString());

      int iconsMaxRight = ctaRight - startW - 8;
      this.buildQuickIcons(options, dx, line3Y, line3H, iconsMaxRight, compact);
   }

   private void buildSetupStrip(DrillOptions options, Drill drill, int sx, int sy, int maxW, int sh, boolean compact) {
      if (maxW < 70) return;
      TrainingSettings settings = TrainerClient.settings(this.mode);

      if (drill == Drill.DUEL) {
         // Tier T1–T5 + Band LT–HT / LT / HT cleanly in one row
         int bandW = compact ? 96 : 114;
         int tiersW = Math.max(90, maxW - bandW - 6);
         TrainingSettings.DuelTier[] tiers = TrainingSettings.DuelTier.values();
         for (int i = 0; i < tiers.length; i++) {
            TrainingSettings.DuelTier tier = tiers[i];
            int bx = gridCol(sx, tiersW, 2, i, tiers.length);
            int bw = gridW(sx, tiersW, 2, i, tiers.length);
            this.action(bx, sy, bw, sh, tier.name(),
               tier == settings.duelTier() ? Style.SELECTED : Style.NORMAL,
               () -> {
                  TrainerClient.save(TrainerClient.settings(this.mode).withDuelTier(tier));
                  this.refresh();
               }).tip(TrainingSettings.DuelBand.AUTO.label(tier) + " · " + switch (tier) {
               case T1 -> "Kurze Öffnungen nutzen und auf Gegenwehr umstellen.";
               case T2 -> "Mehrere Aktionen verbinden und auf eine Finte reagieren.";
               case T3 -> "Länger sichtbare Gelegenheiten für einen Konter nutzen.";
               case T4 -> "Gegen einen vorhersehbaren Angriff die passende Antwort üben.";
               case T5 -> "Abstand, Schläge und einzelne Gegenstände üben.";
            });
         }

         int bx0 = sx + tiersW + 6;
         TrainingSettings.DuelBand[] bands = TrainingSettings.DuelBand.values();
         for (int i = 0; i < bands.length; i++) {
            TrainingSettings.DuelBand band = bands[i];
            String label = switch (band) {
               case AUTO -> "Alle";
               case LOW -> "LT";
               case HIGH -> "HT";
            };
            int bx = gridCol(bx0, bandW, 2, i, bands.length);
            int bw = gridW(bx0, bandW, 2, i, bands.length);
            this.action(bx, sy, bw, sh, label,
               band == settings.duelBand() ? Style.SELECTED : Style.NORMAL,
               () -> {
                  TrainerClient.save(TrainerClient.settings(this.mode).withDuelBand(band));
                  this.refresh();
               }).tip("Gegner-Rang: " + band.label(settings.duelTier()));
         }
         return;
      }

      if (drill == Drill.ELYTRA_SMASH || drill == Drill.ELY_STUN_SLAM) {
         int[] heights = {16, 32, 64};
         DrillOptions.Field field = DrillOptions.Field.START_HEIGHT;
         for (int i = 0; i < heights.length; i++) {
            int height = heights[i];
            int bx = gridCol(sx, maxW, 3, i, heights.length);
            int bw = gridW(sx, maxW, 3, i, heights.length);
            this.action(bx, sy, bw, sh, "Luftstart " + height + " m",
               options.playerElevation() == height ? Style.SELECTED : Style.NORMAL,
               () -> {
                  TrainerClient.saveOptions(this.mode, options.withIndex(field, field.indexOfNumber(height)));
                  this.refresh();
               }).tip("Deine Starthöhe über dem Arenaboden");
         }
         return;
      }

      if (drill == Drill.WIND_STUN_SLAM) {
         // Air start presets + Wind flight mode side by side in ONE clean row
         int leftW = (maxW - 6) * 48 / 100;
         int rightX = sx + leftW + 6;
         int rightW = maxW - leftW - 6;
         TrainingPreset[] presets = TrainingPreset.values();
         for (int i = 0; i < presets.length; i++) {
            TrainingPreset preset = presets[i];
            TrainingPreset.Setup setup = preset.setup(settings, drill);
            DrillOptions target = options.carryModifiersTo(setup.options(), this.mode)
               .withIndex(DrillOptions.Field.WIND_FLIGHT, options.windFlight().ordinal());
            boolean active = setup.behavior().equals(settings) && target.equals(options);
            String shortLabel = switch (preset) {
               case BEGINNER -> "Boden";
               case PRACTICE -> "16 m";
               case ADVANCED -> "32 m";
            };
            int bx = gridCol(sx, leftW, 2, i, presets.length);
            int bw = gridW(sx, leftW, 2, i, presets.length);
            this.action(bx, sy, bw, sh, shortLabel, active ? Style.SELECTED : Style.NORMAL, () -> {
               TrainerClient.saveSetup(setup.behavior(), target);
               this.refresh();
            }).tip(preset.label(drill) + " · " + setup.detail());
         }

         DrillOptions.Field flightField = DrillOptions.Field.WIND_FLIGHT;
         DrillOptions.WindFlight[] flights = DrillOptions.WindFlight.values();
         for (int i = 0; i < flights.length; i++) {
            DrillOptions.WindFlight flight = flights[i];
            DrillOptions nextOpts = options.withIndex(flightField, flight.ordinal());
            String shortLabel = switch (flight) {
               case CHARGE -> "Charge";
               case CHARGE_AND_CATCH -> "+Catch";
               case CATCH -> "Catch";
            };
            int bx = gridCol(rightX, rightW, 2, i, flights.length);
            int bw = gridW(rightX, rightW, 2, i, flights.length);
            this.action(bx, sy, bw, sh, shortLabel,
               flight == options.windFlight() ? Style.SELECTED : Style.NORMAL,
               () -> {
                  TrainerClient.saveOptions(this.mode, nextOpts);
                  this.refresh();
               }).tip("Bot-Aufstieg: " + nextOpts.value(flightField));
         }
         return;
      }

      if (drill.hidesPresets()) {
         return;
      }

      // Standard 3 contextual drill variants (presets keep sticky modifiers!)
      TrainingPreset[] presets = TrainingPreset.values();
      for (int i = 0; i < presets.length; i++) {
         TrainingPreset preset = presets[i];
         TrainingPreset.Setup setup = preset.setup(settings, drill);
         DrillOptions presetOpts = options.carryModifiersTo(setup.options(), this.mode);
         boolean active = setup.behavior().equals(settings) && presetOpts.equals(options);
         int bx = gridCol(sx, maxW, 3, i, presets.length);
         int bw = gridW(sx, maxW, 3, i, presets.length);
         this.action(bx, sy, bw, sh, preset.label(drill), active ? Style.SELECTED : Style.NORMAL, () -> {
            TrainerClient.saveSetup(setup.behavior(), presetOpts);
            this.refresh();
         }).tip(setup.detail());
      }
   }

   private void buildQuickIcons(DrillOptions options, int startX, int y, int h, int maxRight, boolean compact) {
      int cx = startX;
      int chipGap = compact ? 3 : 4;
      int baseW = compact ? 22 : 24;

      // 1. Spieltempo (TPS): gray at 20 TPS, Amber + value when Slow-Mo
      int tps = options.tickRate();
      boolean slowMo = tps != 20;
      String tpsLabel = slowMo ? (compact ? Integer.toString(tps) : tps + " TPS") : "";
      int tpsW = slowMo ? Math.max(baseW + 14, 18 + this.font.width(tpsLabel) + 6) : baseW;
      if (cx + tpsW <= maxRight) {
         this.iconChip(cx, y, tpsW, h, UiIcons.CLOCK, tpsLabel, slowMo, ACCENT_AMBER,
            () -> this.setTickRate(nextPreset(TPS_STEPS, tps)),
            () -> this.setTickRate(tps <= 1 ? 20 : tps - 1),
            dir -> this.setTickRate(Math.clamp(tps + dir, 1, 20)))
            .tip("Spieltempo: " + tps + " TPS" + (slowMo ? " (Slow-Mo aktiv)" : " (Normal)")
               + "\nLinksklick: 20 → 15 → 10 → 5 TPS\nRechtsklick / Mausrad: fein ±1 TPS");
         cx += tpsW + chipGap;
      }

      // 2. Arena-Größe: gray at Standard, Green + "Groß" when Large Arena
      boolean large = options.largeArena();
      String arenaLabel = large ? (compact ? "Gr." : "Groß") : "";
      int arenaW = large ? Math.max(baseW + 14, 18 + this.font.width(arenaLabel) + 6) : baseW;
      if (cx + arenaW <= maxRight) {
         this.iconChip(cx, y, arenaW, h, UiIcons.ARENA, arenaLabel, large, ACCENT_GREEN,
            () -> {
               TrainerClient.saveOptions(this.mode, options.withIndex(DrillOptions.Field.ARENA_SIZE, large ? 0 : 1));
               this.refresh();
            }, null, null)
            .tip("Arena: " + (large ? "Groß (Hügel, 80 Bl. Tiefe)" : "Standard (Forge-Arena)")
               + "\nKlicken zum Wechseln (bleibt beim Übungswechsel aktiv)");
         cx += arenaW + chipGap;
      }

      // 3. Zero-Damage: gray when OFF, Sky Blue (Player) / Cyan (Both) when active
      DrillOptions.ZeroDamage zd = options.zeroDamage();
      boolean zdActive = zd != DrillOptions.ZeroDamage.OFF;
      String zdLabel = switch (zd) {
         case OFF -> "";
         case PLAYER_ONLY -> "Du";
         case BOTH -> "2×";
      };
      int zdColor = zd == DrillOptions.ZeroDamage.BOTH ? ACCENT_CYAN : ACCENT_SKY;
      int zdW = zdActive ? Math.max(baseW + 12, 18 + this.font.width(zdLabel) + 6) : baseW;
      if (cx + zdW <= maxRight) {
         this.iconChip(cx, y, zdW, h, UiIcons.ZERO_DAMAGE, zdLabel, zdActive, zdColor,
            () -> {
               int next = (zd.ordinal() + 1) % DrillOptions.ZeroDamage.values().length;
               TrainerClient.saveOptions(this.mode, options.withIndex(DrillOptions.Field.ZERO_DAMAGE, next));
               this.refresh();
            },
            () -> {
               int len = DrillOptions.ZeroDamage.values().length;
               int prev = (zd.ordinal() + len - 1) % len;
               TrainerClient.saveOptions(this.mode, options.withIndex(DrillOptions.Field.ZERO_DAMAGE, prev));
               this.refresh();
            }, null)
            .tip("Zero-Damage: " + switch (zd) {
               case OFF -> "Aus (normaler Schaden)";
               case PLAYER_ONLY -> "Nur Spieler (unverwundbar)";
               case BOTH -> "Beide (0 LP-Verlust)";
            } + "\nKlicken: Aus → Du → Beide");
         cx += zdW + chipGap;
      }

      // 4. Ping-Simulation: gray at 0 ms, Coral + ms when active
      int ping = options.playerPing();
      boolean pingActive = ping > 0;
      String pingLabel = pingActive ? (compact ? Integer.toString(ping) : ping + " ms") : "";
      int pingW = pingActive ? Math.max(baseW + 14, 18 + this.font.width(pingLabel) + 6) : baseW;
      if (cx + pingW <= maxRight) {
         this.iconChip(cx, y, pingW, h, UiIcons.PING, pingLabel, pingActive, ACCENT_CORAL,
            () -> this.setPlayerPing(nextPreset(PING_STEPS, ping)),
            () -> this.setPlayerPing(ping <= 0 ? 100 : Math.max(0, ping - 5)),
            dir -> this.setPlayerPing(Math.clamp(ping + dir * 5, 0, 300)))
            .tip("Dein Ping: " + ping + " ms" + (pingActive ? " (simuliert)" : " (lokal)")
               + "\nLinksklick: 0 → 30 → 60 → 100 ms\nRechtsklick / Mausrad: fein ±5 ms");
         cx += pingW + chipGap;
      }

      // 5. Unendliche Items: gray when consuming items, Violet when Infinite Items ON
      boolean infinite = !options.itemConsumption(this.mode);
      if (cx + baseW <= maxRight) {
         this.iconChip(cx, y, baseW, h, UiIcons.INFINITY, "", infinite, ACCENT_VIOLET,
            () -> {
               TrainerClient.saveOptions(this.mode, options.next(DrillOptions.Field.ITEM_CONSUMPTION, this.mode));
               this.refresh();
            }, null, null)
            .tip("Unendliche Items: " + (infinite ? "Aktiv (kein Item-Verbrauch)" : "Aus (normaler Verbrauch)")
               + "\nKlicken zum Umschalten");
         cx += baseW + chipGap;
      }

      // 6. Fallschaden: gray when OFF (default), Gold when ON
      boolean fallDmg = options.fallDamage();
      if (cx + baseW <= maxRight) {
         this.iconChip(cx, y, baseW, h, UiIcons.FALL_DAMAGE, "", fallDmg, ACCENT_GOLD,
            () -> {
               TrainerClient.saveOptions(this.mode, options.withIndex(DrillOptions.Field.FALL_DAMAGE, fallDmg ? 1 : 0));
               this.refresh();
            }, null, null)
            .tip("Fallschaden: " + (fallDmg ? "An" : "Aus (Standard)")
               + "\nKlicken zum Umschalten (Mace-Fallstrecke bleibt immer erhalten)");
      }
   }

   private void setTickRate(int tps) {
      DrillOptions current = TrainerClient.options(this.mode);
      DrillOptions.Field field = DrillOptions.Field.TICK_RATE;
      TrainerClient.saveOptions(this.mode, current.withIndex(field, field.indexOfNumber(Math.clamp(tps, 1, 20))));
      this.refresh();
   }

   private void setPlayerPing(int ms) {
      DrillOptions current = TrainerClient.options(this.mode);
      DrillOptions.Field field = DrillOptions.Field.PLAYER_PING;
      int snapped = Math.clamp((ms / 5) * 5, 0, 300);
      TrainerClient.saveOptions(this.mode, current.withIndex(field, field.indexOfNumber(snapped)));
      this.refresh();
   }

   private static int nextPreset(int[] presets, int current) {
      for (int i = 0; i < presets.length; i++) {
         if (presets[i] == current) {
            return presets[(i + 1) % presets.length];
         }
      }
      return presets[0];
   }

   private String activeKitName() {
      if (this.minecraft.level == null) return "Standardkit";
      try {
         RegistryAccess reg = this.minecraft.level.registryAccess();
         KitLoadout selected = KitLibrary.selected(reg, this.mode, false);
         return selected.preset() ? "Standard" : selected.name();
      } catch (RuntimeException ignored) {
         return "Standard";
      }
   }

   private DrillHistory.Result latestFailure(TrainingStatus status) {
      if (status.active() && status.drill() == Drill.RETOTEM) return null;
      if (!status.recent().isEmpty() && status.mode() == this.mode && status.drill() == TrainerClient.options(this.mode).drill()) {
         DrillHistory.Result last = status.recent().getLast();
         if (status.drill() == Drill.RETOTEM && !last.reason().equals(status.progress())) return null;
         return last.won() ? null : last;
      }
      return null;
   }

   private String guide(Drill drill, DrillOptions options) {
      return drill.standardGuide(this.mode, options, this.minecraft.options.keyJump.getTranslatedKeyMessage().getString());
   }

   private boolean matchesLastConfiguration(TrainingStatus status) {
      if (this.available && !status.seriesKey().isEmpty()) {
         RegistryAccess registry = this.minecraft.level.registryAccess();
         KitLoadout playerKit = KitLibrary.personalized(registry, this.mode, KitLibrary.selected(registry, this.mode, false).id());
         String kitKey = KitLibrary.fingerprint(registry, playerKit) + "/" + KitLibrary.fingerprint(registry, KitLibrary.selected(registry, this.mode, true));
         return status.seriesKey().equals(TrainerClient.seriesKey(this.mode, kitKey));
      }
      return false;
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      super.extractRenderState(graphics, mouseX, mouseY, delta);

      // Tooltips rendered AFTER widgets & scissor clipping so they never get cut off
      if (mouseX >= this.x && mouseX <= this.right && mouseY >= this.viewTop && mouseY <= this.viewBottom) {
         DrillTile hovered = this.tileAt(mouseX, mouseY);
         if (hovered != null) {
            DrillOptions opts = hovered.drill == TrainerClient.options(this.mode).drill()
               ? TrainerClient.options(this.mode) : DrillOptions.forDrill(hovered.drill);
            String desc = hovered.drill.hint();
            String startNote = this.available ? "Klick: auswählen · Doppelklick: direkt starten" : "Zum Trainieren eine eigene Welt öffnen";
            graphics.setComponentTooltipForNextFrame(this.font, List.of(
               Component.literal(hovered.drill.label()).withStyle(ChatFormatting.BOLD),
               Component.literal(desc),
               Component.literal(startNote).withStyle(ChatFormatting.GRAY)
            ), mouseX, mouseY);
         }
      } else if (this.shownFailure != null && this.failureW > 0
         && mouseX >= this.failureX && mouseX <= this.failureX + this.failureW
         && mouseY >= this.failureY && mouseY <= this.failureY + this.failureH) {
         graphics.setComponentTooltipForNextFrame(this.font, List.of(
            Component.literal("Letzter Fehlversuch").withStyle(ChatFormatting.GOLD),
            Component.literal(this.shownFailure.reason()),
            Component.literal("Klicken für Rundenlog").withStyle(ChatFormatting.GRAY)
         ), mouseX, mouseY);
      }
   }

   @Override
   void content(GuiGraphicsExtractor graphics) {
      // Hairline vertical divider next to gear icon in header
      int dividerX = this.right - 24 - 22 - 4;
      graphics.fill(dividerX, this.top + 8, dividerX + 1, this.top + 21, LINE);

      // Apple Segmented Control recessed track for the 5 modes
      fillRounded2(graphics, this.x, this.modeBarY, this.inner, this.modeBarH, TRACK);
      outlineRounded2(graphics, this.x, this.modeBarY, this.inner, this.modeBarH, LINE);

      // --- Aimlabs Exercise Grid (scissor-clipped) ---
      Drill activeDrill = TrainerClient.options(this.mode).drill();
      double mx = this.minecraft.mouseHandler.xpos() * this.width / Math.max(1, this.minecraft.getWindow().getScreenWidth());
      double my = this.minecraft.mouseHandler.ypos() * this.height / Math.max(1, this.minecraft.getWindow().getScreenHeight());
      boolean inView = mx >= this.x && mx <= this.right && my >= this.viewTop && my <= this.viewBottom;

      graphics.enableScissor(this.x - 1, this.viewTop, this.right + 1, this.viewBottom);
      for (SectionLabel sec : this.sectionLabels) {
         int sy = this.viewTop + sec.y - this.scrollOffset;
         if (sy + 10 >= this.viewTop && sy <= this.viewBottom) {
            graphics.text(this.font, sec.title, this.x + 1, sy, MUTED, false);
         }
      }

      for (DrillTile tile : this.drillTiles) {
         int ty = this.viewTop + tile.y - this.scrollOffset;
         if (ty + tile.h < this.viewTop || ty > this.viewBottom) continue;

         boolean selected = tile.drill == activeDrill;
         boolean hovered = inView && mx >= tile.x && mx < tile.x + tile.w && my >= ty && my < ty + tile.h;

         int bg = selected ? (hovered ? 0xFF3E2A30 : SELECTED) : (hovered ? HOVER : (tile.freeMode ? 0xFF26262F : CELL));
         fillRounded(graphics, tile.x, ty, tile.w, tile.h, bg);
         if (selected) {
            outlineRounded(graphics, tile.x, ty, tile.w, tile.h, withAlpha(EMBER, 0xC8));
            graphics.fill(tile.x + 1, ty + 4, tile.x + 3, ty + tile.h - 4, EMBER);
         } else {
            outlineRounded(graphics, tile.x, ty, tile.w, tile.h, hovered ? 0x48FFFFFF : LINE);
         }

         // Scaled crisp 12x12 Minecraft item icon
         ItemStack stack = UiIcons.drillIcon(tile.drill, this.mode);
         int iconX = tile.x + (selected ? 6 : 5);
         int iconY = ty + (tile.h - 12) / 2;
         var pose = graphics.pose();
         pose.pushMatrix();
         pose.translate(iconX, iconY);
         pose.scale(0.75f);
         graphics.item(stack, 0, 0);
         pose.popMatrix();

         int textX = iconX + 15;
         int textMaxW = tile.x + tile.w - textX - 5;
         int textY = ty + (tile.h - 8) / 2;
         this.line(graphics, tile.drill.label(), textX, textY, textMaxW, selected ? TEXT : (hovered ? TEXT : 0xFFE5E5EA));
      }
      graphics.disableScissor();

      // Subtle Apple scrollbar when scrolling is needed
      if (this.maxScroll > 0) {
         drawScrollbar(graphics, this.right - 3, this.viewTop, this.viewBottom - this.viewTop,
            this.scrollOffset, this.maxScroll, this.contentHeight);
      }

      // --- Elevated Apple Launch Dock ---
      fillRounded2(graphics, this.x, this.dockTop, this.inner, this.dockH, SURFACE);
      outlineRounded2(graphics, this.x, this.dockTop, this.inner, this.dockH, BORDER);

      // Dock Line 1: Selected Drill Icon + Bold Title + short guide inline
      ItemStack activeIcon = UiIcons.drillIcon(activeDrill, this.mode);
      var pose = graphics.pose();
      pose.pushMatrix();
      pose.translate(this.dockTitleX, this.dockTitleY - 2);
      pose.scale(0.75f);
      graphics.item(activeIcon, 0, 0);
      pose.popMatrix();

      MutableComponent titleComp = Component.literal(activeDrill.label()).withStyle(ChatFormatting.BOLD);
      int tx = this.dockTitleX + 15;
      graphics.text(this.font, titleComp, tx, this.dockTitleY, TEXT, false);
      int guideX = tx + this.font.width(titleComp) + 7;
      int guideW = this.dockTitleX + this.dockTitleMaxW - guideX;
      if (guideW > 36) {
         String guideText = this.guide(activeDrill, TrainerClient.options(this.mode));
         this.line(graphics, "·  " + guideText, guideX, this.dockTitleY, guideW, MUTED);
      }

      // Clickable Failure Pill (when last attempt failed)
      if (this.shownFailure != null && this.failureW > 0) {
         boolean hoverFail = mx >= this.failureX && mx <= this.failureX + this.failureW
            && my >= this.failureY && my <= this.failureY + this.failureH;
         fillRounded(graphics, this.failureX, this.failureY, this.failureW, this.failureH,
            withAlpha(ACCENT_AMBER, hoverFail ? 0x3A : 0x22));
         outlineRounded(graphics, this.failureX, this.failureY, this.failureW, this.failureH,
            withAlpha(ACCENT_AMBER, hoverFail ? 0xCC : 0x80));
         this.line(graphics, "⚠ " + this.shownFailure.reason(), this.failureX + 5, this.failureY + 4, this.failureW - 10, ACCENT_AMBER);
      }

      // If drill hides presets, show calm hint on Line 2 left
      if (activeDrill.hidesPresets()) {
         int line2Y = this.dockTop + (this.panelWidth < 510 || this.panelHeight < 292 ? 24 : 26);
         this.line(graphics, activeDrill.hint(), this.dockTitleX, line2Y + 5, Math.max(40, this.inner - 165), MUTED);
      }
   }

   private DrillTile tileAt(double mx, double my) {
      if (my < this.viewTop || my > this.viewBottom) return null;
      for (DrillTile tile : this.drillTiles) {
         int ty = this.viewTop + tile.y - this.scrollOffset;
         if (mx >= tile.x && mx < tile.x + tile.w && my >= ty && my < ty + tile.h) {
            return tile;
         }
      }
      return null;
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
      double mx = event.x();
      double my = event.y();

      if (event.button() == 0) {
         // Scrollbar drag start
         if (this.maxScroll > 0 && mx >= this.right - 7 && mx <= this.right + 1 && my >= this.viewTop && my <= this.viewBottom) {
            this.draggingScrollbar = true;
            this.dragStartY = (int)my;
            this.dragStartScroll = this.scrollOffset;
            return true;
         }

         // Click on Aimlabs drill card (single click selects, double click starts immediately!)
         DrillTile hit = this.tileAt(mx, my);
         if (hit != null) {
            this.minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
            long now = System.currentTimeMillis();
            boolean doubleClick = twice || (hit.drill == this.lastClickedDrill && now - this.lastClickMillis <= 360L);
            this.lastClickedDrill = hit.drill;
            this.lastClickMillis = now;
            this.selectDrill(hit.drill, doubleClick);
            return true;
         }

         // Click on Failure Pill -> jump straight to Statistik (Rundenlog tab)
         if (this.shownFailure != null && this.failureW > 0
            && mx >= this.failureX && mx <= this.failureX + this.failureW
            && my >= this.failureY && my <= this.failureY + this.failureH) {
            this.open(ProgressScreen.openLog(this, this.mode, true));
            return true;
         }
      }

      return super.mouseClicked(event, twice);
   }

   @Override
   public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
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
      if (this.draggingScrollbar) {
         this.draggingScrollbar = false;
         return true;
      }
      return super.mouseReleased(event);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.maxScroll > 0 && mouseX >= this.x && mouseX <= this.right && mouseY >= this.viewTop && mouseY <= this.viewBottom) {
         int next = Math.clamp(this.scrollOffset - (int)Math.round(scrollY * 22.0), 0, this.maxScroll);
         if (next != this.scrollOffset) {
            this.scrollOffset = next;
            return true;
         }
      }
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   @Override
   public void tick() {
      if (this.running != TrainerClient.status().active()
         || this.waiting != TrainerClient.status().waitingForStart()
         || this.available != TrainerClient.available()
         || !Objects.equals(this.shownFailure, this.latestFailure(TrainerClient.status()))
         || !Objects.equals(this.shownUpdate, UpdateChecker.available())) {
         this.refresh();
      }
   }

   private record DrillTile(Drill drill, int x, int y, int w, int h, boolean freeMode) {}
   private record SectionLabel(String title, int y) {}
}

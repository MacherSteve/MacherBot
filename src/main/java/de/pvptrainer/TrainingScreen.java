package de.pvptrainer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map.Entry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

public final class TrainingScreen extends TrainerPanel {
   private boolean running;
   private boolean available;
   private boolean waiting;
   private int statusY;
   private boolean showStatus;
   private DrillHistory.Result shownFailure;

   public TrainingScreen() {
      super(null, releaseTitle(), TrainerClient.selected().mode());
   }

   private static String releaseTitle() {
      return "MacherBot";
   }

   public boolean keyPressed(KeyEvent var1) {
      if (TrainerClient.available() && TrainerClient.isRestartKey(var1)) {
         TrainerClient.restart();
         return true;
      } else {
         return super.keyPressed(var1);
      }
   }

   @Override
   int preferredWidth() {
      return 446;
   }

   @Override
   int preferredHeight() {
      return (hasStatus() ? 248 : 222) + (TrainerClient.options(this.mode).drill() == Drill.WIND_STUN_SLAM ? 24 : 0);
   }

   private static boolean hasStatus() {
      return !TrainerClient.available() || !TrainerClient.feedback().isEmpty() || TrainerClient.status().active() || TrainerClient.status().rounds() > 0;
   }

   @Override
   void build() {
      TrainingStatus var1 = TrainerClient.status();
      this.running = var1.active();
      this.waiting = var1.waitingForStart();
      this.available = TrainerClient.available();
      DrillOptions var11 = TrainerClient.options(this.mode);
      Drill var12 = var11.drill();
      boolean wind = var12 == Drill.WIND_STUN_SLAM;
      this.showStatus = hasStatus() && this.panelHeight >= (wind ? 228 : 192);
      this.shownFailure = this.latestFailure(var1);
      this.subtitle = !this.available ? "Vorschau" : (this.running ? (var1.waitingForStart() ? "Startbereit" : "Training aktiv") : "");
      int var2 = this.panelHeight < 175 ? 18 : 22;
      TrainingSettings.Mode[] var6;
      int var5 = (var6 = TrainingSettings.Mode.values()).length;

      for (int var4 = 0; var4 < var5; var4++) {
         TrainingSettings.Mode var3 = var6[var4];
         int var7 = var3.ordinal();
         this.tile(
               this.col(var7, 5),
               this.y,
               this.cw(var7, 5),
               var2,
               var3.label(),
               "",
               icon(var3),
               var3 == this.mode ? TrainerPanel.Style.SELECTED : TrainerPanel.Style.NORMAL,
               () -> {
                  this.mode = var3;
                  TrainerClient.save(TrainerClient.settings(this.mode));
                  this.refresh();
               }
            )
            .tip(var3.label() + (var3 == this.mode ? " ist ausgewählt" : " wählen"));
      }

      TrainerPanel.Action kits = this.action(this.col(4, 5), this.y, this.cw(4, 5), var2,
         "Eigene Kits", TrainerPanel.Style.NORMAL, () -> this.open(new KitShelfScreen(this, this.mode, false)));
      kits.active = this.minecraft.level != null;
      kits.tip("Eigene Kits für alle Modi erstellen, bearbeiten und ausrüsten.");

      int var17 = this.panelHeight < 175 ? 18 : 22;
      int var18 = this.bottom - var17;
      var5 = this.panelHeight < 175 ? 20 : 25;
      int var20 = this.showStatus ? (this.shownFailure == null ? 16 : 24) : 0;
      int var21 = var18 - 11 - var20 - var5;
      int var8 = this.panelHeight < 190 ? 16 : 20;
      int var9 = var21 - var8 - this.gap;
      int var10 = this.y + var2 + this.gap;
      boolean showPresets = var12 != Drill.LOW_WEB_SMASH && var12 != Drill.PERFECT_WIND_CHARGE;
      int firstRow = wind ? var9 - var8 - this.gap : var9;
      this.addRenderableWidget(new TrainingScreen.DrillCard(var10, (showPresets ? firstRow : var21) - var10 - this.gap, var12));
      if (showPresets) this.addProfiles(var11, firstRow, var8);
      if (wind) {
         var field = DrillOptions.Field.WIND_FLIGHT;
         for (var flight : DrillOptions.WindFlight.values()) {
            int column = flight.ordinal();
            var selected = var11.withIndex(field, column);
            String label = selected.value(field);
            String shortLabel = switch (flight) {
               case CHARGE -> "Wind Charge";
               case CHARGE_AND_CATCH -> "Charge + Catch";
               case CATCH -> "Pearl Catch";
            };
            int width = this.cw(column, 3);
            this.action(this.col(column, 3), var9, width, var8, font.width(label) + 10 <= width ? label : shortLabel,
               flight == var11.windFlight() ? TrainerPanel.Style.SELECTED : TrainerPanel.Style.NORMAL, () -> {
                  TrainerClient.saveOptions(this.mode, selected);
                  this.refresh();
               }).tip(label);
         }
      }
      boolean var13 = this.matchesLastConfiguration(var1);
      int var14 = this.running ? Math.min(104, this.inner / 3) : 0;
      int var15 = this.running ? this.inner - var14 - this.gap : this.inner;
      if (var12 == Drill.DUEL) {
         TrainingSettings settings = TrainerClient.settings(this.mode);
         int width = Math.min(126, this.inner / 3);
         var15 -= width + this.gap;
         this.action(this.x + var15 + this.gap, var21, width, var5,
            "Gegner · " + settings.duelRank(), TrainerPanel.Style.NORMAL, () -> {
               var current = TrainerClient.settings(this.mode);
               var band = switch (current.duelBand()) {
                  case AUTO -> TrainingSettings.DuelBand.LOW;
                  case LOW -> TrainingSettings.DuelBand.HIGH;
                  case HIGH -> TrainingSettings.DuelBand.AUTO;
               };
               TrainerClient.save(current.withDuelBand(band));
               this.refresh();
            }).tip("Rang wählen · Beide Ränge mischen, nur Low Tier oder nur High Tier trainieren.");
      }
      TrainerPanel.Action var16 = this.action(
         this.x,
         var21,
         var15,
         var5,
         var13 ? "Wiederholen" : "Training starten",
         TrainerPanel.Style.PRIMARY,
         () -> TrainerClient.start(TrainerClient.settings(this.mode))
      );
      var16.active = this.available;
      var16.tip("Runde vorbereiten · Neustart im Spiel: " + TrainerClient.restartKeyLabel().getString());
      if (this.running) {
         this.action(this.right - var14, var21, var14, var5, "Beenden", TrainerPanel.Style.NORMAL, TrainerClient::stop)
            .tip("Training beenden und in deine Welt zurückkehren");
      }

      this.statusY = var21 + var5 + 3;
      if (this.showStatus && this.shownFailure != null) {
         this.addRenderableWidget(new TrainingScreen.FailureNote(this.statusY, this.shownFailure.reason()));
      }

      this.action(
            this.col(0, 4), var18, this.cw(0, 4), var17, "Bot & Arena", TrainerPanel.Style.NORMAL, () -> this.open(new TrainingOptionsScreen(this, this.mode))
         )
         .tip("Bot-Verhalten, Verteidigung und Startposition");
      this.action(this.col(1,4), var18, this.cw(1,4), var17, "Modus-Kits", TrainerPanel.Style.NORMAL,
         () -> this.open(new KitShelfScreen(this, this.mode, false, false))).active = this.minecraft.level != null;
      this.action(this.col(2, 4), var18, this.cw(2, 4), var17, "Analytics", TrainerPanel.Style.NORMAL,
         () -> this.open(new ProgressScreen(this, this.mode))).tip("Lokale Bestleistungen und Fortschritt je Trainingskonfiguration");
      this.action(
            this.col(3, 4), var18, this.cw(3, 4), var17, "Rundenlog", TrainerPanel.Style.NORMAL, () -> this.open(new TrainingScreen.Results(this, this.mode))
         )
         .tip("Ereignisse und Ergebnis der letzten Runden");
   }

   private void addProfiles(DrillOptions var1, int var2, int var3) {
      TrainingSettings var4 = TrainerClient.settings(this.mode);
      if (var1.drill() == Drill.DUEL) {
         var tiers = TrainingSettings.DuelTier.values();
         for (var tier : tiers) {
            int column = tier.ordinal();
            this.action(this.col(column, tiers.length), var2, this.cw(column, tiers.length), var3, tier.name(),
               tier == var4.duelTier() ? TrainerPanel.Style.SELECTED : TrainerPanel.Style.NORMAL, () -> {
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
         return;
      }
      if (var1.drill() == Drill.ELYTRA_SMASH) {
         int[] heights = {16, 32, 64};
         for (int column = 0; column < heights.length; column++) {
            int height = heights[column];
            var field = DrillOptions.Field.START_HEIGHT;
            String label = "Luftstart " + height + " m";
            this.action(this.col(column, 3), var2, this.cw(column, 3), var3, label,
               var1.playerElevation() == height ? TrainerPanel.Style.SELECTED : TrainerPanel.Style.NORMAL, () -> {
                  TrainerClient.saveOptions(this.mode, var1.withIndex(field, field.indexOfNumber(height)));
                  this.refresh();
               }).tip("Deine Starthöhe über dem Arenaboden");
         }
         return;
      }
      TrainingPreset[] var8;
      int var7 = (var8 = TrainingPreset.values()).length;

      for (int var6 = 0; var6 < var7; var6++) {
         TrainingPreset var5 = var8[var6];
         TrainingPreset.Setup var9 = var5.setup(var4, var1.drill());
         DrillOptions baseOptions = var9.options().withIndex(DrillOptions.Field.PEARL_CATCH_ONLY, var1.pearlCatchOnly() ? 1 : 0);
         DrillOptions presetOptions = var1.drill() == Drill.WIND_STUN_SLAM
               ? baseOptions.withIndex(DrillOptions.Field.WIND_FLIGHT, var1.windFlight().ordinal()) : baseOptions;
         boolean var10 = var9.behavior().equals(var4) && presetOptions.equals(var1);
         int var11 = var5.ordinal();
         this.action(this.col(var11, 3), var2, this.cw(var11, 3), var3, var5.label(var1.drill()), var10 ? TrainerPanel.Style.SELECTED : TrainerPanel.Style.NORMAL, () -> {
            TrainerClient.saveSetup(var9.behavior(), presetOptions);
            this.refresh();
         }).tip(var9.detail());
      }
   }

   private DrillHistory.Result latestFailure(TrainingStatus var1) {
      if (var1.active() && var1.drill() == Drill.RETOTEM) {
         return null;
      }

      if (!var1.recent().isEmpty() && var1.mode() == this.mode && var1.drill() == TrainerClient.options(this.mode).drill()) {
         DrillHistory.Result var2 = var1.recent().getLast();
         if (var1.drill() == Drill.RETOTEM && !var2.reason().equals(var1.progress())) {
            return null;
         } else {
            return var2.won() ? null : var2;
         }
      } else {
         return null;
      }
   }

   private String guide(Drill var1, DrillOptions var2) {
      return var1.standardGuide(this.mode, var2, this.minecraft.options.keyJump.getTranslatedKeyMessage().getString());
   }

   private boolean matchesLastConfiguration(TrainingStatus var1) {
      if (this.available && !var1.seriesKey().isEmpty()) {
         RegistryAccess var2 = this.minecraft.level.registryAccess();
         KitLoadout var3 = KitLibrary.personalized(var2, this.mode, KitLibrary.selected(var2, this.mode, false).id());
         String var4 = KitLibrary.fingerprint(var2, var3) + "/" + KitLibrary.fingerprint(var2, KitLibrary.selected(var2, this.mode, true));
         return var1.seriesKey().equals(TrainerClient.seriesKey(this.mode, var4));
      } else {
         return false;
      }
   }

   @Override
   void content(GuiGraphicsExtractor var1) {
      int var2 = this.panelHeight < 175 ? 18 : 22;
      var1.fill(this.x, this.bottom - var2 - 6, this.right, this.bottom - var2 - 5, -12107967);
      if (this.showStatus && this.shownFailure == null) {
         TrainingStatus var3 = TrainerClient.status();
         String var4 = TrainerClient.feedback();
         if (!this.available) {
            var4 = "Zum Trainieren eine eigene Welt öffnen.";
         } else if (var4.isEmpty() && this.running && var3.waitingForStart()) {
            var4 = "Start: Bewegung, Links- oder Rechtsklick";
         } else if (var4.isEmpty() && this.running && var3.drill() == Drill.RETOTEM) {
            var4 = var3.progress();
         } else if (var4.isEmpty() && this.running) {
            var4 = "Runde "
               + var3.rounds()
               + "   ·   "
               + var3.hits()
               + " Treffer   ·   "
               + var3.pops()
               + " Pops   ·   "
               + (int)Math.ceil(var3.health())
               + "/"
               + (int)var3.maxHealth()
               + " LP";
         } else if (var4.isEmpty()) {
            var4 = var3.message();
         }

         this.line(var1, var4, this.x, this.statusY, this.inner, -3819081);
      }
   }

   public void tick() {
      if (this.running != TrainerClient.status().active()
         || this.waiting != TrainerClient.status().waitingForStart()
         || this.available != TrainerClient.available()
         || !Objects.equals(this.shownFailure, this.latestFailure(TrainerClient.status()))
         || this.showStatus != (hasStatus() && this.panelHeight >= 192)) {
         this.refresh();
      }
   }

   private final class DrillCard extends TrainerPanel.Action {
      private final Drill drill;

      DrillCard(int var2, int var3, Drill var4) {
         super(
            TrainingScreen.this.x,
            var2,
            TrainingScreen.this.inner,
            var3,
            "Übung wählen",
            var4.label(),
            ItemStack.EMPTY,
            TrainerPanel.Style.NORMAL,
            () -> TrainingScreen.this.open(new TrainingScreen.DrillPicker(TrainingScreen.this, TrainingScreen.this.mode))
         );
         this.drill = var4;
         String var5 = TrainingScreen.this.guide(var4, TrainerClient.options(TrainingScreen.this.mode));
         this.setMessage(Component.literal("Übung wählen · " + var4.label() + ". Standardkit · " + var5));
         int var6 = Math.max(2, Math.min(6, (var3 - 34) / 4)) + 9 + 4;
         if (var3 >= 59) {
            var6 += 9 + 2;
         }

         int var7 = TrainingScreen.this.font.split(Component.literal(var5), this.getWidth() - 14).size();
         if (var6 + var7 * (9 + 1) > var3) {
            this.tip(var5);
         }
      }

      @Override
      protected void extractContents(GuiGraphicsExtractor var1, int var2, int var3, float var4) {
         int var5 = this.getX();
         int var6 = this.getY();
         int var7 = this.getWidth();
         int var8 = this.getHeight();
         boolean var9 = this.isHoveredOrFocused();
         var1.fillGradient(var5, var6, this.getRight(), this.getBottom(), var9 ? -12963533 : -13358034, -14014678);
         if (var9) {
            var1.outline(var5, var6, var7, var8, -593428);
         }

         var1.fill(var5, var6 + 1, var5 + 2, this.getBottom() - 1, -31624);
         int var10 = var5 + 7;
         int var11 = var6 + Math.max(2, Math.min(6, (var8 - 34) / 4));
         MutableComponent var12 = Component.literal(this.drill.label()).withStyle(ChatFormatting.BOLD);
         var1.text(TrainingScreen.this.font, var12, var10, var11, -593428, false);
         String var13 = "Wechseln ›";
         var1.text(TrainingScreen.this.font, var13, this.getRight() - 7 - TrainingScreen.this.font.width(var13), var11, -31624, false);
         int var14 = var11 + 9 + 4;
         if (var8 >= 59) {
            TrainingScreen.this.line(var1, "Standardkit · Hotbar 1–9", var10, var14, var7 - 14, -3819081);
            var14 += 9 + 2;
         }

         String var15 = TrainingScreen.this.guide(this.drill, TrainerClient.options(TrainingScreen.this.mode));

         for (FormattedCharSequence var17 : TrainingScreen.this.font.split(Component.literal(var15), var7 - 14)) {
            if (var14 + 9 > this.getBottom() - 1) {
               break;
            }

            var1.text(TrainingScreen.this.font, var17, var10, var14, -3819081, false);
            var14 += 9 + 1;
         }
      }
   }

   private static final class DrillPicker extends TrainerPanel {
      private int specialY;
      private int freeY;

      DrillPicker(TrainingScreen var1, TrainingSettings.Mode var2) {
         super(var1, "Übung wählen", var2);
      }

      @Override
      void build() {
         this.subtitle = this.mode.label();
         List<Drill> var1 = List.of(Drill.PASSIVE, Drill.TOTEM_POPS, Drill.SHIELD, Drill.DUEL)
            .stream().filter(drill -> drill.supports(this.mode)).toList();
         List var2 = Drill.forMode(this.mode).stream().filter(var1x -> !var1.contains(var1x)).toList();
         int var3 = (var2.size() + 1) / 2 + (var1.size() + 1) / 2;
         int var4 = Math.min(33, (this.bottom - this.y - 28 - var3 * this.gap) / var3);
         this.specialY = this.y;
         this.group(var2, this.specialY + 13, var4);
         this.freeY = this.specialY + 16 + (var2.size() + 1) / 2 * (var4 + this.gap);
         this.group(var1, this.freeY + 13, var4);
      }

      private void group(List<Drill> var1, int var2, int var3) {
         for (int var4 = 0; var4 < var1.size(); var4++) {
            Drill var5 = (Drill)var1.get(var4);
            boolean var6 = var5 == TrainerClient.options(this.mode).drill();
            this.action(
                  this.col(var4 % 2, 2),
                  var2 + var4 / 2 * (var3 + this.gap),
                  this.cw(var4 % 2, 2),
                  var3,
                  var5.label(),
                  var6 ? TrainerPanel.Style.SELECTED : TrainerPanel.Style.NORMAL,
                  () -> {
                     if (!var6) {
                        TrainerClient.saveSetup(TrainerClient.settings(this.mode).forDrill(var5), DrillOptions.forDrill(var5));
                     }

                     this.onClose();
                  }
               )
               .tip(
                  var5.standardGuide(
                     this.mode,
                     var6 ? TrainerClient.options(this.mode) : DrillOptions.forDrill(var5),
                     this.minecraft.options.keyJump.getTranslatedKeyMessage().getString()
                  )
               );
         }
      }

      @Override
      void content(GuiGraphicsExtractor var1) {
         this.line(var1, "Technikübungen", this.x, this.specialY + 1, this.inner, -3819081);
         this.line(var1, "Freies Training", this.x, this.freeY + 1, this.inner, -3819081);
      }
   }

   private final class FailureNote extends TrainerPanel.Action {
      private final String reason;

      FailureNote(int var2, String var3) {
         super(
            TrainingScreen.this.x,
            var2,
            TrainingScreen.this.inner,
            21,
            "Letzter Fehlversuch",
            var3,
            ItemStack.EMPTY,
            TrainerPanel.Style.NORMAL,
            () -> TrainingScreen.this.open(new TrainingScreen.Results(TrainingScreen.this, TrainingScreen.this.mode, true))
         );
         this.reason = var3;
         this.tip(var3);
      }

      @Override
      protected void extractContents(GuiGraphicsExtractor var1, int var2, int var3, float var4) {
         int var5 = this.getY() + 1;
         if (this.isHoveredOrFocused()) {
            var1.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), -12963533);
         }

         MutableComponent var6 = Component.literal("Fehlversuch · ")
            .withStyle(var0 -> var0.withColor(-31624))
            .append(Component.literal(this.reason).withStyle(var0 -> var0.withColor(-593428)));
         List var7 = TrainingScreen.this.font.split(var6, this.getWidth() - 10);

         for (int var8 = 0; var8 < Math.min(2, var7.size()); var8++) {
            var1.text(TrainingScreen.this.font, (FormattedCharSequence)var7.get(var8), this.getX() + 3, var5 + var8 * 10, -593428, false);
         }
      }
   }

   private static final class Results extends TrainerPanel {
      private int page;
      private int selected = -1;
      private int reasonY;
      private int lineTop;
      private int pageCount;
      private int linesPerPage;
      private List<FormattedCharSequence> reasonRows = List.of();
      private List<String> lines = List.of();
      private List<DrillHistory.Result> recent = List.of();
      private String reason;
      private String commonIssue = "";
      private boolean active;
      private String median = "–";

      Results(TrainingScreen var1, TrainingSettings.Mode var2) {
         this(var1, var2, false);
      }

      Results(TrainingScreen var1, TrainingSettings.Mode var2, boolean var3) {
         super(var1, "Rundenlog", var2);
         if (var3) {
            this.selected = TrainerClient.status().recent().size() - 1;
         }
      }

      @Override
      void build() {
         TrainingStatus var1 = TrainerClient.status();
         this.active = var1.active();
         this.recent = var1.recent();
         this.subtitle = var1.exercise().isEmpty() ? "Letzte Übung" : var1.exercise();
         if (this.selected >= this.recent.size()) {
            this.selected = -1;
         }

         int[] var2 = this.recent.stream().filter(DrillHistory.Result::won).mapToInt(DrillHistory.Result::ticks).sorted().toArray();
         if (var2.length == 0) {
            this.median = "–";
         } else {
            double var3 = (var2[(var2.length - 1) / 2] + var2[var2.length / 2]) / 2.0;
            this.median = (var3 == (int)var3 ? Integer.toString((int)var3) : String.format(Locale.GERMAN, "%.1f", var3)) + " Ticks";
         }

         LinkedHashMap<String, Integer> var12 = new LinkedHashMap<>();
         this.recent.stream().filter(var0 -> !var0.won()).forEach(var1x -> var12.merge(var1x.reason(), 1, Integer::sum));
         this.commonIssue = var12.entrySet()
            .stream()
            .max(Entry.comparingByValue())
            .map(var0 -> "Häufigster Fehler · " + var0.getValue() + "× " + (String)var0.getKey())
            .orElse("");
         int var4 = this.y + 31 + this.gap;
         int var5 = Math.min(74, this.inner / 5);
         this.action(
            this.x,
            var4,
            var5,
            18,
            this.active ? "Live" : "Zuletzt",
            this.selected < 0 ? TrainerPanel.Style.SELECTED : TrainerPanel.Style.NORMAL,
            () -> this.select(-1)
         );
         int var6 = this.x + var5 + this.gap;
         int var7 = Math.min(31, (this.right - var6 + 2) / 10);

         for (int var8 = 0; var8 < this.recent.size(); var8++) {
            int var9 = var8;
            DrillHistory.Result var10 = this.recent.get(var8);
            TrainerPanel.Action var11 = this.action(
               var6 + var7 * var8,
               var4,
               var7 - 2,
               18,
               Integer.toString(var8 + 1),
               this.selected == var8 ? TrainerPanel.Style.SELECTED : TrainerPanel.Style.NORMAL,
               () -> this.select(var9)
            );
            var11.tip("Versuch " + (var8 + 1) + " · " + (var10.won() ? "Erfolg" : "Fehlversuch") + " · " + var10.ticks() + " Ticks");
         }

         if (this.selected < 0) {
            this.lines = var1.timeline();
            this.reason = var1.progress();
         } else {
            DrillHistory.Result var13 = this.recent.get(this.selected);
            this.lines = var13.timeline();
            this.reason = (var13.won() ? "Erfolg" : "Fehlversuch") + " · " + var13.reason() + " · " + var13.ticks() + " Ticks";
         }

         this.reasonY = var4 + 22;
         this.reasonRows = this.font.split(Component.literal(this.reason), this.inner);
         this.lineTop = this.reasonY + 5 + 10 * Math.min(3, Math.max(1, this.reasonRows.size()));
         this.linesPerPage = Math.max(1, (this.bottom - 35 - this.lineTop) / 12);
         this.pageCount = Math.max(1, (this.lines.size() + this.linesPerPage - 1) / this.linesPerPage);
         this.page = Math.min(this.page, this.pageCount - 1);
         this.action(this.x, this.bottom - 18, 34, 18, "‹", TrainerPanel.Style.NORMAL, () -> {
            this.page--;
            this.refresh();
         }).active = this.page > 0;
         this.action(this.right - 34, this.bottom - 18, 34, 18, "›", TrainerPanel.Style.NORMAL, () -> {
            this.page++;
            this.refresh();
         }).active = this.page + 1 < this.pageCount;
      }

      private void select(int var1) {
         this.selected = var1;
         this.page = 0;
         this.refresh();
      }

      @Override
      void content(GuiGraphicsExtractor var1) {
         TrainingStatus var2 = TrainerClient.status();
         String var3 = var2.attempts() == 0 ? "–" : Math.round(100.0F * var2.successes() / var2.attempts()) + " %";
         String[] var4 = new String[]{"Serie", "Erfolgsquote", "Median · Erfolge"};
         String[] var5 = new String[]{var2.successes() + "/" + var2.attempts(), var3, this.median};

         for (int var6 = 0; var6 < var4.length; var6++) {
            int var7 = this.col(var6, 3);
            int var8 = this.cw(var6, 3);
            var1.fill(var7, this.y, var7 + var8, this.y + 30, this.shade());
            this.line(var1, var4[var6], var7 + 5, this.y + 4, var8 - 10, -3819081);
            this.line(var1, var5[var6], var7 + 5, this.y + 17, var8 - 10, -593428);
         }

         for (int var9 = 0; var9 < Math.min(3, this.reasonRows.size()); var9++) {
            var1.text(this.font, this.reasonRows.get(var9), this.x, this.reasonY + 10 * var9, -593428, false);
         }

         if (this.lines.isEmpty()) {
            this.line(var1, "Hier erscheinen die Ereignisse des Versuchs.", this.x, this.lineTop, this.inner, -3819081);
         }

         for (int var10 = 0; var10 < this.linesPerPage && this.page * this.linesPerPage + var10 < this.lines.size(); var10++) {
            this.line(var1, this.lines.get(this.page * this.linesPerPage + var10), this.x, this.lineTop + var10 * 12, this.inner, -3819081);
         }

         if (!this.commonIssue.isEmpty()) {
            this.line(var1, this.commonIssue, this.x, this.bottom - 30, this.inner, -12646);
         }

         this.line(var1, "Ereignisse · " + (this.page + 1) + "/" + this.pageCount, this.x + 41, this.bottom - 13, this.inner - 82, -3819081);
      }

      @Override
      public void extractRenderState(GuiGraphicsExtractor var1, int var2, int var3, float var4) {
         super.extractRenderState(var1, var2, var3, var4);
         if (var2 >= this.x && var2 < this.right) {
            int var5 = (var3 - this.lineTop) / 12;
            String var6 = "";
            if (var3 >= this.lineTop && var5 < this.linesPerPage && this.page * this.linesPerPage + var5 < this.lines.size()) {
               var6 = this.lines.get(this.page * this.linesPerPage + var5);
            }

            if (var3 >= this.reasonY && var3 < this.lineTop) {
               var6 = this.reason;
            }

            if (var3 >= this.bottom - 32 && var3 < this.bottom - 21) {
               var6 = this.commonIssue;
            }

            if (!var6.isEmpty() && this.font.width(var6) > this.inner) {
               var1.setTooltipForNextFrame(Component.literal(var6), var2, var3);
            }
         }
      }

      public void tick() {
         TrainingStatus var1 = TrainerClient.status();
         if (this.active != var1.active() || !this.recent.equals(var1.recent()) || this.selected < 0 && !this.lines.equals(var1.timeline())) {
            this.refresh();
         }
      }
   }
}

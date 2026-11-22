package de.pvptrainer.ui;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.progress.HudPreferences;
import de.pvptrainer.TrainerClient;
import de.pvptrainer.progress.TrainingProgress;
import de.pvptrainer.progress.UpdateChecker;
import de.pvptrainer.kits.TrainingSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

final class TrainingOptionsScreen extends TrainerPanel {
   private TrainingOptionsScreen.Group group;
   private int scenesLabelY;
   private int page;

   TrainingOptionsScreen(TrainingScreen var1, TrainingSettings.Mode var2) {
      super(var1, "Bot & Arena", var2);
      this.group = TrainerClient.options(var2).drill().slamOptions() ? TrainingOptionsScreen.Group.SCENE : TrainingOptionsScreen.Group.BOT;
   }

   @Override
   void build() {
      TrainingSettings var1 = TrainerClient.settings(this.mode);
      DrillOptions var2 = TrainerClient.options(this.mode);
      this.subtitle = var2.drill().label();
      byte var3 = 22;
      TrainingOptionsScreen.Group[] var7;
      int var6 = (var7 = TrainingOptionsScreen.Group.values()).length;

      for (int var5 = 0; var5 < var6; var5++) {
         TrainingOptionsScreen.Group var4 = var7[var5];
         int var8 = var4.ordinal();
         this.action(
            this.col(var8, 3),
            this.y,
            this.cw(var8, 3),
            var3,
            var4.label,
            var4 == this.group ? TrainerPanel.Style.SELECTED : TrainerPanel.Style.NORMAL,
            () -> {
               this.group = var4;
               this.page = 0;
               this.refresh();
            }
         );
      }

      int var13 = this.y + var3 + this.gap;
      this.scenesLabelY = -1;
      if (this.group == TrainingOptionsScreen.Group.SCENE && var2.drill().slamOptions()) {
         this.scenesLabelY = var13 + 1;
         this.addSlamScenes(var2, var13 + 12);
         var13 += 12 + 2 * (18 + this.gap);
      }

      boolean var14 = var2.slamScene() != DrillOptions.SlamScene.FREE || var2.drill() == Drill.LOW_WEB_SMASH || var2.drill() == Drill.PERFECT_WIND_CHARGE;
      ArrayList<Option> var15 = new ArrayList<>();
      if (this.group == TrainingOptionsScreen.Group.SCENE) {
         var15.add(new Option("Hinweise (global)", on(HudPreferences.hints()), () -> {
            HudPreferences.toggleHints();
            if (!HudPreferences.hints()) TrainingProgress.hideResultToast();
            this.refresh();
         }));
         var15.add(new Option("Update-Hinweise", on(HudPreferences.updateCheck()), () -> {
            HudPreferences.toggleUpdateCheck();
            if (HudPreferences.updateCheck()) UpdateChecker.checkAsync();
            else UpdateChecker.clear();
            this.refresh();
         }));
      }
      if (this.group == TrainingOptionsScreen.Group.BOT) {
         if (this.mode == TrainingSettings.Mode.MACE && !var2.drill().centeredMaceDrill()) {
            var15.add(this.base("Arenamitte fixieren", on(var1.maceCenterLocked()),
               () -> TrainerClient.settings(this.mode).withMaceCenterLocked(!var1.maceCenterLocked())));
         }
         if (var2.drill() == Drill.DUEL) {
            var15.add(this.base("Duell-Tier", var1.duelTier().name(), () -> {
               var settings = TrainerClient.settings(this.mode);
               return settings.withDuelTier(next(TrainingSettings.DuelTier.values(), settings.duelTier()));
            }));
         } else {
            var15.add(this.base("Schwierigkeit", var1.difficulty().label(), () -> TrainerClient.settings(this.mode).withDifficulty(next(TrainingSettings.Difficulty.values(), TrainerClient.settings(this.mode).difficulty()))));
         }
         if (var2.drill().totemDefense()) {
            boolean var16 = var1.movement() == TrainingSettings.Movement.STRAFE;
            var15.add(
               this.base(
                  "Bewegung",
                  var16 ? "Seitwärts" : "Herantreten",
                  () -> TrainerClient.settings(this.mode).withMovement(var16 ? TrainingSettings.Movement.FOLLOW : TrainingSettings.Movement.STRAFE)
               )
            );
         } else if (!var14 && !(var2.drill() == Drill.WIND_STUN_SLAM ? var2.windCenter() : var1.maceCenterLocked())) {
            var15.add(this.base("Bewegung", var1.movement().label(), () -> TrainerClient.settings(this.mode).withMovement(next(TrainingSettings.Movement.values(), var1.movement()))));
         }

         var15.add(new Option("Bot-Leben", "", null, 6, Math.max(0, Math.min(5, var1.maxHealth() / 10 - 1)),
            step -> (step + 1) * 5 + " Herzen",
            step -> TrainerClient.save(TrainerClient.settings(this.mode).withMaxHealth((step + 1) * 10))));
         if (!var14 && var2.drill() != Drill.PASSIVE && !var2.drill().needsSwordAttack() && !var2.drill().totemDefense()) {
            var15.add(this.base("Gegenangriffe", on(var1.counterAttacks()), () -> TrainerClient.settings(this.mode).withCounterAttacks(!var1.counterAttacks())));
         }

         var15.add(this.base("Auto-Respawn", on(var1.autoRespawn()), () -> TrainerClient.settings(this.mode).withAutoRespawn(!var1.autoRespawn())));
      }

      for (DrillOptions.Field var17 : this.orderedFields(var2.drill())) {
         if (TrainingOptionsScreen.Group.of(var17) == this.group && var17.visible(var2, this.mode) && usefulForExercise(var17, var2.drill())) {
            if (numeric(var17)) {
               var15.add(new Option(var17.label(), "", null, var17.size(), var2.sliderStep(var17),
                  step -> TrainerClient.options(this.mode).withIndex(var17, var17.indexForSliderStep(step)).value(var17),
                  step -> TrainerClient.saveOptions(this.mode, TrainerClient.options(this.mode).withIndex(var17, var17.indexForSliderStep(step)))));
               continue;
            }
            String label = this.mode == TrainingSettings.Mode.MACE && var17 == DrillOptions.Field.REPLACE
                  ? "Schild bei Verschleiß" : var17.label();
            var15.add(new TrainingOptionsScreen.Option(label, var2.value(var17, this.mode), () -> {
               TrainerClient.saveOptions(this.mode, TrainerClient.options(this.mode).next(var17, this.mode));
               this.refresh();
            }));
         }
      }

      int var18 = Math.max(2, Math.min(4, (this.bottom - 24 - var13) / (28 + this.gap)));
      int var20 = Math.min(42, (this.bottom - 24 - var13 - (var18 - 1) * this.gap) / var18);
      int var9 = var18 * 2;
      int var10 = Math.max(1, (var15.size() + var9 - 1) / var9);
      this.page = Math.min(this.page, var10 - 1);

      for (int var11 = 0; var11 < var9 && this.page * var9 + var11 < var15.size(); var11++) {
         TrainingOptionsScreen.Option var12 = (TrainingOptionsScreen.Option)var15.get(this.page * var9 + var11);
         if (var12.steps() > 1) {
            var slider = new SettingSlider(this.col(var11 % 2, 2), var13 + var11 / 2 * (var20 + this.gap),
               this.cw(var11 % 2, 2), var20, var12.label(), var12.steps(), var12.initial(), var12.format(), var12.apply());
            if (var12.label().equals(DrillOptions.Field.SHIELD_BREAK_TICKS.label())) {
               slider.setTooltip(net.minecraft.client.gui.components.Tooltip.create(net.minecraft.network.chat.Component.literal(
                  "Zeit bis zur nächsten wirksamen Deckung nach Axt-Stun oder Schildersatz. 20 Ticks = 1 Sekunde. Standard verwendet das normale Spielverhalten.")));
            } else if (var12.label().equals(DrillOptions.Field.PLAYER_PING.label()) || var12.label().equals(DrillOptions.Field.BOT_PING.label())) {
               slider.setTooltip(net.minecraft.client.gui.components.Tooltip.create(net.minecraft.network.chat.Component.literal("Zusätzliche Hin- und Rücklaufzeit. 0 ms ist das normale lokale Spiel.")));
            } else if (var12.label().equals(DrillOptions.Field.TICK_RATE.label())) {
               slider.setTooltip(net.minecraft.client.gui.components.Tooltip.create(net.minecraft.network.chat.Component.literal(
                  "Gilt vom Start bis zum Ende des Versuchs für die gesamte lokale Welt. 20 TPS = normales Tempo, 10 TPS = halbes Tempo.")));
            } else if (var12.label().equals(DrillOptions.Field.BOT_REACH.label())) {
               slider.setTooltip(net.minecraft.client.gui.components.Tooltip.create(net.minecraft.network.chat.Component.literal(
                  "Obergrenze für Nahkampftreffer des Bots, gemessen von den Augen bis zu deiner Trefferbox.")));
            }
            this.addRenderableWidget(slider);
            continue;
         }
         var tile = this.tile(
            this.col(var11 % 2, 2),
            var13 + var11 / 2 * (var20 + this.gap),
            this.cw(var11 % 2, 2),
            var20,
            var12.label(),
            var12.value(),
            ItemStack.EMPTY,
            TrainerPanel.Style.NORMAL,
            var12.next()
         );
         if (var12.label().equals("Hinweise (global)")) {
            tile.tip("Übungstipps und Einblendungen oben rechts für alle Modi und Welten dieses Profils.");
         } else if (var12.label().equals("Update-Hinweise")) {
            tile.tip("Prüft beim Spielstart einmalig auf neue MacherBot-Releases (ohne automatischen Download).");
         } else if (var12.label().equals(DrillOptions.Field.PEARL_CATCH_ONLY.label())) {
            tile.tip("Nur eine in der Luft mit deiner eigenen Windladung gefangene Perle teleportiert dich.");
         } else if (var12.label().equals(DrillOptions.Field.TOTEMS.label())) {
            tile.tip("Unbegrenzt schützt das Übungsziel auch ohne Kit-Totem. Im freien Duell gilt der Kit-Vorrat als Voraussetzung. Doublehand ist Crystal-Kits mit mindestens zwei Totems vorbehalten.");
         } else if (var12.label().equals(DrillOptions.Field.WIND_FLIGHT.label())) {
            tile.tip("Kombiniert startet der Bot mit einer Windladung vom Boden und fängt im selben Flug seine Enderperle. Beim reinen Pearl Catch wirft er die Perle vom Boden aus.");
         } else if (var12.label().equals(DrillOptions.Field.ITEM_CONSUMPTION.label())) {
            tile.tip(this.mode == TrainingSettings.Mode.MACE || this.mode == TrainingSettings.Mode.CRYSTAL
               ? "Gilt für beide Kits. Totems werden bei jedem Pop verbraucht. Auto-Totem regelt das Nachfüllen beim Bot."
               : "Regelt den Verbrauch von Vorräten wie Pfeilen, Netzen und Heilitems für dich und den Bot.");
         } else if (var12.label().equals(DrillOptions.Field.ARMOR_WEAR.label())) {
            tile.tip("Bestimmt, ob die getragene Rüstung von dir und dem Bot Haltbarkeit verliert.");
         } else if (var12.label().equals(DrillOptions.Field.FALL_DAMAGE.label())) {
            tile.tip("Bestimmt den Landeschaden für dich und den Bot. Die für einen Mace-Smash gemessene Fallstrecke bleibt erhalten.");
         }
      }

      if (var10 > 1) {
         this.action(this.x, this.bottom - 19, 35, 19, "‹", TrainerPanel.Style.NORMAL, () -> {
            this.page--;
            this.refresh();
         }).active = this.page > 0;
         this.action(this.right - 35, this.bottom - 19, 35, 19, "›", TrainerPanel.Style.NORMAL, () -> {
            this.page++;
            this.refresh();
         }).active = this.page + 1 < var10;
         this.action(
            this.x + 35 + this.gap,
            this.bottom - 19,
            this.inner - 70 - 2 * this.gap,
            19,
            "Fertig  ·  " + (this.page + 1) + "/" + var10,
            TrainerPanel.Style.NORMAL,
            this::onClose
         );
      } else {
         this.action(this.x, this.bottom - 19, this.inner, 19, "Fertig", TrainerPanel.Style.NORMAL, this::onClose);
      }
   }

   private void addSlamScenes(DrillOptions var1, int var2) {
      DrillOptions.SlamScene[] var6;
      int var5 = (var6 = DrillOptions.SlamScene.values()).length;

      for (int var4 = 0; var4 < var5; var4++) {
         DrillOptions.SlamScene var3 = var6[var4];
         int var7 = var3.ordinal();
         DrillOptions var8 = var1.selectSlamScene(var3);
         this.action(
               this.col(var7 % 2, 2),
               var2 + var7 / 2 * (18 + this.gap),
               this.cw(var7 % 2, 2),
               18,
               var1.drill() == Drill.WIND_STUN_SLAM ? switch (var3) {
                  case FREE -> "Standard"; case GROUND_LOCK -> "Bodenstart"; case AIR_LOCK -> "Erhöhte Basis"; case PAIRED_FALL -> "Fall aus Zielhöhe";
               } : var8.value(DrillOptions.Field.SLAM_SCENE),
               var3 == var1.slamScene() ? TrainerPanel.Style.SELECTED : TrainerPanel.Style.NORMAL,
               () -> {
                  TrainerClient.saveOptions(this.mode, TrainerClient.options(this.mode).selectSlamScene(var3));
                  this.refresh();
               }
            )
            .tip(this.sceneHint(var3));
      }
   }

   private String sceneHint(DrillOptions.SlamScene var1) {
      if (TrainerClient.options(mode).drill() == Drill.WIND_STUN_SLAM) return switch (var1) {
         case FREE, GROUND_LOCK -> "Der Bot springt vom Arenaboden. Du bestimmst deinen Fallstart.";
         case AIR_LOCK -> "Der Bot springt von einer erhöhten Plattform. Stelle deine Höhe relativ zur Basis ein.";
         case PAIRED_FALL -> "Der Bot fällt sofort aus der Zielhöhe und springt vom Boden weiter. Dein Fall beginnt bei Bewegung.";
      };
      return switch (var1) {
         case FREE -> TrainerClient.settings(this.mode).maceCenterLocked()
            ? "Der Bot bleibt durch die gewählte Fixierung in der Arenamitte."
            : "Bewegung und Rückstoß des Bots gelten wie im Duell.";
         case GROUND_LOCK -> "Das Bodenziel bleibt am Platz. Bewege dich, um den Fall zu starten.";
         case AIR_LOCK -> "Das Ziel schwebt. Wähle Startposition und Zielhöhe. Bewegen oder klicken startet den Versuch.";
         case PAIRED_FALL -> TrainerClient.settings(this.mode).maceCenterLocked()
            ? "Beim gemeinsamen Fall sinkt der Bot senkrecht über der Arenamitte."
            : "Ihr schwebt in eurer Startposition. Bewege dich für den gemeinsamen Fall.";
      };
   }

   private List<DrillOptions.Field> orderedFields(Drill var1) {
      if (this.group != TrainingOptionsScreen.Group.SCENE) {
         return List.of(DrillOptions.Field.values());
      }

      List var2 = switch (var1) {
         case HIT_CRYSTAL, D_TAP, TRIPLE_TAP, TAP_ANCHOR -> List.of(DrillOptions.Field.BASE, DrillOptions.Field.CRYSTAL_AIR, DrillOptions.Field.TERRAIN);
         case LEDGE_DASH -> List.of(DrillOptions.Field.LEDGE, DrillOptions.Field.BASE);
         case SAFE_ANCHOR, HEAL -> List.of(DrillOptions.Field.COVER);
         case RETOTEM, DOUBLE_HAND -> List.of(DrillOptions.Field.BURST_PATTERN, DrillOptions.Field.REACTION);
         case SMASH, WIND_CHAIN, BACKSTAB, PEARL_CATCH, ELYTRA_SMASH -> List.of(DrillOptions.Field.START_HEIGHT, DrillOptions.Field.SIDE_OFFSET);
         case LOW_WEB_SMASH -> List.of(DrillOptions.Field.START_HEIGHT);
         case WIND_STUN_SLAM, STUN_SLAM -> List.of(
            DrillOptions.Field.SLAM_POSITION, DrillOptions.Field.TARGET_HEIGHT, DrillOptions.Field.HEIGHT_OFFSET, DrillOptions.Field.START_HEIGHT
         );
         default -> List.of();
         case SPACING -> List.of(DrillOptions.Field.COMBO_LENGTH);
         case WEB_DRAIN, RE_LAVA -> List.of(DrillOptions.Field.REACTION);
      };
      ArrayList var3 = new ArrayList(var2);
      if (this.mode == TrainingSettings.Mode.MACE) var3.add(0, DrillOptions.Field.PEARL_CATCH_ONLY);
      var3.add(DrillOptions.Field.TICK_RATE);
      var3.add(DrillOptions.Field.PLAYER_PING);
      var3.add(DrillOptions.Field.BOT_PING);
      DrillOptions.Field[] var7;
      int var6 = (var7 = DrillOptions.Field.values()).length;

      for (int var5 = 0; var5 < var6; var5++) {
         DrillOptions.Field var4 = var7[var5];
         if (!var3.contains(var4)) {
            var3.add(var4);
         }
      }

      return var3;
   }

   private static boolean usefulForExercise(DrillOptions.Field var0, Drill var1) {
      return switch (var0) {
         case VARIABLE -> var1.needsSwordAttack() || var1.totemDefense() || var1 == Drill.WEB_DRAIN || var1 == Drill.RE_LAVA;
         case WINDOW -> {
            switch (var1) {
               case HIT_CRYSTAL:
               case D_TAP:
               case TRIPLE_TAP:
               case TAP_ANCHOR:
               case SMASH:
               case WIND_CHAIN:
               case PEARL_CATCH:
               case ELYTRA_SMASH:
               case HIT_SELECT:
               case PUNISH_CRIT:
                  yield true;
               case LEDGE_DASH:
               case SAFE_ANCHOR:
               case RETOTEM:
               case DOUBLE_HAND:
               case STUN_SLAM:
               case BACKSTAB:
               case SHIELD_DRAIN:
               case SPACING:
               default:
                  yield false;
            }
         }
         case SLAM_SCENE -> false;
         case BURST_PATTERN -> var1.totemDefense();
         default -> true;
      };
   }

   @Override
   void content(GuiGraphicsExtractor var1) {
      if (this.scenesLabelY >= 0) {
         DrillOptions var2 = TrainerClient.options(this.mode);
         String var3 = "Du " + var2.playerElevation() + " Bl. · Bot " + var2.botElevation() + " Bl.";
         int var4 = this.font.width(var3);
         this.line(var1, "Stun-Slam-Szene", this.x, this.scenesLabelY, this.inner - var4 - this.gap, -3819081);
         this.line(var1, var3, this.right - var4, this.scenesLabelY, var4, -3819081);
      }
   }

   private TrainingOptionsScreen.Option base(String var1, String var2, Supplier<TrainingSettings> var3) {
      return new TrainingOptionsScreen.Option(var1, var2, () -> {
         TrainerClient.save((TrainingSettings)var3.get());
         this.refresh();
      });
   }

   private static <T extends Enum<T>> T next(T[] var0, T var1) {
      return (T)var0[(var1.ordinal() + 1) % var0.length];
   }

   private static String on(boolean var0) {
      return var0 ? "Ein" : "Aus";
   }

   private enum Group {
      BOT("Verhalten"),
      DEFENSE("Verteidigung"),
      SCENE("Szene");

      final String label;

      Group(String var3) {
         this.label = var3;
      }

      static TrainingOptionsScreen.Group of(DrillOptions.Field var0) {
         return switch (var0) {
            case GAZE, TURN_RATE, BOT_REACH, WIND_CENTER, WIND_FLIGHT -> BOT;
            case TOTEMS, REFILL, RESET_POP, SHIELD, REPLACE, REBLOCK, DURABILITY, SHIELD_BREAK_TICKS, ARMOR_WEAR, ITEM_CONSUMPTION, FALL_DAMAGE -> DEFENSE;
            default -> SCENE;
         };
      }
   }

   private static boolean numeric(DrillOptions.Field field) {
      return switch (field) {
         case TURN_RATE, REFILL, DURABILITY, DISTANCE, ANGLE, REACTION, WINDOW, LEDGE, START_HEIGHT, SIDE_OFFSET, START_HEALTH, TARGET_HEIGHT, COMBO_LENGTH, HEIGHT_OFFSET, SHIELD_BREAK_TICKS, BOT_REACH, TICK_RATE, PLAYER_PING, BOT_PING -> true;
         default -> false;
      };
   }

   private record Option(String label, String value, Runnable next, int steps, int initial,
                         IntFunction<String> format, IntConsumer apply) {
      Option(String label, String value, Runnable next) { this(label, value, next, 0, 0, null, null); }

   }
}

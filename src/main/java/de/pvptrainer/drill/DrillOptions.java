package de.pvptrainer.drill;
import de.pvptrainer.progress.HudPreferences;
import de.pvptrainer.kits.TrainingSettings;

import java.util.EnumMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public record DrillOptions(Drill drill, Map<DrillOptions.Field, Integer> overrides) {
   public static final int DEFAULT_FLIGHT_HEIGHT = 32;
   public DrillOptions(Drill drill, Map<DrillOptions.Field, Integer> overrides) {
      Objects.requireNonNull(drill);
      EnumMap var3 = new EnumMap<>(DrillOptions.Field.class);
      if (overrides != null) {
         overrides.forEach(
            (var2, var3x) -> {
               if (var2 != null && var3x != null && var3x >= 0 && var3x < var2.size()) {
                  if (var2 == Field.HINTS && drill != Drill.JUMP_RESET) return;
                  if (var2 == Field.ARENA_DIFFICULTY) return;
                  if ((drill == Drill.LOW_WEB_SMASH || drill == Drill.SHIELD_TRIPLE_TAP || drill == Drill.MACE_COUNTER_DTAP) && var2 == Field.SHIELD) return;
                  if (drill.requiresPopChain() && (var2 == Field.CRYSTAL_AIR || var2 == Field.RESET_POP || var2 == Field.TOTEMS && var3x == 0)) {
                     return;
                  }
                  if (var2 != DrillOptions.Field.SHIELD || !drill.needsShield() && !drill.totemDefense()) {
                     if (var2 != DrillOptions.Field.REBLOCK || !requiresStunWindow(drill)) {
                        if (var2 == DrillOptions.Field.TARGET_HEIGHT
                           || var2 == DrillOptions.Field.SLAM_POSITION
                           || var2 == DrillOptions.Field.HEIGHT_OFFSET
                           || var2 == Field.ITEM_CONSUMPTION || var3x != initial(drill, var2)) {
                           var3.put(var2, var3x);
                        }
                     }
                  }
               }
            }
         );
      }

      overrides = Map.copyOf(var3);
      this.drill = drill;
      this.overrides = overrides;
   }

   public DrillOptions selectSlamScene(DrillOptions.SlamScene var1) {
      return var1 == this.slamScene() ? this : this.withIndex(DrillOptions.Field.SLAM_SCENE, var1.ordinal());
   }

   public static DrillOptions defaults(TrainingSettings.Mode var0) {
      return forDrill(Drill.PASSIVE);
   }

   public static DrillOptions forDrill(Drill var0) {
      return new DrillOptions(var0, Map.of());
   }

   /** Copies sticky arena/session modifiers onto a target drill configuration within the same mode. */
   public DrillOptions carryModifiersTo(DrillOptions target, TrainingSettings.Mode mode) {
      DrillOptions updated = carryCrossModeModifiersTo(target)
         .withIndex(Field.ITEM_CONSUMPTION, this.itemConsumption(mode) ? 0 : 1);
      if (mode == TrainingSettings.Mode.MACE) {
         updated = updated.withIndex(Field.PEARL_CATCH_ONLY, this.pearlCatchOnly() ? 1 : 0);
      }
      return updated;
   }

   /** Copies universal environment modifiers when switching between game modes. */
   public DrillOptions carryCrossModeModifiersTo(DrillOptions target) {
      return target
         .withIndex(Field.TICK_RATE, Field.TICK_RATE.indexOfNumber(this.tickRate()))
         .withIndex(Field.ARENA_SIZE, this.largeArena() ? 1 : 0)
         .withIndex(Field.ZERO_DAMAGE, this.zeroDamage().ordinal())
         .withIndex(Field.PLAYER_PING, Field.PLAYER_PING.indexOfNumber(this.playerPing()))
         .withIndex(Field.BOT_PING, Field.BOT_PING.indexOfNumber(this.botPing()))
         .withIndex(Field.FALL_DAMAGE, this.fallDamage() ? 0 : 1)
         .withIndex(Field.ARMOR_WEAR, this.armorWear() ? 0 : 1);
   }

   DrillOptions next(DrillOptions.Field var1) {
      int next = (this.index(var1) + 1) % var1.size();
      if (var1 == Field.TOTEMS && this.drill.requiresPopChain() && next == 0) next = 1;
      return this.withIndex(var1, next);
   }

   public DrillOptions withIndex(DrillOptions.Field var1, int var2) {
      if (var2 >= 0 && var2 < var1.size()) {
         EnumMap var3 = new EnumMap<>(DrillOptions.Field.class);
         var3.putAll(this.overrides);
         if (var1 == Field.SHIELD_BREAK_TICKS) var3.remove(Field.REBLOCK);
         var3.put(var1, var2);
         return new DrillOptions(this.drill, var3);
      } else {
         throw new IllegalArgumentException(var1.label() + ": " + var2);
      }
   }

   private static boolean requiresStunWindow(Drill var0) {
      return var0 == Drill.STUN_SLAM
         || var0 == Drill.ELY_STUN_SLAM
         || var0 == Drill.WIND_STUN_SLAM
         || var0 == Drill.STUN_WEB
         || var0 == Drill.MOMENTUM_BREAK
         || var0 == Drill.CORNER_WEB_DTAP
         || var0 == Drill.SHIELD_TRIPLE_TAP;
   }

   private static int initial(Drill var0, DrillOptions.Field var1) {
      return switch (var1) {
         case GAZE -> var0 != Drill.BACKSTAB && var0 != Drill.PASSIVE ? 1 : 0;
         case TURN_RATE -> 2;
         case BOT_REACH -> Field.BOT_REACH.indexOfNumber(30);
         case ARMOR_WEAR, ITEM_CONSUMPTION, FALL_DAMAGE -> 1;
         case TICK_RATE -> Field.TICK_RATE.indexOfNumber(20);
         case TOTEMS -> Totems.UNLIMITED.ordinal();
         case PEARL_CATCH_ONLY -> var0.maceTechnique() ? 1 : 0;
         default -> 0;
         case SHIELD -> var0.needsShield() ? 1 : 0;
         case BASE -> var0 == Drill.CORNER_WEB_DTAP ? 1 : 0;
         case REPLACE, HINTS, WIND_CENTER -> 1;
         case DURABILITY -> var0 == Drill.SHIELD_DRAIN ? 2 : 0;
         case DISTANCE -> switch (var0) {
            case HEAL, JUMP_RESET, HIT_SELECT, UPPERCUT, PCRIT_BREAK -> 4;
            case MACE_COUNTER_DTAP -> 3;
            case HEAD_WEB, OBI_TRAP, CORNER_OBI_TRAP, CORNER_WEB_DTAP, SELF_WEB_CRYSTAL, ANTI_DRAIN -> 1;
            case ELYTRA_SMASH, ELY_STUN_SLAM, PERFECT_WIND_CHARGE -> 5;
            default -> 2;
         };
         case REACTION -> var0 == Drill.RETOTEM ? 1 : 2;
         case WINDOW -> 3;
         case START_HEALTH -> var0.totemDefense() ? 0 : (var0 == Drill.HEAL ? 1 : 4);
      };
   }

   private int index(DrillOptions.Field var1) {
      if (var1 == Field.SHIELD_BREAK_TICKS && !overrides.containsKey(var1) && instantReblock()) {
         return var1.indexOfNumber(0);
      }
      if (var1 == DrillOptions.Field.TARGET_HEIGHT && !this.overrides.containsKey(var1)) {
         return this.slamScene() == DrillOptions.SlamScene.PAIRED_FALL ? 3 : 1;
      }

      if (var1 == DrillOptions.Field.SLAM_POSITION && !this.overrides.containsKey(var1)) {
         if (this.slamScene() == DrillOptions.SlamScene.PAIRED_FALL) {
            return DrillOptions.SlamPosition.LEVEL.ordinal();
         } else if (this.overrides.containsKey(DrillOptions.Field.START_HEIGHT)) {
            int var4 = this.number(DrillOptions.Field.START_HEIGHT) - this.targetHeight();
            return (var4 > 0 ? DrillOptions.SlamPosition.ABOVE : (var4 < 0 ? DrillOptions.SlamPosition.BELOW : DrillOptions.SlamPosition.LEVEL)).ordinal();
         } else {
            return DrillOptions.SlamPosition.ABOVE.ordinal();
         }
      } else if (var1 == DrillOptions.Field.HEIGHT_OFFSET && !this.overrides.containsKey(var1)) {
         return var1.indexOfNumber(Math.clamp(this.defaultHeightOffset(), 1, 16));
      } else {
         return this.overrides.getOrDefault(var1, initial(this.drill, var1));
      }
   }

   private int number(DrillOptions.Field var1) {
      return var1.numbers[this.index(var1)];
   }

   public int sliderStep(Field field) {
      return field.blockSteps ? number(field) - field.numbers[0] : index(field);
   }

   public String value(DrillOptions.Field var1) {
      if (var1.names != null) {
         return var1.names[this.index(var1)];
      }

      int var2 = var1 == DrillOptions.Field.HEIGHT_OFFSET ? this.heightOffset() : this.number(var1);
      if (var1 == Field.START_HEIGHT && (drill == Drill.ELYTRA_SMASH || drill == Drill.ELY_STUN_SLAM) && var2 == 0) return "Auto (" + DEFAULT_FLIGHT_HEIGHT + " Bl.)";
      if (var1 == Field.SHIELD_BREAK_TICKS && drill == Drill.WIND_STUN_SLAM && var2 < 0) return "Standard (8 Ticks)";

      return switch (var1) {
         case TURN_RATE -> var2 + "°/Tick";
         case BOT_REACH -> (var2 / 10) + "," + (var2 % 10) + " Bl.";
         case PLAYER_PING, BOT_PING -> var2 + " ms";
         case TICK_RATE -> var2 + " TPS";
         default -> Integer.toString(var2);
         case REFILL, REACTION, WINDOW -> var2 + " Ticks";
         case SHIELD_BREAK_TICKS -> var2 < 0 ? "Standard" : (var2 == 0 ? "Sofort" : var2 + (var2 == 1 ? " Tick" : " Ticks"));
         case DURABILITY -> var2 == -1 ? "Wie im Kit" : var2 + " Punkte";
         case DISTANCE, LEDGE, START_HEIGHT, SIDE_OFFSET, TARGET_HEIGHT, HEIGHT_OFFSET -> var2 + " Bl.";
         case ANGLE -> var2 + "°";
         case START_HEALTH -> var2 + " LP";
      };
   }

   public DrillOptions.Gaze gaze() {
      return DrillOptions.Gaze.values()[this.index(DrillOptions.Field.GAZE)];
   }

   public DrillOptions.Totems totems() {
      return DrillOptions.Totems.values()[this.index(DrillOptions.Field.TOTEMS)];
   }

   public int refillTicks() {
      return this.number(DrillOptions.Field.REFILL);
   }

   public boolean resetOnPop() {
      return drill != Drill.LOW_WEB_SMASH && this.index(DrillOptions.Field.RESET_POP) == 1;
   }

   public boolean holdShield() {
      return this.index(DrillOptions.Field.SHIELD) == 1;
   }

   public double botReach() { return number(Field.BOT_REACH) / 10.0; }
   public int playerPing() { return number(Field.PLAYER_PING); }
   public int botPing() { return number(Field.BOT_PING); }
   public int tickRate() { return number(Field.TICK_RATE); }
   public boolean armorWear() { return index(Field.ARMOR_WEAR) == 0; }
   public boolean itemConsumption(TrainingSettings.Mode mode) {
      return overrides.getOrDefault(Field.ITEM_CONSUMPTION, mode == TrainingSettings.Mode.UHC ? 0 : 1) == 0;
   }

   public boolean consumeTotems(TrainingSettings.Mode mode) {
      return mode == TrainingSettings.Mode.MACE
         || mode == TrainingSettings.Mode.CRYSTAL
         || mode == TrainingSettings.Mode.HUGOSMP
         || itemConsumption(mode);
   }

   public String value(Field field, TrainingSettings.Mode mode) {
      return field == Field.ITEM_CONSUMPTION ? field.names[itemConsumption(mode) ? 0 : 1] : value(field);
   }

   public DrillOptions next(Field field, TrainingSettings.Mode mode) {
      return field == Field.ITEM_CONSUMPTION ? withIndex(field, itemConsumption(mode) ? 1 : 0) : next(field);
   }
   public boolean fallDamage() { return index(Field.FALL_DAMAGE) == 0; }

   public boolean replaceShield() {
      return this.index(DrillOptions.Field.REPLACE) == 1;
   }

   public boolean windCenter() { return index(Field.WIND_CENTER) == 1; }

   public enum WindFlight { CHARGE, CHARGE_AND_CATCH, CATCH }

   public WindFlight windFlight() { return WindFlight.values()[index(Field.WIND_FLIGHT)]; }

   public boolean instantReblock() {
      return this.index(DrillOptions.Field.REBLOCK) == 1;
   }

   public int shieldBreakTicks(TrainingSettings.Mode mode) {
      boolean supported = (mode == TrainingSettings.Mode.MACE || mode == TrainingSettings.Mode.HUGOSMP) && holdShield()
         || drill == Drill.SHIELD_TRIPLE_TAP;
      if (!supported) return -1;
      int ticks = number(Field.SHIELD_BREAK_TICKS);
      return drill == Drill.WIND_STUN_SLAM && ticks < 0 ? 8 : ticks;
   }

   public boolean largeArena() {
      return index(Field.ARENA_SIZE) == 1;
   }

   public ArenaDifficulty arenaDifficulty() {
      return ArenaDifficulty.values()[index(Field.ARENA_DIFFICULTY)];
   }

   public ZeroDamage zeroDamage() {
      return ZeroDamage.values()[index(Field.ZERO_DAMAGE)];
   }

   public boolean botJump() {
      return drill == Drill.STUN_WEB && index(Field.BOT_JUMP) == 1;
   }

   public boolean randomBlockPos() {
      return drill == Drill.STUN_WEB && index(Field.RANDOM_BLOCK_POS) == 1;
   }

   public boolean botAutoHit() {
      return drill == Drill.MOMENTUM_BREAK || (drill == Drill.STUN_WEB && index(Field.BOT_AUTO_HIT) == 1);
   }

   public boolean cleanHeal() {
      return drill == Drill.HEAL && index(Field.CLEAN_HEAL) == 1;
   }

   public DunkType dunkType() {
      return DunkType.values()[index(Field.DUNK_TYPE)];
   }

   public DrillOptions.SlamScene slamScene() {
      return drill.slamOptions() ? DrillOptions.SlamScene.values()[this.index(DrillOptions.Field.SLAM_SCENE)] : DrillOptions.SlamScene.FREE;
   }

   private boolean hasAirTarget() {
      return this.slamScene() == DrillOptions.SlamScene.AIR_LOCK || this.slamScene() == DrillOptions.SlamScene.PAIRED_FALL;
   }

   private boolean maceApproachDrill() {
      return this.drill == Drill.SMASH
         || this.drill == Drill.PEARL_CATCH
         || this.drill == Drill.ELYTRA_SMASH
         || this.drill == Drill.ELY_STUN_SLAM
         || this.drill == Drill.WIND_CHAIN
         || this.drill == Drill.BACKSTAB;
   }

   DrillOptions.SlamPosition slamPosition() {
      return DrillOptions.SlamPosition.values()[this.index(DrillOptions.Field.SLAM_POSITION)];
   }

   private int defaultHeightOffset() {
      if (this.slamScene() == DrillOptions.SlamScene.AIR_LOCK
         && this.overrides.containsKey(DrillOptions.Field.START_HEIGHT)
         && !this.overrides.containsKey(DrillOptions.Field.SLAM_POSITION)) {
         return Math.max(1, Math.abs(this.number(DrillOptions.Field.START_HEIGHT) - this.targetHeight()));
      } else {
         return this.slamScene() == DrillOptions.SlamScene.PAIRED_FALL ? 2 : 6;
      }
   }

   int heightOffset() {
      if (this.hasAirTarget() && this.slamPosition() != DrillOptions.SlamPosition.LEVEL) {
         return this.overrides.containsKey(DrillOptions.Field.HEIGHT_OFFSET) ? this.number(DrillOptions.Field.HEIGHT_OFFSET) : this.defaultHeightOffset();
      } else {
         return 0;
      }
   }

   public int botElevation() {
      if (!this.hasAirTarget()) {
         return 0;
      } else {
         return this.slamPosition() == DrillOptions.SlamPosition.BELOW ? Math.max(3 + this.heightOffset(), this.targetHeight()) : this.targetHeight();
      }
   }

   public int playerElevation() {
      if (!this.hasAirTarget()) {
         return this.startHeight();
      }

      return this.botElevation() + switch (this.slamPosition()) {
         case ABOVE -> this.heightOffset();
         case LEVEL -> 0;
         case BELOW -> -this.heightOffset();
      };
   }

   int targetHeight() {
      return DrillOptions.Field.TARGET_HEIGHT.visible(this) ? this.number(DrillOptions.Field.TARGET_HEIGHT) : 0;
   }

   public boolean tripleBurst() {
      return this.drill.totemDefense() && this.index(DrillOptions.Field.BURST_PATTERN) == 1;
   }

   boolean airborneFollowups() {
      return this.drill.requiresAirPopChain() || DrillOptions.Field.CRYSTAL_AIR.visible(this) && this.index(DrillOptions.Field.CRYSTAL_AIR) == 1;
   }

   int comboLength() {
      return this.number(DrillOptions.Field.COMBO_LENGTH);
   }

   public int cover() {
      return DrillOptions.Field.COVER.visible(this) ? this.index(DrillOptions.Field.COVER) : 0;
   }

   public int startDistance() {
      return this.slamScene() == DrillOptions.SlamScene.PAIRED_FALL ? 2 : this.number(DrillOptions.Field.DISTANCE);
   }

   public int reactionTicks() {
      return this.number(DrillOptions.Field.REACTION);
   }

   public int windowTicks() {
      return this.number(DrillOptions.Field.WINDOW);
   }

   public boolean hints() { return HudPreferences.hints(); }

   // Keep the attack schedule and history key of pre-existing Jump-Reset presets.
   public boolean usesJumpCountdown() { return drill == Drill.JUMP_RESET && index(Field.HINTS) == 1; }

   public boolean pearlCatchOnly() { return index(Field.PEARL_CATCH_ONLY) == 1; }

   public int shieldDurability() {
      return this.number(DrillOptions.Field.DURABILITY);
   }

   public boolean preparedBase() {
      return this.index(DrillOptions.Field.BASE) == 1;
   }

   public int ledgeHeight() {
      return this.number(DrillOptions.Field.LEDGE);
   }

   public int turnRate() {
      return this.number(DrillOptions.Field.TURN_RATE);
   }

   public int approachAngle() {
      return this.slamScene() == DrillOptions.SlamScene.PAIRED_FALL ? 0 : this.number(DrillOptions.Field.ANGLE);
   }

   boolean variableTiming() {
      return this.index(DrillOptions.Field.VARIABLE) == 1;
   }

   public int startHeight() {
      if (this.hasAirTarget()) {
         return this.playerElevation();
      } else {
         if (!Field.START_HEIGHT.visible(this)) return 0;
         int configured = number(Field.START_HEIGHT);
         return (drill == Drill.ELYTRA_SMASH || drill == Drill.ELY_STUN_SLAM) && configured == 0 ? DEFAULT_FLIGHT_HEIGHT : configured;
      }
   }

   public int sideOffset() {
      return DrillOptions.Field.SIDE_OFFSET.visible(this) ? this.number(DrillOptions.Field.SIDE_OFFSET) : 0;
   }

   public int holeDepth() {
      return DrillOptions.Field.TERRAIN.visible(this) ? this.index(DrillOptions.Field.TERRAIN) : 0;
   }

   boolean pingPong() {
      return this.index(DrillOptions.Field.PING_PONG) == 1;
   }

   public int startHealth() {
      return this.number(DrillOptions.Field.START_HEALTH);
   }

   public enum Field {
      GAZE("Blickrichtung", "Fest", "Spieler folgen"),
      TURN_RATE("Drehgeschwindigkeit", 5, 15, 45, 180),
      TOTEMS("Auto-Totem", "Aus", "Kit-Vorrat", "Unbegrenzt"),
      REFILL("Refill nach Pop", 0, 1, 2, 4, 8, 12, 20),
      RESET_POP("Nach Bot-Pop", "Weiterspielen", "Neu aufstellen"),
      SHIELD("Dauerblocken", "Aus", "Ein"),
      REPLACE("Schild bei Bruch", "Verbrauchen", "Ersetzen"),
      REBLOCK("Nach Axt-Stun", "Normale Dauer", "Sofort blocken"),
      DURABILITY("Schildhaltbarkeit", -1, 1, 10, 40, 100),
      DISTANCE("Startabstand", 2, 3, 4, 5, 7, 9),
      ANGLE("Anlaufwinkel", 0, 15, 30, 45),
      REACTION("Reaktionszeit", 5, 10, 20, 30, 40, 60),
      VARIABLE("Angriffstakt", "Gleichmäßig", "Wechselnd"),
      WINDOW("Kombofenster", 10, 20, 30, 40, 60, 100),
      BASE("Kristallbasis", "Selbst setzen", "Vorbereitet"),
      LEDGE("Kantenhöhe", 1, 2, 3),
      START_HEIGHT("Starthöhe", true, 0, 3, 6, 12, 18, 24, 30, 48, 64, 80, 96),
      SIDE_OFFSET("Seitlicher Versatz", 0, 2, 4, 6),
      TERRAIN("Bot-Standort", "Ebene", "Ein Block tiefer", "Zwei Blöcke tiefer"),
      PING_PONG("Kristall-Richtung", "Frei", "Seitenwechsel"),
      START_HEALTH("Eigene Start-LP", 4, 8, 12, 16, 20),
      HINTS("Übungshinweise", "Aus", "Ein"),
      SLAM_SCENE("Stun-Slam-Szene", "Frei", "Am Boden fixiert", "In der Luft fixiert", "Gemeinsam fallen"),
      TARGET_HEIGHT("Zielhöhe", true, 3, 6, 10, 16, 24, 32, 48, 64, 80, 96),
      COMBO_LENGTH("Trefferserie", 2, 3, 5),
      CRYSTAL_AIR("Folgetreffer", "Nächstes Schadensfenster", "Nur in der Luft"),
      COVER("Startdeckung", "Frei", "Deckungsblock", "Kurze Wand"),
      BURST_PATTERN("Kristallfolge", "D-Tap", "Triple Tap"),
      SLAM_POSITION("Deine Startlage", "Über dem Bot", "Auf gleicher Höhe", "Unter dem Bot"),
      HEIGHT_OFFSET("Höhenabstand", true, 1, 2, 3, 6, 10, 16),
      SHIELD_BREAK_TICKS("Blockpause nach Bruch", java.util.stream.IntStream.rangeClosed(-1, 200).toArray()),
      BOT_REACH("Max. Schlagreichweite", java.util.stream.IntStream.rangeClosed(10, 30).toArray()),
      TICK_RATE("Spieltempo", java.util.stream.IntStream.rangeClosed(1, 20).toArray()),
      ARMOR_WEAR("Rüstungsverschleiß", "An", "Aus"),
      ITEM_CONSUMPTION("Item-Verbrauch", "An", "Aus"),
      FALL_DAMAGE("Fallschaden", "An", "Aus"),
      PLAYER_PING("Dein Ping", java.util.stream.IntStream.rangeClosed(0, 60).map(i -> i * 5).toArray()),
      BOT_PING("Bot-Ping", java.util.stream.IntStream.rangeClosed(0, 60).map(i -> i * 5).toArray()),
      WIND_CENTER("Arenamitte fixieren", "Aus", "Ein"),
      WIND_FLIGHT("Bot-Aufstieg", "Nur Wind Charge", "Wind Charge + Pearl Catch", "Nur Pearl Catch"),
      PEARL_CATCH_ONLY("Nur Pearl-Catches", "Aus", "Ein"),
      ARENA_SIZE("Arena", "Standard", "Groß"),
      BOT_JUMP("Dauerhaft springen", "Aus", "Ein"),
      ARENA_DIFFICULTY("Arena-Schwierigkeit", "Schwer", "Von Welt", "Normal", "Leicht"),
      ZERO_DAMAGE("Zero-Damage-Modus", "Aus", "Nur Spieler", "Beide"),
      RANDOM_BLOCK_POS("Blockposition", "Mitte", "Zufällig"),
      BOT_AUTO_HIT("Bot Auto-Hit", "Aus", "Ein"),
      CLEAN_HEAL("Sauberer Heal", "Aus", "Ein"),
      DUNK_TYPE("Secondary-Typ", "Cobweb", "Lava", "Web + Lava");

      private final String label;
      private final String[] names;
      private final int[] numbers;
      private final boolean blockSteps;

      Field(String var3, String... var4) {
         this.label = var3;
         this.names = var4;
         this.numbers = null;
         this.blockSteps = false;
      }

      Field(String var3, int... var4) {
         this(var3, false, var4);
      }

      Field(String label, boolean blockSteps, int... originalValues) {
         this.label = label;
         this.names = null;
         this.blockSteps = blockSteps;
         if (!blockSteps) {
            this.numbers = originalValues;
            return;
         }

         // Saved presets store indices. Keep all original indices and append the in-between heights.
         int first = originalValues[0];
         int last = originalValues[originalValues.length - 1];
         this.numbers = Arrays.copyOf(originalValues, last - first + 1);
         int next = originalValues.length;
         for (int value = first; value <= last; value++) {
            if (Arrays.binarySearch(originalValues, value) < 0) this.numbers[next++] = value;
         }
      }

      public int indexForSliderStep(int step) {
         return blockSteps ? indexOfNumber(numbers[0] + step) : step;
      }

      public String label() {
         return this.label;
      }

      public int size() {
         return this.names == null ? this.numbers.length : this.names.length;
      }

      public int indexOfNumber(int var1) {
         if (this.numbers != null) {
            for (int var2 = 0; var2 < this.numbers.length; var2++) {
               if (this.numbers[var2] == var1) {
                  return var2;
               }
            }
         }

         throw new IllegalArgumentException(this.label + " unterstützt diesen Wert nicht: " + var1);
      }

      public boolean visible(DrillOptions options, TrainingSettings.Mode mode) {
         if (!mode.allowsBotTotems() && (this == TOTEMS || this == REFILL || this == RESET_POP)) return false;
         if (this == SHIELD_BREAK_TICKS) {
            return (mode == TrainingSettings.Mode.MACE || mode == TrainingSettings.Mode.HUGOSMP) && options.holdShield()
               || options.drill() == Drill.SHIELD_TRIPLE_TAP;
         }
         if (this == REBLOCK && (mode == TrainingSettings.Mode.MACE || mode == TrainingSettings.Mode.HUGOSMP || options.drill() == Drill.SHIELD_TRIPLE_TAP)) {
            return false;
         }
         return visible(options);
      }

      public boolean visible(DrillOptions var1) {
         return switch (this) {
            case TURN_RATE -> var1.gaze() == DrillOptions.Gaze.TRACK;
            default -> true;
            case REFILL -> var1.totems() != DrillOptions.Totems.OFF;
            case SHIELD -> var1.drill() != Drill.LOW_WEB_SMASH
               && var1.drill() != Drill.SHIELD_TRIPLE_TAP
               && var1.drill() != Drill.MACE_COUNTER_DTAP
               && !var1.drill().needsShield()
               && !var1.drill().totemDefense();
            case WIND_CENTER, WIND_FLIGHT -> var1.drill() == Drill.WIND_STUN_SLAM;
            case BOT_JUMP, RANDOM_BLOCK_POS, BOT_AUTO_HIT -> var1.drill() == Drill.STUN_WEB;
            case CLEAN_HEAL -> var1.drill() == Drill.HEAL;
            case DUNK_TYPE -> var1.drill() == Drill.WEB_DUNK;
            case HINTS, ARENA_DIFFICULTY -> false;
            case REPLACE -> var1.holdShield();
            case DURABILITY -> var1.holdShield();
            case REBLOCK -> var1.holdShield() && !DrillOptions.requiresStunWindow(var1.drill());
            case DISTANCE, ANGLE -> var1.slamScene() != DrillOptions.SlamScene.PAIRED_FALL;
            case BASE -> var1.drill() == Drill.HIT_CRYSTAL
               || var1.drill() == Drill.D_TAP
               || var1.drill() == Drill.TRIPLE_TAP
               || var1.drill() == Drill.SHIELD_TRIPLE_TAP
               || var1.drill() == Drill.SELF_WEB_CRYSTAL
               || var1.drill() == Drill.LEDGE_DASH
               || var1.drill() == Drill.TAP_ANCHOR
               || var1.drill() == Drill.MACE_COUNTER_DTAP
               || var1.drill() == Drill.CORNER_WEB_DTAP
               || var1.drill() == Drill.DUEL;
            case LEDGE -> var1.drill() == Drill.LEDGE_DASH;
            case START_HEIGHT -> var1.drill() == Drill.LOW_WEB_SMASH || var1.drill().slamOptions() && !var1.hasAirTarget() || var1.maceApproachDrill();
            case SIDE_OFFSET -> var1.drill().slamOptions() && var1.slamScene() != DrillOptions.SlamScene.PAIRED_FALL || var1.maceApproachDrill();
            case TERRAIN -> var1.drill() == Drill.HIT_CRYSTAL
               || var1.drill() == Drill.D_TAP
               || var1.drill() == Drill.TRIPLE_TAP
               || var1.drill() == Drill.SHIELD_TRIPLE_TAP
               || var1.drill() == Drill.SELF_WEB_CRYSTAL
               || var1.drill() == Drill.SAFE_ANCHOR
               || var1.drill() == Drill.TAP_ANCHOR
               || var1.drill() == Drill.DOUBLE_ANCHOR;
            case PING_PONG -> var1.drill() == Drill.TRIPLE_TAP || var1.drill() == Drill.SHIELD_TRIPLE_TAP;
            case SLAM_SCENE -> var1.drill().slamOptions();
            case TARGET_HEIGHT -> var1.slamScene() == DrillOptions.SlamScene.AIR_LOCK || var1.slamScene() == DrillOptions.SlamScene.PAIRED_FALL;
            case COMBO_LENGTH -> var1.drill() == Drill.SPACING;
            case CRYSTAL_AIR -> var1.drill() == Drill.TAP_ANCHOR;
            case RESET_POP -> !var1.drill().requiresPopChain() && var1.drill() != Drill.LOW_WEB_SMASH;
            case COVER -> var1.drill() == Drill.HEAL || var1.drill() == Drill.SAFE_ANCHOR;
            case BURST_PATTERN -> var1.drill().totemDefense();
            case SLAM_POSITION -> var1.hasAirTarget();
            case HEIGHT_OFFSET -> var1.hasAirTarget() && var1.slamPosition() != DrillOptions.SlamPosition.LEVEL;
         };
      }
   }

   public enum Gaze {
      FIXED,
      TRACK;
   }

   public enum SlamPosition {
      ABOVE,
      LEVEL,
      BELOW;
   }

   public enum SlamScene {
      FREE,
      GROUND_LOCK,
      AIR_LOCK,
      PAIRED_FALL;
   }

   public enum Totems {
      OFF,
      RESERVE,
      UNLIMITED;
   }

   public enum ArenaDifficulty {
      HARD,
      WORLD,
      NORMAL,
      EASY;

      public net.minecraft.world.Difficulty resolve(net.minecraft.world.Difficulty worldDifficulty, boolean inArena) {
         return net.minecraft.world.Difficulty.HARD;
      }
   }

   public enum ZeroDamage {
      OFF,
      PLAYER_ONLY,
      BOTH;
   }

   public enum DunkType {
      WEB,
      LAVA,
      WEB_LAVA;
   }
}

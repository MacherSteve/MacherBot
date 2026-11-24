package de.pvptrainer.kits;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;

import java.util.EnumMap;

public enum TrainingPreset {
   BEGINNER("Einstieg"),
   PRACTICE("Aufbau"),
   ADVANCED("Präzision");

   public final String label;

   TrainingPreset(String var3) {
      this.label = var3;
   }

   public String label(Drill drill) {
      return switch (drill) {
         case STUN_SLAM -> names("Bodenziel", "Luftziel", "Gemeinsamer Fall");
         case WIND_STUN_SLAM -> names("Bodenstart", "Luftstart 16 m", "Luftstart 32 m");
         case LOW_WEB_SMASH -> names("Nah am Netz", "Netzanflug", "Weiter Anflug");
         case PERFECT_WIND_CHARGE -> names("Absprung", "Wurftiming", "Maximalhöhe");
         case PEARL_CATCH -> names("Gerade Perle", "Seitlicher Catch", "Catch in Bewegung");
         case ELYTRA_SMASH -> names("Hoher Gleitflug", "Rüstungswechsel", "Kurzer Anflug");
         case BACKSTAB -> names("Fester Blick", "Schild umgehen", "Schnelle Drehung");
         case HIT_CRYSTAL -> names("Fertige Basis", "Basis setzen", "Bewegtes Ziel");
         case D_TAP -> names("Pop-Eröffnung", "Doppel-Pop", "Seitlicher D-Tap");
         case TRIPLE_TAP -> names("Dritte Zündung", "Dreierfolge", "Mobiler Triple-Tap");
         case TAP_ANCHOR -> names("Anker vorbereiten", "Ankerwechsel", "Mobiler Abschluss");
         case LEDGE_DASH -> names("Kleine Kante", "Basis am Absatz", "Hohe Kante");
         case SAFE_ANCHOR -> names("Blockdeckung", "Wanddeckung", "Eigene Deckung");
         case RETOTEM -> names("Ruhiger Refill", "Schneller Refill", "Dreifach-Burst");
         case HEAD_WEB -> names("Ruhiger Kopf", "Netz im Zulauf", "Seitlicher Fang");
         case OBI_TRAP -> names("Standziel sperren", "Zulauf sperren", "Seitweg sperren");
         case STUN_WEB -> names("Stun am Stand", "Stun im Zulauf", "Seitlicher Stun");
         case WEB_DRAIN -> names("Quelle suchen", "Wasser schöpfen", "Reaktion lesen");
         case RE_LAVA -> names("Lava nachsetzen", "Löschen lesen", "Schneller Re-Lava");
         case HEAL -> names("Wand-Heal", "Block-Heal", "Freier Heal");
         case SPACING -> names("Zweierkombo", "Dreierkombo", "Fünferkombo");
         case HIT_SELECT -> names("Gerader Konter", "Schräger Konter", "Takt lesen");
         case PUNISH_CRIT -> names("Fallphase", "Schräger Crit", "Crit im Wechsel");
         case JUMP_RESET -> names("Sprungsignal", "Rückstoß lesen", "Freies Timing");
         case DUEL -> names("Ruhiges Duell", "Verfolgung", "Seitendruck");
         case PASSIVE -> names("Nahes Ziel", "Blickkontakt", "Fernes Ziel");
         case TOTEM_POPS -> names("Naher Pop", "Pop-Folge", "Pop auf Distanz");
         case SHIELD -> names("Frontblock", "Schildwinkel", "Schild auf Distanz");
         case DOUBLE_HAND -> names("Beide Hände", "Doppel-Refill", "Burst-Refill");
         case SMASH -> names("Gerader Smash", "Seitlicher Smash", "Mobiler Smash");
         case WIND_CHAIN -> names("Erster Aufwind", "Folgesmash", "Mobile Kette");
         case SHIELD_DRAIN -> names("Letzter Block", "Schildabnutzung", "Langer Drain");
         case DOUBLE_ANCHOR -> names("Ruhiger Doppel-Anker", "Schneller Doppel-Anker", "Mobiler Doppel-Anker");
         case SHIELD_TRIPLE_TAP -> names("Schild-Konter", "Dreierfolge mit Schild", "Mobiler Shield-Triple");
         case CORNER_OBI_TRAP -> names("Ecke sperren", "Zulauf in Ecke", "Seitliche Ecke");
         case MACE_COUNTER_DTAP -> names("Ruhiger Konter", "Standard-Konter", "Schneller Konter");
         case CORNER_WEB_DTAP -> names("Ecken-Web + D-Tap", "D-Tap aus Distanz", "Schneller Schild-Reset");
         case SELF_WEB_CRYSTAL -> names("Fertige Basis", "Selbst setzen", "Schneller D-Tap");
         case UPPERCUT -> names("Rückstoß-Uppercut", "Schräger Uppercut", "Uppercut im Wechsel");
         case ELY_STUN_SLAM -> names("Hoher Anflug", "Standard-Anflug", "Kurzer Anflug");
         case SHIELD_TIMING -> names("Ruhiger Rhythmus", "Mit Baits", "Schnelle Konter");
         case MOMENTUM_BREAK -> names("Standard-Break", "Schneller Auto-Hit", "Seitlicher Break");
         case WEB_DUNK -> names("Web Dunk", "Lava Dunk", "Web + Lava Dunk");
         case ANTI_DRAIN -> names("Standard-Escape", "Schneller Drainer", "Druck im Web");
         case HEAL_PUNISH -> names("Nahes Gappen", "Abstand brechen", "Schneller Punish");
         case PCRIT_BREAK -> names("Gerader Break", "Schräger Break", "Wechselnder Takt");
      };
   }

   private String names(String first, String second, String third) {
      return switch (this) { case BEGINNER -> first; case PRACTICE -> second; case ADVANCED -> third; };
   }

   private int choose(int var1, int var2, int var3) {
      return switch (this) {
         case BEGINNER -> var1;
         case PRACTICE -> var2;
         case ADVANCED -> var3;
      };
   }

   public TrainingPreset.Setup setup(TrainingSettings var1, Drill var2) {
      TrainingSettings var3 = var1.forDrill(var2).withDifficulty(TrainingSettings.Difficulty.HARD);
      TrainingPreset.Recipe var4 = new TrainingPreset.Recipe();
      if (var2 == Drill.JUMP_RESET) var4.index(DrillOptions.Field.HINTS, this == ADVANCED ? 0 : 1);
      boolean var5 = this == ADVANCED;
      boolean var6 = this == BEGINNER;

      String detail = switch (var2) {
         case DUEL -> {
            var4.number(DrillOptions.Field.REACTION, this.choose(40, 20, 10));
            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.FOLLOW);
            yield var3.movement().label();
         }
         case PASSIVE, TOTEM_POPS, SHIELD -> {
            var4.number(DrillOptions.Field.DISTANCE, this.choose(2, 3, 4));
            var4.index(DrillOptions.Field.GAZE, var6 ? 0 : 1);
            var4.number(DrillOptions.Field.TURN_RATE, this.choose(5, 15, 45));
            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : (var6 ? TrainingSettings.Movement.STILL : TrainingSettings.Movement.FOLLOW));
            yield var3.movement().label() + " · " + (var6 ? "fester Blick" : "Blick folgt dir");
         }
         case HIT_CRYSTAL, D_TAP, TRIPLE_TAP, SHIELD_TRIPLE_TAP, TAP_ANCHOR -> {
            var4.index(DrillOptions.Field.BASE, var6 ? 1 : 0);
            var4.number(DrillOptions.Field.DISTANCE, this.choose(2, 3, 4));
            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.STILL);
            yield var6 ? "Vorgelegtes Obsidian · ruhiges Ziel" : (var5 ? "Selbst platzieren · seitliches Ziel" : "Selbst platzieren · ruhiges Ziel");
         }
         case DOUBLE_ANCHOR -> {
            var4.number(DrillOptions.Field.DISTANCE, this.choose(3, 4, 5));
            var4.number(DrillOptions.Field.WINDOW, this.choose(60, 40, 30));
            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.STILL);
            yield "Zwei Anker-Pops · " + this.choose(60, 40, 30) + " Ticks Fenster";
         }
         case MACE_COUNTER_DTAP -> {
            var4.number(DrillOptions.Field.DISTANCE, this.choose(5, 5, 7));
            var4.number(DrillOptions.Field.REACTION, this.choose(20, 10, 5));
            var3 = var3.withMovement(TrainingSettings.Movement.STILL);
            yield "Bot springt aus " + this.choose(5, 5, 7) + " Bl. · Shielden & D-Tapen";
         }
         case CORNER_WEB_DTAP -> {
            var4.index(DrillOptions.Field.SHIELD, 1);
            var4.index(DrillOptions.Field.BASE, var6 ? 1 : 0);
            var4.number(DrillOptions.Field.DISTANCE, this.choose(2, 3, 4));
            var3 = var3.withMovement(TrainingSettings.Movement.STILL);
            yield "Ecken-Web, Axt-Stun & 2 Kristall-Pops";
         }
         case SELF_WEB_CRYSTAL -> {
            var4.index(DrillOptions.Field.BASE, var6 ? 1 : 0);
            var4.number(DrillOptions.Field.DISTANCE, this.choose(2, 3, 4));
            var4.number(DrillOptions.Field.WINDOW, this.choose(60, 40, 20));
            var3 = var3.withMovement(TrainingSettings.Movement.STILL);
            yield var6 ? "Vorbereitetes Boden-Obsidian · Bot webt sich ein" : "Selbst setzen · " + this.choose(60, 40, 20) + " Ticks für 2 Pops";
         }
         case LEDGE_DASH -> {
            int var16 = this.choose(1, 2, 3);
            var4.number(DrillOptions.Field.LEDGE, var16);
            var4.index(DrillOptions.Field.BASE, var6 ? 1 : 0);
            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.STILL);
            yield "Kante " + var16 + " Bl. · " + (var6 ? "Basis vorbereitet" : "Basis selbst setzen");
         }
         case SAFE_ANCHOR -> {
            int var15 = this.choose(2, 1, 0);
            var4.index(DrillOptions.Field.COVER, var15);
            var4.number(DrillOptions.Field.DISTANCE, this.choose(3, 4, 5));
            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.STILL);
            yield coverDescription(var15) + " · " + (var5 ? "seitliches Ziel" : "ruhiges Ziel");
         }
         case RETOTEM, DOUBLE_HAND -> {
            int var14 = this.choose(30, 10, 5);
            var4.number(DrillOptions.Field.REACTION, var14);
            var4.number(DrillOptions.Field.START_HEALTH, 4);
            var4.index(DrillOptions.Field.BURST_PATTERN, var5 ? 1 : 0);
            yield (var5 ? "Triple Tap" : "D-Tap") + " · " + var14 + " Ticks Vorbereitung";
         }
         case SMASH, WIND_CHAIN, PEARL_CATCH, ELYTRA_SMASH, ELY_STUN_SLAM -> {
            int var13 = this.choose(0, 2, 4);
            var4.number(DrillOptions.Field.SIDE_OFFSET, var13);
            var4.number(DrillOptions.Field.DISTANCE, this.choose(2, 3, 4));
            if (var2 == Drill.ELYTRA_SMASH || var2 == Drill.ELY_STUN_SLAM) {
               var4.number(DrillOptions.Field.START_HEIGHT, this.choose(40, DrillOptions.DEFAULT_FLIGHT_HEIGHT, 24));
               var4.number(DrillOptions.Field.DISTANCE, this.choose(9, 7, 5));
            }
            if (var2 == Drill.ELY_STUN_SLAM) {
               var4.index(DrillOptions.Field.SHIELD, 1);
               var4.index(DrillOptions.Field.REBLOCK, 0);
            }

            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.STILL);
            yield var13 == 0 ? "Gerader Anflug · ruhiges Ziel" : var13 + " Bl. Versatz · " + (var5 ? "seitliches Ziel" : "ruhiges Ziel");
         }
         case PERFECT_WIND_CHARGE -> {
            var4.number(DrillOptions.Field.DISTANCE, 9);
            var3 = var3.withMovement(TrainingSettings.Movement.STILL).withCounterAttacks(false);
            yield "Einzelwurf · Höhe über dem Absprung";
         }
         case LOW_WEB_SMASH -> {
            var4.number(DrillOptions.Field.DISTANCE, this.choose(2, 3, 4));
            var3 = var3.withMovement(TrainingSettings.Movement.STILL);
            yield "Kopf im Netz · Beine frei";
         }
         case WIND_STUN_SLAM -> {
            int height = this.choose(0, 16, 32);
            var4.number(DrillOptions.Field.DISTANCE, 3);
            var4.number(DrillOptions.Field.START_HEIGHT, height);
            var3 = var3.withMovement(TrainingSettings.Movement.STILL);
            yield height == 0 ? "Dein Start am Boden" : "Dein Luftstart · " + height + " Blöcke";
         }
         case STUN_SLAM -> {
            var4.index(DrillOptions.Field.SLAM_SCENE, this.choose(1, 2, 3));
            var4.number(DrillOptions.Field.DISTANCE, 2);
            var4.number(DrillOptions.Field.START_HEIGHT, this.choose(6, 12, 0));
            var4.number(DrillOptions.Field.TARGET_HEIGHT, this.choose(3, 6, 16));
            if (!var6) {
               var4.index(DrillOptions.Field.SLAM_POSITION, var5 ? DrillOptions.SlamPosition.LEVEL.ordinal() : DrillOptions.SlamPosition.ABOVE.ordinal());
               var4.number(DrillOptions.Field.HEIGHT_OFFSET, var5 ? 2 : 6);
            }

            var4.index(DrillOptions.Field.SHIELD, 1);
            var4.index(DrillOptions.Field.REBLOCK, 0);
            var3 = var3.withMovement(TrainingSettings.Movement.STILL);
            yield var6 ? "Festes Bodenziel · 6 Bl. Luftstart" : (var5 ? "Gemeinsamer Fall aus 16 Blöcken" : "Festes Luftziel · 6 Bl. Fallweg");
         }
         case BACKSTAB -> {
            var4.index(DrillOptions.Field.GAZE, var6 ? 0 : 1);
            var4.number(DrillOptions.Field.TURN_RATE, this.choose(5, 15, 45));
            var4.number(DrillOptions.Field.DISTANCE, this.choose(2, 3, 4));
            var4.number(DrillOptions.Field.START_HEIGHT, this.choose(3, 6, 6));
            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.STILL);
            yield var6 ? "Fester Blick · schwebender Start" : "Bot dreht mit " + this.choose(5, 15, 45) + "°/Tick";
         }
         case SHIELD_DRAIN -> {
            int var12 = this.choose(1, 10, 40);
            var4.number(DrillOptions.Field.DURABILITY, var12);
            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.STILL);
            yield "Schild mit " + var12 + (var12 == 1 ? " Punkt" : " Punkten") + " Resthaltbarkeit";
         }
         case SPACING, HIT_SELECT, PUNISH_CRIT, JUMP_RESET, UPPERCUT, PCRIT_BREAK -> {
            int var11 = this.choose(30, 20, 10);
            var4.number(DrillOptions.Field.REACTION, var11);
            boolean readableApproach = var2 == Drill.HIT_SELECT || var2 == Drill.JUMP_RESET || var2 == Drill.UPPERCUT || var2 == Drill.PCRIT_BREAK;
            var4.number(DrillOptions.Field.ANGLE, readableApproach ? this.choose(0, 0, 15) : this.choose(0, 15, 30));
            if (readableApproach) {
               var4.number(DrillOptions.Field.DISTANCE, this.choose(7, 7, 9));
            }
            var4.index(DrillOptions.Field.VARIABLE, var5 ? 1 : 0);
            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.FOLLOW);
            if (var2 == Drill.SPACING) {
               int var17 = this.choose(2, 3, 5);
               var4.number(DrillOptions.Field.COMBO_LENGTH, var17);
               yield var17 + " Treffer · " + (var5 ? "wechselnder Takt" : var11 + " Ticks Angriffstakt");
            } else if (readableApproach) {
               yield var5 ? "9 Bl. Abstand · 3-Block-Sprint-Hit" : "7 Bl. Abstand · direkter 3-Block-Sprint-Hit";
            } else {
               yield var5 ? "Schräger Anlauf · wechselnder Takt" : var11 + " Ticks Angriffstakt · " + (var6 ? "gerader Anlauf" : "15° Anlauf");
            }
         }
         case STUN_WEB -> {
            var4.index(DrillOptions.Field.SHIELD, 1);
            var4.index(DrillOptions.Field.REBLOCK, 0);
            var4.number(DrillOptions.Field.DISTANCE, this.choose(2, 3, 4));
            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : (var6 ? TrainingSettings.Movement.STILL : TrainingSettings.Movement.FOLLOW));
            yield "Schildstun · " + var3.movement().label();
         }
         case HEAD_WEB, OBI_TRAP, CORNER_OBI_TRAP -> {
            var4.number(DrillOptions.Field.DISTANCE, this.choose(2, 3, 4));
            var3 = var3.withMovement(var6 ? TrainingSettings.Movement.STILL : var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.FOLLOW);
            yield (var2 == Drill.HEAD_WEB ? "Netz an der Wand" : (var2 == Drill.CORNER_OBI_TRAP ? "Obsidian-Falle in der Ecke" : "Lava und Wasser an der Wand"))
               + " · " + var3.movement().label();
         }
         case WEB_DRAIN, RE_LAVA -> {
            int var10 = this.choose(40, 20, 10);
            var4.number(DrillOptions.Field.REACTION, var10);
            var4.number(DrillOptions.Field.DISTANCE, this.choose(2, 3, 4));
            var4.index(DrillOptions.Field.VARIABLE, var5 ? 1 : 0);
            yield var5 ? "Bot reagiert ab 10 Ticks · variabel" : "Bot reagiert nach " + var10 + " Ticks";
         }
         case HEAL -> {
            int var8 = this.choose(2, 1, 0);
            int var9 = this.choose(9, 5, 3);
            var4.index(DrillOptions.Field.COVER, var8);
            var4.number(DrillOptions.Field.DISTANCE, var9);
            var4.number(DrillOptions.Field.REACTION, this.choose(40, 20, 10));
            var4.number(DrillOptions.Field.START_HEALTH, 8);
            if (var5) var4.index(DrillOptions.Field.CLEAN_HEAL, 1);
            var3 = var3.withMovement(TrainingSettings.Movement.FOLLOW);
            yield coverDescription(var8) + " · " + var9 + " Bl. Vorsprung" + (var5 ? " · Sauberer Heal" : "");
         }
         case SHIELD_TIMING -> {
            int react = this.choose(20, 10, 5);
            var4.number(DrillOptions.Field.REACTION, react);
            var4.number(DrillOptions.Field.DISTANCE, this.choose(3, 4, 4));
            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.FOLLOW).withCounterAttacks(true);
            yield "Nur Schwert & Schild · " + (var6 ? "ruhiger Takt" : (var5 ? "schnelle Baits" : "Jump-Baits & Konter"));
         }
         case MOMENTUM_BREAK -> {
            var4.index(DrillOptions.Field.SHIELD, 1);
            var4.index(DrillOptions.Field.REBLOCK, 0);
            var4.number(DrillOptions.Field.DISTANCE, this.choose(3, 3, 4));
            var4.number(DrillOptions.Field.WINDOW, this.choose(40, 30, 20));
            var3 = var3.withMovement(var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.STILL);
            yield "Axt-Stun → Jump-Reset auf Auto-Hit → Schwert-Anschluss";
         }
         case WEB_DUNK -> {
            var4.index(DrillOptions.Field.DUNK_TYPE, this.ordinal());
            var4.number(DrillOptions.Field.DISTANCE, this.choose(3, 3, 4));
            var4.number(DrillOptions.Field.WINDOW, this.choose(40, 30, 30));
            var3 = var3.withMovement(var6 ? TrainingSettings.Movement.STILL : (var5 ? TrainingSettings.Movement.STRAFE : TrainingSettings.Movement.FOLLOW));
            yield switch (this) {
               case BEGINNER -> "Crit → Cobweb in die Flugbahn";
               case PRACTICE -> "Crit → Lava unter den Bot";
               case ADVANCED -> "Crit → Cobweb + Lava darüber";
            };
         }
         case ANTI_DRAIN -> {
            var4.number(DrillOptions.Field.REACTION, this.choose(20, 10, 5));
            var4.number(DrillOptions.Field.DISTANCE, this.choose(3, 3, 2));
            var3 = var3.withMovement(TrainingSettings.Movement.FOLLOW).withCounterAttacks(true);
            yield "Im Cobweb erst Bot wegschlagen · dann sicher watern";
         }
         case HEAL_PUNISH -> {
            var4.number(DrillOptions.Field.DISTANCE, this.choose(4, 5, 5));
            var4.number(DrillOptions.Field.REACTION, this.choose(20, 10, 5));
            var3 = var3.withMovement(TrainingSettings.Movement.STILL);
            yield "Bot gapt aus " + this.choose(4, 5, 5) + " Bl. · 2 Crits landen";
         }
         default -> throw new IllegalStateException("Unbekannte Übung " + var2);
      };
      return new TrainingPreset.Setup(var3, new DrillOptions(var2, var4.values), detail);
   }

   private static String coverDescription(int var0) {
      return switch (var0) {
         case 1 -> "Deckungsblock";
         case 2 -> "Kurze Wand";
         default -> "Eigene Deckung";
      };
   }

   private static final class Recipe {
      final EnumMap<DrillOptions.Field, Integer> values = new EnumMap<>(DrillOptions.Field.class);

      void index(DrillOptions.Field var1, int var2) {
         this.values.put(var1, var2);
      }

      void number(DrillOptions.Field var1, int var2) {
         this.index(var1, var1.indexOfNumber(var2));
      }
   }

   public record Setup(TrainingSettings behavior, DrillOptions options, String detail) {
   }
}

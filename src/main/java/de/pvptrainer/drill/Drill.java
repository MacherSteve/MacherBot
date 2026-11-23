package de.pvptrainer.drill;
import de.pvptrainer.kits.TrainingSettings;

import java.util.Arrays;
import java.util.List;

public enum Drill {
   DUEL(null, "Freies Duell", "Trainiere mit den gewählten Bewegungs- und Angriffsregeln."),
   PASSIVE(null, "Passives Ziel", "Übe deine Technik an einem ausgerüsteten Ziel."),
   TOTEM_POPS(null, "Totems poppen", "Löse durch Schaden ein echtes Totem des Bots aus."),
   SHIELD(null, "Schild üben", "Triff frontal auf die gehobene Deckung."),
   HIT_CRYSTAL(TrainingSettings.Mode.CRYSTAL, "Hit-Crystal", "Nahkampftreffer → wirksame Kristallexplosion, bevor der Bot wieder landet."),
   D_TAP(TrainingSettings.Mode.CRYSTAL, "D-Tap", "Eröffne mit Totem oder Schwert. Triff mit zwei Kristallen vor der Landung. Jeder Kristall muss ein Totem auslösen."),
   TRIPLE_TAP(TrainingSettings.Mode.CRYSTAL, "Triple Tap", "Eröffnung, Kristall, Nahkampftreffer aufs Luftziel und zwei Folgekristalle. Drei Pops vor der Landung."),
   LEDGE_DASH(TrainingSettings.Mode.CRYSTAL, "Ledge Dash", "Verlasse die Kante und triff den höher stehenden Bot mit einem Kristall."),
   SAFE_ANCHOR(TrainingSettings.Mode.CRYSTAL, "Safe Anchor", "Zünde einen geladenen Anker aus Deckung. Der Bot soll mehr Schaden nehmen als du."),
   TAP_ANCHOR(TrainingSettings.Mode.CRYSTAL, "D-Tap → Anchor", "Verbinde zwei Kristalltreffer nach dem Nahkampftreffer mit einem wirksamen Anker."),
   RETOTEM(TrainingSettings.Mode.CRYSTAL, "Manuelles Retotem", "Fülle Totems nach und überlebe, bis dein letztes Totem poppt."),
   DOUBLE_HAND(
      TrainingSettings.Mode.CRYSTAL, "Double Hand", "Halte vor dem ersten Pop zwei Totems. Überstehe den nächsten Kristall mit dem verbliebenen Totem."
   ),
   SMASH(TrainingSettings.Mode.MACE, "Wind-Charge-Smash", "Starte mit der eigenen Windladung und lande im Fallen einen echten Smash."),
   WIND_CHAIN(TrainingSettings.Mode.MACE, "Wind-Burst-Kette", "Lande zwei Smashes vor dem nächsten Bodenkontakt. Nutze eine Wind-Burst-Mace."),
   STUN_SLAM(TrainingSettings.Mode.MACE, "Stun Slam", "Deaktiviere das Schild mit der Axt und lande einen vollen Wind-Burst-Smash im offenen Zeitfenster."),
   BACKSTAB(TrainingSettings.Mode.MACE, "Backstab", "Lande einen Smash von der ungedeckten Seite, während der Bot blockt."),
   SHIELD_DRAIN(TrainingSettings.Mode.MACE, "Shield Drain", "Brich das gehobene Schild durch tatsächlichen Haltbarkeitsverlust."),
   PEARL_CATCH(TrainingSettings.Mode.MACE, "Pearl Catch", "Fange die eigene Perle mit einer Windladung in der Luft und lande danach einen Smash."),
   ELYTRA_SMASH(TrainingSettings.Mode.MACE, "Elytra-Anflug", "Beende den Gleitflug und lande vor dem Aufsetzen einen Smash."),
   SPACING(TrainingSettings.Mode.SWORD, "Spacing", "Gewinne die Eröffnung und lande den Anschluss vor dem ersten Gegentreffer."),
   HIT_SELECT(TrainingSettings.Mode.SWORD, "Hit Select", "Nimm den ersten Treffer an. Kontere im Rückstoß und sichere den Folgetreffer."),
   PUNISH_CRIT(TrainingSettings.Mode.SWORD, "Punish Crit", "Nutze die Fallphase aus dem gegnerischen Rückstoß für einen kritischen Konter."),
   JUMP_RESET(TrainingSettings.Mode.SWORD, "Jump-Reset", "Springe zum gegnerischen Treffer und vergleiche den Versatz mit einem Kontrollversuch."),
   STUN_WEB(TrainingSettings.Mode.UHC, "Stun-Web", "Stunne das Schild mit einem Doppelklick und fange den Bot vor der Landung im Netz."),
   WEB_DRAIN(TrainingSettings.Mode.UHC, "Web Drain", "Der Bot steht im Netz. Schöpfe seine Rettungswasserquelle, bevor er entkommt."),
   RE_LAVA(TrainingSettings.Mode.UHC, "Re-Lava", "Der Bot löscht den ersten Lavakontakt. Erfasse ihn anschließend erneut mit Lava."),
   HEAL(TrainingSettings.Mode.UHC, "Heilfenster", "Schaffe Abstand und beende eine echte Heilmahlzeit vor der Niederlage."),
   HEAD_WEB(TrainingSettings.Mode.UHC, "Head Web", "Setze ein Spinnennetz auf Augenhöhe des Bots."),
   OBI_TRAP(TrainingSettings.Mode.UHC, "Obi Trap", "Ummauere den Bot ohne Fluchtweg und schließe Kopf und Füße in Obsidian ein."),
   LOW_WEB_SMASH(TrainingSettings.Mode.MACE, "Cobweb-Smash", "Triff im Fallen die freien Beine des Bots unter dem Kopfnetz."),
   WIND_STUN_SLAM(TrainingSettings.Mode.MACE, "Wind-Stun-Slam", "Brich das Schild des fliegenden Bots mit der Axt und lande im selben Flug einen Smash."),
   PERFECT_WIND_CHARGE(TrainingSettings.Mode.MACE, "Perfect Wind Charge", "Erreiche mit einem Sprung und einer eigenen Windladung die maximale Höhe."),
   DOUBLE_ANCHOR(TrainingSettings.Mode.CRYSTAL, "Double Anchor", "Zünde zwei geladene Anker in Folge. Jeder Anker muss ein Bot-Totem auslösen."),
   SHIELD_TRIPLE_TAP(
      TrainingSettings.Mode.CRYSTAL,
      "Shield-Triple-Tap",
      "Nach dem ersten Pop hebt der Bot sein Schild. Brich es in der Luft mit der Axt und lande zwei Folgekristalle."
   ),
   CORNER_OBI_TRAP(TrainingSettings.Mode.UHC, "Corner Obi Trap", "Schließe Kopf und Füße des Bots in der Ecke in Obsidian ein, ohne Fluchtweg."),
   MACE_COUNTER_DTAP(
      TrainingSettings.Mode.HUGOSMP,
      "Mace-Konter · D-Tap",
      "Blocke den Wind-Charge-Mace-Sprung des Bots mit dem Schild und lande danach einen D-Tap."
   ),
   CORNER_WEB_DTAP(
      TrainingSettings.Mode.HUGOSMP,
      "Corner Web · D-Tap",
      "Webbe den Bot in der Ecke ein, brich sein Schild mit der Axt und lande einen D-Tap."
   ),
   SELF_WEB_CRYSTAL(
      TrainingSettings.Mode.CRYSTAL,
      "Self-Web · Boden-Crystal",
      "Der Bot webt sich selbst ein. Poppe ihn zweimal mit Boden-Kristallen, solange er im Netz steht."
   ),
   UPPERCUT(
      TrainingSettings.Mode.SWORD,
      "Uppercut",
      "Triff den Gegner im Rückstoß oder von einer tieferen Position unterhalb des Bots."
   ),
   ELY_STUN_SLAM(
      TrainingSettings.Mode.MACE,
      "Ely-Stun-Slam",
      "Fliege mit der Elytra an, deaktiviere das Schild mit der Axt und lande einen vollen Wind-Burst-Stun-Slam."
   ),
   SHIELD_TIMING(
      TrainingSettings.Mode.UHC,
      "Shield Timing",
      "Nur Schwert & Schild (ohne Axt): Blocke den Bot-Schlag sauber ab und kontere danach – nie panisch nach Zwischentreffern schlagen."
   ),
   MOMENTUM_BREAK(
      TrainingSettings.Mode.UHC,
      "Momentum Break",
      "Brich das Schild mit der Axt, führe beim sofortigen Bot-Auto-Hit einen Jump-Reset aus und lande den Schwert-Anschluss."
   ),
   WEB_DUNK(
      TrainingSettings.Mode.UHC,
      "Web / Lava Dunk",
      "Lande ohne Schild-Stun einen echten Crit mit dem Schwert und fange den Bot im Rückstoß direkt mit Cobweb oder Lava."
   ),
   ANTI_DRAIN(
      TrainingSettings.Mode.UHC,
      "Anti-Drain (Web-Escape)",
      "Du startest im Cobweb: Erst Schild halten, den Bot mit W-Tap wegschlagen und dann sicher hinter dir watern."
   ),
   HEAL_PUNISH(
      TrainingSettings.Mode.UHC,
      "Heal-Punish",
      "Der Bot gapt zu nah an dir. Bestrafe den Heilversuch aggressiv mit mindestens zwei Crits, bevor der Goldapfel wirkt."
   ),
   PCRIT_BREAK(
      TrainingSettings.Mode.SWORD,
      "P-Crit Break",
      "Nimm den Bot-Treffer an, öffne kurz Abstand und brich den P-Crit-Trade mit einem Sprint-KB-Hit + Folgetreffer."
   );

   private final TrainingSettings.Mode mode;
   private final String label;
   private final String hint;

   Drill(TrainingSettings.Mode var3, String var4, String var5) {
      this.mode = var3;
      this.label = var4;
      this.hint = var5;
   }

   public String label() {
      return this.label;
   }

   public String hint() {
      return this.hint;
   }

   public String standardGuide(TrainingSettings.Mode var1, DrillOptions var2, String var3) {
      return switch (this) {
         case DUEL -> {
            switch (var1) {
               case CRYSTAL:
                  yield "Mit Totem (3) ansprinten und treffen, Obsidian (6) setzen und Kristall (5) zünden.";
               case MACE:
                  yield "Mit Windladung (6) aufsteigen. Im Fallen mit der Mace (8 oder 9) treffen.";
               case SWORD:
                  yield "Mit dem Schwert (1) auf Reichweite gehen. Nach dem ersten Treffer Abstand für den Anschluss halten.";
               case UHC:
                  yield "Schwert (1) im Nahkampf, Armbrust (7) auf Distanz. Mit Deckung Zeit für den Goldapfel (3) gewinnen.";
               case HUGOSMP:
                  yield "Schild (1), Mace (2) mit Windladung (4) sowie Obsidian (5) und Kristalle (6) verbinden.";
               default:
                  throw new MatchException(null, null);
            }
         }
         case PASSIVE -> {
            switch (var1) {
               case CRYSTAL:
                  yield "Triff das Ziel mit Totem (3) oder Schwert (1). Übe Kristalle (5) auf Obsidian (6).";
               case MACE:
                  yield "Windladung (6) unter dir zünden. Mit der Mace (8) im Fallen auf das Ziel schlagen.";
               case SWORD:
                  yield "Mit dem Schwert (1) treffen. Wiederhole den Schlag aus verschiedenen Abständen.";
               case UHC:
                  yield "Wechsle am Ziel zwischen Schwert (1), Netz (8) und Eimer (6 oder 9).";
               case HUGOSMP:
                  yield "Übe Mace (2), Axt (8), Windladung (4), Cobweb und Kristalle (6) auf Obsidian (5).";
               default:
                  throw new MatchException(null, null);
            }
         }
         case TOTEM_POPS -> {
            switch (var1) {
               case CRYSTAL:
                  yield "Totem (3) zum Nahkampftreffer, Obsidian (6), Kristall (5). Löse ein Totem des Bots aus.";
               case MACE:
                  yield "Mit Windladungen (6) und Mace (8) angreifen, bis das Totem des Bots auslöst.";
               case HUGOSMP:
                  yield "Obsidian (5), Kristall (6) oder Mace (2) nutzen, bis das Totem des Bots auslöst.";
               case SWORD:
               case UHC:
                  yield "Mit dem Schwert (1) am Bot bleiben und nachsetzen, bis sein Totem auslöst.";
               default:
                  throw new MatchException(null, null);
            }
         }
         case SHIELD -> "Stell dich vor den blockenden Bot. Triff sein Schild mit dem Schwert (1).";
         case HIT_CRYSTAL -> "Schwert (1) oder Totem-Sprint (3). Obsidian (6) in die Flugbahn setzen und Kristall (5) zünden.";
         case D_TAP -> "Schwert (1) oder Totem-Sprint (3). Obsidian (6), Kristall (5), dann den zweiten im nächsten Fenster zünden. Zwei Pops ohne Landung.";
         case TRIPLE_TAP -> "Schwert (1) oder Totem-Sprint (3) → Obsidian (6) → Kristall (5) → Totem aufs Luftziel → zwei Kristalle. Alle drei Kristalle müssen vor der Landung je ein Totem auslösen.";
         case LEDGE_DASH -> "Von der Kante absteigen. Unten Obsidian (6) setzen und mit einem Kristall (5) das höhere Ziel treffen.";
         case SAFE_ANCHOR -> "Anker (8) setzen, mit Glowstone (9) laden. Hinter Obsidian (6) gehen und mit Totem (3) rechtsklicken.";
         case TAP_ANCHOR -> "Schwert oder Totem-Sprint, Obsidian (6), zwei Kristalle (5). Anker (8) setzen, laden (9) und zünden.";
         case RETOTEM -> "Starte mit Totem (3) und Nebenhand. Beide dürfen poppen. Fülle nach, bis dein letztes Totem dich rettet.";
         case DOUBLE_HAND -> var2.tripleBurst()
            ? "Totem (3) und Nebenhand-Totem halten. Nach Pops nachlegen und den letzten Kristall des Triple Taps überstehen."
            : "Totem (3) und Nebenhand-Totem halten. Den Folgekristall mit dem verbliebenen Totem überstehen.";
         case SMASH -> "Windladung (6) auf den Boden werfen. Mit der Mace (8) im Fallen treffen.";
         case WIND_CHAIN -> "Windladung (6) zum Start. Mit der Wind-Burst-Mace (8) treffen und nach dem Aufwind vor der Landung erneut zuschlagen.";
         case LOW_WEB_SMASH -> "Triff unter dem Netz nach deiner Windladung oder mit einer Wind-Burst-Mace. Jeder Smash im selben Flug zählt. Bodenkontakt beendet die Serie.";
         case WIND_STUN_SLAM -> "Der Bot springt bereits. Bewege dich für deinen Fallstart. Deaktiviere sein Schild mit der Axt und mace ihn im selben Flug.";
         case PERFECT_WIND_CHARGE -> "Ziele senkrecht auf den Boden und stimme Sprung und Windladung aufeinander ab. Grün zeigt einen perfekten Einzelwurf.";
         case STUN_SLAM -> {
            boolean var4 = var2.startHeight() > 0 || var2.slamScene() == DrillOptions.SlamScene.PAIRED_FALL;
            yield var4
               ? "Mit einer Bewegung starten. Axt (4) aufs Schild → voller Smash mit der Wind-Burst-Mace (8) und Auftrieb."
               : "Mit Windladung (6) aufsteigen. Axt (4) aufs Schild → voller Smash mit der Wind-Burst-Mace (8) und Auftrieb.";
         }
         case BACKSTAB -> "Seitlich hinter den Bot gelangen. Mit der Mace (8) im Fallen an seinem gehobenen Schild vorbeischlagen.";
         case SHIELD_DRAIN -> "Schlage frontal mit Schwert (1), Axt (4) oder Mace (8/9) auf das gehobene Schild, bis es durch Verschleiß zerbricht.";
         case PEARL_CATCH -> "Perle (7) hochwerfen, Windladung (6) hinterher. Nach dem Luft-Teleport mit der Mace (8) treffen.";
         case ELYTRA_SMASH -> "Die Elytra ist angelegt. Starte den Gleitflug und wechsle vor dem Bot zur Brustplatte. Triff im Fallen mit der Mace.";
         case SPACING -> "Mit dem Schwert (1) zuerst treffen. Abstand halten und " + var2.comboLength() + " Treffer in Folge vor dem Gegentreffer landen.";
         case HIT_SELECT -> "Den ersten Schlag nehmen. Mit dem Schwert (1) im Rückstoß kontern und nach der Landung erneut treffen.";
         case PUNISH_CRIT -> "Lass dich vom Bot hochschlagen. In der Fallphase mit dem Schwert (1) einen kritischen Gegentreffer landen.";
         case JUMP_RESET -> "Im Moment des gegnerischen Schwerttreffers mit " + var3 + " springen. Vergleiche den Rückstoß mit einem Versuch ohne Sprung.";
         case HEAD_WEB -> "Dränge den Bot an die Wand. Ziele neben seinem Kopf auf die Wandfläche und setze das Netz in seinen Kopfblock.";
         case OBI_TRAP -> "Schließe Kopf und Füße in deinem Obsidian ein. Nutze die Arenawand und verbaue alle Wege zum Kriechen oder Herausspringen.";
         case CORNER_OBI_TRAP -> "Nutze die Ecke aus zwei Wänden und schließe Kopf und Füße des Bots ohne Fluchtweg in deinem Obsidian ein.";
         case STUN_WEB -> "Triff das gehobene Schild zweimal schnell. Der zweite Treffer muss mit der Axt das Schild deaktivieren. Setze das Netz vor der Landung.";
         case WEB_DRAIN -> "Wassereimer (6) seitlich leeren. Mit dem leeren Eimer die Rettungsquelle des Bots abschöpfen, solange er im Netz steckt.";
         case RE_LAVA -> "Lava (9) an die Füße setzen. Nach dem Löschen die zweite Lava aus dem Inventar holen und den Bot erneut erfassen.";
         case HEAL -> "Mit Brettern (5) oder Netz (8) Platz schaffen. Den Goldapfel (3) oder Golden Head (2) vollständig essen.";
         case DOUBLE_ANCHOR -> "Anker (8) setzen, mit Glowstone (9) laden und zünden. Sofort den zweiten Anker setzen, laden und zünden – zwei Pops in Folge.";
         case SHIELD_TRIPLE_TAP -> "Eröffnung → Obsidian (6) → Kristall 1 (5) → Bot hebt Schild → Axt in der Luft aufs Schild → zwei Folgekristalle vor der Landung.";
         case MACE_COUNTER_DTAP -> "Der Bot springt mit Windladung und Mace auf dich. Blocke den Smash mit dem Schild (1) und D-Tappe ihn mit Obsidian (5) und zwei Kristallen (6).";
         case CORNER_WEB_DTAP -> "Webbe den Bot in der Ecke ein, brich sein Schild mit der Axt (8) und D-Tappe ihn mit zwei Kristallen (6) auf Obsidian (5).";
         case SELF_WEB_CRYSTAL -> "Der Bot setzt ein Cobweb an seine Füße. Zünde Boden-Kristalle (5) auf Obsidian (6) auf Fußhöhe des Bots für zwei Totem-Pops.";
         case UPPERCUT -> "Nimm den Sprint-Treffer des Bots an und kontere im Rückstoß oder triff den Bot mit dem Schwert (1) aus einer tieferen Position.";
         case ELY_STUN_SLAM -> "Die Elytra ist angelegt. Fliege an, beende den Gleitflug, brich das Schild mit der Axt (4) und lande einen vollen Wind-Burst-Smash (8).";
         case SHIELD_TIMING -> "Nur Schwert (1) & Schild – ohne Axt! Blocke den Bot-Angriff mit dem Schild und kontere direkt. Nach einem Zwischentreffer erst wieder blocken.";
         case MOMENTUM_BREAK -> "Deaktiviere das Schild mit der Axt (4), mach beim Bot-Auto-Hit einen Jump-Reset (" + var3 + ") und wechsle sofort aufs Schwert (1).";
         case WEB_DUNK -> switch (var2.dunkType()) {
            case WEB -> "Lande einen echten Crit mit dem Schwert (1) und platziere direkt das Cobweb (8) in die Flugbahn des Bots.";
            case LAVA -> "Lande einen echten Crit mit dem Schwert (1) und platziere direkt Lava (9) unter den aufsteigenden Bot.";
            case WEB_LAVA -> "Lande einen Crit (1), fange den Bot im Cobweb (8) und setze direkt Lava (9) darüber.";
         };
         case ANTI_DRAIN -> "Du steckst im Cobweb! Halte erst das Schild, schlage den Bot mit W-Tap weg und setze dann Wasser (6) außer Reichweite, um sicher zu entkommen.";
         case HEAL_PUNISH -> "Der angeschlagene Bot versucht zu nah zu gappen. Stürme los und lande mindestens 2 Crits mit dem Schwert (1), bevor sein Goldapfel wirkt.";
         case PCRIT_BREAK -> "Lass den Bot zuerst treffen, löse kurz W für Abstand und brich die Serie mit einem vollen Sprint-KB-Treffer (>85 %) + Anschluss.";
      };
   }

   boolean maceTechnique() { return mode == TrainingSettings.Mode.MACE; }

   public boolean series() {
      return this != DUEL && this != PASSIVE && this != TOTEM_POPS && this != SHIELD;
   }

   public boolean supports(TrainingSettings.Mode var1) {
      if (this == TOTEM_POPS) return var1.allowsBotTotems();
      return this != DOUBLE_HAND && this != SMASH && this != WIND_CHAIN && (this.mode == null || this.mode == var1);
   }

   public boolean requiresPopChain() {
      return this == D_TAP || this == TRIPLE_TAP || this == SHIELD_TRIPLE_TAP
         || this == SELF_WEB_CRYSTAL || this == DOUBLE_ANCHOR || this == MACE_COUNTER_DTAP || this == CORNER_WEB_DTAP;
   }

   public boolean requiresAirPopChain() {
      return this == D_TAP || this == TRIPLE_TAP || this == SHIELD_TRIPLE_TAP || this == MACE_COUNTER_DTAP;
   }

   public boolean isObiTrap() {
      return this == OBI_TRAP || this == CORNER_OBI_TRAP;
   }

   public boolean needsCornerWalls() {
      return this == CORNER_OBI_TRAP || this == CORNER_WEB_DTAP;
   }

   public boolean hidesPresets() {
      return this == LOW_WEB_SMASH || this == PERFECT_WIND_CHARGE || this == D_TAP;
   }

   public static List<Drill> forMode(TrainingSettings.Mode var0) {
      return Arrays.stream(values()).filter(var1 -> var1.supports(var0)).toList();
   }

   public boolean totemDefense() {
      return this == DOUBLE_HAND || this == RETOTEM;
   }

   public boolean slamOptions() { return this == STUN_SLAM || this == WIND_STUN_SLAM; }

   public boolean centeredMaceDrill() {
      return this == LOW_WEB_SMASH || this == WIND_STUN_SLAM;
   }

   boolean needsShield() {
      return this == SHIELD || this == STUN_SLAM || this == ELY_STUN_SLAM || this == WIND_STUN_SLAM || this == BACKSTAB
         || this == SHIELD_DRAIN || this == STUN_WEB || this == MOMENTUM_BREAK || this == CORNER_WEB_DTAP;
   }

   public boolean needsSwordAttack() {
      return this == SPACING || this == HIT_SELECT || this == PUNISH_CRIT || this == JUMP_RESET
         || this == UPPERCUT || this == PCRIT_BREAK || this == HEAL;
   }
}

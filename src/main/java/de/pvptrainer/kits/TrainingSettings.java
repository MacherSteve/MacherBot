package de.pvptrainer.kits;
import de.pvptrainer.drill.Drill;

import java.util.Objects;

public record TrainingSettings(
   TrainingSettings.Mode mode,
   TrainingSettings.Difficulty difficulty,
   TrainingSettings.Movement movement,
   int maxHealth,
   boolean counterAttacks,
   boolean autoRespawn,
   TrainingSettings.DuelTier duelTier,
   TrainingSettings.DuelBand duelBand,
   boolean maceCenterLocked
) {
   public TrainingSettings(
      TrainingSettings.Mode mode,
      TrainingSettings.Difficulty difficulty,
      TrainingSettings.Movement movement,
      int maxHealth,
      boolean counterAttacks,
      boolean autoRespawn
   ) {
      this(mode, difficulty, movement, maxHealth, counterAttacks, autoRespawn, null, DuelBand.AUTO, false);
   }

   public TrainingSettings(Mode mode, Difficulty difficulty, Movement movement, int maxHealth,
                    boolean counterAttacks, boolean autoRespawn, DuelTier duelTier) {
      this(mode, difficulty, movement, maxHealth, counterAttacks, autoRespawn, duelTier, DuelBand.AUTO, false);
   }

   public TrainingSettings {
      Objects.requireNonNull(mode);
      Objects.requireNonNull(difficulty);
      Objects.requireNonNull(movement);
      // Profiles from before 1.6 only contain the three drill difficulty levels.
      if (duelTier == null) duelTier = DuelTier.fromLegacy(difficulty);
      if (duelBand == null) duelBand = DuelBand.AUTO;
      if (mode != Mode.MACE) maceCenterLocked = false;
      if (maxHealth < 10 || maxHealth > 60 || maxHealth % 10 != 0) {
         throw new IllegalArgumentException("Bot-Lebenspunkte müssen in Zehnerschritten zwischen 10 und 60 liegen.");
      }
   }

   public static TrainingSettings defaults(TrainingSettings.Mode var0) {
      byte var1 = switch (var0) {
         case MACE -> 30;
         default -> 20;
      };
      return new TrainingSettings(var0, TrainingSettings.Difficulty.HARD, TrainingSettings.Movement.STILL, var1, false, true);
   }

   public TrainingSettings forDrill(Drill var1) {
      boolean var2 = var1 == Drill.DUEL || var1.needsSwordAttack() || var1 == Drill.RETOTEM || var1 == Drill.DOUBLE_HAND
         || var1 == Drill.SHIELD_TIMING || var1 == Drill.ANTI_DRAIN;
      TrainingSettings.Movement var3 = TrainingSettings.Movement.STILL;
      if (var1 == Drill.DUEL) {
         var3 = this.mode != TrainingSettings.Mode.SWORD && this.mode != TrainingSettings.Mode.CRYSTAL && this.mode != TrainingSettings.Mode.HUGOSMP
            ? TrainingSettings.Movement.FOLLOW
            : TrainingSettings.Movement.STRAFE;
      } else if (var1.needsSwordAttack() || var1.totemDefense() || var1 == Drill.SHIELD_TIMING || var1 == Drill.ANTI_DRAIN) {
         var3 = TrainingSettings.Movement.FOLLOW;
      }

      int health = (var1 == Drill.DOUBLE_ANCHOR || var1 == Drill.CORNER_WEB_DTAP || var1 == Drill.MACE_COUNTER_DTAP) && this.maxHealth >= 20
         ? 10
         : (var1.requiresPopChain() && this.maxHealth == 40 ? 20 : this.maxHealth);
      return new TrainingSettings(mode, Difficulty.HARD, var3, health, var2, autoRespawn, duelTier, duelBand, maceCenterLocked);
   }

   public TrainingSettings withDifficulty(TrainingSettings.Difficulty var1) {
      return new TrainingSettings(mode, var1, movement, maxHealth, counterAttacks, autoRespawn, duelTier, duelBand, maceCenterLocked);
   }

   public TrainingSettings withMovement(TrainingSettings.Movement var1) {
      return new TrainingSettings(mode, difficulty, var1, maxHealth, counterAttacks, autoRespawn, duelTier, duelBand, maceCenterLocked);
   }

   public TrainingSettings withMaxHealth(int var1) {
      return new TrainingSettings(mode, difficulty, movement, var1, counterAttacks, autoRespawn, duelTier, duelBand, maceCenterLocked);
   }

   public TrainingSettings withCounterAttacks(boolean var1) {
      return new TrainingSettings(mode, difficulty, movement, maxHealth, var1, autoRespawn, duelTier, duelBand, maceCenterLocked);
   }

   public TrainingSettings withAutoRespawn(boolean var1) {
      return new TrainingSettings(mode, difficulty, movement, maxHealth, counterAttacks, var1, duelTier, duelBand, maceCenterLocked);
   }

   public TrainingSettings withDuelTier(DuelTier tier) {
      return new TrainingSettings(mode, difficulty, movement, maxHealth, counterAttacks, autoRespawn, Objects.requireNonNull(tier), duelBand, maceCenterLocked);
   }

   public TrainingSettings withDuelBand(DuelBand band) {
      return new TrainingSettings(mode, difficulty, movement, maxHealth, counterAttacks, autoRespawn, duelTier, Objects.requireNonNull(band), maceCenterLocked);
   }

   public TrainingSettings withMaceCenterLocked(boolean locked) {
      return new TrainingSettings(mode, difficulty, movement, maxHealth, counterAttacks, autoRespawn, duelTier, duelBand, locked);
   }

   public String duelRank() {
      return duelBand.label(duelTier);
   }

   public String difficultyLabel(Drill drill) {
      return drill == Drill.DUEL ? duelRank() : difficulty.label();
   }

   public enum DuelBand {
      AUTO, LOW, HIGH;
      public String label(DuelTier tier) {
         return switch (this) {
            case LOW -> "L" + tier.name();
            case HIGH -> "H" + tier.name();
            case AUTO -> "LT" + (tier.ordinal()+1) + "–HT" + (tier.ordinal()+1);
         };
      }
   }

   public enum DuelTier {
      T1, T2, T3, T4, T5;

      static DuelTier fromLegacy(Difficulty difficulty) {
         return switch (difficulty) {
            case EASY -> T5;
            case NORMAL -> T3;
            case HARD -> T1;
         };
      }
   }

   public enum Difficulty {
      EASY("Leicht"),
      NORMAL("Normal"),
      HARD("Schwer");

      private final String label;

      Difficulty(String var3) {
         this.label = var3;
      }

      public String label() {
         return this.label;
      }
   }

   public enum Mode {
      CRYSTAL("Crystal"),
      MACE("Mace"),
      SWORD("Sword"),
      UHC("UHC"),
      HUGOSMP("HugoSMP");

      private final String label;

      Mode(String var3) {
         this.label = var3;
      }

      public boolean allowsBotTotems() {
         return this == CRYSTAL || this == MACE || this == HUGOSMP;
      }

      public String label() {
         return this.label;
      }
   }

   public enum Movement {
      STILL("Stillstehen"),
      FOLLOW("Verfolgen"),
      STRAFE("Seitwärts");

      private final String label;

      Movement(String var3) {
         this.label = var3;
      }

      public String label() {
         return this.label;
      }
   }
}

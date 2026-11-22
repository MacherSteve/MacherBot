package de.pvptrainer.progress;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.kits.TrainingSettings;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.LoggerFactory;

public final class DrillHistory {
   private static final Gson JSON = new Gson();
   private final Map<String, List<DrillHistory.Result>> series = new LinkedHashMap<>();
   private final Path file;

   public DrillHistory(Path var1) {
      this.file = var1;
      if (Files.isRegularFile(var1)) {
         try {
            try (BufferedReader reader = Files.newBufferedReader(var1)) {
               Map<String, List<Result>> stored = JSON.fromJson(reader, new TypeToken<Map<String, List<Result>>>() {}.getType());
               if (stored != null) {
                  stored.forEach((key, results) -> {
                     if (key != null && results != null) {
                        this.series.put(key, new ArrayList<>(results.stream().filter(Objects::nonNull).limit(10).toList()));
                     }
                  });
               }
            }
         } catch (RuntimeException | IOException var14) {
            LoggerFactory.getLogger("pvptrainer").warn("Übungsserien konnten nicht gelesen werden", var14);
         }
      }
   }

   public static final String ENCLOSED_TRAP = "-enclosed";

   public static String key(TrainingSettings var0, DrillOptions var1, String var2) {
      // Keep the same series when a legacy "Sofort blocken" setup is reselected on the Mace slider.
      if (var1.overrides().containsKey(DrillOptions.Field.SHIELD_BREAK_TICKS)
            && var1.shieldBreakTicks(var0.mode()) == 0 && DrillOptions.Field.REBLOCK.visible(var1)) {
         var1 = var1.withIndex(DrillOptions.Field.SHIELD_BREAK_TICKS, DrillOptions.Field.SHIELD_BREAK_TICKS.indexOfNumber(-1))
                    .withIndex(DrillOptions.Field.REBLOCK, 1);
      }
      // Preserve the 1.5 key for technique exercises. In a duel the tier is the difficulty.
      StringBuilder var3 = new StringBuilder("rules-1.9|TrainingSettings[mode=").append(var0.mode())
         .append(var1.drill() == Drill.DUEL ? ", duelTier=" + var0.duelRank() : ", difficulty=" + var0.difficulty().name())
         .append(", movement=").append(var0.movement()).append(", maxHealth=").append(var0.maxHealth())
         .append(", counterAttacks=").append(var0.counterAttacks()).append(", autoRespawn=").append(var0.autoRespawn())
         .append("]|").append(var1.drill()).append('|').append(var2);
      DrillOptions.Field[] var7;
      int var6 = (var7 = DrillOptions.Field.values()).length;

      for (int var5 = 0; var5 < var6; var5++) {
         DrillOptions.Field var4 = var7[var5];
         if (var4.ordinal() < DrillOptions.Field.HINTS.ordinal()) {
            var3.append('|').append(var1.value(var4));
         }
      }

      if (var1.drill() == Drill.D_TAP
         || var1.drill() == Drill.TRIPLE_TAP
         || var1.drill() == Drill.SHIELD_TRIPLE_TAP
         || var1.drill() == Drill.TAP_ANCHOR
         || var1.drill() == Drill.MACE_COUNTER_DTAP
         || var1.drill() == Drill.CORNER_WEB_DTAP) {
         var3.append("|crystal-windows-v2");
      }

      if (var1.drill() == Drill.HIT_CRYSTAL
         || var1.drill() == Drill.D_TAP
         || var1.drill() == Drill.TRIPLE_TAP
         || var1.drill() == Drill.SHIELD_TRIPLE_TAP
         || var1.drill() == Drill.TAP_ANCHOR
         || var1.drill() == Drill.MACE_COUNTER_DTAP) {
         var3.append("|melee-with-held-totem");
      }

      if (var1.drill().requiresPopChain()) {
         var3.append("|unbroken-air-and-pop-per-blast");
      }
      if (var1.drill() == Drill.STUN_WEB) {
         var3.append("|axe-stun-before-lift-v2");
      }
      if (var1.drill() == Drill.HEAD_WEB || var1.drill() == Drill.OBI_TRAP) var3.append("|wall-start");
      if (var1.drill().needsCornerWalls()) var3.append("|corner-start");

      if (var1.drill() == Drill.ELYTRA_SMASH) var3.append("|equipped-glider");
      if (var1.drill() == Drill.LOW_WEB_SMASH) var3.append("|airborne-wind-smash");
      if (var0.maceCenterLocked()) var3.append("|mace-center");
      if (var1.drill() == Drill.TRIPLE_TAP || var1.drill() == Drill.SHIELD_TRIPLE_TAP) {
         var3.append("|air-contact-after-first-crystal");
      }

      if (var1.drill() == Drill.DOUBLE_HAND) {
         var3.append("|tracked-refill-hand-and-pursuit");
      }

      if (var1.drill() == Drill.RETOTEM) {
         var3.append("|refill-sprint-crystal-v2");
      }

      if (var1.drill() == Drill.STUN_SLAM && (var1.startHeight() > 0 || var1.slamScene() == DrillOptions.SlamScene.PAIRED_FALL)) {
         var3.append("|air-start");
      }

      var6 = (var7 = DrillOptions.Field.values()).length;

      for (int var10 = 0; var10 < var6; var10++) {
         DrillOptions.Field var9 = var7[var10];
         if (var9 == DrillOptions.Field.ITEM_CONSUMPTION) {
            if (var0.mode() != TrainingSettings.Mode.SWORD) {
               var3.append("|utility-1.9.3=").append(var1.value(var9, var0.mode()));
            } else if (var1.itemConsumption(var0.mode())) {
               var3.append('|').append(var9.name()).append('=').append(var1.value(var9, var0.mode()));
            }
            continue;
         }
         if (var9.ordinal() > DrillOptions.Field.HINTS.ordinal()
            && var9.visible(var1, var0.mode())
            && (var1.overrides().containsKey(var9) || var9 == DrillOptions.Field.TARGET_HEIGHT)) {
            var3.append('|').append(var9.name()).append('=').append(var1.value(var9));
         }
      }

      if (var1.usesJumpCountdown()) {
         var3.append("|swing-cue");
      }

      try {
         String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(var3.toString().getBytes(StandardCharsets.UTF_8)));
         return var1.drill().isObiTrap() ? hash + ENCLOSED_TRAP : hash;
      } catch (NoSuchAlgorithmException var8) {
         throw new IllegalStateException(var8);
      }
   }

   public List<DrillHistory.Result> get(String var1) {
      return this.series.getOrDefault(var1, List.of());
   }

   void record(String var1, DrillHistory.Result var2) {
      List var3 = this.series.computeIfAbsent(var1, var0 -> new ArrayList<>());
      if (var3.size() >= 10) {
         var3.remove(0);
      }

      var3.add(var2);

      while (this.series.size() > 256) {
         this.series.remove(this.series.keySet().iterator().next());
      }

      try {
         Files.createDirectories(this.file.getParent());
         Path var4 = Files.createTempFile(this.file.getParent(), "serien-", ".tmp");

         try {
            try (BufferedWriter writer = Files.newBufferedWriter(var4)) {
               JSON.toJson(this.series, writer);
            }
            try {
               Files.move(var4, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException var24) {
               Files.move(var4, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
         } finally {
            Files.deleteIfExists(var4);
         }
      } catch (IOException var28) {
         LoggerFactory.getLogger("pvptrainer").warn("Übungsserie konnte nicht gespeichert werden", var28);
      }
   }

   public record Result(boolean won, int ticks, double controlDistance, String reason, List<String> timeline) {
      public Result(boolean won, int ticks, double controlDistance, String reason, List<String> timeline) {
         if (reason == null || reason.isBlank()) {
            reason = won ? "Geschafft" : "Versuch beendet";
         }

         timeline = timeline == null ? List.of() : List.copyOf(timeline);
         this.won = won;
         this.ticks = ticks;
         this.controlDistance = controlDistance;
         this.reason = reason;
         this.timeline = timeline;
      }

      public Result(boolean won, int ticks, double controlDistance) {
         this(won, ticks, controlDistance, null, List.of());
      }
   }
}

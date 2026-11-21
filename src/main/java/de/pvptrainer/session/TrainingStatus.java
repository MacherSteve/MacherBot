package de.pvptrainer.session;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.progress.DrillHistory;
import de.pvptrainer.kits.TrainingSettings;

import java.util.List;

public record TrainingStatus(
   boolean active,
   String message,
   int rounds,
   int hits,
   float health,
   float maxHealth,
   int pops,
   int successes,
   int attempts,
   int medianTicks,
   String progress,
   List<String> timeline,
   List<DrillHistory.Result> recent,
   TrainingSettings.Mode mode,
   Drill drill,
   String seriesKey,
   long startToken,
   ObiTimer obiTimer,
   double windMeters
) {
   public record ObiTimer(int ticks, int rate, double bestSeconds, boolean runningOrWon) {
      double seconds() { return ticks / (double)rate; }
      public boolean burning() { return runningOrWon && seconds() < bestSeconds; }
      public String text() { return format(seconds()); }
      public static String format(double seconds) { return String.format(java.util.Locale.GERMANY, "%.2f s", seconds); }
   }

   public TrainingStatus {
      timeline = List.copyOf(timeline);
      recent = List.copyOf(recent);
   }

   public TrainingStatus(
      boolean active,
      String message,
      int rounds,
      int hits,
      float health,
      float maxHealth,
      int pops,
      int successes,
      int attempts,
      int medianTicks,
      String progress,
      List<String> timeline,
      List<DrillHistory.Result> recent,
      TrainingSettings.Mode mode,
      Drill drill,
      String seriesKey,
      long startToken
   ) {
      this(active, message, rounds, hits, health, maxHealth, pops, successes, attempts, medianTicks, progress,
         timeline, recent, mode, drill, seriesKey, startToken, null, 0);
   }

   public boolean waitingForStart() {
      return this.startToken != 0L;
   }

   public String exercise() {
      return this.mode != null && this.drill != null ? this.mode.label() + " · " + this.drill.label() : "";
   }

   public static TrainingStatus idle() {
      return new TrainingStatus(false, "Bereit", 0, 0, 0.0F, 0.0F, 0, 0, 0, -1, "", List.of(), List.of(), null, null, "", 0L);
   }
}

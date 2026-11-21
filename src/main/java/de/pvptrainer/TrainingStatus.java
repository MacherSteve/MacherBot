package de.pvptrainer;

import java.util.List;

record TrainingStatus(
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
   record ObiTimer(int ticks, int rate, double bestSeconds, boolean runningOrWon) {
      double seconds() { return ticks / (double)rate; }
      boolean burning() { return runningOrWon && seconds() < bestSeconds; }
      String text() { return format(seconds()); }
      static String format(double seconds) { return String.format(java.util.Locale.GERMANY, "%.2f s", seconds); }
   }

   TrainingStatus {
      timeline = List.copyOf(timeline);
      recent = List.copyOf(recent);
   }

   TrainingStatus(
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

   boolean waitingForStart() {
      return this.startToken != 0L;
   }

   String exercise() {
      return this.mode != null && this.drill != null ? this.mode.label() + " · " + this.drill.label() : "";
   }

   static TrainingStatus idle() {
      return new TrainingStatus(false, "Bereit", 0, 0, 0.0F, 0.0F, 0, 0, 0, -1, "", List.of(), List.of(), null, null, "", 0L);
   }
}

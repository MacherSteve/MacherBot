package de.pvptrainer.progress;

import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.WindHeight;
import de.pvptrainer.kits.TrainingSettings;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** Pure-Java aggregation engine transforming raw TrainingProgress runs into concrete, real PvP metrics. */
public final class TrainingStats {
    private static final long WEEK_MILLIS = 7L * 86_400_000L;

    public enum TimeRange {
        TODAY("Heute"),
        WEEK("7 Tage"),
        ALL("Gesamt");

        private final String label;
        TimeRange(String label) { this.label = label; }
        public String label() { return label; }

        public boolean matches(long runTimeMillis, long nowMillis, ZoneId zone) {
            if (this == ALL) return true;
            if (runTimeMillis <= 0L) return false;
            if (this == WEEK) return runTimeMillis >= nowMillis - WEEK_MILLIS;
            long startOfDay = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli();
            return runTimeMillis >= startOfDay;
        }
    }

    public enum ChartAxis {
        FALL_METERS,
        WIND_METERS,
        DUEL_IMPACT,
        SECONDS;

        public static ChartAxis forDrill(Drill drill) {
            if (drill == null) return SECONDS;
            return switch (drill) {
                case STUN_SLAM, WIND_STUN_SLAM, ELY_STUN_SLAM, ELYTRA_SMASH, LOW_WEB_SMASH,
                     PEARL_CATCH, BACKSTAB, SMASH, WIND_CHAIN -> FALL_METERS;
                case PERFECT_WIND_CHARGE -> WIND_METERS;
                case DUEL -> DUEL_IMPACT;
                default -> SECONDS;
            };
        }
    }

    public record FailureCount(String reason, int count, int totalFailures) {
        public int percent() { return totalFailures <= 0 ? 0 : (int) Math.round(100.0 * count / totalFailures); }
    }

    public record DrillSummary(
        TrainingSettings.Mode mode,
        Drill drill,
        int attempts,
        int wins,
        int bestStreak,
        double bestSeconds,
        double maxFallMeters,
        double maxWindMeters,
        RoundMetrics metrics
    ) {
        public int winRatePct() { return attempts <= 0 ? 0 : (int) Math.round(100.0 * wins / attempts); }

        public String headline() {
            if (drill == Drill.PERFECT_WIND_CHARGE && maxWindMeters > 0.0) {
                return "Max " + formatMeters(maxWindMeters);
            }
            if (ChartAxis.forDrill(drill) == ChartAxis.FALL_METERS && maxFallMeters > 0.0) {
                return winRatePct() + " % · Max " + formatMeters(maxFallMeters);
            }
            if (drill.isObiTrap() && Double.isFinite(bestSeconds)) {
                return "Best " + formatSeconds(bestSeconds);
            }
            if (attempts > 0) {
                return winRatePct() + " % (" + wins + "/" + attempts + ")";
            }
            if (metrics.botPops() > 0) return metrics.botPops() + " Pops";
            if (metrics.maceSmashes() > 0) return metrics.maceSmashes() + " Smashes";
            return "Freies Üben";
        }
    }

    public record ModeOverview(
        TrainingSettings.Mode mode, // null = Gesamt
        TimeRange range,
        int scoredAttempts,
        int scoredWins,
        int practiceEntries,
        double totalSeconds,
        int duelWins,
        int duelLosses,
        int dTapWins,
        int tripleTapWins,
        int bestStreak,
        double bestObiTrapSeconds,
        TrainingSettings.Mode topMode,
        RoundMetrics totals,
        List<DrillSummary> topDrills
    ) {
        public int totalRounds() { return scoredAttempts + practiceEntries; }
        public int winRatePct() { return scoredAttempts <= 0 ? 0 : (int) Math.round(100.0 * scoredWins / scoredAttempts); }
        public int duelTotal() { return duelWins + duelLosses; }
        public int duelWinRatePct() { return duelTotal() <= 0 ? 0 : (int) Math.round(100.0 * duelWins / duelTotal()); }
        public boolean isEmpty() { return scoredAttempts == 0 && practiceEntries == 0 && !totals.hasActivity(); }
    }

    public record DrillReport(
        TrainingSettings.Mode mode,
        Drill drill,
        TimeRange range,
        ChartAxis axis,
        List<TrainingProgress.Run> scoredRuns,
        int attempts,
        int wins,
        int currentStreak,
        int bestStreak,
        double bestSeconds,
        double medianSeconds,
        double maxFallMeters,
        double avgFallMeters,
        double maxWindMeters,
        RoundMetrics totals,
        List<FailureCount> topFailures
    ) {
        public int winRatePct() { return attempts <= 0 ? 0 : (int) Math.round(100.0 * wins / attempts); }

        public double chartValue(TrainingProgress.Run run) {
            RoundMetrics m = run.metrics();
            return switch (axis) {
                case FALL_METERS -> m.maxFallMeters();
                case WIND_METERS -> m.maxWindMeters() > 0.0 ? m.maxWindMeters() : parseWindMeters(run.reason());
                case DUEL_IMPACT -> m.botPops() > 0 ? m.botPops() : m.damageHearts();
                case SECONDS -> run.seconds();
            };
        }

        public String formatChartValue(double value) {
            return switch (axis) {
                case FALL_METERS, WIND_METERS -> formatMeters(value);
                case DUEL_IMPACT -> totals.botPops() > 0
                    ? String.format(Locale.GERMAN, "%.0f Pops", value)
                    : formatHearts(value);
                case SECONDS -> formatSeconds(value);
            };
        }

        public String chartTitle() {
            return switch (axis) {
                case FALL_METERS -> "Fallhöhe je Versuch (m)";
                case WIND_METERS -> "Sprunghöhe je Wurf (m)";
                case DUEL_IMPACT -> totals.botPops() > 0 ? "Bot-Pops je Duell" : "Schaden je Duell (❤)";
                case SECONDS -> "Ausführungszeit je Versuch (s)";
            };
        }

        public double referenceLine() {
            if (axis == ChartAxis.WIND_METERS) return WindHeight.PERFECT;
            if (axis == ChartAxis.FALL_METERS && avgFallMeters > 0.0) return avgFallMeters;
            if (axis == ChartAxis.SECONDS && Double.isFinite(medianSeconds)) return medianSeconds;
            return Double.NaN;
        }
    }

    private TrainingStats() {}

    public static ModeOverview overview(List<TrainingProgress.Run> allRuns, TrainingSettings.Mode modeFilter, TimeRange range) {
        return overview(allRuns, modeFilter, range, System.currentTimeMillis(), ZoneId.systemDefault());
    }

    public static ModeOverview overview(List<TrainingProgress.Run> allRuns, TrainingSettings.Mode modeFilter,
                                        TimeRange range, long nowMillis, ZoneId zone) {
        int scoredAttempts = 0;
        int scoredWins = 0;
        int practiceEntries = 0;
        double totalSeconds = 0.0;
        int duelWins = 0;
        int duelLosses = 0;
        int dTapWins = 0;
        int tripleTapWins = 0;
        double bestObiTrap = Double.POSITIVE_INFINITY;
        RoundMetrics totals = RoundMetrics.EMPTY;

        Map<String, Integer> runningStreak = new HashMap<>();
        Map<String, Integer> maxStreakPerSetup = new HashMap<>();
        EnumMap<TrainingSettings.Mode, Integer> modeCounts = new EnumMap<>(TrainingSettings.Mode.class);

        record DrillKey(TrainingSettings.Mode mode, Drill drill) {}
        Map<DrillKey, List<TrainingProgress.Run>> byDrill = new LinkedHashMap<>();

        for (TrainingProgress.Run run : allRuns) {
            if (run == null || run.isTechniqueSubEntry()) continue;
            if (!range.matches(run.time(), nowMillis, zone)) continue;
            TrainingSettings.Mode runMode = parseMode(run.mode());
            if (runMode == null) continue;
            if (modeFilter != null && runMode != modeFilter) continue;

            Drill runDrill = parseDrill(run.drill());
            totalSeconds += run.seconds();
            totals = totals.add(run.metrics());
            modeCounts.merge(runMode, 1, Integer::sum);

            if (runDrill != null) {
                byDrill.computeIfAbsent(new DrillKey(runMode, runDrill), k -> new ArrayList<>()).add(run);
            }

            if (run.practiceOnly()) {
                practiceEntries++;
                continue;
            }

            scoredAttempts++;
            if (run.won()) scoredWins++;

            if (runDrill == Drill.DUEL) {
                if (run.won()) duelWins++; else duelLosses++;
            } else if (run.won() && (runDrill == Drill.D_TAP || runDrill == Drill.SELF_WEB_CRYSTAL || runDrill == Drill.CORNER_WEB_DTAP || runDrill == Drill.MACE_COUNTER_DTAP)) {
                dTapWins++;
            } else if (run.won() && (runDrill == Drill.TRIPLE_TAP || runDrill == Drill.SHIELD_TRIPLE_TAP)) {
                tripleTapWins++;
            }

            if (run.won() && runDrill != null && runDrill.isObiTrap()) {
                bestObiTrap = Math.min(bestObiTrap, run.seconds());
            }

            // Track best streak cleanly across setups
            if (run.won()) {
                int cur = run.startsAirChain() ? 1 : runningStreak.getOrDefault(run.setup(), 0) + 1;
                runningStreak.put(run.setup(), cur);
                maxStreakPerSetup.merge(run.setup(), cur, Math::max);
            } else if (!(runDrill == Drill.LOW_WEB_SMASH && !run.isTechniqueSubEntry())) {
                runningStreak.put(run.setup(), 0);
            }
        }

        int bestStreak = maxStreakPerSetup.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        TrainingSettings.Mode topMode = modeCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(modeFilter);

        List<DrillSummary> summaries = new ArrayList<>();
        for (var entry : byDrill.entrySet()) {
            DrillKey dk = entry.getKey();
            int att = 0, w = 0, curS = 0, bestS = 0;
            double bestSec = Double.POSITIVE_INFINITY, maxFall = 0.0, maxWind = 0.0;
            RoundMetrics dm = RoundMetrics.EMPTY;
            for (TrainingProgress.Run r : entry.getValue()) {
                dm = dm.add(r.metrics());
                maxFall = Math.max(maxFall, r.metrics().maxFallMeters());
                double wm = r.metrics().maxWindMeters() > 0.0 ? r.metrics().maxWindMeters() : parseWindMeters(r.reason());
                maxWind = Math.max(maxWind, wm);
                if (r.practiceOnly()) continue;
                att++;
                if (r.won()) {
                    w++;
                    bestSec = Math.min(bestSec, r.seconds());
                    curS = r.startsAirChain() ? 1 : curS + 1;
                    bestS = Math.max(bestS, curS);
                } else if (dk.drill() != Drill.LOW_WEB_SMASH) {
                    curS = 0;
                }
            }
            summaries.add(new DrillSummary(dk.mode(), dk.drill(), att, w, bestS, bestSec, maxFall, maxWind, dm));
        }
        summaries.sort(Comparator.comparingInt((DrillSummary s) -> s.attempts() + (s.metrics().hasActivity() ? 1 : 0)).reversed());

        return new ModeOverview(
            modeFilter, range, scoredAttempts, scoredWins, practiceEntries, totalSeconds,
            duelWins, duelLosses, dTapWins, tripleTapWins, bestStreak,
            Double.isFinite(bestObiTrap) ? bestObiTrap : Double.NaN,
            topMode, totals, List.copyOf(summaries)
        );
    }

    public static DrillReport drillReport(List<TrainingProgress.Run> allRuns, TrainingSettings.Mode mode,
                                          Drill drill, TimeRange range) {
        return drillReport(allRuns, mode, drill, range, System.currentTimeMillis(), ZoneId.systemDefault());
    }

    public static DrillReport drillReport(List<TrainingProgress.Run> allRuns, TrainingSettings.Mode mode,
                                          Drill drill, TimeRange range, long nowMillis, ZoneId zone) {
        List<TrainingProgress.Run> scored = new ArrayList<>();
        RoundMetrics totals = RoundMetrics.EMPTY;
        int wins = 0;
        int curStreak = 0;
        int bestStreak = 0;
        double bestSec = Double.POSITIVE_INFINITY;
        double maxFall = 0.0;
        double maxWind = 0.0;
        List<Double> winTimes = new ArrayList<>();
        LinkedHashMap<String, Integer> failCounts = new LinkedHashMap<>();
        int totalFailures = 0;

        for (TrainingProgress.Run run : allRuns) {
            if (run == null || run.isTechniqueSubEntry()) continue;
            if (!range.matches(run.time(), nowMillis, zone)) continue;
            if (!mode.name().equals(run.mode()) || !drill.name().equals(run.drill())) continue;

            totals = totals.add(run.metrics());
            maxFall = Math.max(maxFall, run.metrics().maxFallMeters());
            double wm = run.metrics().maxWindMeters() > 0.0 ? run.metrics().maxWindMeters() : parseWindMeters(run.reason());
            maxWind = Math.max(maxWind, wm);

            if (run.practiceOnly()) continue;

            scored.add(run);
            if (run.won()) {
                wins++;
                double s = run.seconds();
                winTimes.add(s);
                bestSec = Math.min(bestSec, s);
                curStreak = run.startsAirChain() ? 1 : curStreak + 1;
                bestStreak = Math.max(bestStreak, curStreak);
            } else {
                if (drill != Drill.LOW_WEB_SMASH) curStreak = 0;
                totalFailures++;
                String reason = run.reason().isBlank() ? "Fehlversuch" : run.reason();
                failCounts.merge(reason, 1, Integer::sum);
            }
        }

        double medianSec = Double.NaN;
        if (!winTimes.isEmpty()) {
            winTimes.sort(Double::compare);
            int mid = winTimes.size() / 2;
            medianSec = (winTimes.size() % 2 == 1) ? winTimes.get(mid) : (winTimes.get(mid - 1) + winTimes.get(mid)) / 2.0;
        }

        final int failsTotal = totalFailures;
        List<FailureCount> topFailures = failCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(3)
            .map(e -> new FailureCount(e.getKey(), e.getValue(), failsTotal))
            .toList();

        return new DrillReport(
            mode, drill, range, ChartAxis.forDrill(drill), List.copyOf(scored),
            scored.size(), wins, curStreak, bestStreak,
            Double.isFinite(bestSec) ? bestSec : Double.NaN,
            medianSec, maxFall, totals.avgFallMeters(), maxWind, totals, topFailures
        );
    }

    public static List<Drill> recordedDrillsForMode(List<TrainingProgress.Run> allRuns, TrainingSettings.Mode mode, TimeRange range) {
        return recordedDrillsForMode(allRuns, mode, range, System.currentTimeMillis(), ZoneId.systemDefault());
    }

    public static List<Drill> recordedDrillsForMode(List<TrainingProgress.Run> allRuns, TrainingSettings.Mode mode,
                                                    TimeRange range, long nowMillis, ZoneId zone) {
        LinkedHashMap<Drill, Integer> counts = new LinkedHashMap<>();
        for (TrainingProgress.Run run : allRuns) {
            if (run == null || run.isTechniqueSubEntry()) continue;
            if (!range.matches(run.time(), nowMillis, zone)) continue;
            if (!mode.name().equals(run.mode())) continue;
            Drill d = parseDrill(run.drill());
            if (d != null) counts.merge(d, 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .sorted(Map.Entry.<Drill, Integer>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .toList();
    }

    // --- German formatting helpers ---

    public static String formatInt(int value) {
        return NumberFormat.getIntegerInstance(Locale.GERMANY).format(value);
    }

    public static String formatSeconds(double seconds) {
        return Double.isFinite(seconds) ? String.format(Locale.GERMAN, "%.2f s", seconds) : "–";
    }

    public static String formatMeters(double meters) {
        return Double.isFinite(meters) && meters > 0.0 ? String.format(Locale.GERMAN, "%.1f m", meters) : "–";
    }

    public static String formatHearts(double hearts) {
        return Double.isFinite(hearts) && hearts > 0.0 ? String.format(Locale.GERMAN, "%.1f ❤", hearts) : "–";
    }

    public static String formatRatio(double ratio) {
        return Double.isFinite(ratio) && ratio > 0.0 ? String.format(Locale.GERMAN, "%.2f", ratio) : "–";
    }

    public static String formatPercent(double pct) {
        return Double.isFinite(pct) ? Math.round(pct) + " %" : "–";
    }

    public static String formatDuration(double totalSeconds) {
        long sec = Math.max(0L, Math.round(totalSeconds));
        if (sec < 60) return sec + " s";
        long min = sec / 60;
        long remSec = sec % 60;
        if (min < 60) return min + " m " + remSec + " s";
        long hrs = min / 60;
        long remMin = min % 60;
        return hrs + " h " + remMin + " m";
    }

    static double parseWindMeters(String reason) {
        if (reason == null || !reason.contains("m")) return 0.0;
        // Legacy format: "Windladung · 9,25 m"
        int sep = reason.indexOf('·');
        String tail = sep >= 0 ? reason.substring(sep + 1) : reason;
        tail = tail.replace("m", "").trim().replace(',', '.');
        try {
            double v = Double.parseDouble(tail);
            return Double.isFinite(v) && v > 0.0 && v < 100.0 ? v : 0.0;
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static TrainingSettings.Mode parseMode(String name) {
        if (name == null) return null;
        try { return TrainingSettings.Mode.valueOf(name); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static Drill parseDrill(String name) {
        if (name == null) return null;
        try { return Drill.valueOf(name); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}

package de.pvptrainer.progress;

import de.pvptrainer.TrainerClient;
import de.pvptrainer.analytics.BotArchive;
import de.pvptrainer.analytics.BotTelemetry;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillAttempt;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.drill.WindHeight;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.session.TrainingSession;
import de.pvptrainer.session.TrainingStatus;
import de.pvptrainer.ui.OutcomeOverlay;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.LoggerFactory;

public final class TrainingProgress {
    private static final Gson JSON = new Gson();
    private static final SystemToast.SystemToastId RESULT_TOAST = new SystemToast.SystemToastId(2000);
    private static UUID loadedPlayer;
    private static final List<Run> history = new ArrayList<>();
    private static final Set<String> knownSetups = new HashSet<>();
    private static final Map<TrainingSession, Long> signalledRound = new WeakHashMap<>();
    private static final Map<TrainingSession, Long> terminalRound = new WeakHashMap<>();
    private static final Map<String, Streak> streaks = new HashMap<>();

    public record Streak(int current, int best, int previousBest, long lastWinMillis) {
        public static final Streak EMPTY = new Streak(0, 0, 0, 0);
        public Streak next(Run run) {
            if (run.practiceOnly()) return this;
            if (!run.won() && run.drill().equals(Drill.LOW_WEB_SMASH.name()) && !run.setup().contains("/")) return this;
            if (!run.won()) return new Streak(0, best, best, 0);
            int count = run.startsAirChain() ? 1 : current + 1;
            return new Streak(count, Math.max(best, count), run.startsAirChain() ? best : previousBest, run.time());
        }
        public boolean burning() { return current > Math.max(1, previousBest); }
        public boolean recordHit() { return burning() && current == Math.max(1, previousBest) + 1; }
        public boolean nearRecord() {
            int gap = previousBest - current;
            return current > 0 && gap >= 0 && gap <= Math.clamp(previousBest / 5, 1, 3);
        }
        public String label() { return "Streak " + current + "  ·  Rekord " + best; }
    }

    private static void index(Run run) {
        knownSetups.add(run.setup());
        if (!run.practiceOnly()) {
            streaks.put(run.setup(), streaks.getOrDefault(run.setup(), Streak.EMPTY).next(run));
        }
    }

    public static synchronized Streak streak(UUID owner, String key) {
        if (owner == null || key == null || key.isEmpty()) return Streak.EMPTY;
        try {
            load(owner);
            return streaks.getOrDefault(key, Streak.EMPTY);
        } catch (IOException e) {
            return Streak.EMPTY;
        }
    }

    public static synchronized void endAirChain(UUID owner, String key) {
        Streak old = streak(owner, key);
        if (old.current() > 0) streaks.put(key, new Streak(0, old.best(), old.best(), 0));
    }

    public static synchronized double bestTrapTime(UUID owner, String key, List<DrillHistory.Result> recent, int rate) {
        try {
            load(owner);
            if (knownSetups.contains(key)) return history.stream()
                .filter(r -> !r.practiceOnly() && key.equals(r.setup()) && r.won())
                .mapToDouble(Run::seconds).min().orElse(Double.POSITIVE_INFINITY);
        } catch (IOException e) {
            LoggerFactory.getLogger("pvptrainer").warn("Obi-Trap-Bestzeit konnte nicht geladen werden", e);
        }
        return recent.stream().filter(DrillHistory.Result::won).mapToDouble(r -> r.ticks() / (double)rate)
            .min().orElse(Double.POSITIVE_INFINITY);
    }

    public static String techniqueKey(String setup, Drill technique) { return setup + "/" + technique.name(); }

    public static synchronized void technique(String setup, TrainingSettings settings, DrillOptions options, UUID owner,
                                       Drill technique, boolean won, int ticks, String reason) {
        if (setup == null || owner == null || options.drill() == technique) return;
        if (technique == Drill.TOTEM_POPS && !settings.mode().allowsBotTotems()) return;
        String key = techniqueKey(setup, technique);
        try {
            load(owner);
            if (!won && streaks.getOrDefault(key, Streak.EMPTY).current() == 0) return;
            append(owner, List.of(new Run(key, settings.mode().name(), options.drill().name(), System.currentTimeMillis(),
                won, ticks, reason, List.of(), settings.difficultyLabel(options.drill()), options.tickRate(),
                false, RoundMetrics.EMPTY, false)));
            if (streaks.get(key).recordHit()) {
                var client = Minecraft.getInstance();
                client.execute(() -> {
                    if (client.player != null && owner.equals(client.player.getUUID()))
                        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.25F, 0.6F));
                });
            }
        } catch (IOException e) {
            LoggerFactory.getLogger("pvptrainer").warn("Technikserie konnte nicht gespeichert werden", e);
        }
    }

    public record Run(String setup, String mode, String drill, long time, boolean won, int ticks,
                      String reason, List<String> timeline, String difficulty, int tickRate,
                      boolean startsAirChain, RoundMetrics metrics, boolean practiceOnly) {
        public Run(String setup, String mode, String drill, long time, boolean won, int ticks,
                   String reason, List<String> timeline, String difficulty, int tickRate) {
            this(setup, mode, drill, time, won, ticks, reason, timeline, difficulty, tickRate, false, RoundMetrics.EMPTY, false);
        }

        public Run(String setup, String mode, String drill, long time, boolean won, int ticks,
                   String reason, List<String> timeline, String difficulty, int tickRate, boolean startsAirChain) {
            this(setup, mode, drill, time, won, ticks, reason, timeline, difficulty, tickRate, startsAirChain, RoundMetrics.EMPTY, false);
        }

        public Run {
            if (tickRate < 1 || tickRate > 20) tickRate = 20;
            reason = reason == null ? "" : reason;
            difficulty = difficulty == null ? "" : difficulty;
            timeline = timeline == null ? List.of() : timeline.stream().filter(Objects::nonNull).toList();
            metrics = metrics == null ? RoundMetrics.EMPTY : metrics;
        }

        public double seconds() { return Math.max(0, ticks) / (double)tickRate; }

        /** Legacy helper kept only for binary compatibility; UI and toasts use real metrics. */
        public int score() { return won ? 1000 + (int)Math.round(1000.0 / (1.0 + seconds())) : 0; }

        public boolean isTechniqueSubEntry() { return setup != null && setup.contains("/"); }
    }

    private static Path file(UUID owner) {
        return FabricLoader.getInstance().getConfigDir().resolve("pvptrainer-progress").resolve(owner + ".jsonl");
    }

    private static synchronized void load(UUID owner) throws IOException {
        if (owner.equals(loadedPlayer)) return;
        Path path = file(owner);
        List<Run> rows = new ArrayList<>();
        if (Files.isRegularFile(path)) try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    Run run = JSON.fromJson(line, Run.class);
                    if (run != null && run.setup() != null && run.mode() != null && run.drill() != null && run.ticks() >= 0) {
                        rows.add(run);
                    }
                } catch (JsonParseException e) {
                    LoggerFactory.getLogger("pvptrainer").warn("Ungültiger Eintrag in der lokalen Bestenliste wurde übersprungen.");
                }
            }
        }
        history.clear();
        history.addAll(rows);
        knownSetups.clear();
        streaks.clear();
        rows.forEach(TrainingProgress::index);
        rows.stream().filter(Run::startsAirChain).map(Run::setup).distinct().forEach(key -> {
            int best = streaks.getOrDefault(key, Streak.EMPTY).best();
            streaks.put(key, new Streak(0, best, best, 0));
        });
        loadedPlayer = owner;
    }

    private static void append(UUID player, List<Run> rows) throws IOException {
        if (rows.isEmpty()) return;
        Path path = file(player);
        Files.createDirectories(path.getParent());
        StringBuilder batch = new StringBuilder();
        for (Run run : rows) batch.append(JSON.toJson(run)).append('\n');
        Files.writeString(path, batch, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        history.addAll(rows);
        rows.forEach(TrainingProgress::index);
    }

    private static List<Run> fromLegacy(String setup, TrainingSettings.Mode mode, Drill drill, List<DrillHistory.Result> existing) {
        if (knownSetups.contains(setup)) return List.of();
        return existing.stream().map(r -> new Run(setup, mode.name(), drill.name(), 0L, r.won(), r.ticks(),
            r.reason(), r.timeline(), "", 20, false, RoundMetrics.EMPTY, false)).toList();
    }

    public static synchronized List<Run> forScreen(Minecraft client) {
        UUID owner = client.player != null ? client.player.getUUID() : client.getUser().getProfileId();
        if (owner == null) return List.of();
        try {
            load(owner);
            TrainingStatus state = TrainerClient.status();
            if (state.mode() != null && state.drill() != null && !state.seriesKey().isEmpty()) {
                append(owner, fromLegacy(state.seriesKey(), state.mode(), state.drill(), state.recent()));
            }
            return List.copyOf(history);
        } catch (IOException e) {
            LoggerFactory.getLogger("pvptrainer").warn("Lokale Bestenliste konnte nicht gelesen werden", e);
            return owner.equals(loadedPlayer) ? List.copyOf(history) : List.of();
        }
    }

    public static void hideResultToast() {
        SystemToast.forceHide(Minecraft.getInstance().gui.toastManager(), RESULT_TOAST);
    }

    /** Records real actions from passive/interrupted rounds without altering streaks or drill win rates. */
    public static synchronized void recordPractice(String key, TrainingSettings settings, DrillOptions options,
                                                   UUID owner, int ticks, RoundMetrics metrics) {
        if (key == null || settings == null || options == null || owner == null || metrics == null || !metrics.hasActivity()) {
            return;
        }
        Run run = new Run(key, settings.mode().name(), options.drill().name(), Instant.now().toEpochMilli(),
            false, Math.max(0, ticks), "Freies Üben", List.of(), settings.difficultyLabel(options.drill()),
            options.tickRate(), false, metrics, true);
        try {
            load(owner);
            append(owner, List.of(run));
        } catch (IOException e) {
            LoggerFactory.getLogger("pvptrainer").warn("Übungsmetriken konnten nicht gespeichert werden", e);
        }
    }

    public static synchronized void record(DrillHistory series, String key, DrillHistory.Result result,
                                           TrainingSettings settings, DrillOptions options, UUID owner) {
        commit(series, key, result, settings, options, owner, true, RoundMetrics.EMPTY);
    }

    public static synchronized void record(DrillHistory series, String key, DrillHistory.Result result,
                                           TrainingSettings settings, DrillOptions options, UUID owner, RoundMetrics metrics) {
        commit(series, key, result, settings, options, owner, true, metrics);
    }

    public static synchronized void recordRound(DrillHistory series, String key, DrillHistory.Result result,
                                                TrainingSettings settings, DrillOptions options, UUID owner,
                                                TrainingSession session, long roundToken) {
        recordRound(series, key, result, settings, options, owner, session, roundToken, RoundMetrics.EMPTY);
    }

    public static synchronized void recordRound(DrillHistory series, String key, DrillHistory.Result result,
                                                TrainingSettings settings, DrillOptions options, UUID owner,
                                                TrainingSession session, long roundToken, RoundMetrics metrics) {
        boolean first = !Objects.equals(signalledRound.put(session, roundToken), roundToken);
        BotArchive.add(session, key, settings, options, owner, roundToken, session.botTelemetry(),
            result.won() ? "PLAYER_WIN" : "PLAYER_LOSS");
        commit(series, key, result, settings, options, owner, first || !result.won(), metrics);
    }

    public static void botRoundClosed(TrainingSession session, String key, TrainingSettings settings, DrillOptions options,
                                      UUID owner, long roundToken, BotTelemetry.Snapshot snapshot) {
        BotArchive.add(session, key, settings, options, owner, roundToken, snapshot, "UNSCORED");
    }

    public static synchronized boolean freeOutcome(TrainingSession session, DrillHistory series, String key,
                                                   TrainingSettings settings, DrillOptions options, UUID owner,
                                                   long roundToken, int ticks, boolean waiting, boolean defeated,
                                                   boolean resetAfterPop, LivingEntity bot, DrillAttempt attempt) {
        return freeOutcome(session, series, key, settings, options, owner, roundToken, ticks, waiting, defeated,
            resetAfterPop, bot, attempt, (RoundMetricsCollector) null);
    }

    public static synchronized boolean freeOutcome(TrainingSession session, DrillHistory series, String key,
                                                   TrainingSettings settings, DrillOptions options, UUID owner,
                                                   long roundToken, int ticks, boolean waiting, boolean defeated,
                                                   boolean resetAfterPop, LivingEntity bot, DrillAttempt attempt,
                                                   RoundMetricsCollector collector) {
        if (waiting || attempt == null || bot == null || owner == null || key == null || series == null || options == null
            || Objects.equals(terminalRound.get(session), roundToken)) return false;
        Drill drill = options.drill();
        if (drill != Drill.DUEL && drill != Drill.TOTEM_POPS && drill != Drill.SHIELD) return false;
        if (!defeated && (resetAfterPop || !bot.isDeadOrDying())) return false;
        terminalRound.put(session, roundToken);

        boolean win = drill == Drill.DUEL && !defeated;
        String reason = defeated ? "Du wurdest besiegt" : switch (drill) {
            case DUEL -> "Du hast den Bot besiegt";
            case TOTEM_POPS -> "Bot besiegt, ohne dass ein weiteres Totem ausgelöst hat";
            case SHIELD -> "Bot besiegt, ohne dass sein Schild den Treffer geblockt hat";
            default -> throw new AssertionError(drill);
        };
        RoundMetrics snap = collector == null ? RoundMetrics.EMPTY : collector.consumeSnapshot();
        recordRound(series, key, new DrillHistory.Result(win, ticks, -1.0, reason, attempt.timeline()),
            settings, options, owner, session, roundToken, snap);
        return true;
    }

    static String formatToastDetail(Drill drill, Run run) {
        if (!run.won()) return run.reason();
        RoundMetrics m = run.metrics();
        if (drill.isObiTrap()) {
            return TrainingStatus.ObiTimer.format(run.seconds());
        }
        if (drill == Drill.PERFECT_WIND_CHARGE && m.maxWindMeters() > 0.0) {
            return WindHeight.text(m.maxWindMeters());
        }
        if (m.maxFallMeters() > 0.0 && (drill == Drill.STUN_SLAM || drill == Drill.WIND_STUN_SLAM
            || drill == Drill.ELY_STUN_SLAM || drill == Drill.ELYTRA_SMASH || drill == Drill.LOW_WEB_SMASH
            || drill == Drill.PEARL_CATCH || drill == Drill.BACKSTAB)) {
            return String.format(Locale.GERMAN, "%.1f m Fall · %.2f s", m.maxFallMeters(), run.seconds());
        }
        if (m.botPops() > 0 && (drill.requiresPopChain() || drill == Drill.TOTEM_POPS || drill == Drill.HIT_CRYSTAL)) {
            return m.botPops() + (m.botPops() == 1 ? " Pop" : " Pops") + " · " + TrainingStatus.ObiTimer.format(run.seconds());
        }
        return TrainingStatus.ObiTimer.format(run.seconds());
    }

    private static void commit(DrillHistory series, String key, DrillHistory.Result result,
                               TrainingSettings settings, DrillOptions options, UUID owner, boolean signal,
                               RoundMetrics metrics) {
        List<DrillHistory.Result> previous = List.copyOf(series.get(key));
        series.record(key, result);
        if (settings == null || options == null || owner == null) return;
        Run run = new Run(key, settings.mode().name(), options.drill().name(), Instant.now().toEpochMilli(),
            result.won(), result.ticks(), result.reason(), result.timeline(), settings.difficultyLabel(options.drill()),
            options.tickRate(), options.drill() == Drill.LOW_WEB_SMASH && result.won() && streak(owner, key).current() == 0,
            metrics == null ? RoundMetrics.EMPTY : metrics, false);
        boolean crossedRecord = false;
        try {
            load(owner);
            List<Run> batch = new ArrayList<>(fromLegacy(key, settings.mode(), options.drill(), previous));
            batch.add(run);
            append(owner, batch);
            crossedRecord = result.won() && streaks.getOrDefault(key, Streak.EMPTY).recordHit();
        } catch (IOException e) {
            LoggerFactory.getLogger("pvptrainer").warn("Ergebnis konnte nicht zur lokalen Bestenliste hinzugefügt werden", e);
        }
        final boolean milestone = crossedRecord;
        if (!signal && !milestone) return;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player == null || !client.player.getUUID().equals(owner)) return;
            if (milestone) {
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.25F, 0.6F));
            }
            if (!signal) return;
            OutcomeOverlay.show(owner, result.won());
            if (HudPreferences.hints()) {
                var title = Component.literal(result.won() ? "Geschafft" : "Fehlversuch")
                    .withStyle(result.won() ? ChatFormatting.GREEN : ChatFormatting.RED);
                String detail = formatToastDetail(options.drill(), run);
                SystemToast.addOrUpdate(client.gui.toastManager(), RESULT_TOAST, title, Component.literal(detail));
            }
            if (!milestone) {
                client.getSoundManager().play(SimpleSoundInstance.forUI(
                    result.won() ? SoundEvents.EXPERIENCE_ORB_PICKUP : SoundEvents.NOTE_BLOCK_BASS.value(),
                    result.won() ? 1.35F : 0.7F, 0.65F));
            }
        });
    }
}

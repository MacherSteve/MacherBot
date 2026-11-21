package de.pvptrainer.analytics;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.session.TrainingSession;
import de.pvptrainer.kits.TrainingSettings;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

/** One diagnostic sample per active round. Player progress and telemetry have separate lifecycles. */
public final class BotArchive {
    private static final Gson JSON = new Gson();
    private static final Map<TrainingSession,Long> stored = new WeakHashMap<>();
    private static final String BUILD = resolveBuildVersion();

    public static String resolveBuildVersion() {
        try {
            var loader = FabricLoader.getInstance();
            if (loader == null) return "unknown";
            return loader.getModContainer("macherbot")
                .or(() -> loader.getModContainer("pvptrainer"))
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        } catch (RuntimeException error) {
            return "unknown";
        }
    }
    private static final String HEADER = "round,setup,mode,drill,difficulty,kind,start,end,action,outcome,build,truncated,tick_rate";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    public record Setup(String setup, String mode, String drill, String difficulty, String build, int tickRate) {
        public Setup { if (tickRate < 1 || tickRate > 20) tickRate = 20; }
        public Setup(String setup, String mode, String drill, String difficulty, String build) {
            this(setup, mode, drill, difficulty, build, 20);
        }
    }
    public record Round(Setup group, long time, String outcome, BotTelemetry.Snapshot data) {}

    static Path file(UUID owner) {
        return FabricLoader.getInstance().getConfigDir().resolve("pvptrainer-bot").resolve(owner + ".jsonl");
    }

    public static synchronized void add(TrainingSession session, String setup, TrainingSettings settings,
                                 DrillOptions options, UUID owner, long token, BotTelemetry.Snapshot data, String outcome) {
        if (session == null || owner == null || setup == null || setup.isBlank() || settings == null || options == null
            || data == null || data.ticks() <= 0 || Objects.equals(stored.get(session), token)) return;
        String difficulty = options.drill() == Drill.DUEL ? settings.duelRank() : settings.difficulty().name();
        var group = new Setup(setup, settings.mode().name(), options.drill().name(), difficulty, BUILD, options.tickRate());
        Round row = new Round(group, Instant.now().toEpochMilli(), outcome, data);
        Path path = file(owner);
        try {
            Files.createDirectories(path.getParent());
            try (var out = new RandomAccessFile(path.toFile(), "rw")) {
                long length = out.length();
                if (length != 0) {
                    out.seek(length - 1);
                    if (out.read() != '\n') out.write('\n');
                }
                out.write((JSON.toJson(row) + '\n').getBytes(StandardCharsets.UTF_8));
            }
            stored.put(session, token);
        } catch (IOException e) {
            LoggerFactory.getLogger("pvptrainer").warn("Bot-Messung konnte nicht gespeichert werden", e);
        }
    }

    public static synchronized List<Round> load(UUID owner) throws IOException {
        if (owner == null || !Files.isRegularFile(file(owner))) return List.of();
        var rows = new ArrayList<Round>();
        try (var input = Files.newBufferedReader(file(owner), StandardCharsets.UTF_8)) {
            for (String line; (line = input.readLine()) != null;) {
                try {
                    Round round = JSON.fromJson(line, Round.class);
                    if (valid(round)) rows.add(round);
                } catch (RuntimeException e) {
                    LoggerFactory.getLogger("pvptrainer").warn("Unlesbare Bot-Messung übersprungen", e);
                }
            }
        }
        return rows;
    }

    private static boolean valid(Round round) {
        if (round == null || round.data() == null || round.group() == null || round.outcome() == null) return false;
        var group = round.group();
        if (group.setup() == null || group.difficulty() == null || group.mode() == null || group.drill() == null || group.build() == null) return false;
        var data = round.data();
        if (data.ticks() <= 0) return false;
        for (var w : data.windows()) {
            if (w.start() < 0 || w.start() > data.ticks() || w.end() > data.ticks() || w.hit() > data.ticks()) return false;
            if (w.end() != -1 && w.end() < w.start() || w.hit() != -1 && (w.hit() < w.start() || w.hit() > w.end())) return false;
        }
        int previous = -1;
        for (var a : data.actions()) {
            if (a.kind() == null || a.tick() < 0 || a.tick() < previous || a.tick() > data.ticks()) return false;
            previous = a.tick();
        }
        return true;
    }

    public static Path exports() { return FabricLoader.getInstance().getGameDir().resolve("MacherBot-Analysen"); }

    public static Path export(List<Round> rounds) throws IOException {
        if (rounds.isEmpty()) throw new IllegalArgumentException("Keine Runden zum Exportieren");
        Files.createDirectories(exports());
        Path target = Files.createTempFile(exports(), "Bot-" + STAMP.format(LocalDateTime.now()) + "-", ".csv");
        try (var out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            out.write(HEADER); out.write("\r\n");
            for (int i = 0; i < rounds.size(); i++) {
                var r = rounds.get(i);
                int n = i + 1;
                row(out, r, n, "round", 0, r.data().ticks(), "");
                for (var w : r.data().windows()) {
                    boolean hit = w.hit() >= 0, censored = w.end() < 0;
                    row(out, r, n, hit ? "window_hit" : censored ? "window_censored" : "window_miss", w.start(),
                        hit ? w.hit() : censored ? r.data().ticks() : w.end(), "");
                }
                for (var a : r.data().actions()) row(out, r, n, "action", a.tick(), a.tick(), a.kind());
            }
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        return target;
    }

    private static void row(Writer out, Round r, int n, String kind, int start, int end, String action) throws IOException {
        var g = r.group();
        var fields = List.of(Integer.toString(n),g.setup(),g.mode(),g.drill(),g.difficulty(),kind,
            Integer.toString(start),Integer.toString(end),action,r.outcome(),g.build(),Boolean.toString(r.data().truncated()),Integer.toString(g.tickRate()));
        for (int i=0; i<fields.size(); i++) {
            if (i>0) out.write(',');
            String value = fields.get(i);
            if (value.indexOf(',')>=0 || value.indexOf('"')>=0 || value.indexOf('\n')>=0 || value.indexOf('\r')>=0)
                out.write('"'+value.replace("\"","\"\"")+'"');
            else out.write(value);
        }
        out.write("\r\n");
    }
}

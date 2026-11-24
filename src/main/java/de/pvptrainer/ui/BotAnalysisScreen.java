package de.pvptrainer.ui;

import de.pvptrainer.TrainerClient;
import de.pvptrainer.analytics.BotArchive;
import de.pvptrainer.analytics.BotStatistics;
import de.pvptrainer.analytics.CalibrationPlan;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.kits.TrainingSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Bot-Analyse tab inside the unified Apple-style Statistik view. */
final class BotAnalysisScreen extends TrainerPanel {
    private static final int CARD_BG = 0xFF18181F, CANVAS = 0xFF101015, MINT = 0xFF5AD9A0, AMBER = 0xFFFFC266;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());

    private final String preferred;
    private final Map<BotArchive.Setup, List<BotArchive.Round>> groups = new LinkedHashMap<>(16, .75f, true);
    private List<BotArchive.Setup> setups = List.of();
    private List<BotArchive.Round> rows = List.of();
    private BotStatistics stats;
    private List<BotStatistics.Pattern> patterns = List.of();
    private String message = "", label = "";
    private int group = -1, tab, pattern, page, perPage, mouseX, mouseY;
    private int topTabY, topTabH, bodyTop;
    private boolean loaded;

    BotAnalysisScreen(Screen parent, TrainingSettings.Mode mode, String preferred) {
        super(parent, "Statistik", mode);
        this.preferred = preferred;
    }

    @Override void build() {
        // Unified 4-Tab Apple Segmented Control: Übersicht | Übungen | Rundenlog | Bot-Analyse
        this.topTabY = this.y;
        this.topTabH = 22;
        String[] topTabs = {"Übersicht", "Übungen", "Rundenlog", "Bot-Analyse"};
        int segX = this.x + 2;
        int segW = this.inner - 4;
        for (int i = 0; i < topTabs.length; i++) {
            final int idx = i;
            this.action(gridCol(segX, segW, 2, i, 4), this.topTabY + 2, gridW(segX, segW, 2, i, 4), this.topTabH - 4,
                topTabs[i], i == 3 ? Style.SEGMENT_SELECTED : Style.SEGMENT, () -> {
                    if (idx == 0) open(ProgressScreen.openTab(this.parent, this.mode, ProgressScreen.ViewTab.OVERVIEW));
                    else if (idx == 1) open(ProgressScreen.openTab(this.parent, this.mode, ProgressScreen.ViewTab.DRILLS));
                    else if (idx == 2) open(ProgressScreen.openLog(this.parent, this.mode, false));
                });
        }

        this.bodyTop = this.topTabY + this.topTabH + 7;

        if (!loaded) {
            loaded = true;
            try {
                UUID owner = minecraft.player != null ? minecraft.player.getUUID() : minecraft.getUser().getProfileId();
                for (var row : BotArchive.load(owner)) groups.computeIfAbsent(row.group(), unused -> new ArrayList<>()).add(row);
                setups = new ArrayList<>(groups.keySet()).reversed();
                for (int i = 0; i < setups.size(); i++) if (setups.get(i).setup().equals(preferred)) { group = i; break; }
                if (group < 0) {
                    var drill = TrainerClient.options(mode).drill();
                    var settings = TrainerClient.settings(mode);
                    String difficulty = drill == Drill.DUEL ? settings.duelRank() : settings.difficulty().name();
                    for (int i = 0; i < setups.size(); i++) {
                        var entry = setups.get(i);
                        if (!entry.mode().equals(mode.name()) || !entry.drill().equals(drill.name())) continue;
                        boolean sameBand = drill == Drill.DUEL && settings.duelBand() == TrainingSettings.DuelBand.AUTO
                            && (entry.difficulty().equals(TrainingSettings.DuelBand.LOW.label(settings.duelTier()))
                            || entry.difficulty().equals(TrainingSettings.DuelBand.HIGH.label(settings.duelTier())));
                        if (sameBand || entry.difficulty().equals(difficulty)) { group = i; break; }
                    }
                }
                if (group < 0 && !setups.isEmpty()) group = 0;
            } catch (IOException e) { message = "Messdatei konnte nicht gelesen werden."; }
        }

        int foot = bottom - 19;
        action(col(0, 3), foot, cw(0, 3), 19, "CSV exportieren", Style.NORMAL, () -> {
            try { Path path = BotArchive.export(rows); message = "Exportiert · " + path.getFileName(); }
            catch (IOException e) { message = "CSV konnte nicht gespeichert werden."; }
        }).active = group >= 0;
        action(col(1, 3), foot, cw(1, 3), 19, "Ordner öffnen", Style.NORMAL, () -> {
            try { Files.createDirectories(BotArchive.exports()); Util.getPlatform().openPath(BotArchive.exports()); }
            catch (IOException | RuntimeException e) { message = "Ordner · " + BotArchive.exports(); }
        }).tip(BotArchive.exports().toString());
        action(col(2, 3), foot, cw(2, 3), 19, "Testplan", Style.NORMAL, () -> message = CalibrationPlan.open());

        if (group < 0) {
            subtitle = "Bot-Analyse";
            return;
        }
        var selected = setups.get(group);
        rows = groups.get(selected);
        if (stats == null) { stats = new BotStatistics(rows); patterns = stats.patterns(); }
        String drill = selected.drill(), difficulty = selected.difficulty();
        try { drill = Drill.valueOf(drill).label(); difficulty = TrainingSettings.Difficulty.valueOf(difficulty).label(); }
        catch (IllegalArgumentException ignored) {}
        label = selected.mode() + "  /  " + drill + (selected.drill().equals(Drill.DUEL.name()) ? "  ·  " + difficulty : "");
        if (selected.tickRate() != 20) label += "  ·  " + selected.tickRate() + " TPS";
        subtitle = selected.build() + "  ·  " + selected.setup().substring(0, Math.min(7, selected.setup().length()));
        action(x, bodyTop, 20, 16, "‹", Style.NORMAL, () -> choose(group - 1)).active = group > 0;
        action(right - 20, bodyTop, 20, 16, "›", Style.NORMAL, () -> choose(group + 1)).active = group + 1 < setups.size();

        String[] subTabs = {"Tempo", "Muster", "Runden"};
        for (int i = 0; i < subTabs.length; i++) {
            int choice = i;
            action(col(i, 3), bodyTop + 20, cw(i, 3), 17, subTabs[i], tab == i ? Style.SELECTED : Style.NORMAL, () -> { tab = choice; refresh(); });
        }
        int body = bodyTop + 41;
        if (tab == 1 && patterns.size() > 1) action(right - 65, body + 29, 62, 14, "Wechseln ›", Style.NORMAL, () -> {
            pattern = (pattern + 1) % patterns.size(); refresh();
        }).tip("Andere vorangegangene Aktion betrachten");
        if (tab == 2) {
            perPage = Math.max(1, (bottom - 35 - (body + 18)) / 15);
            int pages = (rows.size() + perPage - 1) / perPage;
            if (pages > 1) {
                action(right - 42, body, 19, 14, "‹", Style.NORMAL, () -> { page--; refresh(); }).active = page > 0;
                action(right - 20, body, 19, 14, "›", Style.NORMAL, () -> { page++; refresh(); }).active = page + 1 < pages;
            }
        }
    }

    private void choose(int index) { group = index; stats = null; pattern = page = 0; message = ""; refresh(); }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        mouseX = mx; mouseY = my;
        super.extractRenderState(g, mx, my, delta);
    }

    @Override void content(GuiGraphicsExtractor g) {
        fillRounded2(g, this.x, this.topTabY, this.inner, this.topTabH, TRACK);
        outlineRounded2(g, this.x, this.topTabY, this.inner, this.topTabH, LINE);

        if (group < 0) {
            fillRounded(g, x, bodyTop, inner, 72, CARD_BG);
            outlineRounded(g, x, bodyTop, inner, 72, LINE);
            line(g, "Noch keine Bot-Messung", x + 10, bodyTop + 10, inner - 20, TEXT);
            line(g, "Spiele ein freies Duell mit Gegenangriffen oder eine Runde Manuelles Retotem.", x + 10, bodyTop + 26, inner - 20, MUTED);
            line(g, "Deine persönlichen PvP-Statistiken findest du in den Tabs Übersicht & Übungen.", x + 10, bodyTop + 42, inner - 20, MUTED);
        } else {
            line(g, label, x + 24, bodyTop + 4, inner - 50, TEXT);
            if (over(bodyTop, 16)) tip(g, List.of(label, "Setup " + setups.get(group).setup().substring(0, Math.min(12, setups.get(group).setup().length()))
                + "  ·  Build " + setups.get(group).build(), "Konfiguration " + (group + 1) + " / " + setups.size()));
            int body = bodyTop + 41;
            if (tab == 0) tempo(g, body);
            else if (tab == 1) patterns(g, body);
            else rounds(g, body);
        }
        String status = message;
        if (status.isEmpty() && stats != null) {
            status = rows.size() + " Runden · " + stats.neutral + " ohne Wertung" + (stats.partial > 0 ? " · " + stats.partial + " gekürzt" : "");
        }
        line(g, status, x, bottom - 30, inner, MUTED);
        if (!status.isEmpty() && over(bottom - 33, 14)) tip(g, List.of(status, "Exportordner: " + BotArchive.exports()));
    }

    private void tempo(GuiGraphicsExtractor g, int body) {
        int w = cw(0, 2);
        metric(g, x, body, w, "Erste Schadensantwort", time(stats.median()), "Median · " + stats.hits() + " Treffer");
        metric(g, col(1, 2), body, cw(1, 2), "Langsame Antworten", stats.p90() < 0 ? "–" : time(stats.p90()),
            stats.p90() < 0 ? "P90 ab 10 Treffern" : "P90 · " + stats.hits() + " Treffer");
        int r = body + 39;
        line(g, stats.windows() + " Nahkampffenster  ·  " + stats.missed + " ohne Treffer  ·  " + stats.censored + " offen", x + 2, r, inner - 4, TEXT);
        if (over(r - 1, 12)) tip(g, List.of("Beginn: Bot in 3 Blöcken Schlagreichweite mit Sicht.",
            "Antwort: erster tatsächlicher Schaden des Bots am Spieler.",
            "Pfeil und Kristall zählen ebenfalls. Offene Fenster bleiben getrennt."));
        r += 14;
        if (stats.longest >= 0) line(g, "Längste Antwort  " + time(stats.longest) + "  ·  Runde " + stats.worstRound + " ab T+" + stats.worstStart, x + 2, r, inner - 4, AMBER);
        else line(g, "Es wurde noch keine Schadensantwort gemessen.", x + 2, r, inner - 4, MUTED);
        int rate = setups.get(group).tickRate();
        if (r + 24 < bottom - 31) line(g, "1 Tick ≈ " + Math.round(1000.0 / rate) + " ms bei " + rate + " TPS. Offene Fenster siehe CSV.", x + 2, r + 15, inner - 4, MUTED);
        if (r + 65 < bottom - 31 && stats.hits() > 0) trend(g, r + 30, bottom - 36);
    }

    private void trend(GuiGraphicsExtractor g, int topY, int base) {
        int first = Math.max(0, rows.size() - 24), count = rows.size() - first;
        int leftEdge = x + 34, graphWidth = right - 4 - leftEdge, floor = base - 13, ceiling = topY + 14;
        int scale = 1;
        for (int i = first; i < rows.size(); i++) {
            double median = stats.perRound.get(i).median();
            if (Double.isFinite(median)) scale = Math.max(scale, (int)Math.ceil(median));
        }
        line(g, "Antwortzeit je Runde  ·  Median  ·  letzte " + count, x + 2, topY, inner - 4, MUTED);
        line(g, scale + " T.", x + 2, ceiling - 3, 30, MUTED);
        line(g, "0", x + 2, floor - 4, 30, MUTED);
        g.fill(leftEdge, ceiling, right - 4, ceiling + 1, CARD_BG);
        g.fill(leftEdge, floor, right - 4, floor + 1, LINE);
        for (int i = 0; i < count; i++) {
            int center = leftEdge + (2 * i + 1) * graphWidth / (2 * count);
            int half = Math.max(1, Math.min(5, graphWidth / (3 * count)));
            var rt = stats.perRound.get(first + i);
            if (rt.n() == 0) g.fill(center - half, floor + 3, center + half + 1, floor + 4, MUTED);
            else {
                int height = (int)Math.round(rt.median() / scale * (floor - ceiling));
                g.fill(center - half, floor - height, center + half + 1, floor + 1, MINT);
            }
        }
        line(g, "#" + (first + 1), leftEdge, base - 9, 40, MUTED);
        String end = "#" + rows.size();
        if (count > 1) line(g, end, right - 4 - font.width(end), base - 9, 50, MUTED);
        if (mouseY >= ceiling - 2 && mouseY <= floor + 5 && mouseX >= leftEdge && mouseX < leftEdge + graphWidth) {
            int index = first + (mouseX - leftEdge) * count / graphWidth;
            var rt = stats.perRound.get(index);
            String detail = rows.get(index).data().truncated() ? "Messung gekürzt" : rt.n() == 0
                ? "Kein Treffer in einem Nahkampffenster" : time(rt.median()) + " Median bei " + rt.n() + " Treffern";
            tip(g, List.of("Runde " + (index + 1), detail));
        }
    }

    private void metric(GuiGraphicsExtractor g, int leftX, int topY, int width, String name, String value, String n) {
        fillRounded(g, leftX, topY, width, 35, CARD_BG);
        outlineRounded(g, leftX, topY, width, 35, LINE);
        line(g, name, leftX + 7, topY + 3, width - 14, MUTED);
        line(g, value, leftX + 7, topY + 13, width - 14, MINT);
        line(g, n, leftX + 7, topY + 24, width - 14, MUTED);
    }

    private void patterns(GuiGraphicsExtractor g, int body) {
        var start = stats.opening();
        line(g, "Häufigste Eröffnung", x + 3, body + 2, inner - 6, MUTED);
        line(g, BotStatistics.action(start.to()) + "   " + share(start.count(), start.base()) + " Runden", x + 3, body + 14, inner - 6, TEXT);
        String timing = stats.timedOpening == null ? "Keine eindeutige Eröffnung gemessen."
            : "Häufigstes Paar: " + BotStatistics.action(stats.timedOpening.action()) + " bei T+" + stats.timedOpening.tick()
            + " in " + stats.timedCount + "/" + stats.rounds + " vollständigen Runden.";
        if (over(body, 25)) tip(g, List.of("Erste tatsächlich ausgeführte Bot-Aktion jeder Runde.", timing,
            "Gleichzeitige Eröffnungen und Folgen über solche Gruppen sind ausgenommen.",
            "Skriptfolgen im Retotem getrennt vom freien Duell bewerten."));
        fillRounded(g, x, body + 27, inner, 40, CARD_BG);
        outlineRounded(g, x, body + 27, inner, 40, LINE);
        var p = patterns.isEmpty() ? null : patterns.get(pattern);
        if (p != null) {
            line(g, BotStatistics.action(p.from()) + " → " + BotStatistics.action(p.to()) + "   " + share(p.count(), p.base()),
                x + 7, body + 30, inner - (patterns.size() > 1 ? 76 : 14), TEXT);
            line(g, "Typischer Abstand  " + time(p.gap()) + "  ·  Median", x + 7, body + 43, inner - 14, MUTED);
            var cadence = p.cadence();
            String frequency = cadence == null ? "Häufigster Abstand ab 5 passenden Folgen"
                : "Häufigster Abstand  " + cadence.ticks() + " T. bei " + cadence.support() + "/" + p.count() + (cadence.tied() > 1 ? " · geteilt" : "");
            line(g, frequency, x + 7, body + 55, inner - 14, cadence == null ? MUTED : TEXT);
            if (over(body + 53, 13)) tip(g, List.of("Exakter Tickabstand zwischen den beiden gezeigten Aktionen.",
                "Anteil unter allen Folgen dieses Typs, sichtbar ab 5 Fällen.",
                "Bei Gleichstand erscheint der kürzeste der häufigsten Abstände."));
        } else line(g, "Noch keine Folge aus zwei Aktionen.", x + 7, body + 35, inner - 14, MUTED);
        int t = body + 72;
        boolean enough = stats.testCases >= 10 && stats.testRounds >= 3;
        String prediction = enough ? "Muster " + percent(stats.correct, stats.testCases) + "  /  Basis " + percent(stats.baselineCorrect, stats.testCases)
            : "Vorhersage · zu wenig Testdaten";
        line(g, prediction, x + 3, t, inner - 6, enough ? MINT : MUTED);
        if (t + 20 < bottom - 29) line(g, stats.testCases + " Vorhersagen aus " + stats.testRounds + " späteren Runden", x + 3, t + 12, inner - 6, MUTED);
        if (over(t - 2, 25)) tip(g, List.of("Vorhersage aus früheren Runden: häufigste Folge nach der letzten Aktion.",
            "Basis: häufigste Folgeaktion unabhängig vom Vorgänger. Gleiche Testfälle.",
            "Ab 3 früheren Runden und 5 früheren Fällen pro Vorgänger getestet.",
            "Anzeige ab 10 Prognosen in mindestens 3 späteren Runden."));
    }

    private void rounds(GuiGraphicsExtractor g, int body) {
        line(g, "Neueste zuerst  ·  Antwortzeit je Runde", x + 2, body + 3, inner - 48, MUTED);
        for (int i = 0; i < perPage; i++) {
            int index = rows.size() - 1 - page * perPage - i; if (index < 0) break;
            var row = rows.get(index); int yy = body + 18 + 15 * i;
            var timing = stats.perRound.get(index);
            fillRounded(g, x, yy, inner, 13, (i & 1) == 0 ? CANVAS : CARD_BG);
            line(g, "#" + (index + 1) + "   " + DATE.format(Instant.ofEpochMilli(row.time())) + "   " + outcome(row.outcome()),
                x + 5, yy + 3, inner - 114, TEXT);
            String measure = row.data().truncated() ? "Gekürzt" : time(timing.median()) + "  ·  n=" + timing.n();
            line(g, measure, right - 104, yy + 3, 99, MINT);
        }
    }

    private static String outcome(String kind) { return switch (kind) { case "PLAYER_WIN" -> "Sieg"; case "PLAYER_LOSS" -> "Niederlage"; default -> "Ohne Wertung"; }; }
    private static String time(double ticks) { return Double.isNaN(ticks) ? "–" : (ticks == Math.rint(ticks) ? Long.toString((long)ticks) : String.format(Locale.GERMANY, "%.1f", ticks)) + " Ticks"; }
    private static String percent(int n, int d) { return d == 0 ? "–" : Math.round(100.0 * n / d) + " %"; }
    private static String share(int n, int d) { return d == 0 ? "0 / 0" : n + " / " + d + "  (" + percent(n, d) + ")"; }
    private boolean over(int yy, int h) { return mouseX >= x && mouseX < right && mouseY >= yy && mouseY < yy + h; }
    private void tip(GuiGraphicsExtractor g, List<String> lines) { g.setComponentTooltipForNextFrame(font, lines.stream().map(Component::literal).map(c -> (Component)c).toList(), mouseX, mouseY); }
}

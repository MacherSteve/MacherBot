package de.pvptrainer.ui;

import de.pvptrainer.TrainerClient;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.progress.DrillHistory;
import de.pvptrainer.progress.TrainingProgress;
import de.pvptrainer.session.TrainingStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Map.Entry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Unified Apple-style Statistics screen combining Verlauf (progress charts), Rundenlog (live/recent runs), and Bot-Analyse. */
final class ProgressScreen extends TrainerPanel {
    private static final int CANVAS = 0xFF191920, CARD_BG = 0xFF22222A, GRID = 0x26FFFFFF;
    private static final int MINT = 0xFF5AD9A0, CORAL = 0xFFFF7A85, AMBER = 0xFFFFC266, NEUTRAL = 0xFFAEAEB6;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm").withZone(ZoneId.systemDefault());
    private static final int[] LIMITS = {10, 30, Integer.MAX_VALUE};

    private enum ViewTab { VERLAUF, RUNDENLOG }

    private ViewTab viewTab = ViewTab.VERLAUF;
    private int tabBarY, tabBarH, bodyTop;

    // Verlauf (progress) state
    private String setup;
    private String setupLabel;
    private List<String> setups = List.of();
    private List<TrainingProgress.Run> runs = List.of(), visible = List.of(), leaders = List.of();
    private boolean details;
    private int range, focus, page, perPage, cardsY, chartY, footY, plotLeft, plotRight, plotTop, plotBottom;
    private int best, wins, average, meanDelta, oldSize;
    private double median;
    private int mouseX, mouseY, initialMouseX, initialMouseY;
    private boolean firstFrame, pointerMoved;
    private int[] low = {}, high = {};

    // Rundenlog (recent attempts) state
    private int logPage;
    private int logSelected = -1;
    private int logReasonY, logLineTop, logPageCount, logLinesPerPage;
    private List<FormattedCharSequence> logReasonRows = List.of();
    private List<String> logLines = List.of();
    private List<DrillHistory.Result> logRecent = List.of();
    private String logReason = "";
    private String logCommonIssue = "";
    private boolean logActive;
    private String logMedian = "–";

    ProgressScreen(Screen parent, TrainingSettings.Mode mode) {
        super(parent, "Statistik", mode);
    }

    static ProgressScreen openLog(Screen parent, TrainingSettings.Mode mode, boolean focusLast) {
        ProgressScreen screen = new ProgressScreen(parent, mode);
        screen.viewTab = ViewTab.RUNDENLOG;
        if (focusLast && !TrainerClient.status().recent().isEmpty()) {
            screen.logSelected = TrainerClient.status().recent().size() - 1;
        }
        return screen;
    }

    @Override int preferredWidth() { return 560; }
    @Override int preferredHeight() { return 316; }

    @Override void build() {
        firstFrame = true;
        pointerMoved = false;

        // Unified 3-Tab Apple Segmented Control: Verlauf | Rundenlog | Bot-Analyse
        this.tabBarY = this.y;
        this.tabBarH = 22;
        String[] tabLabels = {"Verlauf", "Rundenlog", "Bot-Analyse"};
        int segX = this.x + 2;
        int segW = this.inner - 4;
        for (int i = 0; i < tabLabels.length; i++) {
            final int idx = i;
            boolean active = (i == 0 && viewTab == ViewTab.VERLAUF) || (i == 1 && viewTab == ViewTab.RUNDENLOG);
            this.action(gridCol(segX, segW, 2, i, 3), this.tabBarY + 2, gridW(segX, segW, 2, i, 3), this.tabBarH - 4,
                tabLabels[i], active ? Style.SEGMENT_SELECTED : Style.SEGMENT, () -> {
                    if (idx == 0) {
                        if (viewTab != ViewTab.VERLAUF) { viewTab = ViewTab.VERLAUF; refresh(); }
                    } else if (idx == 1) {
                        if (viewTab != ViewTab.RUNDENLOG) { viewTab = ViewTab.RUNDENLOG; refresh(); }
                    } else {
                        open(new BotAnalysisScreen(this.parent, this.mode, this.setup));
                    }
                });
        }

        this.bodyTop = this.tabBarY + this.tabBarH + 7;
        if (viewTab == ViewTab.RUNDENLOG) {
            buildLog();
        } else {
            buildProgress();
        }
    }

    private void buildProgress() {
        LinkedHashMap<String, List<TrainingProgress.Run>> groups = new LinkedHashMap<>(16, .75f, true);
        for (var run : TrainingProgress.forScreen(minecraft)) groups.computeIfAbsent(run.setup(), k -> new ArrayList<>()).add(run);
        setups = new ArrayList<>(groups.keySet()).reversed();
        if (setups.isEmpty()) { subtitle = "Verlauf · Lokal"; return; }
        if (!groups.containsKey(setup)) {
            Drill drill = TrainerClient.options(mode).drill();
            String difficulty = TrainerClient.settings(mode).difficultyLabel(drill);
            String active = TrainerClient.status().seriesKey();
            setup = null;
            for (String key : setups) {
                var row = groups.get(key).getLast();
                if (row.mode().equals(mode.name()) && row.drill().equals(drill.name()) && row.difficulty().equals(difficulty)) {
                    if (setup == null || key.equals(active)) setup = key;
                }
            }
            if (setup == null) {
                for (String key : setups) {
                    var row = groups.get(key).getLast();
                    if (row.mode().equals(mode.name()) && row.drill().equals(drill.name())) {
                        setup = key;
                        break;
                    }
                }
            }
            if (setup == null) setup = setups.getFirst();
            focus = groups.get(setup).size() - 1;
        }
        runs = groups.get(setup);
        focus = Math.clamp(focus, 0, runs.size() - 1);
        var last = runs.getLast();
        String drill = last.drill();
        try { drill = Drill.valueOf(drill).label(); } catch (IllegalArgumentException ignored) {}
        setupLabel = last.mode() + "  /  " + drill;
        for (var technique : List.of(Drill.TOTEM_POPS, Drill.STUN_SLAM))
            if (setup.endsWith("/" + technique.name())) setupLabel += "  /  " + technique.label();
        if (!last.difficulty().isEmpty() && last.drill().equals(Drill.DUEL.name())) setupLabel += "  ·  " + last.difficulty();
        if (last.tickRate() != 20) setupLabel += "  ·  " + last.tickRate() + " TPS";
        long variants = groups.values().stream().map(List::getLast).filter(row ->
            last.mode().equals(row.mode()) && last.drill().equals(row.drill()) && last.difficulty().equals(row.difficulty())).count();
        if (variants > 1) setupLabel += "  ·  " + setup.substring(0, Math.min(6, setup.length()));
        var owner = minecraft.player != null ? minecraft.player.getUUID() : minecraft.getUser().getProfileId();
        boolean obiTrap = last.drill().equals(Drill.OBI_TRAP.name()) || last.drill().equals(Drill.CORNER_OBI_TRAP.name());
        if (obiTrap && !setup.contains("/") && !setup.endsWith(DrillHistory.ENCLOSED_TRAP)) {
            subtitle = "Archiv · frühere Trap-Regeln";
        } else if (obiTrap && !setup.contains("/")) {
            var bestTime = runs.stream().filter(TrainingProgress.Run::won).mapToDouble(TrainingProgress.Run::seconds).min();
            subtitle = bestTime.isPresent() ? "Bestzeit " + TrainingStatus.ObiTimer.format(bestTime.getAsDouble()) : "Noch keine Bestzeit";
        } else subtitle = TrainingProgress.streak(owner, setup).label();

        int ordinal = setups.indexOf(setup);
        int actionWidth = inner < 340 ? 68 : 92;
        var leftArrow = action(x, bodyTop, 18, 16, "‹", Style.NORMAL, () -> selectSetup(ordinal - 1));
        leftArrow.active = ordinal > 0;
        leftArrow.tip("Vorherige Trainingskonfiguration");
        var rightArrow = action(right - actionWidth - 21, bodyTop, 18, 16, "›", Style.NORMAL, () -> selectSetup(ordinal + 1));
        rightArrow.active = ordinal + 1 < setups.size();
        rightArrow.tip("Nächste Trainingskonfiguration");
        action(right - actionWidth, bodyTop, actionWidth, 16, details ? "Übersicht" : "Rundendetails", Style.NORMAL, () -> {
            details = !details;
            if (details) { focus = runs.size() - 1; page = 0; }
            refresh();
        });
        if (details) { buildDetails(); return; }

        int first = Math.max(0, runs.size() - LIMITS[range]);
        visible = runs.subList(first, runs.size());
        wins = (int)visible.stream().filter(TrainingProgress.Run::won).count();
        average = mean(visible);
        int previous = Math.max(0, first - visible.size());
        oldSize = first - previous;
        meanDelta = oldSize == 0 ? 0 : average - mean(runs.subList(previous, first));
        var times = visible.stream().filter(TrainingProgress.Run::won).mapToDouble(TrainingProgress.Run::seconds).sorted().toArray();
        median = times.length == 0 ? Double.NaN : (times[(times.length - 1) / 2] + times[times.length / 2]) / 2;
        leaders = runs.stream().filter(TrainingProgress.Run::won)
            .sorted(Comparator.comparingInt(TrainingProgress.Run::score).reversed()).limit(3).toList();
        best = leaders.isEmpty() ? 0 : leaders.getFirst().score();

        cardsY = bodyTop + 20;
        chartY = cardsY + (bottom - cardsY < 175 ? 42 : 50);
        int footer = inner < 360 ? 44 : 50;
        footY = Math.max(chartY + 46, bottom - footer);
        plotLeft = x + 32; plotRight = right - 10; plotTop = chartY + 23; plotBottom = footY - 13;
        buildEnvelope();
        String[] labels = {"10", "30", "Alle"};
        for (int i = 0; i < LIMITS.length; i++) {
            final int idx = i;
            action(right - 84 + i * 27, chartY + 3, 25, 14, labels[i], range == i ? Style.SELECTED : Style.NORMAL, () -> { range = idx; refresh(); })
                .tip(i == 2 ? "Alle Versuche" : "Letzte " + LIMITS[i] + " Versuche");
        }
    }

    private void buildLog() {
        TrainingStatus status = TrainerClient.status();
        this.logActive = status.active();
        this.logRecent = status.recent();
        this.subtitle = status.exercise().isEmpty() ? "Rundenlog" : status.exercise();
        if (this.logSelected >= this.logRecent.size()) this.logSelected = -1;

        int[] winTicks = this.logRecent.stream().filter(DrillHistory.Result::won).mapToInt(DrillHistory.Result::ticks).sorted().toArray();
        if (winTicks.length == 0) {
            this.logMedian = "–";
        } else {
            double med = (winTicks[(winTicks.length - 1) / 2] + winTicks[winTicks.length / 2]) / 2.0;
            this.logMedian = (med == (int)med ? Integer.toString((int)med) : String.format(Locale.GERMAN, "%.1f", med)) + " Ticks";
        }

        LinkedHashMap<String, Integer> reasons = new LinkedHashMap<>();
        this.logRecent.stream().filter(r -> !r.won()).forEach(r -> reasons.merge(r.reason(), 1, Integer::sum));
        this.logCommonIssue = reasons.entrySet().stream()
            .max(Entry.comparingByValue())
            .map(e -> "Häufigster Fehler · " + e.getValue() + "× " + e.getKey())
            .orElse("");

        int navY = this.bodyTop + 34 + this.gap;
        int liveW = Math.min(72, this.inner / 5);
        this.action(this.x, navY, liveW, 18, this.logActive ? "Live" : "Zuletzt",
            this.logSelected < 0 ? Style.SELECTED : Style.NORMAL, () -> selectLog(-1));

        int numX = this.x + liveW + this.gap;
        int numStep = Math.min(31, (this.right - numX + 2) / 10);
        for (int i = 0; i < this.logRecent.size(); i++) {
            final int idx = i;
            DrillHistory.Result r = this.logRecent.get(i);
            this.action(numX + numStep * i, navY, numStep - 2, 18, Integer.toString(i + 1),
                this.logSelected == i ? Style.SELECTED : Style.NORMAL, () -> selectLog(idx))
                .tip("Versuch " + (i + 1) + " · " + (r.won() ? "Erfolg" : "Fehlversuch") + " · " + r.ticks() + " Ticks");
        }

        if (this.logSelected < 0) {
            this.logLines = status.timeline();
            this.logReason = status.progress();
        } else {
            DrillHistory.Result r = this.logRecent.get(this.logSelected);
            this.logLines = r.timeline();
            this.logReason = (r.won() ? "Erfolg" : "Fehlversuch") + " · " + r.reason() + " · " + r.ticks() + " Ticks";
        }

        this.logReasonY = navY + 23;
        this.logReasonRows = this.font.split(Component.literal(this.logReason), this.inner);
        this.logLineTop = this.logReasonY + 5 + 10 * Math.min(3, Math.max(1, this.logReasonRows.size()));
        this.logLinesPerPage = Math.max(1, (this.bottom - 34 - this.logLineTop) / 12);
        this.logPageCount = Math.max(1, (this.logLines.size() + this.logLinesPerPage - 1) / this.logLinesPerPage);
        this.logPage = Math.min(this.logPage, this.logPageCount - 1);

        if (this.logPageCount > 1) {
            this.action(this.x, this.bottom - 18, 30, 18, "‹", Style.NORMAL, () -> { this.logPage--; this.refresh(); }).active = this.logPage > 0;
            this.action(this.right - 30, this.bottom - 18, 30, 18, "›", Style.NORMAL, () -> { this.logPage++; this.refresh(); }).active = this.logPage + 1 < this.logPageCount;
        }
    }

    private void selectLog(int idx) {
        this.logSelected = idx;
        this.logPage = 0;
        this.refresh();
    }

    private void buildEnvelope() {
        int columns = Math.min(visible.size(), Math.max(1, plotRight - plotLeft));
        low = new int[columns]; high = new int[columns];
        Arrays.fill(low, Integer.MAX_VALUE);
        for (int i = 0; i < visible.size(); i++) {
            int col = (int)((long)i * columns / visible.size());
            int score = visible.get(i).score();
            low[col] = Math.min(low[col], score);
            high[col] = Math.max(high[col], score);
        }
    }

    private void selectSetup(int idx) {
        setup = setups.get(idx); details = false; focus = 0; page = 0; refresh();
    }

    static int mean(List<TrainingProgress.Run> rows) {
        return (int)Math.round(rows.stream().mapToInt(TrainingProgress.Run::score).average().orElse(0));
    }

    private static String sec(double s) { return String.format(Locale.GERMAN, "%.2f s", s); }
    private static String date(TrainingProgress.Run r) { return r.time() == 0 ? "" : DATE.format(Instant.ofEpochMilli(r.time())); }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (firstFrame) { initialMouseX = mx; initialMouseY = my; firstFrame = false; }
        if (Math.abs(mx - initialMouseX) + Math.abs(my - initialMouseY) > 2) pointerMoved = true;
        mouseX = mx; mouseY = my;
        super.extractRenderState(g, mx, my, delta);

        if (viewTab == ViewTab.RUNDENLOG && mx >= this.x && mx < this.right) {
            int row = (my - this.logLineTop) / 12;
            String tip = "";
            if (my >= this.logLineTop && row >= 0 && row < this.logLinesPerPage && this.logPage * this.logLinesPerPage + row < this.logLines.size()) {
                tip = this.logLines.get(this.logPage * this.logLinesPerPage + row);
            } else if (my >= this.logReasonY && my < this.logLineTop) {
                tip = this.logReason;
            } else if (my >= this.bottom - 32 && my < this.bottom - 20) {
                tip = this.logCommonIssue;
            }
            if (!tip.isEmpty() && this.font.width(tip) > this.inner) {
                g.setTooltipForNextFrame(Component.literal(tip), mx, my);
            }
        }
    }

    @Override void content(GuiGraphicsExtractor g) {
        fillRounded2(g, this.x, this.tabBarY, this.inner, this.tabBarH, TRACK);
        outlineRounded2(g, this.x, this.tabBarY, this.inner, this.tabBarH, LINE);

        if (viewTab == ViewTab.RUNDENLOG) {
            drawLog(g);
            return;
        }
        if (setups.isEmpty()) {
            fillRounded(g, x, bodyTop, inner, 48, CARD_BG);
            outlineRounded(g, x, bodyTop, inner, 48, LINE);
            line(g, "Dein erster Versuch", x + 10, bodyTop + 10, inner - 20, TEXT);
            line(g, "Nach einer gewerteten Runde erscheint hier dein Verlauf.", x + 10, bodyTop + 26, inner - 20, NEUTRAL);
            return;
        }
        int actionWidth = inner < 340 ? 68 : 92;
        line(g, setupLabel, x + 23, bodyTop + 4, inner - actionWidth - 49, TEXT);
        if (mouseX >= x + 22 && mouseX < right - actionWidth - 22 && mouseY >= bodyTop && mouseY < bodyTop + 16) {
            String id = setup.substring(0, Math.min(8, setup.length()));
            g.setComponentTooltipForNextFrame(font, List.of(Component.literal(setupLabel),
                Component.literal("Konfiguration " + (setups.indexOf(setup) + 1) + " / " + setups.size() + " · " + id)), mouseX, mouseY);
        }
        if (details) { drawDetails(g); return; }
        var last = runs.getLast();
        card(g, 0, "Letzter Score", Integer.toString(last.score()), sec(last.seconds()) + " · " + (last.won() ? "Geschafft" : "Fehlversuch"), last.won() ? MINT : CORAL);
        String gapToBest = leaders.isEmpty() ? "Noch kein Erfolg" : last.score() == best ? "Rekord erreicht" : "Noch " + (best - last.score()) + " Punkte";
        card(g, 1, "Bestleistung", best == 0 ? "–" : Integer.toString(best), gapToBest, AMBER);
        card(g, 2, "Erfolgsquote", Math.round(100.0 * wins / visible.size()) + " %", wins + " / " + visible.size() + " geschafft", MINT);
        drawChart(g);
        if (footY + 12 < bottom) drawSummary(g);
    }

    private void drawLog(GuiGraphicsExtractor g) {
        TrainingStatus status = TrainerClient.status();
        String rate = status.attempts() == 0 ? "–" : Math.round(100.0F * status.successes() / status.attempts()) + " %";
        String[] labels = {"Serie", "Erfolgsquote", "Median · Erfolge"};
        String[] values = {status.successes() + "/" + status.attempts(), rate, this.logMedian};
        for (int i = 0; i < labels.length; i++) {
            int cx = this.col(i, 3);
            int cw = this.cw(i, 3);
            fillRounded(g, cx, this.bodyTop, cw, 31, CARD_BG);
            outlineRounded(g, cx, this.bodyTop, cw, 31, LINE);
            this.line(g, labels[i], cx + 7, this.bodyTop + 5, cw - 14, MUTED);
            this.line(g, values[i], cx + 7, this.bodyTop + 17, cw - 14, TEXT);
        }

        for (int i = 0; i < Math.min(3, this.logReasonRows.size()); i++) {
            g.text(this.font, this.logReasonRows.get(i), this.x, this.logReasonY + 10 * i, TEXT, false);
        }

        if (this.logLines.isEmpty()) {
            this.line(g, "Hier erscheinen die Ereignisse des Versuchs.", this.x, this.logLineTop, this.inner, MUTED);
        }

        for (int i = 0; i < this.logLinesPerPage && this.logPage * this.logLinesPerPage + i < this.logLines.size(); i++) {
            int ty = this.logLineTop + i * 12;
            fillRounded(g, this.x, ty - 1, this.inner, 11, (i & 1) == 0 ? CANVAS : CARD_BG);
            this.line(g, this.logLines.get(this.logPage * this.logLinesPerPage + i), this.x + 5, ty + 1, this.inner - 10, NEUTRAL);
        }

        if (!this.logCommonIssue.isEmpty()) {
            this.line(g, this.logCommonIssue, this.x, this.bottom - 30, this.inner, AMBER);
        }
        if (this.logPageCount > 1) {
            this.line(g, "Ereignisse · " + (this.logPage + 1) + "/" + this.logPageCount, this.x + 36, this.bottom - 13, this.inner - 72, MUTED);
        }
    }

    private void card(GuiGraphicsExtractor g, int col, String name, String value, String detail, int color) {
        int cLeft = col(col, 3), width = cw(col, 3), h = chartY - cardsY - 4;
        fillRounded(g, cLeft, cardsY, width, h, CARD_BG);
        outlineRounded(g, cLeft, cardsY, width, h, LINE);
        g.fill(cLeft + 1, cardsY + 4, cLeft + 3, cardsY + h - 4, color);
        line(g, name, cLeft + 8, cardsY + 5, width - 11, NEUTRAL);
        var pose = g.pose(); pose.pushMatrix(); pose.translate(cLeft + 8, cardsY + 15);
        pose.scale(1.85f);
        g.text(font, value, 0, 0, color, false); pose.popMatrix();
        if (h >= 42) line(g, detail, cLeft + 8, cardsY + h - 10, width - 11, NEUTRAL);
    }

    private void drawChart(GuiGraphicsExtractor g) {
        fillRounded(g, x, chartY, inner, footY - 2 - chartY, CANVAS);
        outlineRounded(g, x, chartY, inner, footY - 2 - chartY, LINE);
        line(g, "Score-Verlauf", x + 8, chartY + 7, inner - 100, TEXT);
        if (plotBottom - plotTop < 8) return;
        int averageX = x + 16 + font.width("Score-Verlauf");
        line(g, "Ø " + average, averageX, chartY + 7, right - 90 - averageX, NEUTRAL);
        if (high.length > 1) {
            for (int xx = plotLeft; xx < plotRight; xx += 2) {
                double at = (xx - plotLeft) * (high.length - 1.0) / (plotRight - plotLeft);
                int a = (int)at, b = Math.min(a + 1, high.length - 1);
                double score = high[a] + (high[b] - high[a]) * (at - a);
                g.fill(xx, scoreY((int)Math.round(score)), Math.min(xx + 2, plotRight), plotBottom, 0x1c61b6ad);
            }
        }
        for (int k = 0; k < 3; k++) {
            int mark = 2000 - k * 1000;
            int yy = scoreY(mark);
            line(g, Integer.toString(mark), x + 5, yy - 3, 27, NEUTRAL);
            g.fill(plotLeft, yy, plotRight + 1, yy + 1, GRID);
        }
        int averageY = scoreY(average);
        for (int xx = plotLeft; xx < plotRight; xx += 7) g.fill(xx, averageY, Math.min(xx + 3, plotRight), averageY + 1, 0x81908aa8);
        int n = high.length, previousX = 0, previousY = 0;
        for (int col = 0; col < n; col++) {
            int xx = plotX(col, n), yy = scoreY(high[col]), ly = scoreY(low[col]);
            if (low[col] != high[col]) g.fill(xx, yy, xx + 1, ly + 1, 0xff748d88);
            if (col > 0) segment(g, previousX, previousY, xx, yy);
            if (n <= 40 || col == n - 1) {
                int marker = n <= 35 ? 2 : 1;
                g.fill(xx - marker, yy - marker, xx + marker + 1, yy + marker + 1, high[col] == 0 ? CORAL : MINT);
            }
            previousX = xx; previousY = yy;
        }
        line(g, "#" + (runs.size() - visible.size() + 1), plotLeft, plotBottom + 3, 40, NEUTRAL);
        String end = "#" + runs.size();
        if (visible.size() > 1) line(g, end, plotRight - font.width(end), plotBottom + 3, font.width(end) + 1, NEUTRAL);
        if (pointerMoved && overChart(mouseX, mouseY)) {
            int idx = hoverIndex(mouseX);
            var sample = visible.get(idx);
            int xx = plotX(idx, visible.size()), yy = scoreY(sample.score());
            g.fill(xx, plotTop, xx + 1, plotBottom + 1, 0x80e3e2dc);
            g.fill(xx - 2, yy - 2, xx + 3, yy + 3, sample.won() ? MINT : CORAL);
            g.setComponentTooltipForNextFrame(font, List.of(
                Component.literal("Versuch " + (runs.size() - visible.size() + idx + 1) + " · " + (sample.won() ? "Geschafft" : "Fehlversuch")),
                Component.literal(sample.score() + " Punkte  ·  " + sec(sample.seconds())),
                Component.literal("Klicken für Rundendetails")), mouseX, mouseY);
        }
    }

    private int scoreY(int score) { return plotBottom - (int)Math.round(score * (plotBottom - plotTop) / 2000.0); }
    private int plotX(int i, int n) { return n == 1 ? (plotLeft + plotRight) / 2 : plotLeft + (int)Math.round(i * (plotRight - plotLeft) / (double)(n - 1)); }
    private boolean overChart(double mx, double my) { return mx >= plotLeft - 2 && mx <= plotRight + 2 && my >= plotTop - 2 && my <= plotBottom + 2; }
    private int hoverIndex(double mx) { return Math.clamp((int)Math.round((mx - plotLeft) * (visible.size() - 1) / Math.max(1, plotRight - plotLeft)), 0, visible.size() - 1); }

    private void segment(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        var pose = g.pose(); pose.pushMatrix(); pose.translate(x1 + .5f, y1 + .5f);
        pose.rotate((float)Math.atan2(y2 - y1, x2 - x1));
        g.fill(0, 0, (int)Math.ceil(Math.hypot(x2 - x1, y2 - y1)), 1, 0xff7ebdba); pose.popMatrix();
    }

    private void drawSummary(GuiGraphicsExtractor g) {
        int cut = x + (inner * 54) / 100;
        int sh = bottom - footY;
        fillRounded(g, x, footY, cut - gap - x, sh, CARD_BG);
        outlineRounded(g, x, footY, cut - gap - x, sh, LINE);
        fillRounded(g, cut, footY, right - cut, sh, CARD_BG);
        outlineRounded(g, cut, footY, right - cut, sh, LINE);
        int w = cut - gap - x - 12;
        line(g, "Vergleich  ·  " + visible.size() + " Versuche", x + 6, footY + 4, w, TEXT);
        String trend = oldSize == 0 ? "" : "  (" + String.format(Locale.GERMAN, "%+d", meanDelta) + ")";
        line(g, "Ø Punkte  " + average + trend, x + 6, footY + 16, w, NEUTRAL);
        if (footY + 35 < bottom) line(g, "Median Erfolg  " + (Double.isNaN(median) ? "–" : sec(median)), x + 6, footY + 27, w, NEUTRAL);
        if (oldSize > 0 && mouseX >= x && mouseX < cut - gap && mouseY >= footY && mouseY < bottom)
            g.setTooltipForNextFrame(Component.literal("Score-Differenz zu den " + oldSize + " vorherigen Versuchen"), mouseX, mouseY);
        line(g, "Persönliche Top 3", cut + 6, footY + 4, right - cut - 12, TEXT);
        for (int i = 0; i < leaders.size() && footY + 24 + 10 * i <= bottom; i++) {
            var row = leaders.get(i);
            line(g, (i + 1) + ".   " + row.score() + " P.   " + sec(row.seconds()), cut + 6, footY + 16 + i * 10, right - cut - 12, i == 0 ? AMBER : NEUTRAL);
        }
        if (leaders.isEmpty()) line(g, "Noch kein Erfolg", cut + 6, footY + 16, right - cut - 12, NEUTRAL);
    }

    @Override public boolean mouseClicked(MouseButtonEvent ev, boolean twice) {
        if (viewTab == ViewTab.VERLAUF && !details && !visible.isEmpty() && ev.button() == 0 && overChart(ev.x(), ev.y())) {
            focus = runs.size() - visible.size() + hoverIndex(ev.x()); details = true; page = 0; refresh(); return true;
        }
        return super.mouseClicked(ev, twice);
    }

    private void buildDetails() {
        int nav = bodyTop + 21;
        var prev = action(x, nav, 21, 16, "‹", Style.NORMAL, () -> { focus--; page = 0; refresh(); });
        prev.active = focus > 0; prev.tip("Vorheriger Versuch");
        var next = action(right - 21, nav, 21, 16, "›", Style.NORMAL, () -> { focus++; page = 0; refresh(); });
        next.active = focus + 1 < runs.size(); next.tip("Nächster Versuch");
        int eventStart = bodyTop + 98;
        perPage = Math.max(1, (bottom - 17 - eventStart) / 13);
        int count = runs.get(focus).timeline().size();
        page = Math.clamp(page, 0, Math.max(0, (count - 1) / perPage));
        if (count > perPage) {
            var back = action(x, bottom - 15, 21, 15, "‹", Style.NORMAL, () -> { page--; refresh(); });
            var more = action(right - 21, bottom - 15, 21, 15, "›", Style.NORMAL, () -> { page++; refresh(); });
            back.active = page > 0; more.active = (page + 1) * perPage < count;
        }
    }

    private void drawDetails(GuiGraphicsExtractor g) {
        var run = runs.get(focus);
        line(g, "Versuch " + (focus + 1) + " / " + runs.size() + "   " + date(run), x + 27, bodyTop + 25, inner - 54, TEXT);
        int topBox = bodyTop + 41, c = run.won() ? MINT : CORAL;
        fillRounded(g, x, topBox, inner, 38, CARD_BG);
        outlineRounded(g, x, topBox, inner, 38, LINE);
        g.fill(x + 1, topBox + 4, x + 3, topBox + 34, c);
        line(g, (run.won() ? "Geschafft" : "Fehlversuch") + "    " + run.score() + " P.    " + sec(run.seconds()), x + 8, topBox + 6, inner - 16, c);
        String why = run.reason().isEmpty() ? "Kein Abschlussgrund gespeichert" : run.reason();
        line(g, why, x + 8, topBox + 22, inner - 16, TEXT);
        if (mouseY >= topBox && mouseY < topBox + 38 && mouseX >= x && mouseX < right && font.width(why) > inner - 16)
            g.setTooltipForNextFrame(Component.literal(why), mouseX, mouseY);
        line(g, "Ereignisse", x + 2, bodyTop + 85, inner - 4, TEXT);
        if (run.timeline().isEmpty()) { line(g, "Keine Ereignisse aufgezeichnet.", x + 2, bodyTop + 99, inner - 4, NEUTRAL); return; }
        int start = page * perPage;
        for (int i = start; i < Math.min(run.timeline().size(), start + perPage); i++) {
            int ty = bodyTop + 98 + (i - start) * 13;
            fillRounded(g, x, ty, inner, 12, (i & 1) == 0 ? CANVAS : CARD_BG);
            String event = run.timeline().get(i);
            line(g, event, x + 6, ty + 2, inner - 12, NEUTRAL);
            if (mouseY >= ty && mouseY < ty + 12 && mouseX >= x && mouseX < right && font.width(event) > inner - 12)
                g.setTooltipForNextFrame(Component.literal(event), mouseX, mouseY);
        }
        if (run.timeline().size() > perPage) line(g, "Seite " + (page + 1) + " / " + ((run.timeline().size() + perPage - 1) / perPage), x + 27, bottom - 11, inner - 54, NEUTRAL);
    }

    @Override public void tick() {
        if (viewTab == ViewTab.RUNDENLOG) {
            TrainingStatus status = TrainerClient.status();
            if (this.logActive != status.active() || !this.logRecent.equals(status.recent())
                || (this.logSelected < 0 && !this.logLines.equals(status.timeline()))) {
                this.refresh();
            }
        }
    }
}

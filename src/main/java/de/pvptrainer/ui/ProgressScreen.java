package de.pvptrainer.ui;

import de.pvptrainer.TrainerClient;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.progress.DrillHistory;
import de.pvptrainer.progress.RoundMetrics;
import de.pvptrainer.progress.TrainingProgress;
import de.pvptrainer.progress.TrainingStats;
import de.pvptrainer.session.TrainingStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Map.Entry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/** Real-metrics Apple-Obsidian Statistics hub: Übersicht (Mode KPIs) | Übungen (Drill Charts) | Rundenlog | Bot-Analyse. */
final class ProgressScreen extends TrainerPanel {
    private static final int CANVAS = 0xFF0E0E13, CARD_BG = 0xFF17171E, GRID = 0x1EFFFFFF;
    private static final int MINT = 0xFF4CD964, CORAL = 0xFFFF7A6B, AMBER = 0xFFFFB84D, SKY = 0xFF5AC8FA, VIOLET = 0xFFBF8BFF;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm").withZone(ZoneId.systemDefault());
    private static final int[] CHART_LIMITS = {10, 30, Integer.MAX_VALUE};

    enum ViewTab { OVERVIEW, DRILLS, LOG }

    private ViewTab viewTab = ViewTab.OVERVIEW;
    private TrainingSettings.Mode filterMode; // null = Gesamt in Overview
    private TrainingStats.TimeRange timeRange = TrainingStats.TimeRange.ALL;

    private int tabBarY, tabBarH, bodyTop;
    private int mouseX, mouseY, initialMouseX, initialMouseY;
    private boolean firstFrame, pointerMoved;

    // Cached full run history for current frame/build
    private List<TrainingProgress.Run> allRuns = List.of();

    // Tab 1: Overview state
    private TrainingStats.ModeOverview overview;
    private final List<TopDrillCard> topDrillCards = new ArrayList<>();
    private int overviewHeroY, overviewDetailsY, overviewTopY;

    // Tab 2: Drills state
    private TrainingSettings.Mode drillMode;
    private Drill selectedDrill;
    private List<Drill> drillChoices = List.of();
    private TrainingStats.DrillReport report;
    private List<TrainingProgress.Run> chartRuns = List.of();
    private int chartRangeIdx = 1; // default last 30
    private boolean showDetails;
    private int detailFocus, detailPage, detailPerPage;
    private int cardsY, chartY, footY, plotLeft, plotRight, plotTop, plotBottom;
    private double chartMaxY = 1.0;

    // Tab 3: Rundenlog state
    private int logPage, logSelected = -1;
    private int logReasonY, logLineTop, logPageCount, logLinesPerPage;
    private List<FormattedCharSequence> logReasonRows = List.of();
    private List<String> logLines = List.of();
    private List<DrillHistory.Result> logRecent = List.of();
    private String logReason = "", logCommonIssue = "", logMedian = "–";
    private boolean logActive;

    ProgressScreen(Screen parent, TrainingSettings.Mode mode) {
        super(parent, "Statistik", mode);
        this.filterMode = mode;
        this.drillMode = mode;
        this.selectedDrill = TrainerClient.options(mode).drill();
    }

    static ProgressScreen openTab(Screen parent, TrainingSettings.Mode mode, ViewTab tab) {
        ProgressScreen screen = new ProgressScreen(parent, mode);
        screen.viewTab = tab;
        return screen;
    }

    static ProgressScreen openLog(Screen parent, TrainingSettings.Mode mode, boolean focusLast) {
        ProgressScreen screen = new ProgressScreen(parent, mode);
        screen.viewTab = ViewTab.LOG;
        if (focusLast && !TrainerClient.status().recent().isEmpty()) {
            screen.logSelected = TrainerClient.status().recent().size() - 1;
        }
        return screen;
    }

    @Override void build() {
        this.firstFrame = true;
        this.pointerMoved = false;
        this.allRuns = TrainingProgress.forScreen(this.minecraft);

        // Unified 4-Tab Apple Segmented Control: Übersicht | Übungen | Rundenlog | Bot-Analyse
        this.tabBarY = this.y;
        this.tabBarH = 22;
        String[] tabLabels = {"Übersicht", "Übungen", "Rundenlog", "Bot-Analyse"};
        int segX = this.x + 2;
        int segW = this.inner - 4;
        for (int i = 0; i < tabLabels.length; i++) {
            final int idx = i;
            boolean active = (i == 0 && viewTab == ViewTab.OVERVIEW)
                || (i == 1 && viewTab == ViewTab.DRILLS)
                || (i == 2 && viewTab == ViewTab.LOG);
            this.action(gridCol(segX, segW, 2, i, 4), this.tabBarY + 2, gridW(segX, segW, 2, i, 4), this.tabBarH - 4,
                tabLabels[i], active ? Style.SEGMENT_SELECTED : Style.SEGMENT, () -> {
                    if (idx == 0 && viewTab != ViewTab.OVERVIEW) { viewTab = ViewTab.OVERVIEW; refresh(); }
                    else if (idx == 1 && viewTab != ViewTab.DRILLS) { viewTab = ViewTab.DRILLS; showDetails = false; refresh(); }
                    else if (idx == 2 && viewTab != ViewTab.LOG) { viewTab = ViewTab.LOG; refresh(); }
                    else if (idx == 3) open(new BotAnalysisScreen(this.parent, this.mode, null));
                });
        }

        this.bodyTop = this.tabBarY + this.tabBarH + 7;
        switch (this.viewTab) {
            case OVERVIEW -> buildOverview();
            case DRILLS -> buildDrills();
            case LOG -> buildLog();
        }
    }

    // ==================== TAB 1: ÜBERSICHT ====================

    private void buildOverview() {
        this.overview = TrainingStats.overview(this.allRuns, this.filterMode, this.timeRange);
        this.subtitle = (this.filterMode == null ? "Alle Modi" : this.filterMode.label()) + " · " + this.timeRange.label();

        int filterY = this.bodyTop;
        int filterH = 18;
        int rangeW = 128;
        int modeAreaW = this.inner - rangeW - 8;

        // Mode filter pills: Gesamt | Crystal | Mace | Sword | UHC | HugoSMP
        TrainingSettings.Mode[] modes = TrainingSettings.Mode.values();
        int modeCount = modes.length + 1;
        for (int i = 0; i < modeCount; i++) {
            final TrainingSettings.Mode target = i == 0 ? null : modes[i - 1];
            String label = target == null ? "Gesamt" : target.label();
            int bx = gridCol(this.x, modeAreaW, 2, i, modeCount);
            int bw = gridW(this.x, modeAreaW, 2, i, modeCount);
            this.action(bx, filterY, bw, filterH, label, target == this.filterMode ? Style.SELECTED : Style.NORMAL, () -> {
                if (this.filterMode != target) {
                    this.filterMode = target;
                    this.refresh();
                }
            });
        }

        // TimeRange pills: Heute | 7T | Gesamt
        TrainingStats.TimeRange[] ranges = TrainingStats.TimeRange.values();
        int rangeX = this.right - rangeW;
        for (int i = 0; i < ranges.length; i++) {
            TrainingStats.TimeRange tr = ranges[i];
            String shortLbl = tr == TrainingStats.TimeRange.WEEK ? "7T" : tr.label();
            int bx = gridCol(rangeX, rangeW, 2, i, ranges.length);
            int bw = gridW(rangeX, rangeW, 2, i, ranges.length);
            this.action(bx, filterY, bw, filterH, shortLbl, tr == this.timeRange ? Style.SELECTED : Style.NORMAL, () -> {
                if (this.timeRange != tr) {
                    this.timeRange = tr;
                    this.refresh();
                }
            }).tip("Zeitraum: " + tr.label());
        }

        this.overviewHeroY = filterY + filterH + 8;
        this.overviewDetailsY = this.overviewHeroY + 56 + 14;
        this.overviewTopY = this.overviewDetailsY + 2 * 34 + 4 + 14;

        this.topDrillCards.clear();
        List<TrainingStats.DrillSummary> top = this.overview.topDrills();
        int cardCount = Math.min(3, top.size());
        int topCardsY = this.overviewTopY + 11;
        int cardH = Math.max(24, Math.min(32, this.bottom - topCardsY));
        for (int i = 0; i < cardCount; i++) {
            int cx = this.col(i, cardCount);
            int cw = this.cw(i, cardCount);
            this.topDrillCards.add(new TopDrillCard(top.get(i), cx, topCardsY, cw, cardH));
        }
    }

    private void drawOverview(GuiGraphicsExtractor g) {
        if (this.overview == null || this.overview.isEmpty()) {
            fillRounded(g, this.x, this.overviewHeroY, this.inner, 56, CARD_BG);
            outlineRounded(g, this.x, this.overviewHeroY, this.inner, 56, LINE);
            String scope = this.filterMode == null ? "diesen Zeitraum" : this.filterMode.label() + " (" + this.timeRange.label() + ")";
            line(g, "Noch keine Daten für " + scope, this.x + 12, this.overviewHeroY + 12, this.inner - 24, TEXT);
            line(g, "Starte eine beliebige Übung, ein freies Duell oder Passives Ziel – alle echten Aktionen werden hier gesammelt.",
                this.x + 12, this.overviewHeroY + 28, this.inner - 24, MUTED);
            return;
        }

        List<KpiTile> heroes = buildHeroTiles(this.overview);
        for (int i = 0; i < heroes.size(); i++) {
            KpiTile kpi = heroes.get(i);
            int cx = this.col(i, 4);
            int cw = this.cw(i, 4);
            drawHeroCard(g, cx, this.overviewHeroY, cw, 54, kpi);
        }

        String secTitle = "DETAILS · " + (this.filterMode == null ? "ALLE MODI" : this.filterMode.label().toUpperCase(Locale.ROOT));
        g.text(this.font, secTitle, this.x + 1, this.overviewDetailsY - 10, MUTED, false);

        List<KpiTile> details = buildDetailTiles(this.overview);
        for (int i = 0; i < Math.min(6, details.size()); i++) {
            int col = i % 3;
            int row = i / 3;
            int cx = this.col(col, 3);
            int cw = this.cw(col, 3);
            int cy = this.overviewDetailsY + row * 38;
            drawDetailCard(g, cx, cy, cw, 34, details.get(i));
        }

        if (!this.topDrillCards.isEmpty() && this.overviewTopY + 24 < this.bottom) {
            g.text(this.font, "MEISTGESPIELTE ÜBUNGEN · KLICK FÜR DETAILS", this.x + 1, this.overviewTopY, MUTED, false);
            for (TopDrillCard card : this.topDrillCards) {
                boolean hover = mouseX >= card.x && mouseX < card.x + card.w && mouseY >= card.y && mouseY < card.y + card.h;
                fillRounded(g, card.x, card.y, card.w, card.h, hover ? HOVER : CARD_BG);
                outlineRounded(g, card.x, card.y, card.w, card.h, hover ? BORDER_HOVER : LINE);

                ItemStack icon = UiIcons.drillIcon(card.summary.drill(), card.summary.mode());
                var pose = g.pose();
                pose.pushMatrix();
                pose.translate(card.x + 6, card.y + (card.h - 12) / 2);
                pose.scale(0.75f);
                g.item(icon, 0, 0);
                pose.popMatrix();

                String title = this.filterMode == null
                    ? card.summary.mode().label() + " · " + card.summary.drill().label()
                    : card.summary.drill().label();
                line(g, title, card.x + 22, card.y + 4, card.w - 28, TEXT);
                line(g, card.summary.headline(), card.x + 22, card.y + card.h - 11, card.w - 28, MUTED);
            }
        }
    }

    private static List<KpiTile> buildHeroTiles(TrainingStats.ModeOverview o) {
        RoundMetrics m = o.totals();
        if (o.mode() == null) {
            return List.of(
                new KpiTile("Totems gepoppt", TrainingStats.formatInt(m.botPops()),
                    m.playerPops() > 0 ? m.playerPops() + " eigene Pops" : "Über alle Modi", AMBER),
                new KpiTile("Mace-Smashes", TrainingStats.formatInt(m.maceSmashes()),
                    m.stunSlams() + " Stun-Slams · Max " + TrainingStats.formatMeters(m.maxFallMeters()), SKY),
                new KpiTile("Kristalle gezündet", TrainingStats.formatInt(Math.max(m.crystalsDetonated(), m.crystalsPlaced())),
                    m.crystalsPerPop() > 0 ? "Ø " + TrainingStats.formatRatio(m.crystalsPerPop()) + " pro Pop" : m.crystalsPlaced() + " gesetzt", VIOLET),
                new KpiTile("Trainingszeit", TrainingStats.formatDuration(o.totalSeconds()),
                    TrainingStats.formatInt(o.totalRounds()) + " Runden · " + o.winRatePct() + " % Quote", MINT)
            );
        }
        return switch (o.mode()) {
            case MACE -> List.of(
                new KpiTile("Stun-Slams", TrainingStats.formatInt(m.stunSlams()),
                    "Axt-Stun → Smash", MINT),
                new KpiTile("Mace-Smashes", TrainingStats.formatInt(m.maceSmashes()),
                    m.avgFallMeters() > 0 ? "Ø " + TrainingStats.formatMeters(m.avgFallMeters()) + " Fall" : "Echte Smashes", SKY),
                new KpiTile("Höchster Smash", TrainingStats.formatMeters(m.maxFallMeters()),
                    m.maxSmashHearts() > 0 ? "Stärkster Hit " + TrainingStats.formatHearts(m.maxSmashHearts()) : "Rekord-Fallhöhe", AMBER),
                new KpiTile("Totems gepoppt", TrainingStats.formatInt(m.botPops()),
                    m.playerPops() > 0 ? m.playerPops() + " eigene" : "Bot-Totems", CORAL)
            );
            case CRYSTAL -> List.of(
                new KpiTile("Totems gepoppt", TrainingStats.formatInt(m.botPops()),
                    m.playerPops() > 0 ? m.playerPops() + " eigene" : "Bot-Totems", AMBER),
                new KpiTile("Kristalle gezündet", TrainingStats.formatInt(Math.max(m.crystalsDetonated(), m.crystalsPlaced())),
                    TrainingStats.formatInt(m.crystalsPlaced()) + " gesetzt", VIOLET),
                new KpiTile("Kristalle / Pop", TrainingStats.formatRatio(m.crystalsPerPop()),
                    m.crystalsPerPop() > 0 ? "Effizienz (1,0 = perfekt)" : "Noch kein Pop", MINT),
                new KpiTile("Anker gezündet", TrainingStats.formatInt(m.anchorsDetonated()),
                    m.anchorPops() + " Anker-Pops", SKY)
            );
            case SWORD -> List.of(
                new KpiTile("Schwert-Treffer", TrainingStats.formatInt(m.meleeHits()),
                    TrainingStats.formatHearts(m.damageHearts()) + " Schaden", SKY),
                new KpiTile("Crit-Quote", m.meleeHits() > 0 ? TrainingStats.formatPercent(m.critRatePct()) : "–",
                    TrainingStats.formatInt(m.crits()) + " Crits gelandet", AMBER),
                new KpiTile("Ø Charge", m.chargeSamples() > 0 ? TrainingStats.formatPercent(m.avgChargePct()) : "–",
                    "Angriffsladung beim Hit", MINT),
                new KpiTile("Duell-Bilanz", o.duelTotal() > 0 ? o.duelWinRatePct() + " %" : "–",
                    o.duelTotal() > 0 ? o.duelWins() + " S / " + o.duelLosses() + " N" : "Noch kein Duell", CORAL)
            );
            case UHC -> List.of(
                new KpiTile("Cobwebs gesetzt", TrainingStats.formatInt(m.websPlaced()),
                    "Stun-Web, Dunk & Traps", SKY),
                new KpiTile("Schild-Stuns", TrainingStats.formatInt(m.shieldStuns()),
                    m.playerBlocks() + " eigene Blocks", AMBER),
                new KpiTile("Beste Obi-Trap", TrainingStats.formatSeconds(o.bestObiTrapSeconds()),
                    TrainingStats.formatInt(m.obsidianPlaced()) + " Obsidian gesetzt", MINT),
                new KpiTile("Lava & Drains", TrainingStats.formatInt(m.lavaPlaced()) + " / " + TrainingStats.formatInt(m.waterDrains()),
                    "Lava gesetzt · Wasser-Drains", CORAL)
            );
            case HUGOSMP -> List.of(
                new KpiTile("Totems gepoppt", TrainingStats.formatInt(m.botPops()),
                    m.playerPops() > 0 ? m.playerPops() + " eigene" : "Bot-Totems", AMBER),
                new KpiTile("Kristalle gezündet", TrainingStats.formatInt(Math.max(m.crystalsDetonated(), m.crystalsPlaced())),
                    m.crystalsPerPop() > 0 ? "Ø " + TrainingStats.formatRatio(m.crystalsPerPop()) + " / Pop" : m.crystalsPlaced() + " gesetzt", VIOLET),
                new KpiTile("Mace-Smashes", TrainingStats.formatInt(m.maceSmashes()),
                    m.stunSlams() + " Stun-Slams · Max " + TrainingStats.formatMeters(m.maxFallMeters()), SKY),
                new KpiTile("Blocks & Stuns", TrainingStats.formatInt(m.playerBlocks()) + " / " + TrainingStats.formatInt(m.shieldStuns()),
                    "Konter geblockt · Axt-Stuns", MINT)
            );
        };
    }

    private static List<KpiTile> buildDetailTiles(TrainingStats.ModeOverview o) {
        RoundMetrics m = o.totals();
        String timeAndRounds = TrainingStats.formatDuration(o.totalSeconds()) + " · " + TrainingStats.formatInt(o.totalRounds()) + " Rnd.";
        String duelText = o.duelTotal() > 0 ? o.duelWinRatePct() + " % (" + o.duelWins() + "/" + o.duelTotal() + ")" : "–";
        if (o.mode() == null) {
            return List.of(
                new KpiTile("Höchster Smash", TrainingStats.formatMeters(m.maxFallMeters()), "Ø " + TrainingStats.formatMeters(m.avgFallMeters()), TEXT),
                new KpiTile("Schwert-Treffer & Crits", TrainingStats.formatInt(m.meleeHits()) + " (" + TrainingStats.formatPercent(m.critRatePct()) + ")", TrainingStats.formatInt(m.crits()) + " Crits", TEXT),
                new KpiTile("Cobwebs & Schild-Stuns", TrainingStats.formatInt(m.websPlaced()) + " · " + TrainingStats.formatInt(m.shieldStuns()), "Webs · Axt-Stuns", TEXT),
                new KpiTile("Wind Charges & Catches", TrainingStats.formatInt(m.windChargesUsed()) + " · " + TrainingStats.formatInt(m.pearlCatches()), "Würfe · Pearl-Catches", TEXT),
                new KpiTile("Längste Serie", o.bestStreak() > 0 ? o.bestStreak() + "×" : "–", "Beste Drill-Streak", TEXT),
                new KpiTile("Meistgespielter Modus", o.topMode() == null ? "–" : o.topMode().label(), "Duell: " + duelText, TEXT)
            );
        }
        return switch (o.mode()) {
            case MACE -> List.of(
                new KpiTile("Wind Charges geworfen", TrainingStats.formatInt(m.windChargesUsed()), "Eigenstarts & Catches", TEXT),
                new KpiTile("Max. Wind-Höhe", TrainingStats.formatMeters(m.maxWindMeters()), "Perfect Wind Charge", TEXT),
                new KpiTile("Pearl-Catches", TrainingStats.formatInt(m.pearlCatches()), "In der Luft gefangen", TEXT),
                new KpiTile("Schilde gestunnt", TrainingStats.formatInt(m.shieldStuns()) + " (" + m.shieldBreaks() + " Bruch)", "Axt-Stuns & Shield Drain", TEXT),
                new KpiTile("Längste Serie", o.bestStreak() > 0 ? o.bestStreak() + "×" : "–", "Duell: " + duelText, TEXT),
                new KpiTile("Trainingszeit", timeAndRounds, o.winRatePct() + " % Drill-Quote", TEXT)
            );
            case CRYSTAL -> List.of(
                new KpiTile("Gelungene D-Taps", TrainingStats.formatInt(o.dTapWins()), "D-Tap & Self-Web", TEXT),
                new KpiTile("Gelungene Triple-Taps", TrainingStats.formatInt(o.tripleTapWins()), "Triple & Shield-Triple", TEXT),
                new KpiTile("Obsidian gesetzt", TrainingStats.formatInt(m.obsidianPlaced()), "Kristall-Basen", TEXT),
                new KpiTile("Axt-Schild-Stuns", TrainingStats.formatInt(m.shieldStuns()), "Schildbrüche in Luft/Duell", TEXT),
                new KpiTile("Duell-Bilanz", duelText, "Rekord-Serie " + (o.bestStreak() > 0 ? o.bestStreak() + "×" : "–"), TEXT),
                new KpiTile("Trainingszeit", timeAndRounds, o.winRatePct() + " % Drill-Quote", TEXT)
            );
            case SWORD -> List.of(
                new KpiTile("Gesamtschaden", TrainingStats.formatHearts(m.damageHearts()), "Verursachter Schaden", TEXT),
                new KpiTile("Schild-Stuns", TrainingStats.formatInt(m.shieldStuns()), "Axt-Deaktivierungen", TEXT),
                new KpiTile("Eigene Schild-Blocks", TrainingStats.formatInt(m.playerBlocks()), "Geblockte Bot-Hits", TEXT),
                new KpiTile("Erfolgsquote (Drills)", o.scoredAttempts() > 0 ? o.winRatePct() + " %" : "–", o.scoredWins() + " / " + o.scoredAttempts(), TEXT),
                new KpiTile("Längste Serie", o.bestStreak() > 0 ? o.bestStreak() + "×" : "–", "Beste Drill-Streak", TEXT),
                new KpiTile("Trainingszeit", timeAndRounds, "Sword-Training", TEXT)
            );
            case UHC -> List.of(
                new KpiTile("Gapples / Heads gegessen", TrainingStats.formatInt(m.healsEaten()), "Heilfenster & Duell", TEXT),
                new KpiTile("Schwert-Treffer & Crits", TrainingStats.formatInt(m.meleeHits()) + " (" + TrainingStats.formatPercent(m.critRatePct()) + ")", TrainingStats.formatInt(m.crits()) + " Crits", TEXT),
                new KpiTile("Ø Angriffsladung", m.chargeSamples() > 0 ? TrainingStats.formatPercent(m.avgChargePct()) : "–", "Cooldown-Disziplin", TEXT),
                new KpiTile("Duell-Bilanz", duelText, "UHC-Duelle", TEXT),
                new KpiTile("Längste Serie", o.bestStreak() > 0 ? o.bestStreak() + "×" : "–", o.winRatePct() + " % Drill-Quote", TEXT),
                new KpiTile("Trainingszeit", timeAndRounds, "UHC-Training", TEXT)
            );
            case HUGOSMP -> List.of(
                new KpiTile("Cobwebs gesetzt", TrainingStats.formatInt(m.websPlaced()), "Corner Web & Duell", TEXT),
                new KpiTile("Obsidian gesetzt", TrainingStats.formatInt(m.obsidianPlaced()), "Kristall-Basen", TEXT),
                new KpiTile("Wind Charges geworfen", TrainingStats.formatInt(m.windChargesUsed()), "Mace-Sprünge", TEXT),
                new KpiTile("Gelungene D-Taps", TrainingStats.formatInt(o.dTapWins()), "Mace-Konter & Corner Web", TEXT),
                new KpiTile("Duell-Bilanz", duelText, "Rekord-Serie " + (o.bestStreak() > 0 ? o.bestStreak() + "×" : "–"), TEXT),
                new KpiTile("Trainingszeit", timeAndRounds, o.winRatePct() + " % Drill-Quote", TEXT)
            );
        };
    }

    private void drawHeroCard(GuiGraphicsExtractor g, int x, int y, int w, int h, KpiTile kpi) {
        fillRounded(g, x, y, w, h, CARD_BG);
        outlineRounded(g, x, y, w, h, LINE);
        g.fill(x + 1, y + 6, x + 3, y + h - 6, kpi.accent);
        line(g, kpi.label, x + 8, y + 6, w - 12, MUTED);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x + 8, y + 18);
        float scale = this.font.width(kpi.value) * 1.65f > w - 16 ? 1.25f : 1.65f;
        pose.scale(scale);
        g.text(this.font, kpi.value, 0, 0, kpi.accent, false);
        pose.popMatrix();
        line(g, kpi.detail, x + 8, y + h - 11, w - 12, MUTED);
    }

    private void drawDetailCard(GuiGraphicsExtractor g, int x, int y, int w, int h, KpiTile kpi) {
        fillRounded(g, x, y, w, h, CARD_BG);
        outlineRounded(g, x, y, w, h, LINE);
        line(g, kpi.label, x + 8, y + 5, w - 14, MUTED);
        line(g, kpi.value, x + 8, y + 17, w - 14, TEXT);
    }

    // ==================== TAB 2: ÜBUNGEN (REAL METRICS CHART) ====================

    private void buildDrills() {
        if (this.drillMode == null) this.drillMode = this.mode;
        List<Drill> recorded = TrainingStats.recordedDrillsForMode(this.allRuns, this.drillMode, this.timeRange);
        this.drillChoices = recorded.isEmpty() ? Drill.forMode(this.drillMode) : recorded;
        if (this.selectedDrill == null || !this.drillChoices.contains(this.selectedDrill)) {
            Drill active = TrainerClient.options(this.drillMode).drill();
            this.selectedDrill = this.drillChoices.contains(active) ? active : this.drillChoices.getFirst();
        }
        this.report = TrainingStats.drillReport(this.allRuns, this.drillMode, this.selectedDrill, this.timeRange);
        this.subtitle = this.drillMode.label() + " · " + this.selectedDrill.label();

        int navY = this.bodyTop;
        int modeW = 74;
        this.action(this.x, navY, modeW, 18, this.drillMode.label() + " ▸", Style.NORMAL, () -> {
            TrainingSettings.Mode[] all = TrainingSettings.Mode.values();
            this.drillMode = all[(this.drillMode.ordinal() + 1) % all.length];
            this.selectedDrill = null;
            this.showDetails = false;
            this.refresh();
        }).tip("Modus wechseln");

        int detailW = 86;
        int rangeBtnW = 54;
        int rightEdge = this.right;

        this.action(rightEdge - detailW, navY, detailW, 18, this.showDetails ? "Übersicht" : "Rundendetails", Style.NORMAL, () -> {
            this.showDetails = !this.showDetails;
            if (this.showDetails) {
                this.detailFocus = Math.max(0, this.report.scoredRuns().size() - 1);
                this.detailPage = 0;
            }
            this.refresh();
        }).active = !this.report.scoredRuns().isEmpty();
        rightEdge -= detailW + 4;

        this.action(rightEdge - rangeBtnW, navY, rangeBtnW, 18, this.timeRange.label(), Style.NORMAL, () -> {
            TrainingStats.TimeRange[] trs = TrainingStats.TimeRange.values();
            this.timeRange = trs[(this.timeRange.ordinal() + 1) % trs.length];
            this.showDetails = false;
            this.refresh();
        }).tip("Zeitraum wechseln (Heute / 7 Tage / Gesamt)");
        rightEdge -= rangeBtnW + 4;

        int drillIdx = this.drillChoices.indexOf(this.selectedDrill);
        int arrowsLeft = this.x + modeW + 6;
        Action prev = this.action(arrowsLeft, navY, 18, 18, "‹", Style.NORMAL, () -> {
            this.selectedDrill = this.drillChoices.get(drillIdx - 1);
            this.showDetails = false;
            this.refresh();
        });
        prev.active = drillIdx > 0;
        Action next = this.action(rightEdge - 18, navY, 18, 18, "›", Style.NORMAL, () -> {
            this.selectedDrill = this.drillChoices.get(drillIdx + 1);
            this.showDetails = false;
            this.refresh();
        });
        next.active = drillIdx + 1 < this.drillChoices.size();

        if (this.showDetails) {
            buildDrillDetails();
            return;
        }

        List<TrainingProgress.Run> runs = this.report.scoredRuns();
        int first = Math.max(0, runs.size() - CHART_LIMITS[this.chartRangeIdx]);
        this.chartRuns = runs.subList(first, runs.size());

        this.cardsY = navY + 23;
        this.chartY = this.cardsY + 50;
        this.footY = Math.max(this.chartY + 62, this.bottom - 52);
        this.plotLeft = this.x + 38;
        this.plotRight = this.right - 12;
        this.plotTop = this.chartY + 23;
        this.plotBottom = this.footY - 14;

        double maxVal = 0.0;
        for (TrainingProgress.Run r : this.chartRuns) {
            maxVal = Math.max(maxVal, this.report.chartValue(r));
        }
        double ref = this.report.referenceLine();
        if (Double.isFinite(ref)) maxVal = Math.max(maxVal, ref);
        this.chartMaxY = niceCeiling(maxVal, this.report.axis());

        String[] limLabels = {"10", "30", "Alle"};
        for (int i = 0; i < CHART_LIMITS.length; i++) {
            final int idx = i;
            this.action(this.right - 84 + i * 27, this.chartY + 4, 25, 14, limLabels[i],
                this.chartRangeIdx == i ? Style.SELECTED : Style.NORMAL, () -> { this.chartRangeIdx = idx; this.refresh(); });
        }
    }

    private void drawDrills(GuiGraphicsExtractor g) {
        int arrowsLeft = this.x + 74 + 6;
        int labelX = arrowsLeft + 23;
        int labelMaxW = (this.right - 86 - 4 - 54 - 4 - 22) - labelX;
        String heading = this.selectedDrill.label() + "  ·  " + this.report.attempts() + " Versuche";
        line(g, heading, labelX, this.bodyTop + 5, labelMaxW, TEXT);

        if (this.showDetails) {
            drawDrillDetails(g);
            return;
        }

        // 3 Real Drill KPI Cards (NO SCORE!)
        KpiTile c1 = new KpiTile("Erfolgsquote",
            this.report.attempts() > 0 ? this.report.winRatePct() + " %" : "–",
            this.report.wins() + " / " + this.report.attempts() + " geschafft", MINT);

        KpiTile c2 = switch (this.report.axis()) {
            case FALL_METERS -> new KpiTile("Fallhöhe",
                TrainingStats.formatMeters(this.report.maxFallMeters()),
                "Ø " + TrainingStats.formatMeters(this.report.avgFallMeters()) + " · " + TrainingStats.formatSeconds(this.report.medianSeconds()), AMBER);
            case WIND_METERS -> new KpiTile("Sprunghöhe",
                TrainingStats.formatMeters(this.report.maxWindMeters()),
                "Schwelle: 9,0 m", AMBER);
            case DUEL_IMPACT -> new KpiTile("Pops & Schaden",
                this.report.totals().botPops() > 0
                    ? TrainingStats.formatInt(this.report.totals().botPops()) + " Pops"
                    : TrainingStats.formatHearts(this.report.totals().damageHearts()),
                this.report.totals().botPops() > 0
                    ? TrainingStats.formatHearts(this.report.totals().damageHearts()) + " Schaden"
                    : "Ø Charge " + TrainingStats.formatPercent(this.report.totals().avgChargePct()), SKY);
            case SECONDS -> new KpiTile("Ausführungszeit",
                TrainingStats.formatSeconds(this.report.bestSeconds()),
                "Median " + TrainingStats.formatSeconds(this.report.medianSeconds()), SKY);
        };

        KpiTile c3 = new KpiTile("Serie",
            this.report.bestStreak() > 0 ? "Rekord " + this.report.bestStreak() + "×" : "–",
            "Aktuell " + this.report.currentStreak() + "×", VIOLET);

        drawHeroCard(g, this.col(0, 3), this.cardsY, this.cw(0, 3), 46, c1);
        drawHeroCard(g, this.col(1, 3), this.cardsY, this.cw(1, 3), 46, c2);
        drawHeroCard(g, this.col(2, 3), this.cardsY, this.cw(2, 3), 46, c3);

        drawRealChart(g);
        drawDrillFooter(g);
    }

    private void drawRealChart(GuiGraphicsExtractor g) {
        int ch = this.footY - 4 - this.chartY;
        fillRounded(g, this.x, this.chartY, this.inner, ch, CANVAS);
        outlineRounded(g, this.x, this.chartY, this.inner, ch, LINE);
        line(g, this.report.chartTitle(), this.x + 8, this.chartY + 7, this.inner - 104, TEXT);
        if (this.plotBottom - this.plotTop < 10) return;

        if (this.chartRuns.isEmpty()) {
            line(g, "Noch keine gewerteten Versuche für " + this.selectedDrill.label() + " (" + this.timeRange.label() + ").",
                this.x + 10, this.plotTop + 10, this.inner - 20, MUTED);
            return;
        }

        // Y-axis ticks (0, mid, max)
        for (int k = 0; k < 3; k++) {
            double val = this.chartMaxY * (2 - k) / 2.0;
            int yy = valueY(val);
            line(g, shortAxisLabel(val, this.report.axis()), this.x + 5, yy - 3, 31, MUTED);
            g.fill(this.plotLeft, yy, this.plotRight + 1, yy + 1, GRID);
        }

        // Reference line (Avg / Median / Perfect threshold)
        double ref = this.report.referenceLine();
        if (Double.isFinite(ref) && ref > 0.0 && ref <= this.chartMaxY) {
            int refY = valueY(ref);
            for (int xx = this.plotLeft; xx < this.plotRight; xx += 7) {
                g.fill(xx, refY, Math.min(xx + 3, this.plotRight), refY + 1, 0x66FFFFFF);
            }
        }

        // Plot real values
        int n = this.chartRuns.size();
        int prevX = 0, prevY = 0;
        for (int i = 0; i < n; i++) {
            TrainingProgress.Run r = this.chartRuns.get(i);
            double val = Math.min(this.chartMaxY, Math.max(0.0, this.report.chartValue(r)));
            int xx = plotX(i, n);
            int yy = valueY(val);
            if (i > 0) drawLineSegment(g, prevX, prevY, xx, yy, 0xFF6B8A9E);
            int radius = n <= 35 ? 2 : 1;
            g.fill(xx - radius, yy - radius, xx + radius + 1, yy + radius + 1, r.won() ? MINT : CORAL);
            prevX = xx; prevY = yy;
        }

        int firstIndex = this.report.scoredRuns().size() - n + 1;
        line(g, "#" + firstIndex, this.plotLeft, this.plotBottom + 3, 40, MUTED);
        String endLbl = "#" + this.report.scoredRuns().size();
        if (n > 1) line(g, endLbl, this.plotRight - this.font.width(endLbl), this.plotBottom + 3, this.font.width(endLbl) + 2, MUTED);

        if (this.pointerMoved && overChart(this.mouseX, this.mouseY)) {
            int idx = hoverIndex(this.mouseX);
            TrainingProgress.Run sample = this.chartRuns.get(idx);
            double val = Math.min(this.chartMaxY, Math.max(0.0, this.report.chartValue(sample)));
            int xx = plotX(idx, n), yy = valueY(val);
            g.fill(xx, this.plotTop, xx + 1, this.plotBottom + 1, 0x55FFFFFF);
            g.fill(xx - 2, yy - 2, xx + 3, yy + 3, sample.won() ? MINT : CORAL);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("Versuch #" + (firstIndex + idx) + " · " + (sample.won() ? "Geschafft" : "Fehlversuch"))
                .withStyle(sample.won() ? ChatFormatting.GREEN : ChatFormatting.RED));
            lines.add(Component.literal(this.report.formatChartValue(this.report.chartValue(sample))
                + "  ·  " + TrainingStats.formatSeconds(sample.seconds())));
            String actionLine = summarizeRunMetrics(sample.metrics());
            if (!actionLine.isEmpty()) lines.add(Component.literal(actionLine).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Klicken für Rundendetails").withStyle(ChatFormatting.DARK_GRAY));
            g.setComponentTooltipForNextFrame(this.font, lines, this.mouseX, this.mouseY);
        }
    }

    private void drawDrillFooter(GuiGraphicsExtractor g) {
        int cut = this.x + (this.inner * 52) / 100;
        int h = this.bottom - this.footY;
        if (h < 28) return;

        // Left: Real action summary for this drill
        int leftW = cut - this.gap - this.x;
        fillRounded(g, this.x, this.footY, leftW, h, CARD_BG);
        outlineRounded(g, this.x, this.footY, leftW, h, LINE);
        line(g, "Echte Aktionen (" + this.report.attempts() + " Versuche)", this.x + 7, this.footY + 5, leftW - 14, TEXT);
        List<String> lines = drillActionLines(this.report.totals(), this.drillMode);
        if (lines.isEmpty()) {
            line(g, "Aktionszähler werden ab dieser Version mitgespeichert.", this.x + 7, this.footY + 18, leftW - 14, MUTED);
        } else {
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                line(g, lines.get(i), this.x + 7, this.footY + 18 + i * 12, leftW - 14, MUTED);
            }
        }

        // Right: Top Failure Reasons
        int rightW = this.right - cut;
        fillRounded(g, cut, this.footY, rightW, h, CARD_BG);
        outlineRounded(g, cut, this.footY, rightW, h, LINE);
        line(g, "Häufigste Fehler", cut + 7, this.footY + 5, rightW - 14, TEXT);
        List<TrainingStats.FailureCount> fails = this.report.topFailures();
        if (fails.isEmpty()) {
            line(g, "Keine Fehlversuche in diesem Zeitraum.", cut + 7, this.footY + 18, rightW - 14, MUTED);
        } else {
            for (int i = 0; i < Math.min(3, fails.size()) && this.footY + 26 + i * 11 <= this.bottom; i++) {
                TrainingStats.FailureCount f = fails.get(i);
                line(g, f.count() + "× (" + f.percent() + " %)  " + f.reason(),
                    cut + 7, this.footY + 17 + i * 11, rightW - 14, i == 0 ? AMBER : MUTED);
            }
        }
    }

    private void buildDrillDetails() {
        List<TrainingProgress.Run> runs = this.report.scoredRuns();
        if (runs.isEmpty()) { this.showDetails = false; return; }
        this.detailFocus = Math.clamp(this.detailFocus, 0, runs.size() - 1);
        int nav = this.bodyTop + 23;
        Action prev = this.action(this.x, nav, 22, 16, "‹", Style.NORMAL, () -> { this.detailFocus--; this.detailPage = 0; this.refresh(); });
        prev.active = this.detailFocus > 0;
        Action next = this.action(this.right - 22, nav, 22, 16, "›", Style.NORMAL, () -> { this.detailFocus++; this.detailPage = 0; this.refresh(); });
        next.active = this.detailFocus + 1 < runs.size();

        int eventStart = this.bodyTop + 102;
        this.detailPerPage = Math.max(1, (this.bottom - 18 - eventStart) / 13);
        int count = runs.get(this.detailFocus).timeline().size();
        this.detailPage = Math.clamp(this.detailPage, 0, Math.max(0, (count - 1) / this.detailPerPage));
        if (count > this.detailPerPage) {
            this.action(this.x, this.bottom - 15, 22, 15, "‹", Style.NORMAL, () -> { this.detailPage--; this.refresh(); }).active = this.detailPage > 0;
            this.action(this.right - 22, this.bottom - 15, 22, 15, "›", Style.NORMAL, () -> { this.detailPage++; this.refresh(); }).active = (this.detailPage + 1) * this.detailPerPage < count;
        }
    }

    private void drawDrillDetails(GuiGraphicsExtractor g) {
        List<TrainingProgress.Run> runs = this.report.scoredRuns();
        if (runs.isEmpty()) return;
        TrainingProgress.Run run = runs.get(this.detailFocus);
        line(g, "Versuch #" + (this.detailFocus + 1) + " / " + runs.size() + "   " + formatDate(run),
            this.x + 28, this.bodyTop + 27, this.inner - 56, TEXT);

        int topBox = this.bodyTop + 44;
        int accent = run.won() ? MINT : CORAL;
        fillRounded(g, this.x, topBox, this.inner, 42, CARD_BG);
        outlineRounded(g, this.x, topBox, this.inner, 42, LINE);
        g.fill(this.x + 1, topBox + 5, this.x + 3, topBox + 37, accent);

        String head = (run.won() ? "Geschafft" : "Fehlversuch") + "   ·   " + TrainingStats.formatSeconds(run.seconds())
            + (run.tickRate() != 20 ? " (" + run.tickRate() + " TPS)" : "");
        String actionSummary = summarizeRunMetrics(run.metrics());
        if (!actionSummary.isEmpty()) head += "   ·   " + actionSummary;
        line(g, head, this.x + 8, topBox + 7, this.inner - 16, accent);

        String why = run.reason().isEmpty() ? "Kein Abschlussgrund gespeichert" : run.reason();
        line(g, why, this.x + 8, topBox + 24, this.inner - 16, TEXT);

        line(g, "Ereignisse", this.x + 2, this.bodyTop + 91, this.inner - 4, MUTED);
        if (run.timeline().isEmpty()) {
            line(g, "Keine Ereignisse aufgezeichnet.", this.x + 2, this.bodyTop + 104, this.inner - 4, MUTED);
            return;
        }
        int start = this.detailPage * this.detailPerPage;
        for (int i = start; i < Math.min(run.timeline().size(), start + this.detailPerPage); i++) {
            int ty = this.bodyTop + 102 + (i - start) * 13;
            fillRounded(g, this.x, ty, this.inner, 12, (i & 1) == 0 ? CANVAS : CARD_BG);
            String ev = run.timeline().get(i);
            line(g, ev, this.x + 6, ty + 2, this.inner - 12, MUTED);
            if (mouseY >= ty && mouseY < ty + 12 && mouseX >= this.x && mouseX < this.right && this.font.width(ev) > this.inner - 12) {
                g.setTooltipForNextFrame(Component.literal(ev), mouseX, mouseY);
            }
        }
        if (run.timeline().size() > this.detailPerPage) {
            line(g, "Seite " + (this.detailPage + 1) + " / " + ((run.timeline().size() + this.detailPerPage - 1) / this.detailPerPage),
                this.x + 28, this.bottom - 11, this.inner - 56, MUTED);
        }
    }

    // ==================== TAB 3: RUNDENLOG ====================

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
            this.logSelected < 0 ? Style.SELECTED : Style.NORMAL, () -> { this.logSelected = -1; this.logPage = 0; this.refresh(); });

        int numX = this.x + liveW + this.gap;
        int numStep = Math.min(31, (this.right - numX + 2) / 10);
        for (int i = 0; i < this.logRecent.size(); i++) {
            final int idx = i;
            DrillHistory.Result r = this.logRecent.get(i);
            this.action(numX + numStep * i, navY, numStep - 2, 18, Integer.toString(i + 1),
                this.logSelected == i ? Style.SELECTED : Style.NORMAL, () -> { this.logSelected = idx; this.logPage = 0; this.refresh(); })
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
            this.line(g, this.logLines.get(this.logPage * this.logLinesPerPage + i), this.x + 5, ty + 1, this.inner - 10, MUTED);
        }
        if (!this.logCommonIssue.isEmpty()) {
            this.line(g, this.logCommonIssue, this.x, this.bottom - 30, this.inner, AMBER);
        }
        if (this.logPageCount > 1) {
            this.line(g, "Ereignisse · " + (this.logPage + 1) + "/" + this.logPageCount, this.x + 36, this.bottom - 13, this.inner - 72, MUTED);
        }
    }

    // ==================== RENDER & INPUT ====================

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (firstFrame) { initialMouseX = mx; initialMouseY = my; firstFrame = false; }
        if (Math.abs(mx - initialMouseX) + Math.abs(my - initialMouseY) > 2) pointerMoved = true;
        mouseX = mx; mouseY = my;
        super.extractRenderState(g, mx, my, delta);

        if (viewTab == ViewTab.LOG && mx >= this.x && mx < this.right) {
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

        switch (this.viewTab) {
            case OVERVIEW -> drawOverview(g);
            case DRILLS -> drawDrills(g);
            case LOG -> drawLog(g);
        }
    }

    @Override public boolean mouseClicked(MouseButtonEvent ev, boolean twice) {
        if (ev.button() == 0) {
            if (viewTab == ViewTab.OVERVIEW) {
                for (TopDrillCard card : this.topDrillCards) {
                    if (ev.x() >= card.x && ev.x() < card.x + card.w && ev.y() >= card.y && ev.y() < card.y + card.h) {
                        this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                        this.drillMode = card.summary.mode();
                        this.selectedDrill = card.summary.drill();
                        this.viewTab = ViewTab.DRILLS;
                        this.showDetails = false;
                        this.refresh();
                        return true;
                    }
                }
            } else if (viewTab == ViewTab.DRILLS && !this.showDetails && !this.chartRuns.isEmpty() && overChart(ev.x(), ev.y())) {
                int base = this.report.scoredRuns().size() - this.chartRuns.size();
                this.detailFocus = base + hoverIndex(ev.x());
                this.showDetails = true;
                this.detailPage = 0;
                this.refresh();
                return true;
            }
        }
        return super.mouseClicked(ev, twice);
    }

    @Override public void tick() {
        if (viewTab == ViewTab.LOG) {
            TrainingStatus status = TrainerClient.status();
            if (this.logActive != status.active() || !this.logRecent.equals(status.recent())
                || (this.logSelected < 0 && !this.logLines.equals(status.timeline()))) {
                this.refresh();
            }
        }
    }

    // ==================== HELPERS ====================

    private int valueY(double val) {
        return this.plotBottom - (int)Math.round((val / Math.max(0.01, this.chartMaxY)) * (this.plotBottom - this.plotTop));
    }

    private int plotX(int i, int n) {
        return n == 1 ? (this.plotLeft + this.plotRight) / 2
            : this.plotLeft + (int)Math.round(i * (this.plotRight - this.plotLeft) / (double)(n - 1));
    }

    private boolean overChart(double mx, double my) {
        return mx >= this.plotLeft - 2 && mx <= this.plotRight + 2 && my >= this.plotTop - 2 && my <= this.plotBottom + 2;
    }

    private int hoverIndex(double mx) {
        return Math.clamp((int)Math.round((mx - this.plotLeft) * (this.chartRuns.size() - 1) / Math.max(1, this.plotRight - this.plotLeft)),
            0, this.chartRuns.size() - 1);
    }

    private void drawLineSegment(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x1 + .5f, y1 + .5f);
        pose.rotate((float)Math.atan2(y2 - y1, x2 - x1));
        g.fill(0, 0, (int)Math.ceil(Math.hypot(x2 - x1, y2 - y1)), 1, color);
        pose.popMatrix();
    }

    private static double niceCeiling(double maxVal, TrainingStats.ChartAxis axis) {
        if (maxVal <= 0.0) {
            return switch (axis) {
                case FALL_METERS -> 20.0;
                case WIND_METERS -> 10.0;
                case DUEL_IMPACT -> 10.0;
                case SECONDS -> 5.0;
            };
        }
        double padded = maxVal * 1.15;
        if (padded <= 2.0) return Math.ceil(padded * 2.0) / 2.0;
        if (padded <= 10.0) return Math.ceil(padded);
        if (padded <= 30.0) return Math.ceil(padded / 5.0) * 5.0;
        return Math.ceil(padded / 10.0) * 10.0;
    }

    private static String shortAxisLabel(double val, TrainingStats.ChartAxis axis) {
        return switch (axis) {
            case FALL_METERS, WIND_METERS -> String.format(Locale.GERMAN, "%.0f m", val);
            case DUEL_IMPACT -> String.format(Locale.GERMAN, "%.0f", val);
            case SECONDS -> val < 10.0 ? String.format(Locale.GERMAN, "%.1f s", val) : String.format(Locale.GERMAN, "%.0f s", val);
        };
    }

    private static String formatDate(TrainingProgress.Run r) {
        return r.time() == 0 ? "" : DATE.format(Instant.ofEpochMilli(r.time()));
    }

    private static String summarizeRunMetrics(RoundMetrics m) {
        if (m == null || !m.hasActivity()) return "";
        List<String> parts = new ArrayList<>();
        if (m.stunSlams() > 0) parts.add(m.stunSlams() + " Stun-Slam" + (m.stunSlams() == 1 ? "" : "s"));
        if (m.maceSmashes() > 0 && m.maxFallMeters() > 0.0) parts.add(TrainingStats.formatMeters(m.maxFallMeters()) + " Fall");
        if (m.maxWindMeters() > 0.0) parts.add(TrainingStats.formatMeters(m.maxWindMeters()) + " Höhe");
        if (m.botPops() > 0) parts.add(m.botPops() + (m.botPops() == 1 ? " Pop" : " Pops"));
        if (m.crystalsDetonated() > 0) parts.add(m.crystalsDetonated() + " Kristalle");
        if (m.anchorsDetonated() > 0) parts.add(m.anchorsDetonated() + " Anker");
        if (m.shieldStuns() > 0) parts.add(m.shieldStuns() + " Stun" + (m.shieldStuns() == 1 ? "" : "s"));
        if (m.websPlaced() > 0) parts.add(m.websPlaced() + " Webs");
        if (parts.isEmpty() && m.meleeHits() > 0) parts.add(m.meleeHits() + " Hits");
        return String.join(" · ", parts.subList(0, Math.min(3, parts.size())));
    }

    private static List<String> drillActionLines(RoundMetrics m, TrainingSettings.Mode mode) {
        if (m == null || !m.hasActivity()) return List.of();
        List<String> items = new ArrayList<>();
        if (m.stunSlams() > 0) items.add(m.stunSlams() + " Stun-Slams");
        if (m.maceSmashes() > 0) items.add(m.maceSmashes() + " Smashes (Ø " + TrainingStats.formatMeters(m.avgFallMeters()) + ")");
        if (m.botPops() > 0) items.add(m.botPops() + " Bot-Pops");
        int crystals = Math.max(m.crystalsDetonated(), m.crystalsPlaced());
        if (crystals > 0) items.add(crystals + " Kristalle" + (m.crystalsPerPop() > 0 ? " (" + TrainingStats.formatRatio(m.crystalsPerPop()) + "/Pop)" : ""));
        if (m.anchorsDetonated() > 0) items.add(m.anchorsDetonated() + " Anker (" + m.anchorPops() + " Pops)");
        if (m.windChargesUsed() > 0) items.add(m.windChargesUsed() + " Wind Charges");
        if (m.pearlCatches() > 0) items.add(m.pearlCatches() + " Pearl-Catches");
        if (m.shieldStuns() > 0) items.add(m.shieldStuns() + " Schild-Stuns");
        if (m.websPlaced() > 0) items.add(m.websPlaced() + " Cobwebs");
        if (m.lavaPlaced() > 0) items.add(m.lavaPlaced() + " Lava");
        if (m.waterDrains() > 0) items.add(m.waterDrains() + " Drains");
        if (m.healsEaten() > 0) items.add(m.healsEaten() + " Heals");
        if (m.obsidianPlaced() > 0) items.add(m.obsidianPlaced() + " Obsidian");
        if (m.meleeHits() > 0 && (mode == TrainingSettings.Mode.SWORD || items.size() < 2)) {
            items.add(m.meleeHits() + " Hits (" + TrainingStats.formatPercent(m.critRatePct()) + " Crits)");
        }
        if (items.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>();
        lines.add(String.join("  ·  ", items.subList(0, Math.min(2, items.size()))));
        if (items.size() > 2) {
            lines.add(String.join("  ·  ", items.subList(2, Math.min(4, items.size()))));
        }
        return lines;
    }

    private record KpiTile(String label, String value, String detail, int accent) {}
    private record TopDrillCard(TrainingStats.DrillSummary summary, int x, int y, int w, int h) {}
}

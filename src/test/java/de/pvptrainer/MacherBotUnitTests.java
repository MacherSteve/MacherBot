package de.pvptrainer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import de.pvptrainer.analytics.BotArchive;
import de.pvptrainer.analytics.BotStatistics;
import de.pvptrainer.analytics.BotTelemetry;
import de.pvptrainer.analytics.CalibrationPlan;
import de.pvptrainer.arena.ForgeArena;
import de.pvptrainer.arena.MacherAppearance;
import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillAttempt;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.drill.WindHeight;
import de.pvptrainer.kits.TrainingPreset;
import de.pvptrainer.kits.TrainingProfiles;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.progress.HudPreferences;
import de.pvptrainer.progress.RoundMetrics;
import de.pvptrainer.progress.RoundMetricsCollector;
import de.pvptrainer.progress.TrainingProgress;
import de.pvptrainer.progress.TrainingStats;
import de.pvptrainer.progress.UpdateChecker;
import de.pvptrainer.session.CobwebFlightTracker;
import de.pvptrainer.ui.KnowledgeGuide;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.phys.Vec3;

/** Schnelle Offline-Unit-Tests ohne Minecraft-Client (Aufruf via build.py --test). */
public final class MacherBotUnitTests {
    private static int passed;

    public static void main(String[] args) throws Exception {
        testStreakAndCobwebAirChain();
        testRunDefaultsAndScoring();
        testCalibrationPlanInstallAndUpdate();
        testBotArchiveDefensiveAndSetup();
        testBotTelemetryAndStatistics();
        testWindHeightHelpers();
        testDrillsAndTrainingProfilesRoundTrip();
        testCobwebFlightTrackerLogic();
        testTotemDefenseStates();
        testSlamAndDoubleClickWindows();
        testUpdateCheckerParsingAndSecurity();
        testUpdateCheckerLocalHttpRoundTrip();
        testHugoSmpKitJsonSchema();
        testNewDrillsAndOptions();
        testLargeArenaGeometry();
        testStunSlamAndElyStunSlamWindBurst();
        testNewDrillAttemptStateMachines();
        testVanillaPlayerDamageAndCrystalParity();
        testBatchFeaturesAndCrystalHotfixes();
        testSwightDrillsAndKnowledgeGuide();
        testUiRedesignStickyModifiersAndIcons();
        testRealPvpMetricsAndStatsAggregation();
        System.out.println("PASS " + passed + " Unit-Tests erfolgreich.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Test fehlgeschlagen: " + message);
    }

    private static void run(String name, CheckedRunnable body) throws Exception {
        body.run();
        passed++;
        System.out.println("  [OK] " + name);
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static void testStreakAndCobwebAirChain() throws Exception {
        run("TrainingProgress.Streak: Siege, Rekord, Verlust und Cobweb-Flugserie", () -> {
            var s = TrainingProgress.Streak.EMPTY;
            // Erster Sieg: current=1, best=1, previousBest=0 -> noch nicht burning (1 > max(1,0) == false)
            s = s.next(new TrainingProgress.Run("k", "SWORD", "SPACING", 100L, true, 20, "", List.of(), "NORMAL", 20));
            check(s.current() == 1 && s.best() == 1 && !s.burning(), "erster Sieg");

            // Zweiter Sieg: current=2 -> burning & recordHit
            s = s.next(new TrainingProgress.Run("k", "SWORD", "SPACING", 200L, true, 18, "", List.of(), "NORMAL", 20));
            check(s.current() == 2 && s.best() == 2 && s.burning() && s.recordHit(), "Rekord bei 2");

            // Normaler Verlust setzt current auf 0, behaelt best=2
            s = s.next(new TrainingProgress.Run("k", "SWORD", "SPACING", 300L, false, 15, "miss", List.of(), "NORMAL", 20));
            check(s.current() == 0 && s.best() == 2 && s.previousBest() == 2, "Verlust setzt current zurueck");

            // Cobweb-Smash: neuer Flug startet bei 1, zweiter Treffer im selben Flug zaehlt auf 2, Verlust auf Primaer-Setup loescht Serie nicht
            var web = TrainingProgress.Streak.EMPTY;
            web = web.next(new TrainingProgress.Run("web", "MACE", "LOW_WEB_SMASH", 1L, true, 10, "", List.of(), "NORMAL", 20, true));
            web = web.next(new TrainingProgress.Run("web", "MACE", "LOW_WEB_SMASH", 2L, true, 12, "", List.of(), "NORMAL", 20, false));
            check(web.current() == 2 && web.best() == 2, "zwei Cobweb-Smashes im selben Flug");
            var afterMiss = web.next(new TrainingProgress.Run("web", "MACE", "LOW_WEB_SMASH", 3L, false, 15, "miss", List.of(), "NORMAL", 20, false));
            check(afterMiss.current() == 2 && afterMiss.best() == 2, "Cobweb-Fehlschlag auf Primaer-Setup bleibt neutral");
            var newFlight = afterMiss.next(new TrainingProgress.Run("web", "MACE", "LOW_WEB_SMASH", 4L, true, 9, "", List.of(), "NORMAL", 20, true));
            check(newFlight.current() == 1 && newFlight.best() == 2 && newFlight.previousBest() == 2, "neuer Flug startet bei 1x und behaelt Rekord");
        });
    }

    private static void testRunDefaultsAndScoring() throws Exception {
        run("TrainingProgress.Run: Tickrate-Grenzen und Score-Berechnung", () -> {
            var clampedLow = new TrainingProgress.Run("s", "UHC", "OBI_TRAP", 1L, true, 40, null, null, null, 0);
            check(clampedLow.tickRate() == 20, "tickRate 0 -> 20");
            check(Math.abs(clampedLow.seconds() - 2.0) < 1e-9, "40 Ticks bei 20 TPS = 2.0 s");
            check(clampedLow.score() > 1000, "gewonnener Run hat Score > 1000");
            check("".equals(clampedLow.reason()) && clampedLow.timeline().isEmpty(), "Null-Felder normalisiert");

            var clampedHigh = new TrainingProgress.Run("s", "UHC", "OBI_TRAP", 1L, false, 20, "fail", List.of(), "HARD", 99);
            check(clampedHigh.tickRate() == 20 && clampedHigh.score() == 0, "Verlust hat Score 0 und tickRate 99 -> 20");
        });
    }

    private static void testCalibrationPlanInstallAndUpdate() throws Exception {
        run("CalibrationPlan: Erstinstallation, Update bei neuer Version und Idempotenz", () -> {
            Path tempGameDir = Files.createTempDirectory("macher-cal-test-");
            try {
                byte[] v1 = "<html>Testplan v1</html>".getBytes(StandardCharsets.UTF_8);
                byte[] v2 = "<html>Testplan v2 (aktualisiert)</html>".getBytes(StandardCharsets.UTF_8);

                Path installed = CalibrationPlan.installBytes(tempGameDir, CalibrationPlan.FILENAME, v1);
                check(Files.isRegularFile(installed), "Datei angelegt");
                check(Files.readString(installed, StandardCharsets.UTF_8).equals("<html>Testplan v1</html>"), "Inhalt v1");

                // Idempotenz: gleicher Inhalt fasst mtime nicht unnoetig an
                FileTime oldTime = FileTime.from(Instant.parse("2025-01-01T00:00:00Z"));
                Files.setLastModifiedTime(installed, oldTime);
                CalibrationPlan.installBytes(tempGameDir, CalibrationPlan.FILENAME, v1);
                check(Files.getLastModifiedTime(installed).equals(oldTime), "identischer Inhalt wird nicht unnoetig neu geschrieben");

                // Update-Bug-Fix: neuer Inhalt ueberschreibt veraltete Datei atomar
                CalibrationPlan.installBytes(tempGameDir, CalibrationPlan.FILENAME, v2);
                check(Files.readString(installed, StandardCharsets.UTF_8).equals("<html>Testplan v2 (aktualisiert)</html>"),
                    "veralteter Testplan wird aktualisiert");
            } finally {
                deleteRecursive(tempGameDir);
            }
        });
    }

    private static void testBotArchiveDefensiveAndSetup() throws Exception {
        run("BotArchive: defensive Versionsaufloesung und Setup-Grenzen", () -> {
            String ver = BotArchive.resolveBuildVersion();
            check(ver != null && !ver.isBlank(), "resolveBuildVersion crasht ausserhalb Fabric nicht");
            var s0 = new BotArchive.Setup("hash", "SWORD", "DUEL", "HT1", "1.11.9", 0);
            var s99 = new BotArchive.Setup("hash", "SWORD", "DUEL", "HT1", "1.11.9", 99);
            var s10 = new BotArchive.Setup("hash", "SWORD", "DUEL", "HT1", "1.11.9", 10);
            check(s0.tickRate() == 20 && s99.tickRate() == 20 && s10.tickRate() == 10, "Setup tickRate clamping");
        });
    }

    private static void testBotTelemetryAndStatistics() throws Exception {
        run("BotTelemetry & BotStatistics: Fenster, Latenz, Muster und Truncation", () -> {
            BotTelemetry t = new BotTelemetry(100);
            t.observe(105, true);
            t.recordAction(105, "MELEE");
            t.recordDamage(108, true); // Delay = 3 Ticks
            t.observe(110, false);     // Fenster [5..8], hit=8
            t.observe(115, true);
            t.recordAction(116, "CRYSTAL");
            t.observe(120, false);     // Verpasstes Fenster [15..15], hit=-1
            t.observe(125, true);      // Zensiertes (noch offenes) Fenster
            var snap = t.snapshot(130);

            check(snap.ticks() == 30, "relative Rundendauer");
            check(snap.windows().size() == 3, "3 Fenster (Treffer, Miss, Zensiert)");
            check(snap.windows().get(0).hit() == 8 && snap.windows().get(0).start() == 5, "Trefferfenster");
            check(snap.windows().get(1).hit() == -1 && snap.windows().get(1).end() >= 15, "verpasstes Fenster");
            check(snap.windows().get(2).hit() == -1 && snap.windows().get(2).end() == -1, "zensiertes Fenster");

            var setup = new BotArchive.Setup("setup1", "CRYSTAL", "DUEL", "HT1", "1.11.9", 20);
            var round1 = new BotArchive.Round(setup, 1000L, "WIN", snap);
            var roundTruncated = new BotArchive.Round(setup, 2000L, "UNSCORED",
                new BotTelemetry.Snapshot(50, List.of(), List.of(), true));

            BotStatistics stats = new BotStatistics(List.of(round1, roundTruncated));
            check(stats.rounds == 1 && stats.partial == 1 && stats.neutral == 1, "Zaehlung normal/gekuerzt/neutral");
            check(stats.hits() == 1 && stats.missed == 1 && stats.censored == 1 && stats.windows() == 3, "Fensterstatistik");
            check(Math.abs(stats.median() - 3.0) < 1e-9, "Latenz-Median 3 Ticks");
            check("MELEE".equals(stats.opening().to()), "Opening MELEE");
            check(stats.patterns().size() == 1 && "CRYSTAL".equals(stats.patterns().getFirst().to()), "Uebergang MELEE -> CRYSTAL");
        });
    }

    private static void testWindHeightHelpers() throws Exception {
        run("WindHeight: Schwellenfarben und deutsches Zahlenformat", () -> {
            check(WindHeight.color(12.0) == 0xff58e776, "Perfekt >= 12m gruen");
            check(WindHeight.color(9.0) == 0xffffa238, "Nah >= 9m bernstein");
            check(WindHeight.color(8.99) == 0xffff5360, "< 9m rot");
            check("12,50 m".equals(WindHeight.text(12.5)), "deutsches Format 12,50 m");
        });
    }

    private static void testDrillsAndTrainingProfilesRoundTrip() throws Exception {
        run("Drill, DrillOptions & TrainingProfiles: Moduszuordnung und JSON-Persistenz", () -> {
            for (Drill d : Drill.values()) {
                check(d.label() != null && !d.label().isBlank(), "Label fehlt fuer " + d);
                check(d.hint() != null && !d.hint().isBlank(), "Hint fehlt fuer " + d);
            }
            for (TrainingSettings.Mode m : TrainingSettings.Mode.values()) {
                DrillOptions defaults = DrillOptions.defaults(m);
                check(defaults.drill().supports(m), "Default-Drill passt zu Modus " + m);
            }
            Path tempDir = Files.createTempDirectory("macher-profiles-test-");
            try {
                Path file = tempDir.resolve("pvptrainer.json");
                TrainingProfiles profiles = new TrainingProfiles(file);
                TrainingSettings mace = TrainingSettings.defaults(TrainingSettings.Mode.MACE).withMaceCenterLocked(true);
                DrillOptions web = DrillOptions.forDrill(Drill.LOW_WEB_SMASH);
                profiles.saveSetup(mace, web);
                check(Files.isRegularFile(file), "pvptrainer.json geschrieben");

                TrainingProfiles reloaded = new TrainingProfiles(file);
                check(reloaded.selected().mode() == TrainingSettings.Mode.MACE, "ausgewaehlter Modus erhalten");
                check(reloaded.selected().maceCenterLocked(), "maceCenterLocked erhalten");
                check(reloaded.options(TrainingSettings.Mode.MACE).drill() == Drill.LOW_WEB_SMASH, "Drill erhalten");

                // Gegenprobe: defekte Datei darf nicht zum Absturz fuehren
                Files.writeString(file, "{kaputtes json", StandardCharsets.UTF_8);
                TrainingProfiles fallback = new TrainingProfiles(file);
                check(fallback.selected() != null, "Fallback bei kaputter JSON-Datei");
            } finally {
                deleteRecursive(tempDir);
            }
        });
    }

    private static void testCobwebFlightTrackerLogic() throws Exception {
        run("CobwebFlightTracker: Settle-Ticks nach Ping/Tickrate und Flugstatus", () -> {
            check(CobwebFlightTracker.settleTicks(0, 20) == 3, "0 ms Ping -> 3 Ticks");
            check(CobwebFlightTracker.settleTicks(50, 20) == 4, "50 ms Ping @ 20 TPS -> 4 Ticks");
            check(CobwebFlightTracker.settleTicks(120, 20) == 6, "120 ms Ping @ 20 TPS -> 6 Ticks");

            var tracker = new CobwebFlightTracker();
            check(!tracker.isChainActive() && !tracker.isFlightJudged(), "Initialzustand sauber");
            tracker.onScored(true, 10);
            check(tracker.isChainActive() && tracker.isFlightJudged(), "Gewonnener Smash aktiviert Flugkette");
            check(!tracker.shouldRecordMissOnLanding(false), "Bereits gewerteter Flug darf keinen Miss ausloesen");
        });
    }

    private static void testTotemDefenseStates() throws Exception {
        run("DrillAttempt/TotemDefenseTracker: RETOTEM und DOUBLE_HAND Positiv- und Gegenfall", () -> {
            // RETOTEM: 2 Totems nachlegen bis 0 Rest -> Sieg
            var retotem = new DrillAttempt(DrillOptions.forDrill(Drill.RETOTEM), -1.0, 0);
            retotem.startRefill(2);
            check("0/2 Totems verbraucht".equals(retotem.refillProgress()), "Startanzeige 0/2");
            retotem.playerPop(1);
            check(!retotem.done() && retotem.playerPops() == 1, "Nach erstem Pop weiter offen");
            retotem.playerPop(0);
            check(retotem.done() && retotem.won() && retotem.playerPops() == 2, "Nach letztem Pop gewonnen");

            // DOUBLE_HAND Positivfall: 2 Totems beim ersten Pop, 1 Totem beim Folgekristall -> Sieg
            var dhWin = new DrillAttempt(DrillOptions.forDrill(Drill.DOUBLE_HAND), -1.0, 0);
            dhWin.defenseBlast(true, true, true, 12.0F, true, true);
            check(!dhWin.done(), "Erster Pop mit zwei Haenden oeffnet Folgekristall");
            dhWin.defenseBlast(false, true, false, 8.0F, true, true);
            check(dhWin.done() && dhWin.won(), "Folgekristall mit verbliebenem Totem gewonnen");

            // DOUBLE_HAND Gegenprobe: fehlendes Totem in einer Hand beim ersten Pop -> sofortiger Fehlschlag
            var dhFail = new DrillAttempt(DrillOptions.forDrill(Drill.DOUBLE_HAND), -1.0, 0);
            dhFail.defenseBlast(false, true, true, 12.0F, true, true);
            check(dhFail.done() && !dhFail.won(), "Fehlendes zweites Totem beim ersten Pop muss scheitern");
        });
    }

    private static void testSlamAndDoubleClickWindows() throws Exception {
        run("DrillAttempt/SlamWindowTracker: STUN_WEB-Doppelklick und STUN_SLAM-Diagnose", () -> {
            // STUN_WEB: Einzelklick reicht nicht, erst 2. aufeinanderfolgender Klick mit Axt loest Stun aus
            var stunWeb = new DrillAttempt(DrillOptions.forDrill(Drill.STUN_WEB), -1.0, 0);
            check(stunWeb.doubleClickDisable(1L, 0.0F) == 0.0F, "Erster Klick (Schwert) darf nicht stunnen");
            check(stunWeb.doubleClickDisable(1L, 5.0F) == 0.0F, "Identische Sequenznummer darf nicht doppelt zaehlen");
            check(stunWeb.doubleClickDisable(2L, 5.0F) == 5.0F, "Direkter Folgeklick mit Axt muss Stun ausloesen");
            check(stunWeb.doubleClickDisable(3L, 5.0F) == 0.0F, "Nach ausgeloestem Doppelklick wieder neu ansetzen");

            // STUN_SLAM: Mace vor Axt -> Diagnose, nach Axt-Stun -> Schildfenster offen
            var slam = new DrillAttempt(DrillOptions.forDrill(Drill.STUN_SLAM), -1.0, 0);
            check(!slam.shieldWindowOpen(), "Schildfenster vor Stun geschlossen");
            slam.maceContact(4.0, false, false);
            check(slam.timingHint() != null && slam.timingHint().contains("vor dem Axt-Stun"), "Diagnose bei verfruehter Mace");
            slam.shieldStun(25);
            check(slam.shieldWindowOpen(), "Schildfenster nach Axt-Stun offen");
            check(slam.timingHint() == null, "Axt-Stun loescht vorherigen Timing-Hinweis");
        });
    }

    private static void testUpdateCheckerParsingAndSecurity() throws Exception {
        run("UpdateChecker: SemVer, GitHub/latest.json/Modrinth-Formate und URI-Schutz", () -> {
            check(UpdateChecker.isNewer("1.12.1", "1.12.0"), "Patch-Bump muss neuer sein");
            check(UpdateChecker.isNewer("1.12.10", "1.12.2"), "Numerischer Vergleich 10 > 2");
            check(UpdateChecker.isNewer("v1.13.0+26.2", "1.12.0"), "v-Praefix und +mc-Suffix");
            check(UpdateChecker.isNewer("1.12.1", "1.12.1-beta.1"), "Release neuer als Beta");
            check(!UpdateChecker.isNewer("1.12.0", "1.12.0"), "Gleiche Version nicht neuer");
            check(!UpdateChecker.isNewer("1.11.9", "1.12.0"), "Aeltere Version nicht neuer");
            check(!UpdateChecker.isNewer("1.13.0", "unknown"), "Unbekannte lokale Version loest keinen Alarm aus");

            // GitHub Releases API
            String ghJson = """
                {"tag_name":"v1.13.0","name":"MacherBot 1.13.0","html_url":"https://github.com/MacherSteve/MacherBot/releases/tag/v1.13.0",
                 "body":"## Änderungen\\n- Neuer Update-Hinweis im Hauptmenü\\n- Zweite Zeile","draft":false,"prerelease":false}
                """;
            var gh = UpdateChecker.parseResponse(ghJson, "1.12.0", false);
            check(gh != null && "1.13.0".equals(gh.latestVersion()), "GitHub-Release erkannt");
            check("Neuer Update-Hinweis im Hauptmenü".equals(gh.summary()), "Erste Patchnote-Zeile extrahiert: " + gh.summary());

            // Draft / Prerelease ignorieren
            String ghPre = """
                {"tag_name":"v1.14.0","html_url":"https://github.com/MacherSteve/MacherBot/releases/tag/v1.14.0","draft":false,"prerelease":true}
                """;
            check(UpdateChecker.parseResponse(ghPre, "1.12.0", false) == null, "Prerelease ignorieren");

            // Einfaches latest.json + Minecraft-Versionsfilter
            String simpleOk = """
                {"version":"1.12.5","minecraft":"26.2","url":"https://example.com/macherbot","notes":"Stabilitaetsupdate"}
                """;
            var simple = UpdateChecker.parseResponse(simpleOk, "1.12.0", false);
            check(simple != null && "1.12.5".equals(simple.latestVersion()) && "Stabilitaetsupdate".equals(simple.summary()), "latest.json erkannt");
            String simpleWrongMc = """
                {"version":"2.0.0","minecraft":"27.0","url":"https://example.com/macherbot"}
                """;
            check(UpdateChecker.parseResponse(simpleWrongMc, "1.12.0", false) == null, "Falsche MC-Version ignorieren");

            // Modrinth Versions API Array
            String modrinth = """
                [
                  {"id":"b1","project_id":"macherbot","version_number":"1.14.0-beta.1","version_type":"beta","game_versions":["26.2"]},
                  {"id":"ver42","project_id":"macherbot","version_number":"1.13.1+26.2","version_type":"release","game_versions":["26.2"],"changelog":"- Modrinth Release"}
                ]
                """;
            var mr = UpdateChecker.parseResponse(modrinth, "1.12.0", false);
            check(mr != null && "1.13.1".equals(mr.latestVersion()), "Modrinth-Release erkannt");
            check("https://modrinth.com/mod/macherbot/version/ver42".equals(mr.downloadUri().toString()), "Modrinth-URL gebaut");

            // Sicherheitspruefung gegen unsichere URI-Schemas
            check(UpdateChecker.parseSafeUri("file:///C:/Windows/System32/cmd.exe", false) == null, "file:// blockiert");
            check(UpdateChecker.parseSafeUri("javascript:alert(1)", false) == null, "javascript: blockiert");
            check(UpdateChecker.parseSafeUri("http://example.com/dl.jar", false) == null, "Remote http:// blockiert");
            check(UpdateChecker.parseSafeUri("http://127.0.0.1:8080/latest", true) != null, "Lokaler Test-Loopback erlaubt");
            String evilUrl = """
                {"tag_name":"v9.9.9","html_url":"file:///C:/Windows/cmd.exe","draft":false,"prerelease":false}
                """;
            check(UpdateChecker.parseResponse(evilUrl, "1.12.0", false) == null, "Unsichere html_url muss verworfen werden");
        });
    }

    private static void testUpdateCheckerLocalHttpRoundTrip() throws Exception {
        run("UpdateChecker: Lokaler HTTP-Roundtrip (200 OK und 404)", () -> {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                byte[] payload = """
                    {"tag_name":"v1.13.0","html_url":"https://github.com/MacherSteve/MacherBot/releases/tag/v1.13.0","body":"- Live HTTP Test","draft":false,"prerelease":false}
                    """.getBytes(StandardCharsets.UTF_8);
                server.createContext("/releases/latest", ex -> {
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, payload.length);
                    try (var out = ex.getResponseBody()) {
                        out.write(payload);
                    }
                });
                server.createContext("/missing", ex -> {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                });
                server.start();

                int port = server.getAddress().getPort();
                var ok = UpdateChecker.fetchFrom(URI.create("http://127.0.0.1:" + port + "/releases/latest"), "1.12.0", true);
                check(ok != null && "1.13.0".equals(ok.latestVersion()) && "Live HTTP Test".equals(ok.summary()), "HTTP 200 Roundtrip erfolgreich");

                var missing = UpdateChecker.fetchFrom(URI.create("http://127.0.0.1:" + port + "/missing"), "1.12.0", true);
                check(missing == null, "HTTP 404 liefert null ohne Fehler");
            } finally {
                server.stop(0);
            }
        });
    }

    private static void testHugoSmpKitJsonSchema() throws Exception {
        run("kits.json: HugoSMP Standardkit vollstaendig und exakt", () -> {
            var stream = MacherBotUnitTests.class.getResourceAsStream("/assets/pvptrainer/kits.json");
            check(stream != null, "kits.json nicht im Klassenpfad gefunden");
            JsonObject root;
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            check(root.has("hugosmp"), "hugosmp Kit fehlt in kits.json");
            JsonObject hugo = root.getAsJsonObject("hugosmp");

            // Ausgeruestete Netherite-Ruestung: alle 4 Teile mit Protection 4
            for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
                JsonObject armor = hugo.getAsJsonObject(piece);
                check(armor != null && armor.get("material").getAsString().equals("netherite_" + piece), "Ruestungsteil " + piece);
                check(hasEnchant(armor, "protection", 4), piece + " braucht Protection 4");
                check(hasEnchant(armor, "unbreaking", 3) && hasEnchant(armor, "mending", 1), piece + " braucht Unb 3 + Mend 1");
            }
            check("totem_of_undying".equals(hugo.getAsJsonObject("offhand").get("material").getAsString()), "Offhand Totem");

            JsonArray inv = hugo.getAsJsonArray("inventory");
            check(inv.size() == 36, "Inventar muss exakt 36 Slots haben: " + inv.size());

            // Hotbar (0..8)
            checkSlot(inv, 0, "shield", 1);
            checkSlot(inv, 1, "mace", 1);
            JsonObject mace = inv.get(1).getAsJsonObject();
            check(hasEnchant(mace, "density", 5) && hasEnchant(mace, "wind_burst", 2), "Mace braucht Density 5 + Wind Burst 2");
            checkSlot(inv, 2, "golden_apple", 64);
            checkSlot(inv, 3, "wind_charge", 64);
            checkSlot(inv, 4, "obsidian", 64);
            checkSlot(inv, 5, "end_crystal", 64);
            checkSlot(inv, 6, "totem_of_undying", 1);
            checkSlot(inv, 7, "netherite_axe", 1);
            checkSlot(inv, 8, "ender_pearl", 16);

            // Inventarzeilen (9..35)
            checkSlot(inv, 9, "experience_bottle", 64);
            checkSlot(inv, 10, "tnt", 64);
            checkSlot(inv, 16, "firework_rocket", 64);
            checkSlot(inv, 17, "splash_potion", 1);
            check("swiftness".equals(inv.get(17).getAsJsonObject().get("potion").getAsString()), "Slot 17 Swiftness I");
            checkSlot(inv, 18, "experience_bottle", 64);
            checkSlot(inv, 19, "tnt", 64);
            checkSlot(inv, 25, "netherite_pickaxe", 1);
            checkSlot(inv, 26, "ender_pearl", 16);
            checkSlot(inv, 27, "water_bucket", 1);
            checkSlot(inv, 28, "netherite_sword", 1);
            checkSlot(inv, 29, "enchanted_golden_apple", 16);
            checkSlot(inv, 30, "netherite_shovel", 1);
            checkSlot(inv, 31, "obsidian", 64);
            checkSlot(inv, 32, "netherite_leggings", 1);
            check(hasEnchant(inv.get(32).getAsJsonObject(), "blast_protection", 4), "Ersatzhose braucht Blast Protection 4");
            checkSlot(inv, 33, "netherite_boots", 1);
            checkSlot(inv, 34, "cobweb", 64);
            checkSlot(inv, 35, "ender_pearl", 16);

            int totemCount = 0;
            for (int i = 0; i < 36; i++) {
                if ("totem_of_undying".equals(inv.get(i).getAsJsonObject().get("material").getAsString())) totemCount++;
            }
            check(totemCount == 11, "Erwartet 11 Inventar-Totems (+ 1 Offhand), gefunden: " + totemCount);
        });
    }

    private static void checkSlot(JsonArray inv, int slot, String material, int amount) {
        JsonObject item = inv.get(slot).getAsJsonObject();
        check(material.equals(item.get("material").getAsString()) && item.get("amount").getAsInt() == amount,
            "Slot " + slot + " erwartet " + amount + "x " + material + ", war " + item);
    }

    private static boolean hasEnchant(JsonObject item, String id, int level) {
        if (!item.has("enchants")) return false;
        for (var el : item.getAsJsonArray("enchants")) {
            JsonObject e = el.getAsJsonObject();
            if (id.equals(e.get("id").getAsString()) && e.get("level").getAsInt() == level) return true;
        }
        return false;
    }

    private static void testNewDrillsAndOptions() throws Exception {
        run("Drills, DrillOptions & Presets: HugoSMP, Crystal, Mace, UHC, Sword, Arena-Groesse & D-Tap", () -> {
            // Neue Modi & Moduszuordnung
            check(TrainingSettings.Mode.HUGOSMP.allowsBotTotems(), "HugoSMP erlaubt Bot-Totems");
            check(Drill.MACE_COUNTER_DTAP.supports(TrainingSettings.Mode.HUGOSMP), "MACE_COUNTER_DTAP in HugoSMP");
            check(!Drill.MACE_COUNTER_DTAP.supports(TrainingSettings.Mode.CRYSTAL), "MACE_COUNTER_DTAP nicht in Crystal");
            check(Drill.CORNER_WEB_DTAP.supports(TrainingSettings.Mode.HUGOSMP), "CORNER_WEB_DTAP in HugoSMP");
            check(Drill.CORNER_WEB_DTAP.needsCornerWalls(), "CORNER_WEB_DTAP braucht Eckenwaende");
            check(Drill.CORNER_OBI_TRAP.supports(TrainingSettings.Mode.UHC) && Drill.CORNER_OBI_TRAP.isObiTrap() && Drill.CORNER_OBI_TRAP.needsCornerWalls(),
                "CORNER_OBI_TRAP in UHC mit Eckenwaenden");
            check(Drill.SHIELD_DRAIN.supports(TrainingSettings.Mode.MACE), "SHIELD_DRAIN in Mace aktiviert");
            check(Drill.DOUBLE_ANCHOR.supports(TrainingSettings.Mode.CRYSTAL), "DOUBLE_ANCHOR in Crystal");
            check(Drill.SHIELD_TRIPLE_TAP.supports(TrainingSettings.Mode.CRYSTAL), "SHIELD_TRIPLE_TAP in Crystal");

            // D-Tap entfernt die 3 Preset-Optionen, andere Drills behalten sie
            check(Drill.D_TAP.hidesPresets(), "D_TAP blendet Preset-Leiste aus");
            check(!Drill.TRIPLE_TAP.hidesPresets() && !Drill.SHIELD_TRIPLE_TAP.hidesPresets(), "Triple Tap zeigt Presets");

            // Jump Reset & Hit Select: groesserer Startabstand (>= 7 Bloecke)
            check(DrillOptions.forDrill(Drill.JUMP_RESET).startDistance() >= 7, "JUMP_RESET startet bei >= 7 Bl.");
            check(DrillOptions.forDrill(Drill.HIT_SELECT).startDistance() >= 7, "HIT_SELECT startet bei >= 7 Bl.");
            for (TrainingPreset preset : TrainingPreset.values()) {
                var sw = TrainingSettings.defaults(TrainingSettings.Mode.SWORD);
                check(preset.setup(sw, Drill.JUMP_RESET).options().startDistance() >= 7, "JUMP_RESET Preset " + preset + " >= 7 Bl.");
                check(preset.setup(sw, Drill.HIT_SELECT).options().startDistance() >= 7, "HIT_SELECT Preset " + preset + " >= 7 Bl.");
            }

            // STUN_WEB: BOT_JUMP Option nur bei STUN_WEB sichtbar
            var stunWeb = DrillOptions.forDrill(Drill.STUN_WEB);
            check(DrillOptions.Field.BOT_JUMP.visible(stunWeb, TrainingSettings.Mode.UHC), "BOT_JUMP bei STUN_WEB sichtbar");
            check(!stunWeb.botJump(), "BOT_JUMP standardmaessig Aus");
            check(stunWeb.withIndex(DrillOptions.Field.BOT_JUMP, 1).botJump(), "BOT_JUMP auf Ein umstellbar");
            check(!DrillOptions.Field.BOT_JUMP.visible(DrillOptions.forDrill(Drill.OBI_TRAP), TrainingSettings.Mode.UHC),
                "BOT_JUMP bei OBI_TRAP ausgeblendet");

            // Arena-Groesse Option ueberall verfuegbar und in TrainingProfiles persistierbar
            var opts = DrillOptions.forDrill(Drill.DUEL);
            check(!opts.largeArena(), "Arena standardmaessig Standard");
            var largeOpts = opts.withIndex(DrillOptions.Field.ARENA_SIZE, 1);
            check(largeOpts.largeArena(), "Arena auf Gross umstellbar");
            Path tempDir = Files.createTempDirectory("macher-large-arena-profile-");
            try {
                Path file = tempDir.resolve("pvptrainer.json");
                var profiles = new TrainingProfiles(file);
                profiles.saveSetup(TrainingSettings.defaults(TrainingSettings.Mode.HUGOSMP), largeOpts);
                var reloaded = new TrainingProfiles(file);
                check(reloaded.selected().mode() == TrainingSettings.Mode.HUGOSMP, "HugoSMP-Modus gespeichert");
                check(reloaded.options(TrainingSettings.Mode.HUGOSMP).largeArena(), "ARENA_SIZE=Gross persistiert");
            } finally {
                deleteRecursive(tempDir);
            }

            // Standard-HP fuer DOUBLE_ANCHOR, CORNER_WEB_DTAP und MACE_COUNTER_DTAP (damit 1. Pop sicher ausloest)
            check(TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).forDrill(Drill.DOUBLE_ANCHOR).maxHealth() == 10,
                "DOUBLE_ANCHOR startet mit 10 Bot-LP");
            check(TrainingSettings.defaults(TrainingSettings.Mode.HUGOSMP).forDrill(Drill.CORNER_WEB_DTAP).maxHealth() == 10,
                "CORNER_WEB_DTAP startet mit 10 Bot-LP");
            check(TrainingSettings.defaults(TrainingSettings.Mode.HUGOSMP).forDrill(Drill.MACE_COUNTER_DTAP).maxHealth() == 10,
                "MACE_COUNTER_DTAP startet mit 10 Bot-LP");
        });
    }

    private static void testLargeArenaGeometry() throws Exception {
        run("ForgeArena: Grosse Arena mit 80 Bloecken Tiefe, flacher Mitte und sanften Huegeln", () -> {
            check(ForgeArena.largeDepthBlocks() == 80, "Tiefe muss exakt 80 Bloecke sein");
            check(ForgeArena.minFloor(false) == 60, "Standard-Arena minFloor=60");
            check(ForgeArena.minFloor(true) == ForgeArena.FLOOR - 81, "Grosse Arena minFloor=-17 (80 grabbar + Bedrock)");
            check(ForgeArena.largeRadius() == 56, "Grosser Radius 56");

            int maxHill = 0;
            int nonZeroCount = 0;
            int r = ForgeArena.largeRadius();
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    int h = ForgeArena.largeHillOffset(x, z);
                    check(h >= 0 && h <= 4, "Huegelhoehe ausserhalb 0..4 bei " + x + "," + z + ": " + h);
                    if (Math.hypot(x, z) <= 10.5) {
                        check(h == 0, "Zentrales Spawn-Plateau muss flach sein bei " + x + "," + z + ": " + h);
                    }
                    if (h > maxHill) maxHill = h;
                    if (h > 0) nonZeroCount++;
                    if (x < r) {
                        int dx = Math.abs(h - ForgeArena.largeHillOffset(x + 1, z));
                        check(dx <= 1, "Huegelstufe X > 1 Block bei " + x + "," + z + ": " + dx);
                    }
                    if (z < r) {
                        int dz = Math.abs(h - ForgeArena.largeHillOffset(x, z + 1));
                        check(dz <= 1, "Huegelstufe Z > 1 Block bei " + x + "," + z + ": " + dz);
                    }
                }
            }
            check(maxHill >= 2 && nonZeroCount > 500, "Grosse Arena braucht spuerbare Huegel: max=" + maxHill + " count=" + nonZeroCount);
        });
    }

    private static void testStunSlamAndElyStunSlamWindBurst() throws Exception {
        run("DrillAttempt: STUN_SLAM & ELY_STUN_SLAM fordern vollen Smash + Wind-Burst-Auftrieb", () -> {
            // Gegenfall 1: Nur Schildbruch zaehlt NICHT als Sieg
            var shieldOnly = new DrillAttempt(DrillOptions.forDrill(Drill.STUN_SLAM), -1.0, 0);
            shieldOnly.observeFall(10, 18.0);
            shieldOnly.shieldStun(105);
            check(!shieldOnly.done() && !shieldOnly.won(), "Nur Schildbruch darf STUN_SLAM niemals gewinnen");

            // Gegenfall 2: Bodentap (fallDistance = 0.0) nach Axt-Stun zaehlt NICHT
            var groundTap = new DrillAttempt(DrillOptions.forDrill(Drill.STUN_SLAM), -1.0, 0);
            groundTap.observeFall(10, 0.0);
            groundTap.shieldStun(105);
            check(!groundTap.beginStunSlamLaunch(0.0, 65.0, true, false, true), "Bodentap ohne Smash-Fallstrecke darf nicht zaehlen");
            check(!groundTap.done() && !groundTap.won(), "Bodentap darf STUN_SLAM nicht gewinnen");

            // Gegenfall 3: Voller Smash OHNE Wind Burst zaehlt NICHT
            var noWindBurst = new DrillAttempt(DrillOptions.forDrill(Drill.STUN_SLAM), -1.0, 0);
            noWindBurst.observeFall(10, 18.0);
            noWindBurst.shieldStun(105);
            check(!noWindBurst.beginStunSlamLaunch(18.0, 66.0, false, false, false), "Mace ohne Wind Burst darf nicht starten");
            check(!noWindBurst.done() && noWindBurst.timingHint() != null && noWindBurst.timingHint().contains("Wind Burst"),
                "Hinweis auf fehlenden Wind Burst erwartet");

            // Gegenfall 4: Wind-Burst-Smash ohne tatsaechlichen Auftrieb in die Luft
            var noLift = new DrillAttempt(DrillOptions.forDrill(Drill.STUN_SLAM), -1.0, 0);
            noLift.observeFall(10, 18.0);
            noLift.shieldStun(105);
            check(noLift.beginStunSlamLaunch(18.0, 66.0, false, false, true), "Wind-Burst-Smash startet Auftriebspruefung");
            noLift.settleStunSlamLaunch(16, 0.0, 66.0);
            check(!noLift.won() && !noLift.awaitingSlamLaunch(), "Ohne Auftrieb kein Sieg");

            // Positivfall 1: Axt-Stun + voller Smash aus Hoehe 19 + Wind-Burst-Auftrieb -> Sieg
            var airSlam = new DrillAttempt(DrillOptions.forDrill(Drill.STUN_SLAM), -1.0, 0);
            airSlam.observeFall(10, 19.0);
            airSlam.shieldStun(105);
            check(airSlam.beginStunSlamLaunch(19.0, 66.0, false, false, true), "Voller Wind-Burst-Smash aus Hoehe 19 akzeptiert");
            check(!airSlam.done() && airSlam.awaitingSlamLaunch(), "Vor Wind-Burst-Auftrieb noch offen");
            airSlam.settleStunSlamLaunch(10, 1.45, 66.0);
            check(airSlam.done() && airSlam.won(), "STUN_SLAM mit Wind-Burst-Auftrieb gewonnen");

            // Gegenfall 5: Mace-Treffer VOR dem Axt-Stun darf nicht zaehlen
            var noAxe = new DrillAttempt(DrillOptions.forDrill(Drill.STUN_SLAM), -1.0, 0);
            check(!noAxe.beginStunSlamLaunch(18.0, 66.0, false, false, true) && !noAxe.done(), "Ohne Axt-Stun kein Erfolg");

            // Gegenfall 6: Ziel blockt noch -> darf nicht zaehlen
            var stillBlocking = new DrillAttempt(DrillOptions.forDrill(Drill.STUN_SLAM), -1.0, 0);
            stillBlocking.shieldStun(25);
            check(!stillBlocking.beginStunSlamLaunch(18.0, 66.0, false, true, true) && !stillBlocking.done(), "Blockendes Ziel kein Erfolg");

            // ELY_STUN_SLAM Gegenfall: ohne vorherigen Elytra-Gleitflug kein Sieg
            var elyNoGlide = new DrillAttempt(DrillOptions.forDrill(Drill.ELY_STUN_SLAM), -1.0, 0);
            elyNoGlide.observeFall(15, 24.0);
            elyNoGlide.shieldStun(105);
            check(!elyNoGlide.beginStunSlamLaunch(24.0, 66.0, false, false, true) && !elyNoGlide.won(),
                "ELY_STUN_SLAM ohne Gleitflug darf nicht gewinnen");

            // ELY_STUN_SLAM Positivfall: Gleitflug -> Axt-Stun -> voller Wind-Burst-Smash -> Auftrieb -> Sieg
            var elyWin = new DrillAttempt(DrillOptions.forDrill(Drill.ELY_STUN_SLAM), -1.0, 0);
            elyWin.observeFall(5, 0.0);
            elyWin.glideStarted();
            elyWin.observeFall(20, 24.0);
            elyWin.shieldStun(105);
            check(elyWin.beginStunSlamLaunch(24.0, 66.0, false, false, true), "ELY_STUN_SLAM nach Gleitflug akzeptiert");
            elyWin.settleStunSlamLaunch(20, 1.5, 66.0);
            check(elyWin.done() && elyWin.won(), "ELY_STUN_SLAM mit Wind-Burst-Auftrieb gewonnen");

            // Rollendes Fallfenster: 3 Ticks Gueltigkeit nach letztem Fall > 1.5
            var fall = new DrillAttempt(DrillOptions.forDrill(Drill.STUN_SLAM), -1.0, 0);
            fall.observeFall(20, 4.2);
            check(fall.recentlyFell(), "Fall bei T+20 aktiv");
            fall.observeFall(23, 0.0);
            check(fall.recentlyFell(), "Fall 3 Ticks spaeter noch aktiv");
            fall.observeFall(24, 0.0);
            check(!fall.recentlyFell(), "Fall 4 Ticks spaeter abgelaufen");
        });
    }

    private static void testNewDrillAttemptStateMachines() throws Exception {
        run("DrillAttempt: DOUBLE_ANCHOR, SHIELD_DRAIN, MACE_COUNTER_DTAP & CORNER_WEB_DTAP", () -> {
            // DOUBLE_ANCHOR Positivfall: 2 Anker-Pops im Zeitfenster
            var daWin = new DrillAttempt(DrillOptions.forDrill(Drill.DOUBLE_ANCHOR), -1.0, 0);
            daWin.anchor(null, BlockPos.ZERO, false);
            daWin.anchorHit(14.0F);
            daWin.anchorPop();
            daWin.settleAnchor(null, 5);
            check(!daWin.done(), "Nach Anker-Pop 1 noch offen");
            daWin.anchor(null, BlockPos.ZERO, false);
            daWin.anchorHit(14.0F);
            daWin.anchorPop();
            daWin.settleAnchor(null, 18);
            check(daWin.done() && daWin.won(), "Zwei Anker-Pops im Fenster gewinnen DOUBLE_ANCHOR");

            // DOUBLE_ANCHOR Gegenfall 1: Anker 2 ohne Pop -> Fehlschlag
            var daNoPop = new DrillAttempt(DrillOptions.forDrill(Drill.DOUBLE_ANCHOR), -1.0, 0);
            daNoPop.anchor(null, BlockPos.ZERO, false);
            daNoPop.anchorPop();
            daNoPop.settleAnchor(null, 5);
            daNoPop.anchor(null, BlockPos.ZERO, false);
            daNoPop.anchorHit(8.0F);
            daNoPop.settleAnchor(null, 12);
            check(daNoPop.done() && !daNoPop.won(), "Anker 2 ohne Totem-Pop muss scheitern");

            // DOUBLE_ANCHOR Gegenfall 2: Anker 2 nach Zeitfenster -> Fehlschlag
            var daLate = new DrillAttempt(DrillOptions.forDrill(Drill.DOUBLE_ANCHOR), -1.0, 0);
            daLate.anchor(null, BlockPos.ZERO, false);
            daLate.anchorPop();
            daLate.settleAnchor(null, 5);
            daLate.anchor(null, BlockPos.ZERO, false);
            daLate.anchorPop();
            daLate.settleAnchor(null, 5 + DrillOptions.forDrill(Drill.DOUBLE_ANCHOR).windowTicks() + 5);
            check(daLate.done() && !daLate.won(), "Anker 2 nach Zeitfenster muss scheitern");

            // SHIELD_DRAIN Positivfall
            var drain = new DrillAttempt(DrillOptions.forDrill(Drill.SHIELD_DRAIN), -1.0, 0);
            check(!drain.done(), "SHIELD_DRAIN startet offen");
            drain.shieldBreak();
            check(drain.done() && drain.won(), "Schildbruch gewinnt SHIELD_DRAIN");

            // MACE_COUNTER_DTAP: Schildblock + Gegenfall (ungeblockter Smash)
            var mcBlock = new DrillAttempt(DrillOptions.forDrill(Drill.MACE_COUNTER_DTAP), -1.0, 0);
            mcBlock.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, true, true, 0, true);
            check(!mcBlock.done() && mcBlock.timingHint() != null && mcBlock.timingHint().contains("blocken"),
                "Kristall vor Mace-Block darf nicht zaehlen");
            mcBlock.maceCounterBlock(15);
            check(mcBlock.maceCounterBlocked() && !mcBlock.done(), "Mace-Block entsperrt D-Tap");

            var mcFail = new DrillAttempt(DrillOptions.forDrill(Drill.MACE_COUNTER_DTAP), -1.0, 0);
            mcFail.maceCounterFailed(15);
            check(mcFail.done() && !mcFail.won(), "Ungeblockter Mace-Smash verliert MACE_COUNTER_DTAP");

            // CORNER_WEB_DTAP: Reihenfolge Web -> Shield Break -> 2 Crystal Pops + Gegenfall
            var cw = new DrillAttempt(DrillOptions.forDrill(Drill.CORNER_WEB_DTAP), -1.0, 0);
            cw.shieldStun(100);
            check(!cw.isCornerShieldBroken() && cw.timingHint() != null, "Schildbruch vor Web darf D-Tap nicht freischalten");
            cw.confirmCornerWeb(true);
            check(cw.isCornerWebbed(), "Corner-Web bestaetigt");
            cw.shieldStun(100);
            check(cw.isCornerShieldBroken(), "Schildbruch nach Corner-Web bestaetigt");
            cw.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, true, false, 0, true);
            check(!cw.done(), "Nach 1. Kristall-Pop noch offen");
            cw.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, true, false, 0, true);
            check(cw.done() && cw.won(), "2. Kristall-Pop gewinnt CORNER_WEB_DTAP");

            var cwNoPop = new DrillAttempt(DrillOptions.forDrill(Drill.CORNER_WEB_DTAP), -1.0, 0);
            cwNoPop.confirmCornerWeb(true);
            cwNoPop.shieldStun(100);
            cwNoPop.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 12.0F, 0.0F, true, false, 0, false);
            check(cwNoPop.done() && !cwNoPop.won(), "Kristall ohne Totem-Pop scheitert in CORNER_WEB_DTAP");
        });
    }

    private static void testVanillaPlayerDamageAndCrystalParity() throws Exception {
        run("Vanilla-Spieler-Paritaet: Schwierigkeitsskalierung, Crystal-Defaults und Triple-Tap", () -> {
            // Positivfaelle: exakt Vanilla Player.hurtServer()-Skalierung fuer skalierende Quellen (Kristall/Anker/Explosion)
            check(Math.abs(TrainingArena.scalePlayerDamage(80.0F, Difficulty.HARD, true, true) - 120.0F) < 1e-5F,
                "HARD muss 1,5x skalieren (80 -> 120)");
            check(Math.abs(TrainingArena.scalePlayerDamage(80.0F, Difficulty.NORMAL, true, true) - 80.0F) < 1e-5F,
                "NORMAL muss 1,0x bleiben");
            check(Math.abs(TrainingArena.scalePlayerDamage(80.0F, Difficulty.EASY, true, true) - 41.0F) < 1e-5F,
                "EASY muss min(dmg/2 + 1, dmg) skalieren (80 -> 41)");
            check(Math.abs(TrainingArena.scalePlayerDamage(1.0F, Difficulty.EASY, true, true) - 1.0F) < 1e-5F,
                "EASY darf kleinen Schaden nicht erhoehen (1 -> 1)");
            check(Math.abs(TrainingArena.scalePlayerDamage(80.0F, Difficulty.PEACEFUL, true, true) - 80.0F) < 1e-5F,
                "PEACEFUL in Arena wird wie DrillDamageMixin als NORMAL behandelt");

            // Gegenfaelle: ausserhalb der Arena gibt PEACEFUL 0; nicht-skalierende Quellen bleiben unveraendert
            check(TrainingArena.scalePlayerDamage(80.0F, Difficulty.PEACEFUL, true, false) == 0.0F,
                "PEACEFUL ausserhalb Arena muss 0 liefern");
            check(Math.abs(TrainingArena.scalePlayerDamage(11.0F, Difficulty.HARD, false, true) - 11.0F) < 1e-5F,
                "Nicht-skalierender Nahkampfschaden darf auf HARD nicht mit 1,5x multipliziert werden");

            // Crystal-Standard startet auf Vanilla 10 Herzen (20 LP), nicht 20 Herzen (40 LP)
            check(TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).maxHealth() == 20,
                "CRYSTAL-Standard muss Vanilla 10 Herzen (20 LP) sein");
            check(TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).forDrill(Drill.TRIPLE_TAP).maxHealth() == 20,
                "TRIPLE_TAP startet mit 10 Herzen (20 LP)");
            check(TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).forDrill(Drill.D_TAP).maxHealth() == 20,
                "D_TAP startet mit 10 Herzen (20 LP)");

            // SHIELD_TRIPLE_TAP: optionaler 3. Luft-Nahkampftreffer nach Pop 2 darf crystalHits nicht auf 0 zuruecksetzen
            var stt = new DrillAttempt(DrillOptions.forDrill(Drill.SHIELD_TRIPLE_TAP), -1.0, 0);
            stt.observeFall(0, 0.0);
            // Setze Zustand via reflection-freie oeffentliche Methoden: Shield-Stun + 2 Pops -> optionaler 3. Lufttreffer -> Pop 3
            var ttField = DrillAttempt.class.getDeclaredField("swordAt");
            ttField.setAccessible(true);
            ttField.setInt(stt, 0);
            var airField = DrillAttempt.class.getDeclaredField("airborneTarget");
            airField.setAccessible(true);
            airField.setBoolean(stt, true);
            stt.observeFall(4, 0.0);
            stt.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, false, true, 6, true);
            check(stt.crystalHits() == 1 && stt.needsAirSword(), "Nach Pop 1 muss Schildbruch erwartet werden");
            stt.observeFall(6, 0.0);
            stt.shieldStun(100);
            check(!stt.needsAirSword(), "Schildbruch entsperrt Kristall 2");
            stt.observeFall(10, 0.0);
            stt.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 15.0F, 0.0F, true, true, 0, true);
            check(stt.crystalHits() == 2 && !stt.done(), "Pop 2 registriert");
            stt.observeFall(20, 0.0);
            stt.swordContact(null, null, 1.5F, 1.0F, true, true, true);
            check(stt.crystalHits() == 2 && !stt.done(), "Optionaler 3. Lufttreffer nach Pop 2 darf crystalHits nicht zuruecksetzen");
            stt.observeFall(22, 0.0);
            stt.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 15.0F, 0.0F, false, true, 8, true);
            check(stt.done() && stt.won() && stt.crystalHits() == 3, "Pop 3 im Fenster von Lufttreffer 3 gewinnt SHIELD_TRIPLE_TAP");
        });
    }

    private static void testBatchFeaturesAndCrystalHotfixes() throws Exception {
        run("Neue Features & Hotfixes: Arena-Schwierigkeit, Zero-Damage, Health-Tag, Self-Web, Uppercut, D-Tap/Triple-Tap", () -> {
            // 1. Arena-Schwierigkeit: fest auf Schwer (HARD) und aus dem UI ausgeblendet
            var defaultOpts = DrillOptions.forDrill(Drill.D_TAP);
            check(defaultOpts.arenaDifficulty() == DrillOptions.ArenaDifficulty.HARD, "Arena-Schwierigkeit muss immer Schwer (HARD) sein");
            check(!DrillOptions.Field.ARENA_DIFFICULTY.visible(defaultOpts, TrainingSettings.Mode.CRYSTAL), "Arena-Schwierigkeit muss aus dem UI entfernt sein");
            check(defaultOpts.arenaDifficulty().resolve(Difficulty.NORMAL, true) == Difficulty.HARD, "Arena bleibt auf Normal-Welt HARD");
            check(DrillOptions.ArenaDifficulty.WORLD.resolve(Difficulty.NORMAL, true) == Difficulty.HARD, "Legacy WORLD mappt in Arena fest auf HARD");
            check(DrillOptions.ArenaDifficulty.EASY.resolve(Difficulty.EASY, true) == Difficulty.HARD, "Legacy EASY mappt in Arena fest auf HARD");
            check(DrillOptions.ArenaDifficulty.WORLD.resolve(Difficulty.PEACEFUL, true) == Difficulty.HARD, "Legacy PEACEFUL mappt in Arena fest auf HARD");
            var legacyWorldOpts = defaultOpts.withIndex(DrillOptions.Field.ARENA_DIFFICULTY, DrillOptions.ArenaDifficulty.WORLD.ordinal());
            check(legacyWorldOpts.arenaDifficulty() == DrillOptions.ArenaDifficulty.HARD, "Legacy ARENA_DIFFICULTY-Override faellt auf HARD zurueck");

            // 2. Zero-Damage-Modus
            check(defaultOpts.zeroDamage() == DrillOptions.ZeroDamage.OFF, "Zero-Damage Standard Aus");
            var zeroPlayer = defaultOpts.withIndex(DrillOptions.Field.ZERO_DAMAGE, DrillOptions.ZeroDamage.PLAYER_ONLY.ordinal());
            var zeroBoth = defaultOpts.withIndex(DrillOptions.Field.ZERO_DAMAGE, DrillOptions.ZeroDamage.BOTH.ordinal());
            check(zeroPlayer.zeroDamage() == DrillOptions.ZeroDamage.PLAYER_ONLY && zeroBoth.zeroDamage() == DrillOptions.ZeroDamage.BOTH,
                "Zero-Damage-Optionen verfuegbar");

            // 3. Health Indicator Nametag
            check("MacherBot".equals(MacherAppearance.healthName(20.0F, 20.0F, 0.0F, false).getString()), "Aus blendet Lebensanzeige aus");
            String fullTag = MacherAppearance.healthName(20.0F, 20.0F, 0.0F, true).getString();
            check(fullTag.contains("20,0") && fullTag.contains("❤") && !fullTag.contains("+"), "Volle LP ohne Goldherzen: " + fullTag);
            String popTag = MacherAppearance.healthName(1.0F, 20.0F, 8.0F, true).getString();
            check(popTag.contains("1,0") && popTag.contains("+8,0"), "Nach Pop mit Goldherzen: " + popTag);

            // 4. D-Tap Hotfix: 2 Pops gewinnen auch ohne vorherigen Schwerttreffer, bei T+10, und trotz Luft-Zwischentreffer
            var dtapDirect = new DrillAttempt(DrillOptions.forDrill(Drill.D_TAP), -1.0, 0);
            dtapDirect.observeFall(4, 0.0);
            dtapDirect.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, true, true, 0, true);
            check(!dtapDirect.done() && dtapDirect.crystalHits() == 1, "Direkter Pop 1 zaehlt im D-Tap");
            dtapDirect.observeFall(8, 0.0);
            dtapDirect.swordContact(null, null, 2.0F, 1.0F, true, true, true);
            check(dtapDirect.crystalHits() == 1, "Lufttreffer zwischen Pop 1 und Pop 2 darf crystalHits nicht zuruecksetzen");
            dtapDirect.observeFall(14, 0.0);
            dtapDirect.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, true, true, 0, true);
            check(dtapDirect.done() && dtapDirect.won(), "Zwei Pops muessen D-Tap gewinnen");

            // D-Tap Gegenfall: Pop 2 ohne Totem-Pop scheitert
            var dtapNoPop = new DrillAttempt(DrillOptions.forDrill(Drill.D_TAP), -1.0, 0);
            dtapNoPop.observeFall(4, 0.0);
            dtapNoPop.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, true, true, 0, true);
            dtapNoPop.observeFall(14, 0.0);
            dtapNoPop.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 6.0F, 0.0F, true, true, 0, false);
            check(dtapNoPop.done() && !dtapNoPop.won(), "Kristall 2 ohne Pop muss im D-Tap scheitern");

            // 5. Triple Tap Hotfix: 3 Pops gewinnen auch ohne erzwungenen Schwert-Lufttreffer dazwischen
            var ttap = new DrillAttempt(DrillOptions.forDrill(Drill.TRIPLE_TAP), -1.0, 0);
            ttap.observeFall(4, 0.0);
            ttap.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, false, true, 6, true);
            ttap.observeFall(14, 0.0);
            ttap.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, false, true, 4, true);
            check(!ttap.done() && ttap.crystalHits() == 2, "2 Pops im Triple Tap registriert");
            ttap.observeFall(24, 0.0);
            ttap.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, true, true, 0, true);
            check(ttap.done() && ttap.won() && ttap.crystalHits() == 3, "3 Pops muessen Triple Tap gewinnen");

            // Triple Tap Gegenfall: Kristall 3 am Boden scheitert
            var ttapGround = new DrillAttempt(DrillOptions.forDrill(Drill.TRIPLE_TAP), -1.0, 0);
            ttapGround.observeFall(4, 0.0);
            ttapGround.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, true, true, 0, true);
            ttapGround.observeFall(14, 0.0);
            ttapGround.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, true, true, 0, true);
            ttapGround.observeFall(24, 0.0);
            ttapGround.crystalBlast(null, null, Vec3.ZERO, Vec3.ZERO, 20.0F, 0.0F, true, false, 0, true);
            check(ttapGround.done() && !ttapGround.won(), "Kristall 3 nach Bodenkontakt muss scheitern");

            // 6. SELF_WEB_CRYSTAL: Boden-Crystal Positiv- und Gegenfaelle
            check(Drill.SELF_WEB_CRYSTAL.supports(TrainingSettings.Mode.CRYSTAL) && Drill.SELF_WEB_CRYSTAL.requiresPopChain()
                && !Drill.SELF_WEB_CRYSTAL.requiresAirPopChain(), "SELF_WEB_CRYSTAL Modus-Flags");
            var swc = new DrillAttempt(DrillOptions.forDrill(Drill.SELF_WEB_CRYSTAL), -1.0, 0);
            swc.confirmSelfWeb();
            // Erhoehter Kristall (nicht auf Fusshoehe) wird abgelehnt
            swc.crystalBlast(null, null, new Vec3(1, 66, 0), new Vec3(0, 65, 0), 6.0F, 0.0F, true, false, 0, false);
            check(!swc.done() && swc.timingHint() != null && swc.timingHint().contains("Boden-Crystal"), "Erhoehter Kristall abgelehnt");
            // Boden-Kristall 1 (Vorbereitung 18.8 LP ohne Pop) scheitert nicht vorzeitig
            swc.crystalBlast(null, null, new Vec3(1, 65, 0), new Vec3(0, 65, 0), 18.8F, 0.0F, true, false, 0, false);
            check(!swc.done(), "Vorbereitender Boden-Crystal vor Pop 1 darf nicht scheitern");
            // Boden-Kristall Pop 1 + Pop 2 -> Sieg
            swc.observeFall(12, 0.0);
            swc.crystalBlast(null, null, new Vec3(1, 65, 0), new Vec3(0, 65, 0), 18.8F, 0.0F, true, false, 0, true);
            check(!swc.done(), "Nach Pop 1 noch offen");
            swc.observeFall(24, 0.0);
            swc.crystalBlast(null, null, new Vec3(1, 65, 0), new Vec3(0, 65, 0), 18.8F, 0.0F, true, false, 0, true);
            check(swc.done() && swc.won(), "2 Boden-Crystal-Pops gewinnen SELF_WEB_CRYSTAL");

            // 7. Alle Presets fuer die 3 neuen Drills muessen sauber bauen
            for (var preset : TrainingPreset.values()) {
                check(preset.setup(TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL), Drill.SELF_WEB_CRYSTAL) != null, "SELF_WEB_CRYSTAL Preset " + preset);
                var up = preset.setup(TrainingSettings.defaults(TrainingSettings.Mode.SWORD), Drill.UPPERCUT);
                check(up.options().startDistance() >= 7, "UPPERCUT Preset startet bei >= 7 Bl.");
                var ely = preset.setup(TrainingSettings.defaults(TrainingSettings.Mode.MACE), Drill.ELY_STUN_SLAM);
                check(ely.options().holdShield() && ely.options().startHeight() >= 24, "ELY_STUN_SLAM Preset mit Schild & Luftstart");
            }
        });
    }

    private static void testSwightDrillsAndKnowledgeGuide() throws Exception {
        run("Swight-Drills, Optionen, Reach-Indikator & Knowledge Guide", () -> {
            // 1. Knowledge Guide JSON vollstaendig & valide
            var entries = KnowledgeGuide.all();
            check(entries.size() >= 24, "Knowledge Guide braucht >= 24 Techniken, gefunden: " + entries.size());
            for (var cat : KnowledgeGuide.Category.values()) {
                check(!KnowledgeGuide.byCategory(cat).isEmpty(), "Kategorie leer: " + cat);
            }
            for (var entry : entries) {
                check(!entry.id().isBlank() && !entry.title().isBlank() && !entry.summary().isBlank()
                    && !entry.timing().isBlank() && !entry.example().isBlank() && !entry.steps().isEmpty()
                    && !entry.mistakes().isBlank(), "Unvollstaendiger Guide-Eintrag: " + entry.id());
                if (entry.drill() != null && entry.mode() != null) {
                    check(entry.drill().supports(entry.mode()), "Guide-Drill passt nicht zum Modus: " + entry.id());
                }
            }
            var mbEntry = KnowledgeGuide.forDrill(Drill.MOMENTUM_BREAK, TrainingSettings.Mode.UHC);
            check(mbEntry != null && "momentum_break".equals(mbEntry.id()), "forDrill findet Momentum Break");
            check(HudPreferences.reachIndicator(), "Reach-Indikator standardmaessig aktiv");

            // 2. SHIELD_TIMING: Gegenfall (Panik-Schlag nach Zwischentreffer + Axt verboten) & Positivfall (3 Schild-Konter)
            var stFail = new DrillAttempt(DrillOptions.forDrill(Drill.SHIELD_TIMING), -1.0, 0);
            stFail.observeFall(5, 0.0);
            stFail.axeNotAllowedInShieldTiming();
            check(stFail.timingHint() != null && stFail.timingHint().contains("ohne Axt"), "Axt in SHIELD_TIMING abgelehnt");
            stFail.playerHit(null, 3.0F);
            stFail.observeFall(8, 0.0);
            stFail.swordContact(null, null, 3.0F, 1.0F, false, true, true);
            check(stFail.done() && !stFail.won(), "Panik-Schlag nach Zwischentreffer muss scheitern");

            var stWin = new DrillAttempt(DrillOptions.forDrill(Drill.SHIELD_TIMING), -1.0, 0);
            for (int i = 0; i < 3; i++) {
                stWin.observeFall(10 + i * 20, 0.0);
                stWin.playerShieldBlocked();
                stWin.observeFall(14 + i * 20, 0.0);
                stWin.swordContact(null, null, 3.0F, 1.0F, false, true, true);
            }
            check(stWin.done() && stWin.won(), "3 saubere Schild-Konter gewinnen SHIELD_TIMING");

            // 3. MOMENTUM_BREAK: Positivfall (Stun + Auto-Hit + Jump-Reset + Schwert) & Gegenfall (kein Jump-Reset)
            var mbWin = new DrillAttempt(DrillOptions.forDrill(Drill.MOMENTUM_BREAK), -1.0, 0);
            mbWin.observeFall(10, 0.0);
            mbWin.shieldStun(100);
            mbWin.observeFall(12, 0.0);
            mbWin.playerHit(null, 2.0F);
            mbWin.jump();
            mbWin.observeFall(15, 0.0);
            mbWin.swordContact(null, null, 4.0F, 1.0F, false, true, true);
            check(mbWin.done() && mbWin.won(), "Stun + Jump-Reset auf Auto-Hit + Schwert gewinnt MOMENTUM_BREAK");

            var mbFail = new DrillAttempt(DrillOptions.forDrill(Drill.MOMENTUM_BREAK), -1.0, 0);
            mbFail.observeFall(10, 0.0);
            mbFail.shieldStun(100);
            mbFail.observeFall(12, 0.0);
            mbFail.playerHit(null, 2.0F);
            mbFail.observeFall(15, 0.0);
            mbFail.swordContact(null, null, 4.0F, 1.0F, false, true, true);
            check(mbFail.done() && !mbFail.won(), "Auto-Hit ohne Jump-Reset scheitert in MOMENTUM_BREAK");

            // 4. WEB_DUNK: Gegenfall (Web vor Crit)
            var wd = new DrillAttempt(DrillOptions.forDrill(Drill.WEB_DUNK), -1.0, 0);
            wd.webPlaced(BlockPos.ZERO);
            check(!wd.done() && wd.timingHint() != null && wd.timingHint().contains("kritischen"), "Web vor Crit gibt Hinweis");

            // 5. ANTI_DRAIN: Gegenfall (gedraint) & Push-Flag
            var adFail = new DrillAttempt(DrillOptions.forDrill(Drill.ANTI_DRAIN), -1.0, 0);
            adFail.playerWaterPlaced(BlockPos.ZERO);
            adFail.antiDrainDrained();
            check(adFail.done() && !adFail.won(), "Gedraintes Wasser verliert ANTI_DRAIN");
            var adPush = new DrillAttempt(DrillOptions.forDrill(Drill.ANTI_DRAIN), -1.0, 0);
            adPush.swordContact(null, null, 3.0F, 1.0F, false, true, true);
            check(adPush.antiDrainPushed(), "Wegschlagen im Web wird fuer ANTI_DRAIN registriert");

            // 6. HEAL_PUNISH: Positivfall (2 Crits) & Gegenfall (Bot heilt durch)
            var hpWin = new DrillAttempt(DrillOptions.forDrill(Drill.HEAL_PUNISH), -1.0, 0);
            hpWin.botStartedHealing();
            hpWin.critical();
            check(!hpWin.done() && hpWin.healPunishCrits() == 1, "Nach 1 Crit noch offen");
            hpWin.critical();
            check(hpWin.done() && hpWin.won(), "2 Crits gewinnen HEAL_PUNISH");

            var hpFail = new DrillAttempt(DrillOptions.forDrill(Drill.HEAL_PUNISH), -1.0, 0);
            hpFail.botStartedHealing();
            hpFail.critical();
            hpFail.botHealFinished();
            check(hpFail.done() && !hpFail.won(), "Fertiger Bot-Heal bei < 2 Crits scheitert");

            // 7. PCRIT_BREAK: Gegenfall (vor Bot-Hit / zu schwacher Charge) & Positivfall (Sprint-KB + Anschluss)
            var pcFail = new DrillAttempt(DrillOptions.forDrill(Drill.PCRIT_BREAK), -1.0, 0);
            pcFail.swordContact(null, null, 3.0F, 1.0F, false, true, true);
            check(pcFail.done() && !pcFail.won(), "Schlag vor Bot-Treffer scheitert in PCRIT_BREAK");

            var pcWin = new DrillAttempt(DrillOptions.forDrill(Drill.PCRIT_BREAK), -1.0, 0);
            pcWin.observeFall(10, 0.0);
            pcWin.playerHit(null, 3.0F);
            pcWin.observeFall(14, 0.0);
            pcWin.swordContact(null, null, 2.0F, 0.5F, false, true, true);
            check(!pcWin.done() && pcWin.timingHint() != null, "Schwacher Hit (<85%) zaehlt nicht als Sprint-KB-Break");
            pcWin.observeFall(18, 0.0);
            pcWin.swordContact(null, null, 4.0F, 1.0F, false, true, true);
            check(!pcWin.done(), "Nach Sprint-KB-Break 1/2 noch offen");
            pcWin.observeFall(26, 0.0);
            pcWin.swordContact(null, null, 4.0F, 1.0F, false, true, true);
            check(pcWin.done() && pcWin.won(), "Sprint-KB-Break + Anschluss gewinnt PCRIT_BREAK");

            // 8. Neue Optionen & Presets fuer alle 6 Swight-Drills
            var swOpts = DrillOptions.forDrill(Drill.STUN_WEB)
                .withIndex(DrillOptions.Field.RANDOM_BLOCK_POS, 1)
                .withIndex(DrillOptions.Field.BOT_AUTO_HIT, 1);
            check(swOpts.randomBlockPos() && swOpts.botAutoHit(), "STUN_WEB neue Optionen aktivierbar");
            check(DrillOptions.forDrill(Drill.HEAL).withIndex(DrillOptions.Field.CLEAN_HEAL, 1).cleanHeal(), "HEAL Sauberer Heal aktivierbar");
            for (var preset : TrainingPreset.values()) {
                for (var drill : List.of(Drill.SHIELD_TIMING, Drill.MOMENTUM_BREAK, Drill.WEB_DUNK, Drill.ANTI_DRAIN, Drill.HEAL_PUNISH)) {
                    check(preset.setup(TrainingSettings.defaults(TrainingSettings.Mode.UHC), drill) != null, drill + " Preset " + preset);
                }
                check(preset.setup(TrainingSettings.defaults(TrainingSettings.Mode.SWORD), Drill.PCRIT_BREAK).options().startDistance() >= 7,
                    "PCRIT_BREAK Preset startet bei >= 7 Bl.");
            }
        });
    }

    private static void testUiRedesignStickyModifiersAndIcons() throws Exception {
        run("UI-Redesign: Sticky Modifier, feste Schwer-Defaults, Legacy-JSON & 12x12 Icon-Assets", () -> {
            // 1. Alle Modi und Presets nutzen fest Difficulty.HARD
            for (var mode : TrainingSettings.Mode.values()) {
                var def = TrainingSettings.defaults(mode);
                check(def.difficulty() == TrainingSettings.Difficulty.HARD, mode + " Default muss HARD sein");
                for (var drill : Drill.forMode(mode)) {
                    check(def.forDrill(drill).difficulty() == TrainingSettings.Difficulty.HARD,
                        mode + "/" + drill + " forDrill muss HARD sein");
                    for (var preset : TrainingPreset.values()) {
                        check(preset.setup(def, drill).behavior().difficulty() == TrainingSettings.Difficulty.HARD,
                            mode + "/" + drill + " Preset " + preset + " muss HARD sein");
                    }
                }
            }

            // 2. Sticky Modifier innerhalb desselben Modus (Drill- und Preset-Wechsel)
            var configured = DrillOptions.forDrill(Drill.STUN_WEB)
                .withIndex(DrillOptions.Field.TICK_RATE, DrillOptions.Field.TICK_RATE.indexOfNumber(10))
                .withIndex(DrillOptions.Field.ARENA_SIZE, 1)
                .withIndex(DrillOptions.Field.ZERO_DAMAGE, DrillOptions.ZeroDamage.PLAYER_ONLY.ordinal())
                .withIndex(DrillOptions.Field.PLAYER_PING, DrillOptions.Field.PLAYER_PING.indexOfNumber(60))
                .withIndex(DrillOptions.Field.BOT_PING, DrillOptions.Field.BOT_PING.indexOfNumber(30))
                .withIndex(DrillOptions.Field.FALL_DAMAGE, 0)
                .withIndex(DrillOptions.Field.ARMOR_WEAR, 0)
                .withIndex(DrillOptions.Field.ITEM_CONSUMPTION, 1); // UHC: unendlich
            var carried = configured.carryModifiersTo(DrillOptions.forDrill(Drill.WEB_DUNK), TrainingSettings.Mode.UHC);
            check(carried.drill() == Drill.WEB_DUNK, "Ziel-Drill uebernommen");
            check(carried.tickRate() == 10, "Spieltempo (10 TPS) bleibt beim Drill-Wechsel aktiv");
            check(carried.largeArena(), "Grosse Arena bleibt beim Drill-Wechsel aktiv");
            check(carried.zeroDamage() == DrillOptions.ZeroDamage.PLAYER_ONLY, "Zero-Damage bleibt beim Drill-Wechsel aktiv");
            check(carried.playerPing() == 60 && carried.botPing() == 30, "Pings bleiben beim Drill-Wechsel aktiv");
            check(carried.fallDamage() && carried.armorWear(), "Fallschaden & Ruestungsverschleiss bleiben aktiv");
            check(!carried.itemConsumption(TrainingSettings.Mode.UHC), "Unendliche Items bleiben im selben Modus aktiv");

            // 3. Cross-Mode Sticky Modifier (z.B. Crystal -> UHC behaelt Tempo/Arena/ZeroDmg/Ping/Fall/Armor, aber respektiert UHC Item-Default)
            var cross = configured.carryCrossModeModifiersTo(DrillOptions.forDrill(Drill.D_TAP));
            check(cross.tickRate() == 10 && cross.largeArena() && cross.zeroDamage() == DrillOptions.ZeroDamage.PLAYER_ONLY
                && cross.playerPing() == 60 && cross.fallDamage(), "Cross-Mode Modifier bleiben erhalten");
            check(!cross.itemConsumption(TrainingSettings.Mode.CRYSTAL), "Crystal behaelt eigenen Item-Default");

            // 4. Legacy-Profile mit EASY und ARENA_DIFFICULTY bleiben fehlerfrei lesbar
            Path tempDir = Files.createTempDirectory("macher-legacy-profile-");
            try {
                Path file = tempDir.resolve("pvptrainer.json");
                String legacyJson = """
                    {
                      "selected": "UHC",
                      "profiles": {
                        "UHC": {
                          "mode": "UHC",
                          "difficulty": "EASY",
                          "movement": "STILL",
                          "maxHealth": 20,
                          "counterAttacks": false,
                          "autoRespawn": true,
                          "duelTier": "T3",
                          "duelBand": "AUTO",
                          "maceCenterLocked": false
                        }
                      },
                      "exercises": {
                        "UHC": {
                          "drill": "STUN_WEB",
                          "overrides": {
                            "ARENA_DIFFICULTY": 3,
                            "ARENA_SIZE": 1,
                            "TICK_RATE": 9
                          }
                        }
                      }
                    }
                    """;
                Files.writeString(file, legacyJson, StandardCharsets.UTF_8);
                var profiles = new TrainingProfiles(file);
                check(profiles.selected().mode() == TrainingSettings.Mode.UHC, "Legacy-Profil lesbar");
                check(profiles.options(TrainingSettings.Mode.UHC).largeArena(), "Legacy ARENA_SIZE erhalten");
                check(profiles.options(TrainingSettings.Mode.UHC).tickRate() == 10, "Legacy TICK_RATE erhalten");
                check(profiles.options(TrainingSettings.Mode.UHC).arenaDifficulty() == DrillOptions.ArenaDifficulty.HARD,
                    "Legacy ARENA_DIFFICULTY wird fest auf HARD aufgeloest");
            } finally {
                deleteRecursive(tempDir);
            }

            // 5. Alle 11 12x12 GUI-Icon-PNGs sind vorhanden und gueltige PNG-Dateien
            for (String name : List.of("gear", "clock", "arena", "zero_damage", "ping", "infinity",
                "fall_damage", "help", "reset", "chevron_right", "chevron_down")) {
                String path = "/assets/pvptrainer/textures/gui/icons/" + name + ".png";
                try (var in = MacherBotUnitTests.class.getResourceAsStream(path)) {
                    check(in != null, "GUI-Icon fehlt: " + path);
                    byte[] header = in.readNBytes(8);
                    check(header.length == 8 && (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G',
                        "Ungueltiges PNG-Format: " + path);
                }
            }
        });
    }

    private static void testRealPvpMetricsAndStatsAggregation() throws Exception {
        run("Statistik-Workover: RoundMetrics, TrainingStats-Aggregation, Zeitraeume, PracticeOnly & Legacy-JSONL", () -> {
            // 1. Collector consumeSnapshot() resettet atomar (kein Doppelzaehlen)
            var collector = new RoundMetricsCollector();
            collector.recordMaceSmash(14.5, 22.0F);
            collector.recordMaceSmash(28.5, 36.0F);
            collector.recordStunSlam();
            collector.recordWindCharge();
            collector.recordWindCharge();
            collector.recordBotPop();
            collector.recordShieldStun();
            RoundMetrics maceSnap = collector.consumeSnapshot();
            check(!collector.hasActivity(), "consumeSnapshot muss Collector atomar zuruecksetzen");
            check(maceSnap.maceSmashes() == 2 && maceSnap.stunSlams() == 1, "Mace-Smashes & Stun-Slams erfasst");
            check(Math.abs(maceSnap.maxFallMeters() - 28.5) < 1e-6 && Math.abs(maceSnap.avgFallMeters() - 21.5) < 1e-6,
                "Max- und Durchschnitts-Fallhoehe exakt");
            check(Math.abs(maceSnap.maxSmashHearts() - 18.0) < 1e-6, "Staerker Smash-Hit in Herzen exakt");

            // 2. Crystal Effizienz & Sword Crit/Charge
            var cCol = new RoundMetricsCollector();
            cCol.recordCrystalPlaced(); cCol.recordCrystalPlaced(); cCol.recordCrystalPlaced();
            cCol.recordCrystalDetonated(); cCol.recordCrystalDetonated(); cCol.recordCrystalDetonated();
            cCol.recordBotPop(); cCol.recordBotPop();
            cCol.recordObsidianPlaced();
            RoundMetrics crystalSnap = cCol.consumeSnapshot();
            check(Math.abs(crystalSnap.crystalsPerPop() - 1.5) < 1e-6, "Kristalle/Pop = 3/2 = 1.5");

            var sCol = new RoundMetricsCollector();
            sCol.recordMeleeHit(0.90F); sCol.recordMeleeHit(1.00F);
            sCol.recordCrit();
            sCol.recordDamageDealt(16.0F);
            RoundMetrics swordSnap = sCol.consumeSnapshot();
            check(Math.abs(swordSnap.critRatePct() - 50.0) < 1e-6 && Math.abs(swordSnap.avgChargePct() - 95.0) < 1e-6,
                "Crit-Quote 50% und Avg-Charge 95%");
            check(RoundMetrics.EMPTY.crystalsPerPop() == 0.0 && RoundMetrics.EMPTY.avgFallMeters() == 0.0
                && RoundMetrics.EMPTY.critRatePct() == 0.0, "Division-durch-0-Schutz bei EMPTY");

            // 3. TrainingStats Modus-Aggregation, Zeitraum-Filter und practiceOnly-Trennung
            long now = 1_780_000_000_000L;
            var zone = java.time.ZoneOffset.UTC;
            long tenDaysAgo = now - 10L * 86_400_000L;
            var runs = List.of(
                new TrainingProgress.Run("s-mace", "MACE", "STUN_SLAM", now - 60_000L, true, 24, "Geschafft", List.of(), "HARD", 20, false, maceSnap, false),
                new TrainingProgress.Run("s-mace", "MACE", "STUN_SLAM", now - 30_000L, false, 40, "Schild noch aktiv", List.of(), "HARD", 20, false, RoundMetrics.EMPTY, false),
                // Practice-only (Passives Ziel): zaehlt Aktionen, aber NICHT Erfolgsquote oder Streak!
                new TrainingProgress.Run("s-crys-p", "CRYSTAL", "PASSIVE", now - 20_000L, false, 200, "Freies Ueben", List.of(), "HARD", 20, false, crystalSnap, true),
                new TrainingProgress.Run("s-crys", "CRYSTAL", "D_TAP", now - 10_000L, true, 18, "Geschafft", List.of(), "HARD", 20, false, crystalSnap, false),
                // Alter Eintrag vor 10 Tagen -> nur in ALL sichtbar, nicht in TODAY/WEEK
                new TrainingProgress.Run("s-sword", "SWORD", "DUEL", tenDaysAgo, true, 120, "Gewonnen", List.of(), "HT1", 20, false, swordSnap, false)
            );

            var maceToday = TrainingStats.overview(runs, TrainingSettings.Mode.MACE, TrainingStats.TimeRange.TODAY, now, zone);
            check(maceToday.scoredAttempts() == 2 && maceToday.scoredWins() == 1 && maceToday.winRatePct() == 50, "Mace Quote 50% (1/2)");
            check(maceToday.totals().stunSlams() == 1 && maceToday.totals().maceSmashes() == 2 && Math.abs(maceToday.totals().maxFallMeters() - 28.5) < 1e-6,
                "Mace Overview echte Kennzahlen");

            var crystalToday = TrainingStats.overview(runs, TrainingSettings.Mode.CRYSTAL, TrainingStats.TimeRange.TODAY, now, zone);
            check(crystalToday.scoredAttempts() == 1 && crystalToday.scoredWins() == 1 && crystalToday.winRatePct() == 100,
                "PracticeOnly darf Drill-Quote nicht verfaelschen (100% statt 50%)");
            check(crystalToday.totals().crystalsDetonated() == 6 && crystalToday.totals().botPops() == 4,
                "PracticeOnly-Aktionen (Passives Ziel) werden bei echten Zaehlern voll mitgezaehlt");

            var streak = TrainingProgress.Streak.EMPTY.next(runs.get(3)).next(runs.get(2));
            check(streak.current() == 1 && streak.best() == 1, "PracticeOnly-Eintrag darf laufende Streak nicht brechen");

            var allWeek = TrainingStats.overview(runs, null, TrainingStats.TimeRange.WEEK, now, zone);
            var allTime = TrainingStats.overview(runs, null, TrainingStats.TimeRange.ALL, now, zone);
            check(allWeek.duelWins() == 0 && allTime.duelWins() == 1, "Zeitraum-Filter (7T vs Gesamt) trennt alte Eintraege sauber");

            // 4. DrillReport waehlt echte Chart-Achse + Top-Fehler
            var maceReport = TrainingStats.drillReport(runs, TrainingSettings.Mode.MACE, Drill.STUN_SLAM, TrainingStats.TimeRange.ALL, now, zone);
            check(maceReport.axis() == TrainingStats.ChartAxis.FALL_METERS, "Stun-Slam nutzt Fallhoehe (m) als Chart-Achse");
            check(maceReport.topFailures().size() == 1 && maceReport.topFailures().getFirst().reason().equals("Schild noch aktiv"),
                "Haeufigster Fehler korrekt erfasst");
            var dtapReport = TrainingStats.drillReport(runs, TrainingSettings.Mode.CRYSTAL, Drill.D_TAP, TrainingStats.TimeRange.ALL, now, zone);
            check(dtapReport.axis() == TrainingStats.ChartAxis.SECONDS && Math.abs(dtapReport.bestSeconds() - 0.9) < 1e-6,
                "D-Tap nutzt echte Ausfuehrungszeit in Sekunden (0,90 s)");

            // 5. Legacy-JSONL-Zeile ohne 'metrics' und 'practiceOnly' bleibt 100% lesbar (Regel 5)
            String legacyLine = "{\"setup\":\"abc\",\"mode\":\"CRYSTAL\",\"drill\":\"D_TAP\",\"time\":1700000000000,\"won\":true,\"ticks\":20,\"reason\":\"Geschafft\",\"timeline\":[],\"difficulty\":\"HARD\",\"tickRate\":20}";
            TrainingProgress.Run parsed = new com.google.gson.Gson().fromJson(legacyLine, TrainingProgress.Run.class);
            check(parsed != null && parsed.metrics() != null && !parsed.metrics().hasActivity() && !parsed.practiceOnly(),
                "Legacy-JSONL-Zeile ohne metrics/practiceOnly deserialisiert sicher mit Defaults");
        });
    }

    private static void deleteRecursive(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}

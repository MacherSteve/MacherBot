package de.pvptrainer;

import com.sun.net.httpserver.HttpServer;
import de.pvptrainer.analytics.BotArchive;
import de.pvptrainer.analytics.BotStatistics;
import de.pvptrainer.analytics.BotTelemetry;
import de.pvptrainer.analytics.CalibrationPlan;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillAttempt;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.drill.WindHeight;
import de.pvptrainer.kits.TrainingProfiles;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.progress.TrainingProgress;
import de.pvptrainer.progress.UpdateChecker;
import de.pvptrainer.session.CobwebFlightTracker;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

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

    private static void deleteRecursive(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}

# MacherBot · Trainingsmod (Quellstand)

Eigenständiges Git-Repository für die Fabric-Mod **MacherBot** (Minecraft Java 26.2, JDK 25, Fabric-ID `macherbot`, Kompatibilitätskennung `provides: ["pvptrainer"]`).

## Paket- und Ordnerstruktur

- `src/main/java/de/pvptrainer/`
  - `TrainerClient` – Fabric-Client-Entrypoint und Runden-Dispatcher
  - `arena/` – Arena-Dimension, Welt-Recovery, Rückkehrpunkt und `ReadyGate` (`TrainingArena`, `ForgeArena`, `ArenaRound`, `ArenaRecoverySave`, `ReadyGate`)
  - `session/` – Runden-Lebenszyklus, Gegner-Entität, Flug-/Wind-Controller, Ping & Ressourcen (`TrainingSession`, `TrainingBot`, `CobwebFlightTracker`, `WindTargetController`, `TrainingStatus`, `ArenaSignals`, `RoundPing`, `LocalPacketDelay`, `TrainingResources`)
  - `drill/` – 32 Übungen, Optionen & zerlegte Wertungs-Tracker (`Drill`, `DrillOptions`, `DrillAttempt`, `TotemDefenseTracker`, `UhcUtilityTracker`, `SlamWindowTracker`, `DrillEvents`, `ObsidianEnclosure`, `WebAim`, `WindHeight`, `WindPearlCatch`)
  - `combat/` – Kampf-KI, Taktik, Ausrüstung und Crystal-Entitäten (`TrainingTactics`, `BotEquipment`, `SwordDuel`, `MaceDuel`, `Crystal*`, `Uhc*`, `Duel*`, `TrainingCrystal*`)
  - `kits/` – Kit-Bibliothek, Spielerinventar-Backup & Profile (`KitLibrary`, `TrainingKit`, `KitLoadout`, `MacherItems`, `TrainingSettings`, `TrainingPreset`, `TrainingProfiles`)
  - `progress/` – Spieler-Fortschritt (`pvptrainer-progress/*.jsonl`), Serien & HUD-Einstellungen (`TrainingProgress`, `DrillHistory`, `HudPreferences`)
  - `analytics/` – Bot-Telemetrie (`pvptrainer-bot/*.jsonl`), Statistik, CSV-Export & Testplan (`BotTelemetry`, `BotArchive`, `BotStatistics`, `CalibrationPlan`)
  - `ui/` – Menüs, Kit-Editor, Statistik-Ansichten und HUD-Overlays (`TrainingScreen`, `ProgressScreen`, `BotAnalysisScreen`, `StreakHud`, `OutcomeOverlay`, …)
  - `mixin/` – Vereinheitlichtes Mixin-Paket (alle 33 Mixins, gruppiert in `pvp-trainer.{arena,kits,tactics}.mixins.json`)
- `src/main/resources/` – `fabric.mod.json` (Single Source of Truth für die Version), Mixin-Konfigurationen, Standardkits (`assets/pvptrainer/kits.json`), Sprache, Texturen, Arena-Dimension
- `src/test/java/de/pvptrainer/` – schnelle Offline-Unit-Tests ohne Minecraft-Start (`MacherBotUnitTests`)
- `tests/ingame/` – zentrale In-Game-Regressionssuite (`run_ingame.py`, `SmokeProbe.java`, `CobwebProbe.java`) für isolierte und Modpack-Profile
- `build.py` – reproduzierbarer Offline-Build (feste ZIP-Zeitstempel, automatische JDK-25-Suche, Mixin-/Entrypoint-Prüfung, bündelt `../../Analysen/HT1-Testplan-1.7.html`)
- `Bibliotheken/` – lokale Offline-Bibliotheken (via `.gitignore` bewusst außerhalb von Git)

## Befehle

```powershell
# Reproduzierbarer Build + Offline-Unit-Tests
python build.py --test

# In-Game-Regression (isoliert: Fabric API + Worldthreader)
python tests/ingame/run_ingame.py --suite all --profile isolated

# In-Game-Smoke im Modpack-Profil (inkl. lokaler Profil-Mods aus MACHER(1)/mods)
python tests/ingame/run_ingame.py --suite smoke --profile modpack
```

# MacherBot · Trainingsmod (Quellstand)

Eigenständiges Git-Repository für die Fabric-Mod **MacherBot** (Minecraft Java 26.2, JDK 25, Fabric-ID `macherbot`, Kompatibilitätskennung `provides: ["pvptrainer"]`).

## Struktur

- `src/main/java/de/pvptrainer/` – vollständiger, direkt kompilierbarer Quellbaum ohne Binärbasis oder ASM-Nachpatcher
- `src/main/resources/` – `fabric.mod.json` (Single Source of Truth für die Version), Mixin-Konfigurationen, Standardkits (`assets/pvptrainer/kits.json`), Sprache, Texturen, Arena-Dimension
- `src/test/java/de/pvptrainer/` – schnelle Offline-Unit-Tests ohne Minecraft-Start
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

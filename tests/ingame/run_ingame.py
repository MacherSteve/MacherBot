"""Zentraler In-Game-Teststarter fuer MacherBot (Minecraft 26.2 + Fabric).

Beispiele:
    python Entwicklung/Trainer/tests/ingame/run_ingame.py --suite smoke --profile isolated
    python Entwicklung/Trainer/tests/ingame/run_ingame.py --suite all --profile modpack
    python Entwicklung/Trainer/tests/ingame/run_ingame.py --suite cobweb --jar Versionen/1.11.6/MacherBot-1.11.6+26.2.jar --expect-fail
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
TRAINER_ROOT = HERE.parents[1]
sys.path.insert(0, str(TRAINER_ROOT))
import build as trainer_build  # noqa: E402

SUITES = {
    "smoke": ("SmokeProbe.java", "de.pvptrainer.SmokeProbe"),
    "cobweb": ("CobwebProbe.java", "de.pvptrainer.CobwebProbe"),
}


def find_norisk_root(explicit: Path | None) -> Path:
    if explicit:
        return explicit
    env = os.environ.get("NORISK_ROOT")
    if env:
        return Path(env)
    appdata = os.environ.get("APPDATA")
    if appdata:
        candidate = Path(appdata) / "norisk" / "NoRiskClientV3"
        if (candidate / "meta").is_dir():
            return candidate
    raise FileNotFoundError("NoRiskClientV3-Ordner nicht gefunden. Setze NORISK_ROOT oder --norisk-root.")


def find_world_template(norisk_root: Path, explicit: Path | None) -> Path:
    if explicit:
        return explicit
    env = os.environ.get("MACHER_TEST_WORLD")
    if env:
        return Path(env)
    for candidate in (
        norisk_root / "data" / "profiles" / "MACHER(1)" / "saves" / "New World (10)",
        TRAINER_ROOT.parents[1] / "Privat" / "Profil" / "saves" / "New World (10)",
    ):
        if (candidate / "level.dat").is_file():
            return candidate
    raise FileNotFoundError("Keine Testwelt mit level.dat gefunden. Setze MACHER_TEST_WORLD oder --world.")


def allowed_windows(lib: dict) -> bool:
    rules = lib.get("rules")
    if rules is None:
        return True
    result = False
    for rule in rules:
        if rule.get("os", {}).get("name", "windows") == "windows":
            result = rule["action"] == "allow"
    return result


def resolve_minecraft_cp(meta: Path) -> tuple[list[Path], str]:
    mc_jsons = sorted((meta / "minecraft_cache").glob("piston_meta_*.json"))
    if not mc_jsons:
        raise FileNotFoundError(f"Kein piston_meta_*.json unter {meta / 'minecraft_cache'}")
    vanilla = json.loads(mc_jsons[0].read_text(encoding="utf-8"))
    fabric_list = json.loads((meta / "fabric_cache" / "fabric_versions_26.2.json").read_text(encoding="utf-8"))
    fabric = next(v for v in fabric_list if v["loader"]["version"] == "0.20.1")

    def maven_jar(coord: str) -> Path:
        g, n, v = coord.split(":")
        return meta / "libraries" / g.replace(".", "/") / n / v / f"{n}-{v}.jar"

    cp = [maven_jar(fabric["loader"]["maven"])]
    cp += [maven_jar(lib["name"]) for lib in fabric["launcherMeta"]["libraries"]["common"]]
    cp += [
        meta / "libraries" / lib["downloads"]["artifact"]["path"]
        for lib in vanilla["libraries"]
        if allowed_windows(lib) and "artifact" in lib.get("downloads", {})
    ]
    cp.append(meta / "versions" / "26.2" / "26.2.jar")
    missing = [str(p) for p in cp if not p.is_file()]
    if missing:
        raise FileNotFoundError(f"Fehlende Minecraft-Bibliotheken: {missing[:5]}")
    return cp, fabric["launcherMeta"]["mainClass"]["client"]


def run_single(
    suite_name: str,
    profile_mode: str,
    jar: Path,
    norisk_root: Path,
    world_template: Path,
    run_dir: Path,
    expected_version: str,
    timeout_sec: int,
    expect_fail: bool,
) -> tuple[bool, Path]:
    javac, java = trainer_build.find_jdk()
    meta = norisk_root / "meta"
    cp, main_class = resolve_minecraft_cp(meta)

    if run_dir.exists():
        shutil.rmtree(run_dir)
    mods = run_dir / "mods"
    mods.mkdir(parents=True)

    fapi = meta / "mod_cache" / "fabric-api-0.161.0+26.2.jar"
    worldthreader = meta / "mod_cache" / "Worldthreader-3.1.0.jar"
    for f in (jar, fapi, worldthreader):
        if not f.is_file():
            raise FileNotFoundError(f"Benoetigte Mod fehlt: {f}")
        dest = mods / ("trainer.jar" if f == jar else f.name)
        shutil.copyfile(f, dest)

    extra_mods: list[str] = []
    if profile_mode == "modpack":
        live_mods = norisk_root / "data" / "profiles" / "MACHER(1)" / "mods"
        for mod_jar in sorted(live_mods.glob("*.jar")):
            if mod_jar.name.lower().startswith("macherbot"):
                continue
            shutil.copyfile(mod_jar, mods / mod_jar.name)
            extra_mods.append(mod_jar.name)

    world = run_dir / "saves" / "Probe"
    shutil.copytree(world_template, world, ignore=shutil.ignore_patterns("session.lock"))
    (run_dir / "options.txt").write_text(
        "version:4903\npauseOnLostFocus:false\nonboardAccessibility:false\ntutorialStep:none\n"
        "renderDistance:3\nsimulationDistance:5\nmaxFps:30\nsoundCategory_master:0.0\n",
        encoding="utf-8",
    )

    # Extract fabric-api-base & fabric-lifecycle-events-v1 from bundled Fabric API for compiling probe
    probe_libs = [jar]
    with zipfile.ZipFile(fapi) as z:
        for prefix in ("fabric-api-base-", "fabric-lifecycle-events-v1-"):
            entry = next(n for n in z.namelist() if f"/{prefix}" in n and n.endswith(".jar"))
            extracted = run_dir / Path(entry).name
            extracted.write_bytes(z.read(entry))
            probe_libs.append(extracted)

    probe_file, probe_class = SUITES[suite_name]
    probe_classes = run_dir / "probe-classes"
    probe_classes.mkdir()
    compile_cp = [p.as_posix() for p in cp + probe_libs]
    compile_args = run_dir / "probe.javac.args"
    compile_args.write_text(
        "\n".join(["-encoding", "UTF-8", "-proc:none", "--release", "25", "-cp", f'"{os.pathsep.join(compile_cp)}"']) + "\n",
        encoding="utf-8",
    )
    comp = subprocess.run([str(javac), f"@{compile_args}", "-d", str(probe_classes), str(HERE / probe_file)], capture_output=True)
    if comp.returncode != 0:
        raise RuntimeError(f"Probe-Kompilierung fehlgeschlagen:\n{comp.stderr.decode('cp1252', errors='replace')}")

    modmeta = {
        "schemaVersion": 1,
        "id": "training_probe",
        "version": "1.0.0",
        "name": "MacherBot Regression Probe",
        "environment": "client",
        "entrypoints": {"client": [probe_class]},
        "depends": {"pvptrainer": "*"},
    }
    with zipfile.ZipFile(mods / "probe.jar", "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("fabric.mod.json", json.dumps(modmeta, indent=2))
        for cls in sorted(probe_classes.rglob("*.class")):
            z.write(cls, cls.relative_to(probe_classes).as_posix())

    jargs = [
        "-Xms256m",
        "-Xmx3g",
        "-Djava.net.useSystemProxies=false",
        "-Dhttp.proxyHost=127.0.0.1",
        "-Dhttp.proxyPort=9",
        "-Dhttps.proxyHost=127.0.0.1",
        "-Dhttps.proxyPort=9",
        f"-Dmacherbot.expectedVersion={expected_version}",
        f"-Dfabric.gameJarPath={cp[-1]}",
        "--enable-native-access=ALL-UNNAMED",
        f"-Djava.library.path={meta / 'natives' / '26.2'}",
        "-cp",
        os.pathsep.join(str(p) for p in cp),
        main_class,
        "--version",
        "26.2",
        "--gameDir",
        str(run_dir),
        "--assetsDir",
        str(meta / "assets"),
        "--assetIndex",
        "32",
        "--username",
        "TrainerTest",
        "--uuid",
        "00000000000030008000000000000001",
        "--accessToken",
        "0",
        "--versionType",
        "release",
        "--width",
        "640",
        "--height",
        "420",
        "--quickPlaySingleplayer",
        "Probe",
    ]
    args_file = run_dir / "launch.args"
    args_file.write_text("\n".join('"' + s.replace("\\", "/") + '"' for s in jargs), encoding="utf-8")

    jar_sha = hashlib.sha256(jar.read_bytes()).hexdigest()
    print(f"Starte In-Game-Suite '{suite_name}' ({profile_mode}) gegen {jar.name} (SHA-256: {jar_sha[:16]}...)")
    if extra_mods:
        print(f"  Zusaetzliche Profil-Mods: {', '.join(extra_mods)}")

    proc = subprocess.run(
        [str(java), f"@{args_file}"],
        cwd=str(run_dir),
        capture_output=True,
        timeout=timeout_sec,
    )
    (run_dir / "console.log").write_bytes(proc.stdout)
    (run_dir / "errors.log").write_bytes(proc.stderr)

    checks_path = run_dir / "checks.txt"
    checks_text = checks_path.read_text(encoding="utf-8") if checks_path.is_file() else ""
    passed = ("PASS complete" in checks_text) and ("FAIL " not in checks_text)

    if checks_text:
        print(checks_text.strip())
    else:
        print("(Keine checks.txt geschrieben; Exit-Code:", proc.returncode, ")")

    ok = (not passed) if expect_fail else passed
    status = "OK (erwarteter Fehler in Gegenprobe)" if (expect_fail and ok) else ("PASS" if ok else "FEHLGESCHLAGEN")
    print(f"Ergebnis [{suite_name}/{profile_mode}]: {status}\n")
    return ok, checks_path


def main() -> None:
    meta = trainer_build.read_metadata()
    default_jar = TRAINER_ROOT / "build" / f"MacherBot-{meta['version']}+26.2.jar"

    parser = argparse.ArgumentParser(description="Fuehrt MacherBot In-Game-Regressionstests in einem isolierten Spielprofil aus.")
    parser.add_argument("--suite", choices=["smoke", "cobweb", "all"], default="all")
    parser.add_argument("--profile", choices=["isolated", "modpack"], default="isolated")
    parser.add_argument("--jar", type=Path, default=default_jar)
    parser.add_argument("--expected-version", default=meta["version"])
    parser.add_argument("--norisk-root", type=Path)
    parser.add_argument("--world", type=Path)
    parser.add_argument("--run-root", type=Path, default=HERE / "runs")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--expect-fail", action="store_true", help="Fuer Gegenproben mit aelteren Releases")
    args = parser.parse_args()

    norisk_root = find_norisk_root(args.norisk_root)
    world_template = find_world_template(norisk_root, args.world)
    suites = ["smoke", "cobweb"] if args.suite == "all" else [args.suite]

    all_ok = True
    for s in suites:
        run_dir = args.run_root / f"{s}-{args.profile}"
        ok, _ = run_single(
            suite_name=s,
            profile_mode=args.profile,
            jar=args.jar.resolve(),
            norisk_root=norisk_root,
            world_template=world_template,
            run_dir=run_dir,
            expected_version=args.expected_version,
            timeout_sec=args.timeout,
            expect_fail=args.expect_fail,
        )
        all_ok = all_ok and ok

    if not all_ok:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

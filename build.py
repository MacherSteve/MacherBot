"""Baut die MacherBot-Trainingsmod vollstaendig und reproduzierbar aus src/main.

Aufruf:
    python Entwicklung/Trainer/build.py [Ziel.jar] [--mc 26.2|1.21.11] [--test]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parent
SRC_JAVA = ROOT / "src" / "main" / "java"
SRC_RES = ROOT / "src" / "main" / "resources"
SRC_MC12111_JAVA = ROOT / "src" / "mc12111" / "java"
TEST_JAVA = ROOT / "src" / "test" / "java"
REMAP_TOOL_SRC = ROOT / "tools" / "RemapTool.java"
CACHE_12111 = ROOT / "Bibliotheken-1.21.11"
TESTPLAN_SOURCE = ROOT.parents[1] / "Analysen" / "HT1-Testplan-1.7.html"
TESTPLAN_JAR_PATH = "assets/pvptrainer/ht1-testplan.html"
DETERMINISTIC_ZIP_TIME = (2026, 1, 1, 0, 0, 0)
MIN_JAVA_MAJOR = 25
SUPPORTED_MC = ("26.2", "1.21.11")


def java_major(javac: Path) -> int | None:
    if not javac.is_file():
        return None
    proc = subprocess.run([str(javac), "-version"], capture_output=True)
    text = (proc.stdout + proc.stderr).decode("utf-8", errors="replace")
    match = re.search(r"\b(\d+)(?:\.\d+)?", text)
    return int(match.group(1)) if match else None


def find_jdk(min_major: int = MIN_JAVA_MAJOR, prefer_exact: int | None = None) -> tuple[Path, Path]:
    exe_javac = "javac.exe" if os.name == "nt" else "javac"
    exe_java = "java.exe" if os.name == "nt" else "java"
    candidates: list[Path] = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin")
    which = shutil.which("javac")
    if which:
        candidates.append(Path(which).resolve().parent)
    home = Path.home()
    for base in (
        Path(r"C:\Program Files\Java"),
        Path(r"C:\Program Files\Eclipse Adoptium"),
        home / ".jdks",
        home / ".codex" / ".chatgpt-projects",
    ):
        if base.is_dir():
            for p in sorted(base.rglob(exe_javac)):
                candidates.append(p.parent)

    seen: set[Path] = set()
    usable: list[tuple[int, Path, Path]] = []
    for bindir in candidates:
        resolved = bindir.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        javac = resolved / exe_javac
        java = resolved / exe_java
        major = java_major(javac)
        if major is not None and major >= min_major and java.is_file():
            usable.append((major, javac, java))

    if prefer_exact is not None:
        for major, javac, java in usable:
            if major == prefer_exact:
                return javac, java
    if usable:
        return usable[0][1], usable[0][2]

    raise RuntimeError(
        f"Kein JDK >= {min_major} gefunden. Setze JAVA_HOME auf ein JDK {min_major}."
    )


def load_classpath() -> list[str]:
    entries = []
    for line in (ROOT / "klassenpfad.txt").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        path = ROOT / line
        if not path.is_file():
            raise FileNotFoundError(f"Bibliothek fehlt im Offline-Klassenpfad: {path}")
        entries.append(path.as_posix())
    return entries


def find_norisk_root() -> Path:
    env = os.environ.get("NORISK_ROOT")
    if env:
        return Path(env)
    appdata = os.environ.get("APPDATA")
    if appdata:
        candidate = Path(appdata) / "norisk" / "NoRiskClientV3"
        if (candidate / "meta").is_dir():
            return candidate
    raise FileNotFoundError("NoRiskClientV3-Ordner nicht gefunden. Setze NORISK_ROOT.")


def _find_gradle_jar(modules_root: Path, group: str, artifact: str) -> Path:
    base = modules_root / group / artifact
    if not base.is_dir():
        raise FileNotFoundError(f"Gradle-Bibliothek fehlt: {group}:{artifact} unter {base}")
    jars = [j for j in base.rglob("*.jar") if not j.name.endswith(("-sources.jar", "-javadoc.jar"))]
    if not jars:
        raise FileNotFoundError(f"Keine JAR gefunden fuer {group}:{artifact} unter {base}")
    return sorted(jars)[-1]


def _allowed_windows(lib: dict) -> bool:
    rules = lib.get("rules")
    if rules is None:
        return True
    result = False
    for rule in rules:
        if rule.get("os", {}).get("name", "windows") == "windows":
            result = rule["action"] == "allow"
    return result


def resolve_mc12111_env(javac: Path, java: Path) -> dict:
    """Loest alle lokalen 1.21.11 Mojmap-/Intermediary-/TinyRemapper-Dateien auf und baut den FAPI-Cache."""
    home = Path.home()
    gradle_modules = home / ".gradle" / "caches" / "modules-2" / "files-2.1"
    loom_root = home / ".gradle" / "caches" / "fabric-loom"
    minecraft_maven = loom_root / "minecraftMaven"
    norisk_meta = find_norisk_root() / "meta"

    mappings_candidates = sorted((loom_root / "1.21.11").rglob("mappings.tiny"))
    if not mappings_candidates:
        raise FileNotFoundError(f"1.21.11 mappings.tiny nicht gefunden unter {loom_root / '1.21.11'}")
    mappings = mappings_candidates[-1]

    mc_common = sorted(minecraft_maven.rglob("minecraft-common-1.21.11-*.jar"))[-1]
    mc_client = sorted(minecraft_maven.rglob("minecraft-clientonly-1.21.11-*.jar"))[-1]
    mc_common_int = sorted(minecraft_maven.rglob("minecraft-common-intermediary-1.21.11-*.jar"))[-1]
    mc_client_int = sorted(minecraft_maven.rglob("minecraft-clientonly-intermediary-1.21.11-*.jar"))[-1]

    remapper_cp = [
        _find_gradle_jar(gradle_modules, "net.fabricmc", "tiny-remapper"),
        _find_gradle_jar(gradle_modules, "net.fabricmc", "mapping-io"),
        _find_gradle_jar(gradle_modules, "org.ow2.asm", "asm"),
        _find_gradle_jar(gradle_modules, "org.ow2.asm", "asm-commons"),
        _find_gradle_jar(gradle_modules, "org.ow2.asm", "asm-tree"),
        _find_gradle_jar(gradle_modules, "org.ow2.asm", "asm-util"),
        _find_gradle_jar(gradle_modules, "org.ow2.asm", "asm-analysis"),
    ]

    piston = next(
        (
            p
            for p in sorted((norisk_meta / "minecraft_cache").glob("piston_meta_*.json"))
            if json.loads(p.read_text(encoding="utf-8")).get("id") == "1.21.11"
        ),
        None,
    )
    if piston is None:
        raise FileNotFoundError(f"Kein piston_meta fuer 1.21.11 unter {norisk_meta / 'minecraft_cache'}")
    vanilla = json.loads(piston.read_text(encoding="utf-8"))

    fabric_list = json.loads((norisk_meta / "fabric_cache" / "fabric_versions_1.21.11.json").read_text(encoding="utf-8"))
    fabric = next(
        (v for v in fabric_list if v["loader"]["version"] == "0.20.1"),
        fabric_list[0],
    )

    def maven_jar(coord: str) -> Path:
        g, n, v = coord.split(":")
        return norisk_meta / "libraries" / g.replace(".", "/") / n / v / f"{n}-{v}.jar"

    base_libs = [maven_jar(fabric["loader"]["maven"])]
    base_libs += [maven_jar(lib["name"]) for lib in fabric["launcherMeta"]["libraries"]["common"]]
    base_libs += [
        norisk_meta / "libraries" / lib["downloads"]["artifact"]["path"]
        for lib in vanilla["libraries"]
        if _allowed_windows(lib) and "artifact" in lib.get("downloads", {})
    ]
    base_libs.append(_find_gradle_jar(gradle_modules, "io.github.llamalad7", "mixinextras-fabric"))
    base_libs = [p for p in base_libs if p.is_file()]

    fapi_jars = sorted((norisk_meta / "mod_cache").glob("fabric-api-*+1.21.11.jar"))
    if not fapi_jars:
        raise FileNotFoundError(f"Kein fabric-api-*+1.21.11.jar unter {norisk_meta / 'mod_cache'}")
    fapi_jar = fapi_jars[-1]

    # Cache: kompiliertes RemapTool + nach named gemapptes Fabric-API-Archiv
    CACHE_12111.mkdir(parents=True, exist_ok=True)
    tool_classes = CACHE_12111 / "remap-tool-classes"
    tool_class = tool_classes / "RemapTool.class"
    if not tool_class.is_file() or tool_class.stat().st_mtime < REMAP_TOOL_SRC.stat().st_mtime:
        if tool_classes.exists():
            shutil.rmtree(tool_classes)
        tool_classes.mkdir(parents=True)
        compile_java(javac, tool_classes, [REMAP_TOOL_SRC], [p.as_posix() for p in remapper_cp], release=21)

    fapi_named = CACHE_12111 / f"{fapi_jar.stem}-named.jar"
    if not fapi_named.is_file():
        with tempfile.TemporaryDirectory(prefix="macher-fapi12111-") as tmp:
            fapi_merged = Path(tmp) / "fapi-merged-int.jar"
            seen: set[str] = set()
            with ZipFile(fapi_jar) as src, ZipFile(fapi_merged, "w", ZIP_DEFLATED) as dst:
                for name in sorted(src.namelist()):
                    if name.startswith("META-INF/jars/") and name.endswith(".jar"):
                        sub_p = Path(tmp) / Path(name).name
                        sub_p.write_bytes(src.read(name))
                        with ZipFile(sub_p) as sz:
                            for sn in sorted(sz.namelist()):
                                if sn.endswith(".class") and sn not in seen:
                                    seen.add(sn)
                                    dst.writestr(sn, sz.read(sn))
            run_remap_tool(
                java=java,
                tool_classes=tool_classes,
                remapper_cp=remapper_cp,
                input_path=fapi_merged,
                output_path=fapi_named,
                mappings=mappings,
                from_ns="intermediary",
                to_ns="named",
                classpath=[mc_common_int, mc_client_int, *base_libs],
                mixins=False,
            )

    compile_cp = [mc_common, mc_client, fapi_named, *base_libs]
    return {
        "mappings": mappings,
        "mc_common": mc_common,
        "mc_client": mc_client,
        "mc_common_int": mc_common_int,
        "mc_client_int": mc_client_int,
        "fapi_jar": fapi_jar,
        "fapi_named": fapi_named,
        "base_libs": base_libs,
        "compile_cp": compile_cp,
        "remapper_cp": remapper_cp,
        "tool_classes": tool_classes,
    }


def run_remap_tool(
    java: Path,
    tool_classes: Path,
    remapper_cp: list[Path],
    input_path: Path,
    output_path: Path,
    mappings: Path,
    from_ns: str,
    to_ns: str,
    classpath: list[Path],
    mixins: bool,
) -> None:
    cp_str = os.pathsep.join([str(tool_classes), *(str(p) for p in remapper_cp)])
    cmd = [
        str(java),
        "-cp",
        cp_str,
        "RemapTool",
        str(input_path),
        str(output_path),
        str(mappings),
        from_ns,
        to_ns,
    ]
    if mixins:
        cmd.append("--mixins")
    cmd.extend(str(p) for p in classpath)
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        out = proc.stdout.decode("utf-8", errors="replace")
        err = proc.stderr.decode("utf-8", errors="replace")
        raise RuntimeError(f"RemapTool fehlgeschlagen (Exit {proc.returncode}):\n{out}\n{err}")


def transform_ui_source_12111(text: str) -> str:
    """Ueberfuehrt MC-26.2-GuiGraphicsExtractor-Aufrufe in de/pvptrainer/ui/*.java deterministisch nach MC 1.21.11."""
    if "GuiGraphicsExtractor" not in text and "PlayerFaceExtractor" not in text:
        return text

    receivers = sorted(set(re.findall(r"\bGuiGraphicsExtractor\s+([A-Za-z_]\w*)", text)))
    out = text
    out = out.replace("net.minecraft.client.gui.GuiGraphicsExtractor", "net.minecraft.client.gui.GuiGraphics")
    out = out.replace("net.minecraft.client.gui.components.PlayerFaceExtractor", "net.minecraft.client.gui.components.PlayerFaceRenderer")
    out = out.replace("PlayerFaceExtractor.extractRenderState(", "PlayerFaceRenderer.draw(")
    out = out.replace("GuiGraphicsExtractor", "GuiGraphics")
    out = out.replace("extractRenderState(", "render(")
    out = out.replace("extractContents(", "renderContents(")
    out = out.replace("extractWidgetRenderState(", "renderWidget(")

    if receivers:
        rec_pat = "|".join(re.escape(r) for r in receivers)
        replacements = (
            ("textWithWordWrap", "drawWordWrap"),
            ("itemDecorations", "renderItemDecorations"),
            ("outline", "renderOutline"),
            ("item", "renderItem"),
            ("text", "drawString"),
        )
        for old_m, new_m in replacements:
            out = re.sub(rf"\b({rec_pat})\.{old_m}\(", rf"\1.{new_m}(", out)
    return out


def stage_sources_12111(staging_dir: Path) -> list[Path]:
    """Kombiniert src/main/java + src/mc12111/java Overlay + UI-Transform fuer MC 1.21.11."""
    overlay_rel = {
        p.relative_to(SRC_MC12111_JAVA).as_posix(): p
        for p in sorted(SRC_MC12111_JAVA.rglob("*.java"))
    }
    staged: list[Path] = []
    seen: set[str] = set()
    for src in sorted(SRC_JAVA.rglob("*.java")):
        rel = src.relative_to(SRC_JAVA).as_posix()
        seen.add(rel)
        dest = staging_dir / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        if rel in overlay_rel:
            shutil.copyfile(overlay_rel[rel], dest)
        elif rel.startswith("de/pvptrainer/ui/"):
            content = src.read_text(encoding="utf-8")
            dest.write_text(transform_ui_source_12111(content), encoding="utf-8")
        else:
            shutil.copyfile(src, dest)
        staged.append(dest)
    for rel, ov in sorted(overlay_rel.items()):
        if rel not in seen:
            dest = staging_dir / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ov, dest)
            staged.append(dest)
    return sorted(staged)


def build_resources_for_mc(mc_version: str) -> tuple[dict[str, bytes], dict]:
    """Erzeugt deterministische Ressourcen fuer 26.2 oder 1.21.11 aus Single Source of Truth."""
    meta = read_metadata()
    entries: dict[str, bytes] = {}
    for file in SRC_RES.rglob("*"):
        if file.is_file():
            entries[file.relative_to(SRC_RES).as_posix()] = file.read_bytes()
    entries[TESTPLAN_JAR_PATH] = TESTPLAN_SOURCE.read_bytes()

    if mc_version == "1.21.11":
        meta_12111 = json.loads(json.dumps(meta))
        meta_12111["depends"] = {
            "fabricloader": ">=0.18.0",
            "minecraft": "1.21.11",
            "java": ">=21",
            "fabric-api": ">=0.141.0",
        }
        entries["fabric.mod.json"] = (json.dumps(meta_12111, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
        for mixin_file in meta_12111.get("mixins", []):
            m = json.loads(entries[mixin_file].decode("utf-8"))
            m["compatibilityLevel"] = "JAVA_21"
            if mixin_file == "pvp-trainer.arena.mixins.json":
                client_list = list(m.get("client", []))
                if "GameRendererAccess" not in client_list:
                    client_list.append("GameRendererAccess")
                m["client"] = client_list
            entries[mixin_file] = (json.dumps(m, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
        return entries, meta_12111

    return entries, meta


def compile_java(javac: Path, output: Path, files: list[Path], classpath: list[str], release: int = MIN_JAVA_MAJOR) -> None:
    if not files:
        raise RuntimeError("Keine Java-Quelldateien gefunden.")
    args = output.parent / f"{output.name}.javac.args"
    cp = os.pathsep.join(classpath)
    args.write_text(
        "\n".join(["-encoding", "UTF-8", "-proc:none", "--release", str(release), "-cp", f'"{cp}"']) + "\n",
        encoding="utf-8",
    )
    proc = subprocess.run([str(javac), f"@{args}", "-d", str(output), *[str(f) for f in files]], capture_output=True)
    if proc.returncode != 0:
        err = proc.stderr.decode("cp1252", errors="replace")
        out = proc.stdout.decode("cp1252", errors="replace")
        raise RuntimeError(f"javac fehlgeschlagen (Exit {proc.returncode}):\n{out}\n{err}")


def read_metadata() -> dict:
    return json.loads((SRC_RES / "fabric.mod.json").read_text(encoding="utf-8"))


def run_unit_tests(javac: Path, java: Path, main_classes: Path, libs: list[str], release: int = MIN_JAVA_MAJOR) -> None:
    test_files = sorted(TEST_JAVA.rglob("*.java")) if TEST_JAVA.is_dir() else []
    if not test_files:
        print("Keine Unit-Tests unter src/test/java gefunden.")
        return
    with tempfile.TemporaryDirectory(prefix="macher-tests-") as work:
        test_classes = Path(work) / "test-classes"
        test_classes.mkdir()
        cp = [main_classes.as_posix(), SRC_RES.as_posix(), *libs]
        compile_java(javac, test_classes, test_files, cp, release=release)
        run_cp = os.pathsep.join([test_classes.as_posix(), *cp])
        proc = subprocess.run(
            [str(java), "-ea", "-cp", run_cp, "de.pvptrainer.MacherBotUnitTests"],
            cwd=work,
            capture_output=True,
        )
        stdout = proc.stdout.decode("utf-8", errors="replace")
        stderr = proc.stderr.decode("utf-8", errors="replace")
        if stdout:
            print(stdout, end="")
        if proc.returncode != 0:
            raise RuntimeError(f"Unit-Tests fehlgeschlagen:\n{stderr}")


def write_deterministic_jar(target: Path, entries: dict[str, bytes]) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(target, "w", ZIP_DEFLATED) as z:
        for name in sorted(entries):
            info = ZipInfo(name, date_time=DETERMINISTIC_ZIP_TIME)
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, entries[name])


def verify_jar(target: Path, meta: dict) -> None:
    with ZipFile(target) as z:
        bad = z.testzip()
        if bad is not None:
            raise AssertionError(f"Defekter ZIP-Eintrag: {bad}")
        names = z.namelist()
        if len(names) != len(set(names)):
            raise AssertionError("Doppelte ZIP-Eintraege gefunden.")
        name_set = set(names)
        for mixin_file in meta.get("mixins", []):
            if mixin_file not in name_set:
                raise AssertionError(f"Mixin-Konfiguration fehlt: {mixin_file}")
            m = json.loads(z.read(mixin_file))
            prefix = m["package"].replace(".", "/") + "/"
            for cls in m.get("mixins", []) + m.get("client", []) + m.get("server", []):
                class_path = prefix + cls.replace(".", "/") + ".class"
                if class_path not in name_set:
                    raise AssertionError(f"Mixin-Klasse fehlt in JAR: {class_path}")
        for entries in meta.get("entrypoints", {}).values():
            for ep in entries:
                cls_name = ep["value"] if isinstance(ep, dict) else ep
                class_path = cls_name.replace(".", "/") + ".class"
                if class_path not in name_set:
                    raise AssertionError(f"Entrypoint-Klasse fehlt in JAR: {class_path}")
        if TESTPLAN_JAR_PATH not in name_set:
            raise AssertionError(f"Testplan fehlt in JAR: {TESTPLAN_JAR_PATH}")


def assemble(target: Path | None = None, run_tests: bool = False, mc_version: str = "26.2") -> Path:
    if mc_version not in SUPPORTED_MC:
        raise ValueError(f"Unbekannte Minecraft-Version: {mc_version} (unterstuetzt: {', '.join(SUPPORTED_MC)})")

    if not TESTPLAN_SOURCE.is_file():
        raise FileNotFoundError(
            f"Single Source of Truth fuer den Testplan fehlt: {TESTPLAN_SOURCE}"
        )

    res_entries, meta = build_resources_for_mc(mc_version)
    version = meta["version"]
    if target is None:
        target = ROOT / "build" / f"MacherBot-{version}+{mc_version}.jar"

    if mc_version == "26.2":
        javac, java = find_jdk(min_major=25)
        libs = load_classpath()
        with tempfile.TemporaryDirectory(prefix="macher-build-") as work:
            work_dir = Path(work)
            classes = work_dir / "classes"
            classes.mkdir()
            sources = sorted(SRC_JAVA.rglob("*.java"))
            compile_java(javac, classes, sources, libs, release=25)

            if run_tests:
                run_unit_tests(javac, java, classes, libs, release=25)

            entries: dict[str, bytes] = dict(res_entries)
            for file in classes.rglob("*.class"):
                entries[file.relative_to(classes).as_posix()] = file.read_bytes()

            write_deterministic_jar(target, entries)
            verify_jar(target, meta)
    else:
        javac, java = find_jdk(min_major=21, prefer_exact=21)
        env = resolve_mc12111_env(javac, java)
        libs = [p.as_posix() for p in env["compile_cp"]]
        with tempfile.TemporaryDirectory(prefix="macher-build-12111-") as work:
            work_dir = Path(work)
            staged_src = work_dir / "src"
            named_classes = work_dir / "classes-named"
            intermediary_classes = work_dir / "classes-intermediary"
            staged_src.mkdir()
            named_classes.mkdir()
            intermediary_classes.mkdir()

            sources = stage_sources_12111(staged_src)
            compile_java(javac, named_classes, sources, libs, release=21)

            if run_tests:
                run_unit_tests(javac, java, named_classes, libs, release=21)

            run_remap_tool(
                java=java,
                tool_classes=env["tool_classes"],
                remapper_cp=env["remapper_cp"],
                input_path=named_classes,
                output_path=intermediary_classes,
                mappings=env["mappings"],
                from_ns="named",
                to_ns="intermediary",
                classpath=env["compile_cp"],
                mixins=True,
            )

            entries = dict(res_entries)
            for file in intermediary_classes.rglob("*.class"):
                entries[file.relative_to(intermediary_classes).as_posix()] = file.read_bytes()

            write_deterministic_jar(target, entries)
            verify_jar(target, meta)

    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    print(f"Gebaut: {target} (Version {version}, MC {mc_version}, {len(entries)} Eintraege, SHA-256: {digest})")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description="Baut die MacherBot-Trainingsmod aus src/main.")
    parser.add_argument("target", nargs="?", type=Path, help="Zielpfad der JAR (Standard: build/MacherBot-<version>+<mc>.jar)")
    parser.add_argument("--mc", choices=SUPPORTED_MC, default="26.2", help="Ziel-Minecraft-Version (Standard: 26.2)")
    parser.add_argument("--test", action="store_true", help="Fuehrt nach dem Kompilieren die Unit-Tests aus")
    args = parser.parse_args()
    assemble(args.target, run_tests=args.test, mc_version=args.mc)


if __name__ == "__main__":
    main()

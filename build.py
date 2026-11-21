"""Baut die MacherBot-Trainingsmod vollstaendig und reproduzierbar aus src/main.

Aufruf:
    python Entwicklung/Trainer/build.py [Ziel.jar] [--test]
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
TEST_JAVA = ROOT / "src" / "test" / "java"
TESTPLAN_SOURCE = ROOT.parents[1] / "Analysen" / "HT1-Testplan-1.7.html"
TESTPLAN_JAR_PATH = "assets/pvptrainer/ht1-testplan.html"
DETERMINISTIC_ZIP_TIME = (2026, 1, 1, 0, 0, 0)
MIN_JAVA_MAJOR = 25


def java_major(javac: Path) -> int | None:
    if not javac.is_file():
        return None
    proc = subprocess.run([str(javac), "-version"], capture_output=True)
    text = (proc.stdout + proc.stderr).decode("utf-8", errors="replace")
    match = re.search(r"\b(\d+)(?:\.\d+)?", text)
    return int(match.group(1)) if match else None


def find_jdk() -> tuple[Path, Path]:
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
    for bindir in candidates:
        resolved = bindir.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        javac = resolved / exe_javac
        java = resolved / exe_java
        major = java_major(javac)
        if major is not None and major >= MIN_JAVA_MAJOR and java.is_file():
            return javac, java

    raise RuntimeError(
        f"Kein JDK >= {MIN_JAVA_MAJOR} gefunden. Setze JAVA_HOME auf ein JDK {MIN_JAVA_MAJOR}."
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


def compile_java(javac: Path, output: Path, files: list[Path], classpath: list[str]) -> None:
    if not files:
        raise RuntimeError("Keine Java-Quelldateien gefunden.")
    args = output.parent / f"{output.name}.javac.args"
    cp = os.pathsep.join(classpath)
    args.write_text(
        "\n".join(["-encoding", "UTF-8", "-proc:none", "--release", str(MIN_JAVA_MAJOR), "-cp", f'"{cp}"']) + "\n",
        encoding="utf-8",
    )
    proc = subprocess.run([str(javac), f"@{args}", "-d", str(output), *[str(f) for f in files]], capture_output=True)
    if proc.returncode != 0:
        err = proc.stderr.decode("cp1252", errors="replace")
        out = proc.stdout.decode("cp1252", errors="replace")
        raise RuntimeError(f"javac fehlgeschlagen (Exit {proc.returncode}):\n{out}\n{err}")


def read_metadata() -> dict:
    return json.loads((SRC_RES / "fabric.mod.json").read_text(encoding="utf-8"))


def run_unit_tests(javac: Path, java: Path, main_classes: Path, libs: list[str]) -> None:
    test_files = sorted(TEST_JAVA.rglob("*.java")) if TEST_JAVA.is_dir() else []
    if not test_files:
        print("Keine Unit-Tests unter src/test/java gefunden.")
        return
    with tempfile.TemporaryDirectory(prefix="macher-tests-") as work:
        test_classes = Path(work) / "test-classes"
        test_classes.mkdir()
        cp = [main_classes.as_posix(), SRC_RES.as_posix(), *libs]
        compile_java(javac, test_classes, test_files, cp)
        run_cp = os.pathsep.join([test_classes.as_posix(), *cp])
        proc = subprocess.run(
            [str(java), "-ea", "-cp", run_cp, "de.pvptrainer.MacherBotUnitTests"],
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


def assemble(target: Path | None = None, run_tests: bool = False) -> Path:
    javac, java = find_jdk()
    libs = load_classpath()
    meta = read_metadata()
    version = meta["version"]
    if target is None:
        target = ROOT / "build" / f"MacherBot-{version}+26.2.jar"

    if not TESTPLAN_SOURCE.is_file():
        raise FileNotFoundError(
            f"Single Source of Truth fuer den Testplan fehlt: {TESTPLAN_SOURCE}"
        )

    with tempfile.TemporaryDirectory(prefix="macher-build-") as work:
        work_dir = Path(work)
        classes = work_dir / "classes"
        classes.mkdir()
        sources = sorted(SRC_JAVA.rglob("*.java"))
        compile_java(javac, classes, sources, libs)

        if run_tests:
            run_unit_tests(javac, java, classes, libs)

        entries: dict[str, bytes] = {}
        for file in classes.rglob("*.class"):
            entries[file.relative_to(classes).as_posix()] = file.read_bytes()
        for file in SRC_RES.rglob("*"):
            if file.is_file():
                entries[file.relative_to(SRC_RES).as_posix()] = file.read_bytes()
        entries[TESTPLAN_JAR_PATH] = TESTPLAN_SOURCE.read_bytes()

        write_deterministic_jar(target, entries)
        verify_jar(target, meta)

    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    print(f"Gebaut: {target} (Version {version}, {len(entries)} Eintraege, SHA-256: {digest})")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description="Baut die MacherBot-Trainingsmod aus src/main.")
    parser.add_argument("target", nargs="?", type=Path, help="Zielpfad der JAR (Standard: build/MacherBot-<version>+26.2.jar)")
    parser.add_argument("--test", action="store_true", help="Fuehrt nach dem Kompilieren die Unit-Tests aus")
    args = parser.parse_args()
    assemble(args.target, run_tests=args.test)


if __name__ == "__main__":
    main()

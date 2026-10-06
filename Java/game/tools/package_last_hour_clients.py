#!/usr/bin/env python3
"""Builds one download package per platform for The Last Hour pilot clients.

Each package contains the client JAR, a pinned Temurin 25 JRE, the matching FFmpeg
executable, a start script that asks for the study ID (7, p007 and P007 all become P007),
and a short guide.

Usage (from Java/):
    ./gradlew :game:buildTheLastHourJar :game:downloadLastHourFfmpeg
    python game/tools/package_last_hour_clients.py

Output: game/build/distributions/TheLastHour-<platform>.(zip|tar.gz)
JRE downloads are cached, checksum-verified, in game/local-tools/jre/ (ignored by Git).
"""

from __future__ import annotations

import hashlib
import io
import sys
import time
import tarfile
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

GAME = Path(__file__).resolve().parents[1]
JAR = GAME / "build" / "libs" / "TheLastHour.jar"
FFMPEG = GAME / "local-tools" / "ffmpeg"
JRE_CACHE = GAME / "local-tools" / "jre"
OUT = GAME / "build" / "distributions"
ROOT = "TheLastHour"
JRE_RELEASE = "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4%2B7/"


@dataclass(frozen=True)
class Target:
    platform: str  # FFmpeg directory name used by BundledFfmpeg
    jre_asset: str
    jre_sha256: str
    launcher: str

    @property
    def windows(self) -> bool:
        return self.platform.startswith("windows")

    @property
    def mac(self) -> bool:
        return self.platform.startswith("macos")


TARGETS = [
    Target(
        "windows-x86_64",
        "OpenJDK25U-jre_x64_windows_hotspot_25.0.4_7.zip",
        "5b0d58f043f762fa3ee6cc12b6774b59b245cafdcb357e45ce61f822aa9a56cb",
        "Start-TheLastHour.bat",
    ),
    Target(
        "macos-aarch64",
        "OpenJDK25U-jre_aarch64_mac_hotspot_25.0.4_7.tar.gz",
        "bc5c721d4475b328e50cc0fbbe3319773db374716836e7caa8fb4398c1f90eba",
        "Start-TheLastHour.command",
    ),
    Target(
        "macos-x86_64",
        "OpenJDK25U-jre_x64_mac_hotspot_25.0.4_7.tar.gz",
        "1603a51392d6e4aa66dea50f60cc725d40ed77c05bf7557096e5003807236296",
        "Start-TheLastHour.command",
    ),
    Target(
        "linux-x86_64",
        "OpenJDK25U-jre_x64_linux_hotspot_25.0.4_7.tar.gz",
        "aed3915f8facc0c80733ab2448bb0df4b494a36a2c5759e9a6e1eb979720f2b3",
        "start-thelasthour.sh",
    ),
    Target(
        "linux-aarch64",
        "OpenJDK25U-jre_aarch64_linux_hotspot_25.0.4_7.tar.gz",
        "1f2644427000316bc431df3389504551ed7464fe8486bf6b4f1130af9ffc8f55",
        "start-thelasthour.sh",
    ),
]

WINDOWS_LAUNCHER = r"""@echo off
setlocal
cd /d "%~dp0"
set /a TRIES=0
:ask
set /a TRIES+=1
if %TRIES% gtr 5 (
  echo Keine gueltige Kennung erhalten. Bitte die Versuchsleitung fragen.
  pause
  exit /b 1
)
set "INPUT="
set "STUDY_ID="
set /p "INPUT=Studienkennung eingeben (z. B. 7 oder P007): "
rem P or p with three digits is kept; one to three digits are padded to P###.
echo(%INPUT%| findstr /r /x /i "p[0-9][0-9][0-9]" >nul && set "STUDY_ID=P%INPUT:~1%"
echo(%INPUT%| findstr /r /x "[0-9] [0-9][0-9] [0-9][0-9][0-9]" >nul && (
  set "PADDED=00%INPUT%"
  call set "STUDY_ID=P%%PADDED:~-3%%"
)
if not defined STUDY_ID (
  echo Ungueltige Kennung. Bitte die Nummer eingeben, z. B. 7 oder P007.
  goto ask
)
echo Kennung: %STUDY_ID%
if defined LAST_HOUR_RECORDING_DIR goto recording_ready
set "LAST_HOUR_RECORDING_DIR=%~dp0recordings"
if not exist "%LAST_HOUR_RECORDING_DIR%" mkdir "%LAST_HOUR_RECORDING_DIR%" 2>nul
set "RECORDING_PROBE=%LAST_HOUR_RECORDING_DIR%\.write-test-%RANDOM%-%RANDOM%.tmp"
(type nul > "%RECORDING_PROBE%") 2>nul
if not exist "%RECORDING_PROBE%" (
  set "LAST_HOUR_RECORDING_DIR=%USERPROFILE%\TheLastHour\recordings"
) else (
  del "%RECORDING_PROBE%"
)
:recording_ready
if not exist "%LAST_HOUR_RECORDING_DIR%\logs" mkdir "%LAST_HOUR_RECORDING_DIR%\logs"
"%~dp0runtime\bin\java.exe" "-Dlasthour.recording.dir=%LAST_HOUR_RECORDING_DIR%" "-XX:ErrorFile=%LAST_HOUR_RECORDING_DIR%\logs\hs_err_pid%%p.log" "-Dlasthour.recording.studyId=%STUDY_ID%" -jar "%~dp0TheLastHour.jar"
if errorlevel 1 (
  echo.
  echo Das Spiel wurde mit einem Fehler beendet. Bitte dieses Fenster der Versuchsleitung zeigen.
  pause
)
"""

UNIX_LAUNCHER = """#!/bin/sh
set -eu
cd "$(dirname "$0")"
{quarantine}# P or p with three digits is kept; one to three digits are padded to P###.
while :; do
  printf 'Studienkennung eingeben (z. B. 7 oder P007): '
  read -r INPUT || exit 1
  case "$INPUT" in
    [Pp][0-9][0-9][0-9]) STUDY_ID="P${{INPUT#?}}"; break ;;
    [0-9]) STUDY_ID="P00$INPUT"; break ;;
    [0-9][0-9]) STUDY_ID="P0$INPUT"; break ;;
    [0-9][0-9][0-9]) STUDY_ID="P$INPUT"; break ;;
    *) echo "Ungültige Kennung. Bitte die Nummer eingeben, z. B. 7 oder P007." ;;
  esac
done
echo "Kennung: $STUDY_ID"
RECORDING_ROOT="${{LAST_HOUR_RECORDING_DIR:-$PWD/recordings}}"
if [ -z "${{LAST_HOUR_RECORDING_DIR:-}}" ]; then
  if mkdir -p "$RECORDING_ROOT" 2>/dev/null && PROBE=$(mktemp "$RECORDING_ROOT/.write-test.XXXXXX" 2>/dev/null); then
    rm "$PROBE"
  else
    RECORDING_ROOT="$HOME/TheLastHour/recordings"
  fi
fi
mkdir -p "$RECORDING_ROOT/logs"
exec ./{java} "-Dlasthour.recording.dir=$RECORDING_ROOT" "-XX:ErrorFile=$RECORDING_ROOT/logs/hs_err_pid%p.log" "-Dlasthour.recording.studyId=$STUDY_ID" -jar TheLastHour.jar
"""

# Downloaded files carry macOS quarantine; unsigned FFmpeg would otherwise be blocked.
MAC_QUARANTINE = "xattr -dr com.apple.quarantine . 2>/dev/null || true\n"

GUIDE = """The Last Hour: Kurzanleitung
============================

1. Archiv vollständig entpacken und den Ordner TheLastHour öffnen.
2. Starten:
   - Windows: Start-TheLastHour.bat doppelklicken. Erscheint "Der Computer wurde durch
     Windows geschützt", auf "Weitere Informationen" und dann "Trotzdem ausführen" klicken.
   - macOS: Start-TheLastHour.command beim ersten Mal mit Rechtsklick > Öffnen starten
     und die Nachfrage bestätigen.
   - Linux: im Terminal ./start-thelasthour.sh ausführen.
3. Die Studienkennung eingeben, die du von der Versuchsleitung bekommen hast. Die Nummer
   reicht, z. B. 7 für P007.
4. Im Spiel "Join Game" wählen. Adresse und Port sind voreingestellt. Einen Spielernamen
   ohne echten Namen eintragen und bestätigen.
5. Gib im Spiel keine eigenen Namen, Passwörter oder sonstigen persönlichen Daten ein.
6. Am Spielende nichts schließen. Das Spiel speichert kurz und öffnet danach im Browser
   den Rückblick mit Fragen zu kurzen Ausschnitten aus deinem Spiel.

Aufgezeichnet wird nur das Spielbild, kein Ton, keine Webcam und nicht der übrige Desktop.
Bitte mindestens 2 GB freien Speicher pro Spielstunde bereithalten.
Aufnahmen und technische Logs liegen im Ordner recordings neben TheLastHour.jar.
Falls dieser Ordner nicht beschreibbar ist, liegen sie im Benutzerordner unter
TheLastHour/recordings. Den Spielordner bis zum Abschluss der Befragung behalten.
"""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def jre_archive(target: Target) -> Path:
    """Returns the cached JRE archive, downloading and verifying it when needed."""
    path = JRE_CACHE / target.jre_asset
    if path.is_file() and sha256(path) == target.jre_sha256:
        return path
    JRE_CACHE.mkdir(parents=True, exist_ok=True)
    partial = path.with_suffix(path.suffix + ".part")
    print(f"  lade {target.jre_asset}")
    request = urllib.request.Request(JRE_RELEASE + target.jre_asset, headers={"User-Agent": "TheLastHour"})
    with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as out:
        while block := response.read(1 << 20):
            out.write(block)
    if sha256(partial) != target.jre_sha256:
        partial.unlink()
        sys.exit(f"Prüfsumme falsch für {target.jre_asset}")
    partial.replace(path)
    return path


# No -XstartOnFirstThread on macOS: GameLoop uses useGlfwAsync(), and both together crash with
# "trace trap" when the window opens.
def launcher(target: Target) -> str:
    if target.windows:
        return WINDOWS_LAUNCHER.replace("\n", "\r\n")
    java = "runtime/Contents/Home/bin/java" if target.mac else "runtime/bin/java"
    return UNIX_LAUNCHER.format(
        quarantine=MAC_QUARANTINE if target.mac else "",
        java=java,
    )


def runtime_path(name: str) -> str | None:
    """Maps a JRE archive entry below its top folder into runtime/."""
    parts = name.split("/", 1)
    if len(parts) < 2 or not parts[1]:
        return None
    return f"{ROOT}/runtime/{parts[1]}"


def build_zip(target: Target, out: Path) -> None:
    ffmpeg_dir = FFMPEG / target.platform
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        archive.write(JAR, f"{ROOT}/TheLastHour.jar")
        for file in sorted(ffmpeg_dir.iterdir()):
            archive.write(file, f"{ROOT}/tools/ffmpeg/{target.platform}/{file.name}")
        archive.writestr(f"{ROOT}/{target.launcher}", launcher(target))
        archive.writestr(f"{ROOT}/LIESMICH.txt", GUIDE.replace("\n", "\r\n"))
        with zipfile.ZipFile(jre_archive(target)) as jre:
            for entry in jre.infolist():
                mapped = runtime_path(entry.filename)
                if mapped and not entry.is_dir():
                    archive.writestr(mapped, jre.read(entry))


def add_bytes(archive: tarfile.TarFile, name: str, data: bytes, mode: int) -> None:
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = mode
    info.mtime = int(time.time())
    archive.addfile(info, io.BytesIO(data))


def build_tar(target: Target, out: Path) -> None:
    ffmpeg_dir = FFMPEG / target.platform
    with tarfile.open(out, "w:gz", compresslevel=6) as archive:
        archive.add(JAR, f"{ROOT}/TheLastHour.jar", filter=lambda i: _mode(i, 0o644))
        for file in sorted(ffmpeg_dir.iterdir()):
            mode = 0o644 if file.suffix == ".md" else 0o755
            archive.add(file, f"{ROOT}/tools/ffmpeg/{target.platform}/{file.name}", filter=lambda i, m=mode: _mode(i, m))
        add_bytes(archive, f"{ROOT}/{target.launcher}", launcher(target).encode(), 0o755)
        add_bytes(archive, f"{ROOT}/LIESMICH.txt", GUIDE.encode(), 0o644)
        with tarfile.open(jre_archive(target), "r:gz") as jre:
            for member in jre.getmembers():
                mapped = runtime_path(member.name)
                if not mapped:
                    continue
                member.name = mapped
                if member.islnk():
                    member.linkname = runtime_path(member.linkname) or member.linkname
                member.uid = member.gid = 0
                member.uname = member.gname = ""
                data = jre.extractfile(member) if member.isfile() else None
                archive.addfile(member, data)


def _mode(info: tarfile.TarInfo, mode: int) -> tarfile.TarInfo:
    info.mode = mode
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    return info


def main() -> None:
    if not JAR.is_file():
        sys.exit(f"{JAR} fehlt. Zuerst ./gradlew :game:buildTheLastHourJar ausführen.")
    OUT.mkdir(parents=True, exist_ok=True)
    for target in TARGETS:
        if not (FFMPEG / target.platform).is_dir():
            sys.exit(f"FFmpeg für {target.platform} fehlt. Zuerst ./gradlew :game:downloadLastHourFfmpeg ausführen.")
        suffix = "zip" if target.windows else "tar.gz"
        out = OUT / f"TheLastHour-{target.platform}.{suffix}"
        print(f"{target.platform} -> {out.name}")
        (build_zip if target.windows else build_tar)(target, out)
        print(f"  {out.stat().st_size / 1_000_000:.0f} MB, sha256 {sha256(out)}")


if __name__ == "__main__":
    main()

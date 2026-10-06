"""Abbildungen für Kapitel 6: uv run --with matplotlib studie/abbildungen.py [--data PATH] [--out DIR].

Schreibt PDF-Grafiken nach abbildungen/. Die Daten kommen aus
auswertung.py, damit Abbildungen und Tabellen dieselbe Grundlage haben.
"""

import argparse
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.ticker import FuncFormatter  # noqa: E402

from auswertung import (GATE_KINDS, MEDIAN_EXCLUDED, Session, gate_periods,  # noqa: E402
                        load_clips, load_runs)

# Categorical slots 1-3 of the dataviz reference palette (validated all-pairs, light).
SERIES = ["#2a78d6", "#eb6834", "#1baf7a"]
INK, MUTED, GRID = "#0b0b0b", "#52514e", "#d9d8d4"
GROUPS = [("E1 Stromversorgung", {"E1"}, "o"), ("E3 Lagerzugang", {"E3"}, "s"),
          ("übrige Episoden", {"E2", "E4", "E5", "E6"}, "^")]


GERMAN = FuncFormatter(lambda value, _: f"{value:g}".replace(".", ","))


def style(axis, numeric_y=True):
    axis.xaxis.set_major_formatter(GERMAN)
    if numeric_y:
        axis.yaxis.set_major_formatter(GERMAN)
    axis.spines[["top", "right"]].set_visible(False)
    axis.spines[["left", "bottom"]].set_color(MUTED)
    axis.tick_params(colors=MUTED, labelcolor=INK)
    axis.grid(color=GRID, linewidth=0.6)
    axis.set_axisbelow(True)


def gate_figure(sessions, path):
    """Minutes after the 300 s gate per session, split by judgment situation."""
    names = [n for n in sessions if n not in MEDIAN_EXCLUDED]
    minutes = {kind: [] for kind in GATE_KINDS}
    for name in names:
        totals = dict.fromkeys(GATE_KINDS, 0)
        for *_, parts in gate_periods(sessions[name]):
            for kind in GATE_KINDS:
                totals[kind] += parts[kind] / 60_000
        for kind in GATE_KINDS:
            minutes[kind].append(totals[kind])
    figure, axis = plt.subplots(figsize=(6.2, 3.1))
    left = [0.0] * len(names)
    # White hatching keeps the three parts apart in grayscale print.
    for kind, color, hatch in zip(GATE_KINDS, SERIES, ["", "///", ".."]):
        axis.barh(names, minutes[kind], left=left, color=color, height=0.6, hatch=hatch,
                  edgecolor="white", linewidth=1.5, label=kind)
        left = [a + b for a, b in zip(left, minutes[kind])]
    axis.invert_yaxis()
    axis.set_xlim(left=0)
    axis.set_xlabel("Summierte Episodenzeit nach erfüllter Zeitbedingung (min)", color=INK)
    axis.legend(frameon=False, loc="lower center", bbox_to_anchor=(0.5, 1.0), ncol=3,
                fontsize=9, labelcolor=INK)
    style(axis, numeric_y=False)
    axis.grid(axis="y", visible=False)
    figure.tight_layout()
    figure.savefig(path)


def score_figure(clips, path):
    """Jev raw score against the EES mean of each rated clip."""
    figure, axis = plt.subplots(figsize=(6.2, 3.6))
    for (label, episodes, marker), color in zip(GROUPS, SERIES):
        group = [c for c in clips if c["clip"]["episode"] in episodes
                 and c["score"] is not None and c["session"].name not in MEDIAN_EXCLUDED]
        axis.scatter([c["score"] for c in group],
                     [c["ees"] for c in group],
                     s=30, marker=marker, color=color, edgecolor="white", linewidth=0.8, alpha=0.8,
                     label=f"{label} ($n$ = {len(group)})", zorder=3)
    for border in (0.5, 1.5):
        axis.axvline(border, color=MUTED, linewidth=0.8, linestyle=":")
    for x, level in ((0.25, "LOW"), (1.0, "MEDIUM"), (1.75, "HIGH")):
        axis.text(x, 5.15, level, ha="center", color=MUTED, fontsize=9, family="monospace")
    axis.set_xlim(0, 2)
    axis.set_xticks([0, 0.5, 1, 1.5, 2])
    axis.set_ylim(0.8, 5.35)
    axis.set_xlabel("Persönlicher Risikowert von Jev (0 bis 2)", color=INK)
    axis.set_ylabel("EES-Mittelwert der Frustration (1 bis 5)", color=INK)
    axis.legend(frameon=False, loc="upper left", bbox_to_anchor=(0, 0.93), fontsize=9,
                labelcolor=INK)
    style(axis)
    figure.tight_layout()
    figure.savefig(path)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=root / "pilot-data")
    parser.add_argument("--out", type=Path, default=root / "abbildungen")
    args = parser.parse_args()
    plt.rcParams.update({"font.family": "serif", "font.size": 10, "pdf.fonttype": 42})
    sessions = {name: Session(name, records, summary)
                for name, (records, summary) in load_runs(args.data).items()}
    _, clips = load_clips(args.data, sessions)
    args.out.mkdir(parents=True, exist_ok=True)
    gate_figure(sessions, args.out / "zeitgrenze.pdf")
    score_figure(clips, args.out / "jev-ees.pdf")
    print(f"Abbildungen in {args.out}")


if __name__ == "__main__":
    main()

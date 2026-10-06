"""Pilotzeiten: python studie/session_times.py [--markdown] [--data PATH]."""

import argparse
import csv
import json
import sys
from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from statistics import median


MEDIAN_EXCLUDED = {"S01"}
HEADERS = [
    "Sitzung", "Gesamt ab 1. Beitritt", "Gesamt ab Teamstart",
    "E1 power", "E2 login", "E3 storage", "E4 blue-usb",
    "E5 ventilation", "E6 exit",
]
EPISODES = [
    ("power", "power"), ("login", "login"),
    ("storage-recovery", "storage-access"), ("blue-usb", "blue-usb"),
    ("ventilation", "ventilation"), ("exit-code-assembly", "exit"),
]


def first_time(events, event_type, **fields):
    return next((e.get("elapsedMonotonicMs") for e in events
                 if e.get("eventType") == event_type
                 and all(e.get(k) == v for k, v in fields.items())), None)


def difference(end, start):
    return end - start if end is not None and start is not None else None


def durations(events):
    end = first_time(events, "PUZZLE_SOLVED", puzzleId="exit")
    joined = first_time(events, "PARTICIPANT_JOINED")
    team = first_time(events, "ROOM_EVENT", objectId="pilot.team_ready")
    if team is None:
        participants = set()
        for event in events:
            participant = event.get("participantId")
            if event.get("eventType") == "PARTICIPANT_JOINED" and participant:
                participants.add(participant)
                if len(participants) == 2:
                    team = event.get("elapsedMonotonicMs")
                    break
    return [difference(end, joined), difference(end, team)] + [
        difference(first_time(events, "PUZZLE_SOLVED", puzzleId=solved),
                   first_time(events, "PUZZLE_STARTED", puzzleId=started))
        for started, solved in EPISODES
    ]


def load_runs(data):
    """Map each session (S01, ...) to the records of its newest completed run."""
    with (data / "study-ids.csv").open(encoding="utf-8-sig", newline="") as file:
        sessions = {row[key]: f"S{number:02d}"
                    for number, row in enumerate(csv.DictReader(file), 1)
                    for key in ("studyIdA", "studyIdB") if row[key]}
    runs = {}
    for path in sorted((data / "outbox").glob("*.jsonl")):
        with path.open(encoding="utf-8-sig") as file:
            records = [json.loads(line, parse_float=Decimal) for line in file
                       if line.strip()]
        events = [r["event"] for r in records if r.get("type") == "event"]
        if not any(e.get("eventType") == "PUZZLE_SOLVED"
                   and e.get("puzzleId") == "exit" for e in events):
            continue
        summary_path = path.with_suffix(".pilot-run-summary.json")
        if not summary_path.exists():
            print(f"Warnung: Keine Run-Summary für {path.name}; übersprungen.",
                  file=sys.stderr)
            continue
        with summary_path.open(encoding="utf-8-sig") as file:
            summary = json.load(file)
        study_ids = {c.get("studyId") for c in summary.get("clients", [])}
        if not study_ids or not study_ids <= sessions.keys():
            continue
        mapped = {sessions[study_id] for study_id in study_ids}
        if len(mapped) != 1:
            print(f"Warnung: Uneindeutige Sitzung für {path.name}; übersprungen.",
                  file=sys.stderr)
            continue
        session = mapped.pop()
        started = next((r["session"].get("startedAt") for r in records
                        if r.get("type") == "session"), None)
        # Use the run's start time, not the summary's finalization start time.
        timestamp = (datetime.fromisoformat(started.replace("Z", "+00:00"))
                     if started else datetime.fromtimestamp(path.stat().st_mtime,
                                                            timezone.utc))
        run = (timestamp, path.name, records, summary)
        if session in runs:
            selected = max(runs[session], run, key=lambda r: r[:2])
            print(f"Warnung: Mehrere abgeschlossene Läufe für {session}; "
                  f"verwende den neuesten: {selected[1]}.", file=sys.stderr)
            runs[session] = selected
        else:
            runs[session] = run
    return {session: runs[session][2:]
            for session in sorted(runs, key=lambda s: int(s[1:]))}


def events_of(records):
    return [r["event"] for r in records if r.get("type") == "event"]


def load_sessions(data):
    return {session: durations(events_of(records))
            for session, (records, _) in load_runs(data).items()}


def format_time(milliseconds):
    if milliseconds is None:
        return "–"
    seconds = int((Decimal(str(milliseconds)) / 1000).quantize(
        Decimal("1"), rounding=ROUND_HALF_UP))
    return f"{seconds // 60}:{seconds % 60:02d}"


def table_rows(sessions):
    rows = [[session, *map(format_time, values)]
            for session, values in sessions.items()]
    included = [s for s in sessions if s not in MEDIAN_EXCLUDED]
    medians = []
    for column in range(len(HEADERS) - 1):
        values = [sessions[s][column] for s in included
                  if sessions[s][column] is not None]
        medians.append(format_time(median(values) if values else None))
    label = f"Median S02–{included[-1]}" if included else "Median (keine Sitzungen)"
    return rows + [[label, *medians]]


def print_table(rows, markdown, headers=HEADERS, summary=True):
    """Print rows as a box or Markdown table; summary bolds and separates the last row."""
    if markdown:
        print("| " + " | ".join(headers) + " |")
        print("| :--- | " + " | ".join(["---:"] * (len(headers) - 1)) + " |")
        for index, row in enumerate(rows):
            last = summary and index == len(rows) - 1
            cells = [f"**{cell}**" if last and cell else cell for cell in row]
            print("| " + " | ".join(cells) + " |")
        return
    widths = [max(len(row[i]) for row in [headers, *rows])
              for i in range(len(headers))]

    def border(left, middle, right):
        print(left + middle.join("─" * (width + 2) for width in widths) + right)

    def line(row):
        print("│ " + " │ ".join(cell.ljust(width) if i == 0 else cell.rjust(width)
                                for i, (cell, width) in enumerate(zip(row, widths)))
              + " │")

    border("┌", "┬", "┐")
    line(headers)
    border("├", "┼", "┤")
    for index, row in enumerate(rows):
        if summary and index == len(rows) - 1 and index:
            border("├", "┼", "┤")
        line(row)
    border("└", "┴", "┘")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path,
                        default=Path(__file__).resolve().parent.parent / "pilot-data")
    parser.add_argument("--markdown", action="store_true")
    args = parser.parse_args()
    print_table(table_rows(load_sessions(args.data)), args.markdown)
    print("\nE1 ab erster Interaktion; E3 vom Start der Recovery bis zum geöffneten Lager.")
    print("E5 und E6 laufen parallel, die Summe ergibt nicht die Gesamtzeit.")
    print("S01 nicht im Median.")


if __name__ == "__main__":
    main()

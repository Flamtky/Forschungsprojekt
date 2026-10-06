"""Festgelegte Zählregel: python studie/zaehlregel.py [--markdown] [--data PATH].

Bildet die Zählregel nach, die LastHourAdaptationPolicy.java und LastHourAdaptation.java vor
der Umstellung auf Jev-Urteile nutzten. Die Ereignisübersetzung folgt LastHourTracking.java.
Nur beobachtete Fortschritte und Unterstützungen setzen die Beobachtung zurück.
Hypothetische Entscheidungen verändern weder Ereignisse noch Lösungszeitpunkte.
"""

from __future__ import annotations

import argparse
import sys
from collections import deque
from dataclasses import dataclass, field
from itertools import groupby
from pathlib import Path
from typing import Any

from session_times import events_of, format_time, load_runs, print_table


Event = dict[str, Any]
Timeline = list[tuple[int, str, str, dict[str, str]]]
EPISODES = {
    "E1": ("power",),
    "E2": ("login",),
    "E3": ("storage-recovery", "storage-access"),
    "E4": ("blue-usb",),
    "E5": ("ventilation",),
    "E6": ("exit-code-assembly", "exit"),
}
PUZZLE_EPISODE = {puzzle: episode for episode, puzzles in EPISODES.items()
                  for puzzle in puzzles}
ACTIONS = ("HINT_OFFER", "STORAGE_SIMPLIFY", "STORAGE_AUTO_COMPLETE")
ACTION_LABELS = dict(zip(ACTIONS, ("Hinweis", "Vereinfachung", "Autoabschluss")))
HEADERS = ["Sitzung", "Episode", "Tatsächlich", "Alte Regel", "Nur Zeit", "Ende"]


@dataclass
class _Person:
    signals: deque[int] = field(default_factory=deque)
    signature: str = ""
    signature_ms: int = -10**18


@dataclass
class _Episode:
    started: bool = False
    solved: bool = False
    progress_ms: int = 0
    negative: int = 0
    signature: str = ""
    hint_accepted: bool = False
    last_action: str = "NONE"
    people: dict[str, _Person] = field(default_factory=dict)

    def start(self, ms: int) -> None:
        if not self.started:
            self.started = True
            self.progress_ms = ms

    def reset(self, ms: int, *, support: bool = False) -> None:
        self.start(ms)
        self.progress_ms = ms
        self.negative = 0
        self.signature = ""
        if not support:
            self.hint_accepted = False
        for person in self.people.values():
            person.signals.clear()
            person.signature = ""
            person.signature_ms = -10**18

    def signal(self, participant: str, signature: str, negative: bool, ms: int) -> None:
        self.start(ms)
        person = self.people.setdefault(participant, _Person())
        if negative:
            self.negative += 1
            person.signals.append(ms)
        if signature == self.signature:
            self.negative += 1
        if signature == person.signature and ms - person.signature_ms <= 120_000:
            person.signals.append(ms)
        self.signature = person.signature = signature
        person.signature_ms = ms

    def team_state(self, ms: int, only_time: bool, room_progress: int) -> str:
        if not self.started or self.solved:
            return "B0"
        anchor = max(self.progress_ms, room_progress) if only_time else self.progress_ms
        required = (180_000 if self.hint_accepted else 300_000) if only_time else (
            90_000 if self.hint_accepted else 120_000)
        if ms - anchor < required:
            return "B0"
        if not only_time and self.negative < (2 if self.hint_accepted else 3):
            return "B1"
        return "B3" if self.hint_accepted else "B2"

    def risks(self, ms: int, team: str) -> dict[str, str]:
        risks = {}
        for participant, person in self.people.items():
            # Java entfernt strikt ältere Signale; genau 120 s zählt noch.
            while person.signals and person.signals[0] < ms - 120_000:
                person.signals.popleft()
            count = len(person.signals)
            risk = "LOW"
            if team != "B0" and count:
                risk = "MEDIUM"
            if team in ("B2", "B3") and count >= 3:
                risk = "HIGH"
            risks[participant] = risk
        return risks


@dataclass
class _Replay:
    timeline: Timeline = field(default_factory=list)
    offers: dict[str, list[tuple[int, str]]] = field(
        default_factory=lambda: {episode: [] for episode in EPISODES})
    stages: dict[str, set[str]] = field(
        default_factory=lambda: {episode: set() for episode in EPISODES})


def _episode_of(event: Event) -> str | None:
    return PUZZLE_EPISODE.get(event.get("puzzleId")) or (
        event.get("payload", {}).get("episode")
        if event.get("eventType") == "ROOM_EVENT" else None)


def _replay(events: list[Event], *, only_time: bool = False) -> _Replay:
    """Wertet Ereignisse und sämtliche möglichen Zeitgrenzen bis zum Logende aus.

    Gleiche Millisekunden werden in Logreihenfolge verarbeitet, danach folgt der
    Tick. Teilnehmer werden ab recording_sync registriert; bei Ausschnitten ohne
    solche Marker dienen Beitritte bzw. erste eigene Ereignisse als Ersatz.
    Die Vergleichsregel verbraucht ihre Hinweisstufe beim eigenen Angebot.
    Hypothetische E3-Eingriffe werden gezählt, aber nicht ausgeführt: die nächste
    Stufe benötigt weiterhin die tatsächlich aufgezeichnete vorherige Hilfe.
    """
    result = _Replay()
    if not events:
        return result
    ordered = sorted(events, key=lambda event: event["elapsedMonotonicMs"])
    grouped = {int(ms): list(group) for ms, group in groupby(
        ordered, key=lambda event: event["elapsedMonotonicMs"])}
    horizon = max(grouped)
    # Jeder Anker stammt aus einem Ereignis. Auch ohne Folgeereignis greifen die
    # Schwellen exakt; das persönliche inklusive Fenster endet bei +120001 ms.
    delays = (0, 180_000, 300_000) if only_time else (0, 90_000, 120_000, 120_001)
    ticks = sorted({ms + delay for ms in grouped for delay in delays
                    if ms + delay <= horizon})
    states = {episode: _Episode() for episode in EPISODES}
    previous: dict[str, tuple[str, dict[str, str]]] = {}
    connected: set[str] = set()
    synced: set[str] = set()
    has_sync = any(event.get("objectId") == "pilot.recording_sync" for event in ordered)
    has_joins = any(event.get("eventType") == "PARTICIPANT_JOINED" for event in ordered)
    room_progress = -10**18

    for ms in ticks:
        for event in grouped.get(ms, []):
            kind = event["eventType"]
            obj = event.get("objectId", "")
            participant = event.get("participantId")
            payload = event.get("payload", {})
            if participant:
                if kind == "PARTICIPANT_LEFT":
                    connected.discard(participant)
                elif kind == "PARTICIPANT_JOINED" or not has_joins:
                    connected.add(participant)
                if obj == "pilot.recording_sync":
                    synced.add(participant)
            episode = _episode_of(event)
            if episode not in states:
                continue
            state = states[episode]
            if kind == "PUZZLE_STARTED":
                if not state.solved:
                    state.start(ms)
                    if room_progress == -10**18:
                        room_progress = ms
            elif kind == "PUZZLE_SOLVED":
                state.reset(ms)
                room_progress = ms
                if event["puzzleId"] == EPISODES[episode][-1]:
                    state.solved = True
            elif kind == "ANSWER_SUBMITTED":
                if event.get("outcome") == "CORRECT":
                    state.reset(ms)
                    room_progress = ms
                elif event.get("outcome") == "INCORRECT" and participant:
                    state.signal(participant, obj + ":" + payload["answer"], True, ms)
            elif kind == "ROOM_EVENT":
                if payload.get("signalKind") == "action" and participant:
                    state.signal(participant, obj + ":" + payload["action"], False, ms)
                elif obj == "adaptation.intervention":
                    action = payload.get("action")
                    if action == "HINT_OFFER" and payload.get("accepted") is True:
                        if not state.solved and not state.hint_accepted:
                            state.hint_accepted = True
                            state.last_action = "HINT_OFFER"
                            state.reset(ms, support=True)
                    elif episode == "E3" and action in ACTIONS[1:]:
                        state.last_action = action
                        state.reset(ms, support=True)
                        if action == "STORAGE_AUTO_COMPLETE":
                            # completedByAdaptation meldet bewusst kein PUZZLE_SOLVED.
                            state.reset(ms)
                            state.solved = True
                            room_progress = ms

        for episode, state in states.items():
            if not state.started:
                continue
            if not state.solved:
                for participant in sorted(connected & synced if has_sync else connected):
                    state.people.setdefault(participant, _Person())
            team = state.team_state(ms, only_time, room_progress)
            # Leere Risikokarte markiert das Episodenende für old_risk_at.
            risks = {} if state.solved else state.risks(ms, team)
            snapshot = (team, risks)
            if previous.get(episode) != snapshot:
                result.timeline.append((ms, episode, team, risks))
                previous[episode] = snapshot
            if team != "B0":
                result.stages[episode].add(team)
            if not connected:
                continue
            action = None
            if team == "B2" and state.last_action == "NONE":
                action = "HINT_OFFER"
                state.last_action = action
            elif episode == "E3" and team == "B3":
                if state.last_action == "HINT_OFFER" and state.hint_accepted:
                    action = "STORAGE_SIMPLIFY"
                elif state.last_action == "STORAGE_SIMPLIFY":
                    action = "STORAGE_AUTO_COMPLETE"
            if action and all(old_action != action for _, old_action in result.offers[episode]):
                result.offers[episode].append((ms, action))
    return result


def old_rule_timeline(events: list[Event]) -> list[tuple[int, str, str, dict[str, str]]]:
    """Zustands-/Risikoänderungen der alten Regel unter dem beobachteten Verlauf.

    Zeiten sind Millisekunden ab Sitzungsstart. Das persönliche Fenster umfasst
    [ms - 120000, ms]. Eine abschließende Zeile (ms, Episode, B0, {}) beendet die
    Gültigkeit aller Risiken dieser Episode. Java liefert bei Abschluss LOW;
    hier wird die Episode ausdrücklich geschlossen, damit LOW nicht weiterlebt.
    Ausgewertet wird nur bis zum letzten übergebenen Ereignis.

    Die Schwellen werden in Millisekunden auf die Logzeitpunkte gelegt. Die
    unbekannte Phase der ganzzahligen System.nanoTime-Sekunden und die Latenz des
    damaligen Spielticks sind aus dem Log nicht rekonstruierbar.
    """
    return _replay(events).timeline


def old_risk_at(timeline: Timeline, ms: int, participant_id: str, episode: str) -> str | None:
    """Letzte gültige Stufe; None vor Beginn, nach Ende oder ohne Registrierung."""
    for elapsed, current, _, risks in reversed(timeline):
        if elapsed <= ms and current == episode:
            return risks.get(participant_id)
    return None


def _offer_cell(offers: list[tuple[int, str]], stages: set[str]) -> str:
    value = "–"
    if offers:
        ms, action = offers[0]
        value = f"{format_time(ms)} {ACTION_LABELS[action]}"
        if len(offers) > 1:
            value += f"; {len(offers)} Angebote"
    if len(stages) > 1:
        value += f"; {len(stages)} Stufen ({'/'.join(sorted(stages))})"
    return value


def table_rows(runs: dict) -> list[list[str]]:
    rows = []
    totals = {"S01": [0, 0, 0], "S02–S09": [0, 0, 0]}
    for session, (records, _) in sorted(runs.items()):
        if session not in {f"S{i:02d}" for i in range(1, 10)}:
            continue
        events = events_of(records)
        old = _replay(events)
        timed = _replay(events, only_time=True)
        actual = {episode: [] for episode in EPISODES}
        stages = {episode: set() for episode in EPISODES}
        ends = {}
        for event in sorted(events, key=lambda e: e["elapsedMonotonicMs"]):
            episode = _episode_of(event)
            if episode not in EPISODES:
                continue
            payload = event.get("payload", {})
            ms = event["elapsedMonotonicMs"]
            if event["eventType"] == "ROOM_EVENT":
                if event.get("objectId") == "adaptation.decision" and payload.get("action") in ACTIONS:
                    actual[episode].append((ms, payload["action"]))
                if event.get("objectId") in ("adaptation.state", "adaptation.decision"):
                    if payload.get("state") in ("B1", "B2", "B3"):
                        stages[episode].add(payload["state"])
            elif (event["eventType"] == "PUZZLE_SOLVED"
                  and event["puzzleId"] == EPISODES[episode][-1]):
                ends.setdefault(episode, ms)
        for episode in EPISODES:
            columns = [actual[episode], old.offers[episode], timed.offers[episode]]
            if not any(columns):
                continue
            counts = totals["S01" if session == "S01" else "S02–S09"]
            for index, offers in enumerate(columns):
                counts[index] += len(offers)
            rows.append([session, episode,
                         _offer_cell(columns[0], stages[episode]),
                         _offer_cell(columns[1], old.stages[episode]),
                         _offer_cell(columns[2], timed.stages[episode]),
                         format_time(ends.get(episode))])
    for group, counts in totals.items():
        rows.append([f"Summe {group}", "", *map(str, counts), ""])
    return rows


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path,
                        default=Path(__file__).resolve().parent.parent / "pilot-data")
    parser.add_argument("--markdown", action="store_true")
    args = parser.parse_args()
    print("Wann hätte welche Regel angeboten\n")
    print_table(table_rows(load_runs(args.data)), args.markdown, HEADERS, summary=False)
    print("\nZeiten mm:ss ab Sitzungsstart, auf Sekunden gerundet; Ende = terminales PUZZLE_SOLVED.")
    print("Hinweis = HINT_OFFER; Vereinfachung = STORAGE_SIMPLIFY; Autoabschluss = STORAGE_AUTO_COMPLETE.")
    print("Alte Regel: 120 s / 3 Signale, nach Hinweisannahme 90 s / 2 Signale; erstes Angebot bei B2.")
    print("Nur Zeit: HEAD mit stets blockiertem Team, auch ohne Eingaben; 300 s / 180 s, raumweiter Fortschritt.")
    print("Stufen zählt unterschiedliche erreichte Zustände B1/B2/B3, auch ohne Angebot; B1 bietet nichts an.")
    print("Summen zählen Entscheidungen einschließlich E3-Eingriffen, jede Aktionsart höchstens einmal je Vergleichsepisode.")
    print("Nur beobachtete Hilfe setzt zurück; weitere E3-Angebote benötigen die beobachtete vorherige Hilfe.")
    print("Bloße Angebote und Ablehnungen setzen nichts zurück; jede Vergleichsregel zählt ihre eigenen Angebote.")
    print("Zeitgrenzen auf Log-Millisekunden; ursprüngliche Sekundenphase und Ticklatenz unbekannt. S01 gesondert.")


if __name__ == "__main__":
    main()

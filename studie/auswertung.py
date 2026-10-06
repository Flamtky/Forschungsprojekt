"""Querauswertung Jev, Replay-Ratings und Hilfeangebote: python studie/auswertung.py [--markdown] [--data PATH] [--latex DIR]."""

import argparse
import csv
import json
import sys
from pathlib import Path
from statistics import correlation, median

from session_times import (EPISODES, MEDIAN_EXCLUDED, events_of, format_time,
                           load_runs, print_table)
from zaehlregel import PUZZLE_EPISODE, old_risk_at, old_rule_timeline


LEVELS = ["LOW", "MEDIUM", "HIGH"]
EES_ITEMS = ("frustrated", "irritated", "dissatisfied")
GROUPS = [("alle", None), ("E1 power", {"E1"}), ("E3 storage", {"E3"}),
          ("übrige", {"E2", "E4", "E5", "E6"})]
# Facilitator hints from the session notes in session time (S06, S07 estimated).
FACILITATOR_MS = {"S04": [999_000], "S05": [740_000], "S06": [1_010_000, 1_500_000],
                  "S07": [833_000], "S08": [881_000]}
TIMING = {"EARLY": "zu früh", "APPROPRIATE": "passend", "LATE": "zu spät"}
INTENSITY = {"WEAK": "zu schwach", "APPROPRIATE": "passend", "STRONG": "zu stark"}
WISH = {"NONE": "nichts", "HINT": "Hinweis", "SIMPLIFY": "Vereinfachung",
        "AUTO_COMPLETE": "Abschluss"}
ACTION = {"NONE": "keine", "HINT_OFFER": "Angebot", "STORAGE_SIMPLIFY": "Vereinfachung",
          "STORAGE_AUTO_COMPLETE": "Abschluss"}


def number(value, digits=2):
    return "–" if value is None else f"{value:.{digits}f}".replace(".", ",")


def spread(values):
    return f"{number(min(values))}–{number(max(values))}" if values else "–"


def median_or_none(values):
    return median(values) if values else None


def room(event, name):
    return event.get("eventType") == "ROOM_EVENT" and event.get("objectId") == name


class Session:
    """Events of one run with lookups by episode, sequence and participant."""

    def __init__(self, name, records, summary):
        self.name = name
        self.events = events_of(records)
        self.summary = summary
        self.study = {c["participantId"]: c.get("studyId")
                      for c in summary.get("clients", [])}
        self.episodes = {}
        for index, (started, solved) in enumerate(EPISODES, 1):
            start = self.first("PUZZLE_STARTED", started)
            end = self.first("PUZZLE_SOLVED", solved)
            self.episodes[f"E{index}"] = (start, end)
        self.judgments = [e for e in self.events if room(e, "adaptation.judgment")]
        self.old_rule = old_rule_timeline(self.events)

    def first(self, event_type, puzzle):
        return next((e["elapsedMonotonicMs"] for e in self.events
                     if e.get("eventType") == event_type
                     and e.get("puzzleId") == puzzle), None)

    def judgment(self, episode, sequence):
        """Latest applied Jev judgment of the episode up to the sequence."""
        return next((e for e in reversed(self.judgments)
                     if e["sessionSequence"] <= sequence
                     and e["payload"].get("episode") == episode
                     and e["payload"].get("applied")), None)

    def score(self, judgment, participant):
        if judgment is None:
            return None
        payload = judgment["payload"]
        for key, player in payload.get("players", {}).items():
            if player.get("participantId") == participant:
                return float(payload["answers"][f"risk_{key}"]["score"])
        return None

    def risk(self, episode, sequence, participant):
        return next((e["payload"]["risk"] for e in reversed(self.events)
                     if e["sessionSequence"] < sequence and room(e, "adaptation.risk")
                     and e.get("participantId") == participant
                     and e["payload"].get("episode") == episode), None)

    def facilitated(self, episode, at_ms):
        """True if a facilitator hint fell into the active episode before at_ms."""
        start, end = self.episodes.get(episode, (None, None))
        return any(start is not None and start <= hint <= at_ms
                   and (end is None or hint < end)
                   for hint in FACILITATOR_MS.get(self.name, []))


def level_of(score):
    return LEVELS[min(2, int(score + 0.5))]


def load_clips(data, sessions):
    """Replay answers of the newest cumulative export, one dict per rated clip."""
    exports = sorted((data / "auswertung").glob("replay-export-*.json"))
    if not exports:
        sys.exit("Kein Replay-Export in pilot-data/auswertung gefunden.")
    with exports[-1].open(encoding="utf-8-sig") as file:
        people = json.load(file)
    by_session_id = {s.events[0]["sessionId"]: s for s in sessions.values()}
    clips = []
    for person in people:
        session = by_session_id.get(person["sessionId"])
        if session is None:
            continue
        answers = {a["clipId"]: a for a in person["responses"]["answers"]}
        for clip in person["clips"]:
            answer = answers.get(clip["id"])
            if answer is None:
                continue
            baseline = answer["baseline"]
            participant = person["participantId"]
            judgment = session.judgment(clip["episode"], clip["eventSequence"])
            score = session.score(judgment, participant)
            if clip["action"] == "NONE" and score is not None \
                    and level_of(score) != clip["risk"]:
                print(f"Warnung: {session.name} {person['studyId']} {clip['id']}: "
                      f"Stufe {clip['risk']}, letzter Score {score}.", file=sys.stderr)
            clips.append({
                "session": session, "person": person["studyId"], "clip": clip,
                "baseline": baseline, "intervention": answer["intervention"],
                "ees": sum(baseline[item] for item in EES_ITEMS) / len(EES_ITEMS),
                "score": score,
                "stuck": judgment["payload"].get("stuckProbability") if judgment else None,
                "facilitated": session.facilitated(clip["episode"], clip["eventMs"]),
                "old": old_risk_at(session.old_rule, clip["eventMs"], participant,
                                   clip["episode"]),
            })
    return exports[-1].name, sorted(clips, key=lambda c: (
        int(c["session"].name[1:]), c["person"], c["clip"]["eventMs"]))


def technical_rows(sessions, clips):
    rows = []
    for name, session in sessions.items():
        latencies = [j["payload"]["latencyMs"] for j in session.judgments]
        failed = sum(room(e, "adaptation.judgment-failed") for e in session.events)
        offers = sum(room(e, "adaptation.decision")
                     and e["payload"].get("action") != "NONE" for e in session.events)
        rated = [sum(c["session"] is session and c["person"] == study
                     for c in clips) for study in sorted(session.study.values())]
        rows.append([
            name, session.summary.get("runStatus", "–"),
            str(len(latencies)),
            str(sum(j["payload"].get("applied") is True for j in session.judgments)),
            str(failed),
            number(median_or_none(latencies), 0), number(max(latencies, default=None), 0),
            str(offers), " / ".join(map(str, rated)),
        ])
    return rows


def level_rows(clips, key=lambda c: c["clip"]["risk"], groups=GROUPS):
    rows = []
    for label, episodes in groups:
        for level in LEVELS:
            group = [c for c in clips if key(c) == level
                     and c["session"].name not in MEDIAN_EXCLUDED
                     and (episodes is None or c["clip"]["episode"] in episodes)]
            if not group:
                continue
            ees = [c["ees"] for c in group]
            need = [c["baseline"]["supportNeed"] for c in group]
            rows.append([
                label, level, str(len(group)),
                str(len({c["person"] for c in group})),
                number(median(ees)), spread(ees),
                f"{sum(v <= 2 for v in ees)} / {sum(2 < v < 4 for v in ees)} / "
                f"{sum(v >= 4 for v in ees)}",
                number(median(need), 1), spread(need),
                str(sum(c["baseline"]["desiredReaction"] != "NONE" for c in group)),
                str(sum(c["facilitated"] for c in group)),
            ])
    return rows


def clip_rows(clips):
    rows = []
    for c in clips:
        clip, baseline = c["clip"], c["baseline"]
        flags = ("VL" if c["facilitated"] else "") + \
                ("!" if clip["risk"] == "HIGH" and c["ees"] <= 2
                 or clip["risk"] == "LOW" and c["ees"] >= 4 else "")
        rows.append([
            c["session"].name, c["person"], clip["id"], clip["episode"],
            format_time(clip["eventMs"]), clip["risk"], number(c["score"]),
            number(c["stuck"]), c["old"] or "–", ACTION[clip["action"]],
            " / ".join(str(baseline[item]) for item in EES_ITEMS),
            number(c["ees"]), str(baseline["supportNeed"]),
            WISH.get(baseline["desiredReaction"], baseline["desiredReaction"]), flags,
        ])
    return rows


def offer_rows(sessions):
    rows = []
    for name, session in sessions.items():
        for event in session.events:
            if not room(event, "adaptation.decision") \
                    or event["payload"].get("action") == "NONE":
                continue
            payload, sequence = event["payload"], event["sessionSequence"]
            episode, at = payload["episode"], event["elapsedMonotonicMs"]
            reply = next((e["payload"] for e in session.events
                          if e["sessionSequence"] > sequence
                          and room(e, "adaptation.intervention")
                          and "accepted" in e["payload"]
                          and e["payload"].get("episode") == episode), None)
            judgment = session.judgment(episode, sequence)
            people = [f"{study} {session.risk(episode, sequence, participant) or '–'} "
                      f"({number(session.score(judgment, participant))})"
                      for participant, study in sorted(session.study.items(),
                                                       key=lambda item: item[1])]
            end = session.episodes[episode][1]
            accepted = "–" if reply is None else "ja" if reply["accepted"] else "nein"
            rows.append([
                name, episode, format_time(at), ACTION[payload["action"]],
                payload.get("state", "–"),
                number(judgment["payload"].get("stuckProbability") if judgment else None),
                ", ".join(people), accepted,
                format_time(end - at if end is not None else None),
                "VL" if session.facilitated(episode, at) else "",
            ])
    return rows


def rating_rows(clips):
    rows = []
    for c in clips:
        rating = c["intervention"]
        if c["clip"]["action"] == "NONE" or rating is None:
            continue
        seen = rating.get("visible")
        rows.append([
            c["session"].name, c["person"], c["clip"]["episode"],
            ACTION[c["clip"]["action"]], c["clip"]["risk"],
            WISH.get(c["baseline"]["desiredReaction"], "–"),
            str(c["baseline"]["supportNeed"]),
            "ja" if seen else "nein",
            str(rating.get("fit") or "–"), str(rating.get("helpfulness") or "–"),
            TIMING.get(rating.get("timing"), "–"),
            INTENSITY.get(rating.get("intensity"), "–"),
        ])
    return rows


GATE_KINDS = ["ohne gültiges Urteil", "Urteil unter 0,5", "nach Angebot"]
# Part of the time without a valid judgment in which the current phase had no inputs at all.
GATE_EMPTY = "davon ohne Eingaben"


def gate_periods(session):
    """Time after the gate (state left B0) until it returned to B0 or the episode ended.

    Each phase is split into minutes without a valid judgment, minutes with only
    judgments below the offer threshold, and minutes after an offer. A judgment is
    valid while the recent input lists match those in its payload. Progress,
    accepted help and stronger interventions discard it. GATE_EMPTY additionally
    counts the time without a valid judgment in which the phase had no inputs.
    Returns (episode, start, end, {kind: ms}).
    """
    periods, open_at = [], {}
    for event in session.events:
        if not room(event, "adaptation.state"):
            continue
        payload, at = event["payload"], event["elapsedMonotonicMs"]
        episode = payload["episode"]
        if payload["previousState"] == "B0" and payload["state"] != "B0":
            open_at[episode] = at
        elif payload["state"] == "B0" and episode in open_at:
            periods.append((episode, open_at.pop(episode), at))
    periods += [(episode, start, session.episodes[episode][1])
                for episode, start in open_at.items()]
    result = []
    for episode, start, end in sorted(periods, key=lambda p: p[1]):
        offered = min((e["elapsedMonotonicMs"] for e in session.events
                       if room(e, "adaptation.decision")
                       and e["payload"]["episode"] == episode
                       and e["payload"].get("action") != "NONE"
                       and start <= e["elapsedMonotonicMs"] <= end), default=end)
        inputs = {player["participantId"]: [] for j in session.judgments
                  if j["payload"]["episode"] == episode
                  for player in j["payload"]["players"].values()}
        last, seen, judged_inputs, stuck = {}, set(), None, None
        parts = dict.fromkeys([*GATE_KINDS, GATE_EMPTY], 0)
        previous_at = start

        def add(until):
            kind = GATE_KINDS[1 if inputs == judged_inputs else 0]
            parts[kind] += until - previous_at
            if kind == GATE_KINDS[0] and not any(inputs.values()):
                parts[GATE_EMPTY] += until - previous_at

        for event in session.events:
            at = event["elapsedMonotonicMs"]
            if at > offered:
                break
            payload = event.get("payload", {})
            if (PUZZLE_EPISODE.get(event.get("puzzleId"))
                    or payload.get("episode")) != episode:
                continue
            add(max(at, start))
            previous_at = max(at, start)
            event_type = event["eventType"]
            if (event_type == "PUZZLE_SOLVED"
                    or event_type == "ANSWER_SUBMITTED" and event.get("outcome") == "CORRECT"
                    or room(event, "adaptation.intervention")
                    and (payload.get("accepted") is True or payload.get("action")
                         in ("STORAGE_SIMPLIFY", "STORAGE_AUTO_COMPLETE"))):
                inputs = {participant: [] for participant in inputs}
                last.clear()
                seen.clear()
                judged_inputs = None
            elif (event_type == "ANSWER_SUBMITTED" and event.get("outcome") == "INCORRECT"
                  or event_type == "ROOM_EVENT" and payload.get("signalKind") == "action"):
                participant, target = event["participantId"], event["objectId"]
                key = "input" if event_type == "ANSWER_SUBMITTED" else "action"
                value = payload["answer" if key == "input" else "action"]
                signature = (key, target, value)
                prior = last.get(participant)
                if prior and prior[1] == signature and at - prior[0] <= 5_000:
                    continue
                current = {"target": target, key: value,
                           "result": "wrong" if key == "input" else "no progress",
                           "repeats_earlier_input": (target, value) in seen,
                           "timing": "quick" if prior and at - prior[0] <= 15_000
                           else "after a pause"}
                inputs[participant] = (inputs.get(participant, []) + [current])[-8:]
                last[participant] = (at, signature)
                seen.add((target, value))
            elif room(event, "adaptation.judgment") and payload.get("applied"):
                judged_inputs = {payload["players"][label]["participantId"]: player["inputs"]
                                 for label, player in payload["state"]["players"].items()}
                stuck = payload["stuckProbability"]
            # The triggering judgment, state and offer are separate writes in one tick.
            if at >= start and offered < end and inputs == judged_inputs and stuck >= 0.5:
                offered = at
        add(offered)
        parts[GATE_KINDS[2]] = end - offered
        result.append((episode, start, end, parts))
    return result


def gate_rows(sessions):
    rows, totals = [], dict.fromkeys(GATE_KINDS, 0)
    for name, session in sessions.items():
        if name in MEDIAN_EXCLUDED:
            continue
        for episode, start, end, parts in gate_periods(session):
            rows.append([name, episode, format_time(start), format_time(end - start),
                         *[format_time(parts[kind]) for kind in GATE_KINDS]])
            for kind in GATE_KINDS:
                totals[kind] += parts[kind]
    rows.append(["Summe", "", "", format_time(sum(totals.values())),
                 *[format_time(totals[kind]) for kind in GATE_KINDS]])
    return rows


def comparison_rows(clips):
    """Jev level against the old counting rule at the same clip moment."""
    old_levels = LEVELS + [None]
    rows = [[level, *[str(sum(c["clip"]["risk"] == level and c["old"] == old
                              for c in clips)) for old in old_levels]]
            for level in LEVELS]
    return rows


def cohens_kappa(clips):
    """Unweighted agreement across LOW/MEDIUM/HIGH, excluding missing ratings."""
    pairs = [(c["clip"]["risk"], c["old"]) for c in clips
             if c["clip"]["risk"] in LEVELS and c["old"] in LEVELS]
    n = len(pairs)
    if not n:
        return None
    observed = sum(jev == old for jev, old in pairs) / n
    expected = sum(sum(jev == level for jev, _ in pairs)
                   * sum(old == level for _, old in pairs)
                   for level in LEVELS) / n ** 2
    return (observed - expected) / (1 - expected) if expected < 1 else None


def spearmans_rho(clips, key):
    """Correlate risk levels (0/1/2) with EES, using mean ranks for ties."""
    pairs = [(LEVELS.index(key(c)), c["ees"]) for c in clips if key(c) in LEVELS]
    if len(pairs) < 2:
        return None

    def ranks(values):
        order = sorted(range(len(values)), key=values.__getitem__)
        result = [0.0] * len(values)
        start = 0
        while start < len(order):
            end = start + 1
            while end < len(order) and values[order[end]] == values[order[start]]:
                end += 1
            for index in order[start:end]:
                result[index] = (start + 1 + end) / 2
            start = end
        return result

    levels, ees = zip(*pairs)
    if len(set(levels)) < 2 or len(set(ees)) < 2:
        return None
    return correlation(ranks(levels), ranks(ees))


def wish_rows(clips):
    actions = list(ACTION)
    rows = []
    for wish in WISH:
        counts = [sum(c["baseline"]["desiredReaction"] == wish
                      and c["clip"]["action"] == action for c in clips)
                  for action in actions]
        if any(counts):
            rows.append([WISH[wish], *map(str, counts), str(sum(counts))])
    totals = [sum(c["clip"]["action"] == action for c in clips) for action in actions]
    return rows + [["Summe", *map(str, totals), str(sum(totals))]]


PRE_SURVEY = "forms/The Last Hour_ Vorabfragen.csv"
PRE_ID = "Bitte gib die pseudonyme Kennung ein, die du vor dem Spiel erhalten hast."
PRE_ESCAPE = "Wie oft hast du schon an einem Escape Room teilgenommen (vor Ort oder digital)?"
PRE_GAMES = "Wie oft spielst du Videospiele?"
ESCAPE_LEVELS = ["noch nie", "1 bis 2 Mal", "3 bis 5 Mal", "mehr als 5 Mal"]


def experience_rows(data, clips):
    """Per escape-room experience: medians of the per-person medians (S02–S09)."""
    path = data / PRE_SURVEY
    if not path.exists():
        return [], ""
    with path.open(encoding="utf-8") as file:
        pre = {row[PRE_ID]: row for row in csv.DictReader(file)}
    people = {}
    for c in clips:
        if c["session"].name not in MEDIAN_EXCLUDED:
            people.setdefault(c["person"], []).append(c)
    rows = []
    for level in ESCAPE_LEVELS:
        group = [cs for person, cs in people.items() if pre[person][PRE_ESCAPE] == level]
        if not group:
            continue
        rows.append([
            level, str(len(group)), str(sum(map(len, group))),
            number(median(median(c["ees"] for c in cs) for cs in group)),
            number(median(median(c["baseline"]["supportNeed"] for c in cs) for cs in group), 1),
            str(sum(c["baseline"]["desiredReaction"] != "NONE" for cs in group for c in cs)),
            str(sum(c["clip"]["risk"] == "HIGH" for cs in group for c in cs)),
        ])
    games = {}
    for row in pre.values():
        games[row[PRE_GAMES]] = games.get(row[PRE_GAMES], 0) + 1
    note = "Videospiele (alle 18): " + ", ".join(f"{k} {v}" for k, v in games.items())
    return rows, note


SHORT = {"LOW": "L", "MEDIUM": "M", "HIGH": "H", None: "–"}


def latex_table(path, colspec, headers, rows, caption, label, note=""):
    """Write a booktabs longtable for the thesis appendix (input via \\input)."""
    head = " & ".join(rf"\textbf{{{h}}}" for h in headers) + r" \\"
    lines = [r"\begingroup", r"\footnotesize", r"\setlength{\tabcolsep}{3pt}",
             rf"\begin{{longtable}}{{{colspec}}}",
             rf"\caption{{{caption}}}\label{{{label}}}\\",
             r"\toprule", head, r"\midrule", r"\endfirsthead",
             rf"\multicolumn{{{len(headers)}}}{{@{{}}l}}{{Tabelle~\ref{{{label}}}, Fortsetzung}}\\",
             r"\toprule", head, r"\midrule", r"\endhead",
             r"\midrule",
             rf"\multicolumn{{{len(headers)}}}{{r}}{{Fortsetzung auf der nächsten Seite}}\\",
             r"\endfoot", r"\bottomrule", r"\endlastfoot"]
    for row in rows:
        if row[0] == "Summe":
            lines.append(r"\midrule")
        lines.append(" & ".join(row) + r" \\")
    lines.append(r"\end{longtable}")
    if note:
        lines += [r"\vspace{-0.5\baselineskip}", rf"\noindent {note}\par"]
    lines.append(r"\endgroup")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_latex(directory, sessions, clips):
    """Raw-value tables for the appendix: technics, all clips, time after the gate."""
    directory.mkdir(parents=True, exist_ok=True)
    latex_table(
        directory / "anhang-technik.tex", "@{}l l r r r r r r l@{}",
        ["Sitzung", "Laufstatus", "Urteile", "Übern.", "Fehler", "Lat.~Med.", "Lat.~max",
         "Angebote", "Ausschn."],
        technical_rows(sessions, clips),
        "Technische Kennzahlen je Sitzung", "tab:anhang-technik",
        r"\texttt{INVALID}: keine nutzbare Aufnahme. Urteile: protokollierte Jev-Urteile, "
        r"Übern.: davon mit \texttt{applied = true}. Fehler: fehlgeschlagene Jev-Anfragen. "
        "Lat.~Med. und Lat.~max: Median und Maximum der Antwortzeit von Jev in ms. "
        "Ausschn.: bewertete Replay-Ausschnitte je Person.")
    rated = [c for c in clips if c["session"].name not in MEDIAN_EXCLUDED]
    rows = [[c["session"].name, c["person"], c["clip"]["episode"],
             format_time(c["clip"]["eventMs"]), ACTION[c["clip"]["action"]],
             SHORT[c["clip"]["risk"]], number(c["score"]), number(c["stuck"]),
             SHORT[c["old"]],
             "/".join(str(c["baseline"][item]) for item in EES_ITEMS),
             number(c["ees"]), str(c["baseline"]["supportNeed"]),
             WISH.get(c["baseline"]["desiredReaction"],
                      c["baseline"]["desiredReaction"]).replace("Vereinfachung", "Vereinf."),
             ("VL" if c["facilitated"] else "")
             + ("!" if c["clip"]["risk"] == "HIGH" and c["ees"] <= 2
                or c["clip"]["risk"] == "LOW" and c["ees"] >= 4 else "")]
            for c in rated]
    latex_table(
        directory / "anhang-ausschnitte.tex", "@{}l l l r l l r r l l r r l l@{}",
        ["Sitz.", "Pers.", "Ep.", "Zeit", "Aktion", "Jev", "Wert", "Blk.", "ZR",
         "fr/ir/di", "EES", "Hilfe", "Wunsch", "Marke"],
        rows, "Werte aller bewerteten Replay-Ausschnitte", "tab:anhang-ausschnitte",
        "Sitz.: Sitzung, Pers.: Person, Ep.: Episode. Zeit: Auswahlzeitpunkt in "
        "Minuten:Sekunden ab Sitzungsstart. Jev und ZR: Risikostufe von Jev "
        "und offline nachgerechneter Zählregel (L/M/H). Wert: persönlicher Risikowert "
        "(0 bis 2). Blk.: Blockadewert des zugehörigen Urteils. fr/ir/di: Antworten auf "
        "\\textit{Frustrated}, \\textit{Irritated}, \\textit{Dissatisfied} (1 bis 5), EES: "
        "deren Mittelwert. Hilfe: Hilfebedarf (1 bis 5). Marke VL: nach Hilfe der "
        "Versuchsleitung in derselben Episode. Marke !: H mit "
        "EES~$\\leq 2$ oder L mit EES~$\\geq 4$.")
    latex_table(
        directory / "anhang-zeitgrenze.tex", "@{}l l r r r r r@{}",
        ["Sitzung", "Episode", "Beginn", "Dauer", "Ohne gült.~Urteil", "Urteil unter 0,5",
         "Nach Angebot"],
        gate_rows(sessions), "Zeiträume nach erfüllter Zeitbedingung", "tab:anhang-zeitgrenze",
        "Zeiten in Minuten:Sekunden, Beginn ab Sitzungsstart. Aufteilung nach "
        "Abschnitt~\\ref{subsec:auswertungsplan}. Summen wurden aus ungerundeten Zeiten berechnet.")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path,
                        default=Path(__file__).resolve().parent.parent / "pilot-data")
    parser.add_argument("--markdown", action="store_true")
    parser.add_argument("--latex", type=Path, metavar="DIR",
                        help="write the appendix raw-value tables as LaTeX into DIR")
    args = parser.parse_args()
    sessions = {name: Session(name, records, summary)
                for name, (records, summary) in load_runs(args.data).items()}
    export, clips = load_clips(args.data, sessions)
    if args.latex:
        write_latex(args.latex, sessions, clips)
        print(f"LaTeX-Tabellen geschrieben nach {args.latex}")
        return

    def section(title, rows, headers, summary=False, note=""):
        print(f"\n{'## ' if args.markdown else ''}{title}\n")
        print_table(rows, args.markdown, headers, summary)
        if note:
            print(f"\n{note}")

    print(f"Replay-Export: {export}")
    section("Technik je Sitzung", technical_rows(sessions, clips),
            ["Sitzung", "Laufstatus", "Jev-Urteile", "angewendet", "Fehler",
             "Latenz Median ms", "Latenz max ms", "Angebote", "Ausschnitte je Person"])
    section("Jev-Stufe gegen Selbstauskunft (S02–S09)", level_rows(clips),
            ["Episoden", "Stufe", "Ausschnitte", "Personen", "EES Median", "EES Spanne",
             "EES ≤2 / 2–4 / ≥4", "Hilfebedarf Median", "Hilfebedarf Spanne",
             "Wunsch ≠ nichts", "nach VL-Eingriff"],
            note="EES = Mittel aus frustriert, gereizt, unzufrieden (1–5). "
                 "Stufe = protokollierte Jev-Stufe des Ausschnitts, bei Angeboten "
                 "die letzte Stufe vor der Entscheidung. Bewertung vor der Fortsetzung.")
    rated = [c for c in clips if c["session"].name not in MEDIAN_EXCLUDED]
    section("Alte Zählregel gegen Selbstauskunft (S02–S09)",
            level_rows(rated, key=lambda c: c["old"], groups=GROUPS[:1]),
            ["Episoden", "Stufe", "Ausschnitte", "Personen", "EES Median", "EES Spanne",
             "EES ≤2 / 2–4 / ≥4", "Hilfebedarf Median", "Hilfebedarf Spanne",
             "Wunsch ≠ nichts", "nach VL-Eingriff"],
            note="Stufe der offline nachgerechneten Zählregel zum selben Zeitpunkt.")
    section("Jev-Stufe (Zeilen) gegen alte Zählregel (Spalten)", comparison_rows(rated),
            ["Jev", "LOW", "MEDIUM", "HIGH", "keine"],
            note="keine = alte Regel hatte die Person in der Episode nicht registriert. "
                 f"Cohens Kappa (ungewichtet, nur vollständige Paare): "
                 f"{number(cohens_kappa(rated), 6)}.")
    print("\nSpearmans ρ (Risikostufe gegen EES, mittlere Ränge bei Bindungen): "
          f"Jev {number(spearmans_rho(rated, lambda c: c['clip']['risk']), 6)}, "
          f"Zählregel {number(spearmans_rho(rated, lambda c: c['old']), 6)}.")
    section("Gewünschte Reaktion gegen Systemaktion im Ausschnitt", wish_rows(clips),
            ["Wunsch", *ACTION.values(),
             "Summe"], summary=True)
    section("Zeit nach Erreichen der Zeitgrenze (S02–S09)", gate_rows(sessions),
            ["Sitzung", "Episode", "ab", "Dauer", *GATE_KINDS], summary=True,
            note="Zeitraum = Zustand verlässt B0 bis Rückkehr nach B0 oder Episodenende. "
                 "Ein Urteil bleibt nur bei unveränderten Eingabelisten gültig. Fortschritt, "
                 "angenommene Hilfe und stärkere Eingriffe verwerfen es. "
                 "Davon ohne Eingaben in der Phase: " + format_time(sum(
                     parts[GATE_EMPTY] for name, session in sessions.items()
                     if name not in MEDIAN_EXCLUDED
                     for *_, parts in gate_periods(session))) + ".")
    experience, games = experience_rows(args.data, clips)
    if experience:
        section("Escape-Room-Erfahrung gegen Selbstauskunft (S02–S09)", experience,
                ["Erfahrung", "Personen", "Ausschnitte", "EES Md", "Hilfebedarf Md",
                 "Wunsch ≠ nichts", "HIGH"],
                note="Md = Median der Personenmediane. " + games)
    section("Alle Hilfeangebote", offer_rows(sessions),
            ["Sitzung", "Episode", "Zeit", "Aktion", "Zustand", "Blockade p",
             "Stufe (Score) je Person", "angenommen", "bis Lösung", "VL"],
            note="Zeit ab Sitzungsstart. VL = Eingriff der Versuchsleitung vorher "
                 "in derselben Episode.")
    section("Bewertung der erlebten Angebote", rating_rows(clips),
            ["Sitzung", "Person", "Episode", "Aktion", "Stufe", "Wunsch",
             "Hilfebedarf", "gesehen", "Passung", "Nutzen", "Zeitpunkt", "Stärke"])
    section("Alle Ausschnitte", clip_rows(clips),
            ["Sitzung", "Person", "Clip", "Ep.", "Zeitpunkt", "Stufe", "Score",
             "Blockade p", "Alte Regel", "Aktion", "fr / ge / un", "EES", "Hilfe", "Wunsch",
             "Marke"],
            note="Zeitpunkt in Sitzungszeit: Stufenwechsel, Mitte der LOW-Phase oder "
                 "Systementscheidung (nicht der Videostart des Ausschnitts). "
                 "Marke: VL = nach Eingriff der Versuchsleitung in derselben Episode; "
                 "! = HIGH mit EES ≤ 2 oder LOW mit EES ≥ 4.")


if __name__ == "__main__":
    main()

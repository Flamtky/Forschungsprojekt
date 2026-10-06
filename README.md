# Forschungsprojekt: KI-gestützte Unterstützung in *The Last Hour*

Quellcode zur Forschungsarbeit über adaptive Hilfe im kooperativen Digital Educational Escape Room *The Last Hour*. Der Prototyp erweitert den Mehrspielerraum des [Dungeon-Projekts](https://github.com/Dungeon-CampusMinden/Dungeon). Der Server lässt Jev, ein System-One-Modell von TypeSafe, beurteilen, ob ein Team bei einem Rätsel feststeckt, und bietet dann einen Hinweis an.

## Inhalt

- `Java/`: Stand des Dungeon-Projekts mit dem Prototyp.
  - `game/src/rooms/lasthour/adaptation/`: Adaptionslogik und Anbindung an Jev
  - `game/src/rooms/lasthour/recording/`: Aufnahme für die Replay-Befragung
  - `tracking/`: Ereignisformat, Backend und Importer für die Sitzungsdaten
- `studie/`: Auswertungsskripte der Pilotstudie
  - `session_times.py`: Sitzungs- und Episodendauern
  - `auswertung.py`: Jev-Urteile, Replay-Bewertungen und Hilfeangebote
  - `zaehlregel.py`: Nachrechnung der festgelegten Zählregel
  - `abbildungen.py`: Datengrafiken
  - `vorabformular.gs`: Vorabbefragung als Google-Apps-Script

## Ausführen

Build und Voraussetzungen stehen in `Java/README.md`. Der Raum startet mit `./gradlew runTheLastHour` im Ordner `Java/`. Für die Jev-Urteile braucht der Server einen TypeSafe-API-Schlüssel in `TYPESAFE_API_KEY`.

Die Skripte in `studie/` erwarten die Rohdaten der Pilotstudie in `pilot-data/`. Diese Daten sind aus Datenschutzgründen nicht im Repository enthalten. Die Skripte brauchen nur die Python-Standardbibliothek, `abbildungen.py` zusätzlich matplotlib.

## Lizenz

Es gelten die Lizenzangaben des Dungeon-Projekts in `Java/LICENSE.md` und den zugehörigen Dateien.

# Fahrtenbuch

Android-App für ein digitales Fahrtenbuch (2bit e.U.).

- Fahrt per GPS aufzeichnen: Startadresse, Strecke, Zieladresse; Ende per Knopf oder automatisch nach Stillstand
- Am Ziel: Kategorie wählen (Kategorien frei definierbar)
- Gespeicherte Orte (Zuhause, Büro, Kunden) mit automatischer Erkennung
- Pause mit Zwischenziel: Hin- und Rückfahrt als eine Fahrt
- Berichte je Monat, Jahr und Zeitraum, getrennt nach Kategorie; Export als PDF und CSV
- Warnung vor fixen Blitzern in Österreich (Daten © OpenStreetMap-Mitwirkende, ODbL)
- In-App-Update über die Releases dieses Repos

## Installation

Die neueste `Fahrtenbuch-x.y.z.apk` unter [Releases](../../releases/latest) herunterladen und am Handy öffnen.
Danach aktualisiert sich die App unter *Einstellungen → App-Update* selbst.

## Bauen

Voraussetzungen: JDK 17, Android SDK (Platform 35).

```
gradlew testReleaseUnitTest assembleRelease
```

Signiert wird mit einem eigenen Schlüssel in `signing/` (nicht im Repo). Ohne diesen Ordner entsteht eine
unsignierte Release-APK; Updates einer installierten App sind nur mit demselben Schlüssel möglich.

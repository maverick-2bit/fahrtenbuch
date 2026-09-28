# Fahrtenbuch

Digitales Fahrtenbuch: Android-App, Web-App fürs iPhone und Online-Sicherung mit Verwaltungsseite
(s/e smarte events OG und 2bit e.U.).

- Fahrt per GPS aufzeichnen: Startadresse, Strecke, Zieladresse; Ende per Knopf oder automatisch nach Stillstand
- Am Ziel: Kategorie wählen (Kategorien frei definierbar)
- Gespeicherte Orte (Zuhause, Büro, Kunden) mit automatischer Erkennung
- Pause mit Zwischenziel: Hin- und Rückfahrt als eine Fahrt
- Berichte je Monat, Jahr und Zeitraum, getrennt nach Kategorie; Export als PDF und CSV
- Warnung vor fixen Blitzern in Österreich (Daten © OpenStreetMap-Mitwirkende, ODbL)
- In-App-Update über die Releases dieses Repos
- Online-Sicherung: jede abgeschlossene Fahrt und jede Änderung in einem unveränderlichen Protokoll;
  Verwaltungsseite mit Fahrtenbuch, Berichten und Änderungsprotokoll je Fahrer (`server/`, Cloudflare Worker + D1,
  https://fahrtenbuch.smarte.events/admin/)
- Privatfahrten mit persönlichem PIN: Details gehen nur verschlüsselt an den Server

## iPhone

Das iPhone nutzt die Web-App https://fahrtenbuch.smarte.events/app/ (Safari → Teilen → Zum Home-Bildschirm).
Sie zeichnet auf, solange sie geöffnet ist, und hält dafür den Bildschirm wach.

## Installation (Android)

Die neueste `Fahrtenbuch-x.y.z.apk` unter [Releases](../../releases/latest) herunterladen und am Handy öffnen.
Danach aktualisiert sich die App unter *Einstellungen → App-Update* selbst.

## Bauen

Server und Web-App: `cd server && npm test` (Vitest + Node-Tests), lokal starten mit `npm run dev`,
veröffentlichen mit `npm run deploy` (Migrationen + `wrangler deploy`, Konto s/e smarte events OG).

Android-App:

Voraussetzungen: JDK 17, Android SDK (Platform 35).

```
gradlew testReleaseUnitTest assembleRelease
```

Signiert wird mit einem eigenen Schlüssel in `signing/` (nicht im Repo). Ohne diesen Ordner entsteht eine
unsignierte Release-APK; Updates einer installierten App sind nur mit demselben Schlüssel möglich.

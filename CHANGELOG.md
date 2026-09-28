# Changelog

Alle Änderungen am Fahrtenbuch (Android-App und Server). Versionen nach SemVer; App und Server tragen dieselbe Nummer.

## [0.5.0] – 2026-09-28

### Neu
- Online-Sicherung zieht um: **fahrtenbuch.smarte.events** im Cloudflare-Konto der s/e smarte events OG,
  als Cloudflare Worker (statt Pages, dort investiert Cloudflare nicht mehr), Datenbank in der EU.
  App-Links und erlaubter Server der Android-App zeigen auf die neue Adresse.
- Privatfahrten mit persönlichem PIN: Kategorien lassen sich als privat markieren. Adressen, Zwischenziele,
  Notiz und Koordinaten solcher Fahrten werden am Handy verschlüsselt (AES-GCM, Schlüssel aus dem PIN per
  PBKDF2-SHA-256) und nur so gesichert. Datum, Zeiten und Kilometer bleiben für Berichte sichtbar.
- Verwaltungsseite: „Privatfahrten anzeigen“ entschlüsselt nach Eingabe des PINs im Browser; der PIN verlässt
  weder Handy noch Browser.
- Server: Spalten `fahrer.schluessel` und `fahrten.geheim` (Migration 0002).
- iPhone-Web-App unter `/app/` (zum Home-Bildschirm hinzufügen): GPS-Aufzeichnung, Pause mit Zwischenziel,
  automatisches Fahrtende, Kategorie-Abfrage, Fahrten bearbeiten und nachtragen, Berichte mit CSV,
  gespeicherte Orte, Online-Sicherung und PIN für Privatfahrten – gleiche Daten und gleiche Verschlüsselung wie
  die Android-App. Zeichnet auf, solange sie offen ist, und hält dafür den Bildschirm wach; Stücke bei
  ausgeschaltetem Bildschirm werden über die Straße nachgerechnet, die Ankunftszeit wird geschätzt. Startet auch
  ohne Netz.
- Server: Adressen und Straßenkilometer für die Web-App (`/api/v1/adresse`, `/koordinaten`, `/route`) über
  OpenStreetMap-Dienste (Nominatim, FOSSGIS-Routing) mit Zwischenspeicher, nur für verbundene Fahrer.
- Neues Gerät: `/api/v1/ich` liefert die Schlüsselhülle, die Web-App übernimmt damit per PIN den bisherigen
  Datenschlüssel – sonst wären die schon gesicherten Privatfahrten nicht mehr lesbar.
- Verbindungsseite: am iPhone Anleitung zur Web-App und „Link kopieren“.
- Android-App: Neues Handy oder neu installiert – hat der Server schon einen PIN-Schlüssel, fragt die App
  nach dem bisherigen PIN und übernimmt ihn, statt einen neuen anzulegen („PIN vergessen“ bleibt möglich).

- Android-App: Fahrt automatisch starten, wenn sich das Handy mit dem Auto verbindet (Bluetooth, Geräte in
  den Einstellungen wählbar) und das Auto losfährt – Start am Parkplatz zum Zeitpunkt des Losfahrens. Eine
  pausierte Fahrt geht beim Losfahren automatisch weiter. Solange das Auto verbunden ist, beendet Stillstand
  (Stau, Ampel) die Fahrt nicht. Braucht „Standort: Immer zulassen“, „Geräte in der Nähe“ und keine
  Akku-Optimierung; fehlt etwas, fragt die App beim Verbinden per Benachrichtigung.

### Geändert
- Fahrten werden erst ab dem Zuordnen einer Kategorie ins Änderungsprotokoll geschrieben und gesichert, damit
  eine Privatfahrt nie vorher im Klartext übertragen wird. Alteinträge aus 0.4 ohne Kategorie entfallen.
- Repository in die Organisation smarte-events verschoben; In-App-Update liest von dort.
- App-Datenbank Version 4 (Kategorie-Feld `privat`, „Privat“ wird automatisch privat).

### Behoben
- CSV-Export: Excel-Kennung (BOM) im Quelltext als Zeichencode statt als unsichtbares Zeichen (Lint-Fehler).
- In-App-Update: Scheitert die Installation, zeigt die App den Grund von Android an und bietet
  „Mit dem Android-Installer versuchen“ an – die schon geladene Datei geht dann an den normalen Android-Installer.

## [0.4.0] – 2026-09-28
- Online-Sicherung: Verbindung per QR-Code, Änderungsprotokoll je Fahrt, Sicherung im Hintergrund.
- Server (Cloudflare Pages + D1): Admin-Webseite mit Fahrtenbuch, Berichten, Kilometerstand,
  Änderungsprotokoll, PDF- und CSV-Export; unveränderliches Protokoll in der Datenbank.

## [0.3.0] – 2026-09-28
- Gespeicherte Orte mit automatischer Erkennung; Startadresse vorwählen und während der Fahrt ändern.
- Pause mit Zwischenziel: Hin- und Rückfahrt als eine Fahrt.

## [0.2.1] – 2026-09-28
- Standort-Abfrage beim Öffnen der App; untere Leiste bei offener Tastatur ausgeblendet.

## [0.2.0] – 2026-09-28
- Warnung vor fixen Blitzern in Österreich (OpenStreetMap), In-App-Update über GitHub-Releases.

## [0.1.0] – 2026-09-28
- Erste Version: GPS-Aufzeichnung mit automatischem Ende, Kategorien, Berichte mit PDF- und CSV-Export.

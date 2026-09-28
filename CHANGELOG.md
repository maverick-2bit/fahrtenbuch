# Changelog

Alle Änderungen am Fahrtenbuch (Android-App und Server). Versionen nach SemVer; App und Server tragen dieselbe Nummer.

## [0.5.0] – in Arbeit

### Neu
- Privatfahrten mit persönlichem PIN: Kategorien lassen sich als privat markieren. Adressen, Zwischenziele,
  Notiz und Koordinaten solcher Fahrten werden am Handy verschlüsselt (AES-GCM, Schlüssel aus dem PIN per
  PBKDF2-SHA-256) und nur so gesichert. Datum, Zeiten und Kilometer bleiben für Berichte sichtbar.
- Verwaltungsseite: „Privatfahrten anzeigen“ entschlüsselt nach Eingabe des PINs im Browser; der PIN verlässt
  weder Handy noch Browser.
- Server: Spalten `fahrer.schluessel` und `fahrten.geheim` (Migration 0002).

### Geändert
- Fahrten werden erst ab dem Zuordnen einer Kategorie ins Änderungsprotokoll geschrieben und gesichert, damit
  eine Privatfahrt nie vorher im Klartext übertragen wird. Alteinträge aus 0.4 ohne Kategorie entfallen.
- Repository in die Organisation smarte-events verschoben; In-App-Update liest von dort.
- App-Datenbank Version 4 (Kategorie-Feld `privat`, „Privat“ wird automatisch privat).

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

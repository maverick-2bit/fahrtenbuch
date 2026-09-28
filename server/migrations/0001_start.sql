-- Fahrtenbuch Online: Grundschema
-- Zeitangaben durchgehend als Epoch-Millisekunden (INTEGER).

CREATE TABLE fahrer (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  -- SHA-256 (hex) des Geräte-Codes; NULL = kein gültiger Code
  token_hash TEXT UNIQUE,
  aktiv INTEGER NOT NULL DEFAULT 1,
  erstellt INTEGER NOT NULL,
  zuletzt_sync INTEGER,
  geraet TEXT NOT NULL DEFAULT '',
  -- Angaben aus den App-Einstellungen (für Berichtskopf und Kilometerstand)
  fahrer_name TEXT NOT NULL DEFAULT '',
  fahrzeug TEXT NOT NULL DEFAULT '',
  kennzeichen TEXT NOT NULL DEFAULT '',
  km_stand_start INTEGER NOT NULL DEFAULT 0,
  km_stand_ab INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE kategorien (
  fahrer_id TEXT NOT NULL REFERENCES fahrer(id),
  id INTEGER NOT NULL,
  name TEXT NOT NULL,
  farbe INTEGER NOT NULL,
  sortierung INTEGER NOT NULL DEFAULT 0,
  aktiv INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (fahrer_id, id)
);

-- Aktueller Stand jeder Fahrt (abgeleitet aus dem Protokoll)
CREATE TABLE fahrten (
  uuid TEXT PRIMARY KEY,
  fahrer_id TEXT NOT NULL REFERENCES fahrer(id),
  version INTEGER NOT NULL,
  start_zeit INTEGER NOT NULL,
  ende_zeit INTEGER,
  start_adresse TEXT NOT NULL,
  ende_adresse TEXT NOT NULL,
  zwischenziele TEXT NOT NULL DEFAULT '',
  start_lat REAL,
  start_lon REAL,
  ende_lat REAL,
  ende_lon REAL,
  distanz_meter REAL NOT NULL,
  kategorie_id INTEGER,
  kategorie_name TEXT NOT NULL DEFAULT '',
  notiz TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL,
  geloescht INTEGER NOT NULL DEFAULT 0,
  geaendert INTEGER NOT NULL
);
CREATE INDEX fahrten_fahrer_zeit ON fahrten (fahrer_id, start_zeit);

-- Änderungsprotokoll: jede Fassung jeder Fahrt, nur anfügen.
-- Ändern und Löschen verhindert die Datenbank selbst (Trigger) – Grundlage für ein
-- revisionssicheres elektronisches Fahrtenbuch.
CREATE TABLE fahrten_protokoll (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  eintrag_id TEXT NOT NULL UNIQUE,
  uuid TEXT NOT NULL,
  fahrer_id TEXT NOT NULL,
  version INTEGER NOT NULL,
  aktion TEXT NOT NULL,
  quelle TEXT NOT NULL,
  zeit_geraet INTEGER NOT NULL,
  zeit_server INTEGER NOT NULL,
  daten TEXT NOT NULL,
  UNIQUE (uuid, version)
);
CREATE INDEX protokoll_uuid ON fahrten_protokoll (uuid, version);

CREATE TRIGGER protokoll_unveraenderlich_update BEFORE UPDATE ON fahrten_protokoll
BEGIN
  SELECT RAISE(ABORT, 'Das Änderungsprotokoll ist unveränderlich');
END;

CREATE TRIGGER protokoll_unveraenderlich_delete BEFORE DELETE ON fahrten_protokoll
BEGIN
  SELECT RAISE(ABORT, 'Das Änderungsprotokoll ist unveränderlich');
END;

-- Admin-Anmeldung
CREATE TABLE sitzungen (
  id_hash TEXT PRIMARY KEY,
  erstellt INTEGER NOT NULL,
  ablauf INTEGER NOT NULL,
  info TEXT NOT NULL DEFAULT ''
);

CREATE TABLE anmeldeversuche (
  ip TEXT PRIMARY KEY,
  anzahl INTEGER NOT NULL,
  seit INTEGER NOT NULL
);

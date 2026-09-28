import { fahrerPruefen } from "./anmeldung";
import { Env, HttpFehler, json, leseJson, text, zahl } from "./hilfen";

/** Stand einer Fahrt, wie ihn die App sendet. */
export interface FahrtDaten {
  startZeit: number;
  endeZeit: number | null;
  startAdresse: string;
  endeAdresse: string;
  zwischenziele: string;
  startLat: number | null;
  startLon: number | null;
  endeLat: number | null;
  endeLon: number | null;
  distanzMeter: number;
  kategorieId: number | null;
  notiz: string;
  status: string;
}

export interface SyncEintrag {
  eintragId: string;
  uuid: string;
  aktion: "neu" | "geaendert" | "geloescht";
  zeit: number;
  daten: FahrtDaten;
}

interface SyncAnfrage {
  app?: { version?: string; geraet?: string };
  einstellungen?: { fahrer?: string; fahrzeug?: string; kennzeichen?: string; kmStandStart?: number; kmStandAb?: number };
  kategorien?: { id: number; name: string; farbe: number; sortierung?: number; aktiv?: boolean }[];
  eintraege?: SyncEintrag[];
}

export const MAX_EINTRAEGE = 200;
const ID = /^[A-Za-z0-9-]{8,64}$/;

function daten(d: unknown): FahrtDaten {
  if (!d || typeof d !== "object") throw new HttpFehler(400, "Fahrtdaten fehlen");
  const o = d as Record<string, unknown>;
  const status = text(o.status, 20);
  if (status !== "offen" && status !== "fertig") throw new HttpFehler(400, "Ungültiger Status");
  const zwischen = text(o.zwischenziele, 20_000);
  if (zwischen) {
    try {
      if (!Array.isArray(JSON.parse(zwischen))) throw new Error();
    } catch {
      throw new HttpFehler(400, "Zwischenziele ungültig");
    }
  }
  return {
    startZeit: zahl(o.startZeit),
    endeZeit: zahl(o.endeZeit, true),
    startAdresse: text(o.startAdresse, 500),
    endeAdresse: text(o.endeAdresse, 500),
    zwischenziele: zwischen,
    startLat: zahl(o.startLat, true),
    startLon: zahl(o.startLon, true),
    endeLat: zahl(o.endeLat, true),
    endeLon: zahl(o.endeLon, true),
    distanzMeter: Math.max(0, zahl(o.distanzMeter)),
    kategorieId: zahl(o.kategorieId, true),
    notiz: text(o.notiz, 2_000),
    status,
  };
}

/** Prüft und vereinheitlicht einen Eintrag; wirft bei ungültigen Daten. */
export function eintragPruefen(e: unknown): SyncEintrag {
  if (!e || typeof e !== "object") throw new HttpFehler(400, "Eintrag ungültig");
  const o = e as Record<string, unknown>;
  const eintragId = text(o.eintragId, 64);
  const uuid = text(o.uuid, 64);
  if (!ID.test(eintragId) || !ID.test(uuid)) throw new HttpFehler(400, "Kennung ungültig");
  const aktion = o.aktion;
  if (aktion !== "neu" && aktion !== "geaendert" && aktion !== "geloescht") throw new HttpFehler(400, "Aktion ungültig");
  return { eintragId, uuid, aktion, zeit: zahl(o.zeit), daten: daten(o.daten) };
}

/**
 * Nimmt Änderungen der App entgegen. Jeder Eintrag wird als neue Fassung im unveränderlichen
 * Protokoll abgelegt (doppelt gesendete Einträge werden anhand der eintragId erkannt), danach
 * wird der aktuelle Stand der betroffenen Fahrten aus dem Protokoll übernommen.
 * Alles in wenigen Abfragen über json_each, unabhängig von der Anzahl der Einträge.
 */
export async function sync(req: Request, env: Env): Promise<Response> {
  const fahrer = await fahrerPruefen(req, env);
  const a = await leseJson<SyncAnfrage>(req);
  const roh = Array.isArray(a.eintraege) ? a.eintraege : [];
  if (roh.length > MAX_EINTRAEGE) throw new HttpFehler(413, `Höchstens ${MAX_EINTRAEGE} Einträge je Anfrage`);

  const gueltig: SyncEintrag[] = [];
  const abgelehnt: { eintragId: string; grund: string }[] = [];
  for (const r of roh) {
    try {
      gueltig.push(eintragPruefen(r));
    } catch (e) {
      const id = typeof (r as SyncEintrag)?.eintragId === "string" ? (r as SyncEintrag).eintragId : "";
      abgelehnt.push({ eintragId: id, grund: e instanceof Error ? e.message : "ungültig" });
    }
  }

  const jetzt = Date.now();
  const e = a.einstellungen ?? {};
  const kategorien = (Array.isArray(a.kategorien) ? a.kategorien : []).slice(0, 200).map((k) => ({
    id: zahl(k.id),
    name: text(k.name, 100),
    farbe: zahl(k.farbe),
    sortierung: typeof k.sortierung === "number" ? k.sortierung : 0,
    aktiv: k.aktiv === false ? 0 : 1,
  }));
  const eintraegeJson = JSON.stringify(
    gueltig.map((x) => ({ eintragId: x.eintragId, uuid: x.uuid, aktion: x.aktion, zeit: x.zeit, daten: x.daten })),
  );

  const db = env.DB;
  const anweisungen = [
    db.prepare(
      "UPDATE fahrer SET zuletzt_sync = ?, geraet = ?, fahrer_name = ?, fahrzeug = ?, kennzeichen = ?, " +
        "km_stand_start = ?, km_stand_ab = ? WHERE id = ?",
    ).bind(
      jetzt,
      text(`${a.app?.geraet ?? ""} · App ${a.app?.version ?? "?"}`, 300),
      text(e.fahrer ?? "", 100),
      text(e.fahrzeug ?? "", 100),
      text(e.kennzeichen ?? "", 40),
      Math.max(0, Math.round(typeof e.kmStandStart === "number" ? e.kmStandStart : 0)),
      Math.max(0, Math.round(typeof e.kmStandAb === "number" ? e.kmStandAb : 0)),
      fahrer.id,
    ),
  ];
  if (kategorien.length > 0) {
    anweisungen.push(
      db.prepare(
        "INSERT INTO kategorien (fahrer_id, id, name, farbe, sortierung, aktiv) " +
          "SELECT ?1, json_extract(value, '$.id'), json_extract(value, '$.name'), json_extract(value, '$.farbe'), " +
          "json_extract(value, '$.sortierung'), json_extract(value, '$.aktiv') FROM json_each(?2) WHERE true " +
          "ON CONFLICT (fahrer_id, id) DO UPDATE SET name = excluded.name, farbe = excluded.farbe, " +
          "sortierung = excluded.sortierung, aktiv = excluded.aktiv",
      ).bind(fahrer.id, JSON.stringify(kategorien)),
    );
  }
  if (gueltig.length > 0) {
    anweisungen.push(
      // 1. Protokoll: neue Fassungen anfügen (Versionen je Fahrt fortlaufend)
      db.prepare(
        `INSERT INTO fahrten_protokoll (eintrag_id, uuid, fahrer_id, version, aktion, quelle, zeit_geraet, zeit_server, daten)
         SELECT e.eintrag_id, e.uuid, ?1,
                COALESCE((SELECT MAX(p.version) FROM fahrten_protokoll p WHERE p.uuid = e.uuid), 0)
                  + ROW_NUMBER() OVER (PARTITION BY e.uuid ORDER BY e.idx),
                e.aktion, 'app', e.zeit, ?2, e.daten
         FROM (SELECT key AS idx,
                      json_extract(value, '$.eintragId') AS eintrag_id,
                      json_extract(value, '$.uuid') AS uuid,
                      json_extract(value, '$.aktion') AS aktion,
                      json_extract(value, '$.zeit') AS zeit,
                      json_extract(value, '$.daten') AS daten
               FROM json_each(?3)) e
         WHERE NOT EXISTS (SELECT 1 FROM fahrten_protokoll x WHERE x.eintrag_id = e.eintrag_id)
           AND NOT EXISTS (SELECT 1 FROM fahrten f WHERE f.uuid = e.uuid AND f.fahrer_id <> ?1)
           AND NOT EXISTS (SELECT 1 FROM fahrten_protokoll y WHERE y.uuid = e.uuid AND y.fahrer_id <> ?1)`,
      ).bind(fahrer.id, jetzt, eintraegeJson),
      // 2. Aktueller Stand = jeweils letzte Fassung aus dem Protokoll
      db.prepare(
        `INSERT INTO fahrten (uuid, fahrer_id, version, start_zeit, ende_zeit, start_adresse, ende_adresse, zwischenziele,
                              start_lat, start_lon, ende_lat, ende_lon, distanz_meter, kategorie_id, kategorie_name,
                              notiz, status, geloescht, geaendert)
         SELECT p.uuid, p.fahrer_id, p.version,
                json_extract(p.daten, '$.startZeit'), json_extract(p.daten, '$.endeZeit'),
                json_extract(p.daten, '$.startAdresse'), json_extract(p.daten, '$.endeAdresse'),
                json_extract(p.daten, '$.zwischenziele'),
                json_extract(p.daten, '$.startLat'), json_extract(p.daten, '$.startLon'),
                json_extract(p.daten, '$.endeLat'), json_extract(p.daten, '$.endeLon'),
                json_extract(p.daten, '$.distanzMeter'), json_extract(p.daten, '$.kategorieId'),
                COALESCE(k.name, ''), json_extract(p.daten, '$.notiz'), json_extract(p.daten, '$.status'),
                p.aktion = 'geloescht', p.zeit_geraet
         FROM fahrten_protokoll p
         LEFT JOIN kategorien k ON k.fahrer_id = p.fahrer_id AND k.id = json_extract(p.daten, '$.kategorieId')
         WHERE p.fahrer_id = ?1
           AND p.uuid IN (SELECT json_extract(value, '$.uuid') FROM json_each(?2))
           AND p.version = (SELECT MAX(q.version) FROM fahrten_protokoll q WHERE q.uuid = p.uuid)
         ON CONFLICT (uuid) DO UPDATE SET
           version = excluded.version, start_zeit = excluded.start_zeit, ende_zeit = excluded.ende_zeit,
           start_adresse = excluded.start_adresse, ende_adresse = excluded.ende_adresse,
           zwischenziele = excluded.zwischenziele, start_lat = excluded.start_lat, start_lon = excluded.start_lon,
           ende_lat = excluded.ende_lat, ende_lon = excluded.ende_lon, distanz_meter = excluded.distanz_meter,
           kategorie_id = excluded.kategorie_id, kategorie_name = excluded.kategorie_name, notiz = excluded.notiz,
           status = excluded.status, geloescht = excluded.geloescht, geaendert = excluded.geaendert
         WHERE fahrten.fahrer_id = excluded.fahrer_id`,
      ).bind(fahrer.id, eintraegeJson),
    );
  }
  await db.batch(anweisungen);

  // Bestätigt wird, was jetzt im Protokoll dieses Fahrers steht (auch früher schon angekommene Einträge)
  let angenommen: string[] = [];
  if (gueltig.length > 0) {
    const r = await db
      .prepare(
        "SELECT eintrag_id FROM fahrten_protokoll WHERE fahrer_id = ?1 AND eintrag_id IN (SELECT value FROM json_each(?2))",
      )
      .bind(fahrer.id, JSON.stringify(gueltig.map((x) => x.eintragId)))
      .all<{ eintrag_id: string }>();
    angenommen = r.results.map((x) => x.eintrag_id);
    const ok = new Set(angenommen);
    for (const g of gueltig) if (!ok.has(g.eintragId)) abgelehnt.push({ eintragId: g.eintragId, grund: "Fahrt gehört einem anderen Fahrer" });
  }
  return json({ fahrer: { name: fahrer.name }, angenommen, abgelehnt, serverZeit: jetzt });
}

/** Kurze Prüfung, ob der Geräte-Code gilt – für die Verbindung in der App. */
export async function ich(req: Request, env: Env): Promise<Response> {
  const f = await fahrerPruefen(req, env);
  return json({ fahrer: { name: f.name } });
}

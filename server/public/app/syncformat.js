// Datenformat zwischen Web-App und Server – wie in der Android-App (sync/SyncFormat.kt), siehe server/src/sync.ts.
import { geheimErstellen } from "./krypto.js";

/** Stand einer Fahrt, wie ihn der Server erwartet (gleiche Felder und Reihenfolge wie die Android-App). */
export function fahrtDaten(f) {
  return {
    startZeit: f.startZeit,
    endeZeit: f.endeZeit ?? null,
    startAdresse: f.startAdresse ?? "",
    endeAdresse: f.endeAdresse ?? "",
    zwischenziele: f.zwischenziele ?? "",
    startLat: f.startLat ?? null,
    startLon: f.startLon ?? null,
    endeLat: f.endeLat ?? null,
    endeLon: f.endeLon ?? null,
    distanzMeter: f.distanzMeter ?? 0,
    kategorieId: f.kategorieId ?? null,
    notiz: f.notiz ?? "",
    status: f.status,
  };
}

/** Diese Felder einer Privatfahrt gehen nur verschlüsselt an den Server. */
export const GEHEIME_FELDER = ["startAdresse", "endeAdresse", "zwischenziele", "notiz", "startLat", "startLon", "endeLat", "endeLon"];

/** Ersetzt die Details einer Privatfahrt durch deren verschlüsselte Form (geheim). */
export async function verschluesseln(daten, datenSchluessel) {
  const klar = {};
  for (const k of GEHEIME_FELDER) klar[k] = daten[k] ?? null;
  const ergebnis = { ...daten, startAdresse: "", endeAdresse: "", zwischenziele: "", notiz: "", startLat: null, startLon: null, endeLat: null, endeLon: null };
  ergebnis.geheim = await geheimErstellen(datenSchluessel, JSON.stringify(klar));
  return ergebnis;
}

/**
 * Bereitet Protokolleinträge für die Übertragung vor: Einträge von Privatfahrten werden verschlüsselt.
 * Ohne PIN bleiben sie auf dem Gerät – samt allen späteren Einträgen derselben Fahrt, damit die
 * Reihenfolge der Fassungen am Server stimmt. Einträge noch nicht zugeordneter Fahrten entfallen.
 * @param eintraege [{eintragId, fahrtUuid, aktion, zeit, daten}] in Protokollreihenfolge
 * @param privateKategorien Set der IDs privater Kategorien
 * @param datenSchluessel Uint8Array oder null (kein PIN festgelegt)
 */
export async function vorbereiten(eintraege, privateKategorien, datenSchluessel) {
  const senden = [];
  const entfallen = [];
  const wartendeFahrten = new Set();
  let wartenAufPin = 0;
  for (const e of eintraege) {
    const d = e.daten;
    if (d.status !== "fertig" && e.aktion !== "geloescht") {
      entfallen.push(e.eintragId);
      continue;
    }
    const privat = d.kategorieId !== null && d.kategorieId !== undefined && privateKategorien.has(d.kategorieId);
    if (wartendeFahrten.has(e.fahrtUuid)) wartenAufPin++;
    else if (!privat) senden.push(e);
    else if (!datenSchluessel) {
      wartendeFahrten.add(e.fahrtUuid);
      wartenAufPin++;
    } else senden.push({ ...e, daten: await verschluesseln(d, datenSchluessel) });
  }
  return { senden, wartenAufPin, entfallen };
}

/** Anfrage an /api/v1/sync. */
export function anfrage({ version, geraet, einstellungen = {}, kategorien = [], eintraege = [], schluessel = null }) {
  const a = {
    app: { version, geraet },
    einstellungen: {
      fahrer: einstellungen.fahrer ?? "",
      fahrzeug: einstellungen.fahrzeug ?? "",
      kennzeichen: einstellungen.kennzeichen ?? "",
      kmStandStart: einstellungen.kmStandStart ?? 0,
      kmStandAb: einstellungen.kmStandAb ?? 0,
    },
    kategorien: kategorien.map((k) => ({ id: k.id, name: k.name, farbe: k.farbe, sortierung: k.sortierung, aktiv: k.aktiv, privat: k.privat })),
    eintraege: eintraege.map((e) => ({ eintragId: e.eintragId, uuid: e.fahrtUuid, aktion: e.aktion, zeit: e.zeit, daten: e.daten })),
  };
  if (schluessel) a.schluessel = schluessel;
  return a;
}

const CODE = /^[A-Za-z0-9_-]{20,100}$/;

/**
 * Liest den Verbindungscode aus dem QR-Link (https://…/verbinden#code=…), dem Rückfall-Link der
 * Android-App (fahrtenbuch://verbinden?server=…&code=…) oder einem nackten Code. Die Web-App sichert
 * immer auf dem Server, von dem sie geladen wurde – Links zu einem anderen Server werden abgelehnt.
 * @returns {{code: string} | {fehler: string}}
 */
export function verbindungLesen(text, origin) {
  const t = String(text ?? "").trim();
  if (CODE.test(t)) return { code: t };
  let url;
  try {
    url = new URL(t);
  } catch {
    return { fehler: "Das ist kein Verbindungs-Link." };
  }
  let server;
  let code;
  if (url.protocol === "https:" && url.pathname.startsWith("/verbinden")) {
    server = url.origin;
    code = new URLSearchParams(url.hash.slice(1)).get("code");
  } else if (url.protocol === "fahrtenbuch:") {
    const p = new URLSearchParams(url.search || t.slice(t.indexOf("?")));
    server = (p.get("server") ?? origin).replace(/\/+$/, "");
    code = p.get("code");
  } else {
    return { fehler: "Das ist kein Verbindungs-Link." };
  }
  if (server.toLowerCase() !== origin.toLowerCase()) return { fehler: "Der Link gehört zu einem anderen Server." };
  if (!code || !CODE.test(code)) return { fehler: "Der Link ist unvollständig." };
  return { code };
}

/** Gerätebeschreibung für die Verwaltungsseite, z. B. „iPhone · iOS 18.5 · Web-App“. */
export function geraetBeschreiben(ua, standalone) {
  const ios = /OS (\d+)[_.](\d+)/.exec(ua);
  const geraet = /iPad/.test(ua) ? "iPad" : /iPhone/.test(ua) ? "iPhone" : /Android/.test(ua) ? "Android" : "Browser";
  return [geraet, ios && geraet !== "Android" ? `iOS ${ios[1]}.${ios[2]}` : null, standalone ? "Web-App" : "Browser-Tab"].filter(Boolean).join(" · ");
}

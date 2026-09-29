// Online-Sicherung der Web-App: Verbindung, Übertragung des Änderungsprotokolls, PIN für Privatfahrten
// und die Kartendienste des Servers (Adressen, Straßenkilometer). Ablauf wie in der Android-App (sync/Sicherung.kt).
import * as db from "./daten.js";
import { ausB64, b64, huelleErstellen, huelleOeffnen, MIN_PIN, zufall } from "./krypto.js";
import { anfrage, geraetBeschreiben, vorbereiten } from "./syncformat.js";

export const VERSION = "0.7.2";
const JE_ANFRAGE = 100;

/** Als Web-App vom Home-Bildschirm gestartet (nicht im Safari-Tab)? */
export const alsWebApp = () => window.matchMedia?.("(display-mode: standalone)").matches || navigator.standalone === true;

const hoerer = new Set();
export const beiAenderung = (fn) => (hoerer.add(fn), () => hoerer.delete(fn));
const melden = () => hoerer.forEach((h) => h());

/** Meldungen, die nur fehlendes Netz bedeuten (kein Fehler der Daten). */
export const OHNE_NETZ = ["Keine Internetverbindung.", "Server nicht erreichbar."];

class ApiFehler extends Error {
  constructor(status, text) {
    super(text);
    this.status = status;
  }
}

async function api(pfad, body, code, timeoutMs = 30_000) {
  const abbruch = new AbortController();
  const uhr = setTimeout(() => abbruch.abort(), timeoutMs);
  try {
    const r = await fetch(pfad, {
      method: body === undefined ? "GET" : "POST",
      headers: { authorization: `Bearer ${code}`, ...(body === undefined ? {} : { "content-type": "application/json" }) },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: "no-store",
      signal: abbruch.signal,
    });
    const d = await r.json().catch(() => ({}));
    if (!r.ok) throw new ApiFehler(r.status, d.fehler || `Server meldet Fehler ${r.status}`);
    return d;
  } catch (e) {
    if (e instanceof ApiFehler) throw e;
    throw new ApiFehler(0, navigator.onLine === false ? "Keine Internetverbindung." : "Server nicht erreichbar.");
  } finally {
    clearTimeout(uhr);
  }
}

export const verbindung = () => db.wert("sync", null);

async function ergebnisMerken(zuletzt, meldung, fahrer) {
  await db.wertAendern("sync", (s) => ({ ...s, ...(zuletzt ? { zuletzt } : {}), meldung, ...(fahrer ? { fahrer } : {}) }));
}

/**
 * Verbindet die Web-App mit einem Fahrer. Liefert auch die Schlüsselhülle vom Server: Gibt es sie
 * schon (früheres Gerät), muss die Web-App diesen Schlüssel per PIN übernehmen statt einen neuen anzulegen.
 */
export async function verbinden(code) {
  const d = await api("/api/v1/ich", undefined, code);
  await db.wertSetzen("sync", { code, fahrer: d.fahrer?.name ?? "", zuletzt: 0, meldung: "" });
  const server = d.schluessel ?? null;
  const p = await db.wertAendern("privat", (p) => {
    if (!server) return p.huelle ? { ...p, huelleGesendet: false } : p; // eigene Hülle (neu) übertragen
    if (JSON.stringify(server) === JSON.stringify(p.huelle)) return { ...p, huelleGesendet: true, offeneHuelle: undefined };
    // Hülle eines früheren Geräts: erst per PIN übernehmen – bis dahin warten Privatfahrten, und die
    // eigene Hülle überschreibt die am Server nicht (sonst wären die alten Privatfahrten unlesbar)
    return { ...p, offeneHuelle: server };
  });
  melden();
  return { fahrer: d.fahrer?.name ?? "", pinUebernehmen: !!p.offeneHuelle };
}

export async function trennen() {
  await db.wertSetzen("sync", null);
  melden();
}

// ------------------------------------------------------------------ PIN für Privatfahrten

export const privatStand = () => db.wert("privat", {});

export const pinGueltig = (pin) => new RegExp(`^\\d{${MIN_PIN},}$`).test(pin);

/**
 * Legt den PIN fest oder ändert ihn. Der Datenschlüssel bleibt dabei derselbe (einmal angelegt).
 * Wartet noch die Hülle eines früheren Geräts, ersetzt der neue PIN sie („PIN vergessen“).
 */
export async function pinFestlegen(pin) {
  if (!pinGueltig(pin)) throw new Error(`Der PIN braucht mindestens ${MIN_PIN} Ziffern.`);
  const p = await privatStand();
  const dek = p.datenSchluessel ? ausB64(p.datenSchluessel) : zufall(32);
  const huelle = await huelleErstellen(pin, dek);
  await db.wertSetzen("privat", { datenSchluessel: b64(dek), huelle, huelleGesendet: false });
  melden();
  bald();
}

/** Übernimmt den Datenschlüssel eines früheren Geräts (Hülle vom Server); wirft bei falschem PIN. */
export async function schluesselUebernehmen(pin) {
  const p = await privatStand();
  if (!p.offeneHuelle) return;
  const dek = await huelleOeffnen(p.offeneHuelle, pin);
  await db.wertSetzen("privat", { datenSchluessel: b64(dek), huelle: p.offeneHuelle, huelleGesendet: true });
  melden();
  bald();
}

// ------------------------------------------------------------------ Übertragung

let aktiv = null;
let nochmal = false;
let uhr = null;

/** Sichert in wenigen Sekunden – mehrere Änderungen kurz hintereinander gehen gemeinsam hinaus. */
export function bald(ms = 3_000) {
  clearTimeout(uhr);
  uhr = setTimeout(() => synchronisieren(), ms);
}

/** Überträgt das Änderungsprotokoll; läuft nie doppelt. */
export function synchronisieren() {
  if (aktiv) {
    nochmal = true;
    return aktiv;
  }
  aktiv = (async () => {
    let e;
    do {
      nochmal = false;
      e = await einmal();
    } while (nochmal);
    return e;
  })().finally(() => {
    aktiv = null;
    melden();
  });
  melden();
  return aktiv;
}

export const laeuft = () => aktiv !== null;

async function einmal() {
  const stand = await verbindung();
  if (!stand?.code) return { art: "nicht-verbunden" };
  try {
    const kategorien = await db.kategorien();
    const privat = await privatStand();
    // Solange die Hülle eines früheren Geräts auf den PIN wartet, gehen keine Privatfahrten hinaus
    const dek = privat.datenSchluessel && !privat.offeneHuelle ? ausB64(privat.datenSchluessel) : null;
    const v = await vorbereiten(await db.protokollOffen(), new Set(kategorien.filter((k) => k.privat).map((k) => k.id)), dek);
    await db.protokollMarkieren(v.entfallen, 3);
    // Hülle des Datenschlüssels mitschicken, bis der Server sie bestätigt hat
    let huelle = privat.huelle && !privat.huelleGesendet && !privat.offeneHuelle ? privat.huelle : null;
    const stuecke = [];
    for (let i = 0; i < v.senden.length; i += JE_ANFRAGE) stuecke.push(v.senden.slice(i, i + JE_ANFRAGE));
    if (!stuecke.length) stuecke.push([]); // auch ohne Einträge: Kategorien, Fahrzeug, Schlüssel, „zuletzt gesichert“
    let gesendet = 0;
    let fahrer = "";
    for (const stueck of stuecke) {
      const body = anfrage({
        version: VERSION,
        geraet: geraetBeschreiben(navigator.userAgent, alsWebApp()),
        einstellungen: await db.wert("einstellungen", {}),
        kategorien,
        eintraege: stueck,
        schluessel: huelle,
      });
      const d = await api("/api/v1/sync", body, stand.code, 60_000);
      if (huelle) {
        const gesendeteHuelle = JSON.stringify(huelle);
        // Nur bestätigen, wenn inzwischen kein neuer PIN festgelegt wurde
        await db.wertAendern("privat", (p) => (JSON.stringify(p.huelle) === gesendeteHuelle ? { ...p, huelleGesendet: true } : p));
        huelle = null;
      }
      await db.protokollMarkieren(d.angenommen ?? [], 1);
      for (const a of d.abgelehnt ?? []) if (a.eintragId) await db.protokollMarkieren([a.eintragId], 2, a.grund ?? "");
      gesendet += d.angenommen?.length ?? 0;
      fahrer = d.fahrer?.name ?? fahrer;
    }
    await ergebnisMerken(Date.now(), v.wartenAufPin > 0 ? "Privatfahrten warten auf deinen PIN." : "", fahrer);
    return { art: "ok", gesendet, wartenAufPin: v.wartenAufPin };
  } catch (e) {
    const text =
      e.status === 401 ? "Code ungültig – bitte neu verbinden." : e.status === 403 ? "Fahrer ist gesperrt." : e.message || "Sicherung fehlgeschlagen.";
    await ergebnisMerken(null, text);
    return { art: e.status === 401 || e.status === 403 ? "abgewiesen" : "fehler", text };
  }
}

// ------------------------------------------------------------------ Kartendienste (nur verbunden)

async function kartendienst(pfad, body, timeoutMs) {
  const s = await verbindung();
  if (!s?.code) return null;
  try {
    return await api(pfad, body, s.code, timeoutMs);
  } catch {
    return null;
  }
}

/** Adresse zu einer Position („Straße Nr, PLZ Ort“) oder null. */
export async function adresseErmitteln(lat, lon, timeoutMs = 8_000) {
  const d = await kartendienst("/api/v1/adresse", { lat, lon }, timeoutMs);
  return d?.adresse || null;
}

/**
 * Koordinaten zu einer Adresse (gespeicherte Orte, korrigierter Start) oder null.
 * Mit nahe ({lat, lon}) nur in der Umgebung – so findet „Hauptplatz 1“ den im eigenen Ort.
 */
export async function koordinatenSuchen(adresse, nahe = null) {
  const body = nahe ? { adresse, nahe: { lat: nahe.lat, lon: nahe.lon } } : { adresse };
  const d = await kartendienst("/api/v1/koordinaten", body, 10_000);
  return typeof d?.lat === "number" && typeof d?.lon === "number" ? { lat: d.lat, lon: d.lon } : null;
}

/** Straßenkilometer und Fahrzeit zwischen zwei Punkten ({meter, sekunden}) oder null. */
export async function routeErmitteln(von, nach) {
  const d = await kartendienst("/api/v1/route", { von: { lat: von.lat, lon: von.lon }, nach: { lat: nach.lat, lon: nach.lon } }, 10_000);
  return typeof d?.meter === "number" ? d : null;
}

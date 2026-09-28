// Lokale Datenbank der Web-App (IndexedDB) – Aufbau wie die Room-Datenbank der Android-App:
// Fahrten, Änderungsprotokoll, Kategorien und einfache Werte (Einstellungen, Orte, Verbindung, Schlüssel).
import { fahrtDaten } from "./syncformat.js";

const NAME = "fahrtenbuch";
const VERSION = 1;

export const PALETTE = [0xff1e88e5, 0xff43a047, 0xfffb8c00, 0xffe53935, 0xff8e24aa, 0xff00acc1, 0xff6d4c41, 0xff546e7a, 0xffd81b60, 0xffc0ca33];

/** Startkategorien wie in der Android-App; „Privat“ ist gleich als privat markiert. */
const STANDARD_KATEGORIEN = [
  { id: 1, name: "Dienstlich", farbe: PALETTE[0], sortierung: 1, aktiv: true, privat: false },
  { id: 2, name: "Privat", farbe: PALETTE[1], sortierung: 2, aktiv: true, privat: true },
  { id: 3, name: "Arbeitsweg", farbe: PALETTE[2], sortierung: 3, aktiv: true, privat: false },
];

let verbindung = null;

function db() {
  verbindung ??= new Promise((ok, fehler) => {
    const r = indexedDB.open(NAME, VERSION);
    r.onupgradeneeded = () => {
      const d = r.result;
      const fahrten = d.createObjectStore("fahrten", { keyPath: "uuid" });
      fahrten.createIndex("status", "status");
      const protokoll = d.createObjectStore("protokoll", { keyPath: "id", autoIncrement: true });
      protokoll.createIndex("gesendet", "gesendet");
      protokoll.createIndex("fahrtUuid", "fahrtUuid");
      const kategorien = d.createObjectStore("kategorien", { keyPath: "id" });
      for (const k of STANDARD_KATEGORIEN) kategorien.put(k);
      d.createObjectStore("werte");
    };
    r.onsuccess = () => {
      const d = r.result;
      d.onversionchange = () => d.close();
      ok(d);
    };
    r.onerror = () => fehler(r.error);
    r.onblocked = () => fehler(new Error("Die Datenbank ist blockiert – bitte andere Fenster des Fahrtenbuchs schließen."));
  });
  return verbindung;
}

const warte = (r) =>
  new Promise((ok, fehler) => {
    r.onsuccess = () => ok(r.result);
    r.onerror = () => fehler(r.error);
  });

/**
 * Führt fn in einer Transaktion aus. In fn nur auf IndexedDB-Anfragen warten (sonst schließt der
 * Browser die Transaktion vorzeitig). Das Ergebnis gilt erst nach dem Abschluss der Transaktion.
 */
async function tx(stores, modus, fn) {
  const d = await db();
  return new Promise((ok, fehler) => {
    const t = d.transaction(stores, modus);
    let ergebnis;
    t.oncomplete = () => ok(ergebnis);
    t.onerror = () => fehler(t.error);
    t.onabort = () => fehler(t.error ?? new Error("Speichern abgebrochen"));
    Promise.resolve()
      .then(() => fn(t))
      .then(
        (e) => (ergebnis = e),
        (e) => {
          try {
            t.abort();
          } catch {
            /* schon beendet */
          }
          fehler(e);
        },
      );
  });
}

// ------------------------------------------------------------------ Werte

export async function wert(schluessel, standard = null) {
  const w = await tx(["werte"], "readonly", (t) => warte(t.objectStore("werte").get(schluessel)));
  return w ?? standard;
}

export function wertSetzen(schluessel, w) {
  return tx(["werte"], "readwrite", (t) => {
    if (w === null || w === undefined) t.objectStore("werte").delete(schluessel);
    else t.objectStore("werte").put(w, schluessel);
  });
}

/** Ändert einen gespeicherten Wert in einem Schritt (ohne dass eine andere Änderung dazwischenkommt). */
export function wertAendern(schluessel, fn, standard = {}) {
  return tx(["werte"], "readwrite", async (t) => {
    const s = t.objectStore("werte");
    const neu = fn((await warte(s.get(schluessel))) ?? standard);
    s.put(neu, schluessel);
    return neu;
  });
}

// ------------------------------------------------------------------ Kategorien

export function kategorien() {
  return tx(["kategorien"], "readonly", async (t) =>
    (await warte(t.objectStore("kategorien").getAll())).sort((a, b) => a.sortierung - b.sortierung || a.id - b.id),
  );
}

export function kategorieSpeichern(k) {
  return tx(["kategorien"], "readwrite", async (t) => {
    const s = t.objectStore("kategorien");
    let neu = { ...k, name: k.name.trim() };
    if (neu.id === undefined || neu.id === null) {
      const alle = await warte(s.getAll());
      neu = {
        ...neu,
        id: Math.max(0, ...alle.map((x) => x.id)) + 1,
        sortierung: Math.max(0, ...alle.map((x) => x.sortierung)) + 1,
        aktiv: true,
      };
    }
    s.put(neu);
    return neu;
  });
}

/** Kategorien mit Fahrten werden nur ausgeblendet, damit alte Berichte ihre Zuordnung behalten. */
export function kategorieEntfernen(id) {
  return tx(["kategorien", "fahrten"], "readwrite", async (t) => {
    const benutzt = (await warte(t.objectStore("fahrten").getAll())).some((f) => f.kategorieId === id);
    const s = t.objectStore("kategorien");
    if (benutzt) {
      const k = await warte(s.get(id));
      if (k) s.put({ ...k, aktiv: false });
      return "ausgeblendet";
    }
    s.delete(id);
    return "geloescht";
  });
}

// ------------------------------------------------------------------ Fahrten

export const fahrt = (uuid) => tx(["fahrten"], "readonly", (t) => warte(t.objectStore("fahrten").get(uuid)));

const nachStatus = (status) =>
  tx(["fahrten"], "readonly", (t) => warte(t.objectStore("fahrten").index("status").getAll(status)));

export async function laufendeFahrt() {
  return (await nachStatus("laufend")).sort((a, b) => b.startZeit - a.startZeit)[0] ?? null;
}

export async function offeneFahrten() {
  return (await nachStatus("offen")).sort((a, b) => a.startZeit - b.startZeit);
}

export async function fertigeFahrten() {
  return (await nachStatus("fertig")).sort((a, b) => a.startZeit - b.startZeit);
}

/** Alle Fahrten außer der laufenden, neueste zuerst. */
export async function alleFahrten() {
  const alle = await tx(["fahrten"], "readonly", (t) => warte(t.objectStore("fahrten").getAll()));
  return alle.filter((f) => f.status !== "laufend").sort((a, b) => b.startZeit - a.startZeit);
}

/**
 * Protokolliert erst ab dem Zuordnen einer Kategorie (Status „fertig“): Bis dahin ist nicht bekannt,
 * ob es eine Privatfahrt ist, die nur verschlüsselt den Server erreichen darf. Löschungen nur, wenn
 * die Fahrt schon im Protokoll steht. @returns true, wenn ein Eintrag geschrieben wurde
 */
async function protokollieren(t, f, aktion = null) {
  const p = t.objectStore("protokoll");
  const bekannt = (await warte(p.index("fahrtUuid").count(f.uuid))) > 0;
  if (aktion === "geloescht") {
    if (!bekannt) return false;
  } else if (f.status !== "fertig") {
    return false;
  }
  p.add({
    eintragId: crypto.randomUUID(),
    fahrtUuid: f.uuid,
    aktion: aktion ?? (bekannt ? "geaendert" : "neu"),
    zeit: Date.now(),
    daten: fahrtDaten(f),
    gesendet: 0,
    meldung: "",
  });
  return true;
}

/** Legt eine Fahrt an oder speichert sie – abgeschlossene Fahrten samt Protokolleintrag (eine Transaktion). */
export function fahrtSpeichern(f) {
  return tx(["fahrten", "protokoll"], "readwrite", (t) => {
    t.objectStore("fahrten").put(f);
    return protokollieren(t, f);
  });
}

export function fahrtLoeschen(uuid) {
  return tx(["fahrten", "protokoll"], "readwrite", async (t) => {
    const s = t.objectStore("fahrten");
    const f = await warte(s.get(uuid));
    if (!f) return false;
    s.delete(uuid);
    return protokollieren(t, f, "geloescht");
  });
}

/**
 * Ändert eine noch nicht abgeschlossene Fahrt (laufend oder offen) ohne Protokolleintrag.
 * @param fn bekommt die gespeicherte Fahrt und liefert die Änderungen
 * @returns die geänderte Fahrt oder null, wenn sie nicht mehr in diesem Zustand ist
 */
export function unfertigAendern(uuid, fn, erlaubt = ["laufend", "offen"]) {
  return tx(["fahrten"], "readwrite", async (t) => {
    const s = t.objectStore("fahrten");
    const f = await warte(s.get(uuid));
    if (!f || !erlaubt.includes(f.status)) return null;
    const neu = { ...f, ...fn(f) };
    s.put(neu);
    return neu;
  });
}

// ------------------------------------------------------------------ Änderungsprotokoll

/** Noch nicht übertragene Einträge in Protokollreihenfolge. */
export function protokollOffen(max = 5_000) {
  return tx(["protokoll"], "readonly", async (t) =>
    (await warte(t.objectStore("protokoll").index("gesendet").getAll(0, max))).sort((a, b) => a.id - b.id),
  );
}

/** @param gesendet 1 = angenommen, 2 = abgelehnt, 3 = entfallen */
export function protokollMarkieren(eintragIds, gesendet, meldung = "") {
  if (!eintragIds.length) return Promise.resolve();
  const ids = new Set(eintragIds);
  return tx(["protokoll"], "readwrite", async (t) => {
    const s = t.objectStore("protokoll");
    for (const e of await warte(s.index("gesendet").getAll(0))) {
      if (ids.has(e.eintragId)) s.put({ ...e, gesendet, meldung });
    }
  });
}

export function protokollZaehlen() {
  return tx(["protokoll"], "readonly", async (t) => {
    const i = t.objectStore("protokoll").index("gesendet");
    return { offen: await warte(i.count(0)), abgelehnt: await warte(i.count(2)) };
  });
}

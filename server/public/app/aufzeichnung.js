// Aufzeichnung einer Fahrt in der Web-App – Ablauf wie im TrackingService der Android-App:
// Start → Startadresse (gespeicherter Ort oder GPS) → laufende Positionen (Strecke, Stillstand) →
// optional Pausen mit Zwischenziel → Ende per Knopf oder nach Stillstand → Zieladresse → Status „offen“.
//
// Eigenheiten des iPhones: Die Web-App zeichnet nur auf, solange sie sichtbar ist. Der Bildschirm
// wird deshalb wach gehalten (Wake Lock). Geht er doch aus, fehlt ein Stück: Die Straßenkilometer
// dafür rechnet der Server nach, und die Ankunftszeit wird geschätzt (strecke.js).
import * as db from "./daten.js";
import { alsAdresse, erkennen, istPausiert, koordinatenText, zwischenzieleLesen, zwischenzieleSchreiben } from "./orte.js";
import { adresseErmitteln, koordinatenSuchen, routeErmitteln, verbindung } from "./sicherung.js";
import {
  ankunftSchaetzen,
  distanzMeter,
  LUECKE_M,
  nachtragAnwenden,
  nachtragAusRoute,
  nachtragVorpruefen,
  StillstandErkennung,
  StreckenRechner,
} from "./strecke.js";

/** Fahrten unter dieser Strecke werden beim automatischen Ende verworfen. */
const MIN_FAHRT_M = 100;
const TICK_MS = 15_000;
/** Nach dem Wiedereinschalten so lange auf eine neue Position warten, bevor Stillstand zählt. */
const SCHONFRIST_MS = 2 * 60_000;

let fahrt = null;
let rechner = null;
let stillstand = null;
/** Gespeicherter Zusatzstand: letzte Lücke, wann die App zuletzt verborgen wurde, letzte Position. */
let zustand = leererZustand();
let beobachter = null;
let ticker = null;
let sperre = null;
let sichtbarSeit = Date.now();
let letzterFix = 0;
let kmh = null;
let gpsMeldung = "";
let beendet = null;
/** Kilometer-Nachtrag nach korrigiertem Start: {text, fehler} – Berechnung läuft oder Grund, warum er fehlt. */
let nachtragStand = null;

function leererZustand() {
  return { luecke: null, verborgenAb: null, letzteLage: null, nachgerechnetM: 0 };
}

const hoerer = new Set();
export const beiAenderung = (fn) => (hoerer.add(fn), () => hoerer.delete(fn));
const melden = () => hoerer.forEach((h) => h());

// Alle Änderungen nacheinander – Positionen, Knöpfe und Zeitgeber greifen auf denselben Stand zu
let kette = Promise.resolve();
function nacheinander(fn) {
  const p = kette.then(fn);
  kette = p.catch((e) => console.error(e));
  return p;
}

export function stand() {
  return {
    fahrt,
    meter: rechner?.meter ?? fahrt?.distanzMeter ?? 0,
    kmh,
    gpsMeldung,
    wach: sperre !== null,
    wachMoeglich: "wakeLock" in navigator,
    pausiert: istPausiert(fahrt),
    nachgerechnetM: zustand.nachgerechnetM ?? 0,
    nachtrag: nachtragStand,
  };
}

/** Zuletzt beendete Fahrt (für die Kategorie-Abfrage); wird beim Abholen gelöscht. */
export function beendeteAbholen() {
  const f = beendet;
  beendet = null;
  return f;
}

async function zustandSpeichern() {
  if (!fahrt) return;
  await db.wertSetzen("aufzeichnung", {
    uuid: fahrt.uuid,
    rechner: rechner.stand,
    stillstand: stillstand.stand,
    ...zustand,
    gespeichert: Date.now(),
  });
}

async function aendern(fn) {
  const neu = await db.unfertigAendern(fahrt.uuid, fn, ["laufend"]);
  if (neu) fahrt = neu;
  return neu;
}

/** Adresse für eine Position: gespeicherter Ort im Umkreis, sonst der Kartendienst, sonst Koordinaten. */
export async function adresseBestimmen(lat, lon, timeoutMs = 8_000) {
  const ort = erkennen(await db.wert("orte", []), lat, lon);
  if (ort) return alsAdresse(ort);
  return (await adresseErmitteln(lat, lon, timeoutMs)) ?? koordinatenText(lat, lon);
}

// ------------------------------------------------------------------ Bildschirm wach halten

async function wachHalten() {
  if (!("wakeLock" in navigator) || sperre || document.visibilityState !== "visible") return;
  try {
    const s = await navigator.wakeLock.request("screen");
    sperre = s;
    s.addEventListener("release", () => {
      if (sperre === s) sperre = null;
      melden();
    });
  } catch {
    sperre = null;
  }
  melden();
}

function wachFreigeben() {
  const s = sperre;
  sperre = null;
  s?.release().catch(() => {});
}

// ------------------------------------------------------------------ GPS

function beobachten() {
  if (beobachter !== null || !fahrt || istPausiert(fahrt) || !("geolocation" in navigator)) return;
  beobachter = navigator.geolocation.watchPosition(
    (pos) => nacheinander(() => verarbeiten(pos)),
    (fehler) => {
      gpsMeldung =
        fehler.code === 1
          ? "Standort nicht erlaubt – bitte in den iPhone-Einstellungen erlauben."
          : "Warte auf GPS-Empfang …";
      melden();
    },
    { enableHighAccuracy: true, maximumAge: 0, timeout: 60_000 },
  );
}

function beobachtenStoppen() {
  if (beobachter !== null) navigator.geolocation.clearWatch(beobachter);
  beobachter = null;
}

async function verarbeiten(pos) {
  if (!fahrt || istPausiert(fahrt)) return;
  const p = { lat: pos.coords.latitude, lon: pos.coords.longitude, zeit: pos.timestamp || Date.now(), genauigkeit: pos.coords.accuracy ?? 999 };
  const vorher = zustand.letzteLage;
  zustand.letzteLage = p;
  letzterFix = Date.now();
  gpsMeldung = "";
  if (typeof pos.coords.speed === "number" && pos.coords.speed >= 0) kmh = Math.round(pos.coords.speed * 3.6);
  else if (vorher && p.zeit > vorher.zeit) kmh = Math.round((distanzMeter(vorher.lat, vorher.lon, p.lat, p.lon) / ((p.zeit - vorher.zeit) / 1000)) * 3.6);
  if (p.genauigkeit <= 50) stillstand.punkt(p);

  const aenderung = {};
  if (fahrt.startLat === null || fahrt.startLat === undefined) Object.assign(aenderung, { startLat: p.lat, startLon: p.lon });
  const e = rechner.hinzufuegen(p);
  if (e.uebernommen) {
    aenderung.distanzMeter = rechner.meter;
    if (e.vorher && e.schrittM > LUECKE_M) lueckeNachrechnen(e.vorher, p, e.schrittM);
  }
  if (Object.keys(aenderung).length) await aendern(() => aenderung);
  await zustandSpeichern();
  melden();
}

/**
 * Ein großer Sprung zwischen zwei Punkten heißt: Die Aufzeichnung ruhte (Bildschirm aus, Funkloch).
 * Statt der Luftlinie zählen die Straßenkilometer laut Route, die Fahrzeit hilft beim Schätzen der Ankunft.
 */
function lueckeNachrechnen(von, nach, luftlinie) {
  const uuid = fahrt.uuid;
  zustand.luecke = { von: von.zeit, bis: nach.zeit, sekunden: null };
  routeErmitteln(von, nach).then((r) => {
    if (!r) return;
    nacheinander(async () => {
      if (!fahrt || fahrt.uuid !== uuid) return;
      if (zustand.luecke?.bis === nach.zeit) zustand.luecke = { ...zustand.luecke, sekunden: r.sekunden };
      const dauer = (nach.zeit - von.zeit) / 1000;
      // Nur plausible Routen: länger als die Luftlinie und nicht schneller als 250 km/h
      if (r.meter > luftlinie && (dauer <= 0 || r.meter / dauer <= 70)) {
        rechner.meter += r.meter - luftlinie;
        zustand.nachgerechnetM = (zustand.nachgerechnetM ?? 0) + (r.meter - luftlinie);
        await aendern(() => ({ distanzMeter: rechner.meter }));
      }
      await zustandSpeichern();
      melden();
    });
  });
}

function tickerStarten() {
  if (ticker === null) ticker = setInterval(() => nacheinander(pruefen), TICK_MS);
}

function tickerStoppen() {
  if (ticker !== null) clearInterval(ticker);
  ticker = null;
}

async function pruefen() {
  if (!fahrt) return;
  if (!istPausiert(fahrt)) {
    const minuten = (await db.wert("einstellungen", {})).autoStoppMinuten ?? 5;
    const jetzt = Date.now();
    // Nach dem Wiedereinschalten erst eine frische Position abwarten – sonst endet die Fahrt, obwohl sie weitergeht
    const frisch = letzterFix >= sichtbarSeit || jetzt - sichtbarSeit > SCHONFRIST_MS;
    if (minuten > 0 && frisch && stillstand.stillstandMillis(jetzt) >= minuten * 60_000) {
      await beendenIntern(true);
      return;
    }
  }
  melden();
}

// ------------------------------------------------------------------ Start, Pause, Ende

/** @param ort gespeicherter Ort als Start, sonst GPS */
export function starten(ort = null) {
  return nacheinander(async () => {
    if (fahrt) return;
    const jetzt = Date.now();
    const neu = {
      uuid: crypto.randomUUID(),
      startZeit: jetzt,
      endeZeit: null,
      startAdresse: ort ? alsAdresse(ort) : "",
      endeAdresse: "",
      zwischenziele: "",
      startLat: null,
      startLon: null,
      endeLat: null,
      endeLon: null,
      distanzMeter: 0,
      kategorieId: null,
      notiz: "",
      status: "laufend",
    };
    await db.fahrtSpeichern(neu);
    fahrt = neu;
    rechner = new StreckenRechner();
    stillstand = new StillstandErkennung(jetzt);
    zustand = leererZustand();
    sichtbarSeit = jetzt;
    kmh = null;
    nachtragStand = null;
    await zustandSpeichern();
    beobachten();
    wachHalten();
    tickerStarten();
    melden();
    startpositionErmitteln(neu.uuid);
  });
}

function startpositionErmitteln(uuid) {
  if (!("geolocation" in navigator)) return;
  navigator.geolocation.getCurrentPosition(
    async (pos) => {
      const lat = fahrt?.startLat ?? pos.coords.latitude;
      const lon = fahrt?.startLon ?? pos.coords.longitude;
      if (!fahrt || fahrt.uuid !== uuid) return;
      if (fahrt.startLat === null) await nacheinander(() => fahrt?.uuid === uuid && aendern(() => ({ startLat: lat, startLon: lon })));
      if (fahrt?.startAdresse) return;
      const adresse = await adresseBestimmen(lat, lon);
      // Nicht überschreiben, falls die Startadresse inzwischen von Hand gesetzt wurde
      await nacheinander(async () => {
        if (fahrt?.uuid === uuid && !fahrt.startAdresse) await aendern((f) => (f.startAdresse ? {} : { startAdresse: adresse }));
        melden();
      });
    },
    () => {},
    { enableHighAccuracy: true, maximumAge: 30_000, timeout: 20_000 },
  );
}

/**
 * Pause unterwegs: Die aktuelle Position wird Zwischenziel, die Aufzeichnung ruht und die Fahrt endet
 * nicht automatisch. Mit weiterfahren() geht es als dieselbe Fahrt weiter (Hin- und Rückfahrt).
 */
export function pausieren() {
  return nacheinander(async () => {
    if (!fahrt || istPausiert(fahrt)) return;
    beobachtenStoppen();
    wachFreigeben();
    const jetzt = Date.now();
    const lb = stillstand.letzteBewegung;
    const geschaetzt = ankunftSchaetzen({ letzteBewegung: lb, luecke: zustand.luecke, verborgenAb: zustand.verborgenAb }, jetzt, false);
    // Ankunft = Zeitpunkt des Anhaltens, wenn das Auto schon kurz steht
    const an = geschaetzt !== jetzt ? geschaetzt : jetzt - lb >= 0 && jetzt - lb <= 10 * 60_000 ? lb : jetzt;
    const lage = zustand.letzteLage ?? rechner.letzter;
    const z = { adresse: "", an };
    if (lage) Object.assign(z, { lat: lage.lat, lon: lage.lon });
    await aendern((f) => ({ zwischenziele: zwischenzieleSchreiben([...zwischenzieleLesen(f.zwischenziele), z]) }));
    kmh = null;
    await zustandSpeichern();
    melden();
    if (lage) zwischenzielAdresseErmitteln(fahrt.uuid, an, lage);
  });
}

async function zwischenzielAdresseErmitteln(uuid, an, lage) {
  const adresse = await adresseBestimmen(lage.lat, lage.lon);
  await nacheinander(async () => {
    if (fahrt?.uuid !== uuid) return;
    await aendern((f) => {
      const liste = zwischenzieleLesen(f.zwischenziele);
      const i = liste.findLastIndex((z) => z.an === an);
      // Nicht überschreiben, falls inzwischen von Hand eingetragen
      if (i < 0 || liste[i].adresse) return {};
      liste[i] = { ...liste[i], adresse };
      return { zwischenziele: zwischenzieleSchreiben(liste) };
    });
    melden();
  });
}

export function weiterfahren() {
  return nacheinander(async () => {
    if (!fahrt || !istPausiert(fahrt)) return;
    const jetzt = Date.now();
    await aendern((f) => {
      const liste = zwischenzieleLesen(f.zwischenziele);
      liste[liste.length - 1] = { ...liste.at(-1), ab: jetzt };
      return { zwischenziele: zwischenzieleSchreiben(liste) };
    });
    // Stillstand zählt ab der Weiterfahrt neu, sonst würde die Fahrt sofort automatisch enden
    stillstand = new StillstandErkennung(jetzt);
    zustand = { ...zustand, luecke: null, verborgenAb: null };
    sichtbarSeit = jetzt;
    await zustandSpeichern();
    beobachten();
    wachHalten();
    melden();
  });
}

/** Beendet die Fahrt per Knopf. @returns die beendete Fahrt (Status „offen“) oder null */
export function beenden() {
  return nacheinander(() => beendenIntern(false));
}

async function beendenIntern(automatisch) {
  if (!fahrt) return null;
  beobachtenStoppen();
  wachFreigeben();
  tickerStoppen();
  const f = (await db.fahrt(fahrt.uuid)) ?? fahrt;
  const meter = rechner?.meter ?? f.distanzMeter;
  const zwischen = zwischenzieleLesen(f.zwischenziele);
  let ergebnis = null;
  if (automatisch && meter < MIN_FAHRT_M && !zwischen.length) {
    await db.fahrtLoeschen(f.uuid);
  } else {
    // Beenden während einer Pause: Das Zwischenziel ist in Wahrheit das Ziel
    const pausenZiel = istPausiert(f) ? zwischen.at(-1) : null;
    const restliche = pausenZiel ? zwischen.slice(0, -1) : zwischen;
    const jetzt = Date.now();
    const ende =
      pausenZiel?.an ??
      ankunftSchaetzen({ letzteBewegung: stillstand.letzteBewegung, luecke: zustand.luecke, verborgenAb: zustand.verborgenAb }, jetzt, automatisch);
    const lage = typeof pausenZiel?.lat === "number" ? pausenZiel : (zustand.letzteLage ?? rechner?.letzter);
    let adresse = pausenZiel?.adresse?.trim() ?? "";
    if (!adresse && lage) adresse = await adresseBestimmen(lage.lat, lage.lon, 6_000);
    ergebnis = {
      ...f,
      endeZeit: Math.max(ende, f.startZeit),
      endeLat: lage?.lat ?? null,
      endeLon: lage?.lon ?? null,
      endeAdresse: adresse,
      distanzMeter: meter,
      zwischenziele: zwischenzieleSchreiben(restliche),
      status: "offen",
    };
    await db.fahrtSpeichern(ergebnis);
  }
  fahrt = null;
  rechner = null;
  stillstand = null;
  zustand = leererZustand();
  kmh = null;
  gpsMeldung = "";
  nachtragStand = null;
  await db.wertSetzen("aufzeichnung", null);
  beendet = ergebnis;
  melden();
  return ergebnis;
}

// ------------------------------------------------------------------ Während der Fahrt ändern

/**
 * Nachtrag vom korrigierten Start bis zum Beginn der Aufzeichnung der Fahrt f: gespeicherter Ort oder
 * Adresssuche in der Umgebung, dann Straßenkilometer und Fahrzeit über den Server – wie
 * StartKorrektur.berechnen in der Android-App.
 * @returns {Promise<{nachtrag: {meter: number, ms: number}} | {fehler: string}>}
 */
export async function nachtragBerechnen(f, adresse) {
  const lat = f.startLat;
  const lon = f.startLon;
  if (typeof lat !== "number" || typeof lon !== "number") return { fehler: "Der Beginn der Aufzeichnung ist noch nicht bekannt (noch kein GPS-Signal)." };
  if (!(await verbindung())?.code) return { fehler: "Dafür muss die Web-App mit der Online-Sicherung verbunden sein (Einstellungen)." };
  const ohneNetz = () => navigator.onLine === false;
  const text = adresse.trim();
  const orte = await db.wert("orte", []);
  const ort = orte.find((o) => typeof o.lat === "number" && typeof o.lon === "number" && alsAdresse(o).toLowerCase() === text.toLowerCase());
  const ziel = ort ? { lat: ort.lat, lon: ort.lon } : await koordinatenSuchen(text, { lat, lon });
  if (!ziel) return { fehler: ohneNetz() ? "Keine Internetverbindung." : "Adresse nicht gefunden – bitte mit Postleitzahl und Ort eingeben." };
  const abstand = distanzMeter(ziel.lat, ziel.lon, lat, lon);
  const vorab = nachtragVorpruefen(abstand);
  if (vorab) return vorab;
  const r = await routeErmitteln(ziel, { lat, lon });
  if (!r) return { fehler: ohneNetz() ? "Keine Internetverbindung." : "Keine Straßenverbindung gefunden." };
  return { nachtrag: nachtragAusRoute(r, abstand) };
}

/**
 * Startadresse der laufenden Fahrt ändern, z. B. wenn der Start zu spät gedrückt wurde: Die fehlenden
 * Kilometer und die Abfahrt werden nachgerechnet, ein früherer Nachtrag wird ersetzt.
 */
export function startAdresseAendern(text) {
  const adresse = text.trim();
  return nacheinander(async () => {
    if (!fahrt) return;
    await aendern(() => ({ startAdresse: adresse }));
    const f = fahrt;
    nachtragStand = { text: "Kilometer ab dem neuen Start werden berechnet …", fehler: false };
    melden();
    // Außerhalb der Warteschlange rechnen – die Aufzeichnung läuft währenddessen weiter
    nachtragBerechnen(f, adresse).then((e) =>
      nacheinander(async () => {
        if (fahrt?.uuid !== f.uuid) {
          // Während der Berechnung beendet: der noch nicht zugeordneten Fahrt nachtragen
          if (e.nachtrag) await db.unfertigAendern(f.uuid, (a) => (a.startAdresse === adresse ? nachtragAnwenden(a, e.nachtrag) : {}), ["offen"]);
          return;
        }
        // Inzwischen erneut korrigiert: Dann zählt die neuere Korrektur
        if (fahrt.startAdresse !== adresse) return;
        if (e.fehler) {
          nachtragStand = { text: `Kilometer nicht ergänzt: ${e.fehler}`, fehler: true };
        } else {
          rechner.meter = Math.max(0, rechner.meter + e.nachtrag.meter - (fahrt.nachtragMeter ?? 0));
          await aendern((a) => ({ ...nachtragAnwenden(a, e.nachtrag), distanzMeter: rechner.meter }));
          await zustandSpeichern();
          nachtragStand = null;
        }
        melden();
      }),
    );
  });
}

export function zwischenzielAendern(index, text) {
  return nacheinander(async () => {
    if (!fahrt) return;
    await aendern((f) => {
      const liste = zwischenzieleLesen(f.zwischenziele);
      if (!liste[index]) return {};
      liste[index] = { ...liste[index], adresse: text.trim() };
      return { zwischenziele: zwischenzieleSchreiben(liste) };
    });
    melden();
  });
}

// ------------------------------------------------------------------ Start der App, Sichtbarkeit

/** Setzt eine laufende Fahrt nach dem Öffnen der App fort (iOS beendet Web-Apps im Hintergrund). */
export function wiederaufnehmen() {
  return nacheinander(async () => {
    const f = await db.laufendeFahrt();
    if (!f) {
      await db.wertSetzen("aufzeichnung", null);
      melden();
      return;
    }
    const z = await db.wert("aufzeichnung");
    const passt = z?.uuid === f.uuid;
    fahrt = f;
    rechner = new StreckenRechner(passt ? z.rechner : { meter: f.distanzMeter, letzter: null });
    stillstand = new StillstandErkennung(passt ? (z.stillstand?.letzteBewegung ?? Date.now()) : Date.now(), passt ? z.stillstand : null);
    zustand = passt
      ? { luecke: z.luecke ?? null, verborgenAb: z.verborgenAb ?? z.gespeichert ?? null, letzteLage: z.letzteLage ?? null, nachgerechnetM: z.nachgerechnetM ?? 0 }
      : leererZustand();
    sichtbarSeit = Date.now();
    tickerStarten();
    if (!istPausiert(f)) {
      beobachten();
      wachHalten();
    }
    melden();
  });
}

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") {
    if (fahrt) {
      zustand.verborgenAb = Date.now();
      nacheinander(zustandSpeichern);
    }
    return;
  }
  sichtbarSeit = Date.now();
  if (fahrt && !istPausiert(fahrt)) {
    // iOS liefert nach dem Wiedereinschalten nicht immer weiter Positionen – neu anmelden
    beobachtenStoppen();
    beobachten();
    wachHalten();
  }
  melden();
});

// Gespeicherte Orte und Zwischenziele – gleiche Regeln und gleiches JSON wie die Android-App
// (data/Orte.kt, data/Zwischenziele.kt), damit Server und Verwaltungsseite beides gleich lesen.
import { distanzMeter } from "./strecke.js";

/** Umkreis, in dem ein gespeicherter Ort automatisch erkannt wird. */
export const ERKENNUNG_M = 150;

/** Text, der als Start-, Zwischen- oder Zieladresse in die Fahrt übernommen wird. */
export function alsAdresse(o) {
  const name = (o.name ?? "").trim();
  const adresse = (o.adresse ?? "").trim();
  if (!name) return adresse;
  if (!adresse) return name;
  return `${name}, ${adresse}`;
}

const hatKoordinaten = (o) => typeof o.lat === "number" && typeof o.lon === "number";

/** Nächster gespeicherter Ort im Umkreis, sonst null. */
export function erkennen(orte, lat, lon, radiusM = ERKENNUNG_M) {
  let best = null;
  let bestD = Infinity;
  for (const o of orte ?? []) {
    if (!hatKoordinaten(o)) continue;
    const d = distanzMeter(lat, lon, o.lat, o.lon);
    if (d <= radiusM && d < bestD) {
      best = o;
      bestD = d;
    }
  }
  return best;
}

/** Zwischenziele aus dem gespeicherten JSON; kaputte Daten ergeben eine leere Liste. */
export function zwischenzieleLesen(json) {
  if (!json || !String(json).trim()) return [];
  try {
    const a = JSON.parse(json);
    if (!Array.isArray(a)) return [];
    return a.map((o) => {
      const z = { adresse: typeof o.adresse === "string" ? o.adresse : "" };
      for (const k of ["lat", "lon", "an", "ab"]) if (typeof o[k] === "number") z[k] = o[k];
      return z;
    });
  } catch {
    return [];
  }
}

/**
 * Leere Liste = "" (direkte Fahrt). Fehlende Werte werden weggelassen statt als null geschrieben –
 * die Android-App liest ein vorhandenes, aber leeres Feld sonst als Fehler.
 */
export function zwischenzieleSchreiben(liste) {
  if (!liste?.length) return "";
  return JSON.stringify(
    liste.map((z) => {
      const o = { adresse: z.adresse ?? "" };
      for (const k of ["lat", "lon", "an", "ab"]) if (typeof z[k] === "number" && Number.isFinite(z[k])) o[k] = z[k];
      return o;
    }),
  );
}

/** Anzeige einer Strecke: Start, Zwischenziele, Ziel. */
export function streckeText(von, zwischen, nach, trenner = " → ") {
  return [(von || "").trim() || "?", ...zwischen.map((z) => (z.adresse || "").trim() || "?"), (nach || "").trim() || "?"].join(trenner);
}

/** Pausiert = laufende Fahrt, deren letztes Zwischenziel noch keine Weiterfahrt hat. */
export function istPausiert(f) {
  if (f?.status !== "laufend") return false;
  const z = zwischenzieleLesen(f.zwischenziele).at(-1);
  return !!z && typeof z.an === "number" && typeof z.ab !== "number";
}

export const koordinatenText = (lat, lon) => `${lat.toFixed(5)}, ${lon.toFixed(5)}`;

/** true, wenn noch keine echte Adresse ermittelt wurde (leer oder nur Koordinaten). */
export const adresseFehlt = (a) => !a || !a.trim() || /^-?\d{1,3}\.\d+,\s*-?\d{1,3}\.\d+$/.test(a.trim());

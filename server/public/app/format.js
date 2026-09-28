// Formatierung wie in der Android-App: Zahlen mit Dezimalkomma und Tausenderpunkt (so liest Excel
// die CSV richtig), Datum wie auf der Verwaltungsseite.

const zahl1 = new Intl.NumberFormat("de-DE", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const datumF = new Intl.DateTimeFormat("de-AT", { day: "2-digit", month: "2-digit", year: "numeric" });
const datumKurzF = new Intl.DateTimeFormat("de-AT", { weekday: "short", day: "2-digit", month: "2-digit" });
const zeitF = new Intl.DateTimeFormat("de-AT", { hour: "2-digit", minute: "2-digit" });
const monatF = new Intl.DateTimeFormat("de-AT", { month: "long", year: "numeric" });

/** Kilometer mit einer Nachkommastelle, gerundet wie im Bericht. */
export const kmWert = (meter) => Math.round(meter / 100) / 10;
export const kmZahl = (km) => zahl1.format(km);
export const km = (meter) => `${kmZahl(kmWert(meter))} km`;

/** Kilometer für ein Eingabefeld: ohne Tausenderpunkt, mit Dezimalkomma. */
export const kmEingabe = (meter) => kmWert(meter).toFixed(1).replace(".", ",");

/** Akzeptiert „12,5“, „1.234,5“ und „12.5“; sonst null. */
export function kmParsen(text) {
  const t = String(text ?? "").trim().replace(/\s/g, "");
  if (!t) return null;
  const norm = t.includes(",") ? t.replace(/\./g, "").replace(",", ".") : t;
  if (!/^(\d+\.?\d*|\.\d+)$/.test(norm)) return null;
  const n = Number(norm);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

export function dauer(ms) {
  const min = Math.max(0, Math.floor(ms / 60_000));
  return min < 60 ? `${min} min` : `${Math.floor(min / 60)} h ${String(min % 60).padStart(2, "0")} min`;
}

export const datum = (ms) => datumF.format(new Date(ms));
export const datumKurz = (ms) => datumKurzF.format(new Date(ms));
export const uhrzeit = (ms) => zeitF.format(new Date(ms));
export const monat = (ms) => {
  const t = monatF.format(new Date(ms));
  return t.charAt(0).toUpperCase() + t.slice(1);
};

/** Kategoriefarbe (ARGB-Zahl wie in der App) als CSS-Farbe. */
export const farbe = (f) => (f === null || f === undefined ? "#9e9e9e" : "#" + (Number(f) & 0xffffff).toString(16).padStart(6, "0"));

/** Schwarze oder weiße Schrift, je nachdem was auf der Farbe besser lesbar ist. */
export function schriftAuf(f) {
  const n = Number(f) & 0xffffff;
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.4 ? "#000" : "#fff";
}

/** Datum für <input type="date"> (lokale Zeit). */
export const isoDatum = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** Uhrzeit für <input type="time"> (lokale Zeit). */
export const isoZeit = (ms) => {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
};

/** „2026-09-28“ → lokale Mitternacht. */
export function ausIsoDatum(s) {
  const [y, m, t] = String(s).split("-").map(Number);
  return new Date(y, m - 1, t);
}

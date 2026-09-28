// Berichte wie in der Android-App (bericht/Bericht.kt, BerichtExport.kt): je Kategorie gruppiert,
// Kilometerstand über alle Fahrten des Fahrzeugs fortgeschrieben, CSV für Excel.
import { datum, kmWert, kmZahl, monat, uhrzeit } from "./format.js";
import { zwischenzieleLesen } from "./orte.js";

export const OHNE_KATEGORIE = "Ohne Kategorie";
const OHNE_FARBE = 0xff9e9e9e;
const runde = (km) => Math.round(km * 10) / 10;

/**
 * Halb offenes Intervall [von, bis) eines Zeitraums in lokaler Zeit.
 * @param {{art: "monat", jahr: number, monat: number} | {art: "jahr", jahr: number} | {art: "zeitraum", von: Date, bis: Date}} z
 *   monat: 0 = Jänner
 */
export function zeitraumGrenzen(z) {
  if (z.art === "monat") {
    const von = new Date(z.jahr, z.monat, 1);
    const bis = new Date(z.jahr, z.monat + 1, 1);
    return { von: von.getTime(), bis: bis.getTime(), titel: monat(von.getTime()), erster: von, letzter: new Date(z.jahr, z.monat + 1, 0) };
  }
  if (z.art === "jahr") {
    return {
      von: new Date(z.jahr, 0, 1).getTime(),
      bis: new Date(z.jahr + 1, 0, 1).getTime(),
      titel: `Jahr ${z.jahr}`,
      erster: new Date(z.jahr, 0, 1),
      letzter: new Date(z.jahr, 11, 31),
    };
  }
  let [a, b] = [z.von, z.bis];
  if (b < a) [a, b] = [b, a];
  const von = new Date(a.getFullYear(), a.getMonth(), a.getDate());
  const letzter = new Date(b.getFullYear(), b.getMonth(), b.getDate());
  return {
    von: von.getTime(),
    bis: new Date(b.getFullYear(), b.getMonth(), b.getDate() + 1).getTime(),
    titel: `${datum(von.getTime())} – ${datum(letzter.getTime())}`,
    erster: von,
    letzter,
  };
}

/**
 * Kilometerstand zu Beginn eines Zeitraums: Stand laut Tacho am Stichtag plus alle abgeschlossenen
 * Fahrten zwischen Stichtag und Zeitraumbeginn. null, wenn kein Kilometerstand eingetragen ist.
 */
export function kmVorherBerechnen(fertigeFahrten, { kmStandStart = 0, kmStandAb = 0 } = {}, von) {
  if (!(kmStandStart > 0)) return null;
  if (!(von > kmStandAb)) return kmStandStart;
  const gefahren = fertigeFahrten
    .filter((f) => f.status === "fertig" && f.startZeit >= kmStandAb && f.startZeit < von)
    .reduce((s, f) => s + kmWert(f.distanzMeter), 0);
  return kmStandStart + gefahren;
}

/**
 * @param fahrten abgeschlossene Fahrten im Zeitraum (beliebige Reihenfolge)
 * @param kategorien in Anzeigereihenfolge
 * @param filter Set von Kategorie-IDs (0 = ohne Kategorie) oder null = alle
 */
export function berichtErstellen(fahrten, kategorien, { kmVorher = null, kmStandAb = 0, filter = null } = {}) {
  const kats = new Map(kategorien.map((k) => [k.id, k]));
  const reihenfolge = kategorien.map((k) => k.id);
  const sortiert = [...fahrten].sort((a, b) => a.startZeit - b.startZeit);

  // Kilometerstand läuft über ALLE Fahrten des Fahrzeugs, unabhängig vom Kategorie-Filter
  let stand = kmVorher;
  const zeilen = sortiert.map((f) => {
    const km = kmWert(f.distanzMeter);
    const mitStand = stand !== null && f.startZeit >= kmStandAb;
    const beginn = mitStand ? stand : null;
    const ende = mitStand ? runde(stand + km) : null;
    if (mitStand) stand = ende;
    return { fahrt: f, km, kmStandBeginn: beginn, kmStandEnde: ende };
  });

  const gruppen = new Map();
  for (const z of zeilen) {
    const id = kats.has(z.fahrt.kategorieId) ? z.fahrt.kategorieId : null;
    if (!gruppen.has(id)) gruppen.set(id, []);
    gruppen.get(id).push(z);
  }
  const alle = [...gruppen.entries()]
    .map(([id, liste]) => {
      const k = id === null ? null : kats.get(id);
      return {
        kategorieId: id,
        name: k?.name ?? OHNE_KATEGORIE,
        farbe: k?.farbe ?? OHNE_FARBE,
        zeilen: liste,
        summeKm: runde(liste.reduce((s, z) => s + z.km, 0)),
        anzahl: liste.length,
        dauerMs: liste.reduce((s, z) => s + Math.max(0, (z.fahrt.endeZeit ?? z.fahrt.startZeit) - z.fahrt.startZeit), 0),
      };
    })
    .sort((a, b) => {
      if ((a.kategorieId === null) !== (b.kategorieId === null)) return a.kategorieId === null ? 1 : -1;
      const ia = reihenfolge.indexOf(a.kategorieId);
      const ib = reihenfolge.indexOf(b.kategorieId);
      return (ia < 0 ? Infinity : ia) - (ib < 0 ? Infinity : ib) || a.name.localeCompare(b.name, "de");
    });

  const bloecke = filter ? alle.filter((b) => filter.has(b.kategorieId ?? 0)) : alle;
  const gesamtKm = runde(bloecke.reduce((s, b) => s + b.summeKm, 0));
  return {
    alle,
    bloecke: bloecke.map((b) => ({ ...b, anteil: gesamtKm > 0 ? (b.summeKm / gesamtKm) * 100 : 0 })),
    gesamtKm,
    gesamtAnzahl: bloecke.reduce((s, b) => s + b.anzahl, 0),
    mitKmStand: kmVorher !== null,
  };
}

/** CSV für Excel (deutsch: Semikolon, Dezimalkomma, UTF-8 mit BOM) – Aufbau wie in der Android-App. */
export function csvErstellen(bericht, zr, e = {}) {
  const feld = (s) => {
    const t = String(s ?? "");
    return /[;"\r\n]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t;
  };
  const zeilen = [["Fahrtenbuch"], ["Zeitraum", `${zr.titel} (${datum(zr.erster.getTime())} – ${datum(zr.letzter.getTime())})`]];
  if (e.fahrzeug?.trim()) zeilen.push(["Fahrzeug", e.fahrzeug]);
  if (e.kennzeichen?.trim()) zeilen.push(["Kennzeichen", e.kennzeichen]);
  if (e.fahrer?.trim()) zeilen.push(["Fahrer", e.fahrer]);
  zeilen.push([]);
  const kopf = ["Kategorie", "Datum", "Abfahrt", "Ankunft", "Von", "Über", "Nach", "km"];
  if (bericht.mitKmStand) kopf.push("Km-Stand Beginn", "Km-Stand Ende");
  kopf.push("Zweck / Notiz");
  zeilen.push(kopf);
  for (const b of bericht.bloecke) {
    for (const z of b.zeilen) {
      const f = z.fahrt;
      const r = [
        b.name,
        datum(f.startZeit),
        uhrzeit(f.startZeit),
        f.endeZeit ? uhrzeit(f.endeZeit) : "",
        f.startAdresse,
        zwischenzieleLesen(f.zwischenziele).map((x) => x.adresse).join(" / "),
        f.endeAdresse,
        kmZahl(z.km),
      ];
      if (bericht.mitKmStand) r.push(z.kmStandBeginn === null ? "" : kmZahl(z.kmStandBeginn), z.kmStandEnde === null ? "" : kmZahl(z.kmStandEnde));
      r.push(f.notiz);
      zeilen.push(r);
    }
  }
  zeilen.push([], ["Zusammenfassung"], ["Kategorie", "Fahrten", "km", "Anteil %"]);
  for (const b of bericht.bloecke) zeilen.push([b.name, b.anzahl, kmZahl(b.summeKm), kmZahl(b.anteil)]);
  zeilen.push(["Gesamt", bericht.gesamtAnzahl, kmZahl(bericht.gesamtKm), "100,0"]);
  return "﻿" + zeilen.map((z) => z.map(feld).join(";")).join("\r\n") + "\r\n";
}

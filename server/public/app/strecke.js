// Streckenmessung wie in der Android-App (tracking/Strecke.kt) – ergänzt um Lücken, in denen iOS die
// Web-App angehalten hat (Bildschirm aus, andere App im Vordergrund).

const ERDRADIUS_M = 6_371_008.8;
const rad = (g) => (g * Math.PI) / 180;

/** Großkreisentfernung (Haversine) in Metern. */
export function distanzMeter(lat1, lon1, lat2, lon2) {
  const p1 = rad(lat1);
  const p2 = rad(lat2);
  const a = Math.sin((p2 - p1) / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(rad(lon2 - lon1) / 2) ** 2;
  return 2 * ERDRADIUS_M * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))));
}

const abstand = (a, b) => distanzMeter(a.lat, a.lon, b.lat, b.lon);

/** Ab diesem Sprung zwischen zwei Punkten fehlt ein Stück Aufzeichnung – die Straßenkilometer werden nachgerechnet. */
export const LUECKE_M = 500;

/**
 * Summiert die gefahrene Strecke aus GPS-Punkten {lat, lon, zeit, genauigkeit}.
 * - Punkte mit schlechter Genauigkeit werden verworfen.
 * - Sprünge mit unrealistischer Geschwindigkeit (GPS-Ausreißer) werden verworfen.
 * - Zittern im Stand zählt nicht; die Strecke zählt ab dem nächsten weiter entfernten Punkt.
 * Der Stand ({meter, letzter}) lässt sich speichern und wiederherstellen.
 */
export class StreckenRechner {
  constructor(stand = {}, { maxGenauigkeitM = 35, maxGeschwindigkeitMs = 70, minSchrittM = 10 } = {}) {
    this.meter = stand.meter ?? 0;
    this.letzter = stand.letzter ?? null;
    this.grenzen = { maxGenauigkeitM, maxGeschwindigkeitMs, minSchrittM };
  }

  /** @returns {{uebernommen: boolean, schrittM: number, vorher: object|null}} */
  hinzufuegen(p) {
    const g = this.grenzen;
    const nein = { uebernommen: false, schrittM: 0, vorher: this.letzter };
    if (p.genauigkeit > g.maxGenauigkeitM) return nein;
    const vorher = this.letzter;
    if (!vorher) {
      this.letzter = p;
      return { uebernommen: true, schrittM: 0, vorher: null };
    }
    const d = abstand(vorher, p);
    if (d < Math.max(g.minSchrittM, p.genauigkeit)) return nein;
    const sekunden = (p.zeit - vorher.zeit) / 1000;
    if (sekunden <= 0 || d / sekunden > g.maxGeschwindigkeitMs) return nein;
    this.meter += d;
    this.letzter = p;
    return { uebernommen: true, schrittM: d, vorher };
  }

  get stand() {
    return { meter: this.meter, letzter: this.letzter };
  }
}

/**
 * Erkennt, seit wann sich das Fahrzeug nicht mehr nennenswert bewegt hat: Eine Bewegung liegt vor,
 * sobald ein Punkt mehr als radiusM vom Ankerpunkt entfernt ist.
 */
export class StillstandErkennung {
  constructor(startZeit, stand = null, radiusM = 75) {
    this.anker = stand?.anker ?? null;
    this.letzteBewegung = stand?.letzteBewegung ?? startZeit;
    this.radiusM = radiusM;
  }

  punkt(p) {
    if (!this.anker || abstand(this.anker, p) > this.radiusM) {
      this.anker = p;
      this.letzteBewegung = p.zeit;
    }
  }

  stillstandMillis(jetzt) {
    return Math.max(0, jetzt - this.letzteBewegung);
  }

  get stand() {
    return { anker: this.anker, letzteBewegung: this.letzteBewegung };
  }
}

/**
 * Ankunftszeit einer Fahrt, die auf dem iPhone beendet wird.
 * @param {{letzteBewegung: number, luecke?: {von: number, bis: number, sekunden: number|null}|null, verborgenAb?: number|null}} s
 *   luecke: letzte Aufzeichnungslücke (von = letzter Punkt davor, bis = erster Punkt danach, sekunden = Fahrzeit laut Route);
 *   verborgenAb: wann die Web-App zuletzt in den Hintergrund ging.
 * @param {number} jetzt Zeitpunkt des Beendens
 * @param {boolean} automatisch Ende nach Stillstand (sonst per Knopf)
 */
export function ankunftSchaetzen({ letzteBewegung, luecke = null, verborgenAb = null }, jetzt, automatisch) {
  if (luecke && letzteBewegung <= luecke.bis) {
    // Seit dem Wiedereinschalten keine Bewegung: angekommen ist das Auto irgendwann während der Lücke
    return typeof luecke.sekunden === "number" ? Math.min(luecke.bis, luecke.von + luecke.sekunden * 1000) : luecke.bis;
  }
  if (automatisch) return letzteBewegung;
  // Bildschirm ging erst nach dem Anhalten aus: Ankunft = Zeitpunkt des Anhaltens, nicht des Wiedereinschaltens
  if (verborgenAb !== null && verborgenAb >= letzteBewegung) return letzteBewegung;
  return jetzt;
}

import { fahrerPruefen } from "./anmeldung";
import { Env, HttpFehler, json, leseJson, text } from "./hilfen";

/**
 * Adressen und Straßenkilometer für die iPhone-Web-App (die Android-App nutzt für Adressen den Geocoder des
 * Handys, für Straßenkilometer nach einem korrigierten Start ebenfalls diese Route).
 * Die Anfragen laufen über den Server, damit die Nutzungsregeln der OpenStreetMap-Dienste eingehalten
 * werden: eigene Kennung, Zwischenspeicher, höchstens vereinzelte Anfragen (nur beim Start, Halt und
 * Ende einer Fahrt). Nur verbundene Fahrer dürfen abfragen – kein offener Vermittler für Dritte.
 */

type Holen = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

const NOMINATIM = "https://nominatim.openstreetmap.org";
// Routing der FOSSGIS (wie auf openstreetmap.org): Lücken bei ausgeschaltetem Bildschirm, korrigierter Start
const OSRM = "https://routing.openstreetmap.de/routed-car/route/v1/driving";
const SPEICHER_S = 30 * 86_400;
/** Halbe Kantenlänge des Suchgebiets um einen Punkt in Breitengraden (0,9° ≈ 100 km). */
const UMKREIS_GRAD = 0.9;

/** Anfragekopf, der die Anwendung gegenüber den OSM-Diensten ausweist. */
function kennung(env: Env): HeadersInit {
  const adresse = env.OEFFENTLICHE_ADRESSE?.replace(/\/+$/, "") || "https://fahrtenbuch.smarte.events";
  return { "user-agent": `Fahrtenbuch/0.6 (+${adresse})`, referer: `${adresse}/`, accept: "application/json" };
}

function koordinate(wert: unknown, grenze: number): number {
  if (typeof wert !== "number" || !Number.isFinite(wert) || Math.abs(wert) > grenze) throw new HttpFehler(400, "Koordinate ungültig");
  // Auf rund 10 m gerundet: genug für eine Adresse, und benachbarte Anfragen treffen den Zwischenspeicher
  return Math.round(wert * 10_000) / 10_000;
}

/** Holt eine Antwort aus dem Zwischenspeicher (Cloudflare Cache) oder berechnet und speichert sie. */
async function gespeichert<T>(schluessel: string, berechnen: () => Promise<T | null>): Promise<T | null> {
  const cache = typeof caches !== "undefined" ? (caches as unknown as { default?: Cache }).default : undefined;
  const key = new Request(`https://zwischenspeicher.fahrtenbuch.invalid/${schluessel}`);
  const alt = cache ? await cache.match(key).catch(() => undefined) : undefined;
  if (alt) return (await alt.json()) as T;
  const neu = await berechnen();
  // Nur Treffer speichern – ein vorübergehender Fehler soll nicht 30 Tage lang hängen bleiben
  if (cache && neu !== null) {
    await cache
      .put(key, new Response(JSON.stringify(neu), { headers: { "cache-control": `max-age=${SPEICHER_S}`, "content-type": "application/json" } }))
      .catch(() => {});
  }
  return neu;
}

async function dienst(holen: Holen, url: string, env: Env): Promise<unknown> {
  let r: Response;
  try {
    r = await holen(url, { headers: kennung(env), signal: AbortSignal.timeout(8_000) });
  } catch {
    throw new HttpFehler(502, "Kartendienst nicht erreichbar");
  }
  if (!r.ok) throw new HttpFehler(502, `Kartendienst meldet Fehler ${r.status}`);
  return r.json();
}

interface NominatimAdresse {
  road?: string;
  pedestrian?: string;
  footway?: string;
  path?: string;
  house_number?: string;
  postcode?: string;
  city?: string;
  town?: string;
  village?: string;
  municipality?: string;
  hamlet?: string;
  suburb?: string;
  county?: string;
}

/** Wie in der Android-App: „Straße Hausnummer, PLZ Ort“. */
export function adresseFormatieren(a: NominatimAdresse | undefined, anzeige = ""): string {
  if (a) {
    const strasse = [a.road ?? a.pedestrian ?? a.footway ?? a.path, a.house_number].filter(Boolean).join(" ").trim();
    const ort = [a.postcode, a.city ?? a.town ?? a.village ?? a.municipality ?? a.hamlet ?? a.suburb ?? a.county]
      .filter(Boolean)
      .join(" ")
      .trim();
    const teile = [strasse, ort].filter(Boolean);
    if (teile.length) return teile.join(", ");
  }
  // Ohne Straße und Ort: die ersten Teile der vollständigen Bezeichnung
  return anzeige.split(",").slice(0, 3).map((s) => s.trim()).filter(Boolean).join(", ");
}

/** POST /api/v1/adresse {lat, lon} → {adresse} (leer, wenn der Dienst nichts kennt). */
export async function adresse(req: Request, env: Env, holen: Holen = fetch): Promise<Response> {
  await fahrerPruefen(req, env);
  const b = await leseJson<{ lat?: unknown; lon?: unknown }>(req, 1_000);
  const lat = koordinate(b.lat, 90);
  const lon = koordinate(b.lon, 180);
  const ergebnis = await gespeichert(`adresse/${lat},${lon}`, async () => {
    const d = (await dienst(
      holen,
      `${NOMINATIM}/reverse?format=jsonv2&lat=${lat}&lon=${lon}&zoom=18&addressdetails=1&accept-language=de`,
      env,
    )) as { address?: NominatimAdresse; display_name?: string; error?: string };
    if (d.error) return null;
    const text = adresseFormatieren(d.address, d.display_name ?? "");
    return text ? { adresse: text } : null;
  });
  return json(ergebnis ?? { adresse: "" });
}

/** Suchgebiet für Nominatim: rund 100 km um den Punkt, nur Treffer darin. */
function suchgebiet(p: { lat: number; lon: number }): string {
  const dLon = UMKREIS_GRAD / Math.max(0.1, Math.cos((p.lat * Math.PI) / 180));
  const breite = (x: number) => Math.min(90, Math.max(-90, x)).toFixed(3);
  const laenge = (x: number) => Math.min(180, Math.max(-180, x)).toFixed(3);
  return `&viewbox=${laenge(p.lon - dLon)},${breite(p.lat + UMKREIS_GRAD)},${laenge(p.lon + dLon)},${breite(p.lat - UMKREIS_GRAD)}&bounded=1`;
}

/**
 * POST /api/v1/koordinaten {adresse, nahe?: {lat, lon}} → {lat, lon} oder {lat: null} – für gespeicherte
 * Orte und den korrigierten Start. Mit „nahe“ nur in der Umgebung: So findet „Hauptplatz 1“ den im eigenen Ort.
 */
export async function koordinaten(req: Request, env: Env, holen: Holen = fetch): Promise<Response> {
  await fahrerPruefen(req, env);
  const b = await leseJson<{ adresse?: unknown; nahe?: { lat?: unknown; lon?: unknown } }>(req, 2_000);
  const suche = text(b.adresse, 300).trim().replace(/\s+/g, " ");
  if (suche.length < 3) throw new HttpFehler(400, "Adresse fehlt");
  // Auf 0,1° gerundet (≈ 10 km): genügt für das Suchgebiet, und der Zwischenspeicher greift öfter
  const nahe = b.nahe
    ? { lat: Math.round(koordinate(b.nahe.lat, 90) * 10) / 10, lon: Math.round(koordinate(b.nahe.lon, 180) * 10) / 10 }
    : null;
  const bereich = nahe ? `${nahe.lat},${nahe.lon}/` : "";
  const ergebnis = await gespeichert(`koordinaten/${bereich}${encodeURIComponent(suche.toLowerCase())}`, async () => {
    const d = (await dienst(
      holen,
      `${NOMINATIM}/search?format=jsonv2&limit=1&accept-language=de${nahe ? suchgebiet(nahe) : ""}&q=${encodeURIComponent(suche)}`,
      env,
    )) as { lat?: string; lon?: string }[];
    const t = Array.isArray(d) ? d[0] : undefined;
    const lat = Number(t?.lat);
    const lon = Number(t?.lon);
    return t && Number.isFinite(lat) && Number.isFinite(lon) ? { lat, lon } : null;
  });
  return json(ergebnis ?? { lat: null, lon: null });
}

/**
 * POST /api/v1/route {von: {lat, lon}, nach: {lat, lon}} → {meter, sekunden}
 * Straßenkilometer zwischen zwei Punkten – für die Strecke, während der Bildschirm aus war, und vom
 * korrigierten Start bis zum Beginn der Aufzeichnung (Start zu spät gedrückt).
 */
export async function route(req: Request, env: Env, holen: Holen = fetch): Promise<Response> {
  await fahrerPruefen(req, env);
  const b = await leseJson<{ von?: { lat?: unknown; lon?: unknown }; nach?: { lat?: unknown; lon?: unknown } }>(req, 1_000);
  const von = { lat: koordinate(b.von?.lat, 90), lon: koordinate(b.von?.lon, 180) };
  const nach = { lat: koordinate(b.nach?.lat, 90), lon: koordinate(b.nach?.lon, 180) };
  const ergebnis = await gespeichert(`route/${von.lat},${von.lon};${nach.lat},${nach.lon}`, async () => {
    const d = (await dienst(
      holen,
      `${OSRM}/${von.lon},${von.lat};${nach.lon},${nach.lat}?overview=false&alternatives=false&steps=false`,
      env,
    )) as { code?: string; routes?: { distance?: number; duration?: number }[] };
    const r = d.code === "Ok" ? d.routes?.[0] : undefined;
    return r && typeof r.distance === "number" && typeof r.duration === "number"
      ? { meter: Math.round(r.distance), sekunden: Math.round(r.duration) }
      : null;
  });
  if (!ergebnis) throw new HttpFehler(404, "Keine Route gefunden");
  return json(ergebnis);
}

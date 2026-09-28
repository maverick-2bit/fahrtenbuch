import { fahrerPruefen } from "./anmeldung";
import { Env, HttpFehler, json, leseJson, text } from "./hilfen";

/**
 * Adressen und Straßenkilometer für die iPhone-Web-App (die Android-App nutzt den Geocoder des Handys).
 * Die Anfragen laufen über den Server, damit die Nutzungsregeln der OpenStreetMap-Dienste eingehalten
 * werden: eigene Kennung, Zwischenspeicher, höchstens vereinzelte Anfragen (nur beim Start, Halt und
 * Ende einer Fahrt). Nur verbundene Fahrer dürfen abfragen – kein offener Vermittler für Dritte.
 */

type Holen = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

const NOMINATIM = "https://nominatim.openstreetmap.org";
// Routing der FOSSGIS (wie auf openstreetmap.org), nur für Lücken bei ausgeschaltetem Bildschirm
const OSRM = "https://routing.openstreetmap.de/routed-car/route/v1/driving";
const SPEICHER_S = 30 * 86_400;

/** Anfragekopf, der die Anwendung gegenüber den OSM-Diensten ausweist. */
function kennung(env: Env): HeadersInit {
  const adresse = env.OEFFENTLICHE_ADRESSE?.replace(/\/+$/, "") || "https://fahrtenbuch.smarte.events";
  return { "user-agent": `Fahrtenbuch/0.5 (+${adresse})`, referer: `${adresse}/`, accept: "application/json" };
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

/** POST /api/v1/koordinaten {adresse} → {lat, lon} oder {lat: null} – für gespeicherte Orte. */
export async function koordinaten(req: Request, env: Env, holen: Holen = fetch): Promise<Response> {
  await fahrerPruefen(req, env);
  const b = await leseJson<{ adresse?: unknown }>(req, 2_000);
  const suche = text(b.adresse, 300).trim().replace(/\s+/g, " ");
  if (suche.length < 3) throw new HttpFehler(400, "Adresse fehlt");
  const ergebnis = await gespeichert(`koordinaten/${encodeURIComponent(suche.toLowerCase())}`, async () => {
    const d = (await dienst(
      holen,
      `${NOMINATIM}/search?format=jsonv2&limit=1&accept-language=de&q=${encodeURIComponent(suche)}`,
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
 * Straßenkilometer zwischen zwei Punkten – für die Strecke, während der Bildschirm aus war.
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

export interface Env {
  DB: D1Database;
  ASSETS: Fetcher;
  /** SHA-256 (hex) des Admin-Passworts – per `wrangler secret put ADMIN_PASSWORT_SHA256` gesetzt. */
  ADMIN_PASSWORT_SHA256: string;
  APP_PAKET: string;
  APP_ZERTIFIKAT_SHA256: string;
}

export class HttpFehler extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export function json(daten: unknown, status = 200, header: HeadersInit = {}): Response {
  return new Response(JSON.stringify(daten), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...header },
  });
}

export async function sha256Hex(text: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Zeitkonstanter Vergleich zweier Hex-Strings gleicher Länge. */
export function gleich(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Zufälliger, URL-tauglicher Code (base64url) mit [bytes] Zufallsbytes. */
export function zufallsCode(bytes = 32): string {
  const b = crypto.getRandomValues(new Uint8Array(bytes));
  let s = "";
  for (const x of b) s += String.fromCharCode(x);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function leseJson<T>(req: Request, maxBytes = 2_000_000): Promise<T> {
  const text = await req.text();
  if (text.length > maxBytes) throw new HttpFehler(413, "Anfrage zu groß");
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new HttpFehler(400, "Ungültiges JSON");
  }
}

export function text(wert: unknown, max: number): string {
  if (wert === null || wert === undefined) return "";
  if (typeof wert !== "string") throw new HttpFehler(400, "Text erwartet");
  if (wert.length > max) throw new HttpFehler(400, `Text zu lang (max. ${max})`);
  return wert;
}

export function zahl(wert: unknown, optional: true): number | null;
export function zahl(wert: unknown, optional?: false): number;
export function zahl(wert: unknown, optional = false): number | null {
  if ((wert === null || wert === undefined) && optional) return null;
  if (typeof wert !== "number" || !Number.isFinite(wert)) throw new HttpFehler(400, "Zahl erwartet");
  return wert;
}

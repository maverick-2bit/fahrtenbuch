import { Env, HttpFehler, gleich, json, leseJson, sha256Hex, zufallsCode } from "./hilfen";

const COOKIE = "fb_sitzung";
const SITZUNG_TAGE = 180;
const MAX_FEHLVERSUCHE = 10;
const SPERRE_MS = 15 * 60_000;

function cookieLesen(req: Request, name: string): string | null {
  const kopf = req.headers.get("cookie") ?? "";
  for (const teil of kopf.split(";")) {
    const [k, ...v] = teil.trim().split("=");
    if (k === name) return v.join("=");
  }
  return null;
}

function sitzungsCookie(wert: string, maxAge: number): string {
  return `${COOKIE}=${wert}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${maxAge}`;
}

/**
 * Admin-Anmeldung mit Passwort. Das Passwort ist ein langer Zufallswert; gespeichert ist nur
 * sein SHA-256 als Worker-Secret. Fehlversuche werden je IP begrenzt.
 */
export async function anmelden(req: Request, env: Env): Promise<Response> {
  const ip = req.headers.get("cf-connecting-ip") ?? "unbekannt";
  const jetzt = Date.now();
  const v = await env.DB.prepare("SELECT anzahl, seit FROM anmeldeversuche WHERE ip = ?")
    .bind(ip)
    .first<{ anzahl: number; seit: number }>();
  if (v && v.anzahl >= MAX_FEHLVERSUCHE && jetzt - v.seit < SPERRE_MS) {
    throw new HttpFehler(429, "Zu viele Fehlversuche – bitte in 15 Minuten erneut versuchen.");
  }

  const { passwort } = await leseJson<{ passwort?: string }>(req, 10_000);
  if (!env.ADMIN_PASSWORT_SHA256) throw new HttpFehler(500, "Admin-Passwort ist nicht eingerichtet");
  const ok = typeof passwort === "string" && gleich(await sha256Hex(passwort.trim()), env.ADMIN_PASSWORT_SHA256.toLowerCase());
  if (!ok) {
    const neu = !v || jetzt - v.seit >= SPERRE_MS;
    await env.DB.prepare(
      "INSERT INTO anmeldeversuche (ip, anzahl, seit) VALUES (?, 1, ?) " +
        "ON CONFLICT(ip) DO UPDATE SET anzahl = CASE WHEN ? THEN 1 ELSE anzahl + 1 END, seit = CASE WHEN ? THEN excluded.seit ELSE seit END",
    )
      .bind(ip, jetzt, neu ? 1 : 0, neu ? 1 : 0)
      .run();
    throw new HttpFehler(401, "Passwort falsch");
  }

  const token = zufallsCode(32);
  const ablauf = jetzt + SITZUNG_TAGE * 86_400_000;
  await env.DB.batch([
    env.DB.prepare("DELETE FROM anmeldeversuche WHERE ip = ?").bind(ip),
    env.DB.prepare("DELETE FROM sitzungen WHERE ablauf < ?").bind(jetzt),
    env.DB.prepare("INSERT INTO sitzungen (id_hash, erstellt, ablauf, info) VALUES (?, ?, ?, ?)").bind(
      await sha256Hex(token),
      jetzt,
      ablauf,
      (req.headers.get("user-agent") ?? "").slice(0, 200),
    ),
  ]);
  return json({ ok: true }, 200, { "set-cookie": sitzungsCookie(token, SITZUNG_TAGE * 86_400) });
}

export async function abmelden(req: Request, env: Env): Promise<Response> {
  const token = cookieLesen(req, COOKIE);
  if (token) await env.DB.prepare("DELETE FROM sitzungen WHERE id_hash = ?").bind(await sha256Hex(token)).run();
  return json({ ok: true }, 200, { "set-cookie": sitzungsCookie("", 0) });
}

/** Prüft die Admin-Sitzung; bei ändernden Anfragen zusätzlich einen eigenen Header gegen CSRF. */
export async function adminPruefen(req: Request, env: Env): Promise<void> {
  const token = cookieLesen(req, COOKIE);
  if (!token) throw new HttpFehler(401, "Nicht angemeldet");
  const s = await env.DB.prepare("SELECT ablauf FROM sitzungen WHERE id_hash = ?")
    .bind(await sha256Hex(token))
    .first<{ ablauf: number }>();
  if (!s || s.ablauf < Date.now()) throw new HttpFehler(401, "Nicht angemeldet");
  if (req.method !== "GET" && req.headers.get("x-fahrtenbuch") !== "1") {
    throw new HttpFehler(403, "Anfrage nicht erlaubt");
  }
}

export interface FahrerZeile {
  id: string;
  name: string;
  aktiv: number;
}

/** Prüft den Geräte-Code eines Fahrers (Authorization: Bearer …). */
export async function fahrerPruefen(req: Request, env: Env): Promise<FahrerZeile> {
  const kopf = req.headers.get("authorization") ?? "";
  const m = /^Bearer\s+([A-Za-z0-9_-]{20,100})$/.exec(kopf);
  if (!m) throw new HttpFehler(401, "Geräte-Code fehlt");
  const f = await env.DB.prepare("SELECT id, name, aktiv FROM fahrer WHERE token_hash = ?")
    .bind(await sha256Hex(m[1]))
    .first<FahrerZeile>();
  if (!f) throw new HttpFehler(401, "Geräte-Code ungültig");
  if (!f.aktiv) throw new HttpFehler(403, "Fahrer ist gesperrt");
  return f;
}

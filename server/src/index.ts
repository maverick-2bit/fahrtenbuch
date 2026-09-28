import { admin } from "./admin";
import { abmelden, anmelden } from "./anmeldung";
import { Env, HttpFehler, json } from "./hilfen";
import { ich, sync } from "./sync";

async function api(req: Request, env: Env, url: URL): Promise<Response> {
  const p = url.pathname;
  if (p === "/api/v1/sync" && req.method === "POST") return sync(req, env);
  if (p === "/api/v1/ich" && req.method === "GET") return ich(req, env);
  if (p === "/api/admin/anmelden" && req.method === "POST") return anmelden(req, env);
  if (p === "/api/admin/abmelden" && req.method === "POST") return abmelden(req, env);
  if (p.startsWith("/api/admin/")) return admin(req, env, url);
  throw new HttpFehler(404, "Unbekannte Anfrage");
}

/** Android App Links: Der Verbindungs-Link öffnet direkt die Fahrtenbuch-App. */
export function assetlinks(env: Env): Response {
  return json([
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: { namespace: "android_app", package_name: env.APP_PAKET, sha256_cert_fingerprints: [env.APP_ZERTIFIKAT_SHA256] },
    },
  ]);
}

/** Gemeinsamer Einstieg für Cloudflare Pages (functions/) und den Worker in Tests und lokaler Entwicklung. */
export async function verarbeiten(req: Request, env: Env): Promise<Response> {
  const url = new URL(req.url);
  try {
    if (url.pathname === "/.well-known/assetlinks.json") return assetlinks(env);
    if (url.pathname.startsWith("/api/")) return await api(req, env, url);
    return env.ASSETS.fetch(req);
  } catch (e) {
    if (e instanceof HttpFehler) return json({ fehler: e.message }, e.status);
    console.error(e);
    return json({ fehler: "Interner Fehler" }, 500);
  }
}

export default {
  fetch: (req: Request, env: Env) => verarbeiten(req, env),
} satisfies ExportedHandler<Env>;

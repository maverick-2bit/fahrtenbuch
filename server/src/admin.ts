import { adminPruefen } from "./anmeldung";
import { Env, HttpFehler, json, leseJson, sha256Hex, text, zufallsCode } from "./hilfen";

function verbindungsLink(req: Request, env: Env, code: string): string {
  // Öffentliche Adresse aus der Konfiguration (die App akzeptiert nur diese), sonst die aufgerufene – immer https
  const basis = env.OEFFENTLICHE_ADRESSE?.replace(/\/+$/, "") || `https://${new URL(req.url).host}`;
  return `${basis}/verbinden#code=${code}`;
}

async function neuerCode(env: Env, fahrerId: string): Promise<string> {
  const code = zufallsCode(32);
  await env.DB.prepare("UPDATE fahrer SET token_hash = ? WHERE id = ?").bind(await sha256Hex(code), fahrerId).run();
  return code;
}

async function fahrerHolen(env: Env, id: string) {
  const f = await env.DB.prepare("SELECT * FROM fahrer WHERE id = ?").bind(id).first<Record<string, unknown>>();
  if (!f) throw new HttpFehler(404, "Fahrer nicht gefunden");
  return f;
}

function ohneGeheimnis(f: Record<string, unknown>): Record<string, unknown> {
  const { token_hash, ...rest } = f;
  return { ...rest, hat_code: token_hash !== null };
}

export async function admin(req: Request, env: Env, url: URL): Promise<Response> {
  await adminPruefen(req, env);
  const teile = url.pathname.replace(/^\/api\/admin\/?/, "").split("/").filter(Boolean);
  const m = req.method;

  // GET /api/admin/ich
  if (teile[0] === "ich" && m === "GET") return json({ angemeldet: true });

  // GET /api/admin/fahrer – Übersicht mit Kennzahlen
  if (teile[0] === "fahrer" && teile.length === 1 && m === "GET") {
    const r = await env.DB.prepare(
      `SELECT f.*,
              (SELECT COUNT(*) FROM fahrten t WHERE t.fahrer_id = f.id AND t.geloescht = 0) AS anzahl_fahrten,
              (SELECT MAX(t.start_zeit) FROM fahrten t WHERE t.fahrer_id = f.id AND t.geloescht = 0) AS letzte_fahrt,
              (SELECT COUNT(*) FROM fahrten t WHERE t.fahrer_id = f.id AND t.geloescht = 0 AND t.status = 'offen') AS offene
       FROM fahrer f ORDER BY f.name COLLATE NOCASE`,
    ).all<Record<string, unknown>>();
    return json({ fahrer: r.results.map(ohneGeheimnis) });
  }

  // POST /api/admin/fahrer {name} – neuen Fahrer anlegen, Code nur jetzt im Klartext
  if (teile[0] === "fahrer" && teile.length === 1 && m === "POST") {
    const { name } = await leseJson<{ name?: string }>(req, 10_000);
    const n = text(name, 100).trim();
    if (!n) throw new HttpFehler(400, "Name fehlt");
    const id = zufallsCode(9);
    await env.DB.prepare("INSERT INTO fahrer (id, name, erstellt) VALUES (?, ?, ?)").bind(id, n, Date.now()).run();
    const code = await neuerCode(env, id);
    return json({ fahrer: ohneGeheimnis(await fahrerHolen(env, id)), code, link: verbindungsLink(req, env, code) }, 201);
  }

  // POST /api/admin/fahrer/:id/code – neuer Code, der alte wird ungültig
  if (teile[0] === "fahrer" && teile[2] === "code" && m === "POST") {
    await fahrerHolen(env, teile[1]);
    const code = await neuerCode(env, teile[1]);
    return json({ code, link: verbindungsLink(req, env, code) });
  }

  // POST /api/admin/fahrer/:id {name?, aktiv?}
  if (teile[0] === "fahrer" && teile.length === 2 && m === "POST") {
    await fahrerHolen(env, teile[1]);
    const b = await leseJson<{ name?: string; aktiv?: boolean }>(req, 10_000);
    if (b.name !== undefined) {
      const n = text(b.name, 100).trim();
      if (!n) throw new HttpFehler(400, "Name fehlt");
      await env.DB.prepare("UPDATE fahrer SET name = ? WHERE id = ?").bind(n, teile[1]).run();
    }
    if (typeof b.aktiv === "boolean") {
      await env.DB.prepare("UPDATE fahrer SET aktiv = ? WHERE id = ?").bind(b.aktiv ? 1 : 0, teile[1]).run();
    }
    return json({ fahrer: ohneGeheimnis(await fahrerHolen(env, teile[1])) });
  }

  // GET /api/admin/fahrten?fahrer=&von=&bis= – Fahrtenbuch eines Zeitraums
  if (teile[0] === "fahrten" && teile.length === 1 && m === "GET") {
    const fahrerId = url.searchParams.get("fahrer") ?? "";
    const von = Number(url.searchParams.get("von"));
    const bis = Number(url.searchParams.get("bis"));
    if (!Number.isFinite(von) || !Number.isFinite(bis) || bis <= von) throw new HttpFehler(400, "Zeitraum ungültig");
    const f = ohneGeheimnis(await fahrerHolen(env, fahrerId));
    const [fahrten, kategorien, vorher] = await env.DB.batch([
      env.DB.prepare(
        `SELECT uuid, version, start_zeit, ende_zeit, start_adresse, ende_adresse, zwischenziele, distanz_meter,
                kategorie_id, kategorie_name, notiz, status, geheim
         FROM fahrten WHERE fahrer_id = ? AND geloescht = 0 AND start_zeit >= ? AND start_zeit < ?
         ORDER BY start_zeit`,
      ).bind(fahrerId, von, bis),
      env.DB.prepare("SELECT id, name, farbe, sortierung, aktiv FROM kategorien WHERE fahrer_id = ? ORDER BY sortierung, name").bind(fahrerId),
      // Kilometer abgeschlossener Fahrten zwischen Stichtag und Beginn des Zeitraums (wie in der App gerundet)
      env.DB.prepare(
        `SELECT COALESCE(SUM(ROUND(distanz_meter / 100.0) / 10.0), 0) AS km FROM fahrten
         WHERE fahrer_id = ? AND geloescht = 0 AND status = 'fertig' AND start_zeit >= ? AND start_zeit < ?`,
      ).bind(fahrerId, Number(f.km_stand_ab ?? 0), von),
    ]);
    const kmStart = Number(f.km_stand_start ?? 0);
    const kmVorher = kmStart > 0 ? kmStart + (von > Number(f.km_stand_ab ?? 0) ? Number((vorher.results[0] as { km: number }).km) : 0) : null;
    return json({ fahrer: f, fahrten: fahrten.results, kategorien: kategorien.results, kmVorher });
  }

  // GET /api/admin/fahrten/:uuid/protokoll – alle Fassungen einer Fahrt
  if (teile[0] === "fahrten" && teile[2] === "protokoll" && m === "GET") {
    const r = await env.DB.prepare(
      "SELECT version, aktion, quelle, zeit_geraet, zeit_server, daten FROM fahrten_protokoll WHERE uuid = ? ORDER BY version",
    )
      .bind(teile[1])
      .all<{ daten: string }>();
    return json({ fassungen: r.results.map((x) => ({ ...x, daten: JSON.parse(x.daten) })) });
  }

  throw new HttpFehler(404, "Unbekannte Anfrage");
}

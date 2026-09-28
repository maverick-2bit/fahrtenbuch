import { SELF, env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";

const BASIS = "https://fahrtenbuch.2bit.at";
const PASSWORT = "Test-Passwort-fuer-Vitest-42";

async function anfrage(pfad: string, init: RequestInit = {}) {
  return SELF.fetch(BASIS + pfad, init);
}

async function adminCookie(): Promise<string> {
  const r = await anfrage("/api/admin/anmelden", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": "10.0.0.1" },
    body: JSON.stringify({ passwort: PASSWORT }),
  });
  expect(r.status).toBe(200);
  const c = r.headers.get("set-cookie")!;
  return c.split(";")[0];
}

async function adminPost(cookie: string, pfad: string, body: unknown) {
  return anfrage(pfad, {
    method: "POST",
    headers: { cookie, "content-type": "application/json", "x-fahrtenbuch": "1" },
    body: JSON.stringify(body),
  });
}

async function fahrerAnlegen(cookie: string, name: string): Promise<{ id: string; code: string; link: string }> {
  const r = await adminPost(cookie, "/api/admin/fahrer", { name });
  expect(r.status).toBe(201);
  const d = await r.json<{ fahrer: { id: string }; code: string; link: string }>();
  return { id: d.fahrer.id, code: d.code, link: d.link };
}

function fahrtDaten(extra: Record<string, unknown> = {}) {
  return {
    startZeit: Date.UTC(2026, 8, 28, 6, 0),
    endeZeit: Date.UTC(2026, 8, 28, 7, 0),
    startAdresse: "Zuhause, Hauptstraße 1, 8700 Leoben",
    endeAdresse: "Kunde A, Hauptplatz 1, 8010 Graz",
    zwischenziele: "",
    startLat: 47.38,
    startLon: 15.09,
    endeLat: 47.07,
    endeLon: 15.44,
    distanzMeter: 62_345.6,
    kategorieId: 1,
    notiz: "",
    status: "offen",
    ...extra,
  };
}

async function sync(code: string, body: unknown) {
  return anfrage("/api/v1/sync", {
    method: "POST",
    headers: { authorization: `Bearer ${code}`, "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

describe("Admin-Anmeldung", () => {
  it("lehnt falsches Passwort ab und akzeptiert das richtige", async () => {
    const falsch = await anfrage("/api/admin/anmelden", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "10.0.0.2" },
      body: JSON.stringify({ passwort: "falsch" }),
    });
    expect(falsch.status).toBe(401);

    expect((await anfrage("/api/admin/ich")).status).toBe(401);
    const cookie = await adminCookie();
    expect((await anfrage("/api/admin/ich", { headers: { cookie } })).status).toBe(200);

    // Abmelden beendet die Sitzung
    await adminPost(cookie, "/api/admin/abmelden", {});
    expect((await anfrage("/api/admin/ich", { headers: { cookie } })).status).toBe(401);
  });

  it("sperrt nach zu vielen Fehlversuchen", async () => {
    const versuch = () =>
      anfrage("/api/admin/anmelden", {
        method: "POST",
        headers: { "content-type": "application/json", "cf-connecting-ip": "10.9.9.9" },
        body: JSON.stringify({ passwort: "falsch" }),
      });
    for (let i = 0; i < 10; i++) expect((await versuch()).status).toBe(401);
    expect((await versuch()).status).toBe(429);
  });

  it("verlangt bei ändernden Admin-Anfragen den eigenen Header (Schutz vor CSRF)", async () => {
    const cookie = await adminCookie();
    const r = await anfrage("/api/admin/fahrer", {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: JSON.stringify({ name: "Ohne Header" }),
    });
    expect(r.status).toBe(403);
  });
});

describe("Fahrer und Geräte-Code", () => {
  it("legt Fahrer an, prüft den Code und macht ihn bei Neuvergabe ungültig", async () => {
    const cookie = await adminCookie();
    const f = await fahrerAnlegen(cookie, "Thomas");
    expect(f.link).toBe(`${BASIS}/verbinden#code=${f.code}`);

    const ich = await anfrage("/api/v1/ich", { headers: { authorization: `Bearer ${f.code}` } });
    expect(await ich.json()).toEqual({ fahrer: { name: "Thomas" }, schluessel: null });

    const neu = await adminPost(cookie, `/api/admin/fahrer/${f.id}/code`, {});
    const { code } = await neu.json<{ code: string }>();
    expect((await anfrage("/api/v1/ich", { headers: { authorization: `Bearer ${f.code}` } })).status).toBe(401);
    expect((await anfrage("/api/v1/ich", { headers: { authorization: `Bearer ${code}` } })).status).toBe(200);

    // Gesperrter Fahrer
    await adminPost(cookie, `/api/admin/fahrer/${f.id}`, { aktiv: false });
    expect((await anfrage("/api/v1/ich", { headers: { authorization: `Bearer ${code}` } })).status).toBe(403);
  });

  it("gibt den Code-Hash in der Übersicht nicht heraus", async () => {
    const cookie = await adminCookie();
    await fahrerAnlegen(cookie, "Kollege");
    const r = await anfrage("/api/admin/fahrer", { headers: { cookie } });
    const text = await r.text();
    expect(text).not.toContain("token_hash");
    expect(text).toContain('"hat_code":true');
  });
});

describe("Synchronisation und Änderungsprotokoll", () => {
  let code: string;
  let fahrerId: string;
  let cookie: string;

  beforeEach(async () => {
    cookie = await adminCookie();
    const f = await fahrerAnlegen(cookie, "Thomas");
    code = f.code;
    fahrerId = f.id;
  });

  const kategorien = [
    { id: 1, name: "Dienstlich", farbe: 4280191205, sortierung: 1, aktiv: true },
    { id: 2, name: "Privat", farbe: 4282622023, sortierung: 2, aktiv: true },
  ];

  it("legt Fassungen fortlaufend an und übernimmt den letzten Stand", async () => {
    const r = await sync(code, {
      app: { version: "0.4.0", geraet: "SM-G556B" },
      einstellungen: { fahrer: "Thomas", fahrzeug: "VW Tiguan", kennzeichen: "MT 317 AS" },
      kategorien,
      eintraege: [
        { eintragId: "e-00000001", uuid: "fahrt-000001", aktion: "neu", zeit: 1, daten: fahrtDaten() },
        {
          eintragId: "e-00000002", uuid: "fahrt-000001", aktion: "geaendert", zeit: 2,
          daten: fahrtDaten({ status: "fertig", notiz: "Kunde A" }),
        },
      ],
    });
    expect(r.status).toBe(200);
    const d = await r.json<{ angenommen: string[]; abgelehnt: unknown[] }>();
    expect(d.angenommen.sort()).toEqual(["e-00000001", "e-00000002"]);
    expect(d.abgelehnt).toEqual([]);

    const fahrt = await env.DB.prepare("SELECT * FROM fahrten WHERE uuid = 'fahrt-000001'").first<Record<string, unknown>>();
    expect(fahrt).toMatchObject({ version: 2, status: "fertig", notiz: "Kunde A", kategorie_name: "Dienstlich", geloescht: 0 });

    const prot = await env.DB.prepare("SELECT version, aktion FROM fahrten_protokoll WHERE uuid = 'fahrt-000001' ORDER BY version").all();
    expect(prot.results).toEqual([
      { version: 1, aktion: "neu" },
      { version: 2, aktion: "geaendert" },
    ]);

    const fahrer = await env.DB.prepare("SELECT kennzeichen, fahrzeug FROM fahrer WHERE id = ?").bind(fahrerId).first();
    expect(fahrer).toEqual({ kennzeichen: "MT 317 AS", fahrzeug: "VW Tiguan" });
  });

  it("verarbeitet doppelt gesendete Einträge nur einmal", async () => {
    const eintrag = { eintragId: "e-00000010", uuid: "fahrt-000010", aktion: "neu", zeit: 1, daten: fahrtDaten() };
    await sync(code, { kategorien, eintraege: [eintrag] });
    const r = await sync(code, { kategorien, eintraege: [eintrag] });
    const d = await r.json<{ angenommen: string[] }>();
    expect(d.angenommen).toEqual(["e-00000010"]);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM fahrten_protokoll WHERE uuid = 'fahrt-000010'").first<{ n: number }>();
    expect(n!.n).toBe(1);
  });

  it("markiert gelöschte Fahrten, das Protokoll bleibt vollständig", async () => {
    await sync(code, { eintraege: [{ eintragId: "e-00000020", uuid: "fahrt-000020", aktion: "neu", zeit: 1, daten: fahrtDaten() }] });
    await sync(code, { eintraege: [{ eintragId: "e-00000021", uuid: "fahrt-000020", aktion: "geloescht", zeit: 2, daten: fahrtDaten() }] });
    const f = await env.DB.prepare("SELECT geloescht, version FROM fahrten WHERE uuid = 'fahrt-000020'").first();
    expect(f).toEqual({ geloescht: 1, version: 2 });
  });

  it("verhindert Änderungen und Löschungen im Protokoll", async () => {
    await sync(code, { eintraege: [{ eintragId: "e-00000030", uuid: "fahrt-000030", aktion: "neu", zeit: 1, daten: fahrtDaten() }] });
    await expect(env.DB.prepare("UPDATE fahrten_protokoll SET daten = '{}' WHERE uuid = 'fahrt-000030'").run()).rejects.toThrow(
      /unveränderlich/,
    );
    await expect(env.DB.prepare("DELETE FROM fahrten_protokoll WHERE uuid = 'fahrt-000030'").run()).rejects.toThrow(/unveränderlich/);
  });

  it("lässt keinen Fahrer die Fahrten eines anderen überschreiben", async () => {
    await sync(code, { eintraege: [{ eintragId: "e-00000040", uuid: "fahrt-000040", aktion: "neu", zeit: 1, daten: fahrtDaten() }] });
    const fremd = await fahrerAnlegen(cookie, "Fremd");
    const r = await sync(fremd.code, {
      eintraege: [
        { eintragId: "e-00000041", uuid: "fahrt-000040", aktion: "geaendert", zeit: 2, daten: fahrtDaten({ notiz: "manipuliert" }) },
      ],
    });
    const d = await r.json<{ angenommen: string[]; abgelehnt: { eintragId: string }[] }>();
    expect(d.angenommen).toEqual([]);
    expect(d.abgelehnt.map((x) => x.eintragId)).toEqual(["e-00000041"]);
    const f = await env.DB.prepare("SELECT notiz, fahrer_id FROM fahrten WHERE uuid = 'fahrt-000040'").first();
    expect(f).toEqual({ notiz: "", fahrer_id: fahrerId });
  });

  it("nimmt gültige Einträge an und lehnt ungültige einzeln ab", async () => {
    const r = await sync(code, {
      eintraege: [
        { eintragId: "e-00000050", uuid: "fahrt-000050", aktion: "neu", zeit: 1, daten: fahrtDaten() },
        { eintragId: "e-00000051", uuid: "fahrt-000051", aktion: "neu", zeit: 1, daten: fahrtDaten({ status: "laufend" }) },
      ],
    });
    const d = await r.json<{ angenommen: string[]; abgelehnt: { eintragId: string }[] }>();
    expect(d.angenommen).toEqual(["e-00000050"]);
    expect(d.abgelehnt.map((x) => x.eintragId)).toEqual(["e-00000051"]);
  });

  it("liefert dem Admin Fahrtenbuch, Kategorien, Kilometerstand und Protokoll", async () => {
    const tag = (t: number) => Date.UTC(2026, 8, t, 6, 0);
    await sync(code, {
      einstellungen: { kmStandStart: 10_000, kmStandAb: tag(1) },
      kategorien,
      eintraege: [
        { eintragId: "e-00000060", uuid: "fahrt-000060", aktion: "neu", zeit: 1, daten: fahrtDaten({ startZeit: tag(2), status: "fertig", distanzMeter: 12_340 }) },
        { eintragId: "e-00000061", uuid: "fahrt-000061", aktion: "neu", zeit: 1, daten: fahrtDaten({ startZeit: tag(10), status: "fertig", distanzMeter: 7_660, kategorieId: 2 }) },
      ],
    });
    const r = await anfrage(`/api/admin/fahrten?fahrer=${fahrerId}&von=${tag(5)}&bis=${tag(30)}`, { headers: { cookie } });
    expect(r.status).toBe(200);
    const d = await r.json<{ fahrten: { uuid: string; kategorie_name: string }[]; kategorien: unknown[]; kmVorher: number }>();
    expect(d.fahrten.map((f) => f.uuid)).toEqual(["fahrt-000061"]);
    expect(d.fahrten[0].kategorie_name).toBe("Privat");
    expect(d.kategorien).toHaveLength(2);
    expect(d.kmVorher).toBeCloseTo(10_012.3, 5);

    const p = await anfrage("/api/admin/fahrten/fahrt-000061/protokoll", { headers: { cookie } });
    const prot = await p.json<{ fassungen: { version: number; daten: { distanzMeter: number } }[] }>();
    expect(prot.fassungen).toHaveLength(1);
    expect(prot.fassungen[0].daten.distanzMeter).toBe(7_660);
  });

  it("speichert Privatfahrten nur verschlüsselt, samt Schlüsselhülle des Fahrers", async () => {
    const huelle = { v: 1, iter: 310000, salt: "AQIDBAUGBwgJCgsMDQ4PEA==", iv: "ZGVmZ2hpamtsbW5v", ct: "WEM8D4tb13Z/J45k3KQOfMNY00I86zSe7oyPy/WnyJ9eiQ7FhWccsUHJ9/odcuno" };
    const geheim = { v: 1, iv: "MjM0NTY3ODk6Ozw9", ct: "fa5eQmMNq0RuaIhlcL2e/JTKxJ6qjjyvLk4Hvt5eMuJHDejD" };
    const r = await sync(code, {
      schluessel: huelle,
      kategorien,
      eintraege: [
        {
          eintragId: "e-00000070", uuid: "fahrt-000070", aktion: "neu", zeit: 1,
          // Klartext, der trotz Verschlüsselung mitkommt, darf nicht gespeichert werden
          daten: fahrtDaten({ kategorieId: 2, status: "fertig", geheim, startAdresse: "Geheim", notiz: "Geheim" }),
        },
      ],
    });
    expect((await r.json<{ angenommen: string[] }>()).angenommen).toEqual(["e-00000070"]);

    const f = await env.DB.prepare("SELECT start_adresse, ende_adresse, notiz, start_lat, geheim FROM fahrten WHERE uuid = 'fahrt-000070'").first<Record<string, unknown>>();
    expect(f).toMatchObject({ start_adresse: "", ende_adresse: "", notiz: "", start_lat: null });
    expect(JSON.parse(String(f!.geheim))).toEqual(geheim);
    const p = await env.DB.prepare("SELECT daten FROM fahrten_protokoll WHERE uuid = 'fahrt-000070'").first<{ daten: string }>();
    expect(p!.daten).not.toContain("Geheim");
    expect(p!.daten).not.toContain("47.38");

    // Ein neues Gerät bekommt die Hülle, um mit dem PIN den bisherigen Datenschlüssel zu übernehmen
    const ich = await anfrage("/api/v1/ich", { headers: { authorization: `Bearer ${code}` } });
    expect((await ich.json<{ schluessel: unknown }>()).schluessel).toEqual(huelle);

    const a = await anfrage(`/api/admin/fahrten?fahrer=${fahrerId}&von=0&bis=${Date.UTC(2030, 0, 1)}`, { headers: { cookie } });
    const d = await a.json<{ fahrer: { schluessel: string }; fahrten: { geheim: string }[] }>();
    expect(JSON.parse(d.fahrer.schluessel)).toEqual(huelle);
    expect(JSON.parse(d.fahrten[0].geheim)).toEqual(geheim);
  });

  it("lehnt ungültige Verschlüsselungsdaten ab", async () => {
    const r = await sync(code, {
      eintraege: [
        { eintragId: "e-00000080", uuid: "fahrt-000080", aktion: "neu", zeit: 1, daten: fahrtDaten({ geheim: { v: 2, iv: "x", ct: "y" } }) },
      ],
    });
    const d = await r.json<{ abgelehnt: { eintragId: string }[] }>();
    expect(d.abgelehnt.map((x) => x.eintragId)).toEqual(["e-00000080"]);
    const kaputt = await sync(code, { schluessel: { v: 1, iter: 10, salt: "AQIDBAUGBwgJCgsMDQ4PEA==", iv: "ZGVmZ2hpamtsbW5v", ct: "WEM8" } });
    expect(kaputt.status).toBe(400);
  });

  it("verweigert den Sync ohne gültigen Code", async () => {
    expect((await sync("ungueltiger-code-ungueltiger-code", { eintraege: [] })).status).toBe(401);
    const ohne = await anfrage("/api/v1/sync", { method: "POST", body: "{}" });
    expect(ohne.status).toBe(401);
  });
});

describe("App Links", () => {
  it("liefert assetlinks.json mit Paketname und Zertifikat", async () => {
    const r = await anfrage("/.well-known/assetlinks.json");
    const d = await r.json<{ target: { package_name: string; sha256_cert_fingerprints: string[] } }[]>();
    expect(d[0].target.package_name).toBe("at.zweibit.fahrtenbuch");
    expect(d[0].target.sha256_cert_fingerprints[0]).toMatch(/^6F:76:48/);
  });
});

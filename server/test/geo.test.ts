import { SELF, env } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import { adresse, adresseFormatieren, koordinaten, route } from "../src/geo";
import { HttpFehler } from "../src/hilfen";

const BASIS = "https://fahrtenbuch.smarte.events";
const PASSWORT = "Test-Passwort-fuer-Vitest-42";

let code = "";

beforeAll(async () => {
  const a = await SELF.fetch(`${BASIS}/api/admin/anmelden`, {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": "10.0.1.1" },
    body: JSON.stringify({ passwort: PASSWORT }),
  });
  const cookie = a.headers.get("set-cookie")!.split(";")[0];
  const f = await SELF.fetch(`${BASIS}/api/admin/fahrer`, {
    method: "POST",
    headers: { cookie, "content-type": "application/json", "x-fahrtenbuch": "1" },
    body: JSON.stringify({ name: "Bernd" }),
  });
  code = (await f.json<{ code: string }>()).code;
});

function anfrage(pfad: string, body: unknown, mitCode = true) {
  return new Request(`${BASIS}${pfad}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...(mitCode ? { authorization: `Bearer ${code}` } : {}) },
    body: JSON.stringify(body),
  });
}

/** Ersatz für den Kartendienst: merkt sich die Aufrufe und liefert die vorgegebene Antwort. */
function dienst(antwort: unknown, status = 200) {
  const aufrufe: { url: string; headers: Headers }[] = [];
  const holen = async (input: RequestInfo | URL, init?: RequestInit) => {
    aufrufe.push({ url: String(input), headers: new Headers(init?.headers) });
    return new Response(JSON.stringify(antwort), { status });
  };
  return { holen, aufrufe };
}

async function fehlerStatus(p: Promise<Response>): Promise<number> {
  try {
    return (await p).status;
  } catch (e) {
    if (e instanceof HttpFehler) return e.status;
    throw e;
  }
}

describe("Adressen für die Web-App", () => {
  it("formatiert wie die Android-App", () => {
    expect(adresseFormatieren({ road: "Hauptstraße", house_number: "1", postcode: "8700", city: "Leoben" })).toBe("Hauptstraße 1, 8700 Leoben");
    expect(adresseFormatieren({ road: "Dorfweg", postcode: "8734", village: "Großlobming" })).toBe("Dorfweg, 8734 Großlobming");
    expect(adresseFormatieren({}, "Gleinalmtunnel, A9, Steiermark, Österreich")).toBe("Gleinalmtunnel, A9, Steiermark");
  });

  it("fragt Nominatim mit eigener Kennung und gerundeten Koordinaten und speichert die Antwort zwischen", async () => {
    const d = dienst({ address: { road: "Hauptplatz", house_number: "1", postcode: "8010", city: "Graz" }, display_name: "x" });
    const r = await adresse(anfrage("/api/v1/adresse", { lat: 47.0706712, lon: 15.4382999 }), env, d.holen);
    expect(await r.json()).toEqual({ adresse: "Hauptplatz 1, 8010 Graz" });
    expect(d.aufrufe).toHaveLength(1);
    expect(d.aufrufe[0].url).toContain("nominatim.openstreetmap.org/reverse?");
    expect(d.aufrufe[0].url).toContain("lat=47.0707&lon=15.4383");
    expect(d.aufrufe[0].headers.get("user-agent")).toMatch(/^Fahrtenbuch\/.+fahrtenbuch/);

    // Zweite Abfrage in der Nähe (gleiche gerundete Koordinaten): aus dem Zwischenspeicher
    const zweite = dienst({ error: "sollte nicht gefragt werden" });
    const r2 = await adresse(anfrage("/api/v1/adresse", { lat: 47.07068, lon: 15.43831 }), env, zweite.holen);
    expect(await r2.json()).toEqual({ adresse: "Hauptplatz 1, 8010 Graz" });
    expect(zweite.aufrufe).toHaveLength(0);
  });

  it("liefert eine leere Adresse, wenn der Dienst nichts kennt, und 502 bei Ausfall", async () => {
    const leer = dienst({ error: "Unable to geocode" });
    const r = await adresse(anfrage("/api/v1/adresse", { lat: 1.5, lon: 1.5 }), env, leer.holen);
    expect(await r.json()).toEqual({ adresse: "" });
    const kaputt = dienst({}, 503);
    expect(await fehlerStatus(adresse(anfrage("/api/v1/adresse", { lat: 1.6, lon: 1.6 }), env, kaputt.holen))).toBe(502);
  });

  it("prüft Koordinaten und verlangt einen gültigen Geräte-Code", async () => {
    const d = dienst({});
    expect(await fehlerStatus(adresse(anfrage("/api/v1/adresse", { lat: 95, lon: 15 }), env, d.holen))).toBe(400);
    expect(await fehlerStatus(adresse(anfrage("/api/v1/adresse", { lat: "47", lon: 15 }), env, d.holen))).toBe(400);
    expect(d.aufrufe).toHaveLength(0);
    // Über den Server ohne Code: kein offener Vermittler zu den Kartendiensten
    for (const pfad of ["/api/v1/adresse", "/api/v1/koordinaten", "/api/v1/route"]) {
      const r = await SELF.fetch(anfrage(pfad, { lat: 47, lon: 15 }, false));
      expect(r.status).toBe(401);
    }
  });

  it("sucht Koordinaten zu einer Adresse", async () => {
    const d = dienst([{ lat: "47.3800", lon: "15.0900", display_name: "Hauptstraße 1, Leoben" }]);
    const r = await koordinaten(anfrage("/api/v1/koordinaten", { adresse: "Hauptstraße 1, 8700 Leoben" }), env, d.holen);
    expect(await r.json()).toEqual({ lat: 47.38, lon: 15.09 });
    expect(d.aufrufe[0].url).toContain("search?");
    const nichts = dienst([]);
    const r2 = await koordinaten(anfrage("/api/v1/koordinaten", { adresse: "gibt es nicht 12345" }), env, nichts.holen);
    expect(await r2.json()).toEqual({ lat: null, lon: null });
  });

  it("berechnet Straßenkilometer für eine Aufzeichnungslücke", async () => {
    const d = dienst({ code: "Ok", routes: [{ distance: 12_345.6, duration: 700.4 }] });
    const r = await route(anfrage("/api/v1/route", { von: { lat: 47.38, lon: 15.09 }, nach: { lat: 47.07, lon: 15.44 } }), env, d.holen);
    expect(await r.json()).toEqual({ meter: 12_346, sekunden: 700 });
    // OSRM erwartet Länge vor Breite
    expect(d.aufrufe[0].url).toContain("routed-car/route/v1/driving/15.09,47.38;15.44,47.07");
    const keine = dienst({ code: "NoRoute", routes: [] });
    expect(
      await fehlerStatus(route(anfrage("/api/v1/route", { von: { lat: 10, lon: 10 }, nach: { lat: 11, lon: 11 } }), env, keine.holen)),
    ).toBe(404);
  });
});

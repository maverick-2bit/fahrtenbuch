// Rechenbausteine der iPhone-Web-App (public/app/). Die Fälle entsprechen den Tests der Android-App
// (StreckeTest, OrteZwischenzieleTest, BerichtTest, KryptoTest, SyncFormatTest) – so ist sicher, dass
// beide Apps gleich rechnen und der Server dieselben Daten bekommt.
import assert from "node:assert/strict";
import { describe, test } from "node:test";

// Zeitzone vor dem Laden der Module setzen: die Datumsformate merken sie sich beim Anlegen
process.env.TZ = "Europe/Vienna";
const F = await import("../public/app/format.js");
const { StreckenRechner, StillstandErkennung, distanzMeter, ankunftSchaetzen, nachtragAnwenden, nachtragVorpruefen, nachtragAusRoute, KEIN_NACHTRAG } =
  await import("../public/app/strecke.js");
const O = await import("../public/app/orte.js");
const B = await import("../public/app/bericht.js");
const K = await import("../public/app/krypto.js");
const S = await import("../public/app/syncformat.js");

const nahe = (ist, soll, toleranz, text) => assert.ok(Math.abs(ist - soll) <= toleranz, `${text ?? ""} war ${ist}, erwartet ${soll} ± ${toleranz}`);

describe("Strecke", () => {
  // 0,001° Breite ≈ 111,2 m
  const p = (latOffset, sek, genauigkeit = 5) => ({ lat: 48.2 + latOffset, lon: 16.37, zeit: sek * 1000, genauigkeit });

  test("Luftlinie Wien – Graz", () => {
    const d = distanzMeter(48.2085, 16.3731, 47.0707, 15.4382);
    assert.ok(d > 144_000 && d < 147_000, `war ${d}`);
  });

  test("summiert eine gerade Strecke", () => {
    const r = new StreckenRechner();
    for (let i = 0; i <= 10; i++) r.hinzufuegen(p(i * 0.001, i * 10));
    nahe(r.meter, 1112, 5);
  });

  test("ignoriert ungenaue Punkte", () => {
    const r = new StreckenRechner();
    assert.equal(r.hinzufuegen(p(0, 0)).uebernommen, true);
    assert.equal(r.hinzufuegen(p(0.01, 10, 200)).uebernommen, false);
    assert.equal(r.meter, 0);
  });

  test("verwirft GPS-Sprünge", () => {
    const r = new StreckenRechner();
    r.hinzufuegen(p(0, 0));
    assert.equal(r.hinzufuegen(p(0.1, 10)).uebernommen, false); // 11 km in 10 s
    assert.equal(r.hinzufuegen(p(0.001, 20)).uebernommen, true);
    nahe(r.meter, 111.2, 1);
  });

  test("Zittern im Stand zählt nicht", () => {
    const r = new StreckenRechner();
    r.hinzufuegen(p(0, 0));
    for (let i = 1; i <= 50; i++) r.hinzufuegen(p(i % 2 === 0 ? 0.00003 : -0.00003, i * 5, 8));
    assert.equal(r.meter, 0);
  });

  test("langsame Fahrt geht nicht verloren", () => {
    const r = new StreckenRechner();
    for (let i = 0; i <= 100; i++) r.hinzufuegen(p(i * 0.000045, i));
    nahe(r.meter, 500, 15);
  });

  test("setzt mit gespeichertem Stand fort und meldet Lücken", () => {
    const r = new StreckenRechner({ meter: 1000, letzter: p(0, 0) });
    r.hinzufuegen(p(0.001, 10));
    nahe(r.meter, 1111.2, 1);
    // 5,5 km in 10 Minuten (Bildschirm war aus): wird übernommen und als großer Schritt gemeldet
    const x = new StreckenRechner({ meter: 0, letzter: p(0, 0) });
    const e = x.hinzufuegen(p(0.05, 600));
    assert.equal(e.uebernommen, true);
    nahe(e.schrittM, 5560, 10);
    assert.deepEqual(e.vorher, p(0, 0));
  });

  test("erkennt Stillstand", () => {
    const s = new StillstandErkennung(0);
    s.punkt(p(0, 0));
    s.punkt(p(0.001, 30)); // Bewegung > 75 m
    assert.equal(s.letzteBewegung, 30_000);
    s.punkt(p(0.0012, 60)); // nur ~22 m weiter
    s.punkt(p(0.0011, 400));
    assert.equal(s.letzteBewegung, 30_000);
    assert.equal(s.stillstandMillis(400_000), 370_000);
    // Stand speichern und wiederherstellen
    const t = new StillstandErkennung(0, JSON.parse(JSON.stringify(s.stand)));
    assert.equal(t.stillstandMillis(400_000), 370_000);
  });

  describe("Ankunft schätzen", () => {
    const min = 60_000;
    test("Bildschirm die ganze Zeit an: Knopf = jetzt, automatisch = Anhalten", () => {
      assert.equal(ankunftSchaetzen({ letzteBewegung: 10 * min }, 12 * min, false), 12 * min);
      assert.equal(ankunftSchaetzen({ letzteBewegung: 10 * min }, 15 * min, true), 10 * min);
    });
    test("Bildschirm erst nach dem Anhalten aus: Ankunft = Anhalten", () => {
      assert.equal(ankunftSchaetzen({ letzteBewegung: 10 * min, verborgenAb: 11 * min }, 70 * min, false), 10 * min);
      // Bildschirm war früher schon einmal aus, danach ging die Fahrt weiter: jetzt
      assert.equal(ankunftSchaetzen({ letzteBewegung: 10 * min, verborgenAb: 5 * min }, 12 * min, false), 12 * min);
    });
    test("angekommen während der Lücke: Beginn der Lücke + Fahrzeit laut Route", () => {
      const luecke = { von: 10 * min, bis: 70 * min, sekunden: 15 * 60 };
      assert.equal(ankunftSchaetzen({ letzteBewegung: 70 * min, luecke }, 72 * min, false), 25 * min);
      assert.equal(ankunftSchaetzen({ letzteBewegung: 70 * min, luecke }, 76 * min, true), 25 * min);
      // Ohne Route: Zeitpunkt des Wiedereinschaltens
      assert.equal(ankunftSchaetzen({ letzteBewegung: 70 * min, luecke: { ...luecke, sekunden: null } }, 72 * min, false), 70 * min);
      // Nie später als das Wiedereinschalten
      assert.equal(ankunftSchaetzen({ letzteBewegung: 70 * min, luecke: { ...luecke, sekunden: 5 * 3600 } }, 72 * min, false), 70 * min);
    });
    test("nach der Lücke weitergefahren: normale Regeln", () => {
      const luecke = { von: 10 * min, bis: 70 * min, sekunden: 900 };
      assert.equal(ankunftSchaetzen({ letzteBewegung: 80 * min, luecke }, 82 * min, false), 82 * min);
    });
  });
});

describe("Korrigierter Start (wie StartKorrekturTest der Android-App)", () => {
  const start = 1_759_046_400_000;
  const fahrt = { startZeit: start, distanzMeter: 12_000, startLat: 47.38, startLon: 15.09 };

  test("ergänzt Kilometer und verlegt die Abfahrt vor", () => {
    assert.deepEqual(nachtragAnwenden(fahrt, { meter: 4_200, ms: 420_000 }), {
      distanzMeter: 16_200,
      startZeit: start - 420_000,
      nachtragMeter: 4_200,
      nachtragMs: 420_000,
    });
  });

  test("erneute Korrektur ersetzt den Nachtrag", () => {
    const erste = { ...fahrt, ...nachtragAnwenden(fahrt, { meter: 4_200, ms: 420_000 }) };
    const zweite = { ...erste, ...nachtragAnwenden(erste, { meter: 1_500, ms: 180_000 }) };
    assert.equal(zweite.distanzMeter, 13_500);
    assert.equal(zweite.startZeit, start - 180_000);
    // Zurück auf den ursprünglichen Start: alles wie aufgezeichnet
    const zurueck = { ...zweite, ...nachtragAnwenden(zweite, KEIN_NACHTRAG) };
    assert.deepEqual([zurueck.distanzMeter, zurueck.startZeit, zurueck.nachtragMeter], [12_000, start, 0]);
  });

  test("Luftlinie entscheidet, ob gerechnet wird", () => {
    assert.deepEqual(nachtragVorpruefen(120), { nachtrag: KEIN_NACHTRAG });
    assert.equal(nachtragVorpruefen(3_000), null);
    assert.match(nachtragVorpruefen(180_000).fehler, /180,0 km vom Beginn/);
  });

  test("über die Straße nie kürzer als die Luftlinie", () => {
    assert.deepEqual(nachtragAusRoute({ meter: 4_200, sekunden: 420 }, 3_100), { meter: 4_200, ms: 420_000 });
    assert.deepEqual(nachtragAusRoute({ meter: 2_900, sekunden: 60 }, 3_100), { meter: 3_100, ms: 60_000 });
  });
});

describe("Orte und Zwischenziele", () => {
  test("Zwischenziele speichern und lesen", () => {
    const liste = [
      { adresse: "Kunde A, Hauptplatz 1, 8010 Graz", lat: 47.07, lon: 15.44, an: 1_000, ab: 2_000 },
      { adresse: 'Tankstelle; Nord "Shell"', an: 3_000 },
      { adresse: "Nachgetragen" },
    ];
    assert.deepEqual(O.zwischenzieleLesen(O.zwischenzieleSchreiben(liste)), liste);
    assert.equal(O.zwischenzieleSchreiben([]), "");
    assert.deepEqual(O.zwischenzieleLesen(""), []);
    assert.deepEqual(O.zwischenzieleLesen("kaputt"), []);
  });

  test("schreibt fehlende Werte nicht als null (sonst verwirft die Android-App die Liste)", () => {
    const json = O.zwischenzieleSchreiben([{ adresse: "Kunde", lat: null, lon: undefined, an: 5, ab: null }]);
    assert.equal(json, '[{"adresse":"Kunde","an":5}]');
  });

  test("erkennt eine Pause", () => {
    const basis = { status: "laufend", zwischenziele: "" };
    assert.equal(O.istPausiert(basis), false);
    const inPause = { ...basis, zwischenziele: O.zwischenzieleSchreiben([{ adresse: "Kunde", an: 100 }]) };
    assert.equal(O.istPausiert(inPause), true);
    assert.equal(O.istPausiert({ ...basis, zwischenziele: O.zwischenzieleSchreiben([{ adresse: "Kunde", an: 100, ab: 200 }]) }), false);
    assert.equal(O.istPausiert({ ...inPause, status: "fertig" }), false);
  });

  test("Strecke hin und retour", () => {
    assert.equal(O.streckeText("Zuhause", [{ adresse: "Kunde A" }], "Zuhause"), "Zuhause → Kunde A → Zuhause");
    assert.equal(O.streckeText("", [], ""), "? → ?");
  });

  const zuhause = { id: "z", name: "Zuhause", adresse: "Hauptstraße 1, 8700 Leoben", lat: 47.38, lon: 15.09 };
  const buero = { id: "b", name: "Büro", adresse: "Industriestraße 5, 8700 Leoben", lat: 47.39, lon: 15.1 };
  const ohnePosition = { id: "k", name: "Kunde C", adresse: "Irgendwo 1" };

  test("erkennt gespeicherte Orte im Umkreis", () => {
    assert.equal(O.erkennen([zuhause, buero, ohnePosition], 47.3809, 15.09), zuhause); // ~100 m
    assert.equal(O.erkennen([zuhause, buero], 47.3827, 15.09), null); // ~300 m
    const nah = { ...zuhause, id: "n", name: "Nachbar", lat: 47.3801 };
    assert.equal(O.erkennen([zuhause, nah], 47.3802, 15.09), nah);
  });

  test("Ort als Adresse", () => {
    assert.equal(O.alsAdresse(zuhause), "Zuhause, Hauptstraße 1, 8700 Leoben");
    assert.equal(O.alsAdresse({ name: "", adresse: "Hauptstraße 1" }), "Hauptstraße 1");
    assert.equal(O.alsAdresse({ name: "Zuhause", adresse: " " }), "Zuhause");
  });

  test("erkennt fehlende Adressen", () => {
    assert.equal(O.adresseFehlt(""), true);
    assert.equal(O.adresseFehlt(O.koordinatenText(47.38123456, 15.09)), true);
    assert.equal(O.adresseFehlt("Hauptstraße 1, 8700 Leoben"), false);
  });
});

describe("Berichte", () => {
  const dienst = { id: 1, name: "Dienstlich", farbe: 0xff1e88e5, sortierung: 1, aktiv: true, privat: false };
  const privat = { id: 2, name: "Privat", farbe: 0xff43a047, sortierung: 2, aktiv: true, privat: true };
  const ms = (tag, stunde) => new Date(2026, 8, tag, stunde).getTime();
  const fahrt = (id, tag, meter, k, notiz = "") => ({
    uuid: `f-${id}`,
    startZeit: ms(tag, 8),
    endeZeit: ms(tag, 9),
    startAdresse: "Hauptstraße 1, 1010 Wien",
    endeAdresse: `Ziel ${id}`,
    zwischenziele: "",
    distanzMeter: meter,
    kategorieId: k?.id ?? null,
    notiz,
    status: "fertig",
  });
  const fahrten = [fahrt(3, 20, 30_040, privat), fahrt(1, 2, 12_340, dienst, "Kunde A; Termin"), fahrt(2, 10, 7_660, dienst), fahrt(4, 21, 1_000, null)];
  const september = B.zeitraumGrenzen({ art: "monat", jahr: 2026, monat: 8 });

  test("Zeitraumgrenzen", () => {
    const feb = B.zeitraumGrenzen({ art: "monat", jahr: 2026, monat: 1 });
    assert.equal(feb.von, new Date(2026, 1, 1).getTime());
    assert.equal(feb.bis, new Date(2026, 2, 1).getTime());
    assert.equal(feb.titel, "Februar 2026");
    const jahr = B.zeitraumGrenzen({ art: "jahr", jahr: 2026 });
    assert.equal(jahr.bis, new Date(2027, 0, 1).getTime());
    const tag = B.zeitraumGrenzen({ art: "zeitraum", von: new Date(2026, 8, 5), bis: new Date(2026, 8, 5) });
    assert.equal(tag.bis - tag.von, 24 * 3600 * 1000);
    // Vertauschte Grenzen werden sortiert
    const vertauscht = B.zeitraumGrenzen({ art: "zeitraum", von: new Date(2026, 8, 9), bis: new Date(2026, 8, 5) });
    assert.equal(vertauscht.titel, "05.09.2026 – 09.09.2026");
  });

  test("gruppiert je Kategorie und summiert", () => {
    const b = B.berichtErstellen(fahrten, [dienst, privat]);
    assert.deepEqual(b.bloecke.map((x) => x.name), ["Dienstlich", "Privat", "Ohne Kategorie"]);
    assert.equal(b.bloecke[0].summeKm, 20); // 12,3 + 7,7
    assert.deepEqual(b.bloecke[0].zeilen.map((z) => z.fahrt.uuid), ["f-1", "f-2"]);
    assert.equal(b.bloecke[1].summeKm, 30);
    assert.equal(b.gesamtKm, 51);
    assert.equal(b.gesamtAnzahl, 4);
    nahe(b.bloecke[0].anteil, (20 / 51) * 100, 0.01);
    assert.ok(b.bloecke.every((x) => x.zeilen.every((z) => z.kmStandBeginn === null)));
  });

  test("filtert nach Kategorie (0 = ohne)", () => {
    const b = B.berichtErstellen(fahrten, [dienst, privat], { filter: new Set([2, 0]) });
    assert.deepEqual(b.bloecke.map((x) => x.name), ["Privat", "Ohne Kategorie"]);
    assert.equal(b.alle.length, 3);
  });

  test("Kilometerstand läuft über alle Kategorien", () => {
    const b = B.berichtErstellen(fahrten, [dienst, privat], { kmVorher: 10_000, filter: new Set([2]) });
    const z = b.bloecke[0].zeilen[0];
    assert.equal(z.kmStandBeginn, 10_020);
    assert.equal(z.kmStandEnde, 10_050);
  });

  test("Kilometerstand erst ab dem Stichtag", () => {
    const b = B.berichtErstellen(fahrten, [dienst, privat], { kmVorher: 5_000, kmStandAb: ms(10, 0) });
    const zeilen = b.bloecke.find((x) => x.kategorieId === 1).zeilen;
    assert.equal(zeilen[0].kmStandBeginn, null);
    assert.equal(zeilen[1].kmStandBeginn, 5_000);
    assert.equal(zeilen[1].kmStandEnde, 5_007.7);
  });

  test("Kilometerstand zu Beginn eines Zeitraums", () => {
    const e = { kmStandStart: 10_000, kmStandAb: ms(1, 0) };
    assert.equal(B.kmVorherBerechnen(fahrten, e, ms(15, 0)), 10_020);
    assert.equal(B.kmVorherBerechnen(fahrten, e, ms(1, 0)), 10_000);
    assert.equal(B.kmVorherBerechnen(fahrten, { kmStandStart: 0 }, ms(15, 0)), null);
  });

  test("CSV für Excel wie in der Android-App", () => {
    const b = B.berichtErstellen(fahrten, [dienst, privat], { kmVorher: 10_000 });
    const csv = B.csvErstellen(b, september, { kennzeichen: "W-12345A", fahrer: "Max Muster" });
    assert.ok(csv.startsWith("﻿Fahrtenbuch"));
    assert.ok(csv.includes("Zeitraum;September 2026 (01.09.2026 – 30.09.2026)"));
    assert.ok(csv.includes("Kennzeichen;W-12345A"));
    assert.ok(csv.includes("Kategorie;Datum;Abfahrt;Ankunft;Von;Über;Nach;km;Km-Stand Beginn;Km-Stand Ende;Zweck / Notiz"));
    assert.ok(csv.includes('Dienstlich;02.09.2026;08:00;09:00;Hauptstraße 1, 1010 Wien;;Ziel 1;12,3;10.000,0;10.012,3;"Kunde A; Termin"'));
    assert.ok(csv.includes("Gesamt;4;51,0;100,0"));
  });

  test("CSV mit Zwischenzielen", () => {
    const start = new Date(2026, 8, 28, 8).getTime();
    const f = {
      ...fahrt(9, 28, 84_000, dienst),
      startZeit: start,
      endeZeit: start + 4 * 3600_000,
      startAdresse: "Zuhause",
      endeAdresse: "Zuhause",
      zwischenziele: O.zwischenzieleSchreiben([{ adresse: "Kunde A", an: start + 3600_000, ab: start + 3 * 3600_000 }, { adresse: "Kunde B" }]),
    };
    const csv = B.csvErstellen(B.berichtErstellen([f], [dienst]), september, {});
    assert.ok(csv.includes("Dienstlich;28.09.2026;08:00;12:00;Zuhause;Kunde A / Kunde B;Zuhause;84,0;"), csv);
  });

  test("Kilometer-Eingabe und Rundung", () => {
    assert.equal(F.kmParsen("12,5"), 12.5);
    assert.equal(F.kmParsen("1.234,5"), 1234.5);
    assert.equal(F.kmParsen("12.5"), 12.5);
    assert.equal(F.kmParsen("abc"), null);
    assert.equal(F.kmParsen("-3"), null);
    assert.equal(F.kmParsen("0x10"), null);
    assert.equal(F.kmWert(12_340), 12.3);
    assert.equal(F.kmWert(12_350), 12.4);
    assert.equal(F.km(12_340), "12,3 km");
    assert.equal(F.kmEingabe(1_234_500), "1234,5");
    assert.equal(F.dauer(125 * 60_000), "2 h 05 min");
  });
});

describe("Verschlüsselung (Prüfvektor wie App und Verwaltungsseite)", () => {
  const pin = "48151623";
  const folge = (n, f) => Uint8Array.from({ length: n }, (_, i) => f(i) & 255);
  const salt = folge(16, (i) => i + 1);
  const ivHuelle = folge(12, (i) => 100 + i);
  const dek = folge(32, (i) => 200 - i);
  const ivDaten = folge(12, (i) => 50 + i);
  const klartext =
    '{"startAdresse":"Zuhause, Hauptstraße 1, 8720 Knittelfeld","endeAdresse":"Murpark, 8010 Graz",' +
    '"zwischenziele":"","notiz":"Einkauf","startLat":47.2,"startLon":14.8,"endeLat":47.1,"endeLon":15.4}';

  test("Hülle wie in App und Browser", async () => {
    const h = await K.huelleErstellen(pin, dek, salt, ivHuelle, 310_000);
    assert.equal(h.salt, "AQIDBAUGBwgJCgsMDQ4PEA==");
    assert.equal(h.iv, "ZGVmZ2hpamtsbW5v");
    assert.equal(h.ct, "WEM8D4tb13Z/J45k3KQOfMNY00I86zSe7oyPy/WnyJ9eiQ7FhWccsUHJ9/odcuno");
    assert.deepEqual(await K.huelleOeffnen(h, pin), dek);
  });

  test("Details wie in App und Browser", async () => {
    const g = await K.geheimErstellen(dek, klartext, ivDaten);
    assert.equal(
      g.ct,
      "fa5eQmMNq0RuaIhlcL2e/JTKxJ6qjjyvLk4Hvt5eMuJHDejDSmlj67ClI07H8mUyB9VAwExI7IQazf/AjUF/0szCeku6+Ybx4DTejC+tXhE43P2x" +
        "pbKP77M/Px8LC1lJ5/oc7tYQn/qtNxRsX1FR12dEEqpMgOdghLscOblKf6l/0aIF3RL6WHw+PgnPEfCDPBGN4xz7z+7hIHnPu+JX/4eaoWeXTcgb3zvI" +
        "tTSGs5ByU5dGxOdR7LZRte23uxDRun9nwOCANisbMuX63MnWuiLP",
    );
    assert.equal(await K.geheimOeffnen(dek, g), klartext);
  });

  test("erkennt einen falschen PIN", async () => {
    const h = await K.huelleErstellen(pin, dek, salt, ivHuelle, 310_000);
    await assert.rejects(() => K.huelleOeffnen(h, "48151624"));
  });

  test("zufällige IVs sind jedes Mal anders", async () => {
    const a = await K.geheimErstellen(dek, klartext);
    const b = await K.geheimErstellen(dek, klartext);
    assert.notEqual(a.iv, b.iv);
    assert.equal(await K.geheimOeffnen(dek, b), klartext);
  });
});

describe("Sync-Format", () => {
  const schluessel = Uint8Array.from({ length: 32 }, (_, i) => i);
  const fahrtEintrag = (id, uuid, kategorie, status = "fertig", aktion = "neu") => ({
    eintragId: id,
    fahrtUuid: uuid,
    aktion,
    zeit: 1,
    daten: S.fahrtDaten({
      uuid,
      startZeit: 1,
      startAdresse: "Zuhause",
      endeAdresse: "Therme",
      notiz: "Wellness",
      startLat: 47.2,
      startLon: 14.8,
      distanzMeter: 50_000,
      kategorieId: kategorie,
      status,
    }),
  });

  test("Privatfahrten gehen nur verschlüsselt hinaus", async () => {
    const v = await S.vorbereiten([fahrtEintrag("e-1", "f-dienst", 1), fahrtEintrag("e-2", "f-privat", 2)], new Set([2]), schluessel);
    assert.equal(v.senden.length, 2);
    assert.equal(v.wartenAufPin, 0);
    assert.equal(v.senden[0].daten.startAdresse, "Zuhause");
    assert.equal(v.senden[0].daten.geheim, undefined);
    const privat = v.senden[1].daten;
    assert.equal(privat.startAdresse, "");
    assert.equal(privat.notiz, "");
    assert.equal(privat.startLat, null);
    assert.equal(privat.distanzMeter, 50_000); // Kilometer bleiben sichtbar
    const klar = JSON.parse(await K.geheimOeffnen(schluessel, privat.geheim));
    assert.equal(klar.endeAdresse, "Therme");
    assert.equal(klar.notiz, "Wellness");
    assert.equal(klar.startLon, 14.8);
    assert.equal(klar.endeLat, null);
    assert.ok(!JSON.stringify(privat).includes("Therme"));
  });

  test("verschlüsselt dieselben Felder in derselben Reihenfolge wie die Android-App", async () => {
    const d = S.fahrtDaten({
      startZeit: 1,
      startAdresse: "Zuhause, Hauptstraße 1, 8720 Knittelfeld",
      endeAdresse: "Murpark, 8010 Graz",
      notiz: "Einkauf",
      startLat: 47.2,
      startLon: 14.8,
      endeLat: 47.1,
      endeLon: 15.4,
      distanzMeter: 1,
      kategorieId: 2,
      status: "fertig",
    });
    const v = await S.verschluesseln(d, schluessel);
    assert.equal(
      await K.geheimOeffnen(schluessel, v.geheim),
      '{"startAdresse":"Zuhause, Hauptstraße 1, 8720 Knittelfeld","endeAdresse":"Murpark, 8010 Graz",' +
        '"zwischenziele":"","notiz":"Einkauf","startLat":47.2,"startLon":14.8,"endeLat":47.1,"endeLon":15.4}',
    );
  });

  test("ohne PIN bleiben Privatfahrten samt Folgeeinträgen am Gerät", async () => {
    const v = await S.vorbereiten(
      [fahrtEintrag("e-1", "f-privat", 2), fahrtEintrag("e-2", "f-dienst", 1), fahrtEintrag("e-3", "f-privat", 1, "fertig", "geaendert")],
      new Set([2]),
      null,
    );
    assert.deepEqual(v.senden.map((e) => e.eintragId), ["e-2"]);
    assert.equal(v.wartenAufPin, 2);
  });

  test("nicht zugeordnete Einträge entfallen", async () => {
    const v = await S.vorbereiten([fahrtEintrag("e-1", "f-1", null, "offen"), fahrtEintrag("e-2", "f-1", 1)], new Set([2]), null);
    assert.deepEqual(v.entfallen, ["e-1"]);
    assert.deepEqual(v.senden.map((e) => e.eintragId), ["e-2"]);
  });

  test("Anfrage enthält alles für den Server", () => {
    const e = fahrtEintrag("e-1234567890", "0f0e0d0c-0000-4000-8000-000000000001", 2, "offen");
    const a = S.anfrage({
      version: "0.5.0",
      geraet: "iPhone · iOS 18.5 · Web-App",
      einstellungen: { kennzeichen: "MT 317 AS", kmStandStart: 45_200, kmStandAb: 99 },
      kategorien: [{ id: 2, name: "Privat", farbe: 0xff43a047, sortierung: 2, aktiv: false, privat: true }],
      eintraege: [e],
    });
    assert.equal(a.app.version, "0.5.0");
    assert.equal(a.einstellungen.kennzeichen, "MT 317 AS");
    assert.equal(a.einstellungen.kmStandStart, 45_200);
    assert.equal(a.einstellungen.fahrer, "");
    assert.equal(a.kategorien[0].aktiv, false);
    assert.equal(a.eintraege[0].uuid, e.fahrtUuid);
    assert.equal(a.eintraege[0].daten.endeZeit, null);
    assert.equal(a.schluessel, undefined);
    assert.deepEqual(Object.keys(a.eintraege[0].daten), [
      "startZeit", "endeZeit", "startAdresse", "endeAdresse", "zwischenziele", "startLat", "startLon",
      "endeLat", "endeLon", "distanzMeter", "kategorieId", "notiz", "status",
    ]);
  });

  describe("Verbindungs-Link", () => {
    const code = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcd";
    const origin = "https://fahrtenbuch.smarte.events";
    test("liest QR-Link, Rückfall-Link und nackten Code", () => {
      assert.deepEqual(S.verbindungLesen(`${origin}/verbinden#code=${code}`, origin), { code });
      assert.deepEqual(S.verbindungLesen(`fahrtenbuch://verbinden?server=${encodeURIComponent(origin)}&code=${code}`, origin), { code });
      assert.deepEqual(S.verbindungLesen(`  ${code} `, origin), { code });
    });
    test("lehnt fremde, unsichere und unvollständige Links ab", () => {
      assert.ok(S.verbindungLesen(`https://boese.example.com/verbinden#code=${code}`, origin).fehler);
      assert.ok(S.verbindungLesen(`http://fahrtenbuch.smarte.events/verbinden#code=${code}`, origin).fehler);
      assert.ok(S.verbindungLesen(`fahrtenbuch://verbinden?server=https%3A%2F%2Fboese.example.com&code=${code}`, origin).fehler);
      assert.ok(S.verbindungLesen(`${origin}/anderes#code=${code}`, origin).fehler);
      assert.ok(S.verbindungLesen(`${origin}/verbinden#code=kurz`, origin).fehler);
      assert.ok(S.verbindungLesen("irgendwas", origin).fehler);
    });
  });

  test("beschreibt das Gerät", () => {
    const ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1";
    assert.equal(S.geraetBeschreiben(ua, true), "iPhone · iOS 18.5 · Web-App");
    assert.equal(S.geraetBeschreiben(ua, false), "iPhone · iOS 18.5 · Browser-Tab");
  });
});

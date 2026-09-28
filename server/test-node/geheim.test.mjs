// Prüft die Entschlüsselung im Browser gegen einen festen Testvektor.
// Läuft mit Node (node --test), weil die Workers-Laufzeit PBKDF2 auf 100.000 Runden begrenzt.
// Derselbe Vektor wird in der App (KryptoTest.kt) geprüft – so ist sicher, dass App und Browser zusammenpassen.
import assert from "node:assert/strict";
import { test } from "node:test";
import { entschluesseln, schluesselOeffnen } from "../public/admin/geheim.js";

export const VEKTOR = {
  pin: "48151623",
  huelle: { v: 1, iter: 310000, salt: "AQIDBAUGBwgJCgsMDQ4PEA==", iv: "ZGVmZ2hpamtsbW5v", ct: "WEM8D4tb13Z/J45k3KQOfMNY00I86zSe7oyPy/WnyJ9eiQ7FhWccsUHJ9/odcuno" },
  geheim: {
    v: 1,
    iv: "MjM0NTY3ODk6Ozw9",
    ct: "fa5eQmMNq0RuaIhlcL2e/JTKxJ6qjjyvLk4Hvt5eMuJHDejDSmlj67ClI07H8mUyB9VAwExI7IQazf/AjUF/0szCeku6+Ybx4DTejC+tXhE43P2xpbKP77M/Px8LC1lJ5/oc7tYQn/qtNxRsX1FR12dEEqpMgOdghLscOblKf6l/0aIF3RL6WHw+PgnPEfCDPBGN4xz7z+7hIHnPu+JX/4eaoWeXTcgb3zvItTSGs5ByU5dGxOdR7LZRte23uxDRun9nwOCANisbMuX63MnWuiLP",
  },
};

test("entschlüsselt eine Privatfahrt mit dem richtigen PIN", async () => {
  const s = await schluesselOeffnen(VEKTOR.huelle, VEKTOR.pin);
  const d = await entschluesseln(s, VEKTOR.geheim);
  assert.equal(d.startAdresse, "Zuhause, Hauptstraße 1, 8720 Knittelfeld");
  assert.equal(d.endeAdresse, "Murpark, 8010 Graz");
  assert.equal(d.notiz, "Einkauf");
  assert.equal(d.endeLon, 15.4);
});

test("lehnt einen falschen PIN ab", async () => {
  await assert.rejects(() => schluesselOeffnen(VEKTOR.huelle, "48151624"));
});

test("erkennt manipulierte Daten", async () => {
  const s = await schluesselOeffnen(VEKTOR.huelle, VEKTOR.pin);
  const kaputt = { ...VEKTOR.geheim, ct: "A" + VEKTOR.geheim.ct.slice(1) };
  await assert.rejects(() => entschluesseln(s, kaputt));
});

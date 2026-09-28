// Verschlüsselung der Privatfahrten – identisch zur Android-App (sync/Krypto.kt) und zur Verwaltungsseite:
// - Datenschlüssel: 32 zufällige Bytes je Fahrer, bleiben auf dem Gerät.
// - Hülle: Datenschlüssel mit AES-GCM verschlüsselt; Schlüssel dafür aus dem PIN per PBKDF2-SHA-256.
//   Nur die Hülle geht an den Server, der PIN nie.
// - Details einer Privatfahrt: AES-GCM mit dem Datenschlüssel.

export const RUNDEN = 310_000;
export const MIN_PIN = 6;

export function b64(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

export const ausB64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
export const zufall = (n) => crypto.getRandomValues(new Uint8Array(n));

async function huellenSchluessel(pin, salt, runden, zweck) {
  const basis = await crypto.subtle.importKey("raw", new TextEncoder().encode(pin), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey({ name: "PBKDF2", salt, iterations: runden, hash: "SHA-256" }, basis, { name: "AES-GCM", length: 256 }, false, [zweck]);
}

const aesSchluessel = (roh, zweck) => crypto.subtle.importKey("raw", roh, "AES-GCM", false, [zweck]);

/** Verpackt den Datenschlüssel mit dem PIN: {v, salt, iter, iv, ct} (base64). */
export async function huelleErstellen(pin, datenSchluessel, salt = zufall(16), iv = zufall(12), runden = RUNDEN) {
  const kek = await huellenSchluessel(pin, salt, runden, "encrypt");
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, kek, datenSchluessel));
  return { v: 1, salt: b64(salt), iter: runden, iv: b64(iv), ct: b64(ct) };
}

/** Öffnet die Hülle; wirft bei falschem PIN. @returns {Promise<Uint8Array>} Datenschlüssel */
export async function huelleOeffnen(huelle, pin) {
  const kek = await huellenSchluessel(pin, ausB64(huelle.salt), huelle.iter, "decrypt");
  return new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: ausB64(huelle.iv) }, kek, ausB64(huelle.ct)));
}

/** Verschlüsselt klartext (JSON der Fahrtdetails) als {v, iv, ct}. */
export async function geheimErstellen(datenSchluessel, klartext, iv = zufall(12)) {
  const k = await aesSchluessel(datenSchluessel, "encrypt");
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, k, new TextEncoder().encode(klartext)));
  return { v: 1, iv: b64(iv), ct: b64(ct) };
}

/** @returns {Promise<string>} Klartext; wirft bei falschem Schlüssel oder veränderten Daten */
export async function geheimOeffnen(datenSchluessel, geheim) {
  const k = await aesSchluessel(datenSchluessel, "decrypt");
  return new TextDecoder().decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv: ausB64(geheim.iv) }, k, ausB64(geheim.ct)));
}

// Privatfahrten: Entschlüsselung im Browser. Der PIN verlässt den Browser nie.
// Verfahren wie in der App: PBKDF2-SHA-256 (PIN → Hüllenschlüssel), AES-GCM (Datenschlüssel, Fahrtdetails).

const ausB64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

/**
 * Öffnet den Datenschlüssel eines Fahrers mit seinem PIN.
 * @param {{v:number,salt:string,iter:number,iv:string,ct:string}} huelle aus fahrer.schluessel
 * @returns {Promise<CryptoKey>} wirft bei falschem PIN
 */
export async function schluesselOeffnen(huelle, pin) {
  const basis = await crypto.subtle.importKey("raw", new TextEncoder().encode(pin), "PBKDF2", false, ["deriveKey"]);
  const kek = await crypto.subtle.deriveKey(
    { name: "PBKDF2", salt: ausB64(huelle.salt), iterations: huelle.iter, hash: "SHA-256" },
    basis,
    { name: "AES-GCM", length: 256 },
    false,
    ["decrypt"],
  );
  const dek = await crypto.subtle.decrypt({ name: "AES-GCM", iv: ausB64(huelle.iv) }, kek, ausB64(huelle.ct));
  return crypto.subtle.importKey("raw", dek, "AES-GCM", false, ["decrypt"]);
}

/**
 * Entschlüsselt die Details einer Privatfahrt.
 * @param {CryptoKey} schluessel
 * @param {{v:number,iv:string,ct:string}} geheim
 * @returns {Promise<{startAdresse:string,endeAdresse:string,zwischenziele:string,notiz:string,startLat:number|null,startLon:number|null,endeLat:number|null,endeLon:number|null}>}
 */
export async function entschluesseln(schluessel, geheim) {
  const klar = await crypto.subtle.decrypt({ name: "AES-GCM", iv: ausB64(geheim.iv) }, schluessel, ausB64(geheim.ct));
  return JSON.parse(new TextDecoder().decode(klar));
}

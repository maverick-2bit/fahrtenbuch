// Service Worker der Web-App: Die App öffnet auch ohne Netz (Tiefgarage, Funkloch).
// Immer zuerst aus dem Netz – so kommen Updates sofort an; nur ohne Netz aus dem Zwischenspeicher.
// Die Schnittstelle (/api/) läuft nie über den Service Worker.
const SPEICHER = "fahrtenbuch-app-0.7.2";
// Ohne „index.html“: Cloudflare leitet es auf „./“ um, und umgeleitete Antworten darf ein Service
// Worker nicht für Seitenaufrufe verwenden
const DATEIEN = [
  "./",
  "stil.css",
  "app.js",
  "aufzeichnung.js",
  "bericht.js",
  "daten.js",
  "format.js",
  "krypto.js",
  "orte.js",
  "sicherung.js",
  "strecke.js",
  "syncformat.js",
  "manifest.webmanifest",
  "icon-180.png",
  "icon-192.png",
  "/icon.svg",
];

self.addEventListener("install", (e) => {
  e.waitUntil(
    caches
      .open(SPEICHER)
      .then((c) => c.addAll(DATEIEN))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches
      .keys()
      .then((alle) => Promise.all(alle.filter((k) => k.startsWith("fahrtenbuch-app-") && k !== SPEICHER).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

/** Netz mit Zeitlimit: Ein hängendes Netz (ein Balken Empfang) soll den Start nicht blockieren. */
function ausDemNetz(req, ms) {
  return new Promise((ok, fehler) => {
    const uhr = setTimeout(() => fehler(new Error("Zeitüberschreitung")), ms);
    fetch(req).then(
      (r) => {
        clearTimeout(uhr);
        ok(r);
      },
      (e) => {
        clearTimeout(uhr);
        fehler(e);
      },
    );
  });
}

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin || url.pathname.startsWith("/api/")) return;
  if (!url.pathname.startsWith("/app/") && url.pathname !== "/icon.svg") return;
  e.respondWith(
    (async () => {
      const c = await caches.open(SPEICHER);
      try {
        const r = await ausDemNetz(e.request, 4_000);
        if (r.ok) c.put(e.request, r.clone());
        return r;
      } catch {
        const alt = (await c.match(e.request, { ignoreSearch: true })) ?? (e.request.mode === "navigate" ? await c.match("./") : undefined);
        return alt ?? Response.error();
      }
    })(),
  );
});

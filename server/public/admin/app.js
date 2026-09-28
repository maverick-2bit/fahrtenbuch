// Fahrtenbuch Verwaltung – Admin-Oberfläche ohne Build-Schritt.
// Texte aus Fahrtdaten werden ausschließlich als Text (textContent) eingesetzt, nie als HTML.

const $inhalt = document.getElementById("inhalt");
const $nav = document.getElementById("nav");
const $dialog = document.getElementById("dialog");

// ------------------------------------------------------------------ Hilfen

function el(tag, attrs = {}, ...kinder) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs ?? {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === "class") e.className = v;
    else if (k.startsWith("on") && typeof v === "function") e.addEventListener(k.slice(2), v);
    else if (k === "farbe") e.style.background = v;
    else e.setAttribute(k, v === true ? "" : String(v));
  }
  for (const kind of kinder.flat(Infinity)) {
    if (kind === null || kind === undefined || kind === false) continue;
    e.append(kind instanceof Node ? kind : document.createTextNode(String(kind)));
  }
  return e;
}

function zeige(...knoten) {
  $inhalt.replaceChildren(...knoten.flat(Infinity).filter((k) => k !== null && k !== undefined && k !== false));
}

async function api(pfad, { methode = "GET", daten } = {}) {
  const init = { method: methode, headers: {}, credentials: "same-origin" };
  if (methode !== "GET") init.headers["x-fahrtenbuch"] = "1";
  if (daten !== undefined) {
    init.headers["content-type"] = "application/json";
    init.body = JSON.stringify(daten);
  }
  const r = await fetch(pfad, init);
  const d = await r.json().catch(() => ({}));
  if (r.status === 401 && !pfad.endsWith("/anmelden")) {
    location.hash = "#/anmelden";
    throw new Error("Nicht angemeldet");
  }
  if (!r.ok) throw new Error(d.fehler || `Fehler ${r.status}`);
  return d;
}

// Zahlen mit Punkt als Tausendertrennzeichen wie in der App – so liest Excel auch die CSV korrekt
const zahl1 = new Intl.NumberFormat("de-DE", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const zahl0 = new Intl.NumberFormat("de-DE");
const datumF = new Intl.DateTimeFormat("de-AT", { day: "2-digit", month: "2-digit", year: "numeric" });
const zeitF = new Intl.DateTimeFormat("de-AT", { hour: "2-digit", minute: "2-digit" });
const monatF = new Intl.DateTimeFormat("de-AT", { month: "long", year: "numeric" });

/** Kilometer wie in der App: auf 0,1 km gerundet. */
const kmWert = (meter) => Math.round(meter / 100) / 10;
const km = (wert) => zahl1.format(wert);
const datum = (ms) => datumF.format(new Date(ms));
const zeit = (ms) => zeitF.format(new Date(ms));
const farbe = (f) => (f === null || f === undefined ? "#9e9e9e" : "#" + (Number(f) & 0xffffff).toString(16).padStart(6, "0"));
const gross = (s) => s.charAt(0).toUpperCase() + s.slice(1);

function relativ(ms) {
  if (!ms) return "noch nie";
  const min = Math.round((Date.now() - ms) / 60_000);
  if (min < 1) return "gerade eben";
  if (min < 60) return `vor ${min} Min.`;
  const std = Math.round(min / 60);
  if (std < 24) return `vor ${std} Std.`;
  return `am ${datum(ms)} um ${zeit(ms)}`;
}

function zwischenziele(json) {
  if (!json) return [];
  try {
    const a = JSON.parse(json);
    return Array.isArray(a) ? a : [];
  } catch {
    return [];
  }
}

function strecke(f) {
  return [f.start_adresse || "?", ...zwischenziele(f.zwischenziele).map((z) => z.adresse || "?"), f.ende_adresse || "?"].join(" → ");
}

function fehlerAnzeigen(e) {
  zeige(el("p", { class: "fehler" }, e.message || String(e)));
}

// ------------------------------------------------------------------ Dialog

function dialog(...knoten) {
  $dialog.replaceChildren(...knoten.flat(Infinity).filter((k) => k !== null && k !== undefined && k !== false));
  if (!$dialog.open) $dialog.showModal();
}

function dialogZu() {
  $dialog.close();
}

$dialog.addEventListener("click", (e) => {
  if (e.target === $dialog) dialogZu();
});

// ------------------------------------------------------------------ Navigation

function navigation(angemeldet) {
  $nav.replaceChildren(
    ...(angemeldet
      ? [
          el("a", { class: "knopf", href: "#/" }, "Fahrer"),
          el("button", {
            class: "knopf",
            onclick: async () => {
              await api("/api/admin/abmelden", { methode: "POST", daten: {} }).catch(() => {});
              location.hash = "#/anmelden";
            },
          }, "Abmelden"),
        ]
      : []),
  );
}

async function route() {
  const h = location.hash || "#/";
  try {
    if (h === "#/anmelden") {
      navigation(false);
      return anmeldung();
    }
    navigation(true);
    const m = /^#\/fahrer\/([^/?]+)/.exec(h);
    if (m) return await fahrtenbuch(decodeURIComponent(m[1]));
    return await uebersicht();
  } catch (e) {
    if (e.message !== "Nicht angemeldet") fehlerAnzeigen(e);
  }
}

window.addEventListener("hashchange", route);
// Erster Aufruf ganz am Ende der Datei, damit alle Konstanten schon initialisiert sind

// ------------------------------------------------------------------ Anmeldung

function anmeldung() {
  const passwort = el("input", { type: "password", name: "password", autocomplete: "current-password", required: true, placeholder: "Passwort" });
  const meldung = el("p", { class: "fehler" });
  const form = el(
    "form",
    {
      onsubmit: async (e) => {
        e.preventDefault();
        meldung.textContent = "";
        try {
          await api("/api/admin/anmelden", { methode: "POST", daten: { passwort: passwort.value } });
          location.hash = "#/";
        } catch (err) {
          meldung.textContent = err.message;
        }
      },
    },
    el("input", { type: "text", name: "username", autocomplete: "username", value: "admin", hidden: true }),
    passwort,
    el("button", { class: "knopf haupt", type: "submit" }, "Anmelden"),
    meldung,
  );
  zeige(el("div", { class: "anmeldung karte" }, el("h1", {}, "Anmelden"), el("p", { class: "unter" }, "Fahrtenbuch Verwaltung"), form));
  passwort.focus();
}

// ------------------------------------------------------------------ Übersicht

async function uebersicht() {
  const { fahrer } = await api("/api/admin/fahrer");
  zeige(
    el("h1", {}, "Fahrer"),
    el("p", { class: "unter" }, "Jeder Fahrer verbindet sein Handy einmal über seinen QR-Code. Danach sichert die App jede Fahrt automatisch."),
    el("p", {}, el("button", { class: "knopf haupt", onclick: fahrerAnlegenDialog }, "+ Fahrer anlegen")),
    fahrer.length ? el("div", { class: "karten" }, fahrer.map(fahrerKarte)) : el("p", { class: "leer" }, "Noch keine Fahrer angelegt."),
  );
}

function fahrerKarte(f) {
  const fahrzeug = [f.fahrzeug, f.kennzeichen].filter(Boolean).join(" · ");
  return el(
    "div",
    { class: "karte" },
    el(
      "h3",
      {},
      f.name,
      " ",
      !f.aktiv ? el("span", { class: "abzeichen rot" }, "gesperrt") : null,
      f.offene > 0 ? el("span", { class: "abzeichen gelb" }, `${f.offene} nicht zugeordnet`) : null,
    ),
    el(
      "div",
      { class: "zeilen" },
      fahrzeug ? el("div", {}, fahrzeug) : null,
      el("div", {}, `${zahl0.format(f.anzahl_fahrten)} Fahrten`, f.letzte_fahrt ? `, letzte am ${datum(f.letzte_fahrt)}` : ""),
      el("div", {}, `Letzte Sicherung: ${relativ(f.zuletzt_sync)}`),
      f.geraet ? el("div", { class: "klein" }, f.geraet) : !f.zuletzt_sync ? el("div", { class: "klein" }, "Handy noch nicht verbunden") : null,
    ),
    el(
      "div",
      { class: "aktionen" },
      el("a", { class: "knopf haupt", href: `#/fahrer/${encodeURIComponent(f.id)}` }, "Fahrtenbuch"),
      el("button", { class: "knopf", onclick: () => neuerCodeDialog(f) }, "Neuer Code"),
      el("button", { class: "knopf", onclick: () => umbenennenDialog(f) }, "Umbenennen"),
      el(
        "button",
        {
          class: "knopf" + (f.aktiv ? " warnung" : ""),
          onclick: async () => {
            if (f.aktiv && !confirm(`${f.name} sperren? Das Handy kann dann keine Fahrten mehr sichern.`)) return;
            await api(`/api/admin/fahrer/${encodeURIComponent(f.id)}`, { methode: "POST", daten: { aktiv: !f.aktiv } });
            route();
          },
        },
        f.aktiv ? "Sperren" : "Entsperren",
      ),
    ),
  );
}

function fahrerAnlegenDialog() {
  const name = el("input", { type: "text", required: true, placeholder: "Name, z. B. Thomas", maxlength: 100 });
  const meldung = el("p", { class: "fehler" });
  dialog(
    el("h2", {}, "Fahrer anlegen"),
    el(
      "form",
      {
        onsubmit: async (e) => {
          e.preventDefault();
          try {
            const d = await api("/api/admin/fahrer", { methode: "POST", daten: { name: name.value } });
            codeAnzeigen(d.fahrer.name, d.link);
            route();
          } catch (err) {
            meldung.textContent = err.message;
          }
        },
      },
      name,
      meldung,
      el("div", { class: "aktionen" }, el("button", { class: "knopf", type: "button", onclick: dialogZu }, "Abbrechen"), el("button", { class: "knopf haupt", type: "submit" }, "Anlegen")),
    ),
  );
  name.focus();
}

async function neuerCodeDialog(f) {
  if (!confirm(`Neuen Code für ${f.name} erzeugen? Der bisherige Code wird sofort ungültig – das Handy muss dann neu verbunden werden.`)) return;
  const d = await api(`/api/admin/fahrer/${encodeURIComponent(f.id)}/code`, { methode: "POST", daten: {} });
  codeAnzeigen(f.name, d.link);
}

function umbenennenDialog(f) {
  const name = el("input", { type: "text", value: f.name, required: true, maxlength: 100 });
  dialog(
    el("h2", {}, "Fahrer umbenennen"),
    el(
      "form",
      {
        onsubmit: async (e) => {
          e.preventDefault();
          await api(`/api/admin/fahrer/${encodeURIComponent(f.id)}`, { methode: "POST", daten: { name: name.value } });
          dialogZu();
          route();
        },
      },
      name,
      el("div", { class: "aktionen" }, el("button", { class: "knopf", type: "button", onclick: dialogZu }, "Abbrechen"), el("button", { class: "knopf haupt", type: "submit" }, "Speichern")),
    ),
  );
}

function codeAnzeigen(name, link) {
  let bild = null;
  if (typeof window.qrcode === "function") {
    const qr = window.qrcode(0, "M");
    qr.addData(link);
    qr.make();
    bild = el("img", { src: qr.createDataURL(5, 2), alt: "QR-Code zum Verbinden", width: qr.getModuleCount() * 5 + 20 });
  }
  const kopieren = el(
    "button",
    {
      class: "knopf",
      onclick: async () => {
        await navigator.clipboard.writeText(link);
        kopieren.textContent = "Kopiert";
      },
    },
    "Link kopieren",
  );
  dialog(
    el("h2", {}, `Handy von ${name} verbinden`),
    el(
      "div",
      { class: "qr" },
      bild,
      el(
        "ol",
        {},
        el("li", {}, "Am Handy die Kamera öffnen und den QR-Code scannen."),
        el("li", {}, "Ist die Fahrtenbuch-App installiert, verbindet sie sich direkt. Sonst zeigt die Seite, wie die App installiert wird."),
        el("li", {}, "Ab dann sichert die App jede Fahrt automatisch."),
      ),
    ),
    el("p", { class: "klein" }, "Der Link gilt, bis ein neuer Code erzeugt wird. Er wird nur jetzt angezeigt – bei Verlust einfach einen neuen erzeugen."),
    el("div", { class: "link" }, link),
    el("div", { class: "aktionen" }, kopieren, el("button", { class: "knopf haupt", onclick: dialogZu }, "Fertig")),
  );
}

// ------------------------------------------------------------------ Fahrtenbuch und Berichte

const heute = new Date();
const ansicht = {
  fahrer: null,
  art: "monat",
  monat: new Date(heute.getFullYear(), heute.getMonth(), 1),
  jahr: heute.getFullYear(),
  von: new Date(heute.getFullYear(), heute.getMonth(), 1),
  bis: new Date(heute.getFullYear(), heute.getMonth(), heute.getDate()),
  filter: null, // Set der Kategorie-Schlüssel oder null = alle
};

function zeitraum() {
  if (ansicht.art === "monat") {
    const v = ansicht.monat;
    return { von: v.getTime(), bis: new Date(v.getFullYear(), v.getMonth() + 1, 1).getTime(), titel: gross(monatF.format(v)) };
  }
  if (ansicht.art === "jahr") {
    return { von: new Date(ansicht.jahr, 0, 1).getTime(), bis: new Date(ansicht.jahr + 1, 0, 1).getTime(), titel: `Jahr ${ansicht.jahr}` };
  }
  let v = ansicht.von;
  let b = ansicht.bis;
  if (b < v) [v, b] = [b, v];
  return { von: v.getTime(), bis: new Date(b.getFullYear(), b.getMonth(), b.getDate() + 1).getTime(), titel: `${datum(v)} – ${datum(b)}` };
}

function blaettern(richtung) {
  if (ansicht.art === "monat") ansicht.monat = new Date(ansicht.monat.getFullYear(), ansicht.monat.getMonth() + richtung, 1);
  else if (ansicht.art === "jahr") ansicht.jahr += richtung;
  route();
}

/** Bericht wie in der App: nur abgeschlossene Fahrten, Kilometerstand über alle Kategorien fortgeschrieben. */
export function berichtErstellen(d, filter) {
  const kats = new Map(d.kategorien.map((k) => [k.id, k]));
  const fertig = d.fahrten.filter((f) => f.status === "fertig");
  const offen = d.fahrten.filter((f) => f.status !== "fertig");
  const ab = Number(d.fahrer.km_stand_ab || 0);
  let stand = d.kmVorher;
  const zeilen = fertig.map((f) => {
    const wert = kmWert(f.distanz_meter);
    let beginn = null;
    let ende = null;
    if (stand !== null && f.start_zeit >= ab) {
      beginn = stand;
      ende = Math.round((stand + wert) * 10) / 10;
      stand = ende;
    }
    return { f, km: wert, beginn, ende };
  });

  const bloecke = new Map();
  for (const z of zeilen) {
    const id = z.f.kategorie_id;
    const schluessel = id === null || id === undefined ? "ohne" : String(id);
    if (!bloecke.has(schluessel)) {
      const k = kats.get(id);
      bloecke.set(schluessel, {
        schluessel,
        name: k?.name || z.f.kategorie_name || "Ohne Kategorie",
        farbe: farbe(k?.farbe),
        sortierung: schluessel === "ohne" ? Number.MAX_SAFE_INTEGER : (k?.sortierung ?? 999),
        zeilen: [],
      });
    }
    bloecke.get(schluessel).zeilen.push(z);
  }
  const alle = [...bloecke.values()]
    .map((b) => ({ ...b, km: Math.round(b.zeilen.reduce((s, z) => s + z.km, 0) * 10) / 10, anzahl: b.zeilen.length }))
    .sort((a, b) => a.sortierung - b.sortierung || a.name.localeCompare(b.name, "de"));
  const gezeigt = filter ? alle.filter((b) => filter.has(b.schluessel)) : alle;
  const gesamtKm = Math.round(gezeigt.reduce((s, b) => s + b.km, 0) * 10) / 10;
  return {
    alle,
    bloecke: gezeigt.map((b) => ({ ...b, anteil: gesamtKm > 0 ? (b.km / gesamtKm) * 100 : 0 })),
    offen,
    gesamtKm,
    gesamtAnzahl: gezeigt.reduce((s, b) => s + b.anzahl, 0),
    mitKmStand: d.kmVorher !== null,
  };
}

async function fahrtenbuch(id) {
  if (ansicht.fahrer !== id) {
    ansicht.fahrer = id;
    ansicht.filter = null;
  }
  const zr = zeitraum();
  const d = await api(`/api/admin/fahrten?fahrer=${encodeURIComponent(id)}&von=${zr.von}&bis=${zr.bis}`);
  const b = berichtErstellen(d, ansicht.filter);
  const f = d.fahrer;
  const fahrzeug = [f.fahrzeug, f.kennzeichen].filter(Boolean).join(" · ");
  const kats = new Map(d.kategorien.map((k) => [k.id, k]));

  zeige(
    el(
      "div",
      { class: "druckkopf" },
      el("h1", {}, "Fahrtenbuch"),
      el("div", {}, `Fahrer: ${f.fahrer_name || f.name}`, fahrzeug ? ` · Fahrzeug: ${fahrzeug}` : "", ` · Zeitraum: ${zr.titel}`),
    ),
    el("h1", { class: "nicht-drucken" }, f.name),
    el("p", { class: "unter nicht-drucken" }, [fahrzeug, `Letzte Sicherung: ${relativ(f.zuletzt_sync)}`].filter(Boolean).join(" · ")),
    zeitraumLeiste(zr, () => csvHerunterladen(f, zr, b)),
    kategorieChips(b.alle),
    b.offen.length
      ? el(
          "div",
          { class: "hinweis nicht-drucken" },
          b.offen.length === 1
            ? "1 Fahrt ist in der App noch keiner Kategorie zugeordnet und fehlt in den Summen: "
            : `${b.offen.length} Fahrten sind in der App noch keiner Kategorie zugeordnet und fehlen in den Summen: `,
          b.offen.map((o) => `${datum(o.start_zeit)} ${zeit(o.start_zeit)} (${km(kmWert(o.distanz_meter))} km)`).join(", "),
        )
      : null,
    b.gesamtAnzahl === 0 ? el("p", { class: "leer" }, "Keine abgeschlossenen Fahrten im gewählten Zeitraum.") : [zusammenfassung(b, zr), b.bloecke.map((bl) => block(bl, b.mitKmStand, kats))],
  );
}

function zeitraumLeiste(zr, csv) {
  const segment = el(
    "div",
    { class: "segment", role: "group", "aria-label": "Zeitraum" },
    [
      ["monat", "Monat"],
      ["jahr", "Jahr"],
      ["zeitraum", "Zeitraum"],
    ].map(([art, titel]) =>
      el("button", { "aria-pressed": String(ansicht.art === art), onclick: () => ((ansicht.art = art), route()) }, titel),
    ),
  );
  const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  const ausIso = (s) => {
    const [y, m, t] = s.split("-").map(Number);
    return new Date(y, m - 1, t);
  };
  const wahl =
    ansicht.art === "zeitraum"
      ? el(
          "div",
          { class: "blaettern" },
          el("input", { type: "date", value: iso(ansicht.von), onchange: (e) => e.target.value && ((ansicht.von = ausIso(e.target.value)), route()) }),
          "bis",
          el("input", { type: "date", value: iso(ansicht.bis), onchange: (e) => e.target.value && ((ansicht.bis = ausIso(e.target.value)), route()) }),
        )
      : el(
          "div",
          { class: "blaettern" },
          el("button", { class: "knopf", onclick: () => blaettern(-1), "aria-label": "Zurück" }, "‹"),
          el("strong", {}, zr.titel),
          el("button", { class: "knopf", onclick: () => blaettern(1), "aria-label": "Weiter" }, "›"),
        );
  return el(
    "div",
    { class: "leiste" },
    segment,
    wahl,
    el("div", { class: "fuell" }),
    el("button", { class: "knopf", onclick: () => window.print() }, "Drucken / PDF"),
    el("button", { class: "knopf", onclick: csv }, "CSV für Excel"),
  );
}

function kategorieChips(alle) {
  if (alle.length < 2) return null;
  const schluessel = alle.map((b) => b.schluessel);
  return el(
    "div",
    { class: "chips", style: null },
    alle.map((b) =>
      el(
        "button",
        {
          class: "chip",
          "aria-pressed": String(!ansicht.filter || ansicht.filter.has(b.schluessel)),
          onclick: () => {
            const aktuell = ansicht.filter ?? new Set(schluessel);
            if (aktuell.has(b.schluessel)) aktuell.delete(b.schluessel);
            else aktuell.add(b.schluessel);
            ansicht.filter = aktuell.size === schluessel.length ? null : aktuell;
            route();
          },
        },
        el("span", { class: "punkt", farbe: b.farbe }),
        b.name,
      ),
    ),
  );
}

function zusammenfassung(b, zr) {
  return [
    el("h2", {}, `Zusammenfassung · ${zr.titel}`),
    el(
      "div",
      { class: "tabelle" },
      el(
        "table",
        {},
        el("thead", {}, el("tr", {}, el("th", {}, "Kategorie"), el("th", { class: "zahl" }, "Fahrten"), el("th", { class: "zahl" }, "Kilometer"), el("th", { class: "zahl" }, "Anteil"))),
        el(
          "tbody",
          {},
          b.bloecke.map((bl) =>
            el(
              "tr",
              {},
              el("td", {}, el("span", { class: "punkt", farbe: bl.farbe }), " ", bl.name),
              el("td", { class: "zahl" }, bl.anzahl),
              el("td", { class: "zahl" }, `${km(bl.km)} km`),
              el("td", { class: "zahl" }, `${zahl1.format(bl.anteil)} %`),
            ),
          ),
          el("tr", { class: "summe" }, el("td", {}, "Gesamt"), el("td", { class: "zahl" }, b.gesamtAnzahl), el("td", { class: "zahl" }, `${km(b.gesamtKm)} km`), el("td", { class: "zahl" }, "100,0 %")),
        ),
      ),
    ),
  ];
}

function block(bl, mitKmStand, kats) {
  return [
    el("div", { class: "block-kopf" }, el("span", { class: "punkt", farbe: bl.farbe }), el("h2", {}, bl.name), el("span", { class: "klein" }, `${bl.anzahl} Fahrten · ${km(bl.km)} km`)),
    el(
      "div",
      { class: "tabelle" },
      el(
        "table",
        {},
        el(
          "thead",
          {},
          el(
            "tr",
            {},
            el("th", {}, "Datum"),
            el("th", {}, "Zeit"),
            el("th", { class: "strecke" }, "Strecke"),
            el("th", { class: "zahl" }, "km"),
            mitKmStand ? [el("th", { class: "zahl" }, "Km-Stand Beginn"), el("th", { class: "zahl" }, "Km-Stand Ende")] : null,
            el("th", {}, "Zweck / Notiz"),
            el("th", { class: "nicht-drucken" }, ""),
          ),
        ),
        el(
          "tbody",
          {},
          bl.zeilen.map((z) =>
            el(
              "tr",
              {},
              el("td", {}, datum(z.f.start_zeit)),
              el("td", {}, `${zeit(z.f.start_zeit)}–${z.f.ende_zeit ? zeit(z.f.ende_zeit) : ""}`),
              el("td", { class: "strecke" }, strecke(z.f)),
              el("td", { class: "zahl" }, km(z.km)),
              mitKmStand ? [el("td", { class: "zahl" }, z.beginn === null ? "" : km(z.beginn)), el("td", { class: "zahl" }, z.ende === null ? "" : km(z.ende))] : null,
              el("td", {}, z.f.notiz),
              el(
                "td",
                { class: "nicht-drucken" },
                el(
                  "button",
                  { class: "knopf", title: "Änderungsprotokoll", onclick: () => protokollDialog(z.f, kats) },
                  z.f.version > 1 ? `geändert (${z.f.version - 1}×)` : "Protokoll",
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  ];
}

const FELDER = [
  ["startZeit", "Abfahrt", (v) => (v ? `${datum(v)} ${zeit(v)}` : "")],
  ["endeZeit", "Ankunft", (v) => (v ? `${datum(v)} ${zeit(v)}` : "")],
  ["startAdresse", "Von", (v) => v || ""],
  ["zwischenziele", "Über", (v) => zwischenziele(v).map((z) => z.adresse).join(" / ")],
  ["endeAdresse", "Nach", (v) => v || ""],
  ["distanzMeter", "Kilometer", (v) => `${km(kmWert(v || 0))} km`],
  ["kategorieId", "Kategorie", null],
  ["notiz", "Zweck / Notiz", (v) => v || ""],
  ["status", "Status", (v) => (v === "fertig" ? "abgeschlossen" : v === "offen" ? "nicht zugeordnet" : v)],
];

async function protokollDialog(f, kats) {
  const { fassungen } = await api(`/api/admin/fahrten/${encodeURIComponent(f.uuid)}/protokoll`);
  const katName = (id) => (id === null || id === undefined ? "ohne" : kats.get(id)?.name ?? `Kategorie ${id}`);
  const wert = (feld, format, v) => (feld === "kategorieId" ? katName(v) : format(v));
  const aktionen = { neu: "angelegt", geaendert: "geändert", geloescht: "gelöscht" };
  dialog(
    el("h2", {}, "Änderungsprotokoll"),
    el("p", { class: "klein" }, `${datum(f.start_zeit)} · ${strecke(f)}`),
    fassungen.map((v, i) => {
      const vorher = i > 0 ? fassungen[i - 1].daten : null;
      const zeilen = FELDER.filter(([feld]) => !vorher || JSON.stringify(vorher[feld]) !== JSON.stringify(v.daten[feld])).map(([feld, titel, format]) =>
        el("li", {}, vorher ? `${titel}: ${wert(feld, format, vorher[feld]) || "leer"} → ${wert(feld, format, v.daten[feld]) || "leer"}` : `${titel}: ${wert(feld, format, v.daten[feld])}`),
      );
      return el(
        "div",
        { class: `fassung ${v.aktion}` },
        el("strong", {}, `Fassung ${v.version} · ${aktionen[v.aktion] ?? v.aktion}`),
        el("div", { class: "klein" }, `am Gerät: ${datum(v.zeit_geraet)} ${zeit(v.zeit_geraet)} · eingegangen: ${datum(v.zeit_server)} ${zeit(v.zeit_server)}`),
        zeilen.length ? el("ul", {}, zeilen) : el("div", { class: "klein" }, "keine inhaltliche Änderung"),
      );
    }),
    el("div", { class: "aktionen" }, el("button", { class: "knopf haupt", onclick: dialogZu }, "Schließen")),
  );
}

// ------------------------------------------------------------------ CSV (Excel, deutsch)

function csvHerunterladen(f, zr, b) {
  const feld = (s) => {
    const t = String(s ?? "");
    return /[;"\r\n]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t;
  };
  const zeilen = [["Fahrtenbuch"], ["Zeitraum", zr.titel]];
  if (f.fahrzeug) zeilen.push(["Fahrzeug", f.fahrzeug]);
  if (f.kennzeichen) zeilen.push(["Kennzeichen", f.kennzeichen]);
  zeilen.push(["Fahrer", f.fahrer_name || f.name], []);
  const kopf = ["Kategorie", "Datum", "Abfahrt", "Ankunft", "Von", "Über", "Nach", "km"];
  if (b.mitKmStand) kopf.push("Km-Stand Beginn", "Km-Stand Ende");
  kopf.push("Zweck / Notiz");
  zeilen.push(kopf);
  for (const bl of b.bloecke) {
    for (const z of bl.zeilen) {
      const r = [
        bl.name,
        datum(z.f.start_zeit),
        zeit(z.f.start_zeit),
        z.f.ende_zeit ? zeit(z.f.ende_zeit) : "",
        z.f.start_adresse,
        zwischenziele(z.f.zwischenziele).map((x) => x.adresse).join(" / "),
        z.f.ende_adresse,
        km(z.km),
      ];
      if (b.mitKmStand) r.push(z.beginn === null ? "" : km(z.beginn), z.ende === null ? "" : km(z.ende));
      r.push(z.f.notiz);
      zeilen.push(r);
    }
  }
  zeilen.push([], ["Zusammenfassung"], ["Kategorie", "Fahrten", "km", "Anteil %"]);
  for (const bl of b.bloecke) zeilen.push([bl.name, bl.anzahl, km(bl.km), zahl1.format(bl.anteil)]);
  zeilen.push(["Gesamt", b.gesamtAnzahl, km(b.gesamtKm), "100,0"]);

  const text = "﻿" + zeilen.map((z) => z.map(feld).join(";")).join("\r\n") + "\r\n";
  const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
  const a = el("a", { href: url, download: `Fahrtenbuch_${(f.fahrer_name || f.name).replace(/[^\p{L}\p{N}]+/gu, "_")}_${zr.titel.replace(/[^\p{L}\p{N}]+/gu, "_")}.csv` });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5_000);
}

// ------------------------------------------------------------------ Start

route();

// Fahrtenbuch als Web-App (für das iPhone) – Oberfläche ohne Build-Schritt, Aufbau wie die Android-App.
// Texte aus Fahrtdaten werden ausschließlich als Text (textContent) eingesetzt, nie als HTML.
import * as A from "./aufzeichnung.js";
import { berichtErstellen, csvErstellen, kmVorherBerechnen, zeitraumGrenzen } from "./bericht.js";
import * as db from "./daten.js";
import * as F from "./format.js";
import { MIN_PIN } from "./krypto.js";
import { alsAdresse, adresseFehlt, streckeText, zwischenzieleLesen, zwischenzieleSchreiben } from "./orte.js";
import * as S from "./sicherung.js";
import { nachtragAnwenden } from "./strecke.js";
import { verbindungLesen } from "./syncformat.js";

const $inhalt = document.getElementById("inhalt");
const $dialog = document.getElementById("dialog");
const $status = document.getElementById("syncstatus");
const $meldung = document.getElementById("meldung");

// ------------------------------------------------------------------ Hilfen

function el(tag, attrs = {}, ...kinder) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs ?? {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === "class") e.className = v;
    else if (k.startsWith("on") && typeof v === "function") e.addEventListener(k.slice(2), v);
    else if (k === "farbe") e.style.background = v;
    else if (k === "stil") Object.assign(e.style, v);
    else if (k === "value") e.value = v;
    else e.setAttribute(k, v === true ? "" : String(v));
  }
  for (const kind of kinder.flat(Infinity)) {
    if (kind === null || kind === undefined || kind === false) continue;
    e.append(kind instanceof Node ? kind : document.createTextNode(String(kind)));
  }
  return e;
}

const knoten = (...k) => k.flat(Infinity).filter((x) => x !== null && x !== undefined && x !== false);
const zeige = (...k) => $inhalt.replaceChildren(...knoten(k));
const karte = (...k) => el("section", { class: "karte" }, ...k);

function dialog(...k) {
  $dialog.replaceChildren(...knoten(k));
  // Fokus auf die Überschrift – sonst öffnet das iPhone sofort Tastatur oder Zeitauswahl des ersten Felds
  const titel = $dialog.querySelector("h2");
  if (titel) {
    titel.tabIndex = -1;
    titel.autofocus = true;
  }
  if (!$dialog.open) $dialog.showModal();
  titel?.focus();
}
const dialogZu = () => $dialog.open && $dialog.close();

let meldungUhr = null;
function melde(text, fehler = false) {
  $meldung.textContent = text;
  $meldung.className = "meldung sichtbar" + (fehler ? " fehler" : "");
  clearTimeout(meldungUhr);
  meldungUhr = setTimeout(() => ($meldung.className = "meldung"), 3_500);
}

const istApple = () => /iPhone|iPad|iPod/.test(navigator.userAgent) || (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1);

function relativ(ms) {
  if (!ms) return "noch nie";
  const min = Math.round((Date.now() - ms) / 60_000);
  if (min < 1) return "gerade eben";
  if (min < 60) return `vor ${min} Min.`;
  const std = Math.round(min / 60);
  if (std < 24) return `vor ${std} Std.`;
  return `am ${F.datum(ms)} um ${F.uhrzeit(ms)}`;
}

const punkt = (farbe) => el("span", { class: "punkt", farbe: F.farbe(farbe) });
const fahrtenText = (n) => (n === 1 ? "1 Fahrt" : `${n} Fahrten`);

/** Eingabefeld für Adressen mit Vorschlägen aus den gespeicherten Orten. */
function adressFeld(wert, beschriftung, orte) {
  const id = `orte-${Math.random().toString(36).slice(2)}`;
  const eingabe = el("input", { type: "text", value: wert ?? "", list: id, autocomplete: "off", enterkeyhint: "done" });
  return {
    eingabe,
    knoten: el(
      "label",
      { class: "feld" },
      el("span", {}, beschriftung),
      eingabe,
      el("datalist", { id }, orte.map((o) => el("option", { value: alsAdresse(o) }))),
    ),
  };
}

function feld(beschriftung, eingabe, hinweis = null) {
  return el("label", { class: "feld" }, el("span", {}, beschriftung), eingabe, hinweis ? el("small", {}, hinweis) : null);
}

/** Uhrzeit „HH:MM“ am Tag von tagMs → Epoch-Millis. */
function zeitAmTag(tagMs, hhmm) {
  const [h, m] = hhmm.split(":").map(Number);
  const d = new Date(tagMs);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate(), h, m).getTime();
}

// ------------------------------------------------------------------ Navigation

const TABS = [
  ["#/", "Fahrt", '<path d="M8 5v14l11-7z"/>'],
  ["#/fahrten", "Fahrten", '<path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h10v2H4z"/>'],
  ["#/berichte", "Berichte", '<path d="M5 20V10h3v10zm5.5 0V4h3v16zM16 20v-7h3v7z"/>'],
  [
    "#/belege",
    "Belege",
    '<path d="M18 17H6v-2h12zm0-4H6v-2h12zm0-4H6V7h12zM3 22l1.5-1.5L6 22l1.5-1.5L9 22l1.5-1.5L12 22l1.5-1.5L15 22l1.5-1.5L18 22l1.5-1.5L21 22V2l-1.5 1.5L18 2l-1.5 1.5L15 2l-1.5 1.5L12 2l-1.5 1.5L9 2 7.5 3.5 6 2 4.5 3.5 3 2z"/>',
  ],
  ["#/einstellungen", "Einstellungen", '<path d="M19.4 13a7.5 7.5 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7 7 0 0 0-1.7-1L15 3h-4l-.3 2.9a7 7 0 0 0-1.7 1l-2.5-1-2 3.5L6.6 11a7.5 7.5 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1a7 7 0 0 0 1.7 1L11 21h4l.3-2.9a7 7 0 0 0 1.7-1l2.5 1 2-3.5zM13 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z"/>'],
];

function navigation(aktiv) {
  const nav = document.getElementById("tabs");
  nav.replaceChildren(
    ...TABS.map(([href, titel, pfad]) => {
      const a = el("a", { href, "aria-current": href === aktiv ? "page" : null });
      a.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${pfad}</svg>`; // feste Symbole, keine Nutzerdaten
      a.append(el("span", {}, titel));
      return a;
    }),
  );
}

let aktuelleSeite = null;

// ------------------------------------------------------------------ Belege (eigene Web-App der OG)

/** Belegablage der OG – eigener Login, eingebettet wie in der Android-App (Menüpunkt „Belege“). */
const BELEGE = "https://belege.smarte.events/";
let $belege = null;

/** Rahmen genau zwischen Kopf und unterer Leiste. */
function belegeLage() {
  const oben = document.querySelector(".kopf").getBoundingClientRect().bottom;
  const unten = document.getElementById("tabs").getBoundingClientRect().top;
  $belege.style.top = `${Math.max(0, oben)}px`;
  $belege.style.height = `${Math.max(0, unten - oben)}px`;
}

/**
 * Zeigt die Belege. Der Rahmen wird nur einmal angelegt und danach bloß versteckt: Beim Wechsel der
 * Menüpunkte bleibt ein halb ausgefüllter Beleg erhalten, und eine laufende Fahrt zeichnet weiter auf.
 */
function belegeZeigen() {
  if (!$belege) {
    $belege = el(
      "div",
      { class: "belege-rahmen" },
      el("iframe", { src: BELEGE, title: "Belege", allow: "camera; clipboard-write" }),
    );
    document.body.append($belege);
    // Kopf und Leiste ändern ihre Höhe (Sicherungsstatus, Drehen, Schriftgröße) – der Rahmen folgt
    const folgen = () => !$belege.hidden && belegeLage();
    new ResizeObserver(folgen).observe(document.querySelector(".kopf"));
    new ResizeObserver(folgen).observe(document.getElementById("tabs"));
    window.addEventListener("resize", folgen);
  }
  $inhalt.hidden = true;
  $belege.hidden = false;
  belegeLage();
}

function belegeVerbergen() {
  if ($belege) $belege.hidden = true;
  $inhalt.hidden = false;
}

async function route() {
  const h = location.hash || "#/";
  if (h === "#/belege") {
    aktuelleSeite = "belege";
    navigation(h);
    belegeZeigen();
    return;
  }
  belegeVerbergen();
  try {
    const m = /^#\/fahrt\/(.+)$/.exec(h);
    if (m) {
      aktuelleSeite = "bearbeiten";
      navigation("#/fahrten");
      return await bearbeitenSeite(decodeURIComponent(m[1]));
    }
    if (h === "#/fahrten") {
      aktuelleSeite = "fahrten";
      navigation(h);
      return await fahrtenSeite();
    }
    if (h === "#/berichte") {
      aktuelleSeite = "berichte";
      navigation(h);
      return await berichteSeite();
    }
    if (h === "#/einstellungen") {
      aktuelleSeite = "einstellungen";
      navigation(h);
      return await einstellungenSeite();
    }
    aktuelleSeite = "fahrt";
    navigation("#/");
    return await fahrtSeite();
  } catch (e) {
    console.error(e);
    zeige(karte(el("p", { class: "fehler" }, e.message || String(e))));
  }
}

window.addEventListener("hashchange", () => {
  window.scrollTo(0, 0);
  route();
});

// ------------------------------------------------------------------ Status der Sicherung (Kopfzeile)

async function statusZeigen() {
  const [s, z] = await Promise.all([S.verbindung(), db.protokollZaehlen()]);
  let text;
  let art;
  if (!s?.code) [text, art] = ["nicht verbunden", "grau"];
  else if (S.laeuft()) [text, art] = ["sichert …", "gelb"];
  // Ohne Netz ist nichts verloren – die Änderungen gehen hinaus, sobald wieder Verbindung besteht
  else if (S.OHNE_NETZ.includes(s.meldung)) [text, art] = [z.offen > 0 ? `${z.offen} offen · offline` : "offline", "gelb"];
  else if (s.meldung && !s.meldung.startsWith("Privatfahrten")) [text, art] = ["Fehler", "rot"];
  else if (z.offen > 0) [text, art] = [`${z.offen} offen`, "gelb"];
  else [text, art] = ["gesichert", "gruen"];
  $status.replaceChildren(el("span", { class: `lampe ${art}` }), text);
}
$status.addEventListener("click", () => (location.hash = "#/einstellungen"));

// ------------------------------------------------------------------ Seite „Fahrt“

async function fahrtSeite() {
  const s = A.stand();
  const [offene, kategorien, orte, sync, fertige, einst] = await Promise.all([
    db.offeneFahrten(),
    db.kategorien(),
    db.wert("orte", []),
    S.verbindung(),
    db.fertigeFahrten(),
    db.wert("einstellungen", {}),
  ]);
  if (aktuelleSeite !== "fahrt") return;
  zeige(
    !S.alsWebApp() && istApple() ? installHinweis() : null,
    !sync?.code
      ? el(
          "a",
          { class: "hinweis link", href: "#/einstellungen" },
          el("strong", {}, "Online-Sicherung verbinden"),
          el("span", {}, "Füge in den Einstellungen den Link ein, den du bekommen hast. Bis dahin bleiben die Fahrten nur auf diesem iPhone."),
        )
      : null,
    s.fahrt ? laufendeKarte(s, orte, einst) : startBereich(orte),
    offene.length ? offeneKarte(offene) : null,
    monatKarte(fertige, kategorien),
  );
}

function installHinweis() {
  return el(
    "div",
    { class: "hinweis" },
    el("strong", {}, "Zum Home-Bildschirm hinzufügen"),
    el(
      "span",
      {},
      "Tippe in Safari auf „Teilen“ (je nach iOS-Version erst auf „…“) und dann auf „Zum Home-Bildschirm“. " +
        "Öffne das Fahrtenbuch danach nur noch über das neue Symbol: Nur dort bleiben die Fahrten dauerhaft gespeichert und der Bildschirm während der Fahrt an.",
    ),
    el("a", { href: "/anleitung/" }, "Zur Anleitung"),
  );
}

let startOrtId = "";

function startBereich(orte) {
  const auswahl = el(
    "select",
    { "aria-label": "Start", onchange: (e) => (startOrtId = e.target.value) },
    el("option", { value: "" }, "Start: aktueller Standort (GPS)"),
    orte.map((o) => el("option", { value: o.id, selected: o.id === startOrtId }, `Start: ${o.name || o.adresse}`)),
  );
  return [
    orte.length
      ? auswahl
      : el("p", { class: "klein" }, "Tipp: Unter „Einstellungen“ kannst du Orte wie Zuhause oder Büro speichern. Sie werden dann automatisch erkannt und sind als Start wählbar."),
    el(
      "button",
      {
        class: "gross start",
        onclick: async (e) => {
          if (!("geolocation" in navigator)) return melde("Dieses Gerät kann keinen Standort liefern.", true);
          e.currentTarget.disabled = true;
          await A.starten(orte.find((o) => o.id === startOrtId) ?? null);
        },
      },
      "▶  Fahrt starten",
    ),
    el("p", { class: "klein" }, "Die Startadresse wird per GPS erfasst, gespeicherte Orte werden erkannt. Am Ziel fragt die App, wie die Fahrt gespeichert werden soll."),
  ];
}

function laufendeKarte(s, orte, einst) {
  const f = s.fahrt;
  const pause = s.pausiert;
  const zwischen = zwischenzieleLesen(f.zwischenziele);
  const minuten = einst.autoStoppMinuten ?? 5;
  const zeile = (titel, adresse, aendern) =>
    el("div", { class: "adresszeile" }, el("div", {}, el("small", {}, titel), el("div", {}, adresse)), el("button", { class: "text", onclick: aendern }, "Ändern"));
  return [
    el(
      "section",
      { class: `karte laufend${pause ? " pause" : ""}` },
      el("div", { class: "titel" }, pause ? "Fahrt pausiert" : "Fahrt läuft"),
      el("div", { class: "km" }, F.km(s.meter)),
      el("div", {}, "Dauer: ", el("span", { "data-seit": f.startZeit }, F.dauer(Date.now() - f.startZeit)), ` · seit ${F.uhrzeit(f.startZeit)}`),
      // Groß, damit die Geschwindigkeit während der Fahrt mit einem Blick lesbar ist
      !pause && s.kmh !== null ? el("div", { class: "tempo" }, el("span", { class: "zahl" }, String(s.kmh)), " km/h") : null,
      zeile("Start", f.startAdresse || "Standort wird ermittelt …", () =>
        adresseDialog(
          "Startadresse ändern",
          f.startAdresse,
          orte,
          (t) => A.startAdresseAendern(t),
          "Zum Beispiel, wenn du den Start zu spät gedrückt hast: Die App rechnet die Kilometer vom richtigen Start bis zum Beginn der Aufzeichnung über die Straße nach und schätzt die Abfahrtszeit.",
        ),
      ),
      f.nachtragMeter > 0
        ? el(
            "p",
            { class: "klein" },
            `Inkl. ${F.km(f.nachtragMeter)} vom korrigierten Start${f.nachtragMs >= 60_000 ? `, Abfahrt ca. ${F.dauer(f.nachtragMs)} früher` : ""} (geschätzt).`,
          )
        : null,
      s.nachtrag ? el("p", { class: s.nachtrag.fehler ? "klein fehler" : "klein" }, s.nachtrag.text) : null,
      zwischen.map((z, i) =>
        zeile(
          (pause && i === zwischen.length - 1 ? "Zwischenziel" : "Über") + (z.an ? ` · ${F.uhrzeit(z.an)}${z.ab ? "–" + F.uhrzeit(z.ab) : ""}` : ""),
          z.adresse || "Adresse wird ermittelt …",
          () => adresseDialog("Zwischenziel ändern", z.adresse, orte, (t) => A.zwischenzielAendern(i, t)),
        ),
      ),
      s.nachgerechnetM > 0 ? el("p", { class: "klein" }, `Bildschirm war aus: ${F.km(s.nachgerechnetM)} über die Straße nachgerechnet.`) : null,
      el(
        "p",
        { class: "klein" },
        pause
          ? "Während der Pause wird nicht aufgezeichnet, und die Fahrt endet nicht automatisch."
          : (minuten > 0 ? `Endet automatisch nach ${minuten} min Stillstand. ` : "") + "Für Hin- und Rückfahrt am Zwischenziel „Pause“ tippen.",
      ),
    ),
    s.gpsMeldung ? el("div", { class: "hinweis rot" }, s.gpsMeldung) : null,
    !pause && !s.wach
      ? el(
          "div",
          { class: "hinweis" },
          el("strong", {}, "Bildschirm bitte anlassen"),
          el("span", {}, "Das iPhone zeichnet nur auf, solange das Fahrtenbuch offen und der Bildschirm an ist. Fehlende Stücke werden über die Straße nachgerechnet."),
        )
      : null,
    el(
      "div",
      { class: "knopfreihe" },
      pause
        ? el("button", { class: "gross weiter", onclick: () => A.weiterfahren() }, "▶  Weiterfahren")
        : el("button", { class: "gross pause", onclick: () => A.pausieren() }, "❚❚  Pause"),
      el(
        "button",
        {
          class: "gross stopp",
          onclick: async (e) => {
            e.currentTarget.disabled = true;
            e.currentTarget.textContent = "Wird beendet …";
            await A.beenden();
          },
        },
        "■  Beenden",
      ),
    ),
  ];
}

function adresseDialog(titel, anfang, orte, speichern, hinweis = null) {
  const a = adressFeld(anfang, "Adresse", orte);
  dialog(
    el("h2", {}, titel),
    hinweis ? el("p", { class: "klein" }, hinweis) : null,
    el(
      "form",
      {
        onsubmit: async (e) => {
          e.preventDefault();
          await speichern(a.eingabe.value);
          dialogZu();
          // Bei offenem Dialog baut sich die Fahrtseite nicht neu auf – die Änderung gleich zeigen,
          // nicht erst mit der nächsten Position
          route();
        },
      },
      a.knoten,
      el("div", { class: "aktionen" }, el("button", { type: "button", class: "text", onclick: dialogZu }, "Abbrechen"), el("button", { class: "haupt" }, "Speichern")),
    ),
  );
}

function offeneKarte(offene) {
  return karte(
    el("h2", {}, `Noch nicht zugeordnet (${offene.length})`),
    offene.map((o) =>
      el(
        "button",
        { class: "zeile", onclick: () => kategorieDialog(o) },
        el("strong", {}, `${F.datumKurz(o.startZeit)} · ${F.uhrzeit(o.startZeit)} · ${F.km(o.distanzMeter)}`),
        el("small", {}, streckeText(o.startAdresse, zwischenzieleLesen(o.zwischenziele), o.endeAdresse)),
      ),
    ),
  );
}

function monatKarte(fertige, kategorien) {
  const jetzt = new Date();
  const von = new Date(jetzt.getFullYear(), jetzt.getMonth(), 1).getTime();
  const bis = new Date(jetzt.getFullYear(), jetzt.getMonth() + 1, 1).getTime();
  const b = berichtErstellen(
    fertige.filter((f) => f.startZeit >= von && f.startZeit < bis),
    kategorien,
  );
  return karte(
    el("h2", {}, "Dieser Monat"),
    b.bloecke.length === 0 ? el("p", { class: "klein" }, "Noch keine Fahrten.") : null,
    b.bloecke.map((bl) => el("div", { class: "summenzeile" }, punkt(bl.farbe), el("span", {}, bl.name), el("span", { class: "klein" }, `${bl.anzahl}×`), el("strong", {}, `${F.kmZahl(bl.summeKm)} km`))),
    b.bloecke.length > 1 ? el("div", { class: "summenzeile gesamt" }, el("span", {}, "Gesamt"), el("strong", {}, `${F.kmZahl(b.gesamtKm)} km`)) : null,
  );
}

// ------------------------------------------------------------------ Kategorie-Abfrage am Ziel

async function kategorieDialog(fahrt) {
  const [kategorien, orte] = await Promise.all([db.kategorien(), db.wert("orte", [])]);
  const aktive = kategorien.filter((k) => k.aktiv);
  const von = adressFeld(fahrt.startAdresse, "Von", orte);
  const zwischen = zwischenzieleLesen(fahrt.zwischenziele);
  const ueber = zwischen.map((z, i) => adressFeld(z.adresse, `Über (Zwischenziel ${i + 1})`, orte));
  const nach = adressFeld(fahrt.endeAdresse, "Nach", orte);
  const notiz = el("input", { type: "text", value: fahrt.notiz ?? "", autocomplete: "off" });
  const abfahrt = el("input", { type: "time", value: F.isoZeit(fahrt.startZeit) });
  const ankunft = el("input", { type: "time", value: F.isoZeit(fahrt.endeZeit ?? fahrt.startZeit) });
  // Start ohne Zutun des Nutzers (samt nachgeladener GPS-Adresse) – weicht der Start davon ab, wird nachgerechnet
  let startVorgabe = fahrt.startAdresse ?? "";
  const startKorrigiert = () => !!von.eingabe.value.trim() && von.eingabe.value.trim() !== startVorgabe.trim();
  const $neuerStart = el("p", { class: "klein", hidden: true }, "Neuer Start: Beim Speichern rechnet die App die Kilometer ab dort nach und schätzt die Abfahrt.");
  von.eingabe.addEventListener("input", () => ($neuerStart.hidden = !startKorrigiert()));
  const $status = el("p", { class: "klein", hidden: true });
  let speichert = false;

  async function speichern(k) {
    if (speichert) return;
    speichert = true;
    let aktuell = (await db.fahrt(fahrt.uuid)) ?? fahrt;
    let hinweis = "";
    // Start im Dialog korrigiert: fehlende Kilometer und Abfahrt nachtragen
    if (startKorrigiert() && aktuell.status === "offen") {
      $status.hidden = false;
      $status.textContent = "Kilometer ab dem neuen Start werden nachgerechnet …";
      for (const b of document.querySelectorAll(".kategorien button")) b.disabled = true;
      const e = await A.nachtragBerechnen(aktuell, von.eingabe.value);
      if (e.nachtrag) aktuell = { ...aktuell, ...nachtragAnwenden(aktuell, e.nachtrag) };
      else hinweis = ` Kilometer ab dem neuen Start nicht ergänzt: ${e.fehler}`;
    }
    // Von Hand geänderte Abfahrt gilt, sonst die gespeicherte (samt geschätzter Vorverlegung)
    const abfahrtGeaendert = abfahrt.value && abfahrt.value !== F.isoZeit(fahrt.startZeit);
    const start = abfahrtGeaendert ? zeitAmTag(fahrt.startZeit, abfahrt.value) : aktuell.startZeit;
    let ende = zeitAmTag(fahrt.startZeit, ankunft.value || F.isoZeit(fahrt.endeZeit ?? fahrt.startZeit));
    if (ende < start) ende += 86_400_000; // Fahrt über Mitternacht
    await db.fahrtSpeichern({
      ...aktuell,
      startZeit: start,
      nachtragMs: abfahrtGeaendert ? 0 : (aktuell.nachtragMs ?? 0),
      endeZeit: ende,
      startAdresse: von.eingabe.value.trim(),
      endeAdresse: nach.eingabe.value.trim(),
      zwischenziele: zwischenzieleSchreiben(zwischen.map((z, i) => ({ ...z, adresse: ueber[i].eingabe.value.trim() }))),
      notiz: notiz.value.trim(),
      kategorieId: k.id,
      status: "fertig",
    });
    dialogZu();
    melde(`Als „${k.name}“ gespeichert.${hinweis}`, !!hinweis);
    S.bald();
    route();
  }

  dialog(
    el("h2", {}, "Wie soll die Fahrt gespeichert werden?"),
    el("p", { class: "fett" }, `${F.datumKurz(fahrt.startZeit)} · ${F.km(fahrt.distanzMeter)}`),
    fahrt.nachtragMeter > 0 ? el("p", { class: "klein" }, `Inkl. ${F.km(fahrt.nachtragMeter)} vom korrigierten Start (über die Straße berechnet).`) : null,
    el("div", { class: "zweispaltig" }, feld("Abfahrt", abfahrt), feld("Ankunft", ankunft)),
    von.knoten,
    $neuerStart,
    ueber.map((u) => u.knoten),
    nach.knoten,
    feld("Zweck / Notiz (optional)", notiz),
    aktive.length === 0 ? el("p", { class: "fehler" }, "Keine Kategorien vorhanden – bitte in den Einstellungen anlegen.") : null,
    el(
      "div",
      { class: "kategorien" },
      aktive.map((k) => el("button", { class: "kategorie", stil: { background: F.farbe(k.farbe), color: F.schriftAuf(k.farbe) }, onclick: () => speichern(k) }, k.name)),
    ),
    $status,
    el(
      "div",
      { class: "aktionen" },
      el(
        "button",
        {
          class: "text warnung",
          onclick: async () => {
            if (!confirm("Fahrt verwerfen? Sie wird gelöscht und erscheint in keinem Bericht.")) return;
            await db.fahrtLoeschen(fahrt.uuid);
            dialogZu();
            route();
          },
        },
        "Verwerfen",
      ),
      el("button", { class: "text", onclick: dialogZu }, "Später"),
    ),
  );

  // Fehlende Adressen (z. B. kein Netz am Ziel) nachträglich ermitteln, sofern nicht schon geändert
  const nachladen = async (feldEingabe, alt, lat, lon, setzen) => {
    if (!adresseFehlt(alt) || typeof lat !== "number") return;
    const neu = await A.adresseBestimmen(lat, lon);
    if (adresseFehlt(neu) || feldEingabe.value !== alt) return;
    feldEingabe.value = neu;
    if (feldEingabe === von.eingabe) startVorgabe = neu;
    await db.unfertigAendern(fahrt.uuid, () => setzen(neu), ["offen"]);
  };
  nachladen(von.eingabe, fahrt.startAdresse, fahrt.startLat, fahrt.startLon, (a) => ({ startAdresse: a }));
  nachladen(nach.eingabe, fahrt.endeAdresse, fahrt.endeLat, fahrt.endeLon, (a) => ({ endeAdresse: a }));
}

// ------------------------------------------------------------------ Seite „Fahrten“

async function fahrtenSeite() {
  const [fahrten, kategorien] = await Promise.all([db.alleFahrten(), db.kategorien()]);
  const kats = new Map(kategorien.map((k) => [k.id, k]));
  const monate = new Map();
  for (const f of fahrten) {
    const d = new Date(f.startZeit);
    const schluessel = new Date(d.getFullYear(), d.getMonth(), 1).getTime();
    if (!monate.has(schluessel)) monate.set(schluessel, []);
    monate.get(schluessel).push(f);
  }
  zeige(
    el("a", { class: "knopf haupt breit", href: "#/fahrt/neu" }, "+ Fahrt nachtragen"),
    fahrten.length === 0 ? el("p", { class: "leer" }, "Noch keine Fahrten aufgezeichnet.") : null,
    [...monate.entries()].map(([monat, liste]) => {
      const fertig = liste.filter((f) => f.status === "fertig");
      return el(
        "section",
        { class: "monat" },
        el("h2", { class: "monatskopf" }, el("span", {}, F.monat(monat)), el("span", { class: "klein" }, `${fahrtenText(liste.length)} · ${F.km(fertig.reduce((s, f) => s + f.distanzMeter, 0))}`)),
        liste.map((f) => fahrtZeile(f, kats.get(f.kategorieId))),
      );
    }),
  );
}

function fahrtZeile(f, k) {
  const offen = f.status === "offen";
  const bis = f.endeZeit ? "–" + F.uhrzeit(f.endeZeit) : "";
  return el(
    "button",
    { class: "fahrt", onclick: () => (offen ? kategorieDialog(f) : (location.hash = `#/fahrt/${encodeURIComponent(f.uuid)}`)) },
    el("span", { class: "balken", farbe: offen ? "var(--linie)" : F.farbe(k?.farbe) }),
    el(
      "span",
      { class: "mitte" },
      el("strong", {}, `${F.datumKurz(f.startZeit)} · ${F.uhrzeit(f.startZeit)}${bis}`),
      el("small", {}, streckeText(f.startAdresse, zwischenzieleLesen(f.zwischenziele), f.endeAdresse)),
      el("small", { class: offen ? "fehler" : "klein" }, offen ? "Nicht zugeordnet – antippen" : (k?.name ?? "Ohne Kategorie") + (f.notiz ? ` · ${f.notiz}` : "")),
    ),
    el("strong", { class: "rechts" }, F.km(f.distanzMeter)),
  );
}

// ------------------------------------------------------------------ Fahrt bearbeiten / nachtragen

async function bearbeitenSeite(uuid) {
  const neu = uuid === "neu";
  const [original, kategorien, orte] = await Promise.all([neu ? null : db.fahrt(uuid), db.kategorien(), db.wert("orte", [])]);
  if (!neu && (!original || original.status === "laufend")) {
    location.hash = "#/fahrten";
    return;
  }
  const jetzt = Date.now();
  const basis = original ?? { startZeit: jetzt - 30 * 60_000, endeZeit: jetzt, distanzMeter: 0, zwischenziele: "", kategorieId: null };
  let kategorieId = basis.kategorieId ?? null;
  const datum = el("input", { type: "date", value: F.isoDatum(new Date(basis.startZeit)) });
  const abfahrt = el("input", { type: "time", value: F.isoZeit(basis.startZeit) });
  const ankunft = el("input", { type: "time", value: F.isoZeit(basis.endeZeit ?? basis.startZeit) });
  const von = adressFeld(basis.startAdresse, "Von", orte);
  const nach = adressFeld(basis.endeAdresse, "Nach", orte);
  const kmFeld = el("input", { type: "text", inputmode: "decimal", value: original ? F.kmEingabe(original.distanzMeter) : "", autocomplete: "off" });
  const notiz = el("input", { type: "text", value: basis.notiz ?? "", autocomplete: "off" });
  const zwischen = zwischenzieleLesen(basis.zwischenziele).map((z) => ({ z, feld: adressFeld(z.adresse, "", orte) }));
  const $zwischen = el("div", {});
  const $fehler = el("p", { class: "fehler" });

  function zwischenZeigen() {
    $zwischen.replaceChildren(
      ...zwischen.map((x, i) =>
        el(
          "div",
          { class: "zwischen" },
          el("span", { class: "klein" }, `Über (Zwischenziel ${i + 1})`),
          el("div", { class: "reihe" }, x.feld.eingabe, el("button", { type: "button", class: "text", "aria-label": "Zwischenziel entfernen", onclick: () => (zwischen.splice(i, 1), zwischenZeigen()) }, "✕")),
          x.feld.knoten.querySelector("datalist"),
        ),
      ),
      el("button", { type: "button", class: "text", onclick: () => (zwischen.push({ z: { adresse: "" }, feld: adressFeld("", "", orte) }), zwischenZeigen()) }, "+ Zwischenziel hinzufügen"),
    );
  }
  zwischenZeigen();

  const chips = el("div", { class: "chips" });
  function chipsZeigen() {
    chips.replaceChildren(
      ...kategorien
        .filter((k) => k.aktiv || k.id === kategorieId)
        .map((k) => el("button", { type: "button", class: "chip", "aria-pressed": String(k.id === kategorieId), onclick: () => ((kategorieId = k.id), chipsZeigen()) }, punkt(k.farbe), k.name)),
    );
  }
  chipsZeigen();

  async function speichern(e) {
    e.preventDefault();
    const km = F.kmParsen(kmFeld.value);
    if (kategorieId === null) return ($fehler.textContent = "Bitte eine Kategorie wählen.");
    if (km === null) return ($fehler.textContent = "Bitte die Kilometer eingeben, z. B. 12,5.");
    if (!datum.value || !abfahrt.value || !ankunft.value) return ($fehler.textContent = "Bitte Datum und Zeiten angeben.");
    const tag = F.ausIsoDatum(datum.value).getTime();
    const start = zeitAmTag(tag, abfahrt.value);
    let ende = zeitAmTag(tag, ankunft.value);
    if (ende < start) ende += 86_400_000; // Ankunft vor Abfahrt = Fahrt über Mitternacht
    // Unveränderte km-Anzeige soll die genaue GPS-Strecke nicht verfälschen
    const meter = original && F.kmWert(original.distanzMeter) === km ? original.distanzMeter : km * 1000;
    // Von Hand geänderte Kilometer oder Abfahrt ersetzen einen berechneten Nachtrag (korrigierter Start)
    const nachtrag = original
      ? {
          nachtragMeter: meter === original.distanzMeter ? (original.nachtragMeter ?? 0) : 0,
          nachtragMs: Math.floor(start / 60_000) === Math.floor(original.startZeit / 60_000) ? (original.nachtragMs ?? 0) : 0,
        }
      : {};
    const f = {
      ...(original ?? { uuid: crypto.randomUUID(), startLat: null, startLon: null, endeLat: null, endeLon: null }),
      startZeit: start,
      endeZeit: ende,
      startAdresse: von.eingabe.value.trim(),
      endeAdresse: nach.eingabe.value.trim(),
      zwischenziele: zwischenzieleSchreiben(
        zwischen.map((x) => ({ ...x.z, adresse: x.feld.eingabe.value.trim() })).filter((z) => z.adresse),
      ),
      distanzMeter: meter,
      ...nachtrag,
      kategorieId,
      notiz: notiz.value.trim(),
      status: "fertig",
    };
    await db.fahrtSpeichern(f);
    S.bald();
    melde("Gespeichert.");
    history.length > 1 ? history.back() : (location.hash = "#/fahrten");
  }

  zeige(
    el("div", { class: "seitenkopf" }, el("a", { class: "text", href: "#/fahrten" }, "‹ Fahrten"), el("h1", {}, neu ? "Fahrt nachtragen" : "Fahrt bearbeiten")),
    el(
      "form",
      { class: "karte formular", onsubmit: speichern },
      el("h2", {}, "Kategorie"),
      chips,
      kategorieId !== null && !kategorien.some((k) => k.id === kategorieId && k.aktiv)
        ? el("p", { class: "klein" }, "Bisherige Kategorie ist ausgeblendet – bleibt erhalten, solange keine andere gewählt wird.")
        : null,
      el("h2", {}, "Details"),
      feld("Datum", datum),
      el("div", { class: "zweispaltig" }, feld("Abfahrt", abfahrt), feld("Ankunft", ankunft)),
      von.knoten,
      $zwischen,
      nach.knoten,
      feld("Kilometer", kmFeld),
      feld("Zweck / Notiz", notiz),
      $fehler,
      el("button", { class: "gross haupt" }, "Speichern"),
      neu
        ? null
        : el(
            "button",
            {
              type: "button",
              class: "text warnung breit",
              onclick: async () => {
                if (!confirm("Fahrt löschen? Sie wird endgültig entfernt.")) return;
                await db.fahrtLoeschen(original.uuid);
                S.bald();
                location.hash = "#/fahrten";
              },
            },
            "Fahrt löschen",
          ),
    ),
  );
}

// ------------------------------------------------------------------ Seite „Berichte“

const heute = new Date();
const ansicht = { art: "monat", jahr: heute.getFullYear(), monat: heute.getMonth(), von: new Date(heute.getFullYear(), heute.getMonth(), 1), bis: heute, filter: null };

function zeitraum() {
  if (ansicht.art === "monat") return zeitraumGrenzen({ art: "monat", jahr: ansicht.jahr, monat: ansicht.monat });
  if (ansicht.art === "jahr") return zeitraumGrenzen({ art: "jahr", jahr: ansicht.jahr });
  return zeitraumGrenzen({ art: "zeitraum", von: ansicht.von, bis: ansicht.bis });
}

function blaettern(richtung) {
  if (ansicht.art === "monat") {
    const d = new Date(ansicht.jahr, ansicht.monat + richtung, 1);
    ansicht.jahr = d.getFullYear();
    ansicht.monat = d.getMonth();
  } else if (ansicht.art === "jahr") ansicht.jahr += richtung;
  route();
}

async function berichteSeite() {
  const [fertige, kategorien, einst] = await Promise.all([db.fertigeFahrten(), db.kategorien(), db.wert("einstellungen", {})]);
  const zr = zeitraum();
  const imZeitraum = fertige.filter((f) => f.startZeit >= zr.von && f.startZeit < zr.bis);
  const b = berichtErstellen(imZeitraum, kategorien, {
    kmVorher: kmVorherBerechnen(fertige, einst, zr.von),
    kmStandAb: einst.kmStandAb ?? 0,
    filter: ansicht.filter,
  });

  const segment = el(
    "div",
    { class: "segment", role: "group", "aria-label": "Zeitraum" },
    [["monat", "Monat"], ["jahr", "Jahr"], ["zeitraum", "Zeitraum"]].map(([art, titel]) =>
      el("button", { "aria-pressed": String(ansicht.art === art), onclick: () => ((ansicht.art = art), route()) }, titel),
    ),
  );
  const wahl =
    ansicht.art === "zeitraum"
      ? el(
          "div",
          { class: "blaettern" },
          el("input", { type: "date", value: F.isoDatum(ansicht.von), onchange: (e) => e.target.value && ((ansicht.von = F.ausIsoDatum(e.target.value)), route()) }),
          "bis",
          el("input", { type: "date", value: F.isoDatum(ansicht.bis), onchange: (e) => e.target.value && ((ansicht.bis = F.ausIsoDatum(e.target.value)), route()) }),
        )
      : el(
          "div",
          { class: "blaettern" },
          el("button", { class: "knopf", onclick: () => blaettern(-1), "aria-label": "Zurück" }, "‹"),
          el("strong", {}, zr.titel),
          el("button", { class: "knopf", onclick: () => blaettern(1), "aria-label": "Weiter" }, "›"),
        );

  const alleIds = b.alle.map((x) => x.kategorieId ?? 0);
  const chips =
    b.alle.length > 1
      ? el(
          "div",
          { class: "chips" },
          b.alle.map((bl) => {
            const id = bl.kategorieId ?? 0;
            return el(
              "button",
              {
                class: "chip",
                "aria-pressed": String(!ansicht.filter || ansicht.filter.has(id)),
                onclick: () => {
                  const f = new Set(ansicht.filter ?? alleIds);
                  f.has(id) ? f.delete(id) : f.add(id);
                  ansicht.filter = alleIds.every((x) => f.has(x)) ? null : f;
                  route();
                },
              },
              punkt(bl.farbe),
              bl.name,
            );
          }),
        )
      : null;

  const fahrzeug = [einst.fahrzeug, einst.kennzeichen].filter((x) => x?.trim()).join(" · ");
  zeige(
    el("div", { class: "druckkopf" }, el("h1", {}, "Fahrtenbuch"), el("div", {}, [einst.fahrer ? `Fahrer: ${einst.fahrer}` : null, fahrzeug ? `Fahrzeug: ${fahrzeug}` : null, `Zeitraum: ${zr.titel}`].filter(Boolean).join(" · "))),
    el("div", { class: "karte leiste nicht-drucken" }, segment, wahl, chips),
    b.gesamtAnzahl === 0
      ? el("p", { class: "leer" }, "Keine abgeschlossenen Fahrten im gewählten Zeitraum.")
      : [
          karte(
            el("h2", {}, `Zusammenfassung · ${zr.titel}`),
            el(
              "table",
              { class: "tabelle" },
              el("thead", {}, el("tr", {}, el("th", {}, "Kategorie"), el("th", { class: "zahl" }, "Fahrten"), el("th", { class: "zahl" }, "km"), el("th", { class: "zahl" }, "Anteil"))),
              el(
                "tbody",
                {},
                b.bloecke.map((bl) =>
                  el("tr", {}, el("td", {}, punkt(bl.farbe), " ", bl.name), el("td", { class: "zahl" }, bl.anzahl), el("td", { class: "zahl" }, F.kmZahl(bl.summeKm)), el("td", { class: "zahl" }, `${F.kmZahl(bl.anteil)} %`)),
                ),
                el("tr", { class: "summe" }, el("td", {}, "Gesamt"), el("td", { class: "zahl" }, b.gesamtAnzahl), el("td", { class: "zahl" }, F.kmZahl(b.gesamtKm)), el("td", { class: "zahl" }, "100,0 %")),
              ),
            ),
            el(
              "div",
              { class: "knopfreihe nicht-drucken" },
              el("button", { class: "knopf", onclick: () => window.print() }, "Drucken / PDF"),
              el("button", { class: "knopf haupt", onclick: () => csvTeilen(b, zr, einst) }, "CSV für Excel"),
            ),
          ),
          b.bloecke.map((bl) =>
            el(
              "section",
              { class: "karte block" },
              el("div", { class: "block-kopf" }, punkt(bl.farbe), el("h2", {}, bl.name), el("span", { class: "klein" }, `${fahrtenText(bl.anzahl)} · ${F.kmZahl(bl.summeKm)} km`)),
              el(
                "table",
                { class: "tabelle liste" },
                el(
                  "thead",
                  {},
                  el("tr", {}, el("th", {}, "Datum"), el("th", {}, "Zeit"), el("th", {}, "Strecke"), el("th", { class: "zahl" }, "km"), b.mitKmStand ? [el("th", { class: "zahl" }, "Km-Stand Beginn"), el("th", { class: "zahl" }, "Km-Stand Ende")] : null, el("th", {}, "Zweck / Notiz")),
                ),
                el(
                  "tbody",
                  {},
                  bl.zeilen.map((z) =>
                    el(
                      "tr",
                      {},
                      el("td", { "data-titel": "Datum" }, F.datum(z.fahrt.startZeit)),
                      el("td", { "data-titel": "Zeit" }, `${F.uhrzeit(z.fahrt.startZeit)}–${z.fahrt.endeZeit ? F.uhrzeit(z.fahrt.endeZeit) : ""}`),
                      el("td", { "data-titel": "Strecke", class: "strecke" }, streckeText(z.fahrt.startAdresse, zwischenzieleLesen(z.fahrt.zwischenziele), z.fahrt.endeAdresse)),
                      el("td", { "data-titel": "km", class: "zahl" }, F.kmZahl(z.km)),
                      b.mitKmStand
                        ? [
                            el("td", { "data-titel": "Km-Stand Beginn", class: "zahl" }, z.kmStandBeginn === null ? "" : F.kmZahl(z.kmStandBeginn)),
                            el("td", { "data-titel": "Km-Stand Ende", class: "zahl" }, z.kmStandEnde === null ? "" : F.kmZahl(z.kmStandEnde)),
                          ]
                        : null,
                      el("td", { "data-titel": "Zweck / Notiz" }, z.fahrt.notiz),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
  );
}

async function csvTeilen(b, zr, einst) {
  const name = `Fahrtenbuch_${zr.titel.replace(/[^\p{L}\p{N}]+/gu, "_")}.csv`;
  const datei = new File([csvErstellen(b, zr, einst)], name, { type: "text/csv" });
  try {
    if (navigator.canShare?.({ files: [datei] })) {
      await navigator.share({ files: [datei], title: `Fahrtenbuch ${zr.titel}` });
      return;
    }
  } catch (e) {
    if (e.name === "AbortError") return;
  }
  const url = URL.createObjectURL(datei);
  const a = el("a", { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

// ------------------------------------------------------------------ Seite „Einstellungen“

async function einstellungenSeite() {
  const [kategorien, orte, einst] = await Promise.all([db.kategorien(), db.wert("orte", []), db.wert("einstellungen", {})]);
  zeige(
    el("div", { id: "sicherung-karte" }, await sicherungKarte()),
    el("div", { id: "pin-karte" }, await pinKarte(kategorien)),
    kategorienKarte(kategorien),
    orteKarte(orte),
    fahrzeugKarte(einst),
    fahrtendeKarte(einst),
    infoKarte(),
  );
}

async function sicherungKarteErneuern() {
  const k = document.getElementById("sicherung-karte");
  if (k) k.replaceChildren(await sicherungKarte());
  const p = document.getElementById("pin-karte");
  if (p) p.replaceChildren(await pinKarte(await db.kategorien()));
}

async function sicherungKarte() {
  const s = await S.verbindung();
  if (!s?.code) {
    const eingabe = el("input", { type: "text", placeholder: "Link oder Code", autocomplete: "off", autocapitalize: "off", spellcheck: "false" });
    const fehler = el("p", { class: "fehler" });
    const verbinden = el("button", { class: "haupt" }, "Verbinden");
    return karte(
      el("h2", {}, "Online-Sicherung"),
      el("p", { class: "klein" }, "Füge den Verbindungs-Link ein, den du aus der Fahrtenbuch-Verwaltung bekommen hast. Danach sichert die App jede Fahrt automatisch."),
      el(
        "form",
        {
          class: "formular",
          onsubmit: async (e) => {
            e.preventDefault();
            fehler.textContent = "";
            const v = verbindungLesen(eingabe.value, location.origin);
            if (v.fehler) return (fehler.textContent = v.fehler);
            verbinden.disabled = true;
            verbinden.textContent = "Verbinde …";
            try {
              const r = await S.verbinden(v.code);
              melde(`Verbunden als ${r.fahrer}.`);
              await einstellungenSeite();
              const kategorien = await db.kategorien();
              const p = await S.privatStand();
              if (r.pinUebernehmen) pinUebernehmenDialog();
              else if (kategorien.some((k) => k.privat) && !p.huelle) pinDialog(false);
              S.synchronisieren();
            } catch (err) {
              fehler.textContent = err.message;
              verbinden.disabled = false;
              verbinden.textContent = "Verbinden";
            }
          },
        },
        eingabe,
        el(
          "div",
          { class: "knopfreihe" },
          el(
            "button",
            {
              type: "button",
              class: "knopf",
              onclick: async () => {
                try {
                  eingabe.value = (await navigator.clipboard.readText()).trim();
                } catch {
                  fehler.textContent = "Einfügen ging nicht – bitte ins Feld tippen und „Einsetzen“ wählen.";
                }
              },
            },
            "Einfügen",
          ),
          verbinden,
        ),
        fehler,
      ),
    );
  }
  const z = await db.protokollZaehlen();
  return karte(
    el("h2", {}, "Online-Sicherung"),
    el("p", {}, el("strong", {}, `Verbunden als ${s.fahrer}`)),
    el("p", { class: "klein" }, `Zuletzt gesichert: ${relativ(s.zuletzt)}`),
    z.offen > 0 ? el("p", { class: "klein" }, `Warten auf Übertragung: ${z.offen} Änderungen`) : null,
    z.abgelehnt > 0 ? el("p", { class: "fehler" }, `${z.abgelehnt} Änderungen hat der Server abgelehnt.`) : null,
    s.meldung ? el("p", { class: s.meldung.startsWith("Privatfahrten") ? "klein" : "fehler" }, s.meldung) : null,
    el(
      "div",
      { class: "knopfreihe" },
      el(
        "button",
        {
          class: "knopf haupt",
          disabled: S.laeuft(),
          onclick: async () => {
            const r = await S.synchronisieren();
            if (r.art === "ok") melde(r.gesendet ? `${r.gesendet} Änderungen gesichert.` : "Alles gesichert.");
            else if (r.text) melde(r.text, true);
          },
        },
        S.laeuft() ? "Sichert …" : "Jetzt sichern",
      ),
      el(
        "button",
        {
          class: "knopf",
          onclick: async () => {
            if (!confirm("Online-Sicherung trennen? Neue Fahrten werden dann nicht mehr gesichert. Die bereits gesicherten bleiben auf dem Server.")) return;
            await S.trennen();
            einstellungenSeite();
          },
        },
        "Trennen",
      ),
    ),
  );
}

async function pinKarte(kategorien) {
  const p = await S.privatStand();
  const privat = kategorien.filter((k) => k.privat && k.aktiv).map((k) => k.name);
  let text;
  let knoepfe;
  if (p.offeneHuelle) {
    text = "Für deine Privatfahrten gibt es schon einen PIN von einem früheren Gerät. Gib ihn ein, damit sie lesbar bleiben – bis dahin werden Privatfahrten nicht gesichert.";
    knoepfe = [el("button", { class: "knopf haupt", onclick: pinUebernehmenDialog }, "Bisherigen PIN eingeben")];
  } else if (p.huelle) {
    text = "Details deiner Privatfahrten (Adressen, Notiz) gehen nur verschlüsselt an den Server. Lesen kann sie nur, wer den PIN kennt – Datum, Zeiten und Kilometer bleiben für Berichte sichtbar.";
    knoepfe = [el("button", { class: "knopf", onclick: () => pinDialog(true) }, "PIN ändern")];
  } else {
    text = "Lege einen PIN fest, damit die Details deiner Privatfahrten nur verschlüsselt gesichert werden. Ohne PIN bleiben Privatfahrten auf diesem iPhone.";
    knoepfe = [el("button", { class: "knopf haupt", onclick: () => pinDialog(false) }, "PIN festlegen")];
  }
  return karte(
    el("h2", {}, "PIN für Privatfahrten"),
    el("p", { class: "klein" }, text),
    privat.length ? el("p", { class: "klein" }, `Privat: ${privat.join(", ")} (in den Kategorien einstellbar)`) : el("p", { class: "klein" }, "Keine Kategorie ist als privat markiert."),
    el("div", { class: "knopfreihe" }, knoepfe),
  );
}

function pinFeld(platzhalter) {
  return el("input", { type: "password", inputmode: "numeric", pattern: "[0-9]*", autocomplete: "off", placeholder: platzhalter, minlength: MIN_PIN });
}

function pinDialog(aendern) {
  const pin = pinFeld("PIN");
  const nochmal = pinFeld("PIN wiederholen");
  const fehler = el("p", { class: "fehler" });
  const los = el("button", { class: "haupt" }, "Speichern");
  dialog(
    el("h2", {}, aendern ? "PIN ändern" : "PIN für Privatfahrten"),
    el("p", { class: "klein" }, `Mindestens ${MIN_PIN} Ziffern. Den PIN kennt nur, wer ihn festlegt – weder der Server noch der Admin. Ohne ihn lassen sich die Details deiner Privatfahrten nicht mehr lesen, also gut merken.`),
    el(
      "form",
      {
        class: "formular",
        onsubmit: async (e) => {
          e.preventDefault();
          if (!S.pinGueltig(pin.value)) return (fehler.textContent = `Bitte mindestens ${MIN_PIN} Ziffern.`);
          if (pin.value !== nochmal.value) return (fehler.textContent = "Die beiden PINs stimmen nicht überein.");
          los.disabled = true;
          los.textContent = "Wird verschlüsselt …";
          try {
            await S.pinFestlegen(pin.value);
            dialogZu();
            melde("PIN gespeichert.");
            sicherungKarteErneuern();
          } catch (err) {
            fehler.textContent = err.message;
            los.disabled = false;
            los.textContent = "Speichern";
          }
        },
      },
      pin,
      nochmal,
      fehler,
      el("div", { class: "aktionen" }, el("button", { type: "button", class: "text", onclick: dialogZu }, "Abbrechen"), los),
    ),
  );
}

function pinUebernehmenDialog() {
  const pin = pinFeld("Bisheriger PIN");
  const fehler = el("p", { class: "fehler" });
  const los = el("button", { class: "haupt" }, "Übernehmen");
  dialog(
    el("h2", {}, "Bisherigen PIN eingeben"),
    el("p", { class: "klein" }, "Für deine Privatfahrten gibt es schon einen PIN (von einem früheren Gerät). Mit ihm bleiben die bereits gesicherten Privatfahrten lesbar."),
    el(
      "form",
      {
        class: "formular",
        onsubmit: async (e) => {
          e.preventDefault();
          los.disabled = true;
          los.textContent = "Prüfe …";
          try {
            await S.schluesselUebernehmen(pin.value);
            dialogZu();
            melde("PIN übernommen.");
            sicherungKarteErneuern();
          } catch {
            fehler.textContent = "PIN falsch.";
            los.disabled = false;
            los.textContent = "Übernehmen";
          }
        },
      },
      pin,
      fehler,
      el(
        "div",
        { class: "aktionen" },
        el(
          "button",
          {
            type: "button",
            class: "text warnung",
            onclick: () => {
              if (confirm("PIN vergessen? Du kannst einen neuen festlegen – die Details der bisher gesicherten Privatfahrten sind dann aber für niemanden mehr lesbar (Datum, Zeiten und Kilometer bleiben).")) pinDialog(false);
            },
          },
          "PIN vergessen",
        ),
        el("button", { type: "button", class: "text", onclick: dialogZu }, "Später"),
        los,
      ),
    ),
  );
}

function kategorienKarte(kategorien) {
  const aktive = kategorien.filter((k) => k.aktiv);
  return karte(
    el("h2", {}, "Kategorien"),
    aktive.map((k) =>
      el(
        "button",
        { class: "zeile mit-punkt", onclick: () => kategorieBearbeiten(k) },
        punkt(k.farbe),
        el("span", {}, k.name, k.privat ? el("small", {}, "privat · mit PIN geschützt") : null),
      ),
    ),
    el("button", { class: "knopf", onclick: () => kategorieBearbeiten(null) }, "+ Kategorie"),
  );
}

function kategorieBearbeiten(k) {
  const name = el("input", { type: "text", value: k?.name ?? "", required: true, maxlength: 100 });
  let farbe = k?.farbe ?? db.PALETTE[0];
  const farben = el("div", { class: "farben" });
  const farbenZeigen = () =>
    farben.replaceChildren(
      ...db.PALETTE.map((p) => el("button", { type: "button", class: "farbe", farbe: F.farbe(p), "aria-pressed": String(p === farbe), "aria-label": "Farbe", onclick: () => ((farbe = p), farbenZeigen()) })),
    );
  farbenZeigen();
  const privat = el("input", { type: "checkbox" });
  privat.checked = k?.privat ?? false;
  dialog(
    el("h2", {}, k ? "Kategorie bearbeiten" : "Neue Kategorie"),
    el(
      "form",
      {
        class: "formular",
        onsubmit: async (e) => {
          e.preventDefault();
          if (!name.value.trim()) return;
          await db.kategorieSpeichern({ ...(k ?? {}), name: name.value, farbe, privat: privat.checked });
          dialogZu();
          S.bald();
          einstellungenSeite();
        },
      },
      feld("Name", name),
      farben,
      el("label", { class: "schalter" }, privat, el("span", {}, "Privat", el("small", {}, "Details nur verschlüsselt sichern (PIN)"))),
      el(
        "div",
        { class: "aktionen" },
        k
          ? el(
              "button",
              {
                type: "button",
                class: "text warnung",
                onclick: async () => {
                  if (!confirm(`Kategorie „${k.name}“ entfernen? Hat sie schon Fahrten, wird sie nur ausgeblendet.`)) return;
                  await db.kategorieEntfernen(k.id);
                  dialogZu();
                  S.bald();
                  einstellungenSeite();
                },
              },
              "Entfernen",
            )
          : null,
        el("button", { type: "button", class: "text", onclick: dialogZu }, "Abbrechen"),
        el("button", { class: "haupt" }, "Speichern"),
      ),
    ),
  );
}

function orteKarte(orte) {
  return karte(
    el("h2", {}, "Gespeicherte Orte"),
    el("p", { class: "klein" }, "Orte wie Zuhause, Büro oder Stammkunden werden an Start, Zwischenziel und Ziel automatisch erkannt (Umkreis 150 m)."),
    orte.map((o) =>
      el(
        "button",
        { class: "zeile", onclick: () => ortBearbeiten(o) },
        el("strong", {}, o.name || o.adresse),
        el("small", {}, [o.name ? o.adresse : null, typeof o.lat === "number" ? "wird erkannt" : "ohne Position – nur zur Auswahl"].filter(Boolean).join(" · ")),
      ),
    ),
    el("button", { class: "knopf", onclick: () => ortBearbeiten(null) }, "+ Ort"),
  );
}

function ortBearbeiten(o) {
  const name = el("input", { type: "text", value: o?.name ?? "", placeholder: "z. B. Zuhause", maxlength: 100 });
  const adresse = el("input", { type: "text", value: o?.adresse ?? "", placeholder: "Straße Nr, PLZ Ort", autocomplete: "off" });
  let lat = o?.lat ?? null;
  let lon = o?.lon ?? null;
  let koordinatenFuer = typeof lat === "number" ? (o?.adresse ?? "") : null;
  const lage = el("p", { class: "klein" });
  const lageZeigen = () => (lage.textContent = typeof lat === "number" && koordinatenFuer === adresse.value ? "Position bekannt – wird automatisch erkannt." : "Ohne Position – wird beim Speichern aus der Adresse gesucht.");
  adresse.addEventListener("input", lageZeigen);
  lageZeigen();
  const fehler = el("p", { class: "fehler" });
  dialog(
    el("h2", {}, o ? "Ort bearbeiten" : "Neuer Ort"),
    el(
      "form",
      {
        class: "formular",
        onsubmit: async (e) => {
          e.preventDefault();
          if (!name.value.trim() && !adresse.value.trim()) return (fehler.textContent = "Bitte Name oder Adresse angeben.");
          if (adresse.value.trim() && (typeof lat !== "number" || koordinatenFuer !== adresse.value)) {
            const k = await S.koordinatenSuchen(adresse.value.trim());
            lat = k?.lat ?? null;
            lon = k?.lon ?? null;
          }
          const neu = { id: o?.id ?? crypto.randomUUID(), name: name.value.trim(), adresse: adresse.value.trim() };
          if (typeof lat === "number" && typeof lon === "number") Object.assign(neu, { lat, lon });
          await db.wertAendern("orte", (liste) => (o ? liste.map((x) => (x.id === o.id ? neu : x)) : [...liste, neu]), []);
          dialogZu();
          einstellungenSeite();
        },
      },
      feld("Name", name),
      feld("Adresse", adresse),
      el(
        "button",
        {
          type: "button",
          class: "knopf",
          onclick: (e) => {
            const knopf = e.currentTarget;
            knopf.disabled = true;
            navigator.geolocation.getCurrentPosition(
              async (pos) => {
                lat = pos.coords.latitude;
                lon = pos.coords.longitude;
                if (!adresse.value.trim()) adresse.value = await A.adresseBestimmen(lat, lon);
                koordinatenFuer = adresse.value;
                lageZeigen();
                knopf.disabled = false;
              },
              () => {
                fehler.textContent = "Standort nicht verfügbar.";
                knopf.disabled = false;
              },
              { enableHighAccuracy: true, timeout: 20_000, maximumAge: 10_000 },
            );
          },
        },
        "Aktuellen Standort übernehmen",
      ),
      lage,
      fehler,
      el(
        "div",
        { class: "aktionen" },
        o
          ? el(
              "button",
              {
                type: "button",
                class: "text warnung",
                onclick: async () => {
                  await db.wertAendern("orte", (liste) => liste.filter((x) => x.id !== o.id), []);
                  dialogZu();
                  einstellungenSeite();
                },
              },
              "Löschen",
            )
          : null,
        el("button", { type: "button", class: "text", onclick: dialogZu }, "Abbrechen"),
        el("button", { class: "haupt" }, "Speichern"),
      ),
    ),
  );
}

function fahrzeugKarte(e) {
  const fahrer = el("input", { type: "text", value: e.fahrer ?? "", autocomplete: "name" });
  const fahrzeug = el("input", { type: "text", value: e.fahrzeug ?? "", placeholder: "z. B. VW Tiguan" });
  const kennzeichen = el("input", { type: "text", value: e.kennzeichen ?? "", autocapitalize: "characters" });
  const kmStand = el("input", { type: "text", inputmode: "numeric", value: e.kmStandStart ? String(e.kmStandStart) : "", placeholder: "Tachostand in km" });
  const ab = el("input", { type: "date", value: e.kmStandAb ? F.isoDatum(new Date(e.kmStandAb)) : F.isoDatum(new Date()) });
  return karte(
    el("h2", {}, "Fahrzeug und Fahrer"),
    el(
      "form",
      {
        class: "formular",
        onsubmit: async (ev) => {
          ev.preventDefault();
          const km = Math.max(0, Math.round(Number(kmStand.value.replace(/\D/g, "")) || 0));
          await db.wertAendern("einstellungen", (x) => ({
            ...x,
            fahrer: fahrer.value.trim(),
            fahrzeug: fahrzeug.value.trim(),
            kennzeichen: kennzeichen.value.trim(),
            kmStandStart: km,
            kmStandAb: km > 0 && ab.value ? F.ausIsoDatum(ab.value).getTime() : 0,
          }));
          S.bald();
          melde("Gespeichert.");
        },
      },
      feld("Fahrer", fahrer),
      feld("Fahrzeug", fahrzeug),
      feld("Kennzeichen", kennzeichen),
      el("div", { class: "zweispaltig" }, feld("Kilometerstand", kmStand), feld("am", ab)),
      el("p", { class: "klein" }, "Mit Kilometerstand zeigen die Berichte den Tachostand vor und nach jeder Fahrt."),
      el("button", { class: "knopf haupt" }, "Speichern"),
    ),
  );
}

function fahrtendeKarte(e) {
  const aktuell = e.autoStoppMinuten ?? 5;
  return karte(
    el("h2", {}, "Automatisches Fahrtende"),
    el(
      "select",
      {
        "aria-label": "Automatisches Fahrtende",
        onchange: async (ev) => {
          const n = Number(ev.target.value);
          await db.wertAendern("einstellungen", (x) => ({ ...x, autoStoppMinuten: n }));
          melde("Gespeichert.");
        },
      },
      [0, 3, 5, 10, 15, 20, 30].map((n) => el("option", { value: String(n), selected: n === aktuell }, n === 0 ? "Aus – nur per Knopf" : `nach ${n} Minuten Stillstand`)),
    ),
    el("p", { class: "klein" }, "Funktioniert nur, solange das Fahrtenbuch offen und der Bildschirm an ist."),
  );
}

function infoKarte() {
  return karte(
    el("h2", {}, "Über"),
    el("p", { class: "klein" }, `Fahrtenbuch ${S.VERSION} · ${S.alsWebApp() ? "Web-App" : "im Browser"} · `, el("a", { href: "/anleitung/" }, "Anleitung")),
    el(
      "p",
      { class: "klein" },
      "Adressen und Straßenkilometer: © ",
      el("a", { href: "https://www.openstreetmap.org/copyright", target: "_blank", rel: "noopener" }, "OpenStreetMap-Mitwirkende"),
      " (Nominatim, FOSSGIS-Routing). Fehler in der Karte? ",
      el("a", { href: "https://www.openstreetmap.org/fixthemap", target: "_blank", rel: "noopener" }, "Karte verbessern"),
    ),
    el(
      "button",
      {
        class: "knopf",
        onclick: async () => {
          const r = await navigator.serviceWorker?.getRegistration();
          await r?.update().catch(() => {});
          location.reload();
        },
      },
      "Neu laden",
    ),
  );
}

// ------------------------------------------------------------------ Start

let zeichnenGeplant = null;

async function beiAufzeichnung() {
  const beendet = A.beendeteAbholen();
  if (beendet) {
    await route();
    kategorieDialog(beendet);
    return;
  }
  // Positionen kommen im Sekundentakt – die Seite höchstens alle 1,5 s neu aufbauen
  if (aktuelleSeite !== "fahrt" || $dialog.open || zeichnenGeplant) return;
  zeichnenGeplant = setTimeout(() => {
    zeichnenGeplant = null;
    if (aktuelleSeite === "fahrt" && !$dialog.open) route();
  }, 1_500);
}

let dauerTakt = null;
function takt() {
  clearInterval(dauerTakt);
  dauerTakt = setInterval(() => {
    for (const e of document.querySelectorAll("[data-seit]")) e.textContent = F.dauer(Date.now() - Number(e.dataset.seit));
  }, 1_000);
}

async function start() {
  A.beiAenderung(beiAufzeichnung);
  S.beiAenderung(() => {
    statusZeigen();
    if (aktuelleSeite === "einstellungen" && !$dialog.open) sicherungKarteErneuern();
  });
  $dialog.addEventListener("click", (e) => {
    if (e.target === $dialog) dialogZu();
  });
  // Daten dieser Web-App nicht bei Speicherknappheit löschen lassen
  navigator.storage?.persist?.().catch(() => {});
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js", { scope: "./" }).catch(() => {});

  await A.wiederaufnehmen();
  await route();
  statusZeigen();
  takt();

  // Nicht zugeordnete Fahrten beim Öffnen gleich abfragen (wie in der Android-App)
  const offene = await db.offeneFahrten();
  if (offene.length && !A.stand().fahrt && !$dialog.open) kategorieDialog(offene.at(-1));

  S.synchronisieren();
  setInterval(() => document.visibilityState === "visible" && S.synchronisieren(), 5 * 60_000);
  window.addEventListener("online", () => S.synchronisieren());
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") {
      S.synchronisieren();
      statusZeigen();
    }
  });
}

start();

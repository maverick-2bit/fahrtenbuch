// Verbindungsseite: Der Code steht im Fragment (#code=…) und wird dadurch nie an den Server gesendet.
const code = new URLSearchParams(location.hash.slice(1)).get("code");
const ua = navigator.userAgent;
const zeigen = (id) => (document.getElementById(id).hidden = false);
// iPadOS meldet sich wie ein Mac, hat aber einen Touchscreen
const apple = /iPhone|iPad|iPod/i.test(ua) || (/Macintosh/i.test(ua) && navigator.maxTouchPoints > 1);

if (!code || !/^[A-Za-z0-9_-]{20,100}$/.test(code)) {
  zeigen("fehlt");
} else if (/Android/i.test(ua)) {
  // Rückfallweg, falls der Link nicht direkt die App geöffnet hat: eigenes Schema der App
  document.getElementById("verbinden").href =
    `fahrtenbuch://verbinden?server=${encodeURIComponent(location.origin)}&code=${encodeURIComponent(code)}`;
  zeigen("android");
} else if (apple) {
  // Die Web-App am Home-Bildschirm hat einen eigenen Speicher – der Link kommt per Zwischenablage hinüber
  const hinweis = document.getElementById("kopiert");
  document.getElementById("kopieren").addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(location.href);
      hinweis.textContent = "Kopiert.";
    } catch {
      hinweis.textContent = "Kopieren ging nicht – bitte den Link lange drücken und kopieren.";
    }
  });
  zeigen("iphone");
} else {
  zeigen("pc");
}

// Verbindungsseite: Der Code steht im Fragment (#code=…) und wird dadurch nie an den Server gesendet.
const code = new URLSearchParams(location.hash.slice(1)).get("code");
const ua = navigator.userAgent;
const zeigen = (id) => (document.getElementById(id).hidden = false);

if (!code || !/^[A-Za-z0-9_-]{20,100}$/.test(code)) {
  zeigen("fehlt");
} else if (/Android/i.test(ua)) {
  // Rückfallweg, falls der Link nicht direkt die App geöffnet hat: eigenes Schema der App
  document.getElementById("verbinden").href =
    `fahrtenbuch://verbinden?server=${encodeURIComponent(location.origin)}&code=${encodeURIComponent(code)}`;
  zeigen("android");
} else if (/iPhone|iPad|iPod/i.test(ua)) {
  zeigen("iphone");
} else {
  zeigen("pc");
}

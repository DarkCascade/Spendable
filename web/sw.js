// Offline support. App files: stale-while-revalidate, so the app opens instantly (even offline)
// and picks up a new deploy on the next launch. Google Fonts are cached the same way.
// Bump VERSION when the file list changes.

const VERSION = "spendable-v1";
const APP_SHELL = [
  "./", "index.html", "styles.css", "manifest.webmanifest",
  "js/app.js", "js/csv.js", "js/dates.js", "js/db.js", "js/engine.js", "js/fx.js",
  "icons/apple-touch-icon.png", "icons/icon-192.png", "icons/icon-512.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(VERSION).then((c) => c.addAll(APP_SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  const cacheable = url.origin === self.location.origin || url.hostname.endsWith("fonts.googleapis.com") || url.hostname.endsWith("fonts.gstatic.com");
  if (!cacheable) return;
  event.respondWith(
    caches.open(VERSION).then(async (cache) => {
      const cached = await cache.match(request, { ignoreSearch: true });
      const network = fetch(request)
        .then((res) => { if (res.ok || res.type === "opaque") cache.put(request, res.clone()); return res; })
        .catch(() => cached);
      return cached || network;
    }),
  );
});

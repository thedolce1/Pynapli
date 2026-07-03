// Pynapli app-shell service worker (WEB_PWA_GAMEPLAN.md W1.4).
//
// Privacy rule (load-bearing): this worker caches the STATIC APP SHELL ONLY —
// never API responses, profile photos, or anything user-specific. The API lives
// on another origin, and this worker additionally whitelists shell asset types
// so nothing personal can ever land in Cache Storage on a shared computer.
const CACHE = 'pynapli-shell-v1';
const CORE = ['/', '/index.html', '/manifest.webmanifest'];

const SHELL_ASSET = /\.(js|mjs|wasm|css|html|png|svg|ico|webmanifest|woff2?)$/;

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(CORE)).then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return; // API/media: never touched.

  // SPA navigations: network first, cached shell as the offline fallback.
  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match('/index.html')));
    return;
  }

  if (!SHELL_ASSET.test(url.pathname)) return;

  // Shell assets: cache-first with background refresh (stale-while-revalidate).
  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(() => cached);
      return cached || network;
    }),
  );
});

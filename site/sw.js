// الفجر الصادق (True Dawn) — offline support. Page: network first (updates arrive when online), cache when offline. Icons: cache first.
const CACHE = 'ontime-prayer-v3';
// each page under its own key (the home page as './'), so opening the privacy page never replaces the home page kept for offline use
const key = u => { const p = new URL(u).pathname; return /\/(index\.html)?$/.test(p) ? './' : p; };
const FILES = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './icon-512.png', './icon-maskable-512.png', './apple-touch-icon.png', './favicon.png', './favicon.svg', './fonts/reem-kufi.woff', './fonts/plex-400.woff', './fonts/plex-600.woff'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', e => {
  const r = e.request; if (r.method !== 'GET' || new URL(r.url).origin !== location.origin) return;
  if (r.mode === 'navigate' || r.url.endsWith('.webmanifest')) {
    e.respondWith(fetch(r).then(res => { const copy = res.clone(); if (res.ok) caches.open(CACHE).then(c => c.put(r.mode === 'navigate' ? key(r.url) : r, copy)); return res; })
      .catch(() => caches.match(r.mode === 'navigate' ? key(r.url) : r, { ignoreSearch: true }).then(m => m || caches.match('./index.html'))));
  } else {
    e.respondWith(caches.match(r).then(m => m || fetch(r)));
  }
});

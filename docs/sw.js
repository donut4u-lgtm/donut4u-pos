const CACHE = "donut4u-v1";

self.addEventListener("install", () => { self.skipWaiting(); });

self.addEventListener("activate", e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// Online: always get the newest copy and keep it. Offline: use the saved copy.
self.addEventListener("fetch", e => {
  const r = e.request;
  if (r.method !== "GET") return;
  const u = new URL(r.url);
  const mine = u.origin === location.origin || u.hostname === "www.gstatic.com" || u.hostname === "cdn.jsdelivr.net";
  if (!mine) return;
  e.respondWith(
    fetch(r)
      .then(res => {
        if (res && res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put(r, copy)); }
        return res;
      })
      .catch(() => caches.match(r).then(m => m || caches.match(self.registration.scope)))
  );
});

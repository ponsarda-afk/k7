// K7 — service worker : garde l'appli dispo hors ligne (les livres sont dans IndexedDB, pas ici)
const V = "k7-v13";
const COVERS = "k7-covers"; // pochettes venues du web : gardées d'une version à l'autre
const COVER_HOSTS = /(^|\.)(mzstatic\.com|books\.google\.com|googleusercontent\.com|covers\.openlibrary\.org|archive\.org|upload\.wikimedia\.org)$/;
const SHELL = ["./", "./index.html", "./manifest.webmanifest", "./icon.svg", "./icon-192.png", "./icon-512.png", "./capy-logo.png", ...["archivo-latin-400","archivo-latin-500","archivo-latin-600","archivo-latin-700","archivo-latin-800","vt323-latin-400","permanent-marker-latin-400"].map(f => `./fonts/${f}-normal.woff2`)];
self.addEventListener("install", e => { e.waitUntil(caches.open(V).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener("activate", e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== V && k !== COVERS).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener("fetch", e => {
  const u = new URL(e.request.url);
  if (e.request.method !== "GET") return;
  // réseau d'abord pour la page (mises à jour), cache en secours
  if (u.origin === location.origin && (e.request.mode === "navigate" || u.pathname.endsWith("index.html"))) {
    e.respondWith(fetch(e.request).then(r => { const c = r.clone(); caches.open(V).then(x => x.put("./index.html", c)); return r; })
      .catch(() => caches.match("./index.html")));
    return;
  }
  // pochettes web : cache d'abord, gardées pour le hors-ligne (même sans CORS)
  if (e.request.destination === "image" && COVER_HOSTS.test(u.hostname)) {
    e.respondWith(caches.open(COVERS).then(c => c.match(e.request, {ignoreVary: true}).then(hit => hit ||
      fetch(e.request).then(r => { if (r.ok || r.type === "opaque") c.put(e.request, r.clone()); return r; }))));
    return;
  }
  // le reste de l'appli : cache d'abord
  e.respondWith(caches.match(e.request).then(hit => hit || fetch(e.request).then(r => {
    if (r.ok && (u.origin === location.origin || u.host.includes("fonts.g"))) { const c = r.clone(); caches.open(V).then(x => x.put(e.request, c)); }
    return r;
  })));
});

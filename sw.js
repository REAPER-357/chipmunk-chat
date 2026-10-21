const C = 'chipmunk-v1';
const ASSETS = ['./', 'index.html', 'manifest.json', 'icon.png', 'small-icon.png'];

self.addEventListener('install', e => {
    e.waitUntil(caches.open(C).then(c => Promise.all(ASSETS.map(u => c.add(u).catch(() => {})))));
    self.skipWaiting();
});

self.addEventListener('activate', e => {
    e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== C).map(k => caches.delete(k)))));
    self.clients.claim();
});

// Network-first for the app shell, cache as offline fallback. Cross-origin (PeerJS, CDNs) is left alone.
self.addEventListener('fetch', e => {
    const u = new URL(e.request.url);
    if (e.request.method !== 'GET' || u.origin !== location.origin) return;
    e.respondWith(
        fetch(e.request)
            .then(r => { const copy = r.clone(); caches.open(C).then(c => c.put(e.request, copy)); return r; })
            .catch(() => caches.match(e.request))
    );
});

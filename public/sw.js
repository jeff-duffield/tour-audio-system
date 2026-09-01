// Minimal no-op service worker.
//
// Its ONLY purpose is to satisfy Android/Chrome's installability criteria so
// the app can be added to the home screen as a standalone PWA. It does NOT
// cache, intercept, or modify any request/response — every fetch passes
// straight through to the network exactly as if no service worker were
// installed at all. This guarantees the live audio WebSocket stream, camera
// widget, and admin/guide pages always get fresh content and are never
// served stale/cached data.
//
// If you ever want offline caching, add it deliberately here — do not add a
// generic "cache everything" handler, since that would risk serving stale
// JS/HTML during live tours.

self.addEventListener('install', (event) => {
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(self.clients.claim());
});

// No 'fetch' listener — this is intentional. Without one, the browser
// handles every request with its normal default network behavior.

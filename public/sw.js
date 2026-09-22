// Bump this on every deploy that changes any shell file — this is the ONLY
// thing that makes browsers detect an update at all. A service worker is only
// re-fetched/re-installed when its own script's bytes change; changing
// index.html alone (without touching this file) is invisible to the update
// check, so every previous deploy that didn't bump this number was silently
// serving the very first cached version forever, no matter how many times the
// server itself got new content.
var CACHE_NAME = 'pitch-cutter-v2';

var SHELL_FILES = [
  './',
  './index.html',
  './manifest.json',
  './icons/icon.svg',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-512-maskable.png'
];

self.addEventListener('install', function (event) {
  event.waitUntil(
    caches.open(CACHE_NAME).then(function (cache) { return cache.addAll(SHELL_FILES); })
  );
  self.skipWaiting();
});

self.addEventListener('activate', function (event) {
  event.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(keys.filter(function (k) { return k !== CACHE_NAME; }).map(function (k) { return caches.delete(k); }));
    })
  );
  self.clients.claim();
});

// Stale-while-revalidate: answer instantly from cache (so the app opens offline
// with no spinner), then refresh the cache from the network in the background so
// the next launch has whatever changed. Falls back to cache if the network fetch
// itself fails (fully offline).
self.addEventListener('fetch', function (event) {
  if (event.request.method !== 'GET') return;
  event.respondWith(
    caches.match(event.request).then(function (cached) {
      // no-cache forces revalidation with the server on every fetch instead of
      // trusting the browser's own HTTP cache — belt-and-suspenders alongside
      // the Cache-Control headers in firebase.json, so a background refresh
      // here can't itself be satisfied from a stale cached response.
      var networkFetch = fetch(event.request, { cache: 'no-cache' }).then(function (response) {
        if (response && response.ok) {
          var copy = response.clone();
          caches.open(CACHE_NAME).then(function (cache) { cache.put(event.request, copy); });
        }
        return response;
      }).catch(function () { return cached; });
      return cached || networkFetch;
    })
  );
});

"use strict";

// Back office service worker: makes the app installable and opens its shell offline (with a clear "offline"
// state, since every page needs the API). Administration data is never cached.
const CACHE_PREFIX = "coldtrace-admin-";
const CACHE_NAME = CACHE_PREFIX + "1";
const SHELL = ["/", "/index.html", "/admin.css", "/admin.js", "/manifest.webmanifest", "/icons/icon-192.png",
  "/icons/icon-512.png", "/icons/maskable-512.png", "/icons/apple-touch-icon.png"];

self.addEventListener("install", event => {
  event.waitUntil(caches.open(CACHE_NAME).then(cache =>
    cache.addAll(SHELL.map(url => new Request(url, { cache: "reload" })))).then(() => self.skipWaiting()));
});

self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names.filter(n => n.startsWith(CACHE_PREFIX) && n !== CACHE_NAME).map(n => caches.delete(n)));
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== self.location.origin || !SHELL.includes(url.pathname)) return;
  event.respondWith(fetch(request, { cache: "no-store" }).then(r => {
    if (r.ok) return r;
    throw new Error("unavailable");
  }).catch(async error => {
    const cached = await (await caches.open(CACHE_NAME)).match(url.pathname);
    if (cached) return cached;
    throw error;
  }));
});

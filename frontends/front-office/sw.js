"use strict";

// Bump this version whenever the shell changes. Install a complete offline
// snapshot; keep an update waiting until the operator chooses to reload.
const CACHE_PREFIX = "coldtrace-shell-";
const CACHE_NAME = CACHE_PREFIX + "v5";
const SHELL = [
  "/", "/index.html", "/monitor.css", "/monitor.js", "/pwa.js",
  "/manifest.webmanifest", "/icons/icon-192.png", "/icons/icon-512.png",
  "/icons/maskable-512.png", "/icons/apple-touch-icon.png"
];

self.addEventListener("install", event => {
  event.waitUntil(caches.open(CACHE_NAME).then(cache =>
    cache.addAll(SHELL.map(url => new Request(url, { cache: "reload" })))));
});

self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names.filter(name => name.startsWith(CACHE_PREFIX) && name !== CACHE_NAME)
      .map(name => caches.delete(name)));
    await self.clients.claim();
  })());
});

self.addEventListener("message", event => {
  if (event.data?.type === "ACTIVATE_UPDATE") self.skipWaiting();
});

self.addEventListener("fetch", event => {
  const request = event.request;
  const url = new URL(request.url);
  // An explicit static allowlist excludes every API, mutation, control request,
  // WebSocket endpoint, third-party font and unknown path.
  if (request.method !== "GET" || url.origin !== self.location.origin || !SHELL.includes(url.pathname)) return;
  event.respondWith((async () => {
    try {
      // Network first, bypassing the HTTP cache: deployments cannot be stuck
      // behind an old script/style. Cache Storage holds only the install snapshot.
      const response = await fetch(request, { cache: "no-store" });
      if (response.ok) return response;
      throw new Error("Shell unavailable");
    } catch (error) {
      const cached = await (await caches.open(CACHE_NAME)).match(url.pathname);
      if (cached) return cached;
      throw error;
    }
  })());
});

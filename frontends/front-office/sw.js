"use strict";

// Bump this version whenever the shell changes. Install a complete offline
// snapshot; keep an update waiting until the operator chooses to reload.
const CACHE_PREFIX = "coldtrace-shell-";
const CACHE_NAME = CACHE_PREFIX + "phase-b-2";
const SHELL = [
  "/", "/index.html", "/monitor.css", "/monitor.js", "/reports.js", "/notify.js", "/pwa.js",
  "/vendor/chart.umd.min.js",
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

// ---------- alerts on this device (Web Push, see notify.js and the API's PushService) ----------
// Each new incident arrives as an encrypted message from the browser's push service; the API decides who gets it.

self.addEventListener("push", event => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch (error) { data = { body: event.data?.text() }; }
  const title = typeof data.title === "string" && data.title ? data.title : "ColdTrace alert";
  event.waitUntil(self.registration.showNotification(title, {
    body: typeof data.body === "string" ? data.body : "",
    tag: typeof data.tag === "string" && data.tag ? data.tag : undefined,
    icon: "/icons/icon-192.png",
    badge: "/icons/icon-192.png",
    requireInteraction: data.metric === "temperature" || data.metric === "humidity",
    data: { url: typeof data.url === "string" && data.url.startsWith("/") ? data.url : "/" }
  }));
});

self.addEventListener("notificationclick", event => {
  event.notification.close();
  const target = new URL(event.notification.data?.url || "/", self.location.origin).href;
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const open = windows.find(client => new URL(client.url).origin === self.location.origin);
    if (open) {
      await open.focus();
      if ("navigate" in open) await open.navigate(target).catch(() => {});
      return;
    }
    await self.clients.openWindow(target);
  })());
});

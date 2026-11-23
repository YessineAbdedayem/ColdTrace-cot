const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const root = path.join(__dirname, "..");

function worker() {
  const handlers = {}, stores = new Map(), shown = [], opened = [];
  let skipped = false, claimed = false, network;
  const caches = {
    async keys() { return [...stores.keys()]; },
    async delete(name) { return stores.delete(name); },
    async open(name) {
      if (!stores.has(name)) stores.set(name, new Map());
      const data = stores.get(name);
      return {
        async addAll(requests) { for (const r of requests) data.set(r.url, new Response("offline shell")); },
        async match(url) { return data.get(new URL(url, "https://coldtrace.test").href)?.clone(); }
      };
    }
  };
  class LocalRequest extends Request {
    constructor(url, options) { super(new URL(url, "https://coldtrace.test"), options); }
  }
  vm.runInNewContext(fs.readFileSync(path.join(root, "sw.js"), "utf8"), {
    self: { location: { origin: "https://coldtrace.test" }, addEventListener: (name, fn) => { handlers[name] = fn; },
      clients: { claim: async () => { claimed = true; }, matchAll: async () => [], openWindow: async url => { opened.push(url); } },
      registration: { showNotification: async (title, options) => { shown.push({ title, options }); } },
      skipWaiting: () => { skipped = true; } },
    caches, URL, Request: LocalRequest,
    fetch: (...args) => network(...args)
  });
  return {
    handlers, stores, shown, opened, get skipped() { return skipped; }, get claimed() { return claimed; },
    set network(fn) { network = fn; },
    async event(name) { let pending; handlers[name]({ waitUntil: p => { pending = p; } }); await pending; },
    fetch(url, method = "GET") {
      let response;
      handlers.fetch({ request: new LocalRequest(url, { method }), respondWith: value => { response = value; } });
      return response;
    }
  };
}

test("manifest has standalone identity, both orientations and valid original PNG icons", () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, "manifest.webmanifest")));
  assert.equal(manifest.name, "ColdTrace Front Office");
  assert.equal(manifest.start_url, "/");
  assert.equal(manifest.scope, "/");
  assert.equal(manifest.display, "standalone");
  assert.equal(manifest.orientation, "any");
  assert(manifest.icons.some(i => i.purpose === "maskable"));
  for (const icon of manifest.icons) {
    const png = fs.readFileSync(path.join(root, icon.src));
    assert.equal(png.subarray(0, 8).toString("hex"), "89504e470d0a1a0a");
    assert.equal(`${png.readUInt32BE(16)}x${png.readUInt32BE(20)}`, icon.sizes);
  }
});

test("installation precaches only existing local static assets, without taking over an open session", async () => {
  const sw = worker();
  await sw.event("install");
  const urls = [...sw.stores.values()][0].keys();
  for (const url of urls) {
    const pathname = new URL(url).pathname;
    assert(!pathname.startsWith("/api") && !pathname.startsWith("/ws"));
    assert(fs.existsSync(path.join(root, pathname === "/" ? "index.html" : pathname)));
  }
  assert.equal(sw.skipped, false);
});

test("live data, control requests, mutations, WebSocket paths and third-party assets are not intercepted", () => {
  const sw = worker();
  for (const url of ["/api/sensors", "/api/incidents?status=open", "/api/dev/faults", "/api/dev/faults/clear", "/api/gateways", "/ws/alerts", "/unknown", "https://fonts.googleapis.com/css2"]) {
    assert.equal(sw.fetch(url), undefined, url);
  }
  assert.equal(sw.fetch("/index.html", "POST"), undefined);
  assert.equal(sw.fetch("/api/incidents/id/ack", "POST"), undefined);
});

test("offline navigation uses the static shell and never invents an API response", async () => {
  const sw = worker();
  await sw.event("install");
  sw.network = async () => { throw new Error("offline"); };
  assert.equal(await (await sw.fetch("/")).text(), "offline shell");
  assert.equal(await (await sw.fetch("/monitor.js?deployment=new")).text(), "offline shell");
  assert.equal(sw.fetch("/api/sensors"), undefined);
});

test("online assets always use the network with HTTP cache bypass", async () => {
  const sw = worker();
  await sw.event("install");
  sw.network = async (request, options) => {
    assert.equal(options.cache, "no-store");
    return new Response("new deployment");
  };
  assert.equal(await (await sw.fetch("/monitor.js")).text(), "new deployment");
});

test("activation removes obsolete ColdTrace caches and preserves unrelated caches", async () => {
  const sw = worker();
  await sw.event("install");
  sw.stores.set("coldtrace-shell-obsolete", new Map());
  sw.stores.set("another-app", new Map());
  await sw.event("activate");
  assert(!sw.stores.has("coldtrace-shell-obsolete"));
  assert(sw.stores.has("another-app"));
  assert.equal(sw.claimed, true);
});

test("only an explicit update message activates a waiting worker early", () => {
  const sw = worker();
  sw.handlers.message({ data: { type: "OTHER" } });
  assert.equal(sw.skipped, false);
  sw.handlers.message({ data: { type: "ACTIVATE_UPDATE" } });
  assert.equal(sw.skipped, true);
});

test("a push message shows a notification with the alert, and a click opens the logbook", async () => {
  const sw = worker();
  let pending;
  sw.handlers.push({
    data: { json: () => ({ title: "ColdTrace · Porte ouverte · S01", body: "Porte ouverte sur S01 · site01",
      tag: "incident-1", url: "/#logbook", metric: "door" }) },
    waitUntil: p => { pending = p; }
  });
  await pending;
  assert.equal(sw.shown.length, 1);
  assert.equal(sw.shown[0].title, "ColdTrace · Porte ouverte · S01");
  assert.equal(sw.shown[0].options.tag, "incident-1");
  let closed = false;
  sw.handlers.notificationclick({
    notification: { data: sw.shown[0].options.data, close: () => { closed = true; } },
    waitUntil: p => { pending = p; }
  });
  await pending;
  assert(closed);
  assert.deepEqual(sw.opened, ["https://coldtrace.test/#logbook"]);
});

test("a push message never opens another origin", async () => {
  const sw = worker();
  let pending;
  sw.handlers.push({ data: { json: () => ({ title: "x", url: "https://evil.test/" }) }, waitUntil: p => { pending = p; } });
  await pending;
  assert.equal(sw.shown[0].options.data.url, "/");
});

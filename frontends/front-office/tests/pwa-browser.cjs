// Uses real data from the running stack. No database records are deleted.
// Set PWA_TEST_ACTIONS=1 to create/restore one door fault for action/layout checks.
const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const os = require("node:os");
const root = path.join(__dirname, "..");
const output = path.join(root, "test-results");
// The front-office's address (WildFly default host). The API is on api.<domain>, same port; the browser must trust
// the development CA (CI imports it into the browser's certificate store).
const front = new URL(process.env.FRONT_OFFICE_URL || "https://www.coldtrace.test");
const base = front.origin;
const API = `${front.protocol}//api.${front.hostname.replace(/^www\./, "")}${front.port ? ":" + front.port : ""}`;
const checks = [];
const username = process.env.PWA_TEST_USER;
const password = process.env.PWA_TEST_PASSWORD;
assert(username && password, "Set PWA_TEST_USER and PWA_TEST_PASSWORD for a dedicated test account");
let apiHeaders;
function pass(name) { checks.push(name); console.log("PASS: " + name); }

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const launchOptions = { headless: true,
    ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) };
  const browser = await chromium.launch(launchOptions);
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const errors = [];
  const workerErrors = [];
  const observeWorkerLogs = async (context, page) => {
    const session = await context.newCDPSession(page);
    await session.send("Log.enable");
    session.on("Log.entryAdded", ({ entry }) => {
      if (entry.level === "error" && (entry.source === "worker" || entry.url?.includes("/sw.js"))) workerErrors.push(entry.text);
    });
  };
  await observeWorkerLogs(context, page);
  let activeSockets = 0;
  page.on("websocket", socket => { activeSockets++; socket.on("close", () => { activeSockets--; }); });
  page.on("pageerror", error => errors.push(error.message));
  let savedFault = null;
  const ready = async () => {
    await page.waitForFunction(() => document.querySelectorAll(".tile").length === 10 &&
      document.querySelector("#chip-api.ok") && document.querySelector("#chip-ws.ok"), {}, { timeout: 45000 });
  };
  const tile = id => page.locator(".tile").filter({ has: page.locator(".tile-id", { hasText: new RegExp("^" + id + "$") }) });
  try {
    await page.goto(base, { waitUntil: "domcontentloaded" });
    assert.equal((await context.request.get(API + "/api/sensors")).status(), 401);
    await page.locator("#login-user").fill(username);
    await page.locator("#login-password").fill(password);
    await page.locator("#login-submit").click();
    await ready();
    apiHeaders = await page.evaluate(() => ({ Authorization: "Bearer " + auth.token }));
    pass("Login through Undertow and rejection of unauthenticated API requests");
    await page.waitForFunction(() => Boolean(navigator.serviceWorker.controller), {}, { timeout: 45000 });
    pass("Real dashboard: 10 sensors, 3 sites, REST/WebSocket and active service worker");
    assert.equal(await page.locator(".site").count(), 3);
    const manifestResponse = await context.request.get(base + "/manifest.webmanifest");
    assert.match(manifestResponse.headers()["content-type"], /application\/manifest\+json/);
    const manifest = await manifestResponse.json();
    for (const icon of manifest.icons) assert.equal((await context.request.get(base + icon.src)).status(), 200);
    const sw = await context.request.get(base + "/sw.js");
    assert.match(sw.headers()["content-type"], /javascript/);
    assert.match(sw.headers()["cache-control"], /no-cache|no-store/);
    const cdp = await context.newCDPSession(page);
    const manifestCheck = await cdp.send("Page.getAppManifest");
    assert.deepEqual(manifestCheck.errors, []);
    // Browser.newContext is incognito, where Chrome deliberately blocks install.
    // Check actual install eligibility in a separate normal, temporary profile.
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), "coldtrace-install-check-"));
    const installContext = await chromium.launchPersistentContext(profile, launchOptions);
    try {
      const installPage = installContext.pages()[0] || await installContext.newPage();
      await installPage.goto(base, { waitUntil: "load" });
      await installPage.waitForFunction(() => Boolean(navigator.serviceWorker.controller), {}, { timeout: 45000 });
      const installCdp = await installContext.newCDPSession(installPage);
      const eligibility = await installCdp.send("Page.getInstallabilityErrors");
      assert.deepEqual(eligibility.installabilityErrors, []);
    } finally { await installContext.close(); }
    pass("Manifest, icon responses, worker headers and Chrome installability criteria");

    if (process.env.PWA_TEST_ACTIONS === "1") {
      const faultResponse = await context.request.get(API + "/api/dev/faults", { headers: apiHeaders });
      const data = await faultResponse.json();
      const faults = Array.isArray(data) ? data : data.sensors;
      const incidents = await (await context.request.get(API + "/api/incidents?status=open&limit=200", { headers: apiHeaders })).json();
      const selected = faults.find(s => s.fault === "none" && !incidents.some(i => i.sensorId === s.sensorId));
      assert(selected, "Need an idle sensor without an open incident");
      savedFault = { sensorId: selected.sensorId, fault: selected.fault };
      await tile(selected.sensorId).locator('[data-fault="door_open"]').click();
      await page.locator("#open-incidents .incident.m-door").filter({ has: page.locator(".who", { hasText: selected.sensorId }) })
        .waitFor({ state: "visible", timeout: 20000 });
      pass("Real door incident available for offline action and mobile form checks");
    }

    for (const [width, height] of [[1440, 900], [1024, 768], [768, 1024], [390, 844], [844, 390]]) {
      await page.setViewportSize({ width, height });
      await page.waitForTimeout(350);
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Horizontal overflow at " + width);
      const buttons = await page.locator(".faults button").evaluateAll(elements => elements.map(el => {
        const box = el.getBoundingClientRect(); return { width: box.width, height: box.height };
      }));
      assert(buttons.every(b => b.height >= 44 && b.width >= 42), "Touch target too small at " + width);
      assert(await page.locator(".tile").evaluateAll(elements => elements.every(el =>
        el.querySelector(".tile-head").getBoundingClientRect().top >= el.getBoundingClientRect().top - 1)), "Clipped sensor heading at " + width);
      const ack = page.locator(".btn-ack:visible").first();
      if (await ack.count()) {
        await ack.click();
        const form = page.locator(".ack-form:visible").first();
        await form.locator("textarea").fill("PWA layout check");
        assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Open form overflow at " + width);
        const rect = await form.boundingBox();
        assert(rect.x >= 0 && rect.x + rect.width <= width + 1);
        await form.getByRole("button", { name: "Annuler", exact: true }).click();
      }
      await page.screenshot({ path: path.join(output, "layout-" + width + ".png"), fullPage: true });
      pass("Responsive layout, touch targets and incident actions at " + width + "x" + height);
    }

    const cached = await page.evaluate(async () => {
      const entries = [];
      for (const name of await caches.keys()) for (const request of await (await caches.open(name)).keys()) entries.push(request.url);
      return entries;
    });
    assert(cached.some(url => new URL(url).pathname === "/monitor.js"));
    assert(cached.every(url => !/^\/(api|ws)(\/|$)/.test(new URL(url).pathname)));
    pass("Cache Storage contains shell assets and no operational API/WebSocket data");

    await context.setOffline(true);
    await page.waitForFunction(() => document.querySelector("#connection-status").textContent.includes("Browser offline"));
    assert(await page.locator(".t-val").evaluateAll(elements => elements.every(e => e.textContent === "--")));
    assert(await page.locator(".faults button, .btn-ack, .ack-form [type=submit], #clear-faults").evaluateAll(elements => elements.every(e => e.disabled)));
    const blocked = await page.evaluate(async () => {
      try { await getJson("/api/dev/faults/clear", { method: "POST" }); return false; } catch { return true; }
    });
    assert(blocked);
    pass("Offline readings hidden, controls disabled and attempted mutation blocked before fetch");
    await page.reload({ waitUntil: "domcontentloaded" });
    // CDP offline emulation can report navigator.onLine=true after navigation.
    // Both states must still show unavailable monitoring, without saved data.
    await page.waitForFunction(() => document.querySelector("#connection-status").textContent.includes("Live monitoring unavailable"), {}, { polling: 100, timeout: 10000 });
    assert.equal(await page.locator(".tile").count(), 0);
    pass("Offline reload serves shell without resurrecting saved live measurements or incidents");
    await context.setOffline(false);
    await ready();
    await page.waitForFunction(() => document.querySelectorAll("#feed li").length > 0);
    pass("Online recovery refreshes measurements, incidents, history and operational controls");

    await page.route("**/api/**", route => route.abort("connectionrefused"));
    await page.evaluate(() => poll());
    await page.waitForFunction(() => document.querySelector("#connection-status").textContent.includes("Backend unavailable"));
    assert.equal(await page.evaluate(() => navigator.onLine), true);
    assert(await page.locator(".t-val").evaluateAll(elements => elements.every(e => e.textContent === "--")));
    assert(await page.locator(".faults button, .btn-ack").evaluateAll(elements => elements.every(e => e.disabled)));
    pass("Backend network failure is detected even when navigator.onLine remains true");
    await page.unroute("**/api/**");
    await page.evaluate(() => poll());
    await ready();
    pass("Backend recovery restores the dashboard without reload or duplicated history");
    await page.evaluate(() => alertSocket.close());
    await page.waitForFunction(() => document.querySelector("#connection-status").textContent.includes("Alert link disconnected"), {}, { polling: 50 });
    assert(await page.evaluate(() => canOperate()));
    pass("WebSocket-only interruption is distinct from REST failure; REST-backed actions remain available");
    await ready();
    assert(activeSockets <= 1, "Duplicate WebSocket connections after recovery");
    pass("Alert link reconnects with at most one active WebSocket");
    const keys = await page.locator("#feed li").evaluateAll(items => items.map(i => i.dataset.event));
    assert.equal(keys.length, new Set(keys).size);
    assert.deepEqual(errors, []);
    pass("No duplicated history events or uncaught page errors");
    await page.locator("#logout").click();
    await page.waitForFunction(() => !document.querySelector("#login").hidden);
    assert.equal(await page.locator(".tile").count(), 0);
    assert.equal(await page.evaluate(() => sessionStorage.getItem("coldtrace.session")), null);
    pass("Logout clears the session and displayed operational data");
  } finally {
    await context.setOffline(false);
    await page.unroute("**/api/**");
    if (savedFault) {
      const response = await context.request.post(API + "/api/dev/faults", { data: savedFault, headers: apiHeaders });
      assert.equal(response.status(), 200, "Restore tested sensor");
    }
    await context.close();
  }

  // Isolated localhost origin: exercise two shell deployments without changing
  // production files, deleting application data, or replacing live API readings.
  let version = "update-a";
  const types = { ".js": "application/javascript", ".css": "text/css", ".html": "text/html",
    ".webmanifest": "application/manifest+json", ".png": "image/png" };
  const server = http.createServer((request, response) => {
    const route = new URL(request.url, "http://localhost").pathname;
    const filename = path.resolve(root, "." + (route === "/" ? "/index.html" : route));
    if (!filename.startsWith(root + path.sep) || !fs.existsSync(filename) || route.startsWith("/api/") || route === "/ws/alerts") {
      response.writeHead(503); response.end(); return;
    }
    let content = fs.readFileSync(filename);
    // This origin tests only pwa.js and sw.js: it has no IAM or operational backend.
    // Leave the login overlay hidden instead of starting monitor.js against a fake API.
    if (route === "/" || route === "/index.html") content = Buffer.from(content.toString().replace(/<script src="monitor\.js"( defer)?><\/script>/, ""));
    if (route === "/sw.js") content = Buffer.from(content.toString().replace(/CACHE_PREFIX \+ "[^"]+"/, 'CACHE_PREFIX + "' + version + '"'));
    if (route === "/monitor.css") content = Buffer.concat([content, Buffer.from("\n/* " + version + " */")]);
    response.writeHead(200, { "Content-Type": types[path.extname(filename)] || "application/octet-stream", "Cache-Control": "no-store" });
    response.end(content);
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const updateContext = await browser.newContext();
  try {
    const updatePage = await updateContext.newPage();
    await observeWorkerLogs(updateContext, updatePage);
    updatePage.on("pageerror", error => errors.push(error.message));
    const origin = "http://127.0.0.1:" + server.address().port;
    await updatePage.goto(origin, { waitUntil: "load" });
    await updatePage.waitForFunction(() => Boolean(navigator.serviceWorker.controller), {}, { timeout: 45000 });
    await updatePage.evaluate(async () => { await caches.open("coldtrace-shell-obsolete"); await caches.open("unrelated-cache"); });
    version = "update-b";
    await updatePage.evaluate(async () => (await navigator.serviceWorker.getRegistration()).update());
    await updatePage.waitForFunction(async () => Boolean((await navigator.serviceWorker.getRegistration()).waiting), {}, { timeout: 20000 });
    await updatePage.waitForFunction(() => !document.querySelector("#pwa-update").hidden);
    await updatePage.evaluate(() => { const form = document.createElement("form"); form.className = "ack-form"; form.id = "test-unsaved"; document.body.append(form); });
    await updatePage.locator("#update-app").click();
    assert(await updatePage.evaluate(async () => Boolean((await navigator.serviceWorker.getRegistration()).waiting)));
    pass("New worker waits and update action protects an unfinished acknowledgement form");
    await updatePage.locator("#test-unsaved").evaluate(form => {
      const wrapper = document.createElement("div"); wrapper.hidden = true;
      form.replaceWith(wrapper); wrapper.append(form);
    }); // A completed acknowledgement can leave its form inside a hidden parent.
    await updatePage.locator("#update-app").click();
    await updatePage.waitForFunction(async () => (await caches.keys()).includes("coldtrace-shell-update-b"), {}, { timeout: 20000 });
    await updatePage.waitForFunction(async () => !(await caches.keys()).includes("coldtrace-shell-update-a"));
    const names = await updatePage.evaluate(() => caches.keys());
    assert(!names.includes("coldtrace-shell-obsolete"));
    assert(names.includes("unrelated-cache"));
    assert((await (await updateContext.request.get(origin + "/monitor.css")).text()).includes("update-b"));
    await updateContext.setOffline(true);
    await updatePage.reload({ waitUntil: "domcontentloaded" });
    assert(await updatePage.evaluate(async () => (await (await fetch("/monitor.css")).text()).includes("update-b")));
    pass("Explicit update activates new offline assets and removes only obsolete ColdTrace caches");
    assert.deepEqual(workerErrors, []);
    pass("No service-worker errors reported in Chrome worker logs");
  } finally {
    await updateContext.close();
    await new Promise(resolve => server.close(resolve));
    await browser.close();
    fs.writeFileSync(path.join(output, "pwa-results.json"), JSON.stringify({ checks, errors, workerErrors }, null, 2));
  }
  assert.deepEqual(errors, []);
})().catch(error => { console.error(error); process.exit(1); });

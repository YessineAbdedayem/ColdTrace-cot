// Real HTTP checks; optional actions require an explicitly isolated test stack.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const base = process.env.FRONT_OFFICE_URL || "http://localhost:3000";
const root = path.join(__dirname, "..");
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function waitFor(fn, description) {
  for (let i = 0; i < 90; i++) {
    try { const result = await fn(); if (result) return result; } catch {}
    await pause(1000);
  }
  throw new Error("Timed out: " + description);
}
async function request(url, token, method = "GET", body) {
  return fetch(base + url, {method, redirect: "manual",
    headers: {...(token ? {Authorization: "Bearer " + token} : {}),
      ...(body ? {"Content-Type": "application/json"} : {})},
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(10000)});
}
(async () => {
  await waitFor(async () => (await request("/")).status === 200, "frontend startup");
  const assets = ["/", "/index.html", "/monitor.js", "/monitor.css", "/pwa.js", "/sw.js",
    "/manifest.webmanifest", "/icons/icon-192.png", "/icons/icon-512.png",
    "/icons/maskable-512.png", "/icons/apple-touch-icon.png"];
  for (const asset of assets) {
    const response = await request(asset);
    assert.equal(response.status, 200, asset);
    assert.match(response.headers.get("cache-control"), /no-store/, asset);
    assert.equal(response.headers.get("x-content-type-options"), "nosniff");
    const local = path.join(root, asset === "/" ? "index.html" : asset.slice(1));
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), fs.readFileSync(local), asset);
  }
  assert.match((await request("/manifest.webmanifest")).headers.get("content-type"), /application\/manifest\+json/);
  assert.match((await request("/sw.js")).headers.get("content-type"), /javascript/);
  assert.equal((await request("/sw.js")).headers.get("service-worker-allowed"), "/");
  for (const asset of ["/missing.js", "/missing-route", "/README.md", "/Dockerfile", "/wildfly/frontend.properties", "/.env", "/tests/pwa-browser.cjs", "/icons/"]) {
    const status = (await request(asset)).status;
    assert(asset === "/icons/" ? [403, 404].includes(status) : status === 404,
      "No fallback, listing or source exposure: " + asset + " (" + status + ")");
  }
  // Check virtual-host alias as it will be received from the optional external proxy.
  assert.equal((await fetch(base + "/", {headers: {Host: "coldtrace.test"}})).status, 200);
  console.log("PASS static assets, bytes, MIME, headers, virtual-host alias and protected filesystem paths");
  if (process.env.HOSTING_STATIC_ONLY === "1") return;
  await waitFor(async () => (await request("/api/health")).status === 200, "API startup");
  assert.equal((await request("/api/sensors")).status, 401);
  assert.equal((await request("/api/sensors", "invalid")).status, 401);
  const password = process.env.PWA_TEST_PASSWORD;
  assert(password, "PWA_TEST_PASSWORD required for real IAM checks");
  async function login(username) {
    const response = await request("/auth/login", null, "POST", {username, password});
    assert.equal(response.status, 200, "login " + username);
    return (await response.json()).accessToken;
  }
  const token = await waitFor(async () => {
    try { return await login(process.env.PWA_TEST_USER || "operateur"); } catch { return null; }
  }, "IAM login");
  assert.equal((await request("/auth/me", token)).status, 200);
  const sensors = await (await request("/api/sensors", token)).json();
  assert.equal(sensors.length, 10);
  assert.equal(new Set(sensors.map(s => s.siteId)).size, 3);
  await waitFor(async () => (await (await request("/api/sensors", token)).json()).every(s => s.latest?.ts), "live readings from MQTT");
  const before = await (await request("/api/sensors", token)).json();
  assert(before.every(s => typeof s.latest.temperature === "number" && typeof s.latest.humidity === "number"));
  await waitFor(async () => (await (await request("/api/sensors", token)).json())
    .some(s => s.latest?.ts !== before.find(old => old.id === s.id)?.latest?.ts), "fresh temperature and humidity updates");
  assert.equal((await request("/api/incidents?status=open", token)).status, 200);
  assert.equal((await request("/api/gateways", token)).status, 200);
  console.log("PASS real IAM login, auth forwarding, 401s, ten live sensors, three sites, incidents and gateways");
  if (process.env.HOSTING_MUTATIONS !== "1") return;
  // Everything below operates solely on hosting.compose.yml's disposable data.
  const quality = await login("qualite");
  assert.equal((await request("/api/dev/faults", quality, "POST", {sensorId: "S01", fault: "door_open"})).status, 403);
  let socket;
  let eventSeen = false;
  try {
    socket = new WebSocket(base.replace(/^http/, "ws") + "/ws/alerts?access_token=" + encodeURIComponent(token));
    socket.addEventListener("message", () => { eventSeen = true; });
    await new Promise((resolve, reject) => {
      socket.addEventListener("open", resolve, {once: true});
      socket.addEventListener("error", reject, {once: true});
      setTimeout(() => reject(new Error("WebSocket upgrade timeout")), 15000).unref();
    });
    assert.equal((await request("/api/dev/faults", token, "POST", {sensorId: "S01", fault: "door_open"})).status, 200);
    const incident = await waitFor(async () => (await (await request("/api/incidents?status=open&limit=200", token)).json())
      .find(i => i.sensorId === "S01" && i.metric === "door"), "door incident");
    await waitFor(() => eventSeen, "live WebSocket event");
    const ack = await request("/api/incidents/" + incident.id + "/ack", token, "POST", {comment: "Isolated hosting integration check"});
    assert.equal(ack.status, 200);
    assert.equal((await ack.json()).acknowledgedBy, process.env.PWA_TEST_USER || "operateur");
    assert.equal((await request("/api/dev/faults", token, "POST", {sensorId: "S01", fault: "none"})).status, 200);
    await waitFor(async () => !(await (await request("/api/incidents?status=open&limit=200", token)).json())
      .some(i => i.id === incident.id), "incident recovery");
    console.log("PASS quality role rejection, authorized controls, real WebSocket event, signed acknowledgement and incident recovery");
  } finally {
    socket?.close();
    await request("/api/dev/faults", token, "POST", {sensorId: "S01", fault: "none"});
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });

// Real optional ingress check; uses no simulator mutations and never prints tokens.
const assert = require("node:assert/strict");
const http = require("node:http");
const crypto = require("node:crypto");
const base = process.env.HOSTING_PROXY_URL || "http://127.0.0.1:13003";
const host = "coldtrace.test";
async function request(route, token, body) {
  return new Promise((resolve, reject) => {
    const req = http.request(new URL(route, base), {method: body ? "POST" : "GET",
      headers: {Host: host, ...(token ? {Authorization: "Bearer " + token} : {}),
        ...(body ? {"Content-Type": "application/json"} : {})}}, response => {
      const chunks = [];
      response.on("data", chunk => chunks.push(chunk));
      response.on("end", () => resolve({status: response.statusCode, headers: response.headers,
        json: () => JSON.parse(Buffer.concat(chunks).toString())}));
    });
    req.on("error", reject);
    req.setTimeout(15000, () => req.destroy(new Error("Proxy HTTP timeout")));
    req.end(body ? JSON.stringify(body) : undefined);
  });
}
(async () => {
  const manifest = await request("/manifest.webmanifest");
  assert.equal(manifest.status, 200);
  assert.match(manifest.headers["content-type"], /application\/manifest\+json/);
  assert.equal((await request("/api/sensors")).status, 401);
  const login = await request("/auth/login", null, {username: process.env.PWA_TEST_USER || "operateur", password: process.env.PWA_TEST_PASSWORD});
  assert.equal(login.status, 200);
  const token = (await login.json()).accessToken;
  assert.equal((await request("/api/sensors", token)).status, 200);
  const key = crypto.randomBytes(16).toString("base64");
  await new Promise((resolve, reject) => {
    const req = http.request(new URL("/ws/alerts?access_token=" + encodeURIComponent(token), base), {
      headers: {Host: host, Connection: "Upgrade", Upgrade: "websocket",
        "Sec-WebSocket-Key": key, "Sec-WebSocket-Version": "13"}});
    req.on("upgrade", (response, socket) => {
      try {
        assert.equal(response.statusCode, 101);
        const expected = crypto.createHash("sha1").update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
        assert.equal(response.headers["sec-websocket-accept"], expected);
        socket.destroy();
        resolve();
      } catch (e) { socket.destroy(); reject(e); }
    });
    req.on("response", response => { response.resume(); reject(new Error("Expected WebSocket upgrade, got " + response.statusCode)); });
    req.on("error", reject);
    req.setTimeout(15000, () => req.destroy(new Error("Proxy upgrade timeout")));
    req.end();
  });
  console.log("PASS external proxy: domain routing, manifest MIME, login, auth, 401 and authenticated WebSocket upgrade");
})().catch(error => { console.error(error.message); process.exitCode = 1; });

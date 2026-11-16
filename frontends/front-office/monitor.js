// ColdTrace monitor: polls the REST API every 5 s, listens to live alerts on the WebSocket,
// and drives the simulator's faults through the API's dev proxy.
"use strict";

const POLL_MS = 5000;
const SILENT_AFTER_S = 60;
const METRIC_LABEL = {
  temperature: "Temperature", humidity: "Humidity", door: "Door open", silence: "No signal",
  early_warning: "Early warning"
};
// why an early warning closed (incident.closeReason)
const CLOSE_REASON = {
  calm: "closed: forecast back to normal",
  replaced: "replaced by the real alert",
  no_forecast: "closed: no forecast for 3 min"
};
// incidents written before the "metric" field existed have no metric: show them generically
const metricLabel = m => METRIC_LABEL[m] || m || "Incident";
const FAULT_LABEL = { door_open: "door open", temp_rise: "thawing (cooling failure)", silent: "muted", none: "normal" };

const $ = (sel, root = document) => root.querySelector(sel);
const tiles = new Map();       // sensorId -> { el, ... }
const incidentRows = new Map(); // incident id -> persistent row, preserving an in-progress form across polls
let faultsEnabled = null;      // null = unknown yet
let simFaults = {};            // sensorId -> active fault
let openIncidents = [];
let firstRender = true;
const connection = { api: false, ws: false, lastSuccess: null };
let knownGateways = [];
let connectionEpoch = 0;
let historyNeedsRefresh = true;
const requests = new Set();
const pendingSensors = new Set();
const pendingAcks = new Set();
let clearPending = false;

// ---------- login (middleware/iam) ----------
// The session lives in sessionStorage: it ends when the tab closes. The token itself is checked by the API.
const SESSION_KEY = "coldtrace.session";
const auth = loadSession();
let expiryTimer = null;

function loadSession() {
  try {
    const s = JSON.parse(sessionStorage.getItem(SESSION_KEY) || "null");
    if (s && s.token && s.user && Date.parse(s.expiresAt) > Date.now() + 5000) return s;
  } catch (e) { /* unreadable: start signed out */ }
  sessionStorage.removeItem(SESSION_KEY);
  return { token: null, user: null, expiresAt: null };
}
const hasRole = (...roles) => Boolean(auth.user?.roles?.some(r => roles.includes(r)));
// the simulator's fault buttons: operating the equipment (the API enforces the same rule)
const canFault = () => hasRole("admin", "operator");

const canOperate = () => navigator.onLine && connection.api && Boolean(auth.token);

function updateControls() {
  for (const [id, t] of tiles) {
    t.buttons.forEach(b => { b.disabled = !canOperate() || !canFault() || faultsEnabled !== true || clearPending || pendingSensors.has(id); });
  }
  for (const [id, row] of incidentRows) {
    const disabled = !canOperate() || pendingAcks.has(id);
    row.ackButton.disabled = disabled;
    row.submit.disabled = disabled;
    row.comment.disabled = disabled;
    row.cancel.disabled = pendingAcks.has(id);
  }
  $("#clear-faults").disabled = !canOperate() || !canFault() || faultsEnabled !== true || clearPending || pendingSensors.size > 0;
  document.body.dataset.busy = String(clearPending || pendingSensors.size > 0 || pendingAcks.size > 0);
}

function updateConnection() {
  setChip("chip-api", canOperate(), canOperate() ? "API online" : "API unavailable");
  setChip("chip-ws", connection.ws && navigator.onLine, connection.ws && navigator.onLine ? "Alerts connected" : "Alerts disconnected");
  const status = $("#connection-status");
  const offlineGateways = knownGateways.filter(g => g.status !== "online");
  let message;
  if (!auth.token) message = "Signed out. Sign in to see live data.";
  else if (!navigator.onLine) message = "Browser offline. Live monitoring unavailable. Readings are hidden and operational controls are disabled.";
  else if (!connection.api) message = "Backend unavailable. Live monitoring unavailable. Readings are hidden and operational controls are disabled; retrying.";
  else if (!connection.ws) message = "Alert link disconnected. Measurements refresh every 5 s; live incident alerts are unavailable. Reconnecting.";
  else if (offlineGateways.length) message = "Gateway disconnected: " + offlineGateways.map(g => g.gatewayId || g.siteId).join(", ") + ". Check sensor ages; the latest readings may be old.";
  else message = "Live monitoring connected. Measurements refresh every 5 s; incident alerts arrive in real time.";
  if (status.textContent !== message) status.textContent = message;
  status.classList.toggle("disconnected", !canOperate());
  status.classList.toggle("degraded", canOperate() && (!connection.ws || offlineGateways.length > 0));
  $("#log-state").textContent = canOperate()
    ? "Current incident list · last refresh " + hhmmss(connection.lastSuccess)
    : connection.lastSuccess ? "Last received " + hhmmss(connection.lastSuccess) + " · incidents below are not current."
      : "Live incidents unavailable. No operational data is saved offline.";
  if (!canOperate()) {
    for (const t of tiles.values()) {
      t.el.classList.add("disconnected");
      t.temp.textContent = "--";
      t.hum.textContent = "--";
      t.door.textContent = "--";
      t.seen.textContent = t.lastTs ? "Last " + hhmmss(t.lastTs) : "--";
      t.flag.textContent = "Not live";
      t.line.setAttribute("points", "");
      t.band.setAttribute("height", "0");
    }
    $("#open-count").textContent = "? open";
    $("#open-count").classList.remove("hot");
    $("#open-empty").textContent = "Live incidents unavailable.";
    $("#incidents-nav").textContent = "Incidents (unavailable)";
    renderGateways(knownGateways);
  }
  updateControls();
}

function backendUnavailable() {
  connection.api = false;
  faultsEnabled = false;
  historyNeedsRefresh = true;
  updateConnection();
}

function requestFailed(error) {
  if (!error.status || error.status >= 500) backendUnavailable();
}

// ---------- helpers ----------

async function getJson(url, options) {
  const mutation = options?.method && options.method !== "GET";
  if (mutation && !canOperate()) throw new Error("Live connection unavailable. No action was queued; reconnect and check the current state.");
  const controller = new AbortController();
  const epoch = connectionEpoch;
  requests.add(controller);
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const headers = { ...(options?.headers || {}), ...(auth.token ? { Authorization: "Bearer " + auth.token } : {}) };
    const r = await fetch(url, { ...options, headers, cache: "no-store", signal: controller.signal });
    let data;
    try { data = await r.json(); } catch (e) { data = null; }
    if (r.status === 401) {
      // expired, revoked, or signed by an old key: the user must sign in again
      signOut("Your session has ended. Sign in again.");
      const err = new Error("Signed out.");
      err.status = 401;
      throw err;
    }
    if (!r.ok) {
      const message = data && typeof data.error === "string" ? data.error : url + " returned " + r.status;
      const err = new Error(message);
      err.status = r.status;
      throw err;
    }
    if (epoch !== connectionEpoch || !navigator.onLine) throw new Error("Connection changed. Refresh the current state before trying an action again.");
    return data;
  } finally {
    clearTimeout(timer);
    requests.delete(controller);
  }
}

function fmt(n, digits = 1) {
  return n === null || n === undefined ? "--" : Number(n).toFixed(digits);
}

function hhmmss(iso) {
  return iso ? new Date(iso).toLocaleTimeString([], { hour12: false }) : "--:--:--";
}

function ago(iso) {
  if (!iso) return "never";
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 60) return s + " s";
  if (s < 3600) return Math.floor(s / 60) + " min";
  return Math.floor(s / 3600) + " h";
}

function siteName(sensor) {
  // The dev seed names sensors "<site name> <sensorId>".
  const name = sensor.name || "";
  return name.endsWith(" " + sensor.id) ? name.slice(0, -sensor.id.length - 1) : name;
}

function limitText(limits) {
  if (!limits) return "no limits";
  const { temperatureMin: lo, temperatureMax: hi } = limits;
  if (lo != null && hi != null) return `${fmt(lo, 0)} … ${fmt(hi, 0)} °C`;
  if (hi != null) return `≤ ${fmt(hi, 0)} °C`;
  if (lo != null) return `≥ ${fmt(lo, 0)} °C`;
  return "no limits";
}

function setChip(id, ok, label) {
  const chip = document.getElementById(id);
  chip.classList.toggle("ok", ok);
  chip.classList.toggle("bad", !ok);
  if (label) chip.lastChild.textContent = label;
}

// ---------- sensors ----------

function buildSites(sensors) {
  const root = $("#sites");
  root.textContent = "";
  const bySite = new Map();
  for (const s of sensors) {
    if (!bySite.has(s.siteId)) bySite.set(s.siteId, []);
    bySite.get(s.siteId).push(s);
  }
  let siteIndex = 0;
  let tileIndex = 0;
  for (const [siteId, list] of bySite) {
    const site = document.createElement("section");
    site.className = "site";
    site.style.setProperty("--s", siteIndex++);

    const head = document.createElement("header");
    head.className = "site-head";
    const code = document.createElement("h2");
    code.className = "site-code stencil";
    code.textContent = siteId.replace(/^site/i, "Site ");
    const name = document.createElement("span");
    name.className = "site-name";
    name.textContent = siteName(list[0]);
    const limit = document.createElement("span");
    limit.className = "site-limit";
    limit.textContent = `${list[0].profile || "?"} · ${limitText(list[0].limits)}`;
    head.append(code, name, limit);

    const grid = document.createElement("div");
    grid.className = "tiles";
    for (const s of list) grid.append(buildTile(s, tileIndex++));
    site.append(head, grid);
    root.append(site);
  }
  setTimeout(() => tiles.forEach(t => t.el.classList.add("settled")), 1800);
}

function buildTile(sensor, index) {
  const el = $("#tile-tpl").content.firstElementChild.cloneNode(true);
  el.setAttribute("aria-label", "Sensor " + sensor.id);
  el.style.setProperty("--i", index);
  $(".tile-id", el).textContent = sensor.id;
  $(".tile-name", el).textContent = siteName(sensor);
  el.querySelectorAll(".faults button").forEach(b => {
    b.setAttribute("aria-label", b.textContent + " — sensor " + sensor.id);
    b.addEventListener("click", () => setFault(sensor.id, b.dataset.fault, b));
  });
  tiles.set(sensor.id, {
    el,
    temp: $(".t-val", el), hum: $(".h-val", el), door: $(".d-val", el), seen: $(".s-val", el),
    flag: $(".tile-flag", el), band: $(".spark-band", el), line: $(".spark-line", el),
    buttons: el.querySelectorAll(".faults button"),
    lastTs: null
  });
  return el;
}

function updateTile(sensor) {
  const t = tiles.get(sensor.id);
  if (!t) return;
  t.el.classList.remove("disconnected");
  const r = sensor.latest || {};
  const open = openIncidents.filter(i => i.sensorId === sensor.id);
  const metricOpen = m => open.some(i => i.metric === m);
  const silentFor = r.ts ? (Date.now() - new Date(r.ts).getTime()) / 1000 : Infinity;

  t.temp.textContent = fmt(r.temperature);
  t.hum.textContent = r.humidity == null ? "--" : fmt(r.humidity, 0) + " %";
  t.door.textContent = r.doorOpen == null ? "--" : r.doorOpen ? "OPEN" : "shut";
  t.seen.textContent = ago(r.ts);

  const alarm = metricOpen("temperature") || metricOpen("humidity");
  const door = metricOpen("door") || r.doorOpen === true;
  const silent = metricOpen("silence") || silentFor > SILENT_AFTER_S;
  // the model predicts the limit will be crossed; the real alert replaces it
  const forecast = metricOpen("early_warning") && !alarm;
  t.el.classList.toggle("alarm", alarm);
  t.el.classList.toggle("door-open", door);
  t.el.classList.toggle("silent", silent);
  t.el.classList.toggle("forecast", forecast);
  t.flag.textContent = alarm ? "Out of range" : silent ? "No signal" : forecast ? "Early warning"
    : door ? "Door open" : r.replayed ? "replayed" : "";

  // a fresh reading makes the tile breathe once
  if (r.ts && t.lastTs && r.ts !== t.lastTs && t.el.classList.contains("settled")) {
    t.el.classList.remove("pulse");
    void t.el.offsetWidth;
    t.el.classList.add("pulse");
  }
  t.lastTs = r.ts || t.lastTs;

  drawSpark(t, sensor.recentTemperatures || [], sensor.limits);

  const active = simFaults[sensor.id] || "none";
  t.buttons.forEach(b => {
    b.classList.toggle("active", b.dataset.fault === active);
    b.setAttribute("aria-pressed", String(b.dataset.fault === active));
  });
}

function drawSpark(t, values, limits) {
  if (values.length < 2) {
    t.line.setAttribute("points", "");
    t.band.setAttribute("height", "0");
    return;
  }
  const lo = limits ? limits.temperatureMin : null;
  const hi = limits ? limits.temperatureMax : null;
  let min = Math.min(...values), max = Math.max(...values);
  if (hi != null) { min = Math.min(min, hi - 1); max = Math.max(max, hi + 0.5); }
  if (lo != null) { min = Math.min(min, lo - 0.5); }
  const span = Math.max(max - min, 0.5);
  const y = v => 32 - ((v - min) / span) * 30;
  const step = 120 / (values.length - 1);
  t.line.setAttribute("points", values.map((v, i) => `${(i * step).toFixed(1)},${y(v).toFixed(1)}`).join(" "));
  // allowed band
  const top = hi != null ? y(hi) : 0;
  const bottom = lo != null ? y(lo) : 34;
  t.band.setAttribute("y", Math.max(0, top).toFixed(1));
  t.band.setAttribute("height", Math.max(0, bottom - top).toFixed(1));
}

// ---------- incidents ----------

function incidentDetail(i) {
  if (i.metric === "door") return "door open since " + hhmmss(i.openedAt);
  if (i.metric === "silence") return `no reading for ${fmt(i.peakValue, 0)} s`;
  if (i.metric === "early_warning") {
    // value = temperature when it opened, peakValue = highest predicted maximum, thresholdMax = the limit
    const model = i.modelVersion ? ` · model v${i.modelVersion}` : "";
    return `forecast ${fmt(i.peakValue)} °C within ${i.horizonMin || 10} min · limit ${fmt(i.thresholdMax)} °C`
      + ` · was ${fmt(i.value)} °C${model}`;
  }
  const unit = i.metric === "humidity" ? " %" : " °C";
  const limit = i.thresholdMax != null && i.peakValue > i.thresholdMax ? "max " + fmt(i.thresholdMax) : "min " + fmt(i.thresholdMin);
  return `peak ${fmt(i.peakValue)}${unit} · ${limit}${unit}`;
}

function renderOpen() {
  const list = $("#open-incidents");
  const activeIds = new Set();
  openIncidents.forEach((incident, index) => {
    activeIds.add(incident.id);
    let row = incidentRows.get(incident.id);
    if (!row) {
      row = createIncidentRow(incident);
      incidentRows.set(incident.id, row);
    }
    updateIncidentRow(row, incident);
    const current = list.children[index];
    if (current !== row.el) list.insertBefore(row.el, current || null);
  });
  for (const [id, row] of incidentRows) {
    if (!activeIds.has(id)) {
      row.el.remove();
      incidentRows.delete(id);
    }
  }
  const n = openIncidents.length;
  $("#open-empty").hidden = n > 0;
  const count = $("#open-count");
  count.textContent = n + " open";
  count.classList.toggle("hot", n > 0);
  $("#open-empty").textContent = "All clear. Nothing out of range.";
  $("#incidents-nav").textContent = `Incidents (${n})`;
  document.title = (n ? `(${n}) ` : "") + "ColdTrace Monitor";
}

function createIncidentRow(incident) {
  const el = document.createElement("li");
  el.classList.add("incident", "m-" + incident.metric);

  const who = document.createElement("span"); who.className = "who";
  const what = document.createElement("span"); what.className = "what";
  const when = document.createElement("span"); when.className = "when";
  const detail = document.createElement("span"); detail.className = "detail";
  const state = document.createElement("span"); state.className = "incident-state";
  const acknowledgement = document.createElement("div"); acknowledgement.className = "acknowledgement";
  const action = document.createElement("div"); action.className = "ack-action";
  const ackButton = document.createElement("button");
  ackButton.type = "button";
  ackButton.className = "btn-ack";
  ackButton.textContent = "Acquitter";
  ackButton.setAttribute("aria-label", `Acquitter ${metricLabel(incident.metric)} — sensor ${incident.sensorId}`);

  const form = document.createElement("form");
  form.className = "ack-form";
  form.hidden = true;
  // the API signs the acknowledgement with the logged-in user: nothing to type
  const signer = document.createElement("p");
  signer.className = "ack-signer";
  signer.textContent = `Signé par ${auth.user?.name || auth.user?.username || "vous"} (${auth.user?.username || "?"})`;
  const commentLabel = document.createElement("label");
  commentLabel.append(document.createTextNode("Commentaire (facultatif)"));
  const comment = document.createElement("textarea");
  comment.maxLength = 500;
  comment.rows = 2;
  commentLabel.append(comment);
  const error = document.createElement("p");
  error.className = "ack-error";
  error.setAttribute("role", "alert");
  error.hidden = true;
  const controls = document.createElement("div");
  controls.className = "ack-controls";
  const submit = document.createElement("button");
  submit.type = "submit";
  submit.textContent = "Confirmer";
  const cancel = document.createElement("button");
  cancel.type = "button";
  cancel.textContent = "Annuler";
  controls.append(submit, cancel);
  form.append(signer, commentLabel, error, controls);

  const row = { el, who, what, when, detail, state, acknowledgement, action, ackButton, form, comment, error, submit, cancel };

  ackButton.addEventListener("click", () => {
    if (!canOperate()) return;
    form.hidden = false;
    ackButton.hidden = true;
    comment.focus();
  });
  cancel.addEventListener("click", () => {
    form.hidden = true;
    ackButton.hidden = false;
    error.hidden = true;
    error.textContent = "";
    ackButton.focus();
  });
  form.addEventListener("submit", event => {
    event.preventDefault();
    acknowledgeIncident(incident.id, row);
  });

  action.append(ackButton, form);
  el.append(who, what, when, detail, state, acknowledgement, action);
  return row;
}

function updateIncidentRow(row, incident) {
  row.el.classList.toggle("acknowledged", Boolean(incident.acknowledgedAt));
  row.who.textContent = incident.sensorId || "Unknown sensor";
  row.what.textContent = metricLabel(incident.metric);
  row.when.textContent = ago(incident.openedAt);
  row.detail.textContent = incidentDetail(incident);
  row.state.textContent = incident.acknowledgedAt
    ? "● OPEN · ✓ ACKNOWLEDGED"
    : "● OPEN · AWAITING ACKNOWLEDGEMENT";
  row.acknowledgement.textContent = "";
  row.action.hidden = Boolean(incident.acknowledgedAt);

  if (incident.acknowledgedAt) {
    const operator = incident.acknowledgedBy || "Unknown operator";
    const summary = document.createElement("span");
    summary.textContent = `✓ Acknowledged by ${operator} at ${hhmmss(incident.acknowledgedAt)}`;
    row.acknowledgement.append(summary);
    if (incident.comment) {
      const comment = document.createElement("span");
      comment.textContent = `Comment: ${incident.comment}`;
      row.acknowledgement.append(comment);
    }
  }
}

async function acknowledgeIncident(id, row) {
  if (!canOperate() || pendingAcks.has(id)) return;
  pendingAcks.add(id);
  updateControls();
  row.error.hidden = true;
  row.error.textContent = "";
  row.submit.disabled = true;
  row.cancel.disabled = true;
  row.comment.disabled = true;
  try {
    const incident = await getJson(`/api/incidents/${encodeURIComponent(id)}/ack`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ comment: row.comment.value })
    });
    openIncidents = openIncidents.map(item => item.id === incident.id ? incident : item);
    updateIncidentRow(row, incident);
    poll();
  } catch (e) {
    requestFailed(e);
    row.error.textContent = e.message;
    row.error.hidden = false;
  } finally {
    pendingAcks.delete(id);
    updateControls();
  }
}

function addFeed(kind, incident, at, flash) {
  const feed = $("#feed");
  const key = incident.id + ":" + kind;
  if ([...feed.children].some(item => item.dataset.event === key)) return;
  const li = document.createElement("li");
  li.dataset.event = key;
  li.dataset.at = at || "";
  li.className = kind + (flash ? " flash" : "");
  const time = document.createElement("span");
  time.textContent = hhmmss(at);
  const text = document.createElement("span");
  const b = document.createElement("b");
  if (kind === "acknowledged") {
    b.textContent = `✓ ${incident.sensorId || "Unknown sensor"} ${metricLabel(incident.metric)}`;
    text.append(b, document.createTextNode(` acknowledged by ${incident.acknowledgedBy || "operator"}`));
  } else {
    b.textContent = (kind === "opened" ? "▲ " : "▼ ") + (incident.sensorId || "Unknown sensor") + " " + metricLabel(incident.metric);
    const closedText = CLOSE_REASON[incident.closeReason] ? " " + CLOSE_REASON[incident.closeReason] : " closed";
    text.append(b, document.createTextNode(kind === "opened" ? " opened" : closedText));
    if (incident.metric === "early_warning") li.classList.add("m-early_warning");
  }
  li.append(time, text);
  feed.prepend(li);
  [...feed.children].sort((a, b) => b.dataset.at.localeCompare(a.dataset.at)).forEach(item => feed.append(item));
  while (feed.children.length > 30) feed.lastChild.remove();
  if (flash) setTimeout(() => li.classList.remove("flash"), 2500);
}

async function loadHistory() {
  try {
    const recent = await getJson("/api/incidents?limit=12");
    // oldest first so the newest ends on top
    const events = [];
    for (const i of recent) {
      events.push({ kind: "opened", i, at: i.openedAt });
      if (i.acknowledgedAt) events.push({ kind: "acknowledged", i, at: i.acknowledgedAt });
      if (i.closedAt) events.push({ kind: "closed", i, at: i.closedAt });
    }
    events.sort((a, b) => new Date(a.at) - new Date(b.at)).forEach(e => addFeed(e.kind, e.i, e.at, false));
    historyNeedsRefresh = false;
  } catch (e) {
    historyNeedsRefresh = true;
    if (canOperate()) $("#log-state").textContent = "Event history unavailable; retrying. Displayed events may be incomplete.";
    else updateConnection();
  }
}

// ---------- live alerts ----------

let alertSocket = null;
let reconnectTimer = null;
let reconnectDelay = 1000;

function stopAlerts() {
  clearTimeout(reconnectTimer);
  reconnectTimer = null;
  const previous = alertSocket;
  alertSocket = null;
  previous?.close();
  connection.ws = false;
}

function connectAlerts() {
  if (!navigator.onLine || !auth.token || alertSocket || reconnectTimer) return;
  // browsers can't send an Authorization header on a WebSocket: the token goes in the query
  const ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host
    + "/ws/alerts?access_token=" + encodeURIComponent(auth.token));
  alertSocket = ws;
  ws.onopen = () => {
    if (alertSocket !== ws) return;
    connection.ws = true;
    reconnectDelay = 1000;
    historyNeedsRefresh = true;
    updateConnection();
    poll();
  };
  ws.onmessage = msg => {
    if (alertSocket !== ws || !navigator.onLine) return;
    try {
      const { event, incident } = JSON.parse(msg.data);
      const kind = event === "incident.opened" ? "opened"
        : event === "incident.acknowledged" ? "acknowledged"
          : "closed";
      const at = kind === "opened" ? incident.openedAt
        : kind === "acknowledged" ? incident.acknowledgedAt
          : incident.closedAt;
      addFeed(kind, incident, at, true);
      poll();
    } catch (e) { /* ignore malformed */ }
  };
  ws.onclose = () => {
    if (alertSocket !== ws) return;
    alertSocket = null;
    connection.ws = false;
    updateConnection();
    if (!navigator.onLine) return;
    reconnectTimer = setTimeout(() => { reconnectTimer = null; connectAlerts(); }, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 2, 15000);
  };
}

// ---------- simulator faults (through the API's dev proxy) ----------

async function loadFaults() {
  if (!canFault()) {
    faultsEnabled = false;
    hint("Fault buttons are for operators and administrators.");
    updateControls();
    return;
  }
  try {
    const data = await getJson("/api/dev/faults");
    const list = Array.isArray(data) ? data : data.sensors || [];
    simFaults = Object.fromEntries(list.map(s => [s.sensorId, s.fault]));
    faultsEnabled = true;
    hint("");
  } catch (e) {
    faultsEnabled = false;
    hint(e.status === 404
      ? "Fault buttons are off: set SIM_CONTROL_URL=http://simulator:4000 on the API."
      : "Simulator unreachable.", true);
  }
  updateControls();
}

async function setFault(sensorId, fault, button) {
  if (!canOperate() || faultsEnabled !== true || clearPending || pendingSensors.has(sensorId)) return;
  pendingSensors.add(sensorId);
  updateControls();
  button.disabled = true;
  try {
    await getJson("/api/dev/faults", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sensorId, fault })
    });
    simFaults[sensorId] = fault;
    hint(`${sensorId}: ${FAULT_LABEL[fault]}. Watch the tile and the logbook.`);
  } catch (e) {
    requestFailed(e);
    hint("Could not set the fault (" + e.message + ").", true);
  } finally {
    pendingSensors.delete(sensorId);
    updateControls();
    poll();
  }
}

$("#clear-faults").addEventListener("click", async () => {
  if (!canOperate() || faultsEnabled !== true || clearPending || pendingSensors.size > 0) return;
  clearPending = true;
  updateControls();
  try {
    await getJson("/api/dev/faults/clear", { method: "POST" });
    simFaults = {};
    hint("All faults cleared. Incidents close after 3 normal readings.");
  } catch (e) {
    requestFailed(e);
    hint("Could not clear the faults (" + e.message + ").", true);
  } finally {
    clearPending = false;
    updateControls();
  }
  poll();
});

function hint(text, error = false) {
  const h = $("#fault-hint");
  h.textContent = text;
  h.classList.toggle("err", error);
}

// ---------- gateways ----------

function renderGateways(list) {
  const root = $("#gateways");
  root.textContent = "";
  for (const g of list) {
    const chip = document.createElement("span");
    const online = canOperate() && g.status === "online";
    chip.className = "chip " + (online ? "ok" : "bad");
    chip.title = `forwarded ${g.forwarded ?? "?"} · buffered ${g.buffered ?? "?"} · replayed ${g.replayed ?? "?"}`;
    const icon = document.createElement("i");
    icon.setAttribute("aria-hidden", "true");
    chip.append(icon, document.createTextNode(`${g.gatewayId || g.siteId} ${canOperate() ? g.status : "status unknown"}`));
    root.append(chip);
  }
}

// ---------- main loop ----------

let polling = false;
let refreshRequested = false;
async function poll() {
  if (!navigator.onLine || !auth.token) return;
  if (polling) { refreshRequested = true; return; }
  polling = true;
  const epoch = connectionEpoch;
  try {
    const [sensors, open, gateways] = await Promise.all([
      getJson("/api/sensors"),
      getJson("/api/incidents?status=open"),
      getJson("/api/gateways")
    ]);
    if (epoch !== connectionEpoch || !navigator.onLine) return;
    knownGateways = gateways;
    openIncidents = open;
    if (firstRender || sensors.some(s => !tiles.has(s.id))) {
      tiles.clear();
      if (sensors.length === 0) {
        const message = document.createElement("p");
        message.className = "loading";
        message.textContent = "No sensors registered yet. Is the dev-seed service done?";
        $("#sites").replaceChildren(message);
      } else {
        buildSites(sensors);
        firstRender = false;
      }
    }
    await loadFaults();
    if (epoch !== connectionEpoch || !navigator.onLine) return;
    connection.api = true;
    connection.lastSuccess = new Date().toISOString();
    sensors.forEach(updateTile);
    renderOpen();
    renderGateways(gateways);
    updateConnection();
    if (historyNeedsRefresh) await loadHistory();
  } catch (e) {
    backendUnavailable();
  } finally {
    polling = false;
    if (refreshRequested) {
      refreshRequested = false;
      if (navigator.onLine) queueMicrotask(poll);
    }
  }
}

setInterval(() => { $("#clock").textContent = new Date().toLocaleTimeString([], { hour12: false }); }, 1000);
window.addEventListener("offline", () => {
  connectionEpoch++;
  requests.forEach(request => request.abort());
  stopAlerts();
  backendUnavailable();
});
window.addEventListener("online", () => {
  historyNeedsRefresh = true;
  connectAlerts();
  poll();
});
document.addEventListener("visibilitychange", () => {
  if (document.hidden) return;
  if (!connection.lastSuccess || Date.now() - Date.parse(connection.lastSuccess) > POLL_MS * 3) backendUnavailable();
  connectAlerts();
  poll();
});
// ---------- sign in / sign out ----------

function showLogin(message) {
  $("#login").hidden = false;
  $("#user-chip").hidden = true;
  $("#logout").hidden = true;
  const error = $("#login-error");
  error.textContent = message || "";
  error.hidden = !message;
  $("#login-user").focus();
}

function startSession() {
  $("#login").hidden = true;
  $("#user-name").textContent = `${auth.user.name || auth.user.username} · ${(auth.user.roles || []).join(", ")}`;
  $("#user-chip").hidden = false;
  $("#user-chip").classList.add("ok");
  $("#logout").hidden = false;
  $("#ack-note").textContent = `Acknowledgements are signed as ${auth.user.username}.`;
  clearTimeout(expiryTimer);
  // setTimeout's limit is about 24 days; tokens last hours
  const left = Math.min(Date.parse(auth.expiresAt) - Date.now(), 2 ** 31 - 1);
  expiryTimer = setTimeout(() => signOut("Your session has expired. Sign in again."), Math.max(0, left));
  historyNeedsRefresh = true;
  updateConnection();
  connectAlerts();
  poll();
}

function signOut(message) {
  if (!auth.token && !$("#login").hidden) return;   // already signed out
  sessionStorage.removeItem(SESSION_KEY);
  auth.token = auth.user = auth.expiresAt = null;
  clearTimeout(expiryTimer);
  connectionEpoch++;
  requests.forEach(request => request.abort());
  stopAlerts();
  // forget everything the previous user saw
  connection.api = false;
  connection.lastSuccess = null;
  firstRender = true;
  tiles.clear();
  incidentRows.clear();
  openIncidents = [];
  knownGateways = [];
  const placeholder = document.createElement("p");
  placeholder.className = "loading";
  placeholder.textContent = "Sign in to see the sensors.";
  $("#sites").replaceChildren(placeholder);
  $("#open-incidents").textContent = "";
  $("#feed").textContent = "";
  $("#gateways").textContent = "";
  hint("");
  updateConnection();
  showLogin(message);
}

$("#login-form").addEventListener("submit", async event => {
  event.preventDefault();
  const submit = $("#login-submit");
  const error = $("#login-error");
  submit.disabled = true;
  error.hidden = true;
  try {
    if (!navigator.onLine) throw new Error("You are offline: signing in needs the network.");
    const r = await fetch("/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: $("#login-user").value.trim().toLowerCase(), password: $("#login-password").value }),
      cache: "no-store"
    });
    let data = null;
    try { data = await r.json(); } catch (e) { /* not JSON */ }
    if (!r.ok) {
      throw new Error(data && typeof data.error === "string" ? data.error
        : r.status >= 500 ? "The login server is not reachable. Try again in a moment." : `Sign-in failed (${r.status}).`);
    }
    auth.token = data.accessToken;
    auth.user = data.user;
    auth.expiresAt = data.expiresAt;
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(auth));
    $("#login-password").value = "";
    startSession();
  } catch (e) {
    error.textContent = e.name === "TypeError" ? "The login server is not reachable. Try again in a moment." : e.message;
    error.hidden = false;
  } finally {
    submit.disabled = false;
  }
});

$("#logout").addEventListener("click", () => signOut("Signed out."));

updateConnection();
if (auth.token) startSession(); else showLogin();
setInterval(poll, POLL_MS);

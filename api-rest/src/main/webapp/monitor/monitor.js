// ColdTrace monitor: polls the REST API every 5 s, listens to live alerts on the WebSocket,
// and drives the simulator's faults through the API's dev proxy.
"use strict";

const POLL_MS = 5000;
const SILENT_AFTER_S = 60;
const METRIC_LABEL = { temperature: "Temperature", humidity: "Humidity", door: "Door open", silence: "No signal" };
// incidents written before the "metric" field existed have no metric: show them generically
const metricLabel = m => METRIC_LABEL[m] || m || "Incident";
const FAULT_LABEL = { door_open: "door open", temp_rise: "heating", silent: "muted", none: "normal" };

const $ = (sel, root = document) => root.querySelector(sel);
const tiles = new Map();       // sensorId -> { el, ... }
const incidentRows = new Map(); // incident id -> persistent row, preserving an in-progress form across polls
let faultsEnabled = null;      // null = unknown yet
let simFaults = {};            // sensorId -> active fault
let openIncidents = [];
let firstRender = true;

// ---------- helpers ----------

async function getJson(url, options) {
  const r = await fetch(url, options);
  let data;
  try { data = await r.json(); } catch (e) { data = null; }
  if (!r.ok) {
    const message = data && typeof data.error === "string" ? data.error : url + " returned " + r.status;
    const err = new Error(message);
    err.status = r.status;
    throw err;
  }
  return data;
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
  el.style.setProperty("--i", index);
  $(".tile-id", el).textContent = sensor.id;
  $(".tile-name", el).textContent = siteName(sensor);
  el.querySelectorAll(".faults button").forEach(b =>
    b.addEventListener("click", () => setFault(sensor.id, b.dataset.fault, b)));
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
  t.el.classList.toggle("alarm", alarm);
  t.el.classList.toggle("door-open", door);
  t.el.classList.toggle("silent", silent);
  t.flag.textContent = alarm ? "Out of range" : silent ? "No signal" : door ? "Door open" : r.replayed ? "replayed" : "";

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
    b.disabled = faultsEnabled === false;
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

  const form = document.createElement("form");
  form.className = "ack-form";
  form.hidden = true;
  const operatorLabel = document.createElement("label");
  operatorLabel.append(document.createTextNode("Nom de l’opérateur (démo)"));
  const operator = document.createElement("input");
  operator.type = "text";
  operator.maxLength = 64;
  operator.required = true;
  operator.autocomplete = "name";
  operatorLabel.append(operator);
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
  form.append(operatorLabel, commentLabel, error, controls);

  const row = { el, who, what, when, detail, state, acknowledgement, action, ackButton, form, operator, comment, error, submit, cancel };

  ackButton.addEventListener("click", () => {
    form.hidden = false;
    ackButton.hidden = true;
    operator.focus();
  });
  cancel.addEventListener("click", () => {
    form.hidden = true;
    ackButton.hidden = false;
    error.hidden = true;
    error.textContent = "";
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
  row.error.hidden = true;
  row.error.textContent = "";
  row.submit.disabled = true;
  row.cancel.disabled = true;
  row.operator.disabled = true;
  row.comment.disabled = true;
  try {
    const incident = await getJson(`/api/incidents/${encodeURIComponent(id)}/ack`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ operator: row.operator.value, comment: row.comment.value })
    });
    openIncidents = openIncidents.map(item => item.id === incident.id ? incident : item);
    updateIncidentRow(row, incident);
    poll();
  } catch (e) {
    row.error.textContent = e.message;
    row.error.hidden = false;
  } finally {
    row.submit.disabled = false;
    row.cancel.disabled = false;
    row.operator.disabled = false;
    row.comment.disabled = false;
  }
}

function addFeed(kind, incident, at, flash) {
  const feed = $("#feed");
  const li = document.createElement("li");
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
    text.append(b, document.createTextNode(kind === "opened" ? " opened" : " closed"));
  }
  li.append(time, text);
  feed.prepend(li);
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
      if (i.closedAt) events.push({ kind: "closed", i, at: i.closedAt });
    }
    events.sort((a, b) => new Date(a.at) - new Date(b.at)).forEach(e => addFeed(e.kind, e.i, e.at, false));
  } catch (e) { /* the poll will show the API as down */ }
}

// ---------- live alerts ----------

function connectAlerts(delay = 1000) {
  const ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/ws/alerts");
  ws.onopen = () => { setChip("chip-ws", true); delay = 1000; };
  ws.onmessage = msg => {
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
    setChip("chip-ws", false);
    setTimeout(() => connectAlerts(Math.min(delay * 2, 15000)), delay);
  };
}

// ---------- simulator faults (through the API's dev proxy) ----------

async function loadFaults() {
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
  $("#clear-faults").disabled = faultsEnabled === false;
}

async function setFault(sensorId, fault, button) {
  button.disabled = true;
  try {
    await getJson("/api/dev/faults", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sensorId, fault })
    });
    simFaults[sensorId] = fault;
    hint(`${sensorId}: ${FAULT_LABEL[fault]}. Watch the tile and the logbook.`);
  } catch (e) {
    hint("Could not set the fault (" + e.message + ").", true);
  } finally {
    button.disabled = false;
    poll();
  }
}

$("#clear-faults").addEventListener("click", async () => {
  try {
    await getJson("/api/dev/faults/clear", { method: "POST" });
    simFaults = {};
    hint("All faults cleared. Incidents close after 3 normal readings.");
  } catch (e) {
    hint("Could not clear the faults (" + e.message + ").", true);
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
    const online = g.status === "online";
    chip.className = "chip " + (online ? "ok" : "bad");
    chip.title = `forwarded ${g.forwarded ?? "?"} · buffered ${g.buffered ?? "?"} · replayed ${g.replayed ?? "?"}`;
    chip.append(document.createElement("i"), document.createTextNode(`${g.gatewayId || g.siteId} ${g.status}`));
    root.append(chip);
  }
}

// ---------- main loop ----------

let polling = false;
async function poll() {
  if (polling) return;
  polling = true;
  try {
    const [sensors, open, gateways] = await Promise.all([
      getJson("/api/sensors"),
      getJson("/api/incidents?status=open"),
      getJson("/api/gateways")
    ]);
    setChip("chip-api", true);
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
    sensors.forEach(updateTile);
    renderOpen();
    renderGateways(gateways);
  } catch (e) {
    setChip("chip-api", false);
  } finally {
    polling = false;
  }
}

setInterval(() => { $("#clock").textContent = new Date().toLocaleTimeString([], { hour12: false }); }, 1000);
loadHistory();
connectAlerts();
poll();
setInterval(poll, POLL_MS);

// ColdTrace back office: administration of sites, sensors, thresholds (API) and users (IAM), and the ingestion
// state. Administrators only: the API and the IAM check the role again on every change.
"use strict";

// Same addressing as the front office: this page on admin.<domain>, the API on api.<domain>, the login server on
// iam.<domain>, with the same scheme and port. WildFly's CORS rules allow these calls.
const SITE = location.hostname.replace(/^(admin|www)\./, "");
const NAMED = SITE !== "localhost" && SITE.includes(".") && !/^[\d.]+$/.test(SITE);
const PORT = location.port ? ":" + location.port : "";
const API = NAMED ? `${location.protocol}//api.${SITE}${PORT}` : "";
const IAM = NAMED ? `${location.protocol}//iam.${SITE}${PORT}` : "";
document.getElementById("monitor-link").href = NAMED ? `${location.protocol}//www.${SITE}${PORT}/` : "/";

const SESSION_KEY = "coldtrace.admin.session";
const ROLES = ["admin", "quality", "operator"];
const $ = (sel, root = document) => root.querySelector(sel);

const state = { sites: [], sensors: [], thresholds: [], users: [], tab: "overview" };
let auth = loadSession();
let expiryTimer = null;

// ---------- session ----------

function loadSession() {
  try {
    const s = JSON.parse(sessionStorage.getItem(SESSION_KEY) || "null");
    if (s && s.token && Date.parse(s.expiresAt) > Date.now() + 5000) return s;
  } catch (e) { /* unreadable */ }
  sessionStorage.removeItem(SESSION_KEY);
  return null;
}

function signOut(message) {
  sessionStorage.removeItem(SESSION_KEY);
  auth = null;
  clearTimeout(expiryTimer);
  $("#main").hidden = true;
  $("#tabs").hidden = true;
  $("#logout").hidden = true;
  $("#user-name").textContent = "";
  for (const t of document.querySelectorAll("tbody")) t.textContent = "";
  $("#login").hidden = false;
  const error = $("#login-error");
  error.textContent = message || "";
  error.hidden = !message;
  $("#login-user").focus();
}

function startSession() {
  $("#login").hidden = true;
  $("#main").hidden = false;
  $("#tabs").hidden = false;
  $("#logout").hidden = false;
  $("#user-name").textContent = `${auth.user.name || auth.user.username} · admin`;
  clearTimeout(expiryTimer);
  expiryTimer = setTimeout(() => signOut("Your session has expired. Sign in again."),
    Math.min(Date.parse(auth.expiresAt) - Date.now(), 2 ** 31 - 1));
  showTab(location.hash.slice(1) || state.tab);
}

$("#login-form").addEventListener("submit", async event => {
  event.preventDefault();
  const error = $("#login-error");
  const submit = $("#login-submit");
  submit.disabled = true;
  error.hidden = true;
  try {
    const r = await fetch(IAM + "/auth/login", {
      method: "POST", headers: { "Content-Type": "application/json" }, cache: "no-store",
      body: JSON.stringify({ username: $("#login-user").value.trim().toLowerCase(), password: $("#login-password").value })
    });
    const data = await r.json().catch(() => null);
    if (!r.ok) throw new Error(data?.error || (r.status >= 500 ? "The login server is not reachable." : `Sign-in failed (${r.status}).`));
    if (!data.user.roles.includes("admin")) throw new Error("The back office is for administrators. Use the monitor instead.");
    auth = { token: data.accessToken, user: data.user, expiresAt: data.expiresAt };
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(auth));
    $("#login-password").value = "";
    startSession();
  } catch (e) {
    error.textContent = e.name === "TypeError" ? "The login server is not reachable." : e.message;
    error.hidden = false;
  } finally {
    submit.disabled = false;
  }
});
$("#logout").addEventListener("click", () => signOut("Signed out."));

// ---------- HTTP ----------

async function call(origin, path, method = "GET", body) {
  if (!auth) throw new Error("Signed out.");
  const options = { method, cache: "no-store", headers: { Authorization: "Bearer " + auth.token } };
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  let r;
  try {
    r = await fetch(origin + path, options);
  } catch (e) {
    throw new Error("The server is not reachable. Nothing was changed.");
  }
  if (r.status === 401) {
    signOut("Your session has ended. Sign in again.");
    throw new Error("Signed out.");
  }
  const data = r.status === 204 ? null : await r.json().catch(() => null);
  if (!r.ok) throw new Error(data?.error || `${method} ${path} returned ${r.status}`);
  return data;
}
const api = (path, method, body) => call(API, path, method, body);
const iam = (path, method, body) => call(IAM, path, method, body);

let toastTimer = null;
function toast(text, error = false) {
  const t = $("#toast");
  t.textContent = text;
  t.classList.toggle("error", error);
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, error ? 8000 : 4000);
}

// ---------- small DOM helpers ----------

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null || v === false) continue;
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else if (k === "text") e.textContent = v;
    else e.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat()) if (c !== null && c !== undefined) e.append(c instanceof Node ? c : String(c));
  return e;
}
const td = (content, cls) => el("td", { class: cls }, content ?? "--");
const badge = (text, kind) => el("span", { class: "badge " + (kind || "") }, text);
const when = iso => iso ? new Date(iso).toLocaleString([], { hour12: false }) : "--";
const num = v => v === null || v === undefined ? "--" : String(v);

function actions(...buttons) {
  return el("td", { class: "row-actions" }, buttons);
}
function button(text, onclick, kind) {
  return el("button", { type: "button", class: kind, onclick }, text);
}

// ---------- the editor dialog ----------

/**
 * Opens a form. fields: [{name, label, type: text|number|select|checkbox|multi|password, value, options,
 * required, disabled, help, pattern, max}]. save(values) returns a promise; the dialog closes when it resolves.
 */
function edit(title, fields, save) {
  const dialog = $("#editor");
  $("#editor-title").textContent = title;
  const box = $("#editor-fields");
  box.textContent = "";
  const inputs = {};
  for (const f of fields) {
    let input;
    if (f.type === "select") {
      input = el("select", { name: f.name, disabled: f.disabled },
        f.options.map(o => el("option", { value: o.value, selected: String(o.value) === String(f.value ?? "") }, o.label)));
    } else if (f.type === "checkbox") {
      input = el("input", { type: "checkbox", name: f.name, checked: Boolean(f.value), disabled: f.disabled });
    } else if (f.type === "multi") {
      input = el("fieldset", { class: "multi" }, el("legend", {}, f.label),
        f.options.map(o => el("label", { class: "check" },
          el("input", { type: "checkbox", value: o.value, checked: (f.value || []).includes(o.value) }), o.label)));
      if (!f.options.length) input.append(el("p", { class: "muted" }, f.empty || "Nothing to choose."));
    } else {
      input = el("input", {
        type: f.type || "text", name: f.name, value: f.value ?? "", required: f.required, disabled: f.disabled,
        pattern: f.pattern, maxlength: f.max, step: f.type === "number" ? "0.1" : undefined, autocomplete: "off"
      });
    }
    inputs[f.name] = { input, field: f };
    const row = f.type === "multi" ? input
      : f.type === "checkbox" ? el("label", { class: "check" }, input, f.label)
        : el("label", {}, f.label, input);
    box.append(row);
    if (f.help) box.append(el("p", { class: "help" }, f.help));
  }
  const error = $("#editor-error");
  error.hidden = true;

  const form = $("#editor-form");
  form.onsubmit = async event => {
    event.preventDefault();
    const values = {};
    for (const [name, { input, field }] of Object.entries(inputs)) {
      if (field.disabled) continue;
      if (field.type === "checkbox") values[name] = input.checked;
      else if (field.type === "multi") values[name] = [...input.querySelectorAll("input:checked")].map(i => i.value);
      else if (field.type === "number") values[name] = input.value.trim() === "" ? null : Number(input.value);
      else values[name] = input.value.trim() === "" ? null : input.value.trim();
    }
    const saveButton = $("#editor-save");
    saveButton.disabled = true;
    error.hidden = true;
    try {
      await save(values);
      dialog.close();
    } catch (e) {
      error.textContent = e.message;
      error.hidden = false;
    } finally {
      saveButton.disabled = false;
    }
  };
  $("#editor-cancel").onclick = () => dialog.close();
  dialog.showModal();
  const first = box.querySelector("input:not([disabled]), select:not([disabled])");
  first?.focus();
}

function confirmAction(text) {
  return window.confirm(text);
}

const profileOptions = (none = "— none —") => [{ value: "", label: none }]
  .concat(state.thresholds.map(t => ({ value: t.id, label: t.id })));
const siteOptions = () => state.sites.filter(s => s.active !== false).map(s => ({ value: s.id, label: `${s.id} · ${s.name}` }));
const operatorOptions = () => state.users.filter(u => u.roles.includes("operator"))
  .map(u => ({ value: u.username, label: `${u.username}${u.name ? " · " + u.name : ""}` }));
const ID = "[A-Za-z0-9_\\-]{1,32}";

// ---------- overview ----------

async function loadOverview() {
  const [sites, sensors, gateways, ingestion, users] = await Promise.all([
    api("/api/sites"), api("/api/sensors"), api("/api/gateways"), api("/api/ingestion"), iam("/users").catch(() => null)
  ]);
  const online = sensors.filter(s => s.status === "online").length;
  const stats = $("#overview-stats");
  stats.textContent = "";
  const stat = (label, value, note, warn) => stats.append(el("div", { class: warn ? "warn" : "" },
    el("dt", {}, label), el("dd", {}, value), note ? el("span", { class: "muted" }, note) : null));
  stat("Sites", sites.filter(s => s.active !== false).length, `${sites.length} registered`);
  stat("Sensors online", `${online} / ${sensors.filter(s => s.active).length}`, "reading in the last 30 s",
    online < sensors.filter(s => s.active).length);
  stat("Users", users ? users.filter(u => u.enabled).length : "--", users ? `${users.length} accounts` : "IAM unreachable");
  stat("Ingestion queue", ingestion.pending, ingestion.accepting ? "accepting readings" : "NOT accepting", !ingestion.accepting || ingestion.pending > 100);
  stat("Quarantined", ingestion.quarantined, "rejected messages kept", ingestion.quarantined > 0);

  const body = $("#gateways-table tbody");
  body.textContent = "";
  if (!gateways.length) body.append(el("tr", {}, el("td", { colspan: 9, class: "muted" }, "No gateway has reported yet.")));
  for (const g of gateways) {
    body.append(el("tr", {}, td(g.siteId), td(g.gatewayId), td(badge(g.status || "?", g.status === "online" ? "ok" : "bad")),
      td(when(g.ts || g.updatedAt)), td(num(g.forwarded), "num"), td(num(g.buffered), "num"), td(num(g.replayed), "num"),
      td(num(g.rejected), "num"), td(num(g.dropped), "num" + (g.dropped > 0 ? " bad" : ""))));
  }
}

// ---------- sites ----------

async function loadSites() {
  const [sites, thresholds, users] = await Promise.all([api("/api/sites"), api("/api/thresholds"), iam("/users").catch(() => [])]);
  state.sites = sites;
  state.thresholds = thresholds;
  state.users = users;
  const body = $("#sites-table tbody");
  body.textContent = "";
  for (const s of sites) {
    const active = s.active !== false;
    body.append(el("tr", { class: active ? "" : "inactive" },
      td(el("code", {}, s.id)), td(s.name), td(s.location), td(s.defaultProfile),
      td((s.operators || []).length ? s.operators.join(", ") : el("span", { class: "muted" }, "all operators")),
      td(badge(active ? "active" : "inactive", active ? "ok" : "")),
      actions(button("Edit", () => editSite(s)),
        active ? button("Deactivate", () => toggleSite(s, false), "danger") : button("Reactivate", () => toggleSite(s, true)))));
  }
}

function siteFields(s = {}) {
  return [
    { name: "id", label: "Id (in the MQTT topic)", value: s.id, required: true, pattern: ID, max: 32, disabled: Boolean(s.id),
      help: s.id ? null : "Letters, digits, - or _. It cannot change later." },
    { name: "name", label: "Name", value: s.name, required: true, max: 120 },
    { name: "location", label: "Location", value: s.location, max: 240 },
    { name: "defaultProfile", label: "Default threshold profile", type: "select", value: s.defaultProfile, options: profileOptions() },
    { name: "operators", label: "Operators / drivers who receive its alerts", type: "multi", value: s.operators || [],
      options: operatorOptions(), empty: "No user has the operator role yet." }
  ];
}

function editSite(s) {
  edit(`Site ${s.id}`, siteFields(s), async v => {
    await api(`/api/sites/${encodeURIComponent(s.id)}`, "PUT", v);
    toast(`Site ${s.id} saved.`);
    await loadSites();
  });
}

$("#add-site").addEventListener("click", () => edit("Add a site", siteFields(), async v => {
  const body = { ...v };
  if (!body.operators.length) delete body.operators;
  if (!body.location) delete body.location;
  if (!body.defaultProfile) delete body.defaultProfile;
  await api("/api/sites", "POST", body);
  toast(`Site ${v.id} created.`);
  await loadSites();
}));

async function toggleSite(s, active) {
  if (!active && !confirmAction(`Deactivate site ${s.id}? Its sensors stop raising alerts; their history is kept.`)) return;
  try {
    if (active) await api(`/api/sites/${encodeURIComponent(s.id)}`, "PUT", { active: true });
    else await api(`/api/sites/${encodeURIComponent(s.id)}`, "DELETE");
    toast(`Site ${s.id} ${active ? "reactivated" : "deactivated"}.`);
    await loadSites();
  } catch (e) { toast(e.message, true); }
}

// ---------- sensors ----------

async function loadSensors() {
  const [sensors, sites, thresholds] = await Promise.all([api("/api/sensors"), api("/api/sites"), api("/api/thresholds")]);
  state.sensors = sensors;
  state.sites = sites;
  state.thresholds = thresholds;
  const filter = $("#sensor-site-filter");
  const keep = filter.value;
  filter.length = 1;
  for (const s of sites) filter.append(el("option", { value: s.id }, `${s.id} · ${s.name}`));
  filter.value = sites.some(s => s.id === keep) ? keep : "";
  renderSensors();
}

function renderSensors() {
  const site = $("#sensor-site-filter").value;
  const body = $("#sensors-table tbody");
  body.textContent = "";
  for (const s of state.sensors.filter(x => !site || x.siteId === site)) {
    const kind = { online: "ok", stale: "warn", offline: "bad", disabled: "", unknown: "" }[s.status] ?? "";
    const last = s.lastMeasurement;
    body.append(el("tr", { class: s.active ? "" : "inactive" },
      td(el("code", {}, s.id)), td(s.name), td(s.siteId), td(s.type), td(s.profile),
      td(badge(s.status || "?", kind)),
      td(last ? `${when(last.ts)} · ${last.temperature ?? "--"} °C` : "never"),
      actions(button("Edit", () => editSensor(s)),
        s.active ? button("Deactivate", () => toggleSensor(s, false), "danger") : button("Reactivate", () => toggleSensor(s, true)))));
  }
  if (!body.children.length) body.append(el("tr", {}, el("td", { colspan: 8, class: "muted" }, "No sensor registered.")));
}

$("#sensor-site-filter").addEventListener("change", renderSensors);

function sensorFields(s = {}) {
  return [
    { name: "id", label: "Id (in the MQTT topic)", value: s.id, required: true, pattern: ID, max: 32, disabled: Boolean(s.id) },
    { name: "name", label: "Name", value: s.name, required: true, max: 120 },
    { name: "siteId", label: "Site", type: "select", value: s.siteId, options: siteOptions(),
      help: s.id ? "Moving a sensor needs its open incidents resolved first; its history stays with the old site." : null },
    { name: "type", label: "Type", value: s.type, max: 80, help: "e.g. DHT22 temperature + humidity, DS18B20, reed switch" },
    { name: "profile", label: "Threshold profile", type: "select", value: s.profile, options: profileOptions("— site default —") }
  ];
}

function editSensor(s) {
  edit(`Sensor ${s.id}`, sensorFields(s), async v => {
    await api(`/api/sensors/${encodeURIComponent(s.id)}`, "PUT", v);
    toast(`Sensor ${s.id} saved.`);
    await loadSensors();
  });
}

$("#add-sensor").addEventListener("click", () => {
  if (!siteOptions().length) { toast("Add a site first.", true); return; }
  edit("Register a sensor", sensorFields({ siteId: $("#sensor-site-filter").value || undefined }), async v => {
    const body = { ...v };
    for (const k of ["type", "profile"]) if (!body[k]) delete body[k];
    await api("/api/sensors", "POST", body);
    toast(`Sensor ${v.id} registered.`);
    await loadSensors();
  });
});

async function toggleSensor(s, active) {
  if (!active && !confirmAction(`Deactivate sensor ${s.id}? It stops raising alerts; its history is kept.`)) return;
  try {
    if (active) await api(`/api/sensors/${encodeURIComponent(s.id)}`, "PUT", { active: true });
    else await api(`/api/sensors/${encodeURIComponent(s.id)}`, "DELETE");
    toast(`Sensor ${s.id} ${active ? "reactivated" : "deactivated"}.`);
    await loadSensors();
  } catch (e) { toast(e.message, true); }
}

// ---------- thresholds ----------

async function loadThresholds() {
  state.thresholds = await api("/api/thresholds");
  const body = $("#thresholds-table tbody");
  body.textContent = "";
  for (const t of state.thresholds) {
    body.append(el("tr", {}, td(el("code", {}, t.id)), td(num(t.temperatureMin), "num"), td(num(t.temperatureMax), "num"),
      td(num(t.humidityMin), "num"), td(num(t.humidityMax), "num"), td(when(t.updatedAt)),
      actions(button("Edit", () => editThreshold(t)))));
  }
}

function thresholdFields(t = {}) {
  return [
    { name: "id", label: "Profile", value: t.id, required: true, pattern: ID, max: 32, disabled: Boolean(t.id),
      help: t.id ? null : "e.g. cold, freezer, pharma" },
    { name: "temperatureMin", label: "Temperature min (°C)", type: "number", value: t.temperatureMin },
    { name: "temperatureMax", label: "Temperature max (°C)", type: "number", value: t.temperatureMax },
    { name: "humidityMin", label: "Humidity min (%)", type: "number", value: t.humidityMin },
    { name: "humidityMax", label: "Humidity max (%)", type: "number", value: t.humidityMax }
  ];
}

async function saveThreshold(id, v) {
  const body = { temperatureMin: v.temperatureMin, temperatureMax: v.temperatureMax, humidityMin: v.humidityMin, humidityMax: v.humidityMax };
  await api(`/api/thresholds/${encodeURIComponent(id)}`, "PUT", body);
  toast(`Profile ${id} saved. New readings use these limits.`);
  await loadThresholds();
}

function editThreshold(t) {
  edit(`Profile ${t.id}`, thresholdFields(t), v => saveThreshold(t.id, v));
}

$("#add-profile").addEventListener("click", () => edit("Add a profile", thresholdFields(), async v => {
  if (state.thresholds.some(t => t.id === v.id)) throw new Error(`The profile ${v.id} exists: edit it instead.`);
  await saveThreshold(v.id, v);
}));

// ---------- users (IAM) ----------

async function loadUsers() {
  state.users = await iam("/users");
  const body = $("#users-table tbody");
  body.textContent = "";
  for (const u of state.users) {
    const self = u.username === auth.user.username;
    body.append(el("tr", { class: u.enabled ? "" : "inactive" },
      td(el("code", {}, u.username)), td(u.name), td(u.roles.map(r => badge(r, "role"))),
      td(badge(u.enabled ? "enabled" : "disabled", u.enabled ? "ok" : "")), td(when(u.createdAt)),
      actions(button("Edit", () => editUser(u)), button("New password", () => resetPassword(u)),
        self ? null : button("Delete", () => deleteUser(u), "danger"))));
  }
}

function newPassword() {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, b => alphabet[b % alphabet.length]).join("") + "-7";
}

function showPassword(username, password) {
  edit(`Password of ${username}`, [{ name: "password", label: "Give this password to the user once; it is not shown again.",
    value: password, disabled: true }], async () => {});
  $("#editor-save").textContent = "Done";
  $("#editor").addEventListener("close", () => { $("#editor-save").textContent = "Save"; }, { once: true });
}

function editUser(u) {
  edit(`User ${u.username}`, [
    { name: "name", label: "Full name", value: u.name, max: 80 },
    { name: "roles", label: "Roles", type: "multi", value: u.roles, options: ROLES.map(r => ({ value: r, label: r })) },
    { name: "enabled", label: "Account enabled", type: "checkbox", value: u.enabled }
  ], async v => {
    if (!v.roles.length) throw new Error("Choose at least one role.");
    await iam(`/users/${encodeURIComponent(u.username)}`, "PUT", { name: v.name ?? "", roles: v.roles, enabled: v.enabled });
    toast(`User ${u.username} saved.`);
    await loadUsers();
  });
}

$("#add-user").addEventListener("click", () => {
  const password = newPassword();
  edit("Add a user", [
    { name: "username", label: "Username", required: true, pattern: "[a-z0-9._\\-]{3,32}", max: 32,
      help: "3 to 32 characters: lowercase letters, digits, '.', '-', '_'" },
    { name: "name", label: "Full name", max: 80 },
    { name: "roles", label: "Roles", type: "multi", value: ["operator"], options: ROLES.map(r => ({ value: r, label: r })) },
    { name: "password", label: "First password", value: password, required: true, max: 128,
      help: "At least 10 characters. Generated at random; change it if you want." }
  ], async v => {
    if (!v.roles.length) throw new Error("Choose at least one role.");
    await iam("/users", "POST", { username: v.username, name: v.name, roles: v.roles, password: v.password });
    toast(`User ${v.username} created.`);
    await loadUsers();
  });
});

async function resetPassword(u) {
  if (!confirmAction(`Give ${u.username} a new random password? The old one stops working.`)) return;
  const password = newPassword();
  try {
    await iam(`/users/${encodeURIComponent(u.username)}`, "PUT", { password });
    showPassword(u.username, password);
  } catch (e) { toast(e.message, true); }
}

async function deleteUser(u) {
  if (!confirmAction(`Delete the account ${u.username}? Their past acknowledgements keep their name.`)) return;
  try {
    await iam(`/users/${encodeURIComponent(u.username)}`, "DELETE");
    toast(`User ${u.username} deleted.`);
    await loadUsers();
  } catch (e) { toast(e.message, true); }
}

// ---------- ingestion ----------

async function loadIngestion() {
  const [status, quarantine] = await Promise.all([api("/api/ingestion"), api("/api/ingestion/quarantine?limit=100")]);
  const stats = $("#ingestion-stats");
  stats.textContent = "";
  const stat = (label, value, warn) => stats.append(el("div", { class: warn ? "warn" : "" }, el("dt", {}, label), el("dd", {}, value)));
  stat("Accepting", status.accepting ? "yes" : "NO", !status.accepting);
  stat("Waiting for MongoDB", status.pending, status.pending > 100);
  stat("Quarantined", status.quarantined, status.quarantined > 0);
  stat("Saved since start", status.persistedThisRun);
  stat("Duplicates ignored", status.duplicatesThisRun);
  stat("Disk used", `${(status.bytes / 1048576).toFixed(1)} / ${(status.maxBytes / 1048576).toFixed(0)} MB`);
  if (status.lastFailure) stat("Last problem", status.lastFailure, true);
  const body = $("#quarantine-table tbody");
  body.textContent = "";
  if (!quarantine.length) body.append(el("tr", {}, el("td", { colspan: 3, class: "muted" }, "Nothing in quarantine.")));
  for (const q of quarantine.slice().reverse()) body.append(el("tr", {}, td(when(q.ingestedAt)), td(el("code", {}, q.topic)), td(q.reason)));
}
$("#refresh-ingestion").addEventListener("click", () => run(loadIngestion));

// ---------- tabs ----------

const LOADERS = { overview: loadOverview, sites: loadSites, sensors: loadSensors, thresholds: loadThresholds,
  users: loadUsers, ingestion: loadIngestion };

async function run(loader) {
  try {
    await loader();
  } catch (e) {
    if (auth) toast(e.message, true);
  }
}

function showTab(name) {
  if (!LOADERS[name]) name = "overview";
  state.tab = name;
  for (const b of document.querySelectorAll("#tabs button")) {
    if (b.dataset.tab === name) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
  }
  for (const p of document.querySelectorAll(".panel")) p.hidden = p.id !== "panel-" + name;
  if (location.hash !== "#" + name) history.replaceState(null, "", "#" + name);
  run(LOADERS[name]);
}

document.querySelectorAll("#tabs button").forEach(b => b.addEventListener("click", () => showTab(b.dataset.tab)));
window.addEventListener("hashchange", () => { if (auth) showTab(location.hash.slice(1)); });

if ("serviceWorker" in navigator && window.isSecureContext) {
  navigator.serviceWorker.register("/sw.js", { scope: "/", updateViaCache: "none" }).catch(() => {});
}

if (auth) startSession(); else signOut();

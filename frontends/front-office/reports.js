// ColdTrace history & reports: per-sensor temperature/humidity charts (Chart.js), indicators (KPI) and the
// compliance report exports (PDF, CSV). Uses monitor.js's session (auth), getJson and API_ORIGIN.
"use strict";

(() => {
  const $ = sel => document.querySelector(sel);
  const section = $("#history");
  const sensorSelect = $("#history-sensor");
  const siteSelect = $("#history-site");
  const state = $("#history-state");
  const REFRESH_MS = 60000;
  const POINTS = 300;

  // Chart colours: the monitor's own palette (monitor.css). One series per chart (no dual axis): the line is the
  // average per time bucket, the band its min-max, the limits dashed, and out-of-range parts in the alarm colour.
  const css = getComputedStyle(document.documentElement);
  const color = (name, fallback) => (css.getPropertyValue(name) || fallback).trim();
  const C = {
    line: color("--frost", "#a6ecff"),
    band: "rgba(166, 236, 255, 0.16)",
    out: color("--hazard", "#ff5b1a"),
    limit: color("--dim", "#93aeb5"),
    grid: "rgba(166, 236, 255, 0.08)",
    text: color("--mist", "#9ab4bb"),
    ink: color("--ice", "#e8f8fc"),
    surface: color("--steel-850", "#0e1c20")
  };

  let hours = 24;
  let sensors = [];
  let loaded = false;
  let loading = false;
  let timer = null;
  const charts = {};

  // ---------- helpers ----------

  const fmt = (n, d = 1) => n === null || n === undefined || Number.isNaN(n) ? "--" : Number(n).toFixed(d);
  const pct = n => n === null || n === undefined ? "--" : fmt(n) + " %";
  function minutes(m) {
    if (m === null || m === undefined) return "--";
    if (m < 60) return fmt(m) + " min";
    const total = Math.round(m);
    return Math.floor(total / 60) + " h " + String(total % 60).padStart(2, "0");
  }
  function windowParams() {
    const to = new Date();
    const from = new Date(to.getTime() - hours * 3600 * 1000);
    return { from: from.toISOString(), to: to.toISOString() };
  }
  function query(params) {
    return Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== "")
      .map(([k, v]) => encodeURIComponent(k) + "=" + encodeURIComponent(v)).join("&");
  }
  function tickLabel(ms) {
    const d = new Date(ms);
    const time = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
    return hours <= 24 ? time : d.toLocaleDateString([], { day: "2-digit", month: "2-digit" }) + " " + time;
  }
  function when(iso) {
    return iso ? new Date(iso).toLocaleString([], { hour12: false, day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }) : "--";
  }
  function setState(text, error = false) {
    state.textContent = text;
    state.classList.toggle("err", error);
  }

  // ---------- charts ----------

  // A vertical crosshair at the hovered time, under the tooltip.
  const crosshair = {
    id: "crosshair",
    afterDatasetsDraw(chart) {
      const active = chart.tooltip?.getActiveElements?.() || [];
      if (!active.length) return;
      const x = active[0].element.x, { top, bottom } = chart.chartArea, ctx = chart.ctx;
      ctx.save();
      ctx.strokeStyle = C.limit;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(x, top);
      ctx.lineTo(x, bottom);
      ctx.stroke();
      ctx.restore();
    }
  };

  function makeChart(canvas, unit, digits) {
    if (typeof Chart === "undefined") return null;
    return new Chart(canvas, {
      type: "line",
      data: { datasets: [] },
      plugins: [crosshair],
      options: {
        animation: false,
        responsive: true,
        maintainAspectRatio: false,
        parsing: false,
        normalized: true,
        spanGaps: false,
        interaction: { mode: "nearest", intersect: false, axis: "x" },
        layout: { padding: { top: 6, right: 8 } },
        scales: {
          x: {
            type: "linear",
            grid: { color: C.grid, drawTicks: false },
            border: { color: C.grid },
            ticks: { color: C.text, maxTicksLimit: 7, maxRotation: 0, padding: 6, callback: v => tickLabel(v) }
          },
          y: {
            grid: { color: C.grid, drawTicks: false },
            border: { display: false },
            ticks: { color: C.text, padding: 6, maxTicksLimit: 6, callback: v => fmt(v, digits) + unit }
          }
        },
        plugins: {
          legend: { display: false },   // one series per chart: the title and the key name it
          tooltip: {
            backgroundColor: C.surface,
            borderColor: C.grid,
            borderWidth: 1,
            titleColor: C.ink,
            bodyColor: C.ink,
            padding: 10,
            displayColors: false,
            filter: item => item.dataset.tooltip !== false,
            callbacks: {
              title: items => items.length ? new Date(items[0].parsed.x).toLocaleString([], { hour12: false }) : "",
              label: item => item.dataset.describe(item.raw)
            }
          }
        }
      }
    });
  }

  function limitDataset(label, value, from, to) {
    return {
      label, data: [{ x: from, y: value }, { x: to, y: value }], borderColor: C.limit, borderWidth: 1,
      borderDash: [5, 4], pointRadius: 0, pointHoverRadius: 0, tooltip: false, describe: () => ""
    };
  }

  // the line is drawn in the alarm colour where the average leaves the limits
  function outOfRange(lo, hi) {
    return ctx => {
      const a = ctx.p0.parsed.y, b = ctx.p1.parsed.y;
      const bad = v => (hi != null && v > hi) || (lo != null && v < lo);
      return bad(a) || bad(b) ? C.out : undefined;
    };
  }

  function drawTemperature(history) {
    const chart = charts.temp || (charts.temp = makeChart($("#chart-temp"), " °C", 1));
    if (!chart) return;
    const from = Date.parse(history.from), to = Date.parse(history.to);
    const pts = history.points.filter(p => p.temperature !== null && p.temperature !== undefined);
    const lo = history.limits?.temperatureMin, hi = history.limits?.temperatureMax;
    const datasets = [
      { label: "max", data: pts.map(p => ({ x: Date.parse(p.t), y: p.temperatureMax })), borderWidth: 0, pointRadius: 0,
        pointHoverRadius: 0, fill: "+1", backgroundColor: C.band, tooltip: false, describe: () => "" },
      { label: "min", data: pts.map(p => ({ x: Date.parse(p.t), y: p.temperatureMin })), borderWidth: 0, pointRadius: 0,
        pointHoverRadius: 0, fill: false, tooltip: false, describe: () => "" },
      { label: "Temperature", data: pts.map(p => ({ x: Date.parse(p.t), y: p.temperature, p })),
        borderColor: C.line, borderWidth: 2, pointRadius: 0, pointHoverRadius: 4, pointHoverBackgroundColor: C.line,
        pointHoverBorderColor: C.surface, pointHoverBorderWidth: 2, tension: 0.2,
        segment: { borderColor: outOfRange(lo, hi) },
        describe: raw => [`${fmt(raw.y)} °C average`, `min ${fmt(raw.p.temperatureMin)} · max ${fmt(raw.p.temperatureMax)} °C`,
          `${raw.p.readings} reading${raw.p.readings === 1 ? "" : "s"}` + (raw.p.doorOpen ? " · door opened" : "")] }
    ];
    if (hi != null) datasets.push(limitDataset("max limit", hi, from, to));
    if (lo != null) datasets.push(limitDataset("min limit", lo, from, to));
    chart.data.datasets = datasets;
    chart.options.scales.x.min = from;
    chart.options.scales.x.max = to;
    // keep the limits in view, with a little room
    const values = pts.flatMap(p => [p.temperatureMin, p.temperatureMax]).concat([lo, hi]).filter(v => v != null);
    chart.options.scales.y.suggestedMin = values.length ? Math.min(...values) - 1 : undefined;
    chart.options.scales.y.suggestedMax = values.length ? Math.max(...values) + 1 : undefined;
    chart.update("none");
  }

  function drawHumidity(history) {
    const chart = charts.hum || (charts.hum = makeChart($("#chart-hum"), " %", 0));
    if (!chart) return;
    const from = Date.parse(history.from), to = Date.parse(history.to);
    const pts = history.points.filter(p => p.humidity !== null && p.humidity !== undefined);
    const lo = history.limits?.humidityMin, hi = history.limits?.humidityMax;
    const datasets = [
      { label: "Humidity", data: pts.map(p => ({ x: Date.parse(p.t), y: p.humidity, p })), borderColor: C.line,
        borderWidth: 2, pointRadius: 0, pointHoverRadius: 4, pointHoverBackgroundColor: C.line,
        pointHoverBorderColor: C.surface, pointHoverBorderWidth: 2, tension: 0.2,
        segment: { borderColor: outOfRange(lo, hi) },
        describe: raw => [`${fmt(raw.y, 0)} % average`, `${raw.p.readings} reading${raw.p.readings === 1 ? "" : "s"}`] }
    ];
    if (hi != null) datasets.push(limitDataset("max limit", hi, from, to));
    if (lo != null) datasets.push(limitDataset("min limit", lo, from, to));
    chart.data.datasets = datasets;
    chart.options.scales.x.min = from;
    chart.options.scales.x.max = to;
    chart.options.scales.y.suggestedMin = Math.max(0, Math.min(...pts.map(p => p.humidity), lo ?? 100) - 5);
    chart.options.scales.y.suggestedMax = Math.min(100, Math.max(...pts.map(p => p.humidity), hi ?? 0) + 5);
    chart.update("none");
  }

  function clearCharts() {
    for (const chart of Object.values(charts)) {
      chart.data.datasets = [];
      chart.update("none");
    }
  }

  // ---------- tables ----------

  const METRIC = { temperature: "Temperature", humidity: "Humidity", door: "Door open", silence: "No signal",
    early_warning: "Early warning" };

  function renderIncidents(list) {
    const root = $("#history-incidents");
    root.textContent = "";
    if (!list.length) {
      const li = document.createElement("li");
      li.className = "empty";
      li.textContent = "No incident for this sensor in the period.";
      root.append(li);
      return;
    }
    for (const i of list.slice().reverse()) {
      const li = document.createElement("li");
      li.className = "m-" + i.metric;
      const what = document.createElement("b");
      what.textContent = METRIC[i.metric] || i.metric || "Incident";
      const span = document.createElement("span");
      const duration = i.closedAt ? Math.round((Date.parse(i.closedAt) - Date.parse(i.openedAt)) / 60000) + " min" : "still open";
      span.textContent = ` ${when(i.openedAt)} · ${duration}` + (i.peakValue != null && i.metric !== "door" ? ` · peak ${fmt(i.peakValue)}` : "")
        + (i.acknowledgedBy ? ` · ✓ ${i.acknowledgedBy}` : " · not acknowledged") + (i.comment ? ` · “${i.comment}”` : "");
      li.append(what, span);
      root.append(li);
    }
  }

  function cell(row, text, cls) {
    const td = document.createElement("td");
    td.textContent = text;
    if (cls) td.className = cls;
    row.append(td);
  }

  function renderKpis(summary) {
    $("#kpi-compliance").textContent = pct(summary.compliancePercent);
    $("#kpi-readings").textContent = summary.readings.toLocaleString();
    $("#kpi-incidents").textContent = summary.incidents;
    $("#kpi-incidents-note").textContent = Object.entries(summary.incidentsByMetric || {})
      .map(([m, n]) => `${METRIC[m] || m} ${n}`).join(" · ");
    $("#kpi-alarm").textContent = minutes(summary.alarmMinutes);
    $("#kpi-mtta").textContent = minutes(summary.meanAckMinutes);
    $("#kpi-unack").textContent = summary.unacknowledged;
    $("#kpis").classList.toggle("warn", summary.compliancePercent != null && summary.compliancePercent < 95);

    const body = $("#kpi-table tbody");
    body.textContent = "";
    for (const k of summary.perSensor) {
      const tr = document.createElement("tr");
      cell(tr, k.sensorId);
      cell(tr, k.siteId);
      cell(tr, k.profile || "--");
      cell(tr, k.readings.toLocaleString(), "num");
      cell(tr, k.readings ? `${fmt(k.temperatureMin)} / ${fmt(k.temperatureAvg)} / ${fmt(k.temperatureMax)}` : "--", "num");
      cell(tr, pct(k.compliancePercent), "num" + (k.compliancePercent != null && k.compliancePercent < 95 ? " low" : ""));
      cell(tr, String(k.incidents), "num");
      cell(tr, minutes(k.alarmMinutes), "num");
      cell(tr, minutes(k.meanAckMinutes), "num");
      body.append(tr);
    }
  }

  function renderPoints(history) {
    const body = $("#points-table tbody");
    body.textContent = "";
    for (const p of history.points.slice(-200).reverse()) {
      const tr = document.createElement("tr");
      cell(tr, new Date(p.t).toLocaleString([], { hour12: false }));
      cell(tr, fmt(p.temperature), "num");
      cell(tr, fmt(p.temperatureMin), "num");
      cell(tr, fmt(p.temperatureMax), "num");
      cell(tr, fmt(p.humidity, 0), "num");
      cell(tr, p.doorOpen == null ? "--" : p.doorOpen ? "opened" : "shut");
      cell(tr, String(p.readings), "num");
      body.append(tr);
    }
  }

  // ---------- data ----------

  async function loadLists() {
    const [sensorList, sites] = await Promise.all([getJson("/api/sensors"), getJson("/api/sites")]);
    sensors = sensorList;
    const keep = sensorSelect.value;
    sensorSelect.textContent = "";
    for (const s of sensors) {
      const o = document.createElement("option");
      o.value = s.id;
      o.textContent = `${s.id} · ${s.name || s.siteId}`;
      sensorSelect.append(o);
    }
    if (keep && sensors.some(s => s.id === keep)) sensorSelect.value = keep;
    const keepSite = siteSelect.value;
    siteSelect.length = 1;
    for (const site of sites) {
      const o = document.createElement("option");
      o.value = site.id;
      o.textContent = `${site.id} · ${site.name || ""}`;
      siteSelect.append(o);
    }
    if (keepSite && sites.some(s => s.id === keepSite)) siteSelect.value = keepSite;
    loaded = true;
  }

  async function refresh() {
    if (!auth.token || loading || section.hidden) return;
    loading = true;
    setState("Loading…");
    try {
      if (!loaded) await loadLists();
      const w = windowParams();
      const sensorId = sensorSelect.value;
      const [history, summary] = await Promise.all([
        sensorId ? getJson("/api/reports/history?" + query({ sensorId, ...w, points: POINTS })) : null,
        getJson("/api/reports/kpi?" + query({ siteId: siteSelect.value, ...w }))
      ]);
      renderKpis(summary);
      if (history) {
        const name = history.name || history.sensorId;
        $("#chart-temp-title").textContent = `Temperature of ${name} (°C)`;
        $("#chart-hum-title").textContent = `Humidity of ${name} (%)`;
        drawTemperature(history);
        drawHumidity(history);
        renderIncidents(history.incidents);
        renderPoints(history);
        const n = history.points.reduce((sum, p) => sum + p.readings, 0);
        setState(n ? `${n.toLocaleString()} readings, averaged every ${history.bucketSeconds} s · updated ${new Date().toLocaleTimeString([], { hour12: false })}`
          : "No reading for this sensor in the period.");
      } else {
        clearCharts();
        setState("No sensor registered yet.");
      }
    } catch (e) {
      setState("History unavailable: " + e.message, true);
    } finally {
      loading = false;
    }
  }

  async function download(path, params, filename, button) {
    if (!auth.token) return;
    button.disabled = true;
    const label = button.textContent;
    button.textContent = "Preparing…";
    try {
      const r = await fetch(API_ORIGIN + path + "?" + query(params), {
        headers: { Authorization: "Bearer " + auth.token }, cache: "no-store"
      });
      if (r.status === 401) { signOut("Your session has ended. Sign in again."); return; }
      if (!r.ok) {
        let message = `${path} returned ${r.status}`;
        try { message = (await r.json()).error || message; } catch (e) { /* not JSON */ }
        throw new Error(message);
      }
      const url = URL.createObjectURL(await r.blob());
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      document.body.append(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (e) {
      setState("Export failed: " + (e.name === "TypeError" ? "the API is not reachable" : e.message), true);
    } finally {
      button.disabled = false;
      button.textContent = label;
    }
  }

  function fileName(kind, scope, ext) {
    const day = new Date().toISOString().slice(0, 10);
    return `coldtrace-${kind}${scope ? "-" + scope : ""}-${hours}h-${day}.${ext}`;
  }

  // ---------- events ----------

  section.querySelectorAll(".range button").forEach(b => b.addEventListener("click", () => {
    hours = Number(b.dataset.hours);
    section.querySelectorAll(".range button").forEach(x => {
      x.classList.toggle("active", x === b);
      x.setAttribute("aria-pressed", String(x === b));
    });
    refresh();
  }));
  sensorSelect.addEventListener("change", refresh);
  siteSelect.addEventListener("change", refresh);
  $("#export-pdf").addEventListener("click", e => download("/api/reports/compliance.pdf",
    { siteId: siteSelect.value, ...windowParams() }, fileName("conformite", siteSelect.value, "pdf"), e.currentTarget));
  $("#export-csv").addEventListener("click", e => download("/api/reports/compliance.csv",
    { siteId: siteSelect.value, ...windowParams() }, fileName("conformite", siteSelect.value, "csv"), e.currentTarget));
  $("#export-readings").addEventListener("click", e => download("/api/reports/measurements.csv",
    { sensorId: sensorSelect.value, ...windowParams() }, fileName("mesures", sensorSelect.value, "csv"), e.currentTarget));

  function session(signedIn) {
    clearInterval(timer);
    section.hidden = !signedIn;
    loaded = false;
    if (!signedIn) {
      clearCharts();
      $("#kpi-table tbody").textContent = "";
      $("#points-table tbody").textContent = "";
      $("#history-incidents").textContent = "";
      setState("");
      return;
    }
    refresh();
    timer = setInterval(() => { if (!document.hidden) refresh(); }, REFRESH_MS);
  }

  window.addEventListener("coldtrace:session", e => session(e.detail.signedIn));
  // monitor.js may have started the session before this script ran
  if (auth.token) session(true);
})();

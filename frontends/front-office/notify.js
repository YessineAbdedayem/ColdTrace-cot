// ColdTrace alerts on this device (Web Push): new incidents show as system notifications, even when the app is
// closed. The API decides who receives what (quality managers and admins: everything; drivers/operators: the sites
// they are assigned to). Uses monitor.js's session (auth) and getJson.
"use strict";

(() => {
  const button = document.getElementById("push-toggle");
  const state = document.getElementById("push-state");
  const supported = window.isSecureContext && "serviceWorker" in navigator && "PushManager" in window
    && "Notification" in window;
  let serverKey = null;
  let busy = false;

  function show(text, error = false) {
    state.hidden = !text;
    state.textContent = text || "";
    state.classList.toggle("err", error);
  }

  function keyBytes(base64url) {
    const s = atob(base64url.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - base64url.length % 4) % 4));
    return Uint8Array.from(s, c => c.charCodeAt(0));
  }

  async function subscription() {
    const registration = await navigator.serviceWorker.ready;
    return { registration, current: await registration.pushManager.getSubscription() };
  }

  function render(subscribed) {
    button.hidden = false;
    button.textContent = subscribed ? "Turn off alerts on this device" : "Enable alerts on this device";
    button.setAttribute("aria-pressed", String(subscribed));
    button.classList.toggle("on", subscribed);
  }

  async function refresh() {
    if (!supported || !auth.token) { button.hidden = true; show(""); return; }
    try {
      const status = await getJson("/api/push");
      if (!status.enabled) { button.hidden = true; show(""); return; }
      serverKey = status.publicKey;
      const { current } = await subscription();
      // a subscription made with an older server key no longer works: drop it
      if (current && current.options?.applicationServerKey) {
        const used = new Uint8Array(current.options.applicationServerKey);
        const now = keyBytes(serverKey);
        if (used.length !== now.length || used.some((b, i) => b !== now[i])) await current.unsubscribe();
      }
      const { current: valid } = await subscription();
      // the device may have changed hands: link it to the user signed in now
      if (valid) await save(valid);
      render(Boolean(valid));
      if (Notification.permission === "denied") show("Notifications are blocked for this site in the browser settings.", true);
    } catch (e) {
      button.hidden = true;
    }
  }

  async function save(sub) {
    await getJson("/api/push/subscriptions", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(sub.toJSON())
    });
  }

  async function enable() {
    const permission = await Notification.requestPermission();
    if (permission !== "granted") {
      show("Notifications were not allowed. Allow them in the browser settings to receive alerts.", true);
      return;
    }
    const { registration } = await subscription();
    const sub = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(serverKey) });
    try {
      await save(sub);
    } catch (e) {
      await sub.unsubscribe();
      throw e;
    }
    render(true);
    const r = await getJson("/api/push/test", { method: "POST" });
    show(r.sent ? "Alerts enabled. A test notification was sent to this device." : "Alerts enabled.");
  }

  async function disable() {
    const { current } = await subscription();
    if (current) {
      try {
        await getJson("/api/push/subscriptions", {
          method: "DELETE", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ endpoint: current.endpoint })
        });
      } finally {
        await current.unsubscribe();
      }
    }
    render(false);
    show("Alerts turned off on this device.");
  }

  button.addEventListener("click", async () => {
    if (busy || !auth.token) return;
    busy = true;
    button.disabled = true;
    try {
      if (button.getAttribute("aria-pressed") === "true") await disable(); else await enable();
    } catch (e) {
      show("Could not change the alerts of this device: " + e.message, true);
    } finally {
      busy = false;
      button.disabled = false;
    }
  });

  // The subscription stays when the session ends: a driver keeps receiving alerts with the app closed.
  window.addEventListener("coldtrace:session", e => { if (e.detail.signedIn) refresh(); else { button.hidden = true; show(""); } });
  if (auth.token) refresh();
})();

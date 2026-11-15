"use strict";

// Installation and shell updates are separate from operational data and actions.
(() => {
  const install = document.getElementById("install-app");
  const panel = document.getElementById("pwa-update");
  const message = document.getElementById("pwa-message");
  const update = document.getElementById("update-app");
  let prompt = null;
  let registration = null;
  let hadController = Boolean(navigator.serviceWorker?.controller);
  let requestedUpdate = false;
  let reloadRequired = false;
  let reloading = false;

  // Optional online typography must never block the offline document/parser.
  function loadOnlineFonts() {
    if (!navigator.onLine || document.getElementById("optional-fonts")) return;
    const fonts = document.createElement("link");
    fonts.id = "optional-fonts";
    fonts.rel = "stylesheet";
    fonts.href = "https://fonts.googleapis.com/css2?family=Big+Shoulders+Stencil+Display:wght@700;800&family=Big+Shoulders+Text:wght@400;600;700&family=Martian+Mono:wght@400;500;700&display=swap";
    document.head.append(fonts);
  }
  loadOnlineFonts();
  window.addEventListener("online", loadOnlineFonts);

  window.addEventListener("beforeinstallprompt", event => {
    event.preventDefault();
    prompt = event;
    install.hidden = false;
  });
  window.addEventListener("appinstalled", () => { install.hidden = true; prompt = null; });
  install.addEventListener("click", async () => {
    if (!prompt) return;
    const pending = prompt;
    prompt = null;
    install.hidden = true;
    await pending.prompt();
  });

  function offerUpdate() {
    panel.hidden = false;
    message.textContent = "An app update is ready. Finish any open acknowledgement before updating.";
    update.hidden = false;
  }

  const hasPendingWork = () => document.body.dataset.busy === "true" ||
    [...document.querySelectorAll(".ack-form:not([hidden])")].some(form => form.getClientRects().length > 0);

  update.addEventListener("click", () => {
    if (hasPendingWork()) {
      message.textContent = "Finish or cancel the acknowledgement and wait for pending actions, then select Update app.";
      return;
    }
    if (reloadRequired) {
      location.reload();
    } else if (registration?.waiting) {
      requestedUpdate = true;
      registration.waiting.postMessage({ type: "ACTIVATE_UPDATE" });
    }
  });

  if (!("serviceWorker" in navigator) || !window.isSecureContext) return;
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (!hadController) { hadController = true; return; }
    reloadRequired = true;
    if (requestedUpdate && !reloading && !hasPendingWork()) { reloading = true; location.reload(); }
    else offerUpdate(); // Another tab activated an update; keep this tab's form.
  });

  (async () => {
    try {
      registration = await navigator.serviceWorker.register("/sw.js", { scope: "/", updateViaCache: "none" });
      if (registration.waiting) offerUpdate();
      registration.addEventListener("updatefound", () => {
        const worker = registration.installing;
        worker?.addEventListener("statechange", () => {
          if (worker.state === "installed" && navigator.serviceWorker.controller) offerUpdate();
        });
      });
      document.addEventListener("visibilitychange", () => {
        if (!document.hidden && navigator.onLine) registration.update().catch(() => {});
      });
    } catch (error) {
      panel.hidden = false;
      update.hidden = true;
      message.textContent = "Offline app shell could not be prepared. Keep this page online; reload to retry.";
    }
  })();
})();

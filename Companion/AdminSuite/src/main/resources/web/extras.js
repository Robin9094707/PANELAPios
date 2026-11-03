/* Simplex Panel Web 9.9.0 – personal appearance (stored on the server per account), browser push, home-screen
   install guide for iPhone/iPad/Android, admin-log unread filter, username & public name, LuckPerms and Lands. */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, icon = S.icon, P = S.pages = S.pages || {};
  var store = { get: function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } }, set: function (k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } } };

  // ============================================================ Appearance (server-side per account)
  var ACCENTS = [["cyan", "Türkis"], ["blue", "Blau"], ["violet", "Violett"], ["emerald", "Smaragd"], ["amber", "Bernstein"], ["rose", "Rosé"], ["red", "Rot"], ["slate", "Schiefer"]];
  S.applyPrefs = function (p) {
    p = p || S.prefs || {};
    var root = document.documentElement;
    root.setAttribute("data-accent", p.accent || "cyan");
    root.setAttribute("data-density", p.density || "comfortable");
    root.setAttribute("data-radius", p.radius || "rounded");
    root.setAttribute("data-motion", p.motion || "full");
    root.setAttribute("data-bg", p.background || "glow");
    root.setAttribute("data-sidebar", p.sidebar || "expanded");
    root.style.setProperty("--scale", String((Number(p.font) || 100) / 100));
    if (p.theme && S.theme) S.theme(p.theme);
    store.set("simplex-look", JSON.stringify({ accent: p.accent, density: p.density, radius: p.radius, motion: p.motion, background: p.background, sidebar: p.sidebar, font: p.font }));
  };
  // First paint uses the last appearance of this browser until the account's settings arrive.
  try { var cached = JSON.parse(store.get("simplex-look") || "null"); if (cached) S.applyPrefs(cached); } catch (e) { /* ignore */ }
  S.loadPrefs = async function () {
    if (!S.has("web-prefs-v1")) { S.prefs = null; return null; }
    try { var r = await S.api("web/prefs", { quiet: true }); S.prefs = r.prefs || {}; S.applyPrefs(S.prefs); } catch (e) { S.prefs = null; }
    return S.prefs;
  };
  S.savePrefs = async function (patch) { var r = await S.api("web/prefs", { body: patch }); S.prefs = r.prefs; S.applyPrefs(S.prefs); return r; };

  // ============================================================ Platform & home-screen install
  var ua = navigator.userAgent;
  var platform = S.platform = {
    ios: /iPhone|iPad|iPod/.test(ua) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1),
    android: /Android/.test(ua),
    standalone: !!((window.matchMedia && (matchMedia("(display-mode: standalone)").matches || matchMedia("(display-mode: minimal-ui)").matches)) || navigator.standalone === true),
    samsung: /SamsungBrowser/.test(ua)
  };
  platform.safari = platform.ios && !/CriOS|FxiOS|EdgiOS|OPiOS|GSA\/|YaBrowser|DuckDuckGo|Brave|Instagram|FBAN|FBAV|Line\//.test(ua);
  platform.mobile = platform.ios || platform.android;
  if (platform.standalone) document.documentElement.setAttribute("data-standalone", "1");
  var deferredInstall = null;
  window.addEventListener("beforeinstallprompt", function (e) { e.preventDefault(); deferredInstall = e; S.renderInstallBanner(); });
  window.addEventListener("hashchange", function () { S.renderInstallBanner(); });
  window.addEventListener("appinstalled", function () { deferredInstall = null; store.set("simplex-install-dismissed", String(Date.now())); S.renderInstallBanner(); S.toast("App installiert", "ok", "Simplex Panel liegt jetzt auf deinem Startbildschirm."); });

  function step(n, text, extra) { return h("li", { class: "guide-step" }, h("span", { class: "num", text: String(n) }), h("div", {}, h("div", { text: text }), extra || null)); }
  function shareGlyph() { return h("span", { class: "glyph", title: "Teilen" }, h("svg", { class: "i", viewBox: "0 0 24 24" }, h("path", { d: "M12 3v12M8 7l4-4 4 4M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7" }))); }
  function guideBody() {
    if (platform.standalone) return S.notice("Simplex Panel läuft bereits als App vom Startbildschirm.", "ok", "check");
    if (platform.ios && !platform.safari) {
      return h("div", { class: "stack" }, S.notice(h("span", {}, h("b", { text: "Bitte in Safari öffnen. " }), "Nur Safari kann das Panel als App mit Push-Benachrichtigungen auf den Home-Bildschirm legen."), "warn", "alert"),
        h("ol", { class: "guide" }, step(1, "Adresse kopieren", S.btn("Link kopieren", { size: "sm", icon: "copy", onClick: function () { return S.copy(location.origin + "/", "Link"); } })),
          step(2, "Safari öffnen und den Link in die Adressleiste einfügen."), step(3, "Dann wie unten beschrieben „Zum Home-Bildschirm“ wählen.")));
    }
    if (platform.ios) {
      return h("div", { class: "stack" }, h("ol", { class: "guide" },
        step(1, "Unten in Safari auf „Teilen“ tippen.", shareGlyph()), step(2, "Nach unten scrollen und „Zum Home-Bildschirm“ wählen."),
        step(3, "„Als Web-App öffnen“ eingeschaltet lassen und „Hinzufügen“ tippen."), step(4, "Simplex Panel vom Home-Bildschirm starten und dort einmal anmelden.")),
        S.notice("Push-Benachrichtigungen funktionieren auf dem iPhone/iPad ab iOS 16.4 nur in der App vom Home-Bildschirm – nicht in einem Safari-Tab.", "", "bell"));
    }
    if (platform.android) {
      return h("div", { class: "stack" }, deferredInstall ? h("div", {}, S.btn("Jetzt installieren", { kind: "primary", icon: "download", onClick: installNow })) : null,
        h("ol", { class: "guide" }, platform.samsung ? step(1, "Unten auf das Menü (☰) tippen.") : step(1, "Oben rechts auf das Menü (⋮) tippen."),
          step(2, platform.samsung ? "„Seite hinzufügen zu“ → „Startbildschirm“ wählen." : "„App installieren“ bzw. „Zum Startbildschirm hinzufügen“ wählen."), step(3, "Bestätigen und Simplex Panel vom Startbildschirm öffnen.")));
    }
    return h("div", { class: "stack" }, deferredInstall ? h("div", {}, S.btn("Als App installieren", { kind: "primary", icon: "download", onClick: installNow })) : null,
      h("ol", { class: "guide" }, step(1, "Chrome/Edge: Installationssymbol rechts in der Adressleiste anklicken."), step(2, "Safari (macOS): Menü „Ablage“ → „Zum Dock hinzufügen“.")));
  }
  async function installNow() { if (!deferredInstall) return; deferredInstall.prompt(); try { await deferredInstall.userChoice; } catch (e) { /* ignore */ } deferredInstall = null; S.renderInstallBanner(); }
  S.installGuide = function () { S.modal(platform.ios ? "Zum Home-Bildschirm hinzufügen" : "Als App installieren", guideBody(), [], { icon: "phone" }); };
  S.installHint = function () {
    if (platform.standalone || !platform.mobile) return null;
    return h("button", { type: "button", class: "install-hint", onClick: S.installGuide }, icon("phone"), h("span", { text: platform.ios ? (platform.safari ? "Als App auf den Home-Bildschirm legen" : "In Safari öffnen, um als App zu installieren") : "Als App installieren" }), icon("chevRight"));
  };
  S.renderInstallBanner = function () {
    var host = document.getElementById("install-banner"); if (!host) return;
    S.clear(host);
    var dismissed = Number(store.get("simplex-install-dismissed") || 0);
    var route = location.hash.replace(/^#\/?/, "").split(/[/?]/)[0];
    // Chats and tickets need the full height for the composer; the guide stays reachable under Darstellung/Benachrichtigungen.
    var show = !platform.standalone && (platform.mobile || deferredInstall) && Date.now() - dismissed > 14 * 86400e3 && ["chat", "staff-chat", "ticket"].indexOf(route) < 0;
    host.classList.toggle("hidden", !show);
    if (!show) return;
    host.appendChild(h("div", { class: "install-banner" }, h("img", { src: "/assets/icon-192.png", alt: "", width: 40, height: 40 }),
      h("div", { class: "grow" }, h("b", { text: platform.ios && !platform.safari ? "In Safari öffnen" : "Simplex Panel als App" }),
        h("small", { text: platform.ios ? (platform.safari ? "Auf den Home-Bildschirm – mit Push und Vollbild." : "Nur Safari kann es auf den Home-Bildschirm legen.") : "Vollbild, schneller Start und Push." })),
      deferredInstall ? S.btn("Installieren", { kind: "primary", size: "sm", onClick: installNow }) : S.btn("Anleitung", { kind: "primary", size: "sm", onClick: S.installGuide }),
      S.btn("", { kind: "ghost", size: "sm icon", icon: "x", title: "Ausblenden", onClick: function () { store.set("simplex-install-dismissed", String(Date.now())); S.renderInstallBanner(); } })));
  };

  // ============================================================ Service worker & push
  S.registerWorker = async function () {
    if (!("serviceWorker" in navigator) || !window.isSecureContext) { S.workerError = "Kein sicherer Kontext"; return null; }
    try { S.worker = await navigator.serviceWorker.register("/sw.js", { scope: "/" }); S.workerError = ""; return S.worker; }
    catch (e) { S.workerError = (e && e.message) || String(e); return null; }
  };
  function b64ToBytes(b64) { var s = atob(b64.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((b64.length + 3) % 4)); var out = new Uint8Array(s.length); for (var i = 0; i < s.length; i++) out[i] = s.charCodeAt(i); return out; }
  function bytesToB64(buf) { var b = new Uint8Array(buf), s = ""; for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]); return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); }
  function ready(ms) { return Promise.race([navigator.serviceWorker.ready, new Promise(function (_, reject) { setTimeout(function () { reject(new Error("Service Worker nicht aktiv")); }, ms || 6000); })]); }
  S.pushState = async function () {
    var st = { supported: "serviceWorker" in navigator && "PushManager" in window && "Notification" in window, secure: window.isSecureContext, permission: "Notification" in window ? Notification.permission : "unsupported", subscription: null };
    if (st.supported && st.secure) { try { var reg = S.worker || await navigator.serviceWorker.getRegistration("/"); st.worker = !!reg; if (reg) st.subscription = await reg.pushManager.getSubscription(); } catch (e) { st.error = e.message; } }
    return st;
  };
  S.enablePush = async function (events) {
    if (!("Notification" in window)) throw new Error("Dieser Browser unterstützt keine Benachrichtigungen.");
    var permission = await Notification.requestPermission();
    if (permission !== "granted") throw new Error(permission === "denied" ? "Benachrichtigungen sind blockiert. In den Browser-/Systemeinstellungen für diese Seite erlauben." : "Benachrichtigungen wurden nicht erlaubt.");
    if (!S.worker) await S.registerWorker();
    if (!S.worker) throw new Error("Service Worker konnte nicht starten (" + (S.workerError || "unbekannt") + "). Push benötigt eine Domain mit gültigem HTTPS-Zertifikat.");
    var info = await S.api("push"); var reg = await ready();
    var sub = await reg.pushManager.getSubscription();
    if (sub && sub.options && sub.options.applicationServerKey && bytesToB64(sub.options.applicationServerKey) !== info.publicKey) { await sub.unsubscribe(); sub = null; }
    if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(info.publicKey) });
    var json = sub.toJSON();
    return S.api("push/subscribe", { body: { endpoint: json.endpoint, keys: json.keys, events: events } });
  };
  S.disablePush = async function () {
    var st = await S.pushState(); if (!st.subscription) return;
    try { await S.api("push/unsubscribe", { body: { endpoint: st.subscription.endpoint } }); } finally { await st.subscription.unsubscribe(); }
  };
  /** After sign-in: keeps an existing browser subscription attached to the signed-in account (no prompt). */
  S.resyncPush = async function () {
    if (!S.has("web-push-v1") || !("Notification" in window) || Notification.permission !== "granted") return;
    try { var st = await S.pushState(); if (!st.subscription) return; var json = st.subscription.toJSON(); await S.api("push/subscribe", { quiet: true, body: { endpoint: json.endpoint, keys: json.keys } }); } catch (e) { /* optional */ }
  };
  S.appBadge = function (n) { try { if (navigator.setAppBadge) { if (n > 0) navigator.setAppBadge(n); else navigator.clearAppBadge(); } } catch (e) { /* optional */ } };

  // ============================================================ Page: notifications
  P.notifications = async function (param, query, alive) {
    var info = await S.api("push"); if (!alive()) return;
    var st = await S.pushState(); if (!alive()) return;
    var current = null;
    if (st.subscription) { try { var mine = await S.api("push", { query: { endpoint: st.subscription.endpoint } }); if (!alive()) return; info = mine; current = (mine.devices || []).filter(function (d) { return d.id === mine.current; })[0] || null; } catch (e) { /* keep */ } }
    var allowed = (info.events || []).filter(function (e) { return e.allowed; });
    var selected = {}; ((current && current.events) || info.defaults || []).forEach(function (id) { selected[id] = true; });
    var problems = [];
    if (!st.secure) problems.push("Keine sichere Verbindung (HTTPS).");
    if (!st.supported) problems.push(platform.ios && !platform.standalone ? "Auf dem iPhone/iPad gibt es Push nur in der App vom Home-Bildschirm (iOS 16.4+)." : "Dieser Browser unterstützt keine Push-Benachrichtigungen.");
    if (st.supported && !S.worker && S.workerError) problems.push("Service Worker nicht aktiv: " + S.workerError + (info.domain ? "" : " – Push benötigt eine eigene Domain mit gültigem HTTPS-Zertifikat (wie Passkeys)."));
    var statusBadge = st.permission === "denied" ? S.badge("Blockiert", "danger", "x") : st.subscription && current ? S.badge("Aktiv auf diesem Gerät", "ok", "check") : S.badge("Aus", "", "bell");
    var eventList = h("div", { class: "check-grid" }, allowed.map(function (e) {
      var cb = h("input", { type: "checkbox", checked: !!selected[e.id] }); cb.addEventListener("change", function () { selected[e.id] = cb.checked; });
      return h("label", { class: "check" }, cb, e.title);
    }));
    function chosen() { return allowed.filter(function (e) { return selected[e.id]; }).map(function (e) { return e.id; }); }
    var actions = h("div", { class: "row wrap" },
      st.subscription && current ? S.btn("Auswahl speichern", { kind: "primary", icon: "check", onClick: async function () { await S.api("push/update", { body: { id: current.id, events: chosen() } }); S.toast("Gespeichert"); } })
        : S.btn("Push auf diesem Gerät aktivieren", { kind: "primary", icon: "bell", disabled: !allowed.length || st.permission === "denied", onClick: async function () {
          if (platform.ios && !platform.standalone) { S.installGuide(); return; }
          var r = await S.enablePush(chosen()); S.toast(r.title || "Push aktiviert", "ok", r.description); S.reload();
        } }),
      st.subscription ? S.btn("Test senden", { icon: "send", onClick: async function () { var r = await S.api("push/test", { body: {} }); S.toast(r.title, r.ok ? "ok" : "err", r.description); } }) : null,
      st.subscription ? S.btn("Auf diesem Gerät ausschalten", { kind: "danger", icon: "x", onClick: async function () { await S.disablePush(); S.toast("Push ausgeschaltet"); S.reload(); } }) : null);
    var here = S.card("Dieses Gerät", { icon: platform.mobile ? "phone" : "laptop", sub: (platform.ios ? "iPhone/iPad" : platform.android ? "Android" : "Computer") + (platform.standalone ? " · App vom Startbildschirm" : " · Browser"), actions: statusBadge },
      h("div", { class: "stack" }, problems.length ? S.notice(problems.join(" "), "warn", "alert") : null,
        platform.ios && !platform.standalone ? h("div", {}, S.btn(platform.safari ? "Anleitung: Zum Home-Bildschirm" : "Anleitung: In Safari öffnen", { icon: "phone", onClick: S.installGuide })) : null,
        h("div", { class: "section-title" }, h("h2", { text: "Benachrichtigen bei" })), allowed.length ? eventList : S.notice("Für dein Konto sind keine Benachrichtigungsarten freigegeben.", "", "lock"),
        S.notice("Admin-Aktionen folgen deinem Admin-Log-Filter (z. B. nur Team). Ruhezeiten aus der App gelten auch für Browser-Push.", "", "info"), actions));
    var devices = S.card("Alle Geräte mit Push", { icon: "devices", cls: "pad-list", sub: (info.devices || []).length + " angemeldet · " + (info.status || "") }, h("div", { class: "list" }, (info.devices || []).length ? info.devices.map(function (d) {
      return h("div", { class: "item" }, h("div", { class: "avatar device sm" }, icon(d.os === "iOS" || d.os === "Android" ? "phone" : "laptop")),
        h("div", { class: "meta" }, h("b", { text: (d.label || ((d.browser || "Browser") + (d.os ? " · " + d.os : ""))) + (current && d.id === current.id ? " (dieses Gerät)" : "") }),
          h("small", { text: (d.service || "") + " · seit " + S.date(d.created) + (d.delivered ? " · zuletzt zugestellt " + S.ago(d.delivered) : "") + " · " + (d.events || []).length + " Arten" })),
        S.btn("", { size: "sm icon", kind: "ghost", icon: "trash", title: "Entfernen", onClick: async function () { await S.api("push/update", { body: { id: d.id, operation: "delete" } }); S.reload(); } }));
    }) : S.empty("bell", "Noch kein Gerät", "Aktiviere Push oben auf jedem Gerät, das Hinweise bekommen soll.")));
    S.title("Benachrichtigungen", "Push für Browser und Home-Bildschirm-App");
    S.mount(h("div", { class: "stack" }, h("div", { class: "grid two" }, here, devices)));
  };

  // ============================================================ Page: appearance
  P.appearance = async function (param, query, alive) {
    var r = await S.api("web/prefs"); if (!alive()) return;
    var p = Object.assign({}, r.prefs);
    async function set(patch) { Object.assign(p, patch); S.applyPrefs(p); try { await S.savePrefs(patch); } catch (e) { S.toast(e.message, "err"); } }
    function seg(key, options) { return S.segmented(options, p[key], function (v) { var o = {}; o[key] = v; set(o); }); }
    var swatches = h("div", { class: "swatches", role: "radiogroup", "aria-label": "Akzentfarbe" }, ACCENTS.map(function (a) {
      var b = h("button", { type: "button", class: "swatch" + (p.accent === a[0] ? " on" : ""), "data-accent-swatch": a[0], title: a[1], "aria-label": a[1] });
      b.addEventListener("click", function () { swatches.querySelectorAll(".swatch").forEach(function (x) { x.classList.remove("on"); }); b.classList.add("on"); set({ accent: a[0] }); });
      return b;
    }));
    var font = h("input", { type: "range", min: 85, max: 130, step: 5, value: p.font || 100, class: "range" }), fontLabel = h("b", { text: (p.font || 100) + " %" });
    var fontTimer; font.addEventListener("input", function () { fontLabel.textContent = font.value + " %"; document.documentElement.style.setProperty("--scale", String(font.value / 100)); clearTimeout(fontTimer); fontTimer = setTimeout(function () { set({ font: Number(font.value) }); }, 400); });
    var pages = []; S.nav.forEach(function (g) { g.items.forEach(function (i) { if (!S.denied(i.id)) pages.push(i); }); });
    var start = h("select", { class: "input" }, pages.map(function (i) { return h("option", { value: i.id, text: i.label, selected: i.id === p.start }); }));
    start.addEventListener("change", function () { set({ start: start.value }); });
    var tabs = (p.tabs || []).slice(0, 4); while (tabs.length < 4) tabs.push("");
    var tabSelects = tabs.map(function (value, index) {
      var sel = h("select", { class: "input" }, h("option", { value: "", text: "– leer –" }), pages.map(function (i) { return h("option", { value: i.id, text: i.label, selected: i.id === value }); }));
      sel.addEventListener("change", function () { tabs[index] = sel.value; set({ tabs: tabs.filter(Boolean) }).then(function () { S.rebuildShell && S.rebuildShell(); }); });
      return S.field("Tab " + (index + 1), sel);
    });
    var hidden = {}; (p.hidden || []).forEach(function (id) { hidden[id] = true; });
    var hideList = h("div", { class: "check-grid" }, pages.filter(function (i) { return ["dashboard", "appearance", "security"].indexOf(i.id) < 0; }).map(function (i) {
      var cb = h("input", { type: "checkbox", checked: !hidden[i.id] });
      cb.addEventListener("change", function () { hidden[i.id] = !cb.checked; set({ hidden: Object.keys(hidden).filter(function (k) { return hidden[k]; }) }).then(function () { S.rebuildShell && S.rebuildShell(); }); });
      return h("label", { class: "check" }, cb, i.label);
    }));
    S.title("Darstellung", "Wird pro Konto auf dem Server gespeichert",
      S.btn("Zurücksetzen", { size: "sm", icon: "refresh", onClick: async function () { if (!(await S.confirm("Darstellung zurücksetzen?", "Alle persönlichen Einstellungen des Webpanels werden auf Standard gesetzt.", "Zurücksetzen"))) return; await S.savePrefs({ reset: true }); S.rebuildShell && S.rebuildShell(); S.reload(); } }));
    S.mount(h("div", { class: "stack" },
      h("div", { class: "grid two" },
        S.card("Farben", { icon: "sparkles" }, h("div", { class: "stack" }, S.field("Erscheinungsbild", seg("theme", [["auto", "Automatisch"], ["dark", "Dunkel"], ["light", "Hell"]])), S.field("Akzentfarbe", swatches),
          S.field("Hintergrund", seg("background", [["glow", "Mit Farbverlauf"], ["plain", "Schlicht"]])))),
        S.card("Größe & Dichte", { icon: "grid" }, h("div", { class: "stack" }, S.field("Schriftgröße der Inhalte", h("div", { class: "row" }, font, fontLabel)),
          S.field("Dichte", seg("density", [["comfortable", "Großzügig"], ["compact", "Kompakt"]])), S.field("Ecken", seg("radius", [["rounded", "Rund"], ["soft", "Weich"], ["sharp", "Eckig"]])),
          S.field("Animationen", seg("motion", [["full", "An"], ["reduced", "Reduziert"]]))))),
      h("div", { class: "grid two" },
        S.card("Navigation", { icon: "menu" }, h("div", { class: "stack" }, S.field("Startseite nach dem Anmelden", start),
          S.field("Seitenleiste (Computer)", seg("sidebar", [["expanded", "Ausgeklappt"], ["collapsed", "Nur Symbole"]])),
          h("div", { class: "section-title" }, h("h2", { text: "Tableiste (Handy)" })), h("div", { class: "grid two" }, tabSelects))),
        S.card("Menüpunkte anzeigen", { icon: "list", sub: "Ausgeblendete Bereiche bleiben über die Adresse erreichbar." }, hideList)),
      S.card("Chat", { icon: "chat" }, S.field("Enter-Taste im Chat", seg("chatEnter", [["send", "Senden (Umschalt+Enter = neue Zeile)"], ["newline", "Neue Zeile (Strg/⌘+Enter = senden)"]]))),
      platform.mobile && !platform.standalone ? S.card("App auf dem Startbildschirm", { icon: "phone" }, guideBody()) : null));
  };

  // ============================================================ Admin log: unread filter
  var CAT = { moderation: "Moderation", bans: "Banns", mutes: "Mutes", punishments: "Strafen", vanish: "Vanish", devices: "Geräte", admin: "Verwaltung", player: "Spieler", server: "Server", settings: "Einstellungen", access: "Zugänge", other: "Sonstiges", view: "Nur angesehen" };
  S.adminLogSettings = function (overview) {
    var s = Object.assign({ mode: "staff", admins: [], categories: [], views: false }, overview.settings || {});
    var picked = {}; (s.admins || []).forEach(function (a) { picked[a] = true; });
    var cats = {}; (s.categories || []).forEach(function (c) { cats[c] = true; });
    var adminBox = h("div", { class: "check-grid" }), body;
    function renderAdmins() {
      S.clear(adminBox);
      (overview.admins || []).forEach(function (a) {
        var cb = h("input", { type: "checkbox", checked: !!picked[a.admin], disabled: a.self }); cb.addEventListener("change", function () { picked[a.admin] = cb.checked; });
        adminBox.appendChild(h("label", { class: "check" }, cb, h("span", { class: "grow" }, a.name + (a.self ? " (du)" : "")), a.staff ? S.badge("Team", "accent") : S.badge("Spieler")));
      });
      adminBox.classList.toggle("hidden", s.mode !== "selected");
    }
    renderAdmins();
    var catBox = h("div", { class: "check-grid" }, Object.keys(CAT).filter(function (k) { return k !== "view"; }).map(function (k) {
      var cb = h("input", { type: "checkbox", checked: !!cats[k] }); cb.addEventListener("change", function () { cats[k] = cb.checked; });
      return h("label", { class: "check" }, cb, CAT[k]);
    }));
    var views = h("input", { type: "checkbox", checked: !!s.views });
    var help = h("p", { class: "muted small", style: { margin: 0 } });
    var TEXT = { staff: "Nur Aktionen des Teams (Admins aus App, Konsole, Discord sowie OP/LuckPerms-Admins im Spiel) erhöhen den Zähler. Aktionen normaler Spieler bleiben sichtbar, aber still.",
      all: "Jede protokollierte Aktion erhöht den Zähler.", selected: "Nur die ausgewählten Admins erhöhen den Zähler.", none: "Kein Zähler und keine Push-Hinweise für Admin-Aktionen. Das Protokoll bleibt vollständig." };
    help.textContent = TEXT[s.mode];
    var modes = S.segmented([["staff", "Nur Team"], ["selected", "Ausgewählte"], ["all", "Alle"], ["none", "Aus"]], s.mode, function (v) { s.mode = v; help.textContent = TEXT[v]; renderAdmins(); });
    body = h("div", { class: "stack" }, S.field("Was zählt als ungelesen?", modes), help, adminBox,
      h("div", { class: "section-title" }, h("h2", { text: "Nur diese Kategorien (leer = alle)" })), catBox,
      h("label", { class: "check" }, views, "Reine Ansichten (geöffnet/abgefragt) mitzählen"),
      S.notice("Gilt nur für dich – App und Webpanel teilen diese Einstellung. Push für „Admin-Aktionen“ folgt demselben Filter.", "", "info"));
    var dlg = S.modal("Ungelesen-Zähler einstellen", body, [S.btn("Abbrechen", { onClick: function () { dlg.close(); } }), S.btn("Speichern", { kind: "primary", icon: "check", onClick: async function () {
      var r = await S.api("admin-log/settings", { body: { mode: s.mode, admins: Object.keys(picked).filter(function (k) { return picked[k]; }), categories: Object.keys(cats).filter(function (k) { return cats[k]; }), views: views.checked } });
      dlg.close(); S.toast(r.title, "ok", r.description); S.reload();
    } })], { icon: "filter", wide: true });
  };
  S.adminLogModeText = function (mode) { return { staff: "Zähler: nur Team", all: "Zähler: alle Aktionen", selected: "Zähler: ausgewählte Admins", none: "Zähler aus" }[mode] || ""; };

  // ============================================================ Account: username & public name
  S.nameCard = function (a) {
    var isUser = a.kind === "user";
    var publicInput = S.input({ value: a.publicName || "", placeholder: a.publicDefault || "Team", maxlength: 32 });
    return S.card("Name & Anzeige", { icon: "user", sub: "Login-Name und der Name, den Spieler sehen." }, h("div", { class: "stack" },
      isUser ? h("div", { class: "row wrap" }, h("div", { class: "grow" }, h("div", { class: "muted tiny", text: "Benutzername (Login, privat)" }), h("b", { class: "mono", text: a.username })),
        S.btn("Ändern", { icon: "edit", disabled: !S.has("account-rename-v1"), onClick: function () { S.renameDialog(a.username, true); } })) : S.notice("Für API-Schlüssel gibt es keinen Benutzernamen. Wechsle zu Benutzername und Passwort, um ihn festzulegen.", "", "info"),
      S.field("Öffentlicher Name bei Ticketantworten", publicInput, "Leer = „" + (a.publicDefault || "Team") + "“. Dein Login-Name wird Spielern nie angezeigt."),
      h("div", {}, S.btn("Speichern", { kind: "primary", icon: "check", disabled: !S.has("public-name-v1"), onClick: async function () {
        var r = await S.api("account/public-name", { body: { name: publicInput.value.trim() } }); S.account.publicName = r.publicName; S.toast(r.title, "ok", r.description);
      } }))));
  };
  S.renameDialog = function (current, self) {
    var name = S.input({ value: current, autocapitalize: "none", spellcheck: "false", placeholder: "neuer-name" });
    var pass = S.input({ type: "password", autocomplete: "current-password", placeholder: self ? "Dein Passwort" : "Dein (Hauptadmin-)Passwort" });
    var dlg = S.modal(self ? "Benutzernamen ändern" : "„" + current + "“ umbenennen", h("div", { class: "stack" },
      S.field("Neuer Benutzername", name, "1–64 Zeichen: a–z, 0–9, Punkt, - oder _"), S.field("Zur Bestätigung", pass),
      S.notice("Passwort, Zwei-Faktor-Schutz, Passkeys, Rechte, Benachrichtigungen und alle angemeldeten Geräte bleiben erhalten. Ab sofort mit dem neuen Namen anmelden.", "", "info")),
      [S.btn("Abbrechen", { onClick: function () { dlg.close(); } }), S.btn("Umbenennen", { kind: "primary", icon: "check", onClick: async function () {
        var r = await S.api("account/rename", { body: { username: name.value.trim().toLowerCase(), password: pass.value, target: self ? "" : current } });
        dlg.close(); S.toast(r.title, "ok", r.description);
        if (r.self) { S.account = await S.api("account"); S.rebuildShell && S.rebuildShell(); }
        S.reload();
      } })], { icon: "edit" });
  };

  // ============================================================ Page: LuckPerms
  function plain(text) { return String(text || "").replace(/[&§][0-9a-fk-orx]/gi, "").replace(/<[^>]{1,24}>/g, "").replace(/\{#[0-9a-f]{6}\}/gi, ""); }
  async function lp(values, title, danger) { var r = await S.runAction("luckperms.edit", values, { title: title || "LuckPerms ändern?", danger: danger }); if (r) setTimeout(S.reload, 900); return r; }
  function nodeRow(n, remove) {
    return h("div", { class: "item" }, h("span", { class: "dot " + (n.value === false ? "warn" : "on") }),
      h("div", { class: "meta" }, h("b", { class: "mono", text: n.key }), h("small", { text: (n.value === false ? "verweigert" : "erlaubt") + (n.expiry ? " · bis " + S.date(n.expiry) : "") + (n.context ? " · " + n.context : "") })),
      remove ? S.btn("", { size: "sm icon", kind: "ghost", icon: "trash", title: "Entfernen", disabled: !S.can("action.luckperms.edit"), onClick: remove }) : null);
  }
  function permForm(onSubmit) {
    var picker = S.permInput ? S.permInput() : null, node = picker ? picker.input : S.input({ placeholder: "z. B. essentials.fly", autocapitalize: "none" }), deny = h("input", { type: "checkbox" }), duration = S.input({ placeholder: "leer = dauerhaft, z. B. 7d" }), context = S.input({ placeholder: "optional: world=farmwelt" });
    return h("div", { class: "stack" }, h("div", { class: "grid two" }, S.field("Recht", picker ? picker.el : node), S.field("Dauer", duration)), h("div", { class: "grid two" }, S.field("Kontext", context), h("label", { class: "check", style: { alignSelf: "end" } }, deny, "Verweigern (false)")),
      h("div", {}, S.btn("Recht setzen", { kind: "primary", icon: "plus", disabled: !S.can("action.luckperms.edit"), onClick: function () { return onSubmit({ node: node.value.trim(), value: deny.checked ? "false" : "true", duration: duration.value.trim(), context: context.value.trim() }); } })));
  }
  /** 10.0.0: node list with a filter field; every entry can be removed with one click. */
  function nodeList(title, list, sub, makeRow, empty) {
    var box = h("div", { class: "list" }), filter = "";
    function render() {
      S.clear(box);
      var shown = list.filter(function (n) { return !filter || (n.key + " " + (n.context || "")).toLowerCase().indexOf(filter) >= 0; });
      if (!shown.length) box.appendChild(h("div", { class: "item muted small", text: list.length ? "Kein Recht passt zum Filter." : empty }));
      shown.forEach(function (n) { box.appendChild(makeRow(n)); });
    }
    render();
    var search = list.length > 6 ? h("div", { class: "search", style: { width: "min(240px, 46%)" } }, icon("search"), S.input({ placeholder: "Filtern", onInput: function (e) { filter = e.target.value.toLowerCase(); render(); } })) : null;
    return S.card(title, { icon: "key", cls: "pad-list", sub: sub, actions: search }, box);
  }
  function affixView(text) { return S.mcText ? S.mcText(text) : document.createTextNode(plain(text)); }
  P.luckperms = async function (param, query, alive) {
    var parts = (param || "").split("/"), kind = parts[0], id = parts.slice(1).join("/");
    if (kind === "group" && id) return lpGroup(id, alive);
    if (kind === "player" && id) return lpPlayer(id, alive);
    var d = await S.api("data/luckperms"); if (!alive()) return;
    S.title("Ränge & Rechte", d.installed ? "LuckPerms " + (d.version || "") : "", d.installed ? S.btn("Neue Gruppe", { kind: "primary", size: "sm", icon: "plus", disabled: !S.can("action.luckperms.edit"), onClick: async function () {
      var n = await S.prompt("Neue LuckPerms-Gruppe", "Name (a–z, 0–9, _ oder -)", "", "Anlegen"); if (!n) return; await lp({ operation: "group-create", group: n.trim().toLowerCase() }, "Gruppe „" + n + "“ anlegen?");
    } }) : null);
    if (!d.installed) { S.mount(S.card(null, {}, S.empty("crown", "LuckPerms ist nicht installiert", "Installiere LuckPerms, um Gruppen, Ränge und Rechte hier zu verwalten."))); return; }
    var search = S.input({ placeholder: "Spielername oder UUID", autocapitalize: "none" });
    var lookup = h("form", { class: "row" }, h("div", { class: "search grow" }, icon("search"), search), S.btn("Öffnen", { kind: "primary", submit: true, icon: "chevRight" }));
    lookup.addEventListener("submit", function (e) { e.preventDefault(); if (search.value.trim()) S.go("#/luckperms/player/" + encodeURIComponent(search.value.trim())); });
    var groups = S.card("Gruppen", { icon: "crown", cls: "pad-list", sub: d.groups.length + " Gruppen · sortiert nach Gewicht" }, h("div", { class: "list" }, d.groups.map(function (g) {
      return h("div", { class: "item link", onClick: function () { S.go("#/luckperms/group/" + encodeURIComponent(g.name)); } }, h("div", { class: "avatar device sm" }, icon("crown")),
        h("div", { class: "meta" }, h("b", { text: (g.displayName || g.name) + (g.displayName && g.displayName !== g.name ? " (" + g.name + ")" : "") }), g.prefix || g.suffix ? h("div", { class: "mc-preview sm" }, affixView(g.prefix), h("span", { class: "mc-name", text: "Name" }), affixView(g.suffix)) : null, h("small", { text: g.permissions + " Rechte" + (g.parents.length ? " · erbt von " + g.parents.join(", ") : "") })),
        S.badge("Gewicht " + g.weight), icon("chevRight", "chev"));
    })));
    var team = S.card("Team", { icon: "users", cls: "pad-list", sub: "Erkannte Teammitglieder und ihre Gruppen" }, h("div", { class: "list" }, (d.staff || []).length ? d.staff.map(function (m) {
      return h("div", { class: "item link", onClick: function () { S.go("#/luckperms/player/" + encodeURIComponent(m.uuid)); } }, S.avatar(m.name, "sm"),
        h("div", { class: "meta" }, h("b", { text: m.name }), h("small", { text: "Primär: " + m.primary + " · " + (m.groups || []).join(", ") })), m.online ? h("span", { class: "dot on", title: "online" }) : null, icon("chevRight", "chev"));
    }) : h("div", { class: "item muted small", text: "Keine Teammitglieder erkannt." })));
    var tracks = (d.tracks || []).length ? S.card("Tracks (Beförderungsleitern)", { icon: "activity" }, h("div", { class: "stack" }, d.tracks.map(function (t) { return h("div", { class: "row wrap" }, h("b", { text: t.name }), h("span", { class: "muted small", text: t.groups.join(" → ") })); }))) : null;
    S.mount(h("div", { class: "stack" }, S.card("Spieler verwalten", { icon: "user" }, lookup), h("div", { class: "grid two" }, groups, h("div", { class: "stack" }, team, tracks)),
      h("div", {}, S.btn("LuckPerms synchronisieren", { size: "sm", icon: "refresh", disabled: !S.can("action.luckperms.edit"), onClick: function () { return lp({ operation: "sync" }, "LuckPerms-Daten neu laden?"); } }))));
  };
  async function lpGroup(name, alive) {
    var g = await S.api("data/luckperms", { query: { group: name } }); if (!alive()) return;
    var all = await S.api("data/luckperms"); if (!alive()) return;
    S.title(g.displayName || g.name, "Gruppe", h("a", { class: "btn ghost sm", href: "#/luckperms" }, icon("chevLeft"), h("span", { class: "hide-mobile", text: "Ränge" })));
    var perms = (g.nodes || []).filter(function (n) { return !/^(group|prefix|suffix|weight|displayname|meta)\./.test(n.key); });
    var head = S.card(null, { cls: "hero" }, h("div", { class: "row wrap", style: { gap: "16px" } }, h("div", { class: "avatar lg device" }, icon("crown")),
      h("div", { class: "grow" }, h("h2", { style: { margin: 0 }, text: g.displayName || g.name }), h("div", { class: "row wrap", style: { gap: "6px", marginTop: "6px" } }, S.badge("Gewicht " + g.weight), g.prefix || g.suffix ? h("span", { class: "mc-preview sm" }, affixView(g.prefix), h("span", { class: "mc-name", text: "Steve" }), affixView(g.suffix)) : null,
        S.badge((g.memberCount >= 0 ? g.memberCount : "?") + " Mitglieder", "info", "users"))),
      h("div", { class: "row wrap" },
        S.btn("Gewicht", { size: "sm", icon: "activity", disabled: !S.can("action.luckperms.edit"), onClick: async function () { var v = await S.prompt("Gewicht", "Höher = wichtiger", String(g.weight), "Speichern", { type: "number", min: 0 }); if (v !== null) await lp({ operation: "group-weight", group: g.name, value: String(v) }); } }),
        S.btn("Präfix", { size: "sm", icon: "edit", disabled: !S.can("action.luckperms.edit"), onClick: async function () { var v = S.affixDialog ? await S.affixDialog("Präfix von " + (g.displayName || g.name), { value: g.prefix, other: g.suffix, label: "Präfix (leer = entfernen)" }) : await S.prompt("Präfix (leer = entfernen)", "z. B. &c[Admin] ", g.prefix, "Speichern"); if (v !== null) await lp({ operation: "group-prefix", group: g.name, value: v, priority: String(Math.max(1, g.weight || 100)) }); } }),
        S.btn("Suffix", { size: "sm", icon: "edit", disabled: !S.can("action.luckperms.edit"), onClick: async function () { var v = S.affixDialog ? await S.affixDialog("Suffix von " + (g.displayName || g.name), { value: g.suffix, other: g.prefix, kind: "suffix", label: "Suffix (leer = entfernen)", placeholder: "z. B.  &7✦" }) : await S.prompt("Suffix (leer = entfernen)", "z. B. &7 ✦", g.suffix, "Speichern"); if (v !== null) await lp({ operation: "group-suffix", group: g.name, value: v, priority: String(Math.max(1, g.weight || 100)) }); } }),
        S.btn("Anzeigename", { size: "sm", icon: "edit", disabled: !S.can("action.luckperms.edit"), onClick: async function () { var v = await S.prompt("Anzeigename", "Name in Menüs", g.displayName || g.name, "Speichern"); if (v) await lp({ operation: "group-displayname", group: g.name, value: v.trim() }); } }),
        g.name !== "default" ? S.btn("", { size: "sm icon", kind: "danger", icon: "trash", title: "Gruppe löschen", disabled: !S.can("action.luckperms.edit"), onClick: async function () { if (await lp({ operation: "group-delete", group: g.name }, "Gruppe „" + g.name + "“ löschen?", true)) setTimeout(function () { S.go("#/luckperms"); }, 900); } }) : null)));
    var parentSel = h("select", { class: "input" }, all.groups.filter(function (x) { return x.name !== g.name && g.parents.indexOf(x.name) < 0; }).map(function (x) { return h("option", { value: x.name, text: x.displayName || x.name }); }));
    var parents = S.card("Erbt von", { icon: "link" }, h("div", { class: "stack" }, h("div", { class: "row wrap" }, g.parents.length ? g.parents.map(function (p) {
      return h("span", { class: "chip" }, p, h("button", { type: "button", title: "Entfernen", disabled: !S.can("action.luckperms.edit"), onClick: function () { lp({ operation: "group-parent-remove", group: g.name, value: p }, "„" + p + "“ nicht mehr erben?"); } }, icon("x")));
    }) : h("span", { class: "muted small", text: "Keine Elterngruppen" })), h("div", { class: "row" }, parentSel, S.btn("Hinzufügen", { icon: "plus", disabled: !S.can("action.luckperms.edit") || !parentSel.options.length, onClick: function () { return lp({ operation: "group-parent-add", group: g.name, value: parentSel.value }); } }))));
    var nodes = nodeList("Rechte", perms, perms.length + " Einträge", function (n) {
      return nodeRow(n, function () { return lp({ operation: "group-permission-unset", group: g.name, node: n.key, context: n.context && n.context.indexOf(",") < 0 ? n.context.replace(/\s/g, "") : "" }, "Recht „" + n.key + "“ entfernen?"); });
    }, "Keine eigenen Rechte");
    var add = S.card("Recht hinzufügen", { icon: "plus" }, permForm(function (v) { return lp(Object.assign({ operation: v.duration ? "group-permission-temp" : "group-permission-set", group: g.name }, v)); }));
    var members = S.card("Mitglieder", { icon: "users", cls: "pad-list", sub: g.memberCount >= 0 ? g.memberCount + " direkt zugewiesen" : "Nicht abrufbar" }, h("div", { class: "list" }, (g.members || []).length ? g.members.map(function (m) {
      return h("div", { class: "item link", onClick: function () { S.go("#/luckperms/player/" + encodeURIComponent(m.uuid)); } }, S.avatar(m.name, "sm"), h("div", { class: "meta" }, h("b", { text: m.name })), m.online ? h("span", { class: "dot on" }) : null, icon("chevRight", "chev"));
    }) : h("div", { class: "item muted small", text: "Keine direkten Mitglieder" })));
    S.mount(h("div", { class: "stack" }, head, h("div", { class: "grid two" }, h("div", { class: "stack" }, add, nodes), h("div", { class: "stack" }, parents, members))));
  }
  async function lpPlayer(id, alive) {
    var u = await S.api("data/luckperms", { query: { player: id } }); if (!alive()) return;
    var all = await S.api("data/luckperms"); if (!alive()) return;
    var who = u.name || id;
    S.title(who, "LuckPerms", h("a", { class: "btn ghost sm", href: "#/luckperms" }, icon("chevLeft"), h("span", { class: "hide-mobile", text: "Ränge" })));
    var groupSel = h("select", { class: "input" }, all.groups.map(function (x) { return h("option", { value: x.name, text: (x.displayName || x.name) + " (" + x.weight + ")" }); }));
    var duration = S.input({ placeholder: "leer = dauerhaft, z. B. 30d" });
    var groupsCard = S.card("Gruppen", { icon: "crown", sub: "Primär: " + u.primary }, h("div", { class: "stack" }, h("div", { class: "row wrap" }, (u.groups || []).map(function (gr) {
      return h("span", { class: "chip" + (gr.group === u.primary ? " on" : "") }, gr.group + (gr.expiry ? " · bis " + S.date(gr.expiry) : "") + (gr.context ? " · " + gr.context : ""),
        h("button", { type: "button", title: "Entfernen", disabled: !S.can("action.luckperms.edit"), onClick: function () { lp({ operation: "user-parent-remove", player: u.uuid, group: gr.group }, who + " aus „" + gr.group + "“ entfernen?"); } }, icon("x")));
    })), h("div", { class: "grid two" }, S.field("Gruppe", groupSel), S.field("Dauer", duration)),
      h("div", { class: "row wrap" }, S.btn("Hinzufügen", { kind: "primary", icon: "plus", disabled: !S.can("action.luckperms.edit"), onClick: function () { return lp({ operation: duration.value.trim() ? "user-parent-temp" : "user-parent-add", player: u.uuid, group: groupSel.value, duration: duration.value.trim() }, who + " zu „" + groupSel.value + "“ hinzufügen?"); } }),
        S.btn("Als einzige Gruppe setzen", { icon: "check", disabled: !S.can("action.luckperms.edit"), onClick: function () { return lp({ operation: "user-parent-set", player: u.uuid, group: groupSel.value }, "Alle Gruppen von " + who + " durch „" + groupSel.value + "“ ersetzen?", true); } })),
      (all.tracks || []).length ? h("div", { class: "row wrap" }, all.tracks.map(function (t) {
        return h("span", { class: "row", style: { gap: "6px" } }, h("span", { class: "muted small", text: t.name + ":" }),
          S.btn("Befördern", { size: "sm", icon: "chevRight", disabled: !S.can("action.luckperms.edit"), onClick: function () { return lp({ operation: "user-promote", player: u.uuid, track: t.name }, who + " auf „" + t.name + "“ befördern?"); } }),
          S.btn("Zurückstufen", { size: "sm", icon: "chevLeft", disabled: !S.can("action.luckperms.edit"), onClick: function () { return lp({ operation: "user-demote", player: u.uuid, track: t.name }, who + " auf „" + t.name + "“ zurückstufen?"); } }));
      })) : null));
    var nodes = nodeList("Eigene Rechte", u.nodes || [], (u.nodes || []).length + " Einträge", function (n) {
      return nodeRow(n, /^(prefix|suffix|meta|weight|displayname)\./.test(n.key) ? null : function () { return lp({ operation: "user-permission-unset", player: u.uuid, node: n.key, context: n.context && n.context.indexOf(",") < 0 ? n.context.replace(/\s/g, "") : "" }, "Recht „" + n.key + "“ entfernen?"); });
    }, "Keine eigenen Rechte – alles kommt aus den Gruppen.");
    var add = S.card("Recht hinzufügen", { icon: "plus" }, permForm(function (v) { return lp(Object.assign({ operation: v.duration ? "user-permission-temp" : "user-permission-set", player: u.uuid }, v)); }));
    var head = S.card(null, { cls: "hero" }, h("div", { class: "row wrap", style: { gap: "16px" } }, S.avatar(who, "lg"), h("div", { class: "grow" }, h("h2", { style: { margin: 0 }, text: who }),
      h("div", { class: "row wrap", style: { gap: "6px", marginTop: "6px" } }, u.online ? S.badge("Online", "ok") : S.badge("Offline"), u.prefix || u.suffix ? h("span", { class: "mc-preview sm" }, affixView(u.prefix), h("span", { class: "mc-name", text: who }), affixView(u.suffix)) : null, S.badge(u.uuid, "", "key"))),
      h("div", { class: "row wrap" }, S.has("luckperms-v2") ? S.btn("Eigenes Präfix", { size: "sm", icon: "edit", disabled: !S.can("action.luckperms.edit"), title: "Überschreibt das Gruppenpräfix nur für diesen Spieler", onClick: async function () { var v = await S.affixDialog("Präfix nur für " + who, { value: (u.nodes || []).some(function (n) { return /^prefix\./.test(n.key); }) ? u.prefix : "", other: u.suffix, name: who, label: "Eigenes Präfix (leer = Gruppenpräfix verwenden)" }); if (v !== null) await lp({ operation: "user-prefix", player: u.uuid, value: v, priority: "1000" }, "Präfix von " + who + " ändern?"); } }) : null,
        S.has("luckperms-v2") ? S.btn("Eigenes Suffix", { size: "sm", icon: "edit", disabled: !S.can("action.luckperms.edit"), onClick: async function () { var v = await S.affixDialog("Suffix nur für " + who, { value: (u.nodes || []).some(function (n) { return /^suffix\./.test(n.key); }) ? u.suffix : "", other: u.prefix, name: who, kind: "suffix", label: "Eigenes Suffix (leer = Gruppensuffix verwenden)" }); if (v !== null) await lp({ operation: "user-suffix", player: u.uuid, value: v, priority: "1000" }, "Suffix von " + who + " ändern?"); } }) : null,
        S.has("access-locks-v1") && S.can("data.access-locks") ? h("a", { class: "btn sm", href: "#/access-locks/" + u.uuid }, icon("lock"), "Rechte-Sperren") : null,
        S.can("data.profile") ? h("a", { class: "btn sm", href: "#/player/" + u.uuid }, icon("user"), "Profil") : null)));
    S.mount(h("div", { class: "stack" }, head, h("div", { class: "grid two" }, groupsCard, h("div", { class: "stack" }, add, nodes))));
  }

  // ============================================================ Page: Lands
  P.lands = async function (param, query, alive) {
    var q = query.get("q") || "";
    var d = await S.api("data/lands", { query: { q: q } }); if (!alive()) return;
    S.title("Lands", d.installed ? "Lands " + (d.version || "") : "");
    if (!d.installed) { S.mount(S.card(null, {}, S.empty("map", "Lands ist nicht installiert", "Mit dem Lands-Plugin siehst du hier alle Länder und verwaltest, welche Admins überall Zugriff haben."))); return; }
    var canEdit = S.can("action.lands.access") && S.full() && d.luckperms;
    function toggle(m, node, on) {
      var what = node === "lands.bypass.*" ? "Bypass" : "Admin";
      return S.btn(what + (on ? " entziehen" : " vergeben"), { size: "sm", icon: on ? "x" : "plus", kind: on ? "danger" : "", disabled: !canEdit, title: canEdit ? "" : (d.luckperms ? "Benötigt action.lands.access" : "Benötigt LuckPerms"),
        onClick: async function () { var r = await S.runAction("lands.access", { player: m.uuid, node: node, operation: on ? "revoke" : "grant" }, { title: (on ? "Zugriff entziehen: " : "Zugriff vergeben: ") + m.name, danger: on }); if (r) setTimeout(S.reload, 900); } });
    }
    var admins = S.card("Admin-Zugriff auf alle Länder", { icon: "shieldCheck", cls: "pad-list", sub: "lands.bypass.* = überall bauen/öffnen · lands.admin.* = Lands-Adminbefehle" }, h("div", { class: "list" }, (d.admins || []).length ? d.admins.map(function (m) {
      return h("div", { class: "item stackable" }, S.avatar(m.name, "sm"), h("div", { class: "meta" }, h("b", { text: m.name }), h("div", { class: "row wrap", style: { gap: "6px", marginTop: "4px" } },
        m.bypass ? S.badge("Bypass", "warn", "unlock") : S.badge("Kein Bypass"), m.admin ? S.badge("Lands-Admin", "accent", "crown") : null, m.online ? S.badge("Online", "ok") : null)),
        h("div", { class: "side wrap" }, toggle(m, "lands.bypass.*", m.bypass), toggle(m, "lands.admin.*", m.admin)));
    }) : h("div", { class: "item muted small", text: "Keine Teammitglieder erkannt." })));
    var list = S.card("Länder", { icon: "map", cls: "pad-0", sub: d.total + " gefunden" }, h("div", { class: "table-wrap" }, h("table", { class: "table responsive" },
      h("thead", {}, h("tr", {}, ["Land", "Besitzer", "Mitglieder", "Chunks", "Kasse", "Level"].map(function (t) { return h("th", { text: t }); }))),
      h("tbody", {}, (d.lands || []).map(function (l) {
        return h("tr", {}, h("td", { class: "primary", text: l.display || l.name }), h("td", { "data-label": "Besitzer" }, l.owner && S.can("data.profile") ? h("a", { href: "#/player/" + l.owner, text: l.ownerName }) : l.ownerName),
          h("td", { "data-label": "Mitglieder", text: S.num(l.members) }), h("td", { "data-label": "Chunks", text: S.num(l.chunks) }), h("td", { "data-label": "Kasse", text: S.num(Math.round(l.balance)) }), h("td", { "data-label": "Level", text: l.level || "–" }));
      })))));
    S.mount(h("div", { class: "stack" }, d.error ? S.notice(d.error, "warn", "alert") : null,
      h("div", { class: "grid kpis" }, kpi("Länder", d.total, "map"), kpi("Admins mit Bypass", (d.admins || []).filter(function (a) { return a.bypass; }).length, "unlock"), kpi("Lands-Admins", (d.admins || []).filter(function (a) { return a.admin; }).length, "crown")),
      admins, h("div", { class: "row" }, S.searchBox("Land oder Besitzer suchen …", function (v) { S.go("#/lands?q=" + encodeURIComponent(v)); }, q)), list,
      !d.luckperms ? S.notice("Zugriff vergeben/entziehen benötigt LuckPerms.", "warn", "lock") : null));
  };
  function kpi(label, value, ic) { return h("section", { class: "card kpi" }, h("div", { class: "ico" }, icon(ic)), h("div", { class: "label", text: label }), h("div", { class: "value", text: S.num(value) })); }
})();

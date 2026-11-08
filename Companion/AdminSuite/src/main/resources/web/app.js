/* Simplex Panel Web – shell, navigation, router and boot. */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, icon = S.icon;
  S.pages = S.pages || {};

  // perm: required permission (null = always), feature: required suite feature, full: needs full scope, owner: main admin / manager only
  var NAV = [
    { group: "Übersicht", items: [
      { id: "dashboard", label: "Dashboard", icon: "home", perm: null },
      { id: "ai-workspace", label: "Simplex KI", icon: "sparkles", perm: null, feature: "ai-workspace-v1" },
      { id: "tools", label: "Alle Werkzeuge", icon: "grid", perm: null },
      { id: "histories", label: "Verläufe verwalten", icon: "list", perm: null, feature: "history-management-v1" },
      { id: "events", label: "Ereignisse", icon: "bell", perm: "data.events" },
      { id: "admin-log", label: "Admin-Log", icon: "list", perm: "data.admin-log", feature: "admin-log-v1", badge: "adminLog" }
    ] },
    { group: "Community", items: [
      { id: "players", label: "Spieler", icon: "users", perm: "data.players" },
      { id: "tickets", label: "Tickets", icon: "ticket", perm: "data.tickets", badge: "tickets" },
      { id: "staff-chat", label: "Staff-Chat", icon: "team", perm: "data.staff-chat", feature: "staff-chat-v1" },
      { id: "chat", label: "Serverchat", icon: "chat", perm: "data.chat" },
      { id: "moderation", label: "Moderation", icon: "gavel", perm: "data.reports" }
    ] },
    { group: "Server", items: [
      { id: "diagnostics", label: "Diagnose", icon: "activity", perm: "data.diagnostics", feature: "diagnostics-v1", badge: "diagnostics" },
      { id: "statistics", label: "Statistiken", icon: "chart", perm: "data.statistics" },
      { id: "worlds", label: "Welten", icon: "globe", perm: "data.worlds" },
      { id: "world-trim", label: "Welten-Trimmer", icon: "scissors", perm: "data.world-trim", feature: "world-trim-v1" },
      { id: "plugins", label: "Plugins", icon: "puzzle", perm: "data.plugins" },
      { id: "functions", label: "Alle Funktionen", icon: "grid", perm: null },
      { id: "menu", label: "Suite-Menüs", icon: "box", perm: "action.menu.action", full: true },
      { id: "settings", label: "Einstellungen", icon: "settings", perm: "data.app-settings", feature: "app-settings-v1" }
    ] },
    // 10.3.0: rights got their own group; the long "Server" list is easier to scan.
    { group: "Ränge & Rechte", items: [
      { id: "luckperms", label: "Ränge & LuckPerms", icon: "crown", perm: "data.luckperms", feature: "luckperms-v1" },
      { id: "access-locks", label: "Rechte & Sperren", icon: "lock", perm: "data.access-locks", feature: "access-locks-v1" },
      { id: "commands", label: "Befehle", icon: "terminal", perm: "data.commands", feature: "command-rights-v1" },
      { id: "lands", label: "Lands", icon: "map", perm: "data.lands", feature: "lands-v1" }
    ] },
    { group: "Studio", items: [
      { id: "discord-status", label: "Discord-Status", icon: "palette", perm: "data.discord-status", feature: "discord-status-designer-v1" },
      { id: "scoreboard", label: "Scoreboard", icon: "layers", perm: "data.scoreboard", feature: "scoreboard-designer-v1" },
      { id: "ad-hub", label: "Werbe-Zentrale", icon: "send", perm: "data.discord-ads", feature: "ad-hub-v1", badge: "adHub" }
    ] },
    { group: "Persönlich", items: [
      { id: "notifications", label: "Benachrichtigungen", icon: "bell", perm: null, feature: "web-push-v1" },
      { id: "appearance", label: "Darstellung", icon: "sparkles", perm: null, feature: "web-prefs-v1" },
      { id: "changelog", label: "Neuigkeiten", icon: "sparkles", perm: null, feature: "changelog-v1" }
    ] },
    { group: "Zugänge", items: [
      { id: "users", label: "Benutzer & Rechte", icon: "key", perm: "data.access", full: true },
      { id: "sessions", label: "Geräte & Sitzungen", icon: "devices", perm: null },
      { id: "security", label: "Konto & Sicherheit", icon: "shieldCheck", perm: null },
      { id: "web", label: "Webpanel & Domain", icon: "link", perm: null, owner: true }
    ] }
  ];
  var TABS = ["dashboard", "players", "tickets", "staff-chat", "more"];
  /** 9.9.0: the phone tab bar and hidden menu entries follow the account's appearance settings. */
  function tabs() { var p = S.prefs && S.prefs.tabs; var list = (p && p.length ? p : TABS.slice(0, 4)).filter(function (id) { return navItem(id); }).slice(0, 4); return list.concat(["more"]); }
  function hiddenItem(id) { return !!(S.prefs && (S.prefs.hidden || []).indexOf(id) >= 0) && ["dashboard", "appearance", "security"].indexOf(id) < 0; }
  var ALIASES = { player: "players", ticket: "tickets", function: "functions" };
  S.nav = NAV;

  function navItem(id) { for (var g = 0; g < NAV.length; g++) for (var i = 0; i < NAV[g].items.length; i++) if (NAV[g].items[i].id === id) return NAV[g].items[i]; return null; }
  /** Returns null when allowed, otherwise the reason shown on greyed-out entries. */
  function denied(item) {
    if (!item) return null;
    if (item.feature && !S.has(item.feature)) return "Benötigt eine neuere Admin Suite";
    if (item.owner && !(S.account && S.account.manager)) return "Nur für den Hauptadmin";
    if (item.full && !S.full()) return "Benötigt Vollzugriff";
    if (item.perm && !S.can(item.perm)) return "Keine Berechtigung: " + item.perm;
    return null;
  }
  S.denied = function (id) { return denied(navItem(id)); };

  // ------------------------------------------------------------ Theme
  function theme(value) {
    var t = value || (function () { try { return localStorage.getItem("simplex-theme"); } catch (e) { return null; } })() || "auto";
    var dark = t === "dark" || t === "auto" && !(window.matchMedia && matchMedia("(prefers-color-scheme: light)").matches);
    document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
    var meta = document.querySelector('meta[name="theme-color"]'); if (meta) meta.setAttribute("content", dark ? "#060a17" : "#f3f6fc");
    if (value) try { localStorage.setItem("simplex-theme", value); } catch (e) { /* private mode */ }
    return dark;
  }
  theme();
  S.theme = theme;
  if (window.matchMedia) matchMedia("(prefers-color-scheme: light)").addEventListener("change", function () { theme(); });

  // ------------------------------------------------------------ Shell
  var counts = {};
  function buildShell() {
    var root = document.getElementById("app"); S.clear(root);
    var sidebar = h("aside", { class: "sidebar", id: "sidebar", "aria-label": "Navigation" });
    var scrim = h("div", { class: "scrim", onClick: closeDrawer });
    var brand = h("div", { class: "brand" }, h("img", { src: "/assets/logo.png", alt: "", width: 40, height: 40 }),
      h("div", {}, h("b", { text: "Simplex Panel" }), h("span", { text: (S.health && S.health.name) || "Admin Suite" })));
    sidebar.appendChild(brand);
    NAV.forEach(function (group) {
      var nav = h("nav", { class: "nav nav-group" }, h("div", { class: "nav-title", text: group.group }));
      if (group.items.every(function (item) { return hiddenItem(item.id) || item.owner && denied(item); })) return;
      group.items.forEach(function (item) {
        var reason = denied(item);
        if (item.owner && reason) return; // owner-only tools stay hidden for everybody else
        if (hiddenItem(item.id)) return;
        var a = h("a", { href: "#/" + item.id, "data-id": item.id, class: reason ? "locked" : "", title: reason || item.label }, icon(item.icon), h("span", { text: item.label }));
        if (reason) a.appendChild(icon("lock", "lock"));
        else if (item.badge) a.appendChild(h("span", { class: "count hidden", "data-badge": item.badge }));
        nav.appendChild(a);
      });
      sidebar.appendChild(nav);
    });
    var me = S.account || {};
    var themeBtn = S.btn("", { kind: "ghost", size: "sm icon", icon: document.documentElement.getAttribute("data-theme") === "dark" ? "sun" : "moon", title: "Hell/Dunkel umschalten", onClick: function () {
      var dark = theme(document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark"); S.clear(themeBtn).appendChild(icon(dark ? "sun" : "moon"));
      if (S.prefs) S.prefs.theme = dark ? "dark" : "light";
      if (S.has("web-prefs-v1")) S.api("web/prefs", { body: { theme: dark ? "dark" : "light" }, quiet: true }).catch(function () {});
    } });
    var collapse = S.btn("", { kind: "ghost", size: "sm icon", icon: "chevLeft", title: "Seitenleiste ein-/ausklappen", onClick: function () {
      var next = document.documentElement.getAttribute("data-sidebar") === "collapsed" ? "expanded" : "collapsed";
      document.documentElement.setAttribute("data-sidebar", next); if (S.prefs) S.prefs.sidebar = next;
      if (S.has("web-prefs-v1")) S.api("web/prefs", { body: { sidebar: next }, quiet: true }).catch(function () {});
    } });
    collapse.classList.add("collapse-btn", "hide-mobile");
    sidebar.appendChild(h("div", { class: "sidebar-foot" }, collapse,
      h("div", { class: "me" }, S.avatar(me.username || me.label, "sm"), h("div", { class: "who" }, h("b", { text: me.username || me.label || "" }), h("small", { text: me.owner ? "Hauptadmin" : S.health && S.health.permissions && S.health.permissions.indexOf("*") >= 0 ? "Vollzugriff" : "Teammitglied" })), themeBtn,
        S.btn("", { kind: "ghost", size: "sm icon", icon: "logout", title: "Abmelden", onClick: logout }))));

    var title = h("h1", { id: "page-title", text: "" }), crumb = h("span", { class: "crumb hide-mobile", id: "page-crumb" });
    var finder = h("button", { type: "button", class: "finder hide-mobile", title: "Schnellsuche (Strg/⌘ + K)", onClick: function () { if (S.palette) S.palette(); } }, icon("search"), h("span", { text: "Suchen" }), h("kbd", { text: /Mac|iPhone|iPad/.test(navigator.platform) ? "⌘K" : "Strg K" }));
    var topbar = h("header", { class: "topbar" }, S.btn("", { kind: "ghost", size: "icon", icon: "menu", title: "Menü", onClick: openDrawer, aria: "Menü öffnen" }), h("div", { class: "grow row", style: { gap: "12px", minWidth: 0 } }, title, crumb), finder, h("div", { id: "page-actions", class: "row" }));
    topbar.firstChild.classList.add("menu-btn");
    var content = h("div", { class: "content", id: "content" });
    var tabbar = h("nav", { class: "tabbar", "aria-label": "Schnellnavigation" });
    tabs().forEach(function (id) {
      if (id === "more") { tabbar.appendChild(h("a", { href: "#", onClick: function (e) { e.preventDefault(); openDrawer(); } }, icon("menu"), "Mehr")); return; }
      var item = navItem(id), reason = denied(item);
      var a = h("a", { href: "#/" + id, "data-id": id, class: reason ? "locked" : "", title: reason || item.label }, icon(item.icon), h("span", { text: item.label.replace("Staff-Chat", "Team").replace("Benachrichtigungen", "Hinweise").replace("Ränge & Rechte", "Ränge") }));
      if (item.badge && !reason) a.appendChild(h("span", { class: "count hidden", "data-badge": item.badge }));
      tabbar.appendChild(a);
    });
    var banner = h("div", { id: "install-banner", class: "install-slot hidden" });
    root.appendChild(h("div", { class: "shell" }, sidebar, scrim, h("div", { class: "main" }, topbar, banner, h("main", {}, content)), tabbar));
    S.content = content;
    if (S.renderInstallBanner) S.renderInstallBanner();
  }
  function openDrawer() { var s = document.getElementById("sidebar"); if (s) { s.classList.add("open"); document.querySelector(".scrim").classList.add("open"); } }
  function closeDrawer() { var s = document.getElementById("sidebar"); if (s) { s.classList.remove("open"); document.querySelector(".scrim").classList.remove("open"); } }
  S.title = function (text, crumb, actions) {
    document.title = text + " · Simplex Panel";
    var t = document.getElementById("page-title"); if (t) t.textContent = text;
    var c = document.getElementById("page-crumb"); if (c) c.textContent = crumb || "";
    var a = document.getElementById("page-actions"); if (a) { S.clear(a); if (actions) S.add(a, actions); }
  };
  function setBadge(name, value) {
    counts[name] = value;
    if (S.appBadge) S.appBadge((counts.adminLog || 0) + (counts.tickets || 0));
    document.querySelectorAll('[data-badge="' + name + '"]').forEach(function (b) { b.textContent = value > 99 ? "99+" : String(value); b.classList.toggle("hidden", !value); });
  }
  S.setBadge = setBadge;
  async function refreshBadges() {
    if (!S.health) return;
    try { if (S.can("data.admin-log") && S.has("admin-log-v1")) { var log = await S.api("data/admin-log", { quiet: true }); setBadge("adminLog", log.unread || 0); } } catch (e) { /* optional */ }
    try { if (S.can("data.diagnostics") && S.has("diagnostics-v1")) { var dg = await S.api("diagnostics/overview", { quiet: true, query: { range: 60 } }); setBadge("diagnostics", dg.alert ? 1 : 0); } } catch (e) { /* optional */ }
    try { if (S.can("data.discord-ads") && S.has("ad-hub-v1")) { var ah = await S.api("ad-hub", { quiet: true }); setBadge("adHub", ah.stats ? ah.stats.due || 0 : 0); } } catch (e) { /* optional */ }
    try { if (S.can("data.tickets")) { var t = await S.api("data/tickets", { quiet: true, query: { status: "open" } }); setBadge("tickets", t.counts ? (t.counts.open || 0) : 0); } } catch (e) { /* optional */ }
  }

  // ------------------------------------------------------------ Router
  var current = null, timers = [];
  /** Page refresh timer: paused while hidden, never overlapping, twice as slow in the performance mode. */
  S.every = function (ms, fn) {
    var busy = false, t = setInterval(async function () {
      if (document.hidden || busy) return; busy = true;
      try { await fn(); } catch (e) { /* the page keeps its last view */ } finally { busy = false; }
    }, S.perf && S.perf.lite ? ms * 2 : ms);
    timers.push(t); return t;
  };
  function parse() {
    var raw = location.hash.replace(/^#\/?/, ""), q = raw.indexOf("?"), path = q >= 0 ? raw.slice(0, q) : raw, query = new URLSearchParams(q >= 0 ? raw.slice(q + 1) : "");
    var parts = path.split("/").filter(Boolean).map(decodeURIComponent);
    return { id: parts[0] || "dashboard", param: parts.slice(1).join("/"), query: query };
  }
  async function route() {
    timers.forEach(clearInterval); timers = [];
    if (S.onLeave) { try { S.onLeave(); } catch (e) { /* ignore */ } S.onLeave = null; }
    closeDrawer();
    document.querySelectorAll(".modal-bg").forEach(function (m) { m.remove(); });
    var r = parse(), nav = ALIASES[r.id] || r.id;
    document.querySelectorAll(".nav a, .tabbar a").forEach(function (a) { a.classList.toggle("active", a.getAttribute("data-id") === nav); });
    var page = S.pages[r.id];
    var token = current = {};
    S.title(navItem(nav) ? navItem(nav).label : "Simplex Panel");
    if (!page) { S.mount(S.card(null, {}, S.empty("info", "Seite nicht gefunden", "Diese Adresse gibt es im Webpanel nicht.", h("a", { class: "btn", href: "#/dashboard" }, "Zur Übersicht")))); return; }
    var reason = denied(navItem(nav));
    if (reason && nav !== "dashboard") { S.mount(S.lockedPage("Kein Zugriff", null, reason + ".")); return; }
    S.mount(S.skeleton(4));
    try { await page(r.param, r.query, function () { return token === current; }); }
    catch (e) { if (token === current && e.status !== 401 && e.status !== 423) S.mount(S.errorView(e, route)); }
    window.scrollTo(0, 0);
  }
  S.go = function (hash) { if (location.hash === hash) route(); else location.hash = hash; };
  S.rebuildShell = function () { if (!S.health) return; buildShell(); route(); refreshBadges(); };
  S.reload = route;
  window.addEventListener("hashchange", function () { if (S.health) route(); });

  // ------------------------------------------------------------ Session lifecycle
  async function logout() {
    try { await S.api("logout", { body: {}, quiet: true }); } catch (e) { /* already signed out */ }
    S.health = null; S.account = null; history.replaceState(null, "", "/#/dashboard"); S.auth.login("web");
  }
  S.logout = logout;
  S.onUnauthorized = function () { if (!S.health) return; S.health = null; S.account = null; S.toast("Sitzung abgelaufen", "err", "Bitte erneut anmelden."); S.auth.login("web"); };
  S.onLocked = function (message) {
    S.health = null; var root = document.getElementById("app"); S.clear(root);
    root.appendChild(h("main", { class: "auth" }, h("div", { class: "auth-card" }, h("img", { class: "auth-logo", src: "/assets/logo.png", alt: "" }), h("h1", { text: "Gerät gesperrt" }),
      h("p", { class: "lead", text: message }), S.btn("Erneut prüfen", { kind: "primary", block: true, icon: "refresh", onClick: function () { return S.boot(true); } }))));
  };

  S.boot = async function (fromLogin) {
    if (!S.webConfig) { try { S.webConfig = await S.api("web/config", { quiet: true }); } catch (e) { S.webConfig = {}; } }
    var r = parse();
    if (!fromLogin && r.id === "app-login") { S.auth.login("app", r.query.get("state") || ""); return; }
    if (!fromLogin && r.id === "handoff") { S.auth.handoff(r.query); return; }
    try { S.health = await S.api("health", { quiet: true }); }
    catch (e) { if (e.status === 423) return; S.health = null; S.auth.login("web"); return; }
    try { S.account = await S.api("account", { quiet: true }); } catch (e) { S.account = {}; }
    if (S.loadPrefs) await S.loadPrefs();
    buildShell();
    if (r.id === "app-login" || r.id === "handoff") history.replaceState(null, "", "/#/dashboard");
    // 9.9.0: personal start page when the panel opens without a specific address (also from the home screen).
    var raw = location.hash.replace(/^#\/?/, "");
    if ((!raw || raw === "dashboard") && S.prefs && S.prefs.start && S.prefs.start !== "dashboard" && navItem(S.prefs.start) && !denied(navItem(S.prefs.start)) && !S.started) history.replaceState(null, "", "/#/" + S.prefs.start);
    S.started = true;
    if (S.registerWorker) S.registerWorker().then(function () { if (S.resyncPush) S.resyncPush(); });
    await route();
    // 10.0.0: what's new – shown once per account and Suite version (stored on the server, survives new logins).
    if (S.showChangelog) setTimeout(function () { S.showChangelog(false); }, 600);
    refreshBadges(); clearInterval(S.badgeTimer); S.badgeTimer = setInterval(function () { if (!document.hidden && S.health) refreshBadges(); }, S.perf && S.perf.lite ? 120000 : 60000);
  };
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") closeDrawer(); });
  // Back from the background or a lost connection: refresh the counters once instead of waiting a full minute.
  document.addEventListener("visibilitychange", function () { if (!document.hidden && S.health) refreshBadges(); });
  window.addEventListener("online", function () { S.net(true); if (S.health) { refreshBadges(); route(); } });
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", function () { S.boot(); }); else S.boot();
})();

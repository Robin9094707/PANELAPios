/* Simplex Panel Web – content pages. Every page checks its permission and shows a greyed-out state instead of failing. */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, icon = S.icon, P = S.pages = S.pages || {};
  var samples = { online: [], tps: [], ram: [] };

  function platformBadge(row) {
    var c = String(row.client || "").toUpperCase();
    if (c === "BEDROCK") return S.badge(row.device || "Bedrock", "info", "phone");
    if (c === "JAVA") return S.badge("Java", "", "laptop");
    return null;
  }
  function playerBadges(row) {
    return [row.online ? S.badge("Online", "ok") : null, platformBadge(row), row.ban ? S.badge("Gebannt", "danger", "gavel") : null,
      row.mute ? S.badge("Stumm", "warn") : null, row.warnings ? S.badge(row.warnings + " Warn.", "warn", "alert") : null];
  }
  function tile(label, value, opts) {
    opts = opts || {};
    var c = h("section", { class: "card kpi" + (opts.href ? " clickable" : ""), onClick: opts.href ? function () { S.go(opts.href); } : null },
      h("div", { class: "ico " + (opts.tone || "") }, icon(opts.icon || "activity")),
      h("div", { class: "label", text: label }), h("div", { class: "value" }, value, opts.unit ? h("small", { text: opts.unit }) : null),
      opts.hint ? h("div", { class: "hint", text: opts.hint }) : null, opts.extra || null);
    return c;
  }
  function lockedTile(label, perm) { var c = tile(label, "–", { icon: "lock", extra: S.lockNote(perm === "Vollzugriff" ? "Benötigt Vollzugriff" : "Keine Berechtigung") }); c.classList.add("locked"); c.title = "Benötigt: " + perm; return c; }
  function listCard(title, rows, opts) {
    opts = opts || {};
    var list = h("div", { class: "list" }, rows.length ? rows : h("div", { class: "item muted small", text: opts.empty || "Keine Einträge" }));
    return S.card(title, { icon: opts.icon, sub: opts.sub, actions: opts.href ? h("a", { class: "btn ghost sm", href: opts.href }, "Alle", icon("chevRight")) : null, cls: "pad-list" }, list);
  }
  function deviceIcon(row) { return row.kind === "key" ? "key" : row.kind === "web" ? (row.model === "iPhone" || row.model === "Android-Gerät" ? "phone" : "laptop") : "phone"; }
  function deviceName(row) { return row.label || row.deviceName || (row.kind === "web" ? (row.browser || "Browser") + (row.os ? " · " + row.os : "") : row.model || (row.kind === "key" ? row.user : "App")); }

  // ============================================================ Dashboard
  P.dashboard = async function (param, query, alive) {
    S.title("Dashboard", (S.health && S.health.name) || "");
    var canDash = S.can("data.admin-dashboard"), canOverview = S.can("data.overview");
    async function load() {
      var tasks = [canDash ? S.api("data/admin-dashboard") : canOverview ? S.api("data/overview") : Promise.resolve(null), S.api("sessions").catch(function () { return null; })];
      return Promise.all(tasks);
    }
    var data = await load(); if (!alive()) return;
    function render(d, sessions) {
      if (d) {
        samples.online.push(d.online || 0); samples.tps.push(Number(d.tps) || 0); samples.ram.push(d.ramMax ? d.ramUsed / d.ramMax * 100 : 0);
        Object.keys(samples).forEach(function (k) { if (samples[k].length > 40) samples[k].shift(); });
      }
      var hero = S.card(null, { cls: "hero" }, h("div", { class: "row wrap", style: { gap: "16px" } },
        h("img", { src: "/assets/logo.png", alt: "", width: 56, height: 56, style: { borderRadius: "16px" } }),
        h("div", { class: "grow" }, h("h2", { style: { margin: 0, fontSize: "22px", letterSpacing: "-0.02em" }, text: "Hallo " + ((S.account && S.account.username) || "") + " 👋" }),
          h("div", { class: "muted small", text: (S.health.name || "Server") + " · Admin Suite " + (S.health.version || "") + (d && d.uptime ? " · läuft seit " + S.dur(d.uptime) : "") })),
        d ? S.badge(Number(d.tps).toFixed(1) + " TPS", d.tps >= 18 ? "ok" : d.tps >= 14 ? "warn" : "danger", "activity") : null,
        d && d.maintenance && d.maintenance.active ? S.badge("Wartung aktiv", "warn", "alert") : null,
        d && d.restart && d.restart.active ? S.badge("Neustart in " + d.restart.remaining + " s", "danger", "refresh") : null));
      var kpis = h("div", { class: "grid kpis" });
      if (d) {
        kpis.appendChild(tile("Spieler online", S.num(d.online), { unit: d.maxPlayers ? "/ " + d.maxPlayers : "", icon: "users", href: S.can("data.players") ? "#/players?filter=online" : null, extra: S.sparkline(samples.online) }));
        if (canDash) {
          kpis.appendChild(d.staff ? tile("Team online", S.num(d.staff.length), { icon: "shieldCheck", tone: "v", hint: d.staff.length ? d.staff.map(function (s) { return s.name; }).slice(0, 3).join(", ") : "Niemand im Spiel" }) : lockedTile("Team online", "Vollzugriff"));
        }
        kpis.appendChild(tile("TPS", Number(d.tps).toFixed(1), { icon: "activity", tone: d.tps >= 18 ? "g" : d.tps >= 14 ? "y" : "r", hint: d.mspt >= 0 ? Number(d.mspt).toFixed(1) + " ms pro Tick" : "", extra: S.sparkline(samples.tps) }));
        kpis.appendChild(tile("Arbeitsspeicher", S.bytes(d.ramUsed), { unit: "/ " + S.bytes(d.ramMax), icon: "memory", tone: "v", extra: S.bar(d.ramUsed, d.ramMax) }));
      } else kpis.appendChild(lockedTile("Serverstatus", "data.admin-dashboard"));
      if (sessions) kpis.appendChild(tile(sessions.manage ? "Admins im Panel" : "Deine aktiven Geräte", S.num(sessions.active), { icon: "devices", tone: "g", href: "#/sessions", hint: sessions.manage ? (sessions.activeUsers || []).map(function (u) { return u.user; }).slice(0, 3).join(", ") || "Gerade niemand aktiv" : "in den letzten 5 Minuten" }));
      if (canDash && d) {
        if (d.tickets) kpis.appendChild(tile("Offene Tickets", S.num(d.tickets.open || 0), { icon: "ticket", tone: d.tickets.urgent ? "r" : "y", hint: (d.tickets.urgent ? d.tickets.urgent + " dringend · " : "") + (d.tickets.answered || 0) + " beantwortet", href: S.can("data.tickets") ? "#/tickets" : null }));
        kpis.appendChild(tile("Offene Reports", S.num(d.moderation ? d.moderation.openReports : 0), { icon: "flag", tone: "r", hint: d.moderation ? d.moderation.reviewReports + " in Prüfung · " + d.moderation.internalBans + " Banns" : "", href: S.can("data.reports") ? "#/moderation" : null }));
        kpis.appendChild(tile("Neue Spieler", S.num(d.new24h), { icon: "sparkles", hint: "24 h · " + S.num(d.newWeek) + " diese Woche · " + S.num(d.active24h) + " aktiv", href: S.can("data.players") ? "#/players?filter=new" : null }));
      }
      var grid = h("div", { class: "grid three" });
      // Team online grouped by role
      if (canDash && d && d.staff) {
        var roles = {}; d.staff.forEach(function (s) { (roles[s.role || "Team"] = roles[s.role || "Team"] || []).push(s); });
        var rows = Object.keys(roles).sort().map(function (role) {
          return h("div", { class: "item" }, h("div", { class: "meta" }, h("b", { text: role }), h("small", { text: roles[role].map(function (s) { return s.name + (s.operator ? " (OP)" : ""); }).join(", ") })), S.badge(String(roles[role].length), "accent"));
        });
        grid.appendChild(listCard("Team online nach Rolle", rows, { icon: "shieldCheck", empty: "Gerade ist kein Teammitglied im Spiel." }));
      } else grid.appendChild(S.lockedCard("Team online nach Rolle", canDash ? "Vollzugriff" : "data.admin-dashboard"));
      // Signed-in admins (app and browser)
      if (sessions) {
        var now = Date.now(), active = (sessions.items || []).concat(sessions.keys || []).filter(function (r) { return r.seen && now - r.seen < 15 * 60e3; })
          .sort(function (a, b) { return b.seen - a.seen; }).slice(0, 8);
        grid.appendChild(listCard(sessions.manage ? "Gerade im Panel" : "Deine Geräte", active.map(function (r) {
          return h("div", { class: "item link", onClick: function () { S.go("#/sessions"); } }, h("div", { class: "avatar device sm" }, icon(deviceIcon(r))),
            h("div", { class: "meta" }, h("b", { text: (sessions.manage ? r.user + " · " : "") + deviceName(r) }), h("small", { text: (r.kind === "web" ? "Browser" : r.kind === "key" ? "API-Schlüssel" : "App") + (r.ip ? " · " + r.ip : "") + " · " + S.ago(r.seen) })),
            h("span", { class: "dot " + (now - r.seen < 5 * 60e3 ? "on" : "warn") }));
        }), { icon: "devices", href: "#/sessions", empty: "In den letzten 15 Minuten war niemand aktiv." }));
      }
      // Online players
      if (d && d.players) {
        grid.appendChild(listCard("Online-Spieler", d.players.slice(0, 10).map(function (p) {
          return h("div", { class: "item" + (S.can("data.profile") ? " link" : ""), onClick: S.can("data.profile") ? function () { S.go("#/player/" + p.id); } : null }, S.avatar(p.name, "sm"),
            h("div", { class: "meta" }, h("b", { text: p.name }), h("small", { text: (p.client === "BEDROCK" ? p.device || "Bedrock" : "Java") + (p.version ? " · " + p.version : "") })), p.warnings ? S.badge(String(p.warnings), "warn", "alert") : null);
        }), { icon: "users", href: S.can("data.players") ? "#/players?filter=online" : null, sub: d.online + " online", empty: "Gerade ist niemand online." }));
      }
      if (canDash && d) {
        grid.appendChild(listCard("Letzte Ereignisse", (d.events || []).map(function (e) {
          return h("div", { class: "item" + (S.can("data.events") ? " link" : ""), onClick: S.can("data.events") ? function () { S.go("#/events"); } : null }, h("span", { class: "dot " + (e.critical ? "warn" : "on") }),
            h("div", { class: "meta" }, h("b", { text: e.title || e.type }), h("small", { text: S.ago(e.time) + (e.body ? " · " + String(e.body).slice(0, 80) : "") })));
        }), { icon: "bell", href: S.can("data.events") ? "#/events" : null, empty: "Keine Ereignisse." }));
        grid.appendChild(listCard("Offene Reports", (d.reports || []).map(function (r) {
          return h("div", { class: "item" + (S.can("data.reports") ? " link" : ""), onClick: S.can("data.reports") ? function () { S.go("#/moderation"); } : null }, icon("flag"),
            h("div", { class: "meta" }, h("b", { text: r.name }), h("small", { text: r.category + " · " + S.ago(r.created) })), S.badge(r.status === "OPEN" ? "Offen" : "In Prüfung", r.status === "OPEN" ? "danger" : "warn"));
        }), { icon: "flag", href: S.can("data.reports") ? "#/moderation" : null, empty: "Keine offenen Reports. 🎉" }));
      } else if (!d) grid.appendChild(S.lockedCard("Ereignisse & Reports", "data.admin-dashboard"));
      S.mount(h("div", { class: "stack" }, hero, kpis, grid));
    }
    render(data[0], data[1]);
    S.title("Dashboard", (S.health && S.health.name) || "", S.btn("", { kind: "ghost", size: "sm icon", icon: "refresh", title: "Aktualisieren", onClick: async function () { var n = await load(); if (alive()) render(n[0], n[1]); } }));
    S.every(15000, async function () { try { var n = await load(); if (alive()) render(n[0], n[1]); } catch (e) { /* keep last state */ } });
  };

  // ============================================================ Players
  P.players = async function (param, query, alive) {
    var state = { q: query.get("q") || "", filter: query.get("filter") || "all", offset: 0, rows: [], next: -1, total: 0, snapshot: "" };
    var list = h("div", { class: "list" }), more = h("div", { class: "center", style: { padding: "12px" } }), counter = h("span", { class: "muted small" });
    async function load(reset) {
      if (reset) { state.offset = 0; state.rows = []; state.snapshot = ""; S.clear(list).appendChild(h("div", { class: "item" }, h("div", { class: "skeleton grow", style: { height: "40px" } }))); }
      var r = await S.api("data/players", { query: { q: state.q, filter: state.filter, offset: state.offset, limit: 50, snapshot: reset ? "" : state.snapshot } });
      if (!alive()) return;
      state.rows = reset ? r.items || [] : state.rows.concat(r.items || []); state.total = r.total || 0; state.snapshot = r.snapshot || "";
      state.next = r.next == null ? -1 : r.next; state.offset = state.next;
      S.clear(list);
      if (!state.rows.length) list.appendChild(S.empty("users", "Keine Spieler gefunden", state.q ? "Andere Schreibweise versuchen." : "Für diesen Filter gibt es keine Spieler."));
      state.rows.forEach(function (p) {
        list.appendChild(h("div", { class: "item" + (S.can("data.profile") ? " link" : ""), onClick: S.can("data.profile") ? function () { S.go("#/player/" + p.id); } : null }, S.avatar(p.name),
          h("div", { class: "meta" }, h("b", { text: p.name }), h("small", { text: p.online ? "Jetzt online" : "Zuletzt " + S.ago(p.lastSeen) }), h("div", { class: "row wrap", style: { gap: "6px", marginTop: "6px" } }, playerBadges(p))),
          h("div", { class: "side hide-mobile" }, h("span", { class: "muted small", text: S.num(p.points) + " Punkte" })), S.can("data.profile") ? icon("chevRight", "chev") : null));
      });
      counter.textContent = S.num(state.total) + " Spieler";
      S.clear(more); if (state.next > 0) more.appendChild(S.btn("Mehr laden", { icon: "chevDown", onClick: function () { return load(false); } }));
    }
    var filters = [["all", "Alle"], ["online", "Online"], ["recent", "Zuletzt online"]];
    if (S.has("players-filters-v3")) filters.push(["flagged", "Auffällig"], ["new", "Neu"]);
    S.title("Spieler", "", counter);
    S.mount(h("div", { class: "stack" }, h("div", { class: "row wrap" }, S.searchBox("Name oder UUID suchen …", function (v) { state.q = v; load(true); }, state.q), S.segmented(filters, state.filter, function (v) { state.filter = v; load(true); })),
      S.card(null, { cls: "pad-0" }, list, more)));
    await load(true);
  };

  P.player = async function (id, query, alive) {
    if (!S.can("data.profile")) { S.mount(S.lockedPage("Profil gesperrt", "data.profile")); return; }
    var p = await S.api("data/profile", { query: { player: id } }); if (!alive()) return;
    S.title(p.name, "Spielerprofil", h("a", { class: "btn ghost sm", href: "#/players" }, icon("chevLeft"), h("span", { class: "hide-mobile", text: "Spieler" })));
    var head = S.card(null, {}, h("div", { class: "row wrap", style: { gap: "16px" } }, S.avatar(p.name, "lg"),
      h("div", { class: "grow" }, h("h2", { style: { margin: 0, fontSize: "22px" }, text: p.name }), h("div", { class: "mono muted tiny", text: p.id }), h("div", { class: "row wrap", style: { gap: "6px", marginTop: "8px" } }, playerBadges(p), p.staff ? S.badge(p.staff, "accent", "shieldCheck") : null, p.vpnWhitelisted ? S.badge("VPN-Ausnahme", "info") : null)),
      actionMenu(p)));
    var info = S.card("Profil", { icon: "user" }, S.kv([
      ["Erstmals gesehen", S.date(p.firstSeen)], ["Zuletzt gesehen", p.online ? "Jetzt online" : S.date(p.lastSeen)], ["Client", p.client === "BEDROCK" ? "Bedrock · " + (p.device || "Gerät unbekannt") : p.client === "JAVA" ? "Java" + (p.brand ? " · " + p.brand : "") : p.client],
      ["Version", p.version], ["Simplex-Punkte", S.num(p.points)], ["Spielzeit", p.activity ? S.dur(p.activity.playMillis) + " (AFK " + S.dur(p.activity.afkMillis) + ")" : null],
      ["Sitzungen", p.activity ? S.num(p.activity.sessions) : null], ["Geräte", p.deviceCount], ["Mögliche Alts", p.altCount], ["Frühere Namen", (p.previousNames || []).join(", ")],
      ["Letzter Ort", p.lastLocation ? fmtLoc(p.lastLocation) : null]]));
    var cards = [head];
    // 10.0.0: quick access to rank (LuckPerms) and access locks of this player.
    var quick = h("div", { class: "row wrap" },
      S.has("access-locks-v1") && S.can("data.access-locks") ? h("a", { class: "btn sm", href: "#/access-locks/" + p.id }, icon("lock"), "Rechte-Sperren") : null,
      S.has("luckperms-v1") && S.can("data.luckperms") ? h("a", { class: "btn sm", href: "#/luckperms/player/" + p.id }, icon("key"), "Rang & LuckPerms") : null);
    if (quick.children.length) cards.push(quick);
    var grid = h("div", { class: "grid two" }, info);
    if (p.live) {
      var l = p.live;
      grid.appendChild(S.card("Live", { icon: "activity", sub: l.world + " · " + Math.round(l.x) + " / " + Math.round(l.y) + " / " + Math.round(l.z) }, h("div", { class: "stack" },
        h("div", {}, h("div", { class: "row small" }, "Leben", h("span", { class: "right muted", text: Number(l.health).toFixed(1) + " / " + Number(l.maxHealth).toFixed(0) })), S.bar(l.health, l.maxHealth, 101)),
        h("div", {}, h("div", { class: "row small" }, "Hunger", h("span", { class: "right muted", text: l.food + " / 20" })), S.bar(l.food, 20, 101)),
        S.kv([["Level", l.level], ["Spielmodus", l.gamemode], ["Fliegen erlaubt", l.allowFlight ? "Ja" : "Nein"], ["Unverwundbar", l.invulnerable ? "Ja" : "Nein"]]))));
    }
    if ((p.warningEntries || []).length) grid.appendChild(S.card("Warnungen", { icon: "alert" }, h("div", { class: "list" }, p.warningEntries.map(function (w) { return h("div", { class: "item" }, h("div", { class: "meta" }, h("p", { text: String(w) }))); }))));
    if ((p.notes || []).length) grid.appendChild(S.card("Notizen", { icon: "edit" }, h("div", { class: "list" }, p.notes.map(function (n) { return h("div", { class: "item" }, h("div", { class: "meta" }, h("p", { text: String(n) }))); }))));
    cards.push(grid);
    var tabBody = h("div");
    var tabs = h("div", { class: "tabs" });
    [["devices", "Geräte", "data.devices"], ["alts", "Alt-Accounts", "data.alts"]].forEach(function (t, i) {
      var b = h("button", { class: i === 0 ? "on" : "", type: "button", text: t[1], disabled: !S.can(t[2]), title: S.can(t[2]) ? "" : "Keine Berechtigung: " + t[2] });
      b.addEventListener("click", function () { Array.prototype.forEach.call(tabs.children, function (c) { c.classList.remove("on"); }); b.classList.add("on"); loadTab(t[0]); });
      tabs.appendChild(b);
    });
    async function loadTab(kind) {
      S.clear(tabBody).appendChild(S.skeleton(2));
      try {
        var r = await S.api("data/" + kind, { query: { player: id } }); S.clear(tabBody);
        if (!(r.items || []).length) { tabBody.appendChild(S.empty(kind === "devices" ? "phone" : "users", "Keine Einträge")); return; }
        tabBody.appendChild(h("div", { class: "list" }, r.items.map(function (x) {
          return kind === "devices"
            ? h("div", { class: "item" }, h("div", { class: "avatar device sm" }, icon("phone")), h("div", { class: "meta" }, h("b", { text: x.name }), h("small", { text: [x.platform, x.os, x.version, x.input, "zuletzt " + S.ago(x.lastSeen)].filter(Boolean).join(" · ") })), x.blocked ? S.badge("Blockiert", "danger") : null)
            : h("div", { class: "item link", onClick: function () { S.go("#/player/" + x.id); } }, S.avatar(x.name, "sm"), h("div", { class: "meta" }, h("b", { text: x.name }), h("small", { text: (x.evidence || []).join ? x.evidence.join(" · ") : String(x.evidence || "") })), S.badge(x.level || String(x.score), x.safe ? "danger" : "warn"));
        })));
      } catch (e) { S.clear(tabBody).appendChild(S.notice(e.message, "danger")); }
    }
    cards.push(S.card(null, {}, tabs, tabBody));
    S.mount(h("div", { class: "stack" }, cards));
    if (S.can("data.devices")) loadTab("devices"); else tabBody.appendChild(S.notice("Keine Berechtigung für Gerätedaten.", "warn", "lock"));
  };
  function fmtLoc(l) { return typeof l === "string" ? l : [l.world, Math.round(l.x), Math.round(l.y), Math.round(l.z)].filter(function (v) { return v !== undefined; }).join(" · "); }
  function actionMenu(p) {
    var wrap = h("div", { class: "rel" });
    var b = S.btn("Aktionen", { icon: "sparkles", kind: "primary", onClick: async function () {
      var schema = await S.schema();
      var actions = schema.filter(function (a) { return (a.options || []).some(function (o) { return o.name === "player"; }); });
      var pop = h("div", { class: "menu-pop", style: { maxHeight: "60vh", overflow: "auto" } });
      actions.forEach(function (a) {
        var allowed = a.allowed !== false && S.can("action." + a.id);
        pop.appendChild(h("button", { type: "button", disabled: !allowed, title: allowed ? a.id : "Keine Berechtigung", onClick: function () { pop.remove(); S.openAction(a, { player: p.name }); } }, icon(allowed ? "chevRight" : "lock"), h("span", { text: a.description || a.name })));
      });
      if (!actions.length) pop.appendChild(h("div", { class: "muted small", style: { padding: "10px" }, text: "Keine Spieleraktionen verfügbar." }));
      wrap.appendChild(pop);
      setTimeout(function () { document.addEventListener("click", function off(e) { if (!pop.contains(e.target)) { pop.remove(); document.removeEventListener("click", off); } }); }, 0);
    } });
    wrap.appendChild(b); return wrap;
  }

  // ============================================================ Tickets
  var PRIORITY = { low: ["Niedrig", ""], normal: ["Normal", "info"], high: ["Hoch", "warn"], urgent: ["Dringend", "danger"] };
  var STATUS = { open: ["Offen", "danger"], answered: ["Beantwortet", "ok"], closed: ["Geschlossen", ""] };
  P.tickets = async function (param, query, alive) {
    var state = { status: query.get("status") || "active", q: "", offset: 0, rows: [] };
    var list = h("div", { class: "list" }), more = h("div", { class: "center", style: { padding: "12px" } }), summary = h("div", { class: "row wrap", style: { gap: "8px" } });
    async function load(reset) {
      if (reset) { state.offset = 0; state.rows = []; }
      var r = await S.api("data/tickets", { query: { status: state.status, q: state.q, offset: state.offset } }); if (!alive()) return;
      if (r.enabled === false) { S.mount(S.card(null, {}, S.empty("ticket", "Ticketsystem ist aus", "In den Einstellungen unter Tickets aktivieren."))); return; }
      state.rows = reset ? r.items : state.rows.concat(r.items); state.offset = r.next;
      var c = r.counts || {}; S.clear(summary).appendChild(S.badge((c.open || 0) + " offen", "danger")); summary.appendChild(S.badge((c.answered || 0) + " beantwortet", "ok"));
      if (c.urgent) summary.appendChild(S.badge(c.urgent + " dringend", "danger", "alert")); if (c.waitingDelivery) summary.appendChild(S.badge(c.waitingDelivery + " warten auf Zustellung", "info", "clock"));
      S.setBadge("tickets", c.open || 0);
      S.clear(list);
      if (!state.rows.length) list.appendChild(S.empty("ticket", "Keine Tickets", "Für diesen Filter gibt es keine Tickets."));
      state.rows.forEach(function (t) {
        var st = STATUS[t.status] || [t.status, ""], pr = PRIORITY[t.priority] || [t.priority, ""];
        list.appendChild(h("div", { class: "item link", onClick: function () { S.go("#/ticket/" + t.id); } },
          h("div", { class: "avatar device" }, h("span", { class: "mono", text: "#" + t.id })),
          h("div", { class: "meta" }, h("b", { text: t.subject || "(ohne Betreff)" }), h("small", { text: t.ownerName + " · " + t.category + " · " + S.ago(t.updated) + (t.claimedBy ? " · " + t.claimedBy : "") }), t.preview ? h("p", { class: "ellipsis", text: (t.lastAuthor ? t.lastAuthor + ": " : "") + t.preview }) : null),
          h("div", { class: "side" }, h("div", { class: "stack", style: { gap: "6px", justifyItems: "end" } }, S.badge(st[0], st[1]), t.priority !== "normal" ? S.badge(pr[0], pr[1]) : null, t.pending ? S.badge(t.pending + " ausstehend", "info") : null))));
      });
      S.clear(more); if (state.offset > 0) more.appendChild(S.btn("Mehr laden", { icon: "chevDown", onClick: function () { return load(false); } }));
    }
    S.title("Tickets", "", summary);
    S.mount(h("div", { class: "stack" }, h("div", { class: "row wrap" }, S.searchBox("Ticket, Spieler oder Kategorie …", function (v) { state.q = v; load(true); }),
      S.segmented([["active", "Aktiv"], ["open", "Offen"], ["answered", "Beantwortet"], ["urgent", "Dringend"], ["closed", "Geschlossen"], ["all", "Alle"]], state.status, function (v) { state.status = v; load(true); })),
      S.card(null, { cls: "pad-0" }, list, more)));
    await load(true);
    S.every(30000, function () { load(true).catch(function () {}); });
  };

  function attachmentView(a, url) {
    if (a.image || String(a.type || "").indexOf("image/") === 0) {
      var img = h("img", { alt: a.name || "Bild", loading: "lazy" });
      url().then(function (u) { img.src = u; img.addEventListener("click", function () { S.lightbox(u); }); }).catch(function () { img.replaceWith(S.badge("Bild nicht verfügbar", "warn")); });
      return img;
    }
    if (a.audio) { var au = h("audio", { controls: true, preload: "none" }); url().then(function (u) { au.src = u; }).catch(function () {}); return au; }
    return S.btn((a.name || "Datei") + (a.size ? " · " + S.bytes(a.size) : ""), { size: "sm", icon: "download", onClick: async function () { var u = await url(); var link = h("a", { href: u, download: a.name || "datei" }); document.body.appendChild(link); link.click(); link.remove(); } });
  }
  function filePicker(onFile) {
    var f = h("input", { type: "file", class: "hidden" });
    f.addEventListener("change", function () { if (f.files && f.files[0]) onFile(f.files[0]); f.value = ""; });
    return { input: f, open: function () { f.click(); } };
  }

  P.ticket = async function (id, query, alive) {
    if (!S.can("data.ticket")) { S.mount(S.lockedPage("Ticket gesperrt", "data.ticket")); return; }
    var t = await S.api("data/ticket", { query: { id: id } }); if (!alive()) return;
    function act(action, extra, label) {
      return S.btn(label, { size: "sm", icon: action === "close" ? "check" : action === "reopen" ? "refresh" : action === "claim" ? "user" : action === "delete" ? "trash" : "flag",
        kind: action === "delete" ? "danger" : "", disabled: !S.can("ticket." + action), title: S.can("ticket." + action) ? "" : "Keine Berechtigung: ticket." + action,
        onClick: async function () {
          if (action === "delete" && !(await S.confirm("Ticket löschen?", "Ticket #" + t.id + " und der Discord-Kanal werden entfernt.", "Löschen", true))) return;
          var r = await S.api("tickets", { body: Object.assign({ action: action, id: String(t.id) }, extra || {}) });
          S.toast(r.title || "Gespeichert", "ok", r.description); if (r.deleted) { S.go("#/tickets"); return; } S.reload();
        } });
    }
    var st = STATUS[t.status] || [t.status, ""], pr = PRIORITY[t.priority] || [t.priority, ""];
    var prio = h("select", { class: "input", style: { minHeight: "32px", height: "32px", padding: "0 10px", width: "auto" }, disabled: !S.can("ticket.priority"), "aria-label": "Priorität" },
      Object.keys(PRIORITY).map(function (k) { return h("option", { value: k, selected: k === t.priority, text: PRIORITY[k][0] }); }));
    prio.addEventListener("change", async function () { try { await S.api("tickets", { body: { action: "priority", id: String(t.id), priority: prio.value } }); S.toast("Priorität geändert"); } catch (e) { S.toast(e.message, "err"); } });
    S.title("Ticket #" + t.id, t.subject, h("a", { class: "btn ghost sm", href: "#/tickets" }, icon("chevLeft"), h("span", { class: "hide-mobile", text: "Tickets" })));
    var head = S.card(null, {}, h("div", { class: "stack", style: { gap: "12px" } },
      h("div", { class: "row wrap" }, h("h2", { class: "grow", style: { margin: 0, fontSize: "19px" }, text: t.subject || "(ohne Betreff)" }), S.badge(st[0], st[1]), S.badge(pr[0], pr[1])),
      h("div", { class: "muted small", text: t.ownerName + (t.ownerOnline ? " (online)" : "") + " · " + t.category + " · erstellt " + S.date(t.created) + (t.claimedBy ? " · übernommen von " + t.claimedBy : "") + (t.source ? " · via " + t.source : "") }),
      h("div", { class: "row wrap" }, t.status !== "closed" ? act("claim", null, "Übernehmen") : null, prio, t.status === "closed" ? act("reopen", null, "Wieder öffnen") : act("close", null, "Schließen"), S.full() ? act("delete", null, "Löschen") : null)));
    var msgs = h("div", { class: "msgs" });
    (t.items || []).forEach(function (m) {
      var staff = m.role === "staff" || m.role === "app" || m.source === "app";
      var bubble = h("div", { class: "bubble" }, h("div", { class: "by" }, h("span", { text: m.author || "?" }), h("time", { text: S.date(m.at) }), m.discarded ? S.badge("verworfen", "warn") : !m.delivered && staff ? S.badge("wird zugestellt", "info") : null), m.text ? h("p", { text: m.text }) : null);
      if ((m.attachments || []).length) bubble.appendChild(h("div", { class: "attachments" }, m.attachments.map(function (a) { return attachmentView(a, function () { return S.blobUrl("ticket-file", { ticket: t.id, id: a.id }); }); })));
      msgs.appendChild(h("div", { class: "msg" + (staff ? " mine" : "") }, S.avatar(m.author, "sm"), bubble));
    });
    if (!(t.items || []).length) msgs.appendChild(S.empty("chat", "Noch keine Nachrichten"));
    var text = h("textarea", { class: "input", placeholder: S.can("ticket.reply") ? "Antwort schreiben …" : "Keine Berechtigung zum Antworten", rows: 1, disabled: !S.can("ticket.reply") || t.status === "closed" });
    var mention = h("input", { type: "checkbox" });
    var picker = filePicker(async function (file) {
      if (file.size > 8 * 1024 * 1024) { S.toast("Datei größer als 8 MiB", "err"); return; }
      try { await S.api("tickets/upload", { body: file, type: file.type || "application/octet-stream", query: { id: t.id, name: file.name, text: text.value, mention: mention.checked } }); text.value = ""; S.toast("Anhang gesendet"); S.reload(); } catch (e) { S.toast(e.message, "err"); }
    });
    var send = S.btn("", { kind: "primary", icon: "send", title: "Senden", disabled: text.disabled, onClick: async function () {
      if (!text.value.trim()) return;
      var r = await S.api("tickets", { body: { action: "reply", id: String(t.id), text: text.value, mention: mention.checked } }); text.value = ""; S.toast(r.title || "Gesendet", "ok", r.description); S.reload();
    } });
    text.addEventListener("keydown", function (e) { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) send.click(); });
    var chat = S.card(null, { cls: "pad-0" }, h("div", { class: "chat", style: { height: "auto", minHeight: "0" } }, msgs,
      h("div", { class: "composer" }, picker.input, S.btn("", { kind: "ghost", icon: "clip", title: "Anhang", disabled: text.disabled, onClick: function () { picker.open(); } }), text,
        h("label", { class: "check small hide-mobile", title: "Spieler in Discord erwähnen" }, mention, "@"), send)));
    S.mount(h("div", { class: "stack" }, head, chat));
    msgs.scrollTop = msgs.scrollHeight;
  };

  // ============================================================ Staff chat & server chat
  var SOURCES = { app: ["App", "accent"], discord: ["Discord", "info"], player: ["Minecraft", "ok"], minecraft: ["Minecraft", "ok"], ingame: ["Minecraft", "ok"], console: ["Konsole", "warn"], web: ["Web", "accent"] };
  function dayLabel(ms) {
    var d = new Date(Number(ms)), today = new Date(), y = new Date(); y.setDate(today.getDate() - 1);
    if (d.toDateString() === today.toDateString()) return "Heute"; if (d.toDateString() === y.toDateString()) return "Gestern";
    return d.toLocaleDateString("de-DE", { weekday: "long", day: "2-digit", month: "long" });
  }
  function clock(ms) { return new Date(Number(ms)).toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" }); }
  function chatPage(opts) {
    return async function (param, query, alive) {
      var cursor = 0, me = "", msgs = h("div", { class: "msgs", role: "log", "aria-live": "polite" }), seen = {}, last = null;
      var text = h("textarea", { class: "input", rows: 1, placeholder: opts.canSend() ? "Nachricht an " + opts.title + " …" : "Keine Berechtigung zum Senden", disabled: !opts.canSend(), "aria-label": "Nachricht" });
      function grow() { text.style.height = "auto"; text.style.height = Math.min(160, text.scrollHeight + 2) + "px"; }
      text.addEventListener("input", grow);
      function append(m) {
        if (seen[m.id]) return; seen[m.id] = true;
        var at = Number(m.at || m.time) || Date.now(), author = m.author || m.name || "?", mine = opts.mine(m, me), src = SOURCES[String(m.source || "").toLowerCase()];
        if (!last || new Date(last.at).toDateString() !== new Date(at).toDateString()) { msgs.appendChild(h("div", { class: "day", text: dayLabel(at) })); last = null; }
        var grouped = last && last.author === author && last.source === m.source && at - last.at < 5 * 60e3;
        var bubble = h("div", { class: "bubble" },
          grouped ? null : h("div", { class: "by" }, h("span", { class: "name", text: author }), src ? h("span", { class: "src " + src[1], text: src[0] }) : null),
          m.text || m.message ? h("p", { text: m.text || m.message }) : null, h("time", { class: "stamp", text: clock(at), title: S.date(at) }));
        if ((m.attachments || []).length && opts.file) bubble.appendChild(h("div", { class: "attachments" }, m.attachments.map(function (a) { return attachmentView(a, function () { return S.blobUrl(opts.file, { id: a.id }); }); })));
        msgs.appendChild(h("div", { class: "msg" + (mine ? " mine" : "") + (grouped ? " grouped" : "") }, grouped ? h("div", { class: "avatar-gap" }) : S.avatar(author, "sm"), bubble));
        last = { author: author, source: m.source, at: at };
      }
      async function poll() {
        var r = await S.api(opts.data, { query: { after: cursor } }); if (!alive()) return;
        if (r.you) me = r.you;
        var stick = msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight < 80;
        if (msgs.querySelector(".empty") && (r.items || []).length) S.clear(msgs);
        (r.items || []).forEach(append); cursor = Number(r.cursor) || cursor;
        if (!msgs.children.length) msgs.appendChild(S.empty("chat", "Noch keine Nachrichten", opts.emptyText));
        if (stick) msgs.scrollTop = msgs.scrollHeight;
        if (opts.status) opts.status(r);
      }
      var send = S.btn("", { kind: "primary", icon: "send", title: "Senden", disabled: !opts.canSend(), onClick: async function () { if (!text.value.trim()) return; await opts.send(text.value); text.value = ""; grow(); await poll(); msgs.scrollTop = msgs.scrollHeight; } });
      text.addEventListener("keydown", function (e) {
        if (e.key !== "Enter" || e.isComposing) return;
        var enterSends = !(S.prefs && S.prefs.chatEnter === "newline");
        if (enterSends ? !e.shiftKey : (e.metaKey || e.ctrlKey)) { e.preventDefault(); send.click(); }
      });
      var tools = [];
      if (opts.upload && opts.canSend()) {
        var picker = filePicker(async function (file) {
          if (file.size > 8 * 1024 * 1024) { S.toast("Datei größer als 8 MiB", "err"); return; }
          try { await S.api(opts.upload, { body: file, type: file.type || "application/octet-stream", query: { name: file.name, text: text.value } }); text.value = ""; await poll(); } catch (e) { S.toast(e.message, "err"); }
        });
        tools.push(picker.input, S.btn("", { kind: "ghost", icon: "clip", title: "Bild oder Datei senden", onClick: function () { picker.open(); } }));
      }
      var statusLine = h("div", { class: "row wrap", style: { gap: "8px" } });
      opts.status = opts.statusRender ? function (r) { S.clear(statusLine); S.add(statusLine, opts.statusRender(r)); } : null;
      S.title(opts.title, "", [statusLine, opts.headerActions ? opts.headerActions() : null]);
      S.mount(S.card(null, { cls: "pad-0 chat-card" }, h("div", { class: "chat" }, msgs, h("div", { class: "composer" }, opts.composerExtra ? opts.composerExtra() : null, tools, text, send))));
      await poll(); msgs.scrollTop = msgs.scrollHeight;
      S.every(opts.interval || 4000, function () { poll().catch(function () {}); });
    };
  }
  P["staff-chat"] = chatPage({ title: "Staff-Chat", data: "data/staff-chat", file: "staff-chat-file", upload: "staff-chat/upload", interval: 4000,
    canSend: function () { return S.can("staff-chat.send"); }, mine: function (m, me) { return me && m.author === me; },
    send: function (text) { return S.api("staff-chat", { body: { text: text, pings: [] } }); },
    emptyText: "Nachrichten aus dem Spiel (/staffmsg), Discord und der App erscheinen hier.",
    statusRender: function (r) { return [r.discord ? S.badge("Discord verbunden", "ok", "link") : S.badge("Nur App & Spiel", "", "info"), r.lastError ? S.badge("Discord-Fehler", "danger", "alert") : null]; } });
  // Public server chat: the sender name is public in Minecraft – never the login name. Default: public name or "Server".
  function chatSender() { var stored = null; try { stored = sessionStorage.getItem("simplex-chat-name"); } catch (e) { stored = null; } return stored || (S.account && S.account.publicName) || "Server"; }
  P.chat = chatPage({ title: "Serverchat", data: "data/chat", interval: 3000,
    canSend: function () { return S.can("chat.send") && S.full(); }, mine: function (m) { return m.source === "app"; },
    send: function (text) { return S.api("chat", { body: { name: chatSender(), message: text } }); },
    emptyText: "Der öffentliche Minecraft-Chat erscheint hier live.",
    composerExtra: function () {
      if (!(S.can("chat.send") && S.full())) return null;
      var chip = h("button", { type: "button", class: "sender-chip", title: "Absendername im Spiel ändern" }, icon("user"), h("span", { text: chatSender() }));
      chip.addEventListener("click", async function () { var v = await S.prompt("Absender im Serverchat", "Name, den Spieler sehen (1–32 Zeichen)", chatSender(), "Übernehmen"); if (v === null) return; v = v.trim() || "Server"; try { sessionStorage.setItem("simplex-chat-name", v); } catch (e) { /* ignore */ } chip.lastChild.textContent = v; });
      return chip;
    },
    headerActions: function () {
      return S.can("chat.clear") && S.full() ? S.btn("", { kind: "ghost", size: "sm icon", icon: "trash", title: "Verlauf leeren", onClick: async function () { if (await S.confirm("Chatverlauf leeren?", "Nur die gespeicherte App-Ansicht wird geleert.", "Leeren", true)) { await S.api("chat/clear", { body: {} }); S.reload(); } } }) : null;
    } });

  // ============================================================ Admin log
  P["admin-log"] = async function (admin, query, alive) {
    var CAT = { "": "Alle", moderation: "Moderation", player: "Spieler", server: "Server", settings: "Einstellungen", access: "Zugänge", view: "Ansichten", other: "Sonstiges" };
    if (!admin) {
      var o = await S.api("data/admin-log"); if (!alive()) return;
      S.setBadge("adminLog", o.unread || 0);
      var mode = o.settings ? o.settings.mode : "";
      S.title("Admin-Log", o.unread ? o.unread + " ungelesen" : (S.adminLogModeText ? S.adminLogModeText(mode) : ""), [
        o.settings && S.adminLogSettings ? S.btn("Filter", { size: "sm", icon: "filter", title: "Was zählt als ungelesen?", onClick: function () { S.adminLogSettings(o); } }) : null,
        S.btn("Alle gelesen", { size: "sm", icon: "check", disabled: !o.unread, onClick: async function () { await S.api("admin-log/read", { body: { admin: "" } }); S.reload(); } })]);
      S.mount(S.card(null, { cls: "pad-0" }, h("div", { class: "list" }, (o.admins || []).length ? o.admins.map(function (a) {
        var last = a.last || {};
        return h("div", { class: "item link", onClick: function () { S.go("#/admin-log/" + encodeURIComponent(a.admin)); } }, S.avatar(a.name),
          h("div", { class: "meta" }, h("b", {}, a.name + (a.self ? " (du)" : ""), a.staff === false ? h("span", { class: "tag", text: "Spieler" }) : a.staff ? h("span", { class: "tag accent", text: "Team" }) : null), h("small", { text: (last.action || "") + (last.detail ? " · " + last.detail : "") }), h("small", { text: S.ago(last.time) + " · " + a.total + " Einträge · " + (a.sources || []).join(", ") + (a.counted === false && !a.self ? " · zählt nicht" : "") })),
          h("div", { class: "side" }, a.online ? h("span", { class: "dot on", title: "online" }) : null, a.unread ? S.badge(String(a.unread), "accent") : null, icon("chevRight", "chev")));
      }) : S.empty("list", "Noch keine Admin-Aktivität"))));
      return;
    }
    var state = { q: "", category: "", offset: 0 }, list = h("div", { class: "list" }), more = h("div", { class: "center", style: { padding: "12px" } });
    async function load(reset) {
      if (reset) state.offset = 0;
      var r = await S.api("data/admin-log", { query: { admin: admin, q: state.q, category: state.category, offset: state.offset } }); if (!alive()) return;
      if (reset) S.clear(list);
      (r.items || []).forEach(function (e) {
        list.appendChild(h("div", { class: "item" }, h("span", { class: "dot " + (e.unread ? "on" : "off") }), h("div", { class: "meta" }, h("b", { text: e.action }), e.detail ? h("p", { text: e.detail }) : null,
          h("small", { text: S.date(e.time) + (e.targetName ? " · " + e.targetName : "") + (e.location ? " · " + (e.location.world || "") + " " + [e.location.x, e.location.y, e.location.z].map(Math.round).join("/") : "") })), S.badge(CAT[e.category] || e.category)));
      });
      if (!list.children.length) list.appendChild(S.empty("list", "Keine Einträge"));
      state.offset = r.next; S.clear(more); if (r.next > 0) more.appendChild(S.btn("Mehr laden", { icon: "chevDown", onClick: function () { return load(false); } }));
    }
    S.title("Admin-Log", admin, h("a", { class: "btn ghost sm", href: "#/admin-log" }, icon("chevLeft"), h("span", { class: "hide-mobile", text: "Alle Admins" })));
    S.mount(h("div", { class: "stack" }, h("div", { class: "row wrap" }, S.searchBox("Aktion oder Detail suchen …", function (v) { state.q = v; load(true); }),
      S.segmented(Object.keys(CAT).map(function (k) { return [k, CAT[k]]; }), "", function (v) { state.category = v; load(true); })), S.card(null, { cls: "pad-0" }, list, more)));
    await load(true);
    S.api("admin-log/read", { body: { admin: admin } }).catch(function () {});
  };

  // ============================================================ Events
  P.events = async function (param, query, alive) {
    var state = { q: "", offset: 0 }, list = h("div", { class: "list" }), more = h("div", { class: "center", style: { padding: "12px" } });
    var canAck = S.can("action.events.ack"), canDelete = S.can("action.events.delete") && S.full();
    async function load(reset) {
      if (reset) state.offset = 0;
      var r = await S.api("data/events", { query: { q: state.q, offset: state.offset } }); if (!alive()) return;
      if (reset) S.clear(list);
      (r.items || []).forEach(function (e) {
        var acked = e.acknowledged || e.ack || (e.acks && e.acks.length);
        list.appendChild(h("div", { class: "item" }, h("div", { class: "avatar device sm" }, icon(e.critical ? "alert" : "bell")),
          h("div", { class: "meta" }, h("b", { text: e.title || e.type }), e.body ? h("p", { text: e.body }) : null, h("small", { text: S.date(e.time) + " · " + e.type + (e.note ? " · Notiz: " + e.note : "") })),
          h("div", { class: "side" }, e.critical ? S.badge("Kritisch", "danger") : null,
            canAck && !acked ? S.btn("", { size: "sm icon", icon: "check", title: "Bestätigen", onClick: async function () { await S.runAction("events.ack", { id: String(e.id) }, { skipConfirm: true }); load(true); } }) : null,
            canDelete ? S.btn("", { size: "sm icon", kind: "ghost", icon: "trash", title: "Löschen", onClick: async function () { if (await S.runAction("events.delete", { id: String(e.id) }, { danger: true, title: "Ereignis löschen?" })) load(true); } }) : null)));
      });
      if (!list.children.length) list.appendChild(S.empty("bell", "Keine Ereignisse"));
      state.offset = r.next; S.clear(more); if (r.next > 0) more.appendChild(S.btn("Mehr laden", { icon: "chevDown", onClick: function () { return load(false); } }));
    }
    S.title("Ereignisse");
    S.mount(h("div", { class: "stack" }, h("div", { class: "row" }, S.searchBox("Ereignisse durchsuchen …", function (v) { state.q = v; load(true); })), S.card(null, { cls: "pad-0" }, list, more)));
    await load(true);
  };

  // ============================================================ Moderation
  P.moderation = async function (param, query, alive) {
    var body = h("div"), tab = query.get("tab") || "reports";
    var tabs = h("div", { class: "tabs" });
    [["reports", "Reports", "data.reports"], ["bans", "Banns", "data.bans"]].forEach(function (t) {
      var b = h("button", { type: "button", class: t[0] === tab ? "on" : "", text: t[1], disabled: !S.can(t[2]), title: S.can(t[2]) ? "" : "Keine Berechtigung: " + t[2] });
      b.addEventListener("click", function () { Array.prototype.forEach.call(tabs.children, function (c) { c.classList.remove("on"); }); b.classList.add("on"); tab = t[0]; show(); });
      tabs.appendChild(b);
    });
    var source = "internal", q = "", reportFilter = "open";
    async function show() {
      S.clear(body).appendChild(S.skeleton(3));
      try {
        if (tab === "reports") {
          var r = await S.api("data/reports", { query: { q: q } }); if (!alive()) return; S.clear(body);
          var canEdit = S.can("action.reports.update");
          var all = r.items || [], counts = { open: 0, done: 0 };
          all.forEach(function (x) { if (x.status === "OPEN" || x.status === "IN_REVIEW") counts.open++; else counts.done++; });
          var items = all.filter(function (x) { return reportFilter === "all" || (reportFilter === "open" ? x.status === "OPEN" || x.status === "IN_REVIEW" : x.status === "RESOLVED" || x.status === "REJECTED"); });
          body.appendChild(h("div", { class: "row wrap", style: { marginBottom: "10px" } }, S.segmented([["open", "Offen (" + counts.open + ")"], ["done", "Erledigt (" + counts.done + ")"], ["all", "Alle"]], reportFilter, function (v) { reportFilter = v; show(); })));
          body.appendChild(S.card(null, { cls: "pad-0" }, h("div", { class: "list" }, items.length ? items.map(function (x) {
            var sel = h("select", { class: "input", style: { width: "auto", minHeight: "32px", height: "32px", padding: "0 8px" }, disabled: !canEdit, "aria-label": "Status" },
              [["OPEN", "Offen"], ["IN_REVIEW", "In Prüfung"], ["RESOLVED", "Erledigt"], ["REJECTED", "Abgelehnt"]].map(function (o) { return h("option", { value: o[0], selected: o[0] === x.status, text: o[1] }); }));
            sel.addEventListener("change", async function () {
              var note = await S.prompt("Report #" + x.id + " aktualisieren", "Interne Notiz (optional)", x.note || "", "Weiter");
              if (note === null) { sel.value = x.status; return; }
              try { if (await S.runAction("reports.update", { id: x.id, status: sel.value, note: note, expected: x.revision })) show(); else sel.value = x.status; } catch (e) { S.toast(e.message, "err"); sel.value = x.status; }
            });
            var noteBtn = S.btn("", { size: "sm icon", kind: "ghost", icon: "edit", title: "Interne Notiz bearbeiten", disabled: !canEdit, onClick: async function () {
              var note = await S.prompt("Notiz zu Report #" + x.id, "Interne Notiz (sieht nur das Team)", x.note || "", "Speichern"); if (note === null) return;
              if (await S.runAction("reports.update", { id: x.id, status: x.status, note: note, expected: x.revision })) show();
            } });
            var STATUS = { OPEN: ["Offen", "warn"], IN_REVIEW: ["In Prüfung", "info"], RESOLVED: ["Erledigt", "ok"], REJECTED: ["Abgelehnt", ""] };
            return h("div", { class: "item stackable" }, icon("flag"), h("div", { class: "meta" }, h("div", { class: "row wrap", style: { gap: "6px" } }, x.player && S.can("data.profile") ? h("a", { href: "#/player/" + x.player, text: x.name }) : h("b", { text: x.name }), S.badge(x.category || "Report"), S.badge((STATUS[x.status] || [x.status])[0], (STATUS[x.status] || [])[1])),
              h("p", { text: x.details || "" }), x.note ? h("p", { class: "small", text: "Notiz: " + x.note }) : null, h("small", { text: "#" + x.id + " · von " + x.reporter + " · " + S.date(x.created) + (x.handledBy ? " · bearbeitet von " + x.handledBy : "") })),
              h("div", { class: "side wrap" }, sel, noteBtn, x.player && S.has("access-locks-v1") && S.can("data.access-locks") ? h("a", { class: "btn sm icon ghost", href: "#/access-locks/" + x.player, title: "Rechte-Sperren" }, icon("lock")) : null));
          }) : S.empty("flag", "Keine Reports"))));
        } else {
          // 10.0.0: LiteBans has its own endpoint; Minecraft profile/IP bans are filtered server-side (older Suites: one "minecraft" list).
          var bans = source === "litebans" ? await S.api("data/litebans", { query: { q: q } })
            : await S.api("data/bans", { query: { source: /^minecraft-/.test(source) && !S.has("bans-sources-v2") ? "minecraft" : source, q: q } }); if (!alive()) return; S.clear(body);
          if (/^minecraft-/.test(source) && !S.has("bans-sources-v2")) bans.items = (bans.items || []).filter(function (b) { return b.source === source; });
          var canRemove = S.can("action.bans.remove");
          body.appendChild(h("div", { class: "stack" }, S.segmented([["internal", "Suite"], ["client", "Client-Sperren"], ["minecraft-profile", "Minecraft"], ["minecraft-ip", "IP"], ["litebans", "LiteBans"]], source, function (v) { source = v; show(); }),
            bans.available === false ? S.notice(bans.message || "LiteBans ist nicht verfügbar.", "warn", "info") : null,
            S.card(null, { cls: "pad-0" }, h("div", { class: "list" }, (bans.items || []).length ? bans.items.map(function (b) {
              return h("div", { class: "item" }, S.avatar(b.name, "sm"), h("div", { class: "meta" }, h("b", { text: b.name }), h("p", { text: b.reason || "" }), h("small", { text: (b.actor ? "von " + b.actor + " · " : "") + (b.created ? S.date(b.created) : "") + (b.until ? " · bis " + S.date(b.until) : " · dauerhaft") })),
                S.btn("Aufheben", { size: "sm", icon: "unlock", disabled: !canRemove, title: canRemove ? "" : "Keine Berechtigung", onClick: async function () { if (await S.runAction("bans.remove", { source: b.source || source, id: b.id, expected: b.revision || "" }, { title: "Sperre aufheben?" })) show(); } }));
            }) : S.empty("gavel", "Keine Sperren"))),
            S.can("action.player.punish") ? h("div", { class: "row wrap" }, S.btn("Spieler bannen oder muten", { icon: "plus", onClick: async function () { var acts = await S.schema(); var a = acts.filter(function (x) { return x.id === "player.punish"; })[0]; if (a) S.openAction(a, { type: "tempban" }); } })) : null));
        }
      } catch (e) { S.clear(body).appendChild(S.errorView(e)); }
    }
    S.title("Moderation");
    S.mount(h("div", { class: "stack" }, h("div", { class: "row" }, S.searchBox("Name oder Grund …", function (v) { q = v; show(); })), S.card(null, {}, tabs, body)));
    if (!S.can("data.reports") && S.can("data.bans")) tab = "bans";
    await show();
  };

  // ============================================================ Statistics
  P.statistics = async function (param, query, alive) {
    var s = await S.api("data/statistics"); if (!alive()) return;
    function dist(title, map, ic) {
      var entries = Object.keys(map || {}).map(function (k) { return [k, map[k]]; }).sort(function (a, b) { return b[1] - a[1]; }).slice(0, 10);
      var total = entries.reduce(function (n, e) { return n + e[1]; }, 0) || 1;
      return S.card(title, { icon: ic }, entries.length ? h("div", { class: "stack", style: { gap: "12px" } }, entries.map(function (e) {
        return h("div", {}, h("div", { class: "row small" }, h("span", { class: "ellipsis grow", text: e[0] }), h("span", { class: "muted", text: S.num(e[1]) + " · " + Math.round(e[1] / total * 100) + " %" })), S.bar(e[1], entries[0][1], 101));
      })) : S.empty("chart", "Keine Daten"));
    }
    S.title("Statistiken", "Stand " + S.date(s.at));
    S.mount(h("div", { class: "stack" },
      h("div", { class: "grid kpis" }, tile("Spielerprofile", S.num(s.profiles), { icon: "users" }), tile("Aktiv (24 h)", S.num(s.recent24h), { icon: "activity", tone: "g" }), tile("Neu (24 h)", S.num(s.new24h), { icon: "sparkles", tone: "v" }),
        tile("Spielzeit gesamt", S.dur(s.playMillis), { icon: "clock" }), tile("Sitzungen", S.num(s.sessions), { icon: "list" }), tile("Simplex-Punkte", S.num(s.points), { icon: "star", tone: "y" }),
        tile("Warnungen", S.num(s.warnings), { icon: "alert", tone: "y" }), tile("Banns / Mutes", S.num(s.bans) + " / " + S.num(s.mutes), { icon: "gavel", tone: "r" })),
      h("div", { class: "grid three" }, dist("Plattformen", s.platforms, "devices"), dist("Versionen", s.versions, "box"), dist("Bedrock-Geräte", s.models, "phone"))));
  };

  // ============================================================ Worlds
  P.worlds = async function (param, query, alive) {
    var r = await S.api("data/worlds"); if (!alive()) return;
    var canEdit = S.can("action.world.update") && S.full();
    S.title("Welten", (r.items || []).length + " geladen");
    S.mount(h("div", { class: "grid cards" }, (r.items || []).map(function (w) {
      function set(field, value, label) { return S.btn(label, { size: "sm", disabled: !canEdit, title: canEdit ? "" : "Keine Berechtigung", onClick: async function () { if (await S.runAction("world.update", { world: w.name, field: field, value: String(value) })) S.reload(); } }); }
      var env = { NORMAL: "Oberwelt", NETHER: "Nether", THE_END: "End" }[w.environment] || w.environment;
      return S.card(w.name, { icon: "globe", sub: env + " · Tag " + S.num(w.days) }, h("div", { class: "stack", style: { gap: "12px" } },
        S.kv([["Spieler", w.players], ["Chunks geladen", S.num(w.chunks)], ["Schwierigkeit", w.difficulty], ["Wetter", w.thunder ? "Gewitter" : w.storm ? "Regen" : "Klar"], ["PvP", w.pvp ? "An" : "Aus"], ["Autosave", w.autosave ? "An" : "Aus"], ["Weltgrenze", S.num(Math.round(w.border))]]),
        h("div", { class: "row wrap", style: { gap: "6px" } }, set("time", 1000, "☀ Tag"), set("time", 13000, "☾ Nacht"), set("weather", "clear", "Klar"), set("weather", "rain", "Regen"), set("pvp", !w.pvp, w.pvp ? "PvP aus" : "PvP an"))));
    })));
  };

  // ============================================================ Plugins
  P.plugins = async function (param, query, alive) {
    var r = await S.api("data/plugins"); if (!alive()) return;
    var rows = (r.items || []).slice().sort(function (a, b) { return a.name.localeCompare(b.name); });
    var tbody = h("tbody");
    function render(q) {
      S.clear(tbody);
      rows.filter(function (p) { return !q || p.name.toLowerCase().indexOf(q.toLowerCase()) >= 0; }).forEach(function (p) {
        tbody.appendChild(h("tr", {}, h("td", { class: "primary" }, h("div", { class: "row" }, icon("puzzle"), h("b", { text: p.name }))), h("td", { "data-label": "Version", class: "mono", text: p.version }),
          h("td", { "data-label": "Status" }, p.enabled ? S.badge("Aktiv", "ok") : S.badge("Aus", "danger")), h("td", { "data-label": "Autor", class: "muted small", text: (p.authors || []).join(", ") })));
      });
    }
    S.title("Plugins", rows.length + " installiert");
    S.mount(h("div", { class: "stack" }, S.searchBox("Plugin suchen …", render), S.card(null, { cls: "pad-0" }, h("div", { class: "table-wrap" }, h("table", { class: "table responsive" }, h("thead", {}, h("tr", {}, h("th", { text: "Plugin" }), h("th", { text: "Version" }), h("th", { text: "Status" }), h("th", { text: "Autoren" }))), tbody)))));
    render("");
  };

  S.deviceIcon = deviceIcon; S.deviceName = deviceName;
})();
